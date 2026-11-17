package io.github.mangi.eta.agent.model

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBatchConcurrencyTest {
    @Test
    fun onlyDistinctReadKeysShareOneParallelRunAndWritesBreakTheRun() {
        val slots: (String) -> ToolBatchSlot = { name ->
            when (name) {
                "read_file" -> ToolBatchSlot(readOnly = true, concurrencyKey = "filesystem")
                "web_search" -> ToolBatchSlot(readOnly = true, concurrencyKey = "network")
                "write_file" -> ToolBatchSlot(readOnly = false, concurrencyKey = null)
                else -> ToolBatchSlot.SERIAL
            }
        }
        val mixed = listOf(call("a", "read_file"), call("b", "web_search"), call("c", "read_file"))
        assertEquals(0..1, ToolBatchScheduler.parallelReadRun(mixed, 0, { true }, slots))
        assertNull("A repeated resource key must stay serial", ToolBatchScheduler.parallelReadRun(mixed, 2, { true }, slots))

        val sameKey = listOf(call("a", "read_file"), call("b", "read_file"))
        assertNull(ToolBatchScheduler.parallelReadRun(sameKey, 0, { true }, slots))

        val writeBarrier = listOf(call("a", "read_file"), call("b", "write_file"), call("c", "web_search"))
        assertNull("A write must not be bridged by the parallel run", ToolBatchScheduler.parallelReadRun(writeBarrier, 0, { true }, slots))

        assertNull("Unpublished tools never run in parallel", ToolBatchScheduler.parallelReadRun(mixed, 0, { false }, slots))
    }

    @Test(timeout = 10_000)
    fun distinctReadToolsActuallyOverlapAndResultsKeepTheModelOrder() {
        val bothStarted = CountDownLatch(2)
        val executed = CopyOnWriteArrayList<String>()
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall("read-context", "get_current_context", "{}"),
                    toolCall("read-web", "web_search", "{\"query\":\"nexus\"}"),
                ),
            ),
            assistant(content = "完成", finishReason = "stop"),
        )
        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "同时读取",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id
                bothStarted.countDown()
                // Serial execution would wait for a call that can never start and fail this check.
                assertTrue("Read calls did not overlap", bothStarted.await(3, TimeUnit.SECONDS))
                AgentModelClient.ToolResult(
                    JSONObject().put("ok", true).put("tool", call.id).toString(),
                )
            },
            provider = provider,
        )

        assertEquals(setOf("read-context", "read-web"), executed.toSet())
        assertEquals(
            listOf("read-context", "read-web"),
            result.transcript.filter { it.role == "tool" }.map { it.toolCallId },
        )
        assertTrue(
            result.transcript.filter { it.role == "tool" }
                .all { JSONObject(it.content).getBoolean("ok") },
        )
    }

    private fun modelConfig(): AgentModelClient.ModelConfig = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = "test-key",
        model = "test-model",
        contextWindow = 128_000,
        systemPrompt = "",
        browserTools = true,
    )

    private fun assistant(
        content: String = "",
        finishReason: String,
        toolCalls: List<JSONObject> = emptyList(),
    ): JSONObject = JSONObject()
        .put("role", "assistant")
        .put("content", content)
        .put("finish_reason", finishReason)
        .also { message -> if (toolCalls.isNotEmpty()) message.put("tool_calls", JSONArray(toolCalls)) }

    private fun toolCall(id: String, name: String, arguments: String): JSONObject = JSONObject()
        .put("id", id)
        .put("type", "function")
        .put(
            "function",
            JSONObject().put("name", name).put("arguments", arguments),
        )

    private fun call(id: String, name: String) = AgentModelClient.ToolCall(id, name, "{}")

    private class ScriptedProvider(
        private val responses: List<(ProviderRequest, io.github.mangi.eta.agent.runtime.AgentRunController) -> JSONObject>,
    ) : AgentProviderClient {
        constructor(vararg responses: JSONObject) : this(responses.map { response -> { _, _ -> response } })

        override val id: String = "scripted"
        override val capabilities: ProviderCapabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false,
        )
        private var index = 0

        override fun complete(
            request: ProviderRequest,
            runController: io.github.mangi.eta.agent.runtime.AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            val response = responses.getOrNull(index)
                ?: error("missing scripted response " + (index + 1))
            index += 1
            return ProviderResponse(response(request, runController))
        }
    }
}
