package io.github.mangi.eta.agent.skill

import io.github.mangi.eta.data.db.EtaDatabase
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
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
class SkillPackageArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
    }

    @Test
    fun exportedPackageContainsResourcesAndCanBeInstalledAgain() {
        val source = temporaryFolder.newFolder("export-source")
        writeSkill(source, "export-demo", "Round-trip export.")
        val note = File(source, "references/note.txt").also { file ->
            file.parentFile?.mkdirs()
            file.writeText("portable reference")
        }
        val destination = File(temporaryFolder.newFolder("export-output"), "export-demo.zip")

        val exported = SkillPackageArchive.export(
            skillId = "export-demo",
            displayName = "Export Demo",
            sourceRoot = source,
            destination = destination,
        )

        assertEquals(2, exported.fileCount)
        assertEquals(destination.length(), exported.sizeBytes)
        ZipFile(destination).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue("export-demo/SKILL.md" in names)
            assertTrue("export-demo/references/note.txt" in names)
        }

        val installedRoot = temporaryFolder.newFolder("round-trip-skills")
        val index = SkillIndexService(RuntimeEnvironment.getApplication(), installedRoot)
        val installer = SkillPackageInstaller(installedRoot, index)
        val installed = installer.installLocalZip({ destination.inputStream() })

        assertTrue(installed is SkillInstallResult.Success)
        val installedSkill = File(installedRoot, "export-demo")
        assertTrue(File(installedSkill, "SKILL.md").isFile)
        assertEquals("portable reference", File(installedSkill, "references/note.txt").readText())
        assertTrue(note.isFile)
    }

    @Test
    fun exportRejectsSymbolicLinksWithoutWritingAnArchive() {
        val source = temporaryFolder.newFolder("symlink-source")
        writeSkill(source, "symlink-skill", "Must not export links.")
        val outside = File(temporaryFolder.newFolder("outside"), "secret.txt").also {
            it.writeText("outside")
        }
        try {
            Files.createSymbolicLink(File(source, "linked.txt").toPath(), outside.toPath())
        } catch (failure: Exception) {
            assumeNoException(failure)
        }
        val destination = File(temporaryFolder.newFolder("symlink-output"), "skill.zip")

        assertThrows(IllegalArgumentException::class.java) {
            SkillPackageArchive.export(
                skillId = "symlink-skill",
                displayName = "Symlink Skill",
                sourceRoot = source,
                destination = destination,
            )
        }
        assertFalse(destination.exists())
    }

    @Test
    fun exportRejectsNestedSkillThatWouldMakeThePackageAmbiguous() {
        val source = temporaryFolder.newFolder("nested-source")
        writeSkill(source, "outer-skill", "Outer Skill.")
        writeSkill(File(source, "resources/inner"), "inner-skill", "Nested Skill.")
        val destination = File(temporaryFolder.newFolder("nested-output"), "skill.zip")

        val failure = assertThrows(IllegalArgumentException::class.java) {
            SkillPackageArchive.export(
                skillId = "outer-skill",
                displayName = "Outer Skill",
                sourceRoot = source,
                destination = destination,
            )
        }

        assertTrue(failure.message.orEmpty().contains("嵌套"))
        assertFalse(destination.exists())
    }

    @Test
    fun cancellationLeavesExistingDestinationUntouched() {
        val source = temporaryFolder.newFolder("cancelled-source")
        writeSkill(source, "cancelled-skill", "Cancel export.")
        val destination = File(temporaryFolder.newFolder("cancelled-output"), "skill.zip")
        destination.writeText("existing destination")

        assertThrows(CancellationException::class.java) {
            SkillPackageArchive.export(
                skillId = "cancelled-skill",
                displayName = "Cancelled Skill",
                sourceRoot = source,
                destination = destination,
                isCancelled = { true },
            )
        }

        assertEquals("existing destination", destination.readText())
    }

    private fun writeSkill(root: File, skillId: String, description: String) {
        root.mkdirs()
        File(root, "SKILL.md").writeText(
            """
            ---
            name: $skillId
            description: $description
            ---

            # $skillId
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
    }
}
