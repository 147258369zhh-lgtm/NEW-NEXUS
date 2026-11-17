package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.workflow.WorkflowRevisionStore
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.application.api.ExecutionFact
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RunUserInputTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val arguments get() = JSONObject().put("question", "Choose the report format")
        .put("options", JSONArray(listOf("PDF", "Markdown"))).put("allow_free_text", false)
    @Before fun setup() { Prefs.initLocal(context); SettingsDataStore.init(context) }

    @Test fun actualLoopWaitsForAnAnswerThenUsesItInTheNextModelTurnWithoutRestarting() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val controller = AgentRunController()
            val run = request("input-loop")
            val recorder = NexusExecutionRecorder(store, run)
            recorder.bindController(controller); recorder.start(); recorder.prepareInput(emptyList(), "Create a report", 0)
            var calls = 0
            val provider = object : AgentProviderClient {
                override val id = "input-fixture"
                override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
                override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    calls++
                    onEvent(ProviderEvent.RequestStarted)
                    if (calls > 1) {
                        val received = (0 until request.messages.length()).map(request.messages::getJSONObject)
                            .single { it.optString("role") == "tool" }
                        assertEquals("Markdown", JSONObject(received.getString("content")).getString("answer"))
                    }
                    return if (calls == 1) ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                        .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject().put("id", "ask-format")
                            .put("type", "function").put("function", JSONObject().put("name", RunUserInput.TOOL).put("arguments", arguments.toString())))))
                    else ProviderResponse(JSONObject().put("role", "assistant").put("content", "Report prepared as requested"))
                }
            }
            AgentLocalTools(context, AndroidAgentLogger, rootAvailable = { false }).use { tools ->
                val task = async(Dispatchers.IO) {
                    AgentModelClient.complete(AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture",
                        model = "fixture", systemPrompt = "fixture", supportsTools = true, browserTools = false, terminalTools = false,
                        subagentTools = false, autoCompactionEnabled = false), "Create a report",
                        AgentModelClient.ToolExecutor { call -> RuntimeInvocationScope.withController(controller) { tools.execute(call) } },
                        provider = provider, runController = controller, runBudget = run.budget,
                        onEvent = recorder::record, onTranscript = { recorder.saveCheckpoint(it, null) })
                }
                try {
                    val pending = withTimeout(5000) { RunUserInput.pending.first { it.isNotEmpty() }.single() }
                    assertEquals(RunState.WAITING_USER, store.find(run.runId)!!.state)
                    assertTrue(controller.waitingForUser)
                    val registry = RuntimeRunRegistry()
                    val wire = AgentRuntimeWire.RunRequest("input-loop", "fixture", AgentModelClient.ModelConfig(
                        baseUrl = "https://fixture.invalid", apiKey = "", model = "fixture", systemPrompt = ""), emptyList())
                    registry.admit(wire) { RuntimeRunRegistry.Run(wire, AgentRuntimeSession(wire.runId, controller)).also { it.prepared(wire) } }
                    assertEquals(AgentRuntimeWire.RunPhase.WaitingForInput, registry.snapshots().single().phase)
                    assertEquals(1, calls); assertFalse(task.isCompleted)
                    assertFalse(RunUserInput.answer(pending.part.requestId, "Unexpected"))
                    assertTrue(RunUserInput.answer(pending.part.requestId, "Markdown"))
                    val response = withTimeout(5000) { task.await() }
                    assertEquals("Report prepared as requested", response.content)
                    assertEquals(2, calls)
                    val checkpoint = JSONObject(store.checkpoint(run.runId)!!.payload)
                    val transcript = checkpoint.getJSONArray("transcript")
                    assertEquals(1, (0 until transcript.length()).count { transcript.getJSONObject(it).optString("role") == "tool" })
                    val ended = store.timeline(run.runId).single { it.event.type == RunEventType.USER_INPUT_RESOLVED }
                    val input = WorkflowRevisionStore(context).read(run.runId.value, "input", ended.fact.payloadRef!!)
                    assertEquals("Markdown", input.getJSONObject("result").getString("answer"))
                    assertFalse(store.timeline(run.runId).toString().contains("Choose the report format"))
                    assertFalse(RunUserInput.answer(pending.part.requestId, "PDF"))
                    assertFalse(controller.waitingForUser)
                    recorder.finish(AgentRuntimeWire.RunResult("input-loop", true, response.content), false)
                    val item = store.items(run.runId).single { it.kind == ItemKind.USER_INPUT_REQUEST }
                    assertEquals(RunState.SUCCEEDED, item.state); assertNotNull(item.completedAt)
                    assertEquals(RunState.SUCCEEDED, store.find(run.runId)!!.state)
                } finally { controller.cancel(); task.cancelAndJoin() }
            }
        }
        assertTrue(RunUserInput.pending.value.isEmpty())
    }

    @Test fun pauseAndCancelPreserveTheirOwnStatesAndReleaseThePendingQuestion() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val run = request("input-cancel"); val recorder = NexusExecutionRecorder(store, run); val controller = AgentRunController()
            recorder.bindController(controller); recorder.start()
            val task = async(Dispatchers.IO) { runCatching { RuntimeInvocationScope.withController(controller) { RunUserInput.execute(context, arguments) } } }
            try {
                val pending = withTimeout(5000) { RunUserInput.pending.first { it.isNotEmpty() }.single() }
                controller.pause()
                assertEquals(RunState.PAUSED, store.find(run.runId)!!.state)
                assertTrue(RunUserInput.answer(pending.part.requestId, "PDF"))
                assertEquals(RunState.PAUSED, store.find(run.runId)!!.state)
                controller.cancel()
                assertTrue(withTimeout(5000) { task.await() }.exceptionOrNull() is AgentRunCancelledException)
                recorder.finish(AgentRuntimeWire.RunResult("input-cancel", false, "", "cancelled"), true)
                assertEquals(RunState.CANCELLED, store.find(run.runId)!!.state)
            } finally { controller.cancel(); task.cancelAndJoin() }
        }
        assertTrue(RunUserInput.pending.value.isEmpty())
    }

    @Test fun timeoutSettlesTheQuestionAndReusesTheSameRun() {
        NexusExecutionStore(context).use { store ->
            val run = request("input-timeout"); val recorder = NexusExecutionRecorder(store, run); val controller = AgentRunController()
            recorder.bindController(controller); recorder.start()
            val result = RuntimeInvocationScope.withController(controller) { RunUserInput.execute(context, arguments, timeoutMillis = 1) }
            assertFalse(result.getBoolean("ok")); assertEquals("INPUT_TIMED_OUT", result.getString("code"))
            assertEquals(RunState.RUNNING, store.find(run.runId)!!.state)
            assertTrue(RunUserInput.pending.value.isEmpty())
            recorder.finish(AgentRuntimeWire.RunResult("input-timeout", true, "done"), false)
        }
    }

    @Test fun cancellationWhileWaitingCannotBeMistakenForAUserAnswer() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val run = request("input-stop"); val recorder = NexusExecutionRecorder(store, run); val controller = AgentRunController()
            recorder.bindController(controller); recorder.start()
            val task = async(Dispatchers.IO) { runCatching { RuntimeInvocationScope.withController(controller) { RunUserInput.execute(context, arguments) } } }
            try {
                val pending = withTimeout(5000) { RunUserInput.pending.first { it.isNotEmpty() }.single() }
                controller.cancel()
                assertTrue(withTimeout(5000) { task.await() }.exceptionOrNull() is AgentRunCancelledException)
                assertFalse(RunUserInput.answer(pending.part.requestId, "PDF"))
                val resolved = store.timeline(run.runId).single { it.event.type == RunEventType.USER_INPUT_RESOLVED }
                assertEquals("INPUT_CANCELLED", resolved.fact.errorCode); assertEquals(false, resolved.fact.success)
                recorder.finish(AgentRuntimeWire.RunResult("input-stop", false, "", "cancelled"), true)
            } finally { controller.cancel(); task.cancelAndJoin() }
        }
    }

    @Test fun orphanResolutionAndSuccessfulFinishWithAnUnansweredQuestionAreRejected() {
        NexusExecutionStore(context).use { store ->
            val run = request("input-integrity"); val recorder = NexusExecutionRecorder(store, run); val controller = AgentRunController()
            recorder.bindController(controller); recorder.start()
            assertThrows(IllegalStateException::class.java) {
                store.append(run.runId, ExecutionFact("orphan", RunEventType.USER_INPUT_RESOLVED, 1,
                    itemId = ItemId("missing"), success = true, payloadRef = "nexus-workflow://private/input/ref"))
            }
            controller.recordUserInput(RunInputChange("pending", RunInputPhase.REQUESTED, "nexus-workflow://private/input/ref"))
            assertThrows(IllegalStateException::class.java) {
                store.append(run.runId, ExecutionFact("terminal", RunEventType.RUN_SUCCEEDED, 2, state = RunState.SUCCEEDED))
            }
            recorder.finish(AgentRuntimeWire.RunResult("input-integrity", false, "", "interrupted"), false)
        }
    }

    @Test fun interruptedQuestionIsRestoredOnceAndConsumedOnlyAfterTheContinuationStarts() = runBlocking {
        val requestId = "input-recovery-${UUID.randomUUID()}"
        val conversationId = "conversation-recovery-${UUID.randomUUID()}"
        NexusExecutionStore(context).use { store ->
            val run = request(requestId)
            val recorder = NexusExecutionRecorder(store, run)
            val controller = AgentRunController()
            controller.bindConversationId(conversationId)
            recorder.bindController(controller)
            recorder.start()
            val body = JSONObject()
                .put("request_id", requestId)
                .put("run_id", run.runId.value)
                .put("conversation_id", conversationId)
                .put("source", RunSource.CHAT.name)
                .put("question", "Choose the recovered report format")
                .put("options", JSONArray(listOf("PDF", "Markdown")))
                .put("allow_free_text", false)
            val reference = WorkflowRevisionStore(context).save(run.runId.value, "input", body)
            controller.recordUserInput(RunInputChange(requestId, RunInputPhase.REQUESTED, reference))
            recorder.finish(AgentRuntimeWire.RunResult(runId = run.runId.value, ok = false,
                content = "", error = "interrupted"), cancelled = false)

            RunUserInput.restore(context)
            val recovered = withTimeout(5000) {
                RunUserInput.pending.first { requests -> requests.any { it.part.requestId == requestId } }
                    .single { it.part.requestId == requestId }
            }
            assertTrue(recovered.recovered)
            assertEquals(conversationId, recovered.conversationId)
            assertEquals("Choose the recovered report format", recovered.part.question)
            assertEquals(listOf("PDF", "Markdown"), recovered.part.options)

            assertFalse(RunUserInput.answer(requestId, "Markdown"))
            assertTrue(RunUserInput.finishRecovered(context, requestId, RunInputPhase.ANSWERED))
            assertFalse(RunUserInput.pending.value.any { it.part.requestId == requestId })

            RunUserInput.restore(context)
            assertFalse(RunUserInput.pending.value.any { it.part.requestId == requestId })
        }
    }

    @Test fun meetingQuestionRestoresItsIndependentRoomTarget() = runBlocking {
        val requestId = "input-meeting-recovery-${UUID.randomUUID()}"
        val roomId = "meeting-room-${UUID.randomUUID()}"
        NexusExecutionStore(context).use { store ->
            val run = request(requestId, RunSource.MEETING_HOST)
            val recorder = NexusExecutionRecorder(store, run)
            val controller = AgentRunController()
            controller.bindConversationId("meeting-conversation-$roomId")
            controller.bindExecutionTargetId(roomId)
            recorder.bindController(controller)
            recorder.start()
            val body = JSONObject()
                .put("request_id", requestId)
                .put("run_id", run.runId.value)
                .put("conversation_id", "meeting-conversation-$roomId")
                .put("target_id", roomId)
                .put("source", RunSource.MEETING_HOST.name)
                .put("question", "Choose the meeting deliverable")
                .put("options", JSONArray(listOf("Report", "Slides")))
                .put("allow_free_text", false)
            val reference = WorkflowRevisionStore(context).save(run.runId.value, "input", body)
            controller.recordUserInput(RunInputChange(requestId, RunInputPhase.REQUESTED, reference))
            recorder.finish(AgentRuntimeWire.RunResult(runId = run.runId.value, ok = false,
                content = "", error = "interrupted"), cancelled = false)

            RunUserInput.restore(context)
            val recovered = withTimeout(5000) {
                RunUserInput.pending.first { requests -> requests.any { it.part.requestId == requestId } }
                    .single { it.part.requestId == requestId }
            }
            assertEquals(RunSource.MEETING_HOST, recovered.source)
            assertEquals(roomId, recovered.targetId)
            assertTrue(recovered.recovered)
            assertTrue(RunUserInput.finishRecovered(context, requestId, RunInputPhase.ANSWERED))
        }
    }

    @Test fun subagentQuestionReturnsToItsParentConversationWithActorIdentity() = runBlocking {
        val requestId = "input-child-recovery-${UUID.randomUUID()}"
        val conversationId = "parent-conversation-${UUID.randomUUID()}"
        val actorId = "research-child-${UUID.randomUUID()}"
        NexusExecutionStore(context).use { store ->
            val run = request(requestId, RunSource.SUBAGENT)
            val recorder = NexusExecutionRecorder(store, run)
            val controller = AgentRunController()
            controller.bindConversationId(conversationId)
            controller.bindExecutionTargetId("""{"conversationId":"$conversationId"}""")
            controller.bindRecoverySurface(RunSource.CHAT)
            controller.bindRecoveryActorId(actorId)
            recorder.bindController(controller)
            recorder.start()
            val body = JSONObject()
                .put("request_id", requestId)
                .put("run_id", run.runId.value)
                .put("conversation_id", conversationId)
                .put("target_id", """{"conversationId":"$conversationId"}""")
                .put("source", RunSource.SUBAGENT.name)
                .put("recovery_surface", RunSource.CHAT.name)
                .put("actor_id", actorId)
                .put("question", "Choose the child task result")
                .put("options", JSONArray(listOf("Summary", "Full report")))
                .put("allow_free_text", false)
            val reference = WorkflowRevisionStore(context).save(run.runId.value, "input", body)
            controller.recordUserInput(RunInputChange(requestId, RunInputPhase.REQUESTED, reference))
            recorder.finish(AgentRuntimeWire.RunResult(runId = run.runId.value, ok = false,
                content = "", error = "interrupted"), cancelled = false)

            RunUserInput.restore(context)
            val recovered = withTimeout(5000) {
                RunUserInput.pending.first { requests -> requests.any { it.part.requestId == requestId } }
                    .single { it.part.requestId == requestId }
            }
            assertEquals(RunSource.CHAT, recovered.source)
            assertEquals(conversationId, recovered.conversationId)
            assertEquals(actorId, recovered.actorId)
            assertTrue(RunUserInput.finishRecovered(context, requestId, RunInputPhase.ANSWERED))
        }
    }

    private fun request(id: String, source: RunSource = RunSource.CHAT) = RunRequest(RunId(id), source, RunMode.CHAT, HostId("native-android"),
        SessionId("input-session"), budget = RunBudget(hardRounds = 3, hardToolCalls = 2, hardDurationMs = 30_000))
}
