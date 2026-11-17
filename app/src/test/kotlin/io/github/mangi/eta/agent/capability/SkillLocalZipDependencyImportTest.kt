package io.github.mangi.eta.agent.capability

import io.github.mangi.eta.agent.skill.SkillInstallErrorCode
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.skillInstallerWorkRoot
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SkillLocalZipDependencyImportTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SkillRuntime.resetForTests()
        File(context.filesDir, ".eta-skill-installer").deleteRecursively()
        File(context.filesDir, "capability-center").deleteRecursively()
        File(context.filesDir, "skills").deleteRecursively()
    }

    @Test
    fun parentJournalExistsBeforeFirstDependencyAndSecondFailureRollsBackFirst() = runBlocking {
        val baseZip = skillZip("base", "---\nname: base\ndescription: Base dependency.\n---\n")
        val failingZip = skillZip("failing", "---\nname: failing\ndescription: Failing dependency.\n---\n")
        val baseDigest = sha256(baseZip)
        val failingDigest = sha256(failingZip)
        val catalog = JSONObject()
            .put("schema", "eta-skill-catalog/v1")
            .put(
                "skills",
                JSONArray()
                    .put(catalogEntry("base", baseDigest, "https://skills.example/base.zip"))
                    .put(catalogEntry("failing", failingDigest, "https://skills.example/failing.zip")),
            )
        var parentObservedBeforeBase = false
        var parentJournalObservation = ""
        var baseInstallStarted = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            if (path == "/catalog.json") {
                return@addInterceptor response(request, catalog.toString().toByteArray(), "application/json")
            }
            if (path == "/base.zip") return@addInterceptor response(request, baseZip, "application/zip")
            // 首个依赖已开始安装后才模拟第二个依赖安装失败，覆盖真实回滚路径。
            if (path == "/failing.zip" && baseInstallStarted) {
                return@addInterceptor Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Injected failure")
                    .body(ByteArray(0).toResponseBody("application/zip".toMediaType()))
                    .build()
            }
            response(request, failingZip, "application/zip")
        }.build()
        val market = SkillMarketService(context, client) { dependencyId ->
            if (dependencyId == "base") {
                baseInstallStarted = true
                val workRoot = skillInstallerWorkRoot(File(context.filesDir, "skills"))
                parentJournalObservation = workRoot.absolutePath + " -> " + workRoot.listFiles().orEmpty().joinToString { child ->
                    child.name + "(journal=" + File(child, io.github.mangi.eta.agent.skill.JOURNAL_FILE_NAME).isFile + ")"
                }
                parentObservedBeforeBase = workRoot.listFiles().orEmpty().any { child ->
                    child.name.startsWith("dependency-operation-") &&
                        File(child, io.github.mangi.eta.agent.skill.JOURNAL_FILE_NAME).isFile
                }
            }
        }
        val index = SkillRuntime.createIndexService(context)

        try {
            market.saveSources(listOf(httpSource()))
            val result = market.installLocalZip(
                openStream = {
                    skillZip(
                        "atomic-root",
                        "---\nname: atomic-root\ndescription: Root.\nskill-dependencies: [base, failing]\n---\n",
                    ).inputStream()
                },
                replaceUserSkill = false,
                expectedReplacementId = null,
                expectedArchiveSha256 = null,
            )

            assertTrue(result.toString(), result is SkillInstallResult.Failure)
            assertTrue(
                "parent journal must exist before first dependency write: " +
                    parentJournalObservation + "; result=" + result,
                parentObservedBeforeBase,
            )
            assertFalse(File(context.filesDir, "skills/base").exists())
            assertFalse(File(context.filesDir, "skills/failing").exists())
            assertFalse(File(context.filesDir, "skills/atomic-root").exists())
            assertTrue(index.dependentsOf("base").isEmpty())
            assertFalse(File(context.filesDir, "skills/base/SKILL.md").exists())
        } finally {
            market.close()
        }
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
        SkillRuntime.resetForTests()
    }

    @Test
    fun installsVerifiedCatalogDependencyAndFailsClosedWhenSourceIsMissing() = runBlocking {
        val dependencyZip = skillZip("base", "---\nname: base\ndescription: Base dependency.\n---\n")
        val dependencyDigest = sha256(dependencyZip)
        val catalog = JSONObject()
            .put("schema", "eta-skill-catalog/v1")
            .put(
                "skills",
                JSONArray().put(
                    JSONObject()
                        .put("id", "base")
                        .put("name", "Base")
                        .put("version", "1.0.0")
                        .put("archiveUrl", "https://skills.example/base.zip")
                        .put("archiveSha256", dependencyDigest)
                        .put("dependencies", JSONArray()),
                ),
            )
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (body, type) = if (request.url.encodedPath == "/catalog.json") {
                catalog.toString().toByteArray() to "application/json"
            } else {
                dependencyZip to "application/zip"
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody(type.toMediaType()))
                .build()
        }.build()
        val market = SkillMarketService(context, client)

        try {
            market.saveSources(listOf(httpSource()))
            val imported = market.installLocalZip(
                openStream = { skillZip("root-skill", "---\nname: root-skill\ndescription: Root skill.\nskill-dependencies: base\n---\n").inputStream() },
                replaceUserSkill = false,
                expectedReplacementId = null,
                expectedArchiveSha256 = null,
            )

            assertTrue(imported.toString(), imported is SkillInstallResult.Success)
            assertTrue(File(context.filesDir, "skills/base/SKILL.md").isFile)
            assertTrue(File(context.filesDir, "skills/root-skill/SKILL.md").isFile)
            assertEquals(setOf("root-skill"), SkillRuntime.createIndexService(context).dependentsOf("base"))

            market.saveSources(emptyList())
            val rejected = market.installLocalZip(
                openStream = {
                    skillZip(
                        "root-without-source",
                        "---\nname: root-without-source\ndescription: Root skill.\nskill-dependencies: missing-source\n---\n",
                    ).inputStream()
                },
                replaceUserSkill = false,
                expectedReplacementId = null,
                expectedArchiveSha256 = null,
            ) as SkillInstallResult.Failure

            assertEquals(SkillInstallErrorCode.DEPENDENCY_SETUP_REQUIRED, rejected.error.code)
            assertTrue(rejected.error.message.contains("没有可用来源"))
            assertFalse(File(context.filesDir, "skills/root-without-source").exists())
        } finally {
            market.close()
        }
    }


    private fun httpSource() = SkillSourceDefinition(
        id = "test-catalog",
        name = "Test catalog",
        type = SkillSourceType.HTTP_CATALOG,
        location = "https://skills.example/catalog.json",
        trust = SkillSourceTrust.CUSTOM,
    )

    private fun catalogEntry(id: String, digest: String, archiveUrl: String) = JSONObject()
        .put("id", id)
        .put("name", id)
        .put("version", "1.0.0")
        .put("archiveUrl", archiveUrl)
        .put("archiveSha256", digest)
        .put("dependencies", JSONArray())

    private fun response(request: okhttp3.Request, bytes: ByteArray, type: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bytes.toResponseBody(type.toMediaType()))
            .build()

    private fun skillZip(id: String, markdown: String): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("SKILL.md"))
            zip.write(markdown.toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
