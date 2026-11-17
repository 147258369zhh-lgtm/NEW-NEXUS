package io.github.mangi.eta.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.zhh_lgtm.nexus.application.api.*
import io.github.zhh_lgtm.nexus.kernel.api.*
import io.github.mangi.eta.agent.capability.NexusToolEffectClassifier
import java.security.MessageDigest
import org.json.JSONObject

internal data class UnresolvedUserInput(
    val runId: String,
    val itemId: String,
    val payloadRef: String,
    val createdAt: Long,
)

/** Event rows contain no user payloads; private checkpoint content has separate bounded rows. */
internal class NexusExecutionStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, VERSION), ExecutionStore, ModelBudgetStore {
    private val modelBudgetLedger = NexusModelBudgetLedger(::findIn) { db, run, fact -> appendIn(db, run, fact, terminalReceipt = true) }
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA synchronous=FULL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE execution_runs (
            run_id TEXT PRIMARY KEY NOT NULL, request_json TEXT NOT NULL, admitted_request_json TEXT NOT NULL,
            state TEXT NOT NULL, last_sequence INTEGER NOT NULL DEFAULT -1,
            checkpoint_sequence INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE execution_events (
            run_id TEXT NOT NULL, sequence INTEGER NOT NULL, event_id TEXT NOT NULL UNIQUE,
            fact_key TEXT NOT NULL, type TEXT NOT NULL, created_at INTEGER NOT NULL,
            turn_id TEXT, item_id TEXT, state TEXT, tool_name TEXT, success INTEGER, error_code TEXT, payload_ref TEXT,
            budget_json TEXT, goal_id TEXT,
            PRIMARY KEY(run_id,sequence), UNIQUE(run_id,fact_key),
            FOREIGN KEY(run_id) REFERENCES execution_runs(run_id) ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX execution_runs_state ON execution_runs(state,updated_at)")
        NexusExecutionProjection.createSchema(db)
        NexusModelBudgetLedger.createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1 until newVersion && newVersion == VERSION) { "No execution-ledger migration from $oldVersion to $newVersion" }
        if (oldVersion < 3) db.execSQL("ALTER TABLE execution_events ADD COLUMN payload_ref TEXT")
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE execution_events ADD COLUMN budget_json TEXT")
            db.execSQL("ALTER TABLE execution_events ADD COLUMN goal_id TEXT")
            db.execSQL("ALTER TABLE execution_runs ADD COLUMN admitted_request_json TEXT")
            db.execSQL("UPDATE execution_runs SET admitted_request_json=request_json")
        }
        if (oldVersion < 2) {
            NexusExecutionProjection.createSchema(db)
            db.query("execution_events",null,null,null,null,null,"run_id,sequence ASC").use { rows ->
                while(rows.moveToNext()) NexusExecutionProjection.apply(db,RunId(rows.text("run_id")!!),readFact(rows))
            }
        } else if (oldVersion < 3) {
            db.execSQL("ALTER TABLE execution_items ADD COLUMN payload_ref TEXT")
        }
        if (oldVersion < 5) NexusModelBudgetLedger.createSchema(db)
    }

    override fun create(request: RunRequest, now: Long): ExecutionRunSnapshot {
        require(now >= 0)
        val db = writableDatabase
        return transaction(db) {
            findIn(db, request.runId)?.let { existing ->
                require(existing.admittedRequest == request) { "RUN_ID_CONFLICT" }
                return@transaction existing
            }
            db.insertOrThrow("execution_runs", null, ContentValues().apply {
                put("run_id", request.runId.value); put("request_json", encode(request))
                put("admitted_request_json", encode(request))
                put("state", RunState.CREATED.name); put("last_sequence", -1L)
                put("created_at", now); put("updated_at", now)
            })
            appendIn(db, request.runId, ExecutionFact("run:created", RunEventType.RUN_CREATED, now, state = RunState.CREATED))
            checkNotNull(findIn(db, request.runId))
        }
    }

    override fun append(runId: RunId, fact: ExecutionFact): ExecutionCommit = transaction(writableDatabase) {
        require(fact.type !in setOf(RunEventType.MODEL_BUDGET_RESERVED, RunEventType.MODEL_BUDGET_SETTLED,
            RunEventType.BUDGET_INCREASED, RunEventType.TOOL_EFFECT_REVIEW_RESOLVED)) { "EXECUTION_OWNER_REQUIRED" }
        appendIn(writableDatabase, runId, fact)
    }

    override fun increaseBudget(runId: RunId, expected: RunBudget, fact: ExecutionFact, approvalRunId: RunId,
        approvalCapability: String): ExecutionCommit = transaction(writableDatabase) {
        require(fact.type == RunEventType.BUDGET_INCREASED && fact.budgetLimit != null)
        val db = writableDatabase
        val current = findIn(db, runId) ?: error("RUN_NOT_FOUND")
        check(current.request.budget == expected) { "BUDGET_CHANGED_SINCE_APPROVAL" }
        expected.withIncreasedGoalBudget(expected, checkNotNull(fact.budgetLimit))
        check(current.request.goalId == fact.goalId) { "GOAL_SCOPE_MISMATCH" }
        fun family(id: RunId): RunId {
            var member = findIn(db, id) ?: error("RUN_NOT_FOUND")
            val seen = mutableSetOf<RunId>()
            while (member.request.parentRunId != null) {
                check(seen.add(member.request.runId)) { "RUN_PARENT_CYCLE" }
                member = findIn(db, member.request.parentRunId!!) ?: error("PARENT_RUN_NOT_FOUND")
            }
            return member.request.runId
        }
        check(family(runId) == family(approvalRunId)) { "BUDGET_APPROVAL_FAMILY_MISMATCH" }
        val approved = db.rawQuery("SELECT COUNT(*) FROM execution_events WHERE run_id=? AND type=? AND tool_name=? AND success=1",
            arrayOf(approvalRunId.value, RunEventType.APPROVAL_RESOLVED.name, approvalCapability)).use { it.moveToFirst(); it.getLong(0) }
        check(approved == 1L && (approvalCapability.startsWith("workflow.goal.budget:") ||
            approvalCapability.startsWith("run_budget:extend:"))) { "LOCAL_BUDGET_APPROVAL_REQUIRED" }
        appendIn(db, runId, fact)
    }

    private fun appendIn(db: SQLiteDatabase, runId: RunId, fact: ExecutionFact, terminalReceipt: Boolean = false): ExecutionCommit {
        val run = findIn(db, runId) ?: error("RUN_NOT_FOUND")
        db.query("execution_events", null, "run_id=? AND fact_key=?", arrayOf(runId.value, fact.key), null, null, null).use { row ->
            if (row.moveToFirst()) {
                // Observation time may differ on redelivery; the first committed timestamp wins.
                require(readFact(row).copy(createdAt = fact.createdAt) == fact) { "EVENT_KEY_CONFLICT" }
                return readCommit(row, run.request)
            }
        }
        val reviewAddendum = fact.type == RunEventType.TOOL_EFFECT_REVIEW_RESOLVED && (fact.state == null || fact.state == run.state)
        check(run.state !in terminalStates || terminalReceipt && fact.type == RunEventType.MODEL_BUDGET_SETTLED || reviewAddendum) { "RUN_ALREADY_TERMINAL" }
        validateToolFact(db, runId, fact)
        validateEffectReviewFact(db, runId, fact)
        validateApprovalFact(db, runId, fact)
        validateWorkflowFact(db, runId, fact)
        validateInputFact(db, runId, fact)
        validateEventWaitFact(db, runId, fact)
        if (fact.state == RunState.SUCCEEDED) {
            check(unsettledToolCount(db, runId) == 0L) { "UNRESOLVED_TOOL_EFFECT" }
            check(pendingApprovalCount(db, runId) == 0L) { "UNRESOLVED_APPROVAL" }
            check(pendingInputCount(db, runId) == 0L) { "UNRESOLVED_USER_INPUT" }
            check(pendingEventWaitCount(db, runId) == 0L) { "UNRESOLVED_EVENT_WAIT" }
            check(unsettledWorkflowCount(db, runId) == 0L) { "UNRESOLVED_WORKFLOW_EFFECT" }
            val openModels = db.rawQuery("SELECT COUNT(*) FROM execution_model_requests WHERE run_id=? AND settlement_json IS NULL",
                arrayOf(runId.value)).use { it.moveToFirst(); it.getLong(0) }
            check(openModels == 0L) { "UNRESOLVED_MODEL_REQUEST" }
        }
        val nextState = fact.state ?: run.state
        val requiredState = when (fact.type) {
            RunEventType.RUN_CREATED -> RunState.CREATED
            RunEventType.RUN_ADMITTED -> RunState.ADMITTED
            RunEventType.RUN_STARTED -> RunState.RUNNING
            RunEventType.RUN_SUCCEEDED -> RunState.SUCCEEDED
            RunEventType.RUN_FAILED -> RunState.FAILED
            RunEventType.RUN_CANCELLED -> RunState.CANCELLED
            RunEventType.RUN_INTERRUPTED -> RunState.INTERRUPTED
            else -> null
        }
        require(requiredState == null || fact.state == requiredState) { "EVENT_STATE_MISMATCH" }
        require(validTransition(run.state, nextState)) { "INVALID_RUN_TRANSITION:${run.state}:$nextState" }
        val sequence = run.lastSequence + 1
        val eventId = hash("${runId.value}\u0000${fact.key}")
        db.insertOrThrow("execution_events", null, ContentValues().apply {
            put("run_id", runId.value); put("sequence", sequence); put("event_id", eventId)
            put("fact_key", fact.key); put("type", fact.type.name); put("created_at", fact.createdAt)
            put("turn_id", fact.turnId?.value); put("item_id", fact.itemId?.value)
            put("state", fact.state?.name); put("tool_name", fact.toolName)
            if (fact.success == null) putNull("success") else put("success", if (fact.success == true) 1 else 0)
            put("error_code", fact.errorCode)
            put("payload_ref", fact.payloadRef)
            put("budget_json", fact.budgetLimit?.let(::encodeBudget)?.toString())
            put("goal_id", fact.goalId?.value)
        })
        NexusExecutionProjection.apply(db,runId,fact)
        db.update("execution_runs", ContentValues().apply {
            put("state", nextState.name); put("last_sequence", sequence)
            put("updated_at", maxOf(run.updatedAt, fact.createdAt))
            fact.budgetLimit?.let { limit -> put("request_json", encode(run.request.copy(
                budget = if (fact.type == RunEventType.BUDGET_INCREASED) limit else run.request.budget.intersect(limit),
                goalId = run.request.goalId ?: fact.goalId))) }
        }, "run_id=?", arrayOf(runId.value))
        return ExecutionCommit(eventId, event(run.request, sequence, fact), fact)
    }

    private fun validateToolFact(db: SQLiteDatabase, runId: RunId, fact: ExecutionFact) {
        if (fact.type !in setOf(RunEventType.TOOL_ITEM_STARTED, RunEventType.TOOL_ITEM_COMPLETED)) return
        require(fact.itemId != null && fact.turnId != null && !fact.toolName.isNullOrBlank()) { "TOOL_IDENTITY_REQUIRED" }
        db.query("execution_events", arrayOf("type", "turn_id", "tool_name"), "run_id=? AND item_id=?",
            arrayOf(runId.value, fact.itemId?.value.orEmpty()), null, null, "sequence ASC").use { rows ->
            val prior = buildList { while (rows.moveToNext()) add(Triple(rows.getString(0), rows.getString(1), rows.getString(2))) }
            if (fact.type == RunEventType.TOOL_ITEM_STARTED) {
                check(prior.isEmpty()) { "DUPLICATE_TOOL_ITEM" }
            } else {
                check(prior.size == 1 && prior.single().first == RunEventType.TOOL_ITEM_STARTED.name) { "ORPHAN_OR_DUPLICATE_TOOL_RESULT" }
                require(prior.single().second == fact.turnId?.value && prior.single().third == fact.toolName) { "TOOL_RESULT_IDENTITY_MISMATCH" }
            }
        }
    }

    private fun validateInputFact(db: SQLiteDatabase, runId: RunId, fact: ExecutionFact) {
        if (fact.type !in setOf(RunEventType.USER_INPUT_REQUIRED, RunEventType.USER_INPUT_RESOLVED)) return
        require(fact.itemId != null && fact.payloadRef != null) { "INPUT_IDENTITY_REQUIRED" }
        val itemId = checkNotNull(fact.itemId)
        val requested = fact.type == RunEventType.USER_INPUT_REQUIRED
        require(if (requested) fact.success == null else fact.success != null) { "INPUT_RESULT_REQUIRED" }
        db.query("execution_events", arrayOf("type"), "run_id=? AND item_id=?",
            arrayOf(runId.value, itemId.value), null, null, "sequence ASC").use { rows ->
            val prior = buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            check(if (requested) prior.isEmpty() else prior == listOf(RunEventType.USER_INPUT_REQUIRED.name)) {
                "ORPHAN_OR_DUPLICATE_INPUT"
            }
        }
    }

    private fun pendingInputCount(db: SQLiteDatabase, id: RunId): Long = db.rawQuery("""
        SELECT COUNT(*) FROM execution_events starts WHERE starts.run_id=? AND starts.type='USER_INPUT_REQUIRED'
        AND NOT EXISTS (SELECT 1 FROM execution_events ends WHERE ends.run_id=starts.run_id
            AND ends.item_id=starts.item_id AND ends.type='USER_INPUT_RESOLVED')
    """, arrayOf(id.value)).use { it.moveToFirst(); it.getLong(0) }

    private fun validateEventWaitFact(db: SQLiteDatabase, runId: RunId, fact: ExecutionFact) {
        if (fact.type !in setOf(RunEventType.EVENT_WAIT_REQUIRED, RunEventType.EVENT_WAIT_RESOLVED)) return
        require(fact.itemId != null && fact.payloadRef != null) { "EVENT_WAIT_IDENTITY_REQUIRED" }
        val requested = fact.type == RunEventType.EVENT_WAIT_REQUIRED
        require(if (requested) fact.success == null && fact.errorCode == null && fact.state == RunState.WAITING_EVENT
            else fact.success != null && (fact.success == true || fact.errorCode != null)) { "INVALID_EVENT_WAIT" }
        db.query("execution_events", arrayOf("type", "payload_ref"), "run_id=? AND item_id=?",
            arrayOf(runId.value, fact.itemId?.value.orEmpty()), null, null, "sequence ASC").use { rows ->
            val prior = buildList { while (rows.moveToNext()) add(rows.getString(0) to rows.getString(1)) }
            if (requested) {
                check(prior.isEmpty()) { "DUPLICATE_EVENT_WAIT" }
            } else {
                check(prior.size == 1 && prior.single().first == RunEventType.EVENT_WAIT_REQUIRED.name) {
                    "ORPHAN_OR_DUPLICATE_EVENT_WAIT"
                }
                require(prior.single().second == fact.payloadRef) { "EVENT_WAIT_IDENTITY_MISMATCH" }
            }
        }
    }

    private fun pendingEventWaitCount(db: SQLiteDatabase, id: RunId): Long = db.rawQuery("""
        SELECT COUNT(*) FROM execution_events starts WHERE starts.run_id=? AND starts.type='EVENT_WAIT_REQUIRED'
        AND NOT EXISTS (SELECT 1 FROM execution_events ends WHERE ends.run_id=starts.run_id
            AND ends.item_id=starts.item_id AND ends.type='EVENT_WAIT_RESOLVED')
    """, arrayOf(id.value)).use { it.moveToFirst(); it.getLong(0) }

    private fun unsettledToolCount(db: SQLiteDatabase, id: RunId): Long = db.rawQuery("""
        SELECT COUNT(*) FROM execution_events starts WHERE starts.run_id=? AND starts.type='TOOL_ITEM_STARTED'
        AND NOT EXISTS (SELECT 1 FROM execution_events ends WHERE ends.run_id=starts.run_id
            AND ends.item_id=starts.item_id AND ends.type='TOOL_ITEM_COMPLETED' AND ends.success IS NOT NULL)
    """, arrayOf(id.value)).use { it.moveToFirst(); it.getLong(0) }

    private fun toolNeedsEffectReview(toolName: String?): Boolean =
        toolName != null && NexusToolEffectClassifier.classify(toolName) != EffectClass.READ

    /** Counts started tools whose outcome is still unknown and whose effect was not reviewed yet. */
    internal fun pendingEffectReviewCount(runId: RunId, exceptItemId: String? = null): Int =
        pendingEffectReviewCount(readableDatabase, runId, exceptItemId)

    private fun pendingEffectReviewCount(db: SQLiteDatabase, runId: RunId, exceptItemId: String? = null): Int =
        pendingEffectReviewRows(db, runId.value, exceptItemId).size

    private fun pendingEffectReviewRows(db: SQLiteDatabase, runId: String, exceptItemId: String? = null): List<Pair<String, String?>> =
        db.rawQuery("""
            SELECT starts.item_id, starts.tool_name FROM execution_events starts
            WHERE starts.run_id=? AND starts.type='TOOL_ITEM_STARTED'
              AND NOT EXISTS (SELECT 1 FROM execution_events ends WHERE ends.run_id=starts.run_id
                AND ends.item_id=starts.item_id AND ends.type='TOOL_ITEM_COMPLETED' AND ends.success IS NOT NULL)
              AND NOT EXISTS (SELECT 1 FROM execution_events review WHERE review.run_id=starts.run_id
                AND review.item_id=starts.item_id AND review.type='TOOL_EFFECT_REVIEW_RESOLVED')
            ORDER BY starts.sequence ASC
        """.trimIndent(), arrayOf(runId)).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val itemId = rows.getString(0)
                    if (itemId.isNullOrBlank() || itemId == exceptItemId) continue
                    if (toolNeedsEffectReview(rows.getString(1))) add(itemId to rows.getString(1))
                }
            }
        }

    private fun validateEffectReviewFact(db: SQLiteDatabase, runId: RunId, fact: ExecutionFact) {
        if (fact.type != RunEventType.TOOL_EFFECT_REVIEW_RESOLVED) return
        require(fact.itemId != null && !fact.toolName.isNullOrBlank() && fact.success == true) { "EFFECT_REVIEW_IDENTITY_REQUIRED" }
        require(EffectReviewDecision.fromReference(fact.payloadRef) != null) { "EFFECT_REVIEW_DECISION_REQUIRED" }
        require(toolNeedsEffectReview(fact.toolName)) { "EFFECT_REVIEW_NOT_REQUIRED" }
        db.query("execution_events", arrayOf("type", "tool_name", "success"), "run_id=? AND item_id=?",
            arrayOf(runId.value, fact.itemId?.value.orEmpty()), null, null, "sequence ASC").use { rows ->
            var started = 0
            var unknown = 0
            var resolved = 0
            var identityMatches = true
            while (rows.moveToNext()) {
                when (rows.getString(0)) {
                    RunEventType.TOOL_ITEM_STARTED.name -> {
                        started++
                        identityMatches = identityMatches && rows.getString(1) == fact.toolName
                    }
                    RunEventType.TOOL_ITEM_COMPLETED.name -> if (rows.isNull(2)) unknown++
                    RunEventType.TOOL_EFFECT_REVIEW_RESOLVED.name -> resolved++
                }
            }
            check(started == 1 && identityMatches && unknown == 1 && resolved == 0) { "EFFECT_REVIEW_NOT_PENDING" }
        }
    }

    /**
     * Closes tools left by a previous process. Read-only tools become explicit failures; any
     * other effect becomes an unresolved review item. Nothing is replayed, and pending input,
     * approval or event waits keep their own state.
     */
    internal fun reconcileAbandonedToolEffects(now: Long): Int {
        require(now >= 0)
        return transaction(writableDatabase) {
            val db = writableDatabase
            val abandoned = db.rawQuery("""
                SELECT starts.run_id, starts.item_id, starts.turn_id, starts.tool_name
                FROM execution_events starts
                JOIN execution_runs runs ON runs.run_id=starts.run_id
                WHERE starts.type='TOOL_ITEM_STARTED'
                  AND runs.state NOT IN ('SUCCEEDED','FAILED','CANCELLED','INTERRUPTED')
                  AND NOT EXISTS (SELECT 1 FROM execution_events ends
                    WHERE ends.run_id=starts.run_id AND ends.item_id=starts.item_id
                      AND ends.type='TOOL_ITEM_COMPLETED')
                ORDER BY starts.run_id, starts.sequence ASC
            """.trimIndent(), null).use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        val runId = rows.getString(0)
                        val itemId = rows.getString(1)
                        val toolName = rows.getString(3)
                        if (!runId.isNullOrBlank() && !itemId.isNullOrBlank() && !toolName.isNullOrBlank()) {
                            add(AbandonedTool(runId, itemId, rows.getString(2), toolName))
                        }
                    }
                }
            }
            abandoned.groupBy { it.runId }.values.sumOf { tools ->
                val runId = RunId(tools.first().runId)
                val run = findIn(db, runId) ?: error("RUN_NOT_FOUND")
                val reviewPending = tools.any { toolNeedsEffectReview(it.toolName) } ||
                    pendingEffectReviewRows(db, runId.value).isNotEmpty()
                val preferred = when {
                    pendingApprovalCount(db, runId) > 0L -> RunState.WAITING_AUTH
                    pendingInputCount(db, runId) > 0L -> RunState.WAITING_USER
                    pendingEventWaitCount(db, runId) > 0L -> RunState.WAITING_EVENT
                    run.state == RunState.PAUSED -> RunState.PAUSED
                    reviewPending -> RunState.VERIFYING
                    else -> RunState.INTERRUPTED
                }
                val finalState = if (validTransition(run.state, preferred)) preferred else RunState.INTERRUPTED
                tools.forEachIndexed { index, tool ->
                    val review = toolNeedsEffectReview(tool.toolName)
                    appendIn(db, runId, ExecutionFact(
                        key = "tool:effect:${tool.itemId}:process-restarted",
                        type = RunEventType.TOOL_ITEM_COMPLETED,
                        createdAt = now,
                        turnId = tool.turnId?.let(::TurnId),
                        itemId = ItemId(tool.itemId),
                        state = if (index == tools.lastIndex) finalState else run.state,
                        toolName = tool.toolName,
                        success = if (review) null else false,
                        errorCode = if (review) "EFFECT_REVIEW_REQUIRED" else "TOOL_PROCESS_RESTARTED",
                    ))
                }
                tools.size
            }
        }
    }

    private data class AbandonedTool(val runId: String, val itemId: String, val turnId: String?, val toolName: String)

    /**
     * Records the user's explicit review. The original tool item is never replayed; a rerun is
     * only an audit request that must be started again by the user.
     */
    internal fun resolveEffectReview(itemId: ItemId, decision: EffectReviewDecision, now: Long): ExecutionCommit {
        require(now >= 0)
        val db = writableDatabase
        return transaction(db) {
            val runId = db.query("execution_events", arrayOf("run_id"), "item_id=?",
                arrayOf(itemId.value), null, null, "sequence ASC", "1").use { rows ->
                if (!rows.moveToFirst()) error("EFFECT_REVIEW_NOT_FOUND")
                RunId(rows.getString(0))
            }
            val run = findIn(db, runId) ?: error("RUN_NOT_FOUND")
            val existing = db.query("execution_events", null,
                "run_id=? AND item_id=? AND type=?", arrayOf(runId.value, itemId.value,
                    RunEventType.TOOL_EFFECT_REVIEW_RESOLVED.name), null, null, "sequence ASC").use { rows ->
                if (!rows.moveToFirst()) null else readCommit(rows, run.request)
            }
            if (existing != null) {
                require(EffectReviewDecision.fromReference(existing.fact.payloadRef) == decision) {
                    "EFFECT_REVIEW_ALREADY_RESOLVED"
                }
                return@transaction existing
            }
            val events = db.query("execution_events", null, "run_id=? AND item_id=?",
                arrayOf(runId.value, itemId.value), null, null, "sequence ASC").use { rows ->
                buildList { while (rows.moveToNext()) add(readCommit(rows, run.request)) }
            }
            val start = events.singleOrNull { it.fact.type == RunEventType.TOOL_ITEM_STARTED }
                ?: error("EFFECT_REVIEW_NOT_FOUND")
            val unknown = events.singleOrNull {
                it.fact.type == RunEventType.TOOL_ITEM_COMPLETED && it.fact.success == null
            } ?: error("EFFECT_REVIEW_NOT_REQUIRED")
            val toolName = unknown.fact.toolName ?: start.fact.toolName ?: error("EFFECT_REVIEW_TOOL_REQUIRED")
            require(toolNeedsEffectReview(toolName)) { "EFFECT_REVIEW_NOT_REQUIRED" }
            val others = pendingEffectReviewRows(db, runId.value, exceptItemId = itemId.value).size
            val target = when {
                run.state in terminalStates -> run.state
                pendingApprovalCount(db, runId) > 0L -> RunState.WAITING_AUTH
                pendingInputCount(db, runId) > 0L -> RunState.WAITING_USER
                pendingEventWaitCount(db, runId) > 0L -> RunState.WAITING_EVENT
                others > 0 -> run.state
                decision == EffectReviewDecision.CANCELLED -> RunState.CANCELLED
                else -> RunState.INTERRUPTED
            }
            appendIn(db, runId, ExecutionFact(
                key = "effect-review:${itemId.value}:${decision.name.lowercase()}",
                type = RunEventType.TOOL_EFFECT_REVIEW_RESOLVED,
                createdAt = now,
                turnId = start.fact.turnId,
                itemId = itemId,
                state = target,
                toolName = toolName,
                success = true,
                payloadRef = decision.reference,
            ))
        }
    }

    private fun validateApprovalFact(db: SQLiteDatabase, runId: RunId, fact: ExecutionFact) {
        if (fact.type !in setOf(RunEventType.APPROVAL_REQUIRED, RunEventType.APPROVAL_RESOLVED)) return
        require(fact.itemId != null && !fact.toolName.isNullOrBlank()) { "APPROVAL_IDENTITY_REQUIRED" }
        db.query("execution_events", arrayOf("type", "tool_name"), "run_id=? AND item_id=?",
            arrayOf(runId.value, fact.itemId?.value.orEmpty()), null, null, "sequence ASC").use { rows ->
            val prior = buildList { while (rows.moveToNext()) add(rows.getString(0) to rows.getString(1)) }
            if (fact.type == RunEventType.APPROVAL_REQUIRED) {
                check(prior.isEmpty()) { "DUPLICATE_APPROVAL_ITEM" }
                require(fact.success == null) { "APPROVAL_NOT_RESOLVED" }
            } else {
                check(prior.size == 1 && prior.single().first == RunEventType.APPROVAL_REQUIRED.name) { "ORPHAN_OR_DUPLICATE_APPROVAL" }
                require(prior.single().second == fact.toolName && fact.success != null) { "APPROVAL_RESULT_IDENTITY_MISMATCH" }
            }
        }
    }

    private fun pendingApprovalCount(db: SQLiteDatabase, id: RunId): Long = db.rawQuery("""
        SELECT COUNT(*) FROM execution_events starts WHERE starts.run_id=? AND starts.type='APPROVAL_REQUIRED'
        AND NOT EXISTS (SELECT 1 FROM execution_events ends WHERE ends.run_id=starts.run_id
            AND ends.item_id=starts.item_id AND ends.type='APPROVAL_RESOLVED')
    """, arrayOf(id.value)).use { it.moveToFirst(); it.getLong(0) }

    /**
     * A confirmation coroutine cannot survive process death. Resolve approvals left by the
     * previous process before the UI starts observing the durable ledger. They are never
     * approved here: the run becomes INTERRUPTED and any external effect still requires a fresh
     * decision after the user explicitly reruns the task.
     */
    internal fun reconcileAbandonedApprovals(now: Long): Int {
        require(now >= 0)
        return transaction(writableDatabase) {
            val db = writableDatabase
            val abandoned = db.rawQuery("""
                SELECT starts.run_id, starts.item_id, starts.tool_name
                FROM execution_events starts
                JOIN execution_runs runs ON runs.run_id=starts.run_id
                WHERE starts.type='APPROVAL_REQUIRED'
                  AND runs.state='WAITING_AUTH'
                  AND NOT EXISTS (
                    SELECT 1 FROM execution_events resolved
                    WHERE resolved.run_id=starts.run_id AND resolved.item_id=starts.item_id
                      AND resolved.type='APPROVAL_RESOLVED')
                ORDER BY starts.run_id, starts.sequence ASC
            """.trimIndent(), null).use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        val runId = rows.getString(0)
                        val itemId = rows.getString(1)
                        val toolName = rows.getString(2)
                        if (!runId.isNullOrBlank() && !itemId.isNullOrBlank() && !toolName.isNullOrBlank()) {
                            add(Triple(runId, itemId, toolName))
                        }
                    }
                }
            }
            abandoned.groupBy { it.first }.values.sumOf { approvals ->
                approvals.forEachIndexed { index, (runIdValue, itemId, toolName) ->
                    val lastForRun = index == approvals.lastIndex
                    val state = if (!lastForRun) RunState.RUNNING else when {
                        pendingEventWaitCount(db, RunId(runIdValue)) > 0L -> RunState.WAITING_EVENT
                        pendingInputCount(db, RunId(runIdValue)) > 0L -> RunState.WAITING_USER
                        pendingEffectReviewCount(db, RunId(runIdValue)) > 0 -> RunState.VERIFYING
                        else -> RunState.INTERRUPTED
                    }
                    appendIn(
                        db,
                        RunId(runIdValue),
                        ExecutionFact(
                            key = "approval:$itemId:process-restarted",
                            type = RunEventType.APPROVAL_RESOLVED,
                            createdAt = now,
                            itemId = ItemId(itemId),
                            state = state,
                            toolName = toolName,
                            success = false,
                            errorCode = "APPROVAL_PROCESS_RESTARTED",
                        ),
                    )
                }
                approvals.size
            }
        }
    }

    /**
     * A process death destroys the coroutine that was observing an external event. Close those
     * waits without replaying the pending screen action. Other durable waits keep the run alive.
     */
    internal fun reconcileAbandonedEventWaits(now: Long): Int {
        require(now >= 0)
        return transaction(writableDatabase) {
            val db = writableDatabase
            val abandoned = db.rawQuery("""
                SELECT starts.run_id, starts.item_id, starts.payload_ref
                FROM execution_events starts
                JOIN execution_runs runs ON runs.run_id=starts.run_id
                WHERE starts.type='EVENT_WAIT_REQUIRED'
                  AND runs.state NOT IN ('SUCCEEDED','FAILED','CANCELLED','INTERRUPTED')
                  AND NOT EXISTS (
                    SELECT 1 FROM execution_events resolved
                    WHERE resolved.run_id=starts.run_id AND resolved.item_id=starts.item_id
                      AND resolved.type='EVENT_WAIT_RESOLVED')
                ORDER BY starts.run_id, starts.sequence ASC
            """.trimIndent(), null).use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        val runId = rows.getString(0)
                        val itemId = rows.getString(1)
                        val payloadRef = rows.getString(2)
                        if (!runId.isNullOrBlank() && !itemId.isNullOrBlank() && !payloadRef.isNullOrBlank()) {
                            add(Triple(runId, itemId, payloadRef))
                        }
                    }
                }
            }
            abandoned.groupBy { it.first }.values.sumOf { waits ->
                waits.forEachIndexed { index, (runIdValue, itemId, payloadRef) ->
                    val runId = RunId(runIdValue)
                    val state = when {
                        index != waits.lastIndex -> RunState.WAITING_EVENT
                        pendingApprovalCount(db, runId) > 0L -> RunState.WAITING_AUTH
                        pendingInputCount(db, runId) > 0L -> RunState.WAITING_USER
                        pendingEffectReviewCount(db, runId) > 0 -> RunState.VERIFYING
                        else -> RunState.INTERRUPTED
                    }
                    appendIn(db, runId, ExecutionFact(
                        key = "event:$itemId:process-restarted",
                        type = RunEventType.EVENT_WAIT_RESOLVED,
                        createdAt = now,
                        itemId = ItemId(itemId),
                        state = state,
                        success = false,
                        errorCode = "EVENT_PROCESS_RESTARTED",
                        payloadRef = payloadRef,
                    ))
                }
                waits.size
            }
        }
    }

    private fun validateWorkflowFact(db: SQLiteDatabase, runId: RunId, fact: ExecutionFact) {
        val expected = when (fact.toolName) {
            "goal_manage" -> RunEventType.GOAL_UPDATED
            "plan_manage" -> RunEventType.PLAN_UPDATED
            "notepad_manage" -> RunEventType.NOTEPAD_UPDATED
            else -> null
        }
        val type = fact.type
        if (type !in setOf(RunEventType.WORKFLOW_UPDATE_STARTED, RunEventType.WORKFLOW_UPDATE_FAILED,
                RunEventType.GOAL_UPDATED, RunEventType.PLAN_UPDATED, RunEventType.NOTEPAD_UPDATED)) return
        require(fact.itemId != null && expected != null) { "WORKFLOW_IDENTITY_REQUIRED" }
        db.query("execution_events", arrayOf("type", "tool_name"), "run_id=? AND item_id=?",
            arrayOf(runId.value, fact.itemId?.value.orEmpty()), null, null, "sequence ASC").use { rows ->
            val prior = buildList { while (rows.moveToNext()) add(rows.getString(0) to rows.getString(1)) }
            if (type == RunEventType.WORKFLOW_UPDATE_STARTED) {
                check(prior.isEmpty()) { "DUPLICATE_WORKFLOW_ITEM" }
                require(fact.success == null && fact.payloadRef == null) { "INVALID_WORKFLOW_START" }
            } else {
                check(prior.size == 1 && prior.single().first == RunEventType.WORKFLOW_UPDATE_STARTED.name) { "ORPHAN_OR_DUPLICATE_WORKFLOW_RESULT" }
                require(prior.single().second == fact.toolName) { "WORKFLOW_RESULT_IDENTITY_MISMATCH" }
                require(if (type == RunEventType.WORKFLOW_UPDATE_FAILED) fact.success == false
                    else type == expected && fact.success == true && fact.payloadRef != null) { "INVALID_WORKFLOW_RESULT" }
            }
        }
    }

    private fun unsettledWorkflowCount(db: SQLiteDatabase, id: RunId): Long = db.rawQuery("""
        SELECT COUNT(*) FROM execution_events starts WHERE starts.run_id=? AND starts.type='WORKFLOW_UPDATE_STARTED'
        AND NOT EXISTS (SELECT 1 FROM execution_events ends WHERE ends.run_id=starts.run_id
            AND ends.item_id=starts.item_id AND ends.type IN ('GOAL_UPDATED','PLAN_UPDATED','NOTEPAD_UPDATED','WORKFLOW_UPDATE_FAILED'))
    """, arrayOf(id.value)).use { it.moveToFirst(); it.getLong(0) }

    override fun find(runId: RunId): ExecutionRunSnapshot? = findIn(readableDatabase, runId)

    private fun findIn(db: SQLiteDatabase, id: RunId): ExecutionRunSnapshot? =
        db.query("execution_runs", null, "run_id=?", arrayOf(id.value), null, null, null).use { if (it.moveToFirst()) readRun(it) else null }

    override fun timeline(runId: RunId, afterSequence: Long, limit: Int): List<ExecutionCommit> {
        val db = readableDatabase
        val run = findIn(db, runId) ?: return emptyList()
        return db.query("execution_events", null, "run_id=? AND sequence>?", arrayOf(runId.value, afterSequence.toString()),
            null, null, "sequence ASC", limit.coerceIn(1, 1000).toString()).use { rows ->
            buildList { while (rows.moveToNext()) add(readCommit(rows, run.request)) }
        }
    }

    override fun unfinished(): List<ExecutionRunSnapshot> = readableDatabase.query("execution_runs", null,
        "state NOT IN ('SUCCEEDED','FAILED','CANCELLED','INTERRUPTED')", null, null, null, "created_at ASC").use { rows ->
        buildList { while (rows.moveToNext()) add(readRun(rows)) }
    }

    /** Unresolved questions remain discoverable even when their run was marked interrupted. */
    internal fun unresolvedUserInputs(limit: Int = 32): List<UnresolvedUserInput> =
        readableDatabase.rawQuery("""
            SELECT req.run_id, req.item_id, req.payload_ref, req.created_at
            FROM execution_events req
            WHERE req.type='USER_INPUT_REQUIRED'
              AND NOT EXISTS (
                SELECT 1 FROM execution_events resolved
                WHERE resolved.run_id=req.run_id AND resolved.item_id=req.item_id
                  AND resolved.type='USER_INPUT_RESOLVED')
            ORDER BY req.created_at DESC
            LIMIT ?
        """.trimIndent(), arrayOf(limit.coerceIn(1, 128).toString())).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val runId = rows.getString(0)
                    val itemId = rows.getString(1)
                    val payloadRef = rows.getString(2)
                    if (!runId.isNullOrBlank() && !itemId.isNullOrBlank() && !payloadRef.isNullOrBlank()) {
                        add(UnresolvedUserInput(runId, itemId, payloadRef, rows.getLong(3)))
                    }
                }
            }
        }

    override fun items(runId: RunId): List<ExecutionItem> = readableDatabase.query("execution_items",null,
        "run_id=?",arrayOf(runId.value),null,null,"started_at,item_id").use { rows ->
        buildList { while(rows.moveToNext()) add(NexusExecutionProjection.read(rows)) }
    }

    internal fun item(itemId: ItemId): ExecutionItem? = readableDatabase.query("execution_items", null,
        "item_id=?", arrayOf(itemId.value), null, null, null, "2").use { rows ->
        if (!rows.moveToFirst()) return@use null
        val item = NexusExecutionProjection.read(rows)
        check(!rows.moveToNext()) { "EXECUTION_ITEM_AMBIGUOUS" }
        item
    }

    internal fun itemEvents(runId: RunId, itemId: ItemId): List<ExecutionCommit> {
        val run = find(runId) ?: return emptyList()
        return readableDatabase.query("execution_events", null, "run_id=? AND item_id=?", arrayOf(runId.value, itemId.value),
            null, null, "sequence ASC", "100").use { rows -> buildList { while (rows.moveToNext()) add(readCommit(rows, run.request)) } }
    }

    override fun saveCheckpoint(runId: RunId, throughSequence: Long, payload: String, now: Long): ExecutionCheckpoint {
        require(payload.length <= MAX_CHECKPOINT_CHARS && now >= 0) { "CHECKPOINT_TOO_LARGE_OR_INVALID" }
        val checksum = hash(payload)
        val db = writableDatabase
        return transaction(db) {
            val run = findIn(db,runId) ?: error("RUN_NOT_FOUND")
            require(throughSequence >= 0 && throughSequence <= run.lastSequence) { "CHECKPOINT_CURSOR_NOT_COMMITTED" }
            require(run.checkpointSequence?.let { throughSequence >= it } != false) { "CHECKPOINT_CURSOR_REGRESSION" }
            check(run.state !in terminalStates) { "RUN_ALREADY_TERMINAL" }
            // Replacing both metadata and chunks is transactional, including a failed cursor update.
            db.delete("execution_checkpoints","run_id=?",arrayOf(runId.value))
            db.insertOrThrow("execution_checkpoints",null,ContentValues().apply {
                put("run_id",runId.value); put("through_sequence",throughSequence); put("sha256",checksum)
                put("created_at",now); put("payload_chars",payload.length)
            })
            var offset=0
            var index=0
            while(offset<payload.length) {
                var end=minOf(payload.length,offset+CHECKPOINT_CHUNK_CHARS)
                if(end<payload.length && Character.isHighSurrogate(payload[end-1]) && Character.isLowSurrogate(payload[end])) end--
                val chunk=payload.substring(offset,end)
                db.insertOrThrow("execution_checkpoint_chunks",null,ContentValues().apply {
                    put("run_id",runId.value); put("chunk_index",index++); put("body",chunk)
                })
                offset=end
            }
            db.update("execution_runs",ContentValues().apply { put("checkpoint_sequence",throughSequence) },
                "run_id=?",arrayOf(runId.value))
            ExecutionCheckpoint(runId,throughSequence,checksum,now,payload)
        }
    }

    override fun checkpoint(runId: RunId): ExecutionCheckpoint? {
        val db=readableDatabase
        // A read transaction keeps metadata and all chunks from the same snapshot.
        return transaction(db) {
            db.query("execution_checkpoints",null,"run_id=?",arrayOf(runId.value),null,null,null).use { row ->
                if(!row.moveToFirst()) return@transaction null
                val expectedChars=row.number("payload_chars")
                check(expectedChars in 0L..MAX_CHECKPOINT_CHARS.toLong()) { "CHECKPOINT_SIZE_CORRUPT" }
                val payload=StringBuilder(expectedChars.toInt())
                var next=0
                db.query("execution_checkpoint_chunks",null,"run_id=?",arrayOf(runId.value),null,null,"chunk_index ASC").use { chunks ->
                    while(chunks.moveToNext()) {
                        check(chunks.number("chunk_index") == next++.toLong()) { "CHECKPOINT_CHUNK_MISSING" }
                        val chunk=chunks.text("body").orEmpty()
                        check(payload.length.toLong()+chunk.length <= expectedChars) { "CHECKPOINT_SIZE_CORRUPT" }
                        payload.append(chunk)
                    }
                }
                val encoded=payload.toString()
                check(encoded.length.toLong() == expectedChars && hash(encoded)==row.text("sha256")) { "CHECKPOINT_HASH_MISMATCH" }
                ExecutionCheckpoint(runId,row.number("through_sequence"),row.text("sha256")!!,row.number("created_at"),encoded)
            }
        }
    }

    fun recent(limit: Int = 20): List<ExecutionRunSnapshot> = readableDatabase.query("execution_runs", null,
        null, null, null, null, "updated_at DESC", limit.coerceIn(1,200).toString()).use { rows ->
        buildList { while (rows.moveToNext()) add(readRun(rows)) }
    }

    fun checkpointForBackup() {
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "Execution ledger has pending writes" }
        }
    }

    fun recordCount(): Int = readableDatabase.rawQuery(
        "SELECT (SELECT COUNT(*) FROM execution_runs)+(SELECT COUNT(*) FROM execution_events)+(SELECT COUNT(*) FROM execution_items)+(SELECT COUNT(*) FROM execution_checkpoints)+(SELECT COUNT(*) FROM execution_model_requests)", null,
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    override fun reserveModelRequest(request: ModelBudgetRequest, now: Long): ModelBudgetRecord = transaction(writableDatabase) {
        modelBudgetLedger.reserve(writableDatabase, request, now)
    }
    override fun settleModelRequest(runId: RunId, settlement: ModelBudgetSettlement, now: Long): ModelBudgetRecord = transaction(writableDatabase) {
        modelBudgetLedger.settle(writableDatabase, runId, settlement, now)
    }
    override fun modelBudgetTotals(runId: RunId): ModelBudgetTotals = transaction(readableDatabase) {
        modelBudgetLedger.totals(readableDatabase, runId)
    }
    override fun modelBudgetRequests(runId: RunId): List<ModelBudgetRecord> = modelBudgetLedger.requests(readableDatabase, runId)

    private fun readRun(row: Cursor) = ExecutionRunSnapshot(
        decode(row.text("request_json")!!), RunState.valueOf(row.text("state")!!), row.number("last_sequence"),
        row.getColumnIndexOrThrow("checkpoint_sequence").let { if (row.isNull(it)) null else row.getLong(it) },
        row.number("created_at"), row.number("updated_at"),
        decode(row.text("admitted_request_json") ?: row.text("request_json")!!),
    )

    private fun readFact(row: Cursor) = ExecutionFact(
        row.text("fact_key")!!, RunEventType.valueOf(row.text("type")!!), row.number("created_at"),
        row.text("turn_id")?.let(::TurnId), row.text("item_id")?.let(::ItemId),
        row.text("state")?.let(RunState::valueOf), row.text("tool_name"),
        row.getColumnIndexOrThrow("success").let { if (row.isNull(it)) null else row.getInt(it) == 1 }, row.text("error_code"),
        row.text("payload_ref"),
        row.text("budget_json")?.let { decodeBudget(JSONObject(it)) }, row.text("goal_id")?.let(::GoalId),
    )

    private fun readCommit(row: Cursor, request: RunRequest): ExecutionCommit {
        val fact = readFact(row)
        return ExecutionCommit(row.text("event_id")!!, event(request, row.number("sequence"), fact), fact)
    }

    private fun event(request: RunRequest, sequence: Long, fact: ExecutionFact) = ExecutionEvent(
        request.runId, sequence, fact.type, request.source, request.hostId, fact.createdAt,
        turnId = fact.turnId, itemId = fact.itemId, state = fact.state, errorCode = fact.errorCode, payloadRef = fact.payloadRef,
        budgetLimit = fact.budgetLimit, goalId = fact.goalId,
    )

    private fun Cursor.text(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.number(name: String): Long = getLong(getColumnIndexOrThrow(name))

    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        return try { block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    private fun encode(request: RunRequest): String = JSONObject().apply {
        put("run_id", request.runId.value); put("source", request.source.name); put("mode", request.mode.name)
        put("host_id", request.hostId.value); put("session_id", request.sessionId?.value)
        put("project_id", request.projectId); put("parent_run_id", request.parentRunId?.value)
        put("task_id", request.taskId?.value); put("goal_id", request.goalId?.value)
        put("budget", encodeBudget(request.budget))
    }.toString()

    private fun decode(raw: String): RunRequest {
        val json = JSONObject(raw); val b = json.getJSONObject("budget")
        fun optional(name: String) = json.optString(name).takeIf(String::isNotEmpty)
        return RunRequest(RunId(json.getString("run_id")), RunSource.valueOf(json.getString("source")),
            RunMode.valueOf(json.getString("mode")), HostId(json.getString("host_id")),
            optional("session_id")?.let(::SessionId), optional("project_id"), optional("parent_run_id")?.let(::RunId),
            optional("task_id")?.let(::TaskId), optional("goal_id")?.let(::GoalId),
            decodeBudget(b))
    }

    private fun encodeBudget(budget: RunBudget) = JSONObject().apply {
        put("soft_rounds", budget.softRounds); put("hard_rounds", budget.hardRounds)
        put("soft_ms", budget.softDurationMs); put("hard_ms", budget.hardDurationMs)
        put("soft_tools", budget.softToolCalls); put("hard_tools", budget.hardToolCalls)
        put("cost", budget.costLimitMicros)
        put("currency", budget.costCurrency)
    }

    private fun decodeBudget(json: JSONObject): RunBudget {
        fun number(name: String): Long? = if (json.isNull(name)) null else json.getLong(name)
        return RunBudget(number("soft_rounds")?.toInt(), number("hard_rounds")?.toInt(), number("soft_ms"),
            number("hard_ms"), number("soft_tools")?.toInt(), number("hard_tools")?.toInt(), number("cost"),
            json.optString("currency", "USD"))
    }

    companion object {
        const val DATABASE_NAME = "nexus_execution.db"
        const val VERSION = 5
        private const val CHECKPOINT_CHUNK_CHARS = 32 * 1024
        private const val MAX_CHECKPOINT_CHARS = 64 * 1024 * 1024
        val terminalStates = setOf(RunState.SUCCEEDED, RunState.FAILED, RunState.CANCELLED, RunState.INTERRUPTED)
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun validTransition(before: RunState, after: RunState): Boolean = before == after || when (before) {
            RunState.CREATED -> after in setOf(RunState.ADMITTED, RunState.FAILED, RunState.CANCELLED, RunState.INTERRUPTED)
            RunState.ADMITTED -> after in setOf(RunState.RUNNING, RunState.FAILED, RunState.CANCELLED, RunState.INTERRUPTED)
            RunState.RUNNING -> after in setOf(RunState.WAITING_USER, RunState.WAITING_AUTH, RunState.WAITING_EVENT, RunState.PAUSED, RunState.VERIFYING) || after in terminalStates
            RunState.WAITING_USER, RunState.WAITING_AUTH, RunState.WAITING_EVENT, RunState.PAUSED -> after in setOf(
                RunState.RUNNING, RunState.WAITING_USER, RunState.WAITING_AUTH, RunState.WAITING_EVENT, RunState.PAUSED,
                RunState.VERIFYING,
            ) || after in terminalStates
            RunState.VERIFYING -> after in setOf(RunState.WAITING_USER, RunState.WAITING_AUTH, RunState.WAITING_EVENT, RunState.PAUSED) || after in terminalStates
            else -> false
        }

        @Volatile private var shared: NexusExecutionStore? = null
        fun get(context: Context): NexusExecutionStore = shared ?: synchronized(this) {
            shared ?: NexusExecutionStore(context.applicationContext).also { shared = it }
        }

        fun closeForTests() = synchronized(this) { shared?.close(); shared = null }
    }
}
