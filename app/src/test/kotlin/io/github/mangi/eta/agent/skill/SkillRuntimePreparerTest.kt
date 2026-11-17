package io.github.mangi.eta.agent.skill

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillRuntimePreparerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun copiesVersionedScriptsAndBinaryAssetsIntoTheExistingWorkspaceWithoutExecution() {
        val fixture = fixture()
        val result = fixture.preparer.prepare(fixture.entry, fixture.workspace, "/workspace")
        val target = File(result.androidPath)
        assertEquals("/workspace/.nexa-skills/sample/${result.contentSha256}", result.linuxPath)
        assertEquals("2.0", result.version)
        assertEquals(4, result.files)
        assertTrue(File(target, "assets/template.bin").readBytes().contentEquals(byteArrayOf(0, 1, -1)))
        assertTrue(File(target, "scripts/run.sh").readText().contains("touch MUST_NOT_RUN"))
        assertFalse(File(fixture.workspace, "MUST_NOT_RUN").exists())
        assertEquals(result, fixture.preparer.prepare(fixture.entry, fixture.workspace, "/workspace"))
        assertEquals("/nexus-inputs/.nexa-skills/sample/${result.contentSha256}",
            fixture.preparer.prepare(fixture.entry, fixture.workspace, "/nexus-inputs").linuxPath)
    }

    @Test fun changingAResourceProducesANewDigestAndTamperedCopiesAreNeverOverwritten() {
        val fixture = fixture()
        val original = fixture.preparer.prepare(fixture.entry, fixture.workspace, "/workspace")
        File(original.androidPath, "references/guide.md").writeText("tampered")
        assertThrows(IllegalArgumentException::class.java) { fixture.preparer.prepare(fixture.entry, fixture.workspace, "/workspace") }
        assertEquals("tampered", File(original.androidPath, "references/guide.md").readText())
        File(fixture.entry.rootPath, "references/guide.md").writeText("new revision")
        val updated = fixture.preparer.prepare(fixture.entry, fixture.workspace, "/workspace")
        assertNotEquals(original.contentSha256, updated.contentSha256)
        assertEquals("new revision", File(updated.androidPath, "references/guide.md").readText())
    }

    @Test fun limitsAndDisabledOrForeignSkillsFailBeforeCreatingRuntimeFiles() {
        val fixture = fixture()
        assertThrows(IllegalArgumentException::class.java) { fixture.preparer.prepare(fixture.entry.copy(enabled = false), fixture.workspace, "/workspace") }
        assertThrows(IllegalArgumentException::class.java) {
            SkillRuntimePreparer(fixture.skillsRoot, maxFileBytes = 8).prepare(fixture.entry, fixture.workspace, "/workspace")
        }
        assertThrows(IllegalArgumentException::class.java) { fixture.preparer.prepare(fixture.entry.copy(id = "../escape"), fixture.workspace, "/workspace") }
        assertFalse(File(fixture.workspace, ".nexa-skills").exists())
    }

    @Test fun sourceAndDestinationSymlinksCannotReadOrWriteOutsideTheirRoots() {
        val fixture = fixture()
        val outside = temporary.newFolder("outside")
        File(outside, "secret").writeText("untouched")
        val sourceLink = File(fixture.entry.rootPath, "references/link")
        try { Files.createSymbolicLink(sourceLink.toPath(), outside.toPath()) }
        catch (error: Exception) { assumeNoException("OS does not allow symlinks", error) }
        assertThrows(IllegalArgumentException::class.java) { fixture.preparer.prepare(fixture.entry, fixture.workspace, "/workspace") }
        Files.delete(sourceLink.toPath())
        Files.createSymbolicLink(File(fixture.workspace, ".nexa-skills").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { fixture.preparer.prepare(fixture.entry, fixture.workspace, "/workspace") }
        assertEquals(listOf("secret"), outside.listFiles().orEmpty().map { it.name })
        assertEquals("untouched", File(outside, "secret").readText())
    }

    private fun fixture(): Fixture {
        val base = temporary.newFolder()
        val root = File(base, "skills").apply { mkdir() }
        val skill = File(root, "sample").apply { mkdir() }
        File(skill, "SKILL.md").writeText("---\nname: sample\ndescription: Sample portable Skill\nversion: '2.0'\n---\nRead references/guide.md")
        File(skill, "scripts").mkdir()
        File(skill, "scripts/run.sh").writeText("#!/bin/sh\ntouch MUST_NOT_RUN\n")
        File(skill, "references").mkdir()
        File(skill, "references/guide.md").writeText("guide")
        File(skill, "assets").mkdir()
        File(skill, "assets/template.bin").writeBytes(byteArrayOf(0, 1, -1))
        val workspace = File(base, "workspace").apply { mkdir() }
        return Fixture(root, workspace, SkillRuntimePreparer(root), SkillIndexEntry(
            id = "sample", name = "sample", description = "Sample portable Skill", rootPath = skill.absolutePath,
            skillFilePath = File(skill, "SKILL.md").absolutePath,
            hasScripts = true, hasReferences = true, hasAssets = true, hasEvals = false,
        ))
    }

    private data class Fixture(val skillsRoot: File, val workspace: File, val preparer: SkillRuntimePreparer, val entry: SkillIndexEntry)
}
