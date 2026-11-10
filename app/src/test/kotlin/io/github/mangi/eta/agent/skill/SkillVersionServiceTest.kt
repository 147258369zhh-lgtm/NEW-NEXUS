package io.github.mangi.eta.agent.skill

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
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
class SkillVersionServiceTest {
    private lateinit var appContext: Context
    private lateinit var skillsRoot: File
    private lateinit var index: SkillIndexService
    private lateinit var service: SkillVersionService
    private lateinit var versionStore: SkillVersionStore

    @Before
    fun setUp() {
        appContext = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        appContext.deleteDatabase("eta.db")
        SkillRuntime.resetForTests()
        File(appContext.filesDir, "capability-center").deleteRecursively()
        File(appContext.filesDir, "skills").deleteRecursively()
        skillsRoot = SkillRuntime.skillsRoot(appContext)
        index = SkillRuntime.createIndexService(appContext)
        service = SkillVersionService(appContext)
        versionStore = SkillVersionStore(appContext)
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
        SkillRuntime.resetForTests()
    }

    @Test
    fun backupArchivesSkillAndRestoreCreatesPreRestoreSnapshot() {
        val skillId = "versioned-skill"
        createSkill(skillId, "version one")

        val backup = service.backupCurrent(skillId)

        assertEquals(skillId, backup.skillId)
        assertTrue(versionStore.archiveFile(backup).isFile)
        ZipFile(versionStore.archiveFile(backup)).use { archive ->
            val names = archive.entries().asSequence().map { it.name }.toSet()
            assertTrue("SKILL.md" in names)
            assertTrue("data/note.txt" in names)
        }

        writeSkill(skillId, "version two")
        val outcome = service.restore(backup.id)

        assertTrue(outcome is SkillVersionRestoreOutcome.Success)
        assertTrue(skillFile(skillId).readText().contains("version one"))
        assertEquals(2, service.listForSkill(skillId).size)
    }

    @Test
    fun restoreRejectsTamperedArchiveBeforeTouchingCurrentSkill() {
        val skillId = "tampered-skill"
        createSkill(skillId, "known good")
        val backup = service.backupCurrent(skillId)
        FileOutputStream(versionStore.archiveFile(backup), true).use { output ->
            output.write(0x7f)
        }
        writeSkill(skillId, "current content")

        val outcome = service.restore(backup.id)

        assertTrue(outcome is SkillVersionRestoreOutcome.Failure)
        assertEquals(
            "版本归档校验失败，已拒绝恢复",
            (outcome as SkillVersionRestoreOutcome.Failure).message,
        )
        assertTrue(skillFile(skillId).readText().contains("current content"))
        assertEquals(1, service.listForSkill(skillId).size)
    }

    @Test
    fun restoringOldestVersionSurvivesPreRestorePruning() {
        val skillId = "oldest-version-skill"
        createSkill(skillId, "version one")
        val oldest = service.backupCurrent(skillId)
        waitForDistinctTimestamp()
        writeSkill(skillId, "version two")
        service.backupCurrent(skillId)
        waitForDistinctTimestamp()
        writeSkill(skillId, "version three")
        service.backupCurrent(skillId)
        waitForDistinctTimestamp()
        writeSkill(skillId, "current version")

        val outcome = service.restore(oldest.id)

        assertTrue(outcome is SkillVersionRestoreOutcome.Success)
        assertTrue(skillFile(skillId).readText().contains("version one"))
        assertEquals(3, service.listForSkill(skillId).size)
    }

    @Test
    fun keepsOnlyThreeVersionsAndDeletesArchivesWithSkill() {
        val skillId = "pruned-skill"
        createSkill(skillId, "version zero")
        repeat(4) { version ->
            writeSkill(skillId, "version $version")
            service.backupCurrent(skillId)
            waitForDistinctTimestamp()
        }

        val versions = service.listForSkill(skillId)
        assertEquals(3, versions.size)
        val archives = versions.map(versionStore::archiveFile)
        assertTrue(archives.all(File::isFile))

        assertTrue(index.deleteSkill(skillId))

        assertTrue(service.listForSkill(skillId).isEmpty())
        assertTrue(archives.none(File::exists))
    }

    private fun createSkill(skillId: String, body: String) {
        val root = File(skillsRoot, skillId).also { it.mkdirs() }
        File(root, "data/note.txt").also { note ->
            note.parentFile?.mkdirs()
            note.writeText("portable resource")
        }
        writeSkill(skillId, body)
        assertTrue(index.listSkillsForManagement(forceRefresh = true).any { it.id == skillId })
    }

    private fun writeSkill(skillId: String, body: String) {
        val root = File(skillsRoot, skillId).also { it.mkdirs() }
        File(root, "SKILL.md").writeText(
            """
            ---
            name: $skillId
            description: Version backup test skill.
            ---

            # $skillId

            $body
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
        index.listSkillsForManagement(forceRefresh = true)
    }

    private fun skillFile(skillId: String): File = File(skillsRoot, "$skillId/SKILL.md")

    private fun waitForDistinctTimestamp() {
        Thread.sleep(3L)
    }
}
