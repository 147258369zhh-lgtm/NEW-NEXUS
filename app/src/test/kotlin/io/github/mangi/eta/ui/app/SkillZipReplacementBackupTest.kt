package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.SkillVersionService
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SkillZipReplacementBackupTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SkillRuntime.resetForTests()
        File(context.filesDir, "capability-center").deleteRecursively()
        File(context.filesDir, "skills").deleteRecursively()
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
        SkillRuntime.resetForTests()
    }

    @Test
    fun confirmedZipReplacementCreatesVersionBeforeInstalling() = runBlocking {
        val original = skillZip("replace-demo", "Original body")
        val installer = SkillRuntime.createPackageInstaller(context)
        assertTrue(installer.installLocalZip({ original.inputStream() }) is
            io.github.mangi.eta.agent.skill.SkillInstallResult.Success)
        val replacement = skillZip("replace-demo", "Replacement body")

        val outcome = CoreSkillZipImportGateway(context).installLocalZip(
            openStream = { replacement.inputStream() },
            replaceUserSkill = true,
            expectedReplacementId = "replace-demo",
            expectedArchiveSha256 = sha256(replacement),
        )

        assertTrue(outcome is SkillZipImportOutcome.Success)
        val versions = SkillVersionService(context).listForSkill("replace-demo")
        assertEquals(1, versions.size)
        assertTrue(
            File(context.filesDir, "skills/replace-demo/SKILL.md")
                .readText()
                .contains("Replacement body"),
        )
    }

    private fun skillZip(skillId: String, body: String): ByteArray {
        val skill = """
            ---
            name: $skillId
            description: Replacement backup test.
            ---

            # $skillId

            $body
        """.trimIndent().encodeToByteArray()
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("SKILL.md"))
                zip.write(skill)
                zip.closeEntry()
            }
        }.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
