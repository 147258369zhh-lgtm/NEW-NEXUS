package io.github.mangi.eta.agent.skill

import io.github.mangi.eta.data.db.EtaDatabase
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class SkillAuthoringServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var skillsRoot: File
    private lateinit var index: SkillIndexService
    private lateinit var installer: SkillPackageInstaller

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
        skillsRoot = temporaryFolder.newFolder("skills")
        index = SkillIndexService(RuntimeEnvironment.getApplication(), skillsRoot)
        installer = SkillPackageInstaller(skillsRoot, index)
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
    }

    @Test
    fun updateCreatesVersionBackupBeforeCommit() {
        val service = SkillAuthoringService(index, installer)
        assertTrue(service.manage(createRequest("first body")).getBoolean("ok"))
        var backedUpSkillId: String? = null
        val updating = SkillAuthoringService(index, installer) { skillId ->
            backedUpSkillId = skillId
        }

        val result = updating.manage(updateRequest("second body"))

        assertEquals("authoring-demo", backedUpSkillId)
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertTrue(File(skillsRoot, "authoring-demo/SKILL.md").readText().contains("second body"))
    }

    @Test
    fun backupFailureStopsUpdateAndLeavesExistingContentUntouched() {
        val service = SkillAuthoringService(index, installer)
        assertTrue(service.manage(createRequest("first body")).getBoolean("ok"))
        val skillFile = File(skillsRoot, "authoring-demo/SKILL.md")
        val before = skillFile.readText()
        val updating = SkillAuthoringService(index, installer) { error("backup unavailable") }

        val result = updating.manage(updateRequest("second body"))

        assertFalse(result.getBoolean("ok"))
        assertEquals("SKILL_VERSION_BACKUP_FAILED", result.getString("code"))
        assertEquals(before, skillFile.readText())
    }

    private fun createRequest(body: String): JSONObject = JSONObject()
        .put("action", "create")
        .put("skillId", "authoring-demo")
        .put("description", "Authoring service test.")
        .put("bodyMarkdown", body)

    private fun updateRequest(body: String): JSONObject {
        val revision = skillRevision(File(skillsRoot, "authoring-demo/SKILL.md").readBytes())
        return createRequest(body)
            .put("action", "update")
            .put("expectedRevision", revision)
    }
}
