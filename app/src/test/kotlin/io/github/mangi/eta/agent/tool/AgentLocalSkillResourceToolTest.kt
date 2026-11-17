package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.data.db.EtaDatabase
import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.skill.SkillIndexService
import io.github.mangi.eta.agent.skill.SkillResourceReader
import io.github.mangi.eta.core.AgentLogger
import java.io.File
import org.json.JSONObject
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
class AgentLocalSkillResourceToolTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }

    @Test
    fun enabledSkillResourceIsReadAndTruncatedWithoutTerminalPermission() {
        val fixture = fixture()
        val fullText = "guide-" + "x".repeat(700)
        File(fixture.skillRoot, "references/guide.md").apply {
            parentFile?.mkdirs()
            writeText(fullText)
        }

        val json = execute(
            fixture,
            relativePath = "references/guide.md",
            maxChars = 512,
        )

        assertTrue(json.toString(), json.getBoolean("ok"))
        assertEquals("references/guide.md", json.getString("relativePath"))
        assertEquals(512, json.getString("text").length)
        assertEquals(fullText.length, json.getInt("totalChars"))
        assertTrue(json.getBoolean("truncated"))
    }

    @Test
    fun traversalIsRejectedByCoreResourceReader() {
        val fixture = fixture()

        val json = execute(fixture, relativePath = "../outside.txt", maxChars = 512)

        assertFalse(json.optBoolean("ok", false))
        assertEquals("INVALID_RELATIVE_PATH", json.getString("code"))
    }

    @Test
    fun longResourcesSupportUnicodeSafeContinuationAndRejectOutOfRangeOffsets() {
        val fixture = fixture()
        val text = "a".repeat(511) + "😀" + "中".repeat(70_000) + "tail"
        File(fixture.skillRoot, "references/guide.md").apply { parentFile?.mkdirs(); writeText(text) }
        fun read(offset: Int): JSONObject = JSONObject(fixture.tools.execute(AgentModelClient.ToolCall(
            id = "page-$offset", name = "skills_read_resource", argumentsJson = JSONObject()
                .put("skillId", "resource-demo").put("relativePath", "references/guide.md")
                .put("maxChars", 512).put("offset", offset).toString(),
        )).content)
        try {
            val first = read(0)
            assertEquals(511, first.getInt("next_offset"))
            assertEquals("utf16-code-unit", first.getString("offsetUnit"))
            assertEquals("😀", read(first.getInt("next_offset")).getString("text").take(2))
            assertEquals("INVALID_OFFSET", read(512).getString("code"))
            assertEquals("INVALID_OFFSET", read(text.length + 1).getString("code"))
            assertTrue(read(70_000).getString("text").isNotEmpty())
        } finally { fixture.tools.close() }
    }

    @Test
    fun runtimePreparationHonorsTheLiveTerminalPermissionAndSkillDeclaredWriteBoundary() {
        val disabled = fixture()
        val arguments = JSONObject().put("skillId", "resource-demo").toString()
        fun prepare(fixture: Fixture): JSONObject = fixture.tools.use {
            JSONObject(it.execute(AgentModelClient.ToolCall(id = "prepare", name = "skills_prepare_runtime", argumentsJson = arguments)).content)
        }
        assertEquals("TERMINAL_TOOLS_DISABLED", prepare(disabled).getString("code"))
        val readOnly = fixture(terminalEnabled = true, allowedTools = "Read")
        assertEquals("SKILL_TOOL_NOT_ALLOWED", prepare(readOnly).getString("code"))
    }

    private fun fixture(terminalEnabled: Boolean = false, allowedTools: String? = null): Fixture {
        val context = RuntimeEnvironment.getApplication() as Context
        val skillsRoot = temporaryFolder.newFolder()
        val skillRoot = File(skillsRoot, "resource-demo").also { it.mkdirs() }
        File(skillRoot, "SKILL.md").writeText(
            """
            ---
            name: resource-demo
            description: Read bounded reference files in tests.
            ---

            # Resource demo
            """.trimIndent().let { raw ->
                if (allowedTools == null) raw else raw.replace("description: Read bounded reference files in tests.",
                    "description: Read bounded reference files in tests.\nallowed-tools: $allowedTools")
            },
        )
        val indexService = SkillIndexService(context, skillsRoot)
        indexService.listSkillsForManagement(forceRefresh = true)
        return Fixture(
            skillRoot = skillRoot,
            tools = AgentLocalTools(
                context = context,
                logger = NoOpLogger,
                terminalToolsEnabled = { terminalEnabled },
                skillIndexService = indexService,
                skillResourceReader = SkillResourceReader(skillsRoot),
                runAvailableSkillIds = setOf("resource-demo"),
            ),
        )
    }

    private fun execute(fixture: Fixture, relativePath: String, maxChars: Int): JSONObject {
        val result = fixture.tools.execute(
            AgentModelClient.ToolCall(
                id = "resource-1",
                name = "skills_read_resource",
                argumentsJson = JSONObject()
                    .put("skillId", "resource-demo")
                    .put("relativePath", relativePath)
                    .put("maxChars", maxChars)
                    .toString(),
            ),
        )
        fixture.tools.close()
        return JSONObject(result.content)
    }

    private data class Fixture(
        val skillRoot: File,
        val tools: AgentLocalTools,
    )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
