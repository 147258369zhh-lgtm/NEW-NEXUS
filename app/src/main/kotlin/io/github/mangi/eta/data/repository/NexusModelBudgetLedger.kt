package io.github.mangi.eta.data.repository

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.zhh_lgtm.nexus.application.api.*
import io.github.zhh_lgtm.nexus.kernel.api.*
import org.json.JSONObject

/** All methods run inside the execution store's transaction. */
internal class NexusModelBudgetLedger(
    private val find: (SQLiteDatabase, RunId) -> ExecutionRunSnapshot?,
    private val append: (SQLiteDatabase, RunId, ExecutionFact) -> ExecutionCommit,
) {
    fun reserve(db: SQLiteDatabase, request: ModelBudgetRequest, now: Long): ModelBudgetRecord {
        read(db, request.requestId)?.let {
            require(it.request == request) { "MODEL_REQUEST_ID_CONFLICT" }
            return it
        }
        val run = find(db, request.runId) ?: throw ModelBudgetDenied("MODEL_RUN_NOT_FOUND")
        if (run.state != RunState.RUNNING) throw ModelBudgetDenied("MODEL_RUN_NOT_ACTIVE")
        val family = root(db, run)
        if (family.state in terminal) throw ModelBudgetDenied("MODEL_PARENT_ALREADY_TERMINAL")
        checkLimits(db, family, "family_run_id", family.request.runId.value, request)
        if (family.request.runId != request.runId) checkLimits(db, run, "run_id", request.runId.value, request)
        val commit = append(db, request.runId, ExecutionFact("model-budget:${request.requestId}:reserved",
            RunEventType.MODEL_BUDGET_RESERVED, now, itemId = item(request.requestId),
            payloadRef = "nexus-model-request://${request.requestId}"))
        db.insertOrThrow("execution_model_requests", null, ContentValues().apply {
            put("request_id", request.requestId); put("run_id", request.runId.value); put("family_run_id", family.request.runId.value)
            put("reserved_sequence", commit.event.sequence); put("request_json", encode(request).toString())
            put("currency", request.currency)
        })
        return checkNotNull(read(db, request.requestId))
    }

    fun settle(db: SQLiteDatabase, runId: RunId, value: ModelBudgetSettlement, now: Long): ModelBudgetRecord {
        val current = read(db, value.requestId) ?: throw ModelBudgetDenied("MODEL_RESERVATION_NOT_FOUND")
        require(current.request.runId == runId) { "MODEL_RESERVATION_OWNER_MISMATCH" }
        current.settlement?.let { require(it == value) { "MODEL_SETTLEMENT_CONFLICT" }; return current }
        require(value.costMicros == null || current.request.currency != null) { "MODEL_COST_CURRENCY_REQUIRED" }
        val commit = append(db, runId, ExecutionFact("model-budget:${value.requestId}:settled",
            RunEventType.MODEL_BUDGET_SETTLED, now, itemId = item(value.requestId), success = value.successful,
            payloadRef = "nexus-model-request://${value.requestId}"))
        db.update("execution_model_requests", ContentValues().apply {
            put("settled_sequence", commit.event.sequence); put("settlement_json", encode(value).toString())
            if (value.costMicros == null) putNull("cost_micros") else put("cost_micros", value.costMicros)
            put("usage_complete", if (value.usageComplete) 1 else 0)
        }, "request_id=?", arrayOf(value.requestId))
        return checkNotNull(read(db, value.requestId))
    }

    fun totals(db: SQLiteDatabase, runId: RunId): ModelBudgetTotals {
        val run = find(db, runId) ?: throw ModelBudgetDenied("MODEL_RUN_NOT_FOUND")
        val family = root(db, run)
        val budget = if (family.request.budget.costLimitMicros != null) family.request.budget else run.request.budget
        return totalsFor(db, "family_run_id", family.request.runId.value, budget.costCurrency, family.request.runId)
            .copy(costLimitMicros = budget.costLimitMicros)
    }

    fun requests(db: SQLiteDatabase, runId: RunId): List<ModelBudgetRecord> = db.query("execution_model_requests", null,
        "run_id=?", arrayOf(runId.value), null, null, "reserved_sequence").use { rows ->
        buildList { while (rows.moveToNext()) add(decode(rows)) }
    }

    private fun checkLimits(db: SQLiteDatabase, run: ExecutionRunSnapshot, column: String, id: String, request: ModelBudgetRequest) {
        val budget = run.request.budget
        val totals = totalsFor(db, column, id, budget.costCurrency, run.request.runId)
        if (budget.hardRounds?.let { totals.requests >= it } == true) throw ModelBudgetDenied("RUN_TOTAL_MODEL_REQUESTS_EXCEEDED")
        budget.costLimitMicros?.let { limit ->
            if (request.currency == null || request.priceRevision == null) throw ModelBudgetDenied("MODEL_COST_PRICE_REQUIRED")
            if (request.currency != budget.costCurrency) throw ModelBudgetDenied("MODEL_COST_CURRENCY_MISMATCH")
            if (totals.unknownCharges > 0) throw ModelBudgetDenied("MODEL_COST_UNVERIFIED")
            if (totals.knownCostMicros >= limit) throw ModelBudgetDenied("RUN_COST_BUDGET_EXCEEDED")
            if (totals.openRequests > 0) throw ModelBudgetDenied("MODEL_COST_PENDING")
            val reservation = request.reservedCostMicros ?: throw ModelBudgetDenied("MODEL_COST_RESERVATION_REQUIRED")
            if (reservation > limit - totals.knownCostMicros) throw ModelBudgetDenied("RUN_COST_RESERVATION_EXCEEDED")
        }
    }

    private fun totalsFor(db: SQLiteDatabase, column: String, id: String, currency: String, root: RunId): ModelBudgetTotals {
        require(column in setOf("run_id", "family_run_id"))
        var requests = 0L; var open = 0L; var unknown = 0L; var cost = 0L; var reserved = 0L
        db.query("execution_model_requests", arrayOf("settlement_json", "cost_micros", "currency", "usage_complete", "request_json"),
            "$column=?", arrayOf(id), null, null, null).use { rows ->
            while (rows.moveToNext()) {
                requests++
                if (rows.isNull(0)) {
                    open++
                    val request = JSONObject(rows.getString(4))
                    if (!request.isNull("reserved_cost_micros")) {
                        try { reserved = Math.addExact(reserved, request.getLong("reserved_cost_micros")) }
                        catch (_: ArithmeticException) { throw ModelBudgetDenied("MODEL_COST_OVERFLOW") }
                    }
                }
                else if (rows.isNull(1) || rows.isNull(3) || rows.getInt(3) != 1 ||
                    rows.getLong(1) > 0 && rows.getString(2) != currency) unknown++
                else try { cost = Math.addExact(cost, rows.getLong(1)) }
                    catch (_: ArithmeticException) { throw ModelBudgetDenied("MODEL_COST_OVERFLOW") }
            }
        }
        return ModelBudgetTotals(root, requests, open, unknown, cost, currency, reservedCostMicros = reserved)
    }

    private fun root(db: SQLiteDatabase, run: ExecutionRunSnapshot): ExecutionRunSnapshot {
        var current = run
        val seen = mutableSetOf<RunId>()
        while (seen.add(current.request.runId)) {
            val parent = current.request.parentRunId ?: return current
            current = find(db, parent) ?: throw ModelBudgetDenied("MODEL_PARENT_NOT_FOUND")
        }
        throw ModelBudgetDenied("MODEL_PARENT_CYCLE")
    }

    private fun read(db: SQLiteDatabase, id: String): ModelBudgetRecord? = db.query("execution_model_requests", null,
        "request_id=?", arrayOf(id), null, null, null).use { if (it.moveToFirst()) decode(it) else null }

    private fun decode(row: Cursor): ModelBudgetRecord {
        fun text(name: String): String? = row.getColumnIndexOrThrow(name).let { if (row.isNull(it)) null else row.getString(it) }
        fun number(name: String): Long? = row.getColumnIndexOrThrow(name).let { if (row.isNull(it)) null else row.getLong(it) }
        val json = JSONObject(text("request_json")!!)
        fun optional(key: String) = json.optString(key).takeIf(String::isNotEmpty)
        val request = ModelBudgetRequest(json.getString("id"), RunId(json.getString("run")), json.getString("provider"),
            json.getString("model"), json.getString("purpose"), optional("currency"), optional("price_revision"),
            optional("input_rate"), optional("output_rate"), optional("cache_read_rate"), optional("cache_write_rate"),
            if (json.isNull("reserved_cost_micros")) null else json.getLong("reserved_cost_micros"))
        val settlement = text("settlement_json")?.let { body ->
            val value = JSONObject(body)
            fun token(key: String): Long? = if (value.isNull(key)) null else value.getLong(key)
            ModelBudgetSettlement(request.requestId, token("cost"), value.getBoolean("complete"), value.getBoolean("successful"),
                token("input"), token("output"), token("cache_read"), token("cache_write"))
        }
        return ModelBudgetRecord(request, RunId(text("family_run_id")!!), number("reserved_sequence")!!, number("settled_sequence"), settlement)
    }

    private fun encode(request: ModelBudgetRequest) = JSONObject().put("id", request.requestId).put("run", request.runId.value)
        .put("provider", request.providerId).put("model", request.modelId).put("purpose", request.purpose)
        .put("currency", request.currency).put("price_revision", request.priceRevision).put("input_rate", request.inputRate)
        .put("output_rate", request.outputRate).put("cache_read_rate", request.cacheReadRate).put("cache_write_rate", request.cacheWriteRate)
        .put("reserved_cost_micros", request.reservedCostMicros)

    private fun encode(value: ModelBudgetSettlement) = JSONObject().put("cost", value.costMicros).put("complete", value.usageComplete)
        .put("successful", value.successful).put("input", value.inputTokens).put("output", value.outputTokens)
        .put("cache_read", value.cacheReadTokens).put("cache_write", value.cacheWriteTokens)

    private fun item(id: String) = ItemId("model-$id")

    companion object {
        private val terminal = setOf(RunState.SUCCEEDED, RunState.FAILED, RunState.CANCELLED, RunState.INTERRUPTED)
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE execution_model_requests (
                request_id TEXT PRIMARY KEY NOT NULL, run_id TEXT NOT NULL, family_run_id TEXT NOT NULL,
                reserved_sequence INTEGER NOT NULL, settled_sequence INTEGER, request_json TEXT NOT NULL,
                settlement_json TEXT, currency TEXT, cost_micros INTEGER, usage_complete INTEGER,
                FOREIGN KEY(run_id) REFERENCES execution_runs(run_id),
                FOREIGN KEY(family_run_id) REFERENCES execution_runs(run_id))""")
            db.execSQL("CREATE INDEX execution_model_requests_family ON execution_model_requests(family_run_id)")
            db.execSQL("CREATE INDEX execution_model_requests_run ON execution_model_requests(run_id,reserved_sequence)")
        }
    }
}
