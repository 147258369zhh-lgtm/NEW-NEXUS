package io.github.mangi.eta.agent.tool

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryScope
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
@Config(sdk = [36], application = Application::class)
class AgentLocalMemoryScopeTest {
    private val project = AgentMemoryScope.project("scope_repair_project")
    private val current = AgentMemoryScope.conversation("scope_repair_conversation")
    private val original = "# 核心记忆\n保留的原始记忆"
    private var previousGlobal = ""

    @Before
    fun setUp() {
        AgentMemoryRepository.init(RuntimeEnvironment.getApplication())
        previousGlobal = AgentMemoryRepository.snapshot().content
        listOf(AgentMemoryScope.GLOBAL, project, current).forEach { AgentMemoryRepository.replaceAll(it, original) }
    }

    @After
    fun tearDown() {
        AgentMemoryRepository.replaceAll(previousGlobal)
        AgentMemoryRepository.replaceAll(project, "")
        AgentMemoryRepository.replaceAll(current, "")
    }

    @Test
    fun explicitProjectScopeKeepsWorkingLayerOutOfTheConversation() {
        tools().use { executor ->
            val result = write(executor, "append", project, scope = "project", layer = "working", content = "项目中的当前工作")
            assertTrue(result.getBoolean("ok"))
        }
        assertTrue(AgentMemoryRepository.snapshot(project).content.contains("# 工作记忆\n项目中的当前工作"))
        assertEquals(original, AgentMemoryRepository.snapshot(current).content)
        assertEquals(original, AgentMemoryRepository.snapshot().content)
    }

    @Test
    fun clearAndReplaceDoNotUseTheAppendLayerToRedirectTheScope() {
        tools().use { executor ->
            assertTrue(write(executor, "clear", AgentMemoryScope.GLOBAL, scope = "global", layer = "project").getBoolean("ok"))
            assertEquals("", AgentMemoryRepository.snapshot().content)
            assertEquals(original, AgentMemoryRepository.snapshot(project).content)
            AgentMemoryRepository.replaceAll(original)

            val replacement = write(executor, "replace_range", AgentMemoryScope.GLOBAL,
                layer = "working", content = "更新的全局记忆", startLine = 2, endLine = 2)
            assertTrue(replacement.getBoolean("ok"))
            assertEquals("# 核心记忆\n更新的全局记忆", AgentMemoryRepository.snapshot().content)
            assertEquals(original, AgentMemoryRepository.snapshot(current).content)
        }
    }

    @Test
    fun appendWithoutExplicitScopeKeepsTheExistingProjectDefault() {
        tools().use { executor ->
            assertTrue(write(executor, "append", project, layer = "project", content = "项目仍使用 Kotlin").getBoolean("ok"))
        }
        assertTrue(AgentMemoryRepository.snapshot(project).content.contains("项目仍使用 Kotlin"))
        assertEquals(original, AgentMemoryRepository.snapshot().content)
        assertEquals(original, AgentMemoryRepository.snapshot(current).content)
    }

    @Test
    fun explicitUnknownAndUnavailableScopesCannotBeBypassedByALayer() {
        tools().use { executor ->
            assertEquals("INVALID_ARGUMENT", write(executor, "append", project,
                scope = "unattached", layer = "project", content = "不能写入").getString("code"))
        }
        tools(projectScope = null).use { executor ->
            assertEquals("INVALID_ARGUMENT", write(executor, "append", current,
                scope = "project", layer = "working", content = "不能写入").getString("code"))
        }
        listOf(AgentMemoryScope.GLOBAL, project, current).forEach { assertEquals(original, AgentMemoryRepository.snapshot(it).content) }
    }

    @Test
    fun resultsIdentifyActualScopeAndRejectMismatchedTypes() {
        tools().use { executor ->
            val read = JSONObject(executor.execute(AgentModelClient.ToolCall("read", "memory_get",
                JSONObject().put("scope", "current").toString())).content)
            assertEquals(current.key, read.getString("scope"))
            val result = write(executor, "append", current, scope = "current", content = "会话记录")
            assertEquals(current.key, result.getString("scope"))
            assertEquals("INVALID_ARGUMENT", write(executor, "append", current,
                scope = "meeting", content = "不应写入会话").getString("code"))
        }
    }

    @Test
    fun layeredAppendKeepsTheLegacyPreambleAndUnclassifiedHeading() {
        val preamble = "旧记忆说明\n# 自定义资料\n需要保留的背景"
        AgentMemoryRepository.replaceAll(project, "$preamble\n\n$original")
        tools().use { executor ->
            assertTrue(write(executor, "append", project,
                scope = "project", layer = "working", content = "新的工作记录").getBoolean("ok"))
        }
        assertEquals("$preamble\n\n$original\n\n# 工作记忆\n新的工作记录", AgentMemoryRepository.snapshot(project).content)
        assertEquals(original, AgentMemoryRepository.snapshot(current).content)
    }

    @Test
    fun scopedWritesStillRequireEnabledWritableMemory() {
        listOf(false to true, true to false).forEach { (enabled, writable) ->
            tools(enabled = enabled, writable = writable).use { executor ->
                val result = write(executor, "append", project,
                    scope = "project", layer = "working", content = "不能绕过权限")
                assertFalse(result.getBoolean("ok"))
                assertEquals(if (enabled) "REAL_MEMORY_READ_ONLY" else "MEMORY_DISABLED", result.getString("code"))
            }
        }
        listOf(AgentMemoryScope.GLOBAL, project, current).forEach { assertEquals(original, AgentMemoryRepository.snapshot(it).content) }
    }

    private fun write(executor: AgentLocalTools, mode: String, revisionScope: AgentMemoryScope,
        scope: String? = null, layer: String? = null, content: String = "", startLine: Int = 1, endLine: Int = 1): JSONObject {
        val args = JSONObject().put("mode", mode).put("revision", AgentMemoryRepository.snapshot(revisionScope).revision)
            .put("content", content).put("start_line", startLine).put("end_line", endLine)
        scope?.let { args.put("scope", it) }
        layer?.let { args.put("layer", it) }
        return JSONObject(executor.execute(AgentModelClient.ToolCall("write", "memory_write", args.toString())).content)
    }

    private fun tools(projectScope: AgentMemoryScope? = project, enabled: Boolean = true, writable: Boolean = true) = AgentLocalTools(
        context = RuntimeEnvironment.getApplication(), logger = NoOpLogger, browserRunId = "scope-repair-test",
        memoryToolsEnabled = { enabled }, memoryWritable = writable,
        projectMemoryScope = projectScope, runMemoryScope = current, rootAvailable = { false },
    )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
