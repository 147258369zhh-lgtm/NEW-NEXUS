package io.github.mangi.eta.agent.skill

import io.github.mangi.eta.data.db.EtaDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SkillDependencyAdmissionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }

    @Test
    fun dependencyAdmissionOrdersVerifiedInstallsAndFailsClosedOnConflicts() {
        val digest = "a".repeat(64)
        val parsed = SkillDependencyParser.parseDeclarations(
            mapOf(
                "dependencies" to
                    "root, bash, skill:base@^1.2.0?source=official&sha256=" + digest,
            ),
            emptyMap(),
        )
        assertEquals(listOf("base"), parsed.map { it.id })
        assertNull(parsed.single().declarationProblem)
        assertEquals("^1.2.0", parsed.single().versionConstraint)
        assertEquals("official", parsed.single().sourceId)
        assertEquals(digest, parsed.single().archiveSha256)

        val catalog = mapOf(
            "base" to listOf(entry("base", "1.4.0")),
            "leaf" to listOf(
                entry(
                    "leaf",
                    "1.0.0",
                    declarations = listOf(spec("base", "^1.2.0", "official")),
                ),
            ),
        )
        val ready = resolver(catalog).resolve(
            "root",
            listOf(spec("leaf"), spec("base", "^1.2.0", "official")),
        ) as SkillDependencyAdmission.Ready<String>
        assertEquals(listOf("base", "leaf"), ready.installOrder.map { it.skillId })
        assertEquals(setOf("base", "leaf"), ready.edges["root"])
        assertTrue(ready.reusedIds.isEmpty())

        val incompatible = resolver(
            mapOf("base" to listOf(entry("base", "1.0.0"))),
        ).resolve("root", listOf(spec("base", "^1.2.0"))) as SkillDependencyAdmission.Conflict<String>
        assertTrue(incompatible.issues.any { it.kind == SkillDependencyIssueKind.VERSION_CONFLICT })

        val installedIncompatible = resolver(
            mapOf("base" to listOf(entry("base", "1.4.0"))),
            installed = mapOf(
                "base" to SkillDependencyInstalledState("base", "1.0.0"),
            ),
        ).resolve("root", listOf(spec("base", "^1.2.0"))) as SkillDependencyAdmission.Conflict<String>
        assertTrue(installedIncompatible.issues.any {
            it.kind == SkillDependencyIssueKind.VERSION_CONFLICT
        })

        val unverified = resolver(
            mapOf("base" to listOf(entry("base", "1.4.0", verified = false))),
        ).resolve("root", listOf(spec("base", "^1.2.0"))) as SkillDependencyAdmission.SetupRequired<String>
        assertTrue(unverified.issues.any {
            it.kind == SkillDependencyIssueKind.UNVERIFIED_SOURCE
        })

        val missing = resolver(emptyMap())
            .resolve("root", listOf(spec("missing"))) as SkillDependencyAdmission.SetupRequired<String>
        assertTrue(missing.issues.any { it.kind == SkillDependencyIssueKind.MISSING_SOURCE })

        val deferredChecksum = resolver(
            mapOf("base" to listOf(entry("base", "1.4.0"))),
        ).resolve(
            "root",
            listOf(spec("base", "^1.2.0", "official", digest)),
        ) as SkillDependencyAdmission.Ready<String>
        assertEquals(mapOf("base" to digest), deferredChecksum.expectedArchiveSha256)

        val checksumMismatch = resolver(
            mapOf("base" to listOf(entry("base", "1.4.0", checksum = "b".repeat(64)))),
        ).resolve(
            "root",
            listOf(spec("base", "^1.2.0", "official", digest)),
        ) as SkillDependencyAdmission.Conflict<String>
        assertTrue(checksumMismatch.issues.any {
            it.kind == SkillDependencyIssueKind.CHECKSUM_CONFLICT
        })

        val installedChecksumMismatch = resolver(
            mapOf("base" to listOf(entry("base", "1.4.0", checksum = digest))),
            installed = mapOf(
                "base" to SkillDependencyInstalledState(
                    "base",
                    "1.4.0",
                    "official",
                    "b".repeat(64),
                ),
            ),
        ).resolve(
            "root",
            listOf(spec("base", "^1.2.0", "official", digest)),
        ) as SkillDependencyAdmission.Conflict<String>
        assertTrue(installedChecksumMismatch.issues.any {
            it.kind == SkillDependencyIssueKind.CHECKSUM_CONFLICT
        })

        val installedSourceMismatch = resolver(
            mapOf("base" to listOf(entry("base", "1.4.0", sourceId = "official", checksum = digest))),
            installed = mapOf(
                "base" to SkillDependencyInstalledState(
                    "base",
                    "1.4.0",
                    "community",
                    digest,
                ),
            ),
        ).resolve(
            "root",
            listOf(spec("base", "^1.2.0", "official", digest)),
        ) as SkillDependencyAdmission.Conflict<String>
        assertTrue(installedSourceMismatch.issues.any {
            it.kind == SkillDependencyIssueKind.SOURCE_CONFLICT
        })
    }

    @Test
    fun dependencyAdmissionExpandsValidatedArchivesInStableOrderAndRejectsCyclesOrReadFailures() {
        val digest = "c".repeat(64)
        val catalog = mapOf(
            "alpha" to listOf(entry("alpha", "1.0.0", declarationsAuthoritative = false)),
            "beta" to listOf(entry("beta", "1.0.0", declarationsAuthoritative = false)),
            "shared" to listOf(entry("shared", "1.0.0", declarationsAuthoritative = false)),
        )
        val expectedDigests = mutableListOf<String?>()
        val expanded = SkillDependencyResolver<String>(
            installedById = { null },
            catalogById = { catalog[it].orEmpty() },
            expansion = { entry, expectedSha256 ->
                expectedDigests += expectedSha256
                SkillDependencyExpansion.Declarations(
                    declarations = when (entry.skillId) {
                        "alpha" -> listOf(spec("shared"))
                        "beta" -> listOf(spec("shared"))
                        else -> emptyList()
                    },
                    archiveSha256 = digest,
                )
            },
        ).resolve("root", listOf(spec("alpha", checksum = digest), spec("beta")))
            as SkillDependencyAdmission.Ready<String>

        assertEquals(listOf("shared", "alpha", "beta"), expanded.installOrder.map { it.skillId })
        assertEquals(setOf("alpha", "beta"), expanded.edges["root"])
        assertEquals(setOf("shared"), expanded.edges["alpha"])
        assertEquals(setOf("shared"), expanded.edges["beta"])
        assertEquals(mapOf("alpha" to digest, "beta" to digest, "shared" to digest), expanded.expectedArchiveSha256)
        assertEquals(listOf(digest, null, null), expectedDigests)

        val cyclic = SkillDependencyResolver<String>(
            installedById = { null },
            catalogById = { id -> catalog[id].orEmpty() },
            expansion = { entry, _ ->
                SkillDependencyExpansion.Declarations(
                    listOf(spec(if (entry.skillId == "alpha") "beta" else "alpha")),
                    digest,
                )
            },
        ).resolve("root", listOf(spec("alpha"))) as SkillDependencyAdmission.Conflict<String>
        assertTrue(cyclic.issues.any { it.kind == SkillDependencyIssueKind.CYCLE })

        val unreadable = SkillDependencyResolver<String>(
            installedById = { null },
            catalogById = { catalog[it].orEmpty() },
            expansion = { _, _ -> SkillDependencyExpansion.Unavailable("archive unavailable") },
        ).resolve("root", listOf(spec("alpha"))) as SkillDependencyAdmission.Conflict<String>
        assertTrue(unreadable.issues.any { it.kind == SkillDependencyIssueKind.ARCHIVE_UNAVAILABLE })

        val missingResolver = SkillDependencyResolver<String>(
            installedById = { null },
            catalogById = { catalog[it].orEmpty() },
        ).resolve("root", listOf(spec("alpha"))) as SkillDependencyAdmission.Conflict<String>
        assertTrue(missingResolver.issues.any {
            it.kind == SkillDependencyIssueKind.ARCHIVE_UNAVAILABLE
        })
    }

    @Test
    fun catalogFieldWithNoValidSkillEntryStillExpandsArchiveDeclarations() {
        val digest = "d".repeat(64)
        var loadCount = 0
        val ready = SkillDependencyResolver<String>(
            installedById = { null },
            catalogById = { id ->
                if (id == "base") listOf(
                    entry(
                        id = "base",
                        version = "1.0.0",
                        checksum = digest,
                        declarations = emptyList(),
                        declarationsAuthoritative = false,
                    ),
                ) else emptyList()
            },
            expansion = { _, _ ->
                loadCount += 1
                SkillDependencyExpansion.Declarations(emptyList(), digest)
            },
        ).resolve("root", listOf(spec("base"))) as SkillDependencyAdmission.Ready<String>

        assertEquals(1, loadCount)
        assertEquals(listOf("base"), ready.installOrder.map { it.skillId })
        assertEquals(mapOf("base" to digest), ready.expectedArchiveSha256)
        assertEquals(setOf("base"), ready.owners["root"])
        assertEquals(setOf("base"), ready.newInstalledIds)
    }

    @Test
    fun uninstallKeepsSharedDependencyAndExternalUserArtifact() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        writeSkill(File(skillsRoot, "shared-dep"), "shared-dep")
        writeSkill(File(skillsRoot, "owner-skill"), "owner-skill")
        val service = SkillIndexService(RuntimeEnvironment.getApplication(), skillsRoot)
        val entries = service.listSkillsForManagement(forceRefresh = true)
        assertTrue(entries.any { it.id == "shared-dep" && it.installed })
        assertTrue(entries.any { it.id == "owner-skill" && it.installed })
        service.recordInstalledSkillDependencies("owner-skill", setOf("shared-dep"))

        val artifact = temporaryFolder.newFile("user-artifact.txt")
        artifact.writeText("user produced content")

        assertFalse(service.deleteSkill("shared-dep"))
        assertTrue(File(skillsRoot, "shared-dep/SKILL.md").isFile)

        assertTrue(service.deleteSkill("owner-skill"))
        assertTrue(File(skillsRoot, "shared-dep/SKILL.md").isFile)
        assertEquals("user produced content", artifact.readText())
        assertTrue(service.deleteSkill("shared-dep"))
        assertFalse(File(skillsRoot, "shared-dep").exists())
        assertEquals("user produced content", artifact.readText())
    }

    @Test
    fun archiveChecksumIsEnforcedInsideInstallTransaction() {
        val skillsRoot = temporaryFolder.newFolder("checksum-skills")
        val service = SkillIndexService(RuntimeEnvironment.getApplication(), skillsRoot)
        val installer = SkillPackageInstaller(skillsRoot, service)
        val archive = zip(
            "SKILL.md" to (
                "---\n" +
                    "name: checksum-skill\n" +
                    "description: Checksum transaction.\n" +
                    "---\n\n" +
                    "# checksum-skill\n"
                ).encodeToByteArray(),
        )
        val digest = sha256(archive)

        val rejected = installer.installLocalZip(
            openStream = { archive.inputStream() },
            expectedArchiveSha256 = "b".repeat(64),
        )
        val failure = rejected as SkillInstallResult.Failure
        assertEquals(SkillInstallErrorCode.CHECKSUM_MISMATCH, failure.error.code)
        assertFalse(File(skillsRoot, "checksum-skill").exists())

        val accepted = installer.installLocalZip(
            openStream = { archive.inputStream() },
            expectedArchiveSha256 = digest,
        ) as SkillInstallResult.Success
        assertEquals(digest, accepted.installed.single().archiveSha256)
        assertTrue(File(skillsRoot, "checksum-skill/SKILL.md").isFile)
    }

    private fun resolver(
        catalog: Map<String, List<SkillDependencyCatalogEntry<String>>>,
        installed: Map<String, SkillDependencyInstalledState> = emptyMap(),
    ): SkillDependencyResolver<String> = SkillDependencyResolver(
        installedById = { installed[it] },
        catalogById = { catalog[it].orEmpty() },
    )

    private fun entry(
        id: String,
        version: String?,
        sourceId: String = "official",
        verified: Boolean = true,
        checksum: String? = null,
        declarations: List<SkillDependencySpec> = emptyList(),
        declarationsAuthoritative: Boolean = true,
    ): SkillDependencyCatalogEntry<String> = SkillDependencyCatalogEntry(
        item = id,
        skillId = id,
        version = version,
        sourceId = sourceId,
        sourceName = sourceId,
        trust = "official",
        integrityVerified = verified,
        archiveSha256 = checksum,
        declarations = declarations,
        declarationsAuthoritative = declarationsAuthoritative,
    )

    private fun spec(
        id: String,
        version: String? = null,
        sourceId: String? = null,
        checksum: String? = null,
    ): SkillDependencySpec = SkillDependencySpec(
        id = id,
        versionConstraint = version,
        sourceId = sourceId,
        archiveSha256 = checksum,
    )

    private fun writeSkill(directory: File, id: String) {
        directory.mkdirs()
        File(directory, "SKILL.md").writeText(
            "---\n" +
                "name: " + id + "\n" +
                "description: Test dependency ownership.\n" +
                "---\n\n" +
                "# " + id + "\n",
        )
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}
