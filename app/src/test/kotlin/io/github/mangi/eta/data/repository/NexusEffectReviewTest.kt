package io.github.mangi.eta.data.repository

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.EffectReviewPendingException
import io.github.mangi.eta.agent.runtime.NexusExecutionIds
import io.github.mangi.eta.agent.runtime.NexusExecutionRecorder
import io.github.zhh_lgtm.nexus.application.api.ExecutionFact
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NexusEffectReviewTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun close() {
        NexusExecutionStore.closeForTests()
        io.github.mangi.eta.data.db.EtaDatabase.closeForTests()
    }

    private fun start(store: NexusExecutionStore, runId: String): RunRequest {
        val request = RunRequest(RunId(runId), RunSource.CHAT, RunMode.CHAT, HostId("native-android"),
            sessionId = SessionId("session-$runId"))
        store.create(request, 1)
        store.append(request.runId, ExecutionFact("admitted", RunEventType.RUN_ADMITTED, 2, state = RunState.ADMITTED))
        store.append(request.runId, ExecutionFact("started", RunEventType.RUN_STARTED, 3, state = RunState.RUNNING))
        return request
    }

    private fun itemId(runId: String, key: String) = ItemId(NexusExecutionIds.item(RunId(runId), key))

    private fun startTool(store: NexusExecutionStore, runId: String, tool: String, item: ItemId, now: Long = 4) {
        store.append(RunId(runId), ExecutionFact("tool:start:${item.value}", RunEventType.TOOL_ITEM_STARTED, now,
            turnId = TurnId("turn-1"), itemId = item, toolName = tool))
    }

    @Test fun unknownEffectIsPersistentlyPendingAndCanOnlyBeReviewedOnce() = runBlocking {
        val store = NexusExecutionStore.get(context)
        val runId = "effect-review-run"
        val request = start(store, runId)
        val item = itemId(runId, "tool:1:call")
        startTool(store, runId, "write_file", item)
        store.append(request.runId, ExecutionFact("tool:finish:1:call", RunEventType.TOOL_ITEM_COMPLETED, 5,
            turnId = TurnId("turn-1"), itemId = item, state = RunState.VERIFYING, toolName = "write_file",
            success = null, errorCode = "EFFECT_REVIEW_REQUIRED"))

        assertEquals(RunState.VERIFYING, store.find(request.runId)?.state)
        val pending = store.items(request.runId).single { it.kind == ItemKind.TOOL_CALL }
        assertEquals(RunState.VERIFYING, pending.state)
        assertNotNull(pending.completedAt)
        assertEquals(1, store.pendingEffectReviewCount(request.runId))
        assertThrows(IllegalStateException::class.java) {
            store.append(request.runId, ExecutionFact("terminal", RunEventType.RUN_SUCCEEDED, 6, state = RunState.SUCCEEDED))
        }

        val detail = NexusExecutionDetails(context).read(item.value)!!
        assertEquals(RunState.VERIFYING, detail.state)
        assertEquals(EffectClass.REVERSIBLE_WRITE, detail.effectReview?.effectClass)
        assertTrue(detail.effectReview!!.pending)

        val first = store.resolveEffectReview(item, EffectReviewDecision.ACKNOWLEDGED, 7)
        assertEquals(RunState.INTERRUPTED, store.find(request.runId)?.state)
        assertEquals(RunState.INTERRUPTED, store.items(request.runId).single { it.kind == ItemKind.TOOL_CALL }.state)
        assertEquals(0, store.pendingEffectReviewCount(request.runId))
        assertEquals(1, store.timeline(request.runId).count { it.event.type == RunEventType.TOOL_ITEM_STARTED })
        assertEquals(EffectReviewDecision.ACKNOWLEDGED,
            NexusExecutionDetails(context).read(item.value)!!.effectReview?.decision)

        val before = store.timeline(request.runId).size
        assertEquals(first, store.resolveEffectReview(item, EffectReviewDecision.ACKNOWLEDGED, 8))
        assertEquals(before, store.timeline(request.runId).size)
        assertThrows(IllegalArgumentException::class.java) {
            store.resolveEffectReview(item, EffectReviewDecision.RERUN_REQUESTED, 9)
        }
        assertEquals(1, store.timeline(request.runId).count { it.event.type == RunEventType.TOOL_ITEM_STARTED })
    }

    @Test fun processRestartTurnsAnUnsettledExternalWriteIntoReviewWithoutReplay() {
        var store = NexusExecutionStore.get(context)
        val runId = "restart-review-run"
        val request = start(store, runId)
        val item = itemId(runId, "tool:1:write")
        startTool(store, runId, "write_file", item)
        store.close()
        NexusExecutionStore.closeForTests()
        store = NexusExecutionStore.get(context)

        assertEquals(1, store.reconcileAbandonedToolEffects(10))
        assertEquals(RunState.VERIFYING, store.find(request.runId)?.state)
        val pending = store.items(request.runId).single { it.kind == ItemKind.TOOL_CALL }
        assertEquals(RunState.VERIFYING, pending.state)
        val completion = store.timeline(request.runId).single { it.event.type == RunEventType.TOOL_ITEM_COMPLETED }
        assertNull(completion.fact.success)
        assertEquals("EFFECT_REVIEW_REQUIRED", completion.fact.errorCode)
        assertEquals(1, store.pendingEffectReviewCount(request.runId))
        assertEquals(0, store.reconcileAbandonedToolEffects(11))
        assertEquals(1, store.timeline(request.runId).count { it.event.type == RunEventType.TOOL_ITEM_STARTED })
        assertThrows(IllegalStateException::class.java) {
            store.append(request.runId, ExecutionFact("terminal", RunEventType.RUN_SUCCEEDED, 12, state = RunState.SUCCEEDED))
        }
    }

    @Test fun readOnlyRestartClosesAsFailureWithoutInventingASideEffectReview() {
        var store = NexusExecutionStore.get(context)
        val runId = "restart-read-run"
        val request = start(store, runId)
        val item = itemId(runId, "tool:1:read")
        startTool(store, runId, "read_file", item)
        store.close()
        NexusExecutionStore.closeForTests()
        store = NexusExecutionStore.get(context)

        assertEquals(1, store.reconcileAbandonedToolEffects(10))
        assertEquals(RunState.INTERRUPTED, store.find(request.runId)?.state)
        val closed = store.items(request.runId).single { it.kind == ItemKind.TOOL_CALL }
        assertEquals(RunState.FAILED, closed.state)
        val completion = store.timeline(request.runId).single { it.event.type == RunEventType.TOOL_ITEM_COMPLETED }
        assertEquals(false, completion.fact.success)
        assertEquals("TOOL_PROCESS_RESTARTED", completion.fact.errorCode)
        assertEquals(0, store.pendingEffectReviewCount(request.runId))
        assertThrows(IllegalStateException::class.java) {
            store.resolveEffectReview(item, EffectReviewDecision.ACKNOWLEDGED, 11)
        }
    }

    @Test fun idempotentWriteStillRequiresAnExplicitReviewAndNeverReplays() {
        var store = NexusExecutionStore.get(context)
        val runId = "restart-idempotent-run"
        val request = start(store, runId)
        val item = itemId(runId, "tool:1:volume")
        startTool(store, runId, "set_volume", item)
        store.close()
        NexusExecutionStore.closeForTests()
        store = NexusExecutionStore.get(context)

        assertEquals(1, store.reconcileAbandonedToolEffects(10))
        assertEquals(RunState.VERIFYING, store.find(request.runId)?.state)
        val detail = runBlocking { NexusExecutionDetails(context).read(item.value)!! }
        assertEquals(EffectClass.IDEMPOTENT_WRITE, detail.effectReview?.effectClass)
        assertEquals(ReplayPolicy.VERIFY_THEN_RESUME, detail.effectReview?.replayPolicy)
        store.resolveEffectReview(item, EffectReviewDecision.RERUN_REQUESTED, 11)
        assertEquals(RunState.INTERRUPTED, store.find(request.runId)?.state)
        assertTrue(NexusExecutionDetails(context).runBlockingRead(item.value)!!.effectReview!!.pending.not())
        assertEquals(1, store.timeline(request.runId).count { it.event.type == RunEventType.TOOL_ITEM_STARTED })
    }

    @Test fun reviewDoesNotOverrideAnUnresolvedInputOrApproval() {
        var store = NexusExecutionStore.get(context)
        val runId = "restart-waiting-run"
        val request = start(store, runId)
        val toolItem = itemId(runId, "tool:1:write")
        startTool(store, runId, "write_file", toolItem)
        store.append(request.runId, ExecutionFact("approval:begin", RunEventType.APPROVAL_REQUIRED, 5,
            itemId = ItemId("approval-1"), state = RunState.WAITING_AUTH, toolName = "terminal"))
        store.append(request.runId, ExecutionFact("input:begin", RunEventType.USER_INPUT_REQUIRED, 6,
            itemId = ItemId("input-1"), state = RunState.WAITING_AUTH,
            payloadRef = "nexus-input://fixture/input-1"))
        store.close()
        NexusExecutionStore.closeForTests()
        store = NexusExecutionStore.get(context)

        assertEquals(1, store.reconcileAbandonedToolEffects(10))
        assertEquals(RunState.WAITING_AUTH, store.find(request.runId)?.state)
        assertEquals(1, store.reconcileAbandonedApprovals(10))
        assertEquals(RunState.WAITING_USER, store.find(request.runId)?.state)
        assertEquals(RunState.WAITING_USER, store.items(request.runId).single { it.kind == ItemKind.USER_INPUT_REQUEST }.state)
        assertNull(store.items(request.runId).single { it.kind == ItemKind.USER_INPUT_REQUEST }.completedAt)
        assertEquals(RunState.FAILED, store.items(request.runId).single { it.kind == ItemKind.APPROVAL }.state)
        assertEquals(RunState.VERIFYING, store.items(request.runId).single { it.kind == ItemKind.TOOL_CALL }.state)

        store.resolveEffectReview(toolItem, EffectReviewDecision.ACKNOWLEDGED, 11)
        assertEquals(RunState.WAITING_USER, store.find(request.runId)?.state)
        assertEquals(RunState.WAITING_USER, store.items(request.runId).single { it.kind == ItemKind.USER_INPUT_REQUEST }.state)
        assertNull(store.items(request.runId).single { it.kind == ItemKind.USER_INPUT_REQUEST }.completedAt)
    }

    @Test fun recorderMarksSameProcessUnknownResultAndRefusesSuccess() {
        val store = NexusExecutionStore.get(context)
        val runId = "recorder-review-run"
        val request = RunRequest(RunId(runId), RunSource.CHAT, RunMode.CHAT, HostId("native-android"))
        val recorder = NexusExecutionRecorder(store, request)
        recorder.start()
        recorder.record(AgentEvent.ToolStarted(1, "call", "write_file", "PRIVATE_ARGUMENT"))
        recorder.record(AgentEvent.ToolFinished(1, "call", "write_file", "操作可能已执行，但未能确认完整结果",
            imageCount = 0, imageBytes = 0, success = true, effectUnknown = true))

        assertEquals(RunState.VERIFYING, store.find(request.runId)?.state)
        val item = store.items(request.runId).single { it.kind == ItemKind.TOOL_CALL }
        assertEquals(RunState.VERIFYING, item.state)
        val completion = store.timeline(request.runId).single { it.event.type == RunEventType.TOOL_ITEM_COMPLETED }
        assertNull(completion.fact.success)
        assertEquals(1, store.pendingEffectReviewCount(request.runId))
        assertThrows(EffectReviewPendingException::class.java) {
            recorder.finish(AgentRuntimeWireResult.ok(runId), false)
        }
        assertEquals(RunState.VERIFYING, store.find(request.runId)?.state)
        assertFalse(store.timeline(request.runId).toString().contains("PRIVATE_ARGUMENT"))
        assertFalse(store.timeline(request.runId).toString().contains("操作可能已执行"))
    }

    @Test fun structuredUnconfirmedStatusAndWireFlagReachTheReviewPath() {
        val formatter = AgentTraceFormatter()
        assertTrue(formatter.isUnconfirmedResult(AgentModelClient.ToolResult(
            """{"ok":false,"status":"unconfirmed","code":"STATE_CHANGE_UNCONFIRMED"}""")))
        assertFalse(formatter.isUnconfirmedResult(AgentModelClient.ToolResult("""{"ok":true,"status":"verified"}""")))
        val event = AgentEvent.ToolFinished(1, "call", "write_file", "操作可能已执行，但未能确认完整结果",
            imageCount = 0, imageBytes = 0, success = false, effectUnknown = true)
        assertEquals(event, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)))
    }

    private fun io.github.mangi.eta.data.repository.NexusExecutionDetails.runBlockingRead(itemId: String) =
        runBlocking { read(itemId) }
}

private object AgentRuntimeWireResult {
    fun ok(runId: String) = io.github.mangi.eta.agent.runtime.AgentRuntimeWire.RunResult(runId, true, "done")
}
