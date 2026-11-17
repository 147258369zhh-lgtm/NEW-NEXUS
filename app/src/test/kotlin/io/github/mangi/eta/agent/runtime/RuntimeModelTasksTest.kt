package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RuntimeModelTasksTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "NEVER_SAVE_API_KEY",
        providerId = "fixture", model = "fixture", systemPrompt = "ordinary chat instructions", supportsTools = true,
        terminalTools = true, browserTools = true, subagentTools = true, hostedWebSearchEnabled = true)

    private fun provider(block: (ProviderRequest, AgentRunController, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "model-task-fixture"
        override val capabilities = OpenAiChatCompletionsProvider.capabilities
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
            onEvent(ProviderEvent.RequestStarted)
            return block(request, runController, onEvent)
        }
    }
    private fun request(kind: RuntimeModelTaskKind = RuntimeModelTaskKind.SKILL) =
        RuntimeModelTasks.request(kind, config, "extract-only", "private-source-data", "source-1")

    @Test fun onlyVisionEnabledNotepadTasksAcceptImages() {
        val image = AgentModelClient.ModelImage(reference = "data:image/png;base64,AQ==", mimeType = "image/png", bytes = 1, source = "notepad_attachment")
        val accepted = RuntimeModelTasks.request(RuntimeModelTaskKind.NOTEPAD, config.copy(supportsImageInput = true),
            "organize", "image", "note", images = listOf(image))
        assertEquals(listOf(image), accepted.images)
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeModelTasks.request(RuntimeModelTaskKind.MEMORY, config, "extract", "image", "note", images = listOf(image))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeModelTasks.request(RuntimeModelTaskKind.NOTEPAD, config.copy(supportsImageInput = false), "organize", "image", "note", images = listOf(image))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeModelTasks.request(RuntimeModelTaskKind.NOTEPAD, config, "organize", "image", "note", images = List(5) { image })
        }
    }

    @Test fun notepadImagesReachTheModelProvider() {
        val image = AgentModelClient.ModelImage(reference = "data:image/png;base64,AQ==", mimeType = "image/png", bytes = 1, source = "notepad_attachment")
        NexusExecutionStore(context).use { store ->
            val runtime = RuntimeModelTaskHarness(context, store) { provider { input, _, _ ->
                val content = input.messages.getJSONObject(1).getJSONArray("content")
                assertEquals("text", content.getJSONObject(0).getString("type"))
                assertEquals("image_url", content.getJSONObject(1).getString("type"))
                assertEquals(image.reference, content.getJSONObject(1).getJSONObject("image_url").getString("url"))
                ProviderResponse(JSONObject().put("role", "assistant").put("content", "organized").put("finish_reason", "stop"))
            } }
            val request = RuntimeModelTasks.request(RuntimeModelTaskKind.NOTEPAD, config.copy(supportsImageInput = true),
                "organize", "photo", "note", images = listOf(image))
            val wire = AgentRuntimeWire.runRequestFromBundle(AgentRuntimeWire.toLegacyBundle(request))
            val result = runtime.run(wire, AgentRunController())
            assertTrue(result.error, result.ok)
            assertEquals("organized", result.content)
        }
    }

    @Test fun allMaintenanceKindsUseAdmissionBudgetMetricsAndExactToolFreeInstructions() {
        for (kind in RuntimeModelTaskKind.entries) NexusExecutionStore(context).use { store ->
            val runtime = RuntimeModelTaskHarness(context, store) { provider { input, _, _ ->
                assertEquals(kind.purpose, input.purpose)
                assertEquals(2, input.messages.length())
                assertEquals("extract-only", input.messages.getJSONObject(0).getString("content"))
                assertEquals("private-source-data", input.messages.getJSONObject(1).getString("content"))
                assertFalse(input.config.supportsTools); assertFalse(input.config.hostedWebSearchEnabled)
                assertEquals(0, input.effectiveTools.length())
                ProviderResponse(JSONObject().put("role", "assistant").put("content", "{\"reusable\":false}").put("finish_reason", "stop"))
            } }
            val request = request(kind)
            val wire = AgentRuntimeWire.runRequestFromBundle(AgentRuntimeWire.toBundle(request, emptyList()))
            assertEquals(request.copy(config = request.config.copy(reasoningEffort = request.config.effectiveReasoningEffort)), wire)
            val result = runtime.run(wire, AgentRunController())
            assertTrue(result.error, result.ok)
            assertEquals(1, runtime.results.size)
            val id = NexusRunMapper.canonicalRunId(request.runId)
            val saved = store.find(id)!!
            assertEquals(RunState.SUCCEEDED, saved.state)
            assertEquals(kind.timeoutMs, saved.request.budget.hardDurationMs)
            assertEquals(if (kind == RuntimeModelTaskKind.TRANSCRIPT) RunSource.CHAT else RunSource.NOTE_CONSOLIDATION, saved.request.source)
            assertEquals(1L, store.modelBudgetTotals(id).requests)
            assertEquals(0L, store.modelBudgetTotals(id).openRequests)
            assertEquals("succeeded", runtime.snapshots.last().status)
            assertEquals(1, runtime.snapshots.last().metrics.requestAttempts)
            assertFalse(store.timeline(id).toString().contains("private-source-data"))
            assertFalse(store.checkpoint(id)!!.payload.contains("NEVER_SAVE_API_KEY"))
        }
    }

    @Test fun admissionPreservesStricterCallerAndGoalDurationBudgets() {
        val task = request(RuntimeModelTaskKind.MEMORY)
        val mapping = NexusRunMapper.fromLegacy(task, RunBudget(hardDurationMs = 60_000),
            NexusRunContext(goalId = GoalId("goal"), goalBudget = RunBudget(hardDurationMs = 30_000)))
        assertEquals(30_000L, mapping.request.budget.hardDurationMs)
    }

    @Test fun unexpectedToolsAndTruncatedResultsCannotBecomeMemoryOrSkillCandidates() {
        val responses = listOf(
            ProviderResponse(JSONObject().put("role", "assistant").put("content", "partial").put("finish_reason", "length")),
            ProviderResponse(JSONObject().put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
                .put("tool_calls", JSONArray().put(JSONObject().put("id", "bad").put("type", "function")
                    .put("function", JSONObject().put("name", "write_file").put("arguments", "{}")))))
        )
        for (reply in responses) NexusExecutionStore(context).use { store ->
            val runtime = RuntimeModelTaskHarness(context, store) { provider { _, _, _ -> reply } }
            val request = request()
            val result = runtime.run(request, AgentRunController())
            assertFalse(result.ok)
            val id = NexusRunMapper.canonicalRunId(request.runId)
            assertEquals(RunState.FAILED, store.find(id)!!.state)
            assertTrue(store.items(id).none { it.kind == ItemKind.TOOL_CALL })
            assertTrue(result.content.isBlank())
        }
    }

    @Test fun foregroundPreemptionCancelsTheActualProviderAndClosesTheSameRun() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val entered = CountDownLatch(1); val released = CountDownLatch(1)
            val runtime = RuntimeModelTaskHarness(context, store) { provider { _, controller, _ ->
                val binding = controller.register { released.countDown() }
                try { entered.countDown(); check(released.await(5, TimeUnit.SECONDS)); controller.throwIfCancelled()
                    error("Cancelled provider must not succeed") } finally { binding.close() }
            } }
            val owner = AgentRunController(); val request = request()
            val execution = async(Dispatchers.IO) { runtime.run(request, owner) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); owner.cancel()
                val result = withTimeout(5000) { execution.await() }
                assertFalse(result.ok); assertEquals("已停止", result.error)
                val id = NexusRunMapper.canonicalRunId(request.runId)
                assertEquals(RunState.CANCELLED, store.find(id)!!.state)
                assertEquals(0L, store.modelBudgetTotals(id).openRequests)
            } finally { owner.cancel(); execution.cancelAndJoin() }
        }
    }
}
