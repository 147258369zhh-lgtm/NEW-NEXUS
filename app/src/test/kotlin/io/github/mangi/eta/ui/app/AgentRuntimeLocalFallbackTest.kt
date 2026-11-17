package io.github.mangi.eta.ui.app

import android.app.Application
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.EndpointKind
import io.github.mangi.eta.agent.model.ProviderCapabilities
import io.github.mangi.eta.agent.model.ProviderEvent
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderResponse
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.NexusRunMapper
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.application.api.ExecutionRunSnapshot
import io.github.zhh_lgtm.nexus.application.api.ExecutionStore
import io.github.zhh_lgtm.nexus.kernel.api.ItemKind
import io.github.zhh_lgtm.nexus.kernel.api.RunRequest
import io.github.zhh_lgtm.nexus.kernel.api.RunEventType
import io.github.zhh_lgtm.nexus.kernel.api.RunState
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeLocalFallbackTest {
    private val store = NexusExecutionStore(RuntimeEnvironment.getApplication())

    @After fun closeStore() { store.close() }

    private val request = AgentRuntimeWire.RunRequest(
        runId = "fallback-test", prompt = "你好", images = emptyList(),
        config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture",
            systemPrompt = "保持既有约束", hostedWebSearchEnabled = true,
            extraBodyJson = "{\"tools\":[{\"type\":\"web_search\"}]}",
            terminalTools = true, browserTools = true, deviceDirectTools = true,
            deviceSensitiveReadTools = true, deviceSensitiveActionTools = true, subagentTools = true,
        ),
    )
    private val failure = AgentRuntimeWire.RunResult(request.runId, false, "", "Agent Runtime 服务绑定失败")

    @Test
    fun onlyProvenPreExecutionFailuresCanFallback() {
        listOf(
            "Agent Runtime 服务绑定失败", "无法启动后台执行服务，请返回 Nexus 后重试", "Agent Runtime 无法准备请求",
        ).forEach { error ->
            assertTrue(error, AgentRuntimeLocalFallback.shouldUse(request, failure.copy(error = error)))
        }
        listOf(
            "Agent Runtime 服务连接已断开", "Agent Runtime 请求发送失败（SecurityException）",
            "HTTP 401 Unauthorized", "请先在 Nexus 中配置可用的模型", null,
            "HTTP 500: Agent Runtime 服务绑定失败",
        ).forEach { error ->
            assertFalse(error, AgentRuntimeLocalFallback.shouldUse(request, failure.copy(error = error)))
        }
    }

    @Test
    fun compactionAndReplyRewriteMustNeverBecomeChatRequests() {
        listOf(AgentRuntimeWire.OP_COMPACT, AgentRuntimeWire.OP_REWRITE_REPLY).forEach { operation ->
            assertFalse(AgentRuntimeLocalFallback.shouldUse(request.copy(operation = operation), failure))
            assertFalse(AgentRuntimeLocalFallback.shouldUse(request, failure.copy(operation = operation)))
        }
    }

    @Test
    fun completedOrRecoverableResultsAreNeverOverwrittenByFallback() {
        listOf(
            failure.copy(ok = true), failure.copy(runId = "another-run"),
            failure.copy(content = "真实结果"), failure.copy(reasoningContent = "已有思考"),
            failure.copy(transcript = listOf(AgentModelClient.ConversationMessage("assistant", "已完成工具"))),
            failure.copy(contextSnapshot = AgentContextSnapshot(operationId = request.runId, messages = emptyList())),
            failure.copy(contextSnapshotRef = request.runId),
        ).forEach { assertFalse(AgentRuntimeLocalFallback.shouldUse(request, it)) }
    }

    @Test
    fun fallbackPublishesNoToolSchemasAndDisablesHostedSearch() {
        var calls = 0
        val events = mutableListOf<AgentEvent>()
        val result = AgentRuntimeLocalFallback.run(request, AgentRunController(), store, provider { outgoing ->
            calls++
            assertEquals(0, outgoing.effectiveTools.length())
            assertFalse(outgoing.config.hostedWebSearchEnabled)
            assertFalse(outgoing.config.supportsSearchSuggestions)
            assertEquals("", outgoing.config.extraBodyJson)
            assertTrue(outgoing.config.customBody.isEmpty())
            assertTrue(outgoing.messages.toString().contains("保持既有约束"))
            response("你好")
        }, onEvent = events::add)
        assertTrue(result.ok)
        assertEquals("你好", result.content)
        assertEquals(1, calls)
        assertEquals(0, events.filterIsInstance<AgentEvent.RunStarted>().single().toolCount)
    }

    @Test
    fun stopBeforeFallbackPreventsAnyProviderCall() {
        val controller = AgentRunController().apply { cancel() }
        var calls = 0
        val result = AgentRuntimeLocalFallback.run(request, controller, store, provider {
            calls++
            response("不应调用")
        }) {}
        assertFalse(result.ok)
        assertEquals(0, calls)
    }

    @Test
    fun fallbackCommitsAnIndependentRunTimelineWithoutToolItems() {
        val ledgerId = NexusRunMapper.fromLegacy(
            request.copy(runId = "${request.runId}:local-fallback"),
        ).request.runId
        val events = mutableListOf<AgentEvent>()
        val persistedSequences = mutableListOf<Long>()
        val result = AgentRuntimeLocalFallback.run(request, AgentRunController(), store, provider {
            response("你好")
        }) { event ->
            persistedSequences += store.find(ledgerId)?.lastSequence ?: -1
            events += event
        }

        assertTrue(result.ok)
        assertNotEquals(request.runId, ledgerId.value)
        val timeline = store.timeline(ledgerId)
        assertEquals(listOf(RunEventType.RUN_CREATED, RunEventType.RUN_ADMITTED, RunEventType.RUN_STARTED),
            timeline.take(3).map { it.fact.type })
        assertTrue(timeline.any { it.fact.type == RunEventType.TURN_STARTED })
        assertTrue(timeline.any { it.fact.type == RunEventType.ASSISTANT_ITEM_COMPLETED })
        assertEquals(RunEventType.RUN_SUCCEEDED, timeline.last().fact.type)
        assertEquals(RunState.SUCCEEDED, store.find(ledgerId)?.state)
        assertTrue(store.items(ledgerId).none {
            it.kind == ItemKind.TOOL_CALL || it.kind == ItemKind.TOOL_RESULT
        })
        assertTrue(events.isNotEmpty())
        assertTrue(persistedSequences.all { it >= 0 })

        val failingStore = object : ExecutionStore by store {
            override fun create(request: RunRequest, now: Long): ExecutionRunSnapshot =
                error("ledger unavailable")
        }
        var calls = 0
        val failed = AgentRuntimeLocalFallback.run(request, AgentRunController(), failingStore, provider {
            calls++
            response("不应调用")
        }) {}

        assertFalse(failed.ok)
        assertEquals(0, calls)
        assertEquals("LOCAL_FALLBACK_LEDGER_FAILED", failed.failureCode)
    }

    private fun response(text: String) = ProviderResponse(
        JSONObject().put("role", "assistant").put("content", text).put("finish_reason", "stop"),
    )

    private fun provider(block: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = block(request)
    }
}
