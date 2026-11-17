package io.github.mangi.eta.agent.skill

import io.github.mangi.eta.data.db.EtaDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
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
class SkillManifestCompatibilityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Before fun resetDatabase() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }

    @Test fun localClaudeManifestIsNormalizedAndKeptInert() {
        val fixture = fixture("claude")
        val archive = zip(
            "CLAUDE.md" to """
                # Release Writer
                Prepare a concise release note from verified changes.

                Never publish without checking the final artifact.
            """.trimIndent().encodeToByteArray(),
        )

        val result = fixture.installer.installLocalZip({ archive.inputStream() })

        assertEquals("release-writer", (result as SkillInstallResult.Success).installed.single().id)
        val normalized = File(fixture.skillsRoot, "release-writer/SKILL.md").readText()
        assertTrue(normalized.contains("nexus_import_format: claude-instructions"))
        assertTrue(normalized.contains("Never publish without checking the final artifact."))
    }

    @Test fun repositoryInspectionConvertsCursorAndCopilotInstructions() {
        val fixture = fixture("vendor-repository")
        val archive = zip(
            "repo-main/.cursor/rules/testing.mdc" to "# Testing Rule\nRun focused tests.".encodeToByteArray(),
            "repo-main/.github/copilot-instructions.md" to "# Review Rule\nCheck every changed call site.".encodeToByteArray(),
        )

        val inspection = fixture.installer.inspectRepositoryZip({ archive.inputStream() })
            as SkillArchiveInspectionResult.Success
        assertEquals(setOf("testing-rule", "review-rule"), inspection.candidates.map { it.id }.toSet())

        val installed = fixture.installer.installRepositoryZip(
            openStream = { archive.inputStream() },
            selectedPaths = inspection.candidates.map { it.relativePath },
        )
        assertEquals(2, (installed as SkillInstallResult.Success).installed.size)
        assertTrue(File(fixture.skillsRoot, "testing-rule/SKILL.md").readText().contains("Run focused tests."))
        assertTrue(File(fixture.skillsRoot, "review-rule/SKILL.md").readText()
            .contains("Check every changed call site."))
    }

    @Test fun standardSkillWinsOverVendorFallbackFiles() {
        val fixture = fixture("priority")
        val archive = zip(
            "repo-main/AGENTS.md" to "project-only instructions".encodeToByteArray(),
            "repo-main/skills/actual/SKILL.md" to """
                ---
                name: actual-skill
                description: Canonical portable Skill.
                ---
                # Actual
            """.trimIndent().encodeToByteArray(),
        )

        val inspection = fixture.installer.inspectRepositoryZip({ archive.inputStream() })
            as SkillArchiveInspectionResult.Success
        assertEquals(listOf("actual-skill"), inspection.candidates.map { it.id })
    }

    private fun fixture(name: String): Fixture {
        val root = temporaryFolder.newFolder(name, "skills")
        val service = SkillIndexService(RuntimeEnvironment.getApplication(), root)
        return Fixture(root, SkillPackageInstaller(root, service))
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { archive ->
            entries.forEach { (name, bytes) ->
                archive.putNextEntry(ZipEntry(name))
                archive.write(bytes)
                archive.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private data class Fixture(val skillsRoot: File, val installer: SkillPackageInstaller)
}
