package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.kernel.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NexusExecutionRecorderTest {
    @Test fun productionTranscriptAndContextCallbacksCommitAVerifiableCheckpoint() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val request=RunRequest(RunId("checkpoint-run"),RunSource.MEETING_PARTICIPANT,RunMode.MEETING_PARTICIPANT,HostId("native-android"))
            val recorder=NexusExecutionRecorder(store,request)
            val session=AgentRuntimeSession("checkpoint-run",executionStartRecorder=recorder::start,
                checkpointRecorder=recorder::saveCheckpoint,eventRecorder=recorder::record,inputRecorder=recorder::prepareInput)
            session.beginExecution()
            session.prepareInput(AgentRuntimeWire.RunRequest("checkpoint-run","original task",
                io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig(baseUrl="https://fixture.invalid",
                    apiKey="NEVER_PERSIST_MODEL_KEY",model="fixture",systemPrompt=""),images=emptyList()))
            session.emit(AgentEvent.RoundStarted(1,1))
            val transcript=listOf(io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage("assistant","actual content"))
            session.updateTranscript(transcript)
            val context=io.github.mangi.eta.agent.model.AgentContextSnapshot(operationId="checkpoint-run",messages=transcript)
            session.updateContext(context)
            val checkpoint=store.checkpoint(request.runId)!!
            assertEquals(store.find(request.runId)?.lastSequence,checkpoint.throughSequence)
            val payload=org.json.JSONObject(checkpoint.payload)
            assertEquals("actual content",payload.getJSONArray("transcript").getJSONObject(0).getString("content"))
            assertEquals("checkpoint-run",payload.getJSONObject("context").getString("operationId"))
            assertEquals("original task",payload.getJSONArray("input").getJSONObject(0).getString("content"))
            assertFalse(checkpoint.payload.contains("NEVER_PERSIST_MODEL_KEY"))
            assertFalse(checkpoint.toString().contains("original task"))
            assertFalse(store.timeline(request.runId).toString().contains("actual content"))
        }
    }

    @Test fun explicitExecutionStartCoversCompactionWithoutModelRunStartedEvent() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val request=RunRequest(RunId("compact"),RunSource.CHAT,RunMode.TASK,HostId("native-android"))
            val recorder=NexusExecutionRecorder(store,request)
            val session=AgentRuntimeSession("compact",operation=AgentRuntimeWire.OP_COMPACT,
                executionStartRecorder=recorder::start,terminalRecorder={recorder.finish(it,false)})
            session.beginExecution()
            session.complete(AgentRuntimeWire.RunResult("compact",true,"",operation=AgentRuntimeWire.OP_COMPACT))
            assertEquals(RunState.SUCCEEDED,store.find(request.runId)?.state)
            assertEquals(1,store.timeline(request.runId).count { it.event.type == RunEventType.RUN_STARTED })
        }
    }

    @Test fun productionAccessibilityWaitIsAPairedDurableEventWithoutPrivatePayloads() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val request = RunRequest(RunId("accessibility-event"), RunSource.CHAT, RunMode.CHAT,
                HostId("native-android"))
            val recorder = NexusExecutionRecorder(store, request)
            val session = AgentRuntimeSession("accessibility-event", executionStartRecorder = recorder::start,
                eventRecorder = recorder::record, terminalRecorder = { recorder.finish(it, cancelled = false) })
            session.beginExecution()
            assertTrue(session.emit(AgentEvent.AccessibilityWaiting(1, AccessibilityWaitPhase.WAITING)))
            assertEquals(RunState.WAITING_EVENT, store.find(request.runId)?.state)
            val waiting = store.items(request.runId).single()
            assertEquals(ItemKind.EVENT_WAIT, waiting.kind)
            assertEquals(RunState.WAITING_EVENT, waiting.state)
            assertEquals("nexus-event://accessibility-service/1", waiting.payloadRef)

            assertTrue(session.emit(AgentEvent.AccessibilityWaiting(1, AccessibilityWaitPhase.RESTORED)))
            assertEquals(RunState.RUNNING, store.find(request.runId)?.state)
            val restored = store.items(request.runId).single()
            assertEquals(RunState.SUCCEEDED, restored.state)
            assertNotNull(restored.completedAt)
            val timeline = store.timeline(request.runId)
            assertEquals(1, timeline.count { it.event.type == RunEventType.EVENT_WAIT_REQUIRED })
            assertEquals(1, timeline.count { it.event.type == RunEventType.EVENT_WAIT_RESOLVED })
            assertEquals(1, timeline.filter {
                it.event.type in setOf(RunEventType.EVENT_WAIT_REQUIRED, RunEventType.EVENT_WAIT_RESOLVED)
            }.map { it.event.itemId }.toSet().size)
            assertTrue(session.complete(AgentRuntimeWire.RunResult("accessibility-event", true, "private answer")))
            assertEquals(RunState.SUCCEEDED, store.find(request.runId)?.state)
            assertFalse(store.timeline(request.runId).toString().contains("private answer"))
        }
    }

    @Test fun productionSessionWritesTypedFactsAndMatchingToolIdsWithoutPayloadText() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val request = RunRequest(RunId("recorded"), RunSource.CHAT, RunMode.CHAT, HostId("native-android"))
            val recorder = NexusExecutionRecorder(store, request)
            val session = AgentRuntimeSession("recorded", eventRecorder = recorder::record,
                terminalRecorder = { recorder.finish(it, cancelled = false) })
            session.emit(AgentEvent.RunStarted(0,0,1,false))
            session.emit(AgentEvent.RoundStarted(1,1))
            session.emit(AgentEvent.ToolStarted(1,"call","read_file","secret argument"))
            session.emit(AgentEvent.ToolFinished(1,"call","read_file","secret result",0,0,true))
            assertTrue(session.complete(AgentRuntimeWire.RunResult("recorded", true, "private answer")))
            val timeline = store.timeline(request.runId)
            val started = timeline.single { it.event.type == RunEventType.TOOL_ITEM_STARTED }
            val ended = timeline.single { it.event.type == RunEventType.TOOL_ITEM_COMPLETED }
            assertEquals(started.event.itemId, ended.event.itemId)
            assertEquals(started.event.turnId, ended.event.turnId)
            assertEquals(RunState.SUCCEEDED, store.find(request.runId)?.state)
            assertFalse(timeline.toString().contains("secret"))
            assertFalse(timeline.toString().contains("private answer"))
        }
    }

    @Test fun failureToRecordToolBoundaryPreventsExecutionPastTheSession() {
        var enteredTool = false
        val session = AgentRuntimeSession("failed-boundary", eventRecorder = {
            if (it is AgentEvent.ToolStarted) throw IllegalStateException("fixture storage failure")
        })
        assertThrows(IllegalStateException::class.java) {
            session.emit(AgentEvent.ToolStarted(1,"call","write_file","{}"))
            enteredTool = true
        }
        assertFalse(enteredTool)
    }
}
