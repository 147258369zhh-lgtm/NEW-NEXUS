package io.github.mangi.eta.agent.memory

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.repository.AgentMemoryReadResult
import io.github.mangi.eta.data.repository.AgentMemoryScope
import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MemorySearchToolTest {
    private val call = AgentModelClient.ToolCall("1", "memory_search", "{\"query\":\"release\"}")
    private val project = AgentMemoryScope.project("a")
    private fun read(content: String) = AgentMemoryReadResult(
        AgentMemorySnapshot(content, "rev", content.length, 1), content, 1, 1, false, 1)

    @Test fun failedSourcesReturnOtherAllowedDocumentsAndReportFailure() {
        val visited = mutableListOf<String>()
        val tool = MemorySearchTool(RuntimeEnvironment.getApplication(), listOf(AgentMemoryScope.GLOBAL, project, project),
            searchReviewed = { _, _, _ -> error("database unavailable") },
            readDocument = { scope, _, _ ->
                visited += scope.key
                if (scope == AgentMemoryScope.GLOBAL) error("document unavailable") else read("release details")
            }, isEnabled = { true })
        val result = JSONObject(tool.execute(call).content)
        assertTrue(result.getBoolean("ok"))
        assertTrue(result.getBoolean("partial"))
        assertEquals(listOf("global", "project:a"), visited)
        assertEquals("project:a", result.getJSONArray("documents").getJSONObject(0).getString("scope"))
        assertEquals(2, result.getJSONArray("unavailable_sources").length())
        assertEquals("unavailable", result.getJSONObject("retrieval").getString("status"))
    }

    @Test fun cancellationIsPropagatedAndDisabledMemoryIsNotRead() {
        val context = RuntimeEnvironment.getApplication()
        val cancelled = MemorySearchTool(context, listOf(project), searchReviewed = { _, _, _ -> throw CancellationException() },
            isEnabled = { true })
        assertThrows(CancellationException::class.java) { cancelled.execute(call) }
        val disabled = MemorySearchTool(context, listOf(project), searchReviewed = { _, _, _ -> error("must not read") },
            isEnabled = { false })
        assertFalse(JSONObject(disabled.execute(call).content).getBoolean("ok"))
    }

    @Test fun allSourcesFailIsNotReportedAsSuccess() {
        val tool = MemorySearchTool(RuntimeEnvironment.getApplication(), listOf(project),
            searchReviewed = { _, _, _ -> error("database unavailable") },
            readDocument = { _, _, _ -> error("document unavailable") }, isEnabled = { true })
        val result = JSONObject(tool.execute(call).content)
        assertFalse(result.getBoolean("ok"))
        assertEquals(2, result.getJSONArray("unavailable_sources").length())
    }
}
