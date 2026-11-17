package io.github.mangi.eta.data.repository

import android.app.Application
import io.github.zhh_lgtm.nexus.application.api.ExecutionFact
import io.github.zhh_lgtm.nexus.kernel.api.*
import java.util.Collections
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NexusExecutionStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var store = NexusExecutionStore(context)
    private val request = RunRequest(RunId("run-1"), RunSource.CHAT, RunMode.CHAT, HostId("native-android"),
        sessionId = SessionId("session-1"), budget = RunBudget(hardRounds = 2, hardToolCalls = 3))

    @After fun close() { store.close() }

    private fun start() {
        store.create(request, 1)
        store.append(request.runId, ExecutionFact("admitted", RunEventType.RUN_ADMITTED, 2, state = RunState.ADMITTED))
        store.append(request.runId, ExecutionFact("started", RunEventType.RUN_STARTED, 3, state = RunState.RUNNING))
    }

    @Test fun approvalsRequireMatchingIdentityAndMustSettleBeforeSuccessfulTerminal() {
        start()
        val requested = ExecutionFact("approve:begin", RunEventType.APPROVAL_REQUIRED, 4,
            itemId = ItemId("approval-1"), state = RunState.WAITING_AUTH, toolName = "terminal")
        val resolved = requested.copy(key = "approve:end", type = RunEventType.APPROVAL_RESOLVED,
            createdAt = 5, state = RunState.RUNNING, success = true)
        assertThrows(IllegalStateException::class.java) { store.append(request.runId, resolved) }
        store.append(request.runId, requested)
        assertEquals(RunState.WAITING_AUTH, store.items(request.runId).single().state)
        assertThrows(IllegalArgumentException::class.java) {
            store.append(request.runId, resolved.copy(toolName = "another-tool"))
        }
        assertThrows(IllegalStateException::class.java) {
            store.append(request.runId, ExecutionFact("terminal", RunEventType.RUN_SUCCEEDED, 6, state = RunState.SUCCEEDED))
        }
        store.append(request.runId, resolved)
        assertThrows(IllegalStateException::class.java) { store.append(request.runId, resolved.copy(key = "approve:duplicate")) }
        store.append(request.runId, ExecutionFact("terminal", RunEventType.RUN_SUCCEEDED, 6, state = RunState.SUCCEEDED))
        assertEquals(RunState.SUCCEEDED, store.find(request.runId)?.state)
    }

    @Test fun eventWaitRequiresMatchingIdentityAndMustSettleBeforeSuccessfulTerminal() {
        start()
        val itemId = ItemId("event-1")
        val payloadRef = "nexus-event://accessibility-service/1"
        val required = ExecutionFact("event:begin", RunEventType.EVENT_WAIT_REQUIRED, 4,
            itemId = itemId, state = RunState.WAITING_EVENT, payloadRef = payloadRef)
        val resolved = required.copy(key = "event:end", type = RunEventType.EVENT_WAIT_RESOLVED,
            createdAt = 5, state = RunState.RUNNING, success = true)

        assertThrows(IllegalStateException::class.java) { store.append(request.runId, resolved) }
        store.append(request.runId, required)
        assertEquals(RunState.WAITING_EVENT, store.find(request.runId)?.state)
        assertEquals(ItemKind.EVENT_WAIT, store.items(request.runId).single().kind)
        assertEquals(RunState.WAITING_EVENT, store.items(request.runId).single().state)
        assertThrows(IllegalStateException::class.java) {
            store.append(request.runId, ExecutionFact("terminal", RunEventType.RUN_SUCCEEDED, 6,
                state = RunState.SUCCEEDED))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.append(request.runId, resolved.copy(payloadRef = "nexus-event://another/1"))
        }
        store.append(request.runId, resolved)
        assertEquals(RunState.SUCCEEDED, store.items(request.runId).single().state)
        assertNotNull(store.items(request.runId).single().completedAt)
    }

    @Test fun processRestartClosesAbandonedEventWaitBeforeAnyExternalActionCanBeReplayed() {
        start()
        store.append(request.runId, ExecutionFact("event:begin", RunEventType.EVENT_WAIT_REQUIRED, 4,
            itemId = ItemId("event-1"), state = RunState.WAITING_EVENT,
            payloadRef = "nexus-event://accessibility-service/1"))
        store.close()
        store = NexusExecutionStore(context)

        assertEquals(1, store.reconcileAbandonedEventWaits(10))
        assertEquals(RunState.INTERRUPTED, store.find(request.runId)?.state)
        val item = store.items(request.runId).single()
        assertEquals(ItemKind.EVENT_WAIT, item.kind)
        assertEquals(RunState.FAILED, item.state)
        val resolution = store.timeline(request.runId).single { it.event.type == RunEventType.EVENT_WAIT_RESOLVED }
        assertFalse(resolution.fact.success!!)
        assertEquals("EVENT_PROCESS_RESTARTED", resolution.fact.errorCode)
        assertEquals(0, store.reconcileAbandonedEventWaits(11))
    }

    @Test fun eventAndApprovalRecoveryPreservesAnUnresolvedUserInput() {
        start()
        store.append(request.runId, ExecutionFact("event:begin", RunEventType.EVENT_WAIT_REQUIRED, 4,
            itemId = ItemId("event-1"), state = RunState.WAITING_EVENT,
            payloadRef = "nexus-event://accessibility-service/1"))
        store.append(request.runId, ExecutionFact("approval:begin", RunEventType.APPROVAL_REQUIRED, 5,
            itemId = ItemId("approval-1"), state = RunState.WAITING_AUTH, toolName = "terminal"))
        store.append(request.runId, ExecutionFact("input:begin", RunEventType.USER_INPUT_REQUIRED, 6,
            itemId = ItemId("input-1"), state = RunState.WAITING_AUTH,
            payloadRef = "nexus-input://fixture/input-1"))
        store.close()
        store = NexusExecutionStore(context)

        assertEquals(1, store.reconcileAbandonedEventWaits(10))
        assertEquals(1, store.reconcileAbandonedApprovals(10))
        assertEquals(RunState.WAITING_USER, store.find(request.runId)?.state)
        val input = store.items(request.runId).single { it.kind == ItemKind.USER_INPUT_REQUEST }
        assertEquals(RunState.WAITING_USER, input.state)
        assertNull(input.completedAt)
        assertEquals(RunState.FAILED, store.items(request.runId).single { it.kind == ItemKind.EVENT_WAIT }.state)
        assertEquals(RunState.FAILED, store.items(request.runId).single { it.kind == ItemKind.APPROVAL }.state)
    }

    @Test fun processRestartInterruptsAbandonedApprovalsWithoutApprovingOrReplayingThem() {
        start()
        val approval = ExecutionFact("approve:begin", RunEventType.APPROVAL_REQUIRED, 4,
            itemId = ItemId("approval-1"), state = RunState.WAITING_AUTH, toolName = "terminal")
        store.append(request.runId, approval)
        store.close()
        store = NexusExecutionStore(context)

        assertEquals(1, store.reconcileAbandonedApprovals(10))
        assertEquals(RunState.INTERRUPTED, store.find(request.runId)?.state)
        val resolution = store.timeline(request.runId).single {
            it.event.type == RunEventType.APPROVAL_RESOLVED
        }
        assertFalse(resolution.fact.success!!)
        assertEquals("APPROVAL_PROCESS_RESTARTED", resolution.fact.errorCode)
        assertEquals(0, store.reconcileAbandonedApprovals(11))
        assertEquals(5, store.timeline(request.runId).size)
    }

    @Test fun restartReconciliationClosesEveryParallelApprovalBeforeInterruptingTheRun() {
        start()
        listOf("terminal", "app_state_control").forEachIndexed { index, tool ->
            store.append(request.runId, ExecutionFact("approve:begin:$index", RunEventType.APPROVAL_REQUIRED,
                4L + index, itemId = ItemId("approval-$index"), state = RunState.WAITING_AUTH, toolName = tool))
        }

        assertEquals(2, store.reconcileAbandonedApprovals(5))
        assertEquals(RunState.INTERRUPTED, store.find(request.runId)?.state)
        val resolutions = store.timeline(request.runId).filter { it.event.type == RunEventType.APPROVAL_RESOLVED }
        assertEquals(2, resolutions.size)
        assertTrue(resolutions.all { it.fact.success == false && it.fact.errorCode == "APPROVAL_PROCESS_RESTARTED" })
        assertEquals(setOf("terminal", "app_state_control"), resolutions.mapNotNull { it.fact.toolName }.toSet())
    }

    @Test fun committedFactsAndCanonicalIdentitySurviveReopen() {
        start()
        val fact = ExecutionFact("tool:start:1", RunEventType.TOOL_ITEM_STARTED, 4,
            turnId = TurnId("turn-1"), itemId = ItemId("item-1"), toolName = "read_file")
        val receipt = store.append(request.runId, fact)
        store.close()
        store = NexusExecutionStore(context)
        val snapshot = store.find(request.runId)!!
        assertEquals(request, snapshot.request)
        assertEquals(RunState.RUNNING, snapshot.state)
        assertEquals(receipt.event.sequence, snapshot.lastSequence)
        assertEquals(receipt, store.timeline(request.runId).last())
        assertEquals(request.runId, store.unfinished().single().request.runId)
    }

    @Test fun sameFactKeyIsIdempotentButChangedToolIsRejected() {
        start()
        val fact = ExecutionFact("tool:start:1", RunEventType.TOOL_ITEM_STARTED, 4, turnId = TurnId("turn-1"), itemId = ItemId("tool-1"), toolName = "read_file")
        val first = store.append(request.runId, fact)
        val repeated = store.append(request.runId, fact.copy(createdAt = 100))
        assertEquals(first, repeated)
        assertEquals(4, store.timeline(request.runId).size)
        assertThrows(IllegalArgumentException::class.java) { store.append(request.runId, fact.copy(toolName = "write_file")) }
        assertEquals(4, store.timeline(request.runId).size)
        assertThrows(IllegalArgumentException::class.java) { store.create(request.copy(sessionId = SessionId("another-session")), 101) }
    }

    @Test fun stateUpdateFailureRollsBackBothEventAndSequence() {
        start()
        val before = store.find(request.runId)!!
        store.writableDatabase.execSQL("""CREATE TRIGGER fail_projection BEFORE UPDATE ON execution_runs
            BEGIN SELECT RAISE(ABORT,'fixture projection failure'); END""")
        assertThrows(Exception::class.java) {
            store.append(request.runId, ExecutionFact("tool:start:1", RunEventType.TOOL_ITEM_STARTED, 4, turnId = TurnId("turn-1"), itemId = ItemId("tool-1"), toolName = "read_file"))
        }
        assertEquals(before, store.find(request.runId))
        assertEquals(3, store.timeline(request.runId).size)
        store.writableDatabase.execSQL("DROP TRIGGER fail_projection")
        val next = store.append(request.runId, ExecutionFact("tool:start:1", RunEventType.TOOL_ITEM_STARTED, 4, turnId = TurnId("turn-1"), itemId = ItemId("tool-1"), toolName = "read_file"))
        assertEquals(before.lastSequence + 1, next.event.sequence)
    }

    @Test fun concurrentWritersAndDuplicateDeliveryUseOneSequence() {
        start()
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = (1..4).map { worker -> thread {
            runCatching { repeat(10) { i ->
                store.append(request.runId, ExecutionFact("item:$worker:$i", RunEventType.ASSISTANT_ITEM_COMPLETED, 10 + i.toLong()))
                store.append(request.runId, ExecutionFact("shared", RunEventType.MODEL_REQUEST_STARTED, 4))
            } }.exceptionOrNull()?.let(failures::add)
        } }
        workers.forEach { it.join(5_000); assertFalse(it.isAlive) }
        assertTrue(failures.toString(), failures.isEmpty())
        val rows = store.timeline(request.runId)
        assertEquals(44, rows.size)
        assertEquals((0L until 44).toList(), rows.map { it.event.sequence })
        assertEquals(44, rows.map { it.eventId }.toSet().size)
    }

    @Test fun cancellationRemainsTerminalAndPagingDoesNotReplayOldEvents() {
        start()
        val fact = ExecutionFact("terminal", RunEventType.RUN_CANCELLED, 4, state = RunState.CANCELLED)
        val receipt = store.append(request.runId, fact)
        assertTrue(store.unfinished().isEmpty())
        assertEquals(receipt, store.append(request.runId, fact.copy(createdAt = 5)))
        assertThrows(IllegalStateException::class.java) {
            store.append(request.runId, ExecutionFact("late", RunEventType.RUN_SUCCEEDED, 5, state = RunState.SUCCEEDED))
        }
        assertEquals(listOf(2L,3L), store.timeline(request.runId, afterSequence = 1, limit = 2).map { it.event.sequence })
        assertTrue(store.timeline(request.runId, afterSequence = receipt.event.sequence).isEmpty())
    }

    @Test fun unknownFutureSchemaIsNotDestructivelyRecreated() {
        start()
        store.writableDatabase.version = NexusExecutionStore.VERSION + 1
        store.close()
        store = NexusExecutionStore(context)
        assertThrows(Exception::class.java) { store.readableDatabase }
        val file = context.getDatabasePath(NexusExecutionStore.DATABASE_NAME)
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(NexusExecutionStore.VERSION + 1, db.version)
            db.rawQuery("SELECT COUNT(*) FROM execution_events", null).use { assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0)) }
        }
    }

    @Test fun unverifiedToolCannotBecomeSuccessfulRunOrAcceptAnOrphanResult() {
        start()
        val start = ExecutionFact("tool:begin", RunEventType.TOOL_ITEM_STARTED, 4,
            turnId = TurnId("turn-1"), itemId = ItemId("tool-1"), toolName = "write_file")
        val finish = start.copy(key = "tool:end", type = RunEventType.TOOL_ITEM_COMPLETED, createdAt = 5, success = true)
        assertThrows(IllegalStateException::class.java) { store.append(request.runId, finish) }
        store.append(request.runId, start)
        assertThrows(IllegalStateException::class.java) {
            store.append(request.runId, ExecutionFact("terminal", RunEventType.RUN_SUCCEEDED, 6, state = RunState.SUCCEEDED))
        }
        assertThrows(IllegalArgumentException::class.java) { store.append(request.runId, finish.copy(toolName = "read_file")) }
        store.append(request.runId, finish)
        store.append(request.runId, ExecutionFact("terminal", RunEventType.RUN_SUCCEEDED, 6, state = RunState.SUCCEEDED))
        assertEquals(RunState.SUCCEEDED, store.find(request.runId)?.state)
        val item=store.items(request.runId).single()
        assertEquals(ItemKind.TOOL_CALL,item.kind)
        assertEquals(RunState.SUCCEEDED,item.state)
        assertEquals(4L,item.startedAt)
        assertEquals(5L,item.completedAt)
    }

    @Test fun checkpointCursorAndChunkedUnicodeContentSurviveReopen() {
        start()
        val body="a".repeat(32*1024-1)+"📘"+"中文".repeat(50_000)
        val seq=store.find(request.runId)!!.lastSequence
        val cp=store.saveCheckpoint(request.runId,seq,body,4)
        assertEquals(64,cp.sha256.length)
        store.close();store=NexusExecutionStore(context)
        assertEquals(cp,store.checkpoint(request.runId))
        assertEquals(seq,store.find(request.runId)?.checkpointSequence)
        assertThrows(IllegalArgumentException::class.java) { store.saveCheckpoint(request.runId,seq+1,"future",5) }
        assertThrows(IllegalArgumentException::class.java) { store.saveCheckpoint(request.runId,seq-1,"older",5) }
        assertEquals(cp,store.checkpoint(request.runId))
    }

    @Test fun failedCheckpointWritePreservesPreviousContentAndCursor() {
        start()
        val original=store.saveCheckpoint(request.runId,2,"original",4)
        store.writableDatabase.execSQL("""CREATE TRIGGER fail_checkpoint BEFORE INSERT ON execution_checkpoint_chunks
            BEGIN SELECT RAISE(ABORT,'fixture chunk write failure'); END""")
        assertThrows(Exception::class.java) { store.saveCheckpoint(request.runId,2,"replacement",5) }
        assertEquals(original,store.checkpoint(request.runId))
        assertEquals(2L,store.find(request.runId)?.checkpointSequence)
    }

    @Test fun corruptedCheckpointFailsVerificationInsteadOfReturningPartialContext() {
        start()
        store.saveCheckpoint(request.runId,2,"original",4)
        store.writableDatabase.execSQL("UPDATE execution_checkpoint_chunks SET body='tampered' WHERE run_id=?",arrayOf(request.runId.value))
        assertThrows(IllegalStateException::class.java) { store.checkpoint(request.runId) }
    }

    @Test fun itemProjectionFailureRollsBackTheCorrespondingEvent() {
        start()
        store.writableDatabase.execSQL("""CREATE TRIGGER fail_item BEFORE INSERT ON execution_items
            BEGIN SELECT RAISE(ABORT,'fixture projection failure'); END""")
        val tool=ExecutionFact("tool:start",RunEventType.TOOL_ITEM_STARTED,4,
            turnId=TurnId("turn-1"),itemId=ItemId("tool-1"),toolName="write_file")
        assertThrows(Exception::class.java) { store.append(request.runId,tool) }
        assertEquals(3,store.timeline(request.runId).size)
        assertEquals(2L,store.find(request.runId)?.lastSequence)
        assertTrue(store.items(request.runId).isEmpty())
    }

    @Test fun migrationFromVersionOneRebuildsItemsWithoutChangingEventIds() {
        start()
        val tool=ExecutionFact("tool:start",RunEventType.TOOL_ITEM_STARTED,4,
            turnId=TurnId("turn-1"),itemId=ItemId("tool-1"),toolName="write_file")
        store.append(request.runId,tool)
        store.append(request.runId,tool.copy(key="tool:end",type=RunEventType.TOOL_ITEM_COMPLETED,createdAt=5,success=true))
        val timeline=store.timeline(request.runId)
        val db=store.writableDatabase
        // Reproduce the actual v1 schema rather than deleting the user database.
        db.execSQL("DROP TABLE execution_checkpoint_chunks")
        db.execSQL("DROP TABLE execution_checkpoints")
        db.execSQL("DROP TABLE execution_items")
        db.execSQL("ALTER TABLE execution_events DROP COLUMN payload_ref")
        removeVersionFourColumns(db)
        db.version=1
        store.close();store=NexusExecutionStore(context)
        assertEquals(timeline,store.timeline(request.runId))
        assertEquals(RunState.SUCCEEDED,store.items(request.runId).single().state)
        assertEquals(NexusExecutionStore.VERSION,store.readableDatabase.version)
        assertNull(store.checkpoint(request.runId))
    }

    @Test fun versionTwoMigrationPreservesCheckpointItemsAndStableEvents() {
        start()
        val fact = ExecutionFact("assistant", RunEventType.ASSISTANT_ITEM_COMPLETED, 4, itemId = ItemId("assistant-1"))
        store.append(request.runId, fact)
        val checkpoint = store.saveCheckpoint(request.runId, store.find(request.runId)!!.lastSequence, "private context", 5)
        val events = store.timeline(request.runId)
        val db = store.writableDatabase
        db.execSQL("ALTER TABLE execution_events DROP COLUMN payload_ref")
        db.execSQL("ALTER TABLE execution_items DROP COLUMN payload_ref")
        removeVersionFourColumns(db)
        db.version = 2
        store.close(); store = NexusExecutionStore(context)
        assertEquals(events, store.timeline(request.runId))
        assertEquals(checkpoint, store.checkpoint(request.runId))
        assertEquals(ItemKind.ASSISTANT_MESSAGE, store.items(request.runId).single().kind)
        assertEquals(NexusExecutionStore.VERSION, store.readableDatabase.version)
        val ref = "nexus-workflow://scope/plan/plan-1/2#0123456789"
        store.append(request.runId, ExecutionFact("plan:begin", RunEventType.WORKFLOW_UPDATE_STARTED, 6,
            itemId = ItemId("plan-item"), toolName = "plan_manage"))
        val written = store.append(request.runId, ExecutionFact("plan", RunEventType.PLAN_UPDATED, 6,
            itemId = ItemId("plan-item"), toolName = "plan_manage", success = true, payloadRef = ref))
        store.close(); store = NexusExecutionStore(context)
        assertEquals(written, store.timeline(request.runId).last())
        assertEquals(ref, store.items(request.runId).single { it.kind == ItemKind.PLAN_REVISION }.payloadRef)
    }

    @Test fun unfinishedWorkflowMutationCannotBecomeASuccessfulRunAfterReopen() {
        start()
        val fact = ExecutionFact("workflow:start", RunEventType.WORKFLOW_UPDATE_STARTED, 4,
            itemId = ItemId("workflow-1"), toolName = "notepad_manage")
        store.append(request.runId, fact)
        store.close(); store = NexusExecutionStore(context)
        assertThrows(IllegalStateException::class.java) {
            store.append(request.runId, ExecutionFact("terminal", RunEventType.RUN_SUCCEEDED, 5, state = RunState.SUCCEEDED))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.append(request.runId, fact.copy(key = "wrong", type = RunEventType.PLAN_UPDATED,
                toolName = "plan_manage", success = true, payloadRef = "nexus-workflow://scope/plan/1"))
        }
        store.append(request.runId, ExecutionFact("terminal", RunEventType.RUN_INTERRUPTED, 6, state = RunState.INTERRUPTED))
        assertEquals(RunState.INTERRUPTED, store.items(request.runId).single().state)
        assertNull(store.items(request.runId).single().completedAt)
    }

    private fun removeVersionFourColumns(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("DROP TABLE execution_model_requests")
        db.execSQL("ALTER TABLE execution_events DROP COLUMN budget_json")
        db.execSQL("ALTER TABLE execution_events DROP COLUMN goal_id")
        db.execSQL("ALTER TABLE execution_runs DROP COLUMN admitted_request_json")
    }

    @Test fun versionFiveModelRequestsMigrateFromVersionFourWithoutChangingExistingEvents() {
        start()
        val events = store.timeline(request.runId)
        val before = store.find(request.runId)
        store.writableDatabase.execSQL("DROP TABLE execution_model_requests")
        store.writableDatabase.version = 4
        store.close(); store = NexusExecutionStore(context)
        assertEquals(events, store.timeline(request.runId))
        assertEquals(before, store.find(request.runId))
        assertEquals(0L, store.modelBudgetTotals(request.runId).requests)
    }

    @Test fun versionThreeMigrationPreservesRevisionReferencesAndTheAdmittedRequest() {
        start()
        val begin = ExecutionFact("goal:begin", RunEventType.WORKFLOW_UPDATE_STARTED, 4,
            itemId = ItemId("goal-item"), toolName = "goal_manage")
        store.append(request.runId, begin)
        store.append(request.runId, begin.copy(key = "goal:end", type = RunEventType.GOAL_UPDATED, success = true,
            payloadRef = "nexus-workflow://scope/goal/fixture"))
        val timeline = store.timeline(request.runId)
        val db = store.writableDatabase
        removeVersionFourColumns(db); db.version = 3
        store.close(); store = NexusExecutionStore(context)
        assertEquals(timeline, store.timeline(request.runId))
        assertEquals(request, store.find(request.runId)?.admittedRequest)
        assertEquals(request, store.find(request.runId)?.request)
        assertEquals(NexusExecutionStore.VERSION, store.readableDatabase.version)
    }

    @Test fun goalBudgetAndEffectiveRequestCommitTogetherWithoutChangingAdmissionIdentity() {
        start()
        val begin = ExecutionFact("goal:start", RunEventType.WORKFLOW_UPDATE_STARTED, 4,
            itemId = ItemId("goal-budget-item"), toolName = "goal_manage")
        store.append(request.runId, begin)
        val limit = RunBudget(hardRounds = 1, hardToolCalls = 1)
        val fact = begin.copy(key = "goal:finish", type = RunEventType.GOAL_UPDATED, createdAt = 5,
            success = true, payloadRef = "nexus-workflow://scope/goal/fixture", budgetLimit = limit, goalId = GoalId("goal-1"))
        val committed = store.append(request.runId, fact)
        val snapshot = store.find(request.runId)!!
        assertEquals(request, snapshot.admittedRequest)
        assertEquals(limit, snapshot.request.budget)
        assertEquals(GoalId("goal-1"), snapshot.request.goalId)
        assertEquals(limit, committed.event.budgetLimit)
        assertEquals(snapshot, store.create(request, 6))
        store.close(); store = NexusExecutionStore(context)
        assertEquals(snapshot, store.find(request.runId))
        assertEquals(committed, store.timeline(request.runId).last())
    }

    @Test fun versionFourMigrationKeepsAdmissionAndEffectiveLimitsAndAddsTheModelLedger() {
        start()
        val before = store.find(request.runId)
        val events = store.timeline(request.runId)
        val db = store.writableDatabase
        db.execSQL("DROP TABLE execution_model_requests"); db.version = 4
        store.close(); store = NexusExecutionStore(context)
        assertEquals(before, store.find(request.runId))
        assertEquals(events, store.timeline(request.runId))
        assertEquals(0L, store.modelBudgetTotals(request.runId).requests)
        val reserved = store.reserveModelRequest(io.github.zhh_lgtm.nexus.application.api.ModelBudgetRequest(
            "fixture-request", request.runId, "fixture", "model", "CHAT"), 10)
        assertEquals(request.runId, reserved.familyRunId)
        assertEquals(1L, store.modelBudgetTotals(request.runId).openRequests)
    }
}
