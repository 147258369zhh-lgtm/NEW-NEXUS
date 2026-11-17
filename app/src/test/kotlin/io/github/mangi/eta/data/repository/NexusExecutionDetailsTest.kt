package io.github.mangi.eta.data.repository

import android.app.Application
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.agent.workflow.WorkflowRevisionStore
import io.github.mangi.eta.agent.workflow.WorkflowToolExecutor
import io.github.mangi.eta.ui.app.AgentConversationStore
import io.github.mangi.eta.ui.app.AgentRunMessageProjector
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
class NexusExecutionDetailsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @After fun close() { NexusExecutionStore.closeForTests(); io.github.mangi.eta.data.db.EtaDatabase.closeForTests() }

    @Test fun actualLoopLinksTheExactPlanRevisionToThePersistedToolRow() = runBlocking {
        val store = NexusExecutionStore.get(context)
        val request = RunRequest(RunId("plan-detail"), RunSource.CHAT, RunMode.PLAN, HostId("native-android"), SessionId("chat-plan"))
        val controller = AgentRunController()
        val recorder = NexusExecutionRecorder(store, request)
        recorder.bindController(controller); recorder.start()
        val plan = JSONObject().put("action", "replace").put("expected_revision", 0)
            .put("steps", JSONArray().put(JSONObject().put("id", "inspect").put("title", "Check source file")
                .put("verification", "Compare the report with the source")))
        var calls = 0
        val provider = object : AgentProviderClient {
            override val id = "plan-detail-fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                onEvent(ProviderEvent.RequestStarted)
                return if (++calls == 1) ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                    .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject().put("id", "save-plan")
                        .put("type", "function").put("function", JSONObject().put("name", "plan_manage").put("arguments", plan.toString())))))
                else ProviderResponse(JSONObject().put("role", "assistant").put("content", "Plan saved"))
            }
        }
        val projector = AgentRunMessageProjector()
        var messages = emptyList<io.github.mangi.eta.ui.model.AgentChatMessageUi>()
        val events = mutableListOf<AgentEvent>()
        val response = AgentLoop(AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "fixture",
            systemPrompt = "fixture", supportsTools = true, autoCompactionEnabled = false),
            JSONArray().put(AgentConversationCodec.userTextMessage("Prepare a plan")), AgentWorkflowToolCatalog.schemas(), provider,
            AgentModelClient.ToolExecutor { call -> AgentModelClient.ToolResult(RuntimeInvocationScope.withController(controller) {
                WorkflowToolExecutor.execute(context, "chat-plan", call.name, JSONObject(call.argumentsJson)).toString()
            }) }, controller, AgentTraceFormatter(), onEvent = {
                recorder.record(it); events += it; messages = projector.applyEvent("plan-detail", it, messages)
            }).run()
        assertEquals("Plan saved", response.content)
        val tool = messages.filterIsInstance<ToolActivityMessageUi>().single()
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertNotNull(finished.workflowReference)
        assertEquals(finished, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(finished)))
        assertEquals(finished, AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(finished)))
        val detail = NexusExecutionDetails(context).read(tool.executionItemId!!)!!
        assertEquals(RunState.SUCCEEDED, detail.state)
        assertEquals(1L, detail.revision)
        assertEquals("Check source file", detail.steps.single().title)
        val later = JSONObject(plan.toString()).put("expected_revision", 1)
        later.getJSONArray("steps").getJSONObject(0).put("title", "Changed later")
        assertTrue(RuntimeInvocationScope.withController(controller) { WorkflowToolExecutor.execute(context, "chat-plan", "plan_manage", later) }.getBoolean("ok"))
        assertEquals("Check source file", NexusExecutionDetails(context).read(tool.executionItemId!!)!!.steps.single().title)
        recorder.finish(AgentRuntimeWire.RunResult("plan-detail", true, response.content), false)
        val state = AgentChatHomeUiState(messages = messages, input = "", isStreaming = false, thinkingEnabled = false)
        AgentConversationStore.save(context, "chat-plan", mapOf("chat-plan" to state), mapOf("chat-plan" to "Plan"), mapOf("chat-plan" to 1L))
        val restored = AgentConversationStore.loadConversation(context, "chat-plan")!!.messages.filterIsInstance<ToolActivityMessageUi>().single()
        assertEquals(tool.executionItemId, restored.executionItemId)
        assertNotNull(NexusExecutionDetails(context).read(restored.executionItemId!!))
    }

    @Test fun missingOrCorruptRevisionNeverFallsBackToTheLatestMutablePlan() = runBlocking {
        val store = NexusExecutionStore.get(context)
        val run = RunRequest(RunId("missing-plan"), RunSource.CHAT, RunMode.PLAN, HostId("native-android"))
        val recorder = NexusExecutionRecorder(store, run); recorder.start()
        val ref = WorkflowRevisionStore(context).save("chat", "plan", JSONObject().put("revision", 1).put("steps", JSONArray()))
        recorder.record(AgentEvent.ToolStarted(1, "plan", "plan_manage", "plan"))
        recorder.record(AgentEvent.ToolFinished(1, "plan", "plan_manage", "done", 0, 0, true, workflowReference = ref))
        val id = NexusExecutionIds.tool("missing-plan", 1, "plan")
        assertFalse(NexusExecutionDetails(context).read(id)!!.contentUnavailable)
        val digest = ref.substringAfterLast('/')
        java.io.File(context.filesDir, "workflow-state/revisions").walkTopDown().first { it.name == "$digest.json" }.writeText("corrupt fixture")
        assertTrue(NexusExecutionDetails(context).read(id)!!.contentUnavailable)
        assertNull(NexusExecutionDetails(context).read("item-" + "0".repeat(64)))
        assertNull(NexusExecutionDetails(context).read("../unsafe"))
        recorder.finish(AgentRuntimeWire.RunResult("missing-plan", true, "done"), false)
    }

    @Test fun originalUserInputRevisionCanBeReadAsARecordWithoutReopeningThePrompt() = runBlocking {
        val store = NexusExecutionStore.get(context)
        val run = RunRequest(RunId("history-input"), RunSource.CHAT, RunMode.CHAT, HostId("native-android"))
        val recorder = NexusExecutionRecorder(store, run); val controller = AgentRunController()
        recorder.bindController(controller); recorder.start()
        val body = JSONObject().put("question", "Report format?").put("result", JSONObject().put("answer", "Markdown"))
        val ref = WorkflowRevisionStore(context).save("history-input", "input", body)
        controller.recordUserInput(RunInputChange("question", RunInputPhase.REQUESTED, ref))
        controller.recordUserInput(RunInputChange("question", RunInputPhase.ANSWERED, ref))
        val item = store.items(run.runId).single { it.kind == ItemKind.USER_INPUT_REQUEST }
        val detail = NexusExecutionDetails(context).read(item.itemId.value)!!
        assertEquals("Report format?", detail.text)
        assertEquals("Markdown", detail.answer)
        assertTrue(RunUserInput.pending.value.isEmpty())
        recorder.finish(AgentRuntimeWire.RunResult("history-input", true, "done"), false)
    }
}
