package io.github.mangi.eta.data.repository

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.zhh_lgtm.nexus.application.api.ExecutionFact
import io.github.zhh_lgtm.nexus.kernel.api.*

/** Item projection is written by the event transaction, never by UI or another feature. */
internal object NexusExecutionProjection {
    fun createSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE execution_items (
            run_id TEXT NOT NULL, item_id TEXT NOT NULL, turn_id TEXT, kind TEXT NOT NULL,
            state TEXT, started_at INTEGER NOT NULL, completed_at INTEGER, payload_ref TEXT,
            PRIMARY KEY(run_id,item_id),
            FOREIGN KEY(run_id) REFERENCES execution_runs(run_id) ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE execution_checkpoints (
            run_id TEXT PRIMARY KEY NOT NULL, through_sequence INTEGER NOT NULL,
            sha256 TEXT NOT NULL, created_at INTEGER NOT NULL, payload_chars INTEGER NOT NULL,
            FOREIGN KEY(run_id) REFERENCES execution_runs(run_id) ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE execution_checkpoint_chunks (
            run_id TEXT NOT NULL, chunk_index INTEGER NOT NULL, body TEXT NOT NULL,
            PRIMARY KEY(run_id,chunk_index),
            FOREIGN KEY(run_id) REFERENCES execution_checkpoints(run_id) ON DELETE CASCADE)""")
    }

    fun apply(db: SQLiteDatabase, runId: RunId, fact: ExecutionFact) {
        if(fact.type in setOf(RunEventType.RUN_FAILED,RunEventType.RUN_CANCELLED,RunEventType.RUN_INTERRUPTED)) {
            db.execSQL("UPDATE execution_items SET state='INTERRUPTED' WHERE run_id=? AND completed_at IS NULL",arrayOf(runId.value))
        }
        val itemId = fact.itemId ?: return
        val kind = when (fact.type) {
            RunEventType.MODEL_BUDGET_RESERVED, RunEventType.MODEL_BUDGET_SETTLED -> ItemKind.MODEL_REQUEST
            RunEventType.TOOL_ITEM_STARTED, RunEventType.TOOL_ITEM_COMPLETED,
            RunEventType.TOOL_EFFECT_REVIEW_RESOLVED -> ItemKind.TOOL_CALL
            RunEventType.ASSISTANT_ITEM_STARTED, RunEventType.ASSISTANT_ITEM_COMPLETED -> ItemKind.ASSISTANT_MESSAGE
            RunEventType.CONTEXT_COMPACTED -> ItemKind.CONTEXT_COMPACTION
            RunEventType.APPROVAL_REQUIRED, RunEventType.APPROVAL_RESOLVED -> ItemKind.APPROVAL
            RunEventType.USER_INPUT_REQUIRED, RunEventType.USER_INPUT_RESOLVED -> ItemKind.USER_INPUT_REQUEST
            RunEventType.EVENT_WAIT_REQUIRED, RunEventType.EVENT_WAIT_RESOLVED -> ItemKind.EVENT_WAIT
            RunEventType.PLAN_UPDATED -> ItemKind.PLAN_REVISION
            RunEventType.NOTEPAD_UPDATED -> ItemKind.NOTEPAD_REVISION
            RunEventType.GOAL_UPDATED -> ItemKind.GOAL_UPDATE
            RunEventType.WORKFLOW_UPDATE_STARTED, RunEventType.WORKFLOW_UPDATE_FAILED -> when (fact.toolName) {
                "goal_manage" -> ItemKind.GOAL_UPDATE
                "plan_manage" -> ItemKind.PLAN_REVISION
                "notepad_manage" -> ItemKind.NOTEPAD_REVISION
                else -> error("WORKFLOW_KIND_REQUIRED")
            }
            else -> return
        }
        val finished = fact.type in setOf(RunEventType.TOOL_ITEM_COMPLETED, RunEventType.ASSISTANT_ITEM_COMPLETED,
            RunEventType.APPROVAL_RESOLVED, RunEventType.PLAN_UPDATED, RunEventType.GOAL_UPDATED,
            RunEventType.NOTEPAD_UPDATED, RunEventType.WORKFLOW_UPDATE_FAILED, RunEventType.MODEL_BUDGET_SETTLED,
            RunEventType.USER_INPUT_RESOLVED, RunEventType.EVENT_WAIT_RESOLVED,
            RunEventType.TOOL_EFFECT_REVIEW_RESOLVED) ||
            fact.type == RunEventType.CONTEXT_COMPACTED && fact.success != null
        val state = if (!finished) {
            when (kind) {
                ItemKind.APPROVAL -> RunState.WAITING_AUTH
                ItemKind.USER_INPUT_REQUEST -> RunState.WAITING_USER
                ItemKind.EVENT_WAIT -> RunState.WAITING_EVENT
                else -> RunState.RUNNING
            }
        } else when {
            fact.errorCode == "APPROVAL_CANCELLED" -> RunState.CANCELLED
            fact.errorCode == "INPUT_CANCELLED" -> RunState.CANCELLED
            fact.errorCode == "EVENT_ACCESSIBILITY_CANCELLED" -> RunState.CANCELLED
            fact.type == RunEventType.TOOL_EFFECT_REVIEW_RESOLVED ->
                if (EffectReviewDecision.fromReference(fact.payloadRef) == EffectReviewDecision.CANCELLED) {
                    RunState.CANCELLED
                } else RunState.INTERRUPTED
            fact.success == false -> RunState.FAILED
            fact.type == RunEventType.TOOL_ITEM_COMPLETED && fact.success == null -> RunState.VERIFYING
            else -> RunState.SUCCEEDED
        }
        val old = db.query("execution_items", arrayOf("started_at", "kind"), "run_id=? AND item_id=?",
            arrayOf(runId.value,itemId.value),null,null,null).use { row ->
            if (row.moveToFirst()) row.getLong(0) to row.getString(1) else null
        }
        check(old == null || old.second == kind.name) { "ITEM_KIND_CONFLICT" }
        val started = old?.first ?: fact.createdAt
        val values = ContentValues().apply {
            put("run_id",runId.value); put("item_id",itemId.value); put("turn_id",fact.turnId?.value)
            put("kind",kind.name); put("state",state?.name); put("started_at",started)
            put("payload_ref",fact.payloadRef)
            if (finished) put("completed_at",maxOf(started,fact.createdAt)) else putNull("completed_at")
        }
        if (old == null) db.insertOrThrow("execution_items",null,values)
        else db.update("execution_items",values,"run_id=? AND item_id=?",arrayOf(runId.value,itemId.value))
    }

    fun read(row: Cursor): ExecutionItem {
        fun text(name: String): String? = row.getColumnIndexOrThrow(name).let { if(row.isNull(it)) null else row.getString(it) }
        fun number(name: String) = row.getLong(row.getColumnIndexOrThrow(name))
        return ExecutionItem(ItemId(text("item_id")!!),RunId(text("run_id")!!),text("turn_id")?.let(::TurnId),
            ItemKind.valueOf(text("kind")!!),text("state")?.let(RunState::valueOf),number("started_at"),
            row.getColumnIndexOrThrow("completed_at").let { if(row.isNull(it)) null else row.getLong(it) },
            payloadRef = text("payload_ref"))
    }
}
