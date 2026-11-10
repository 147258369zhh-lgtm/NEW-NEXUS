package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.fork.projects.ProjectRepository
import io.github.mangi.eta.fork.projects.ProjectSubjectType
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Room transactions serialize writers. The service/UI lifecycle never determines whether a run is counted. */
internal object AgentRunMetricsStore {
    internal const val LEGACY_PREFS = "eta_run_metrics"
    internal const val LEGACY_KEY = "records_v1"
    private const val IMPORTED = "legacy_imported"
    private const val MAX_COMPLETED = 200
    private val ownerSession = UUID.randomUUID().toString()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "eta-metrics-io") }
    private data class Pending(val context: Context, var snapshot: AgentRunTelemetrySnapshot?)
    private val pending = mutableMapOf<String, Pending>()
    private val writeFailure = AtomicReference<Exception?>()

    fun init(context: Context) {
        val app = context.applicationContext
        writer.execute { safely {
            val db = AgentMetricsDatabase.get(app)
            migrateLegacy(app, db)
            recoverInterrupted(db)
        } }
    }

    fun enqueue(context: Context, snapshot: AgentRunTelemetrySnapshot) {
        val app = context.applicationContext
        synchronized(pending) {
            val prior = pending[snapshot.recordId]
            if (prior != null) {
                val old = prior.snapshot
                if (old == null || snapshot.revision > old.revision) prior.snapshot = snapshot
                return
            }
            pending[snapshot.recordId] = Pending(app, snapshot)
            writer.execute {
                while (true) {
                    val item = synchronized(pending) {
                        val next = pending[snapshot.recordId] ?: return@execute
                        val latest = next.snapshot
                        if (latest == null) { pending.remove(snapshot.recordId); return@execute }
                        next.snapshot = null
                        next.context to latest
                    }
                    safely { save(item.first, item.second) }
                }
            }
        }
    }

    fun records(context: Context): Flow<List<AgentRunMetricsRecord>> = flow {
        val db = AgentMetricsDatabase.get(context)
        migrateLegacy(context, db)
        emitAll(db.dao().recordsFlow())
    }.flowOn(Dispatchers.IO)

    fun detail(context: Context, id: String): Flow<AgentMetricDetail?> = flow {
        val db = AgentMetricsDatabase.get(context)
        migrateLegacy(context, db)
        val dao = db.dao()
        // Room's aggregate @Relation projection has produced empty request collections on
        // Android 16/17 even when metric_requests contains the matching rows. Keep the normal
        // run/trace/model projection, but merge the request ledger from its own observable query
        // so the detail screen cannot silently fall back to the legacy placeholder.
        emitAll(combine(dao.detailFlow(id), dao.requestsFlow(id)) { detail, requests ->
            detail?.copy(requests = requests)
        })
    }.flowOn(Dispatchers.IO)

    fun models(context: Context): Flow<List<AgentMetricModelEntity>> = flow {
        emitAll(AgentMetricsDatabase.get(context).dao().modelsFlow())
    }.flowOn(Dispatchers.IO)

    fun overview(context: Context): Flow<List<AgentMetricOverview>> = flow {
        val db = AgentMetricsDatabase.get(context)
        migrateLegacy(context, db)
        emitAll(db.dao().overviewFlow())
    }.flowOn(Dispatchers.IO)

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        writer.submit {
            val db = AgentMetricsDatabase.get(context)
            migrateLegacy(context, db)
            db.runInTransaction { db.dao().clearCompleted() }
        }.get(10, TimeUnit.SECONDS)
        Unit
    }

    /** Backup waits for accepted metrics checkpoints before taking the database snapshot. */
    fun flush() {
        writer.submit {}.get(10, TimeUnit.SECONDS)
        writeFailure.getAndSet(null)?.let { throw IllegalStateException("运行统计尚未成功保存，请重试备份", it) }
    }

    internal fun save(context: Context, snapshot: AgentRunTelemetrySnapshot, database: AgentMetricsDatabase = AgentMetricsDatabase.get(context)) {
        require(snapshot.runId.isNotBlank() && snapshot.runId.length <= 256)
        require(snapshot.recordId.isNotBlank() && snapshot.recordId.length <= 288)
        migrateLegacy(context, database)
        val id = snapshot.recordId
        val dao = database.dao()
        // Resolve project once on the first capture; later project moves do not rewrite history.
        val prior = dao.record(id)
        val project = if (prior != null) prior.projectId else runCatching {
            runBlocking {
                when {
                    snapshot.roomId.isNotBlank() -> ProjectRepository.bindingForSubject(context, ProjectSubjectType.MEETING, snapshot.roomId)
                    snapshot.conversationId.isNotBlank() -> ProjectRepository.bindingForSubject(context, ProjectSubjectType.CONVERSATION, snapshot.conversationId)
                    else -> null
                }?.projectId.orEmpty()
            }
        }.getOrDefault("")
        val localSamples = snapshot.requests.filter { it.localRuntime != null }
        database.runInTransaction {
            val current = dao.record(id)
            if (current != null && current.status != AgentMetricStatus.RUNNING && current.status != AgentMetricStatus.LEGACY) return@runInTransaction
            if (current != null && current.ownerSession == ownerSession && current.revision >= snapshot.revision) return@runInTransaction
            dao.putRun(AgentRunMetricsRecord(
                id, snapshot.runId, ownerSession, snapshot.source, snapshot.operation,
                snapshot.conversationId, snapshot.roomId, snapshot.taskId, current?.projectId ?: project,
                snapshot.startedAt, snapshot.updatedAt, snapshot.completedAt, snapshot.durationMs,
                snapshot.status, snapshot.revision, snapshot.omittedTraceSteps, snapshot.metrics,
                snapshot.models.firstOrNull()?.providerId.orEmpty(), snapshot.models.firstOrNull()?.modelId.orEmpty(),
                snapshot.models.firstOrNull()?.name.orEmpty(),
            ))
            dao.clearTrace(id)
            dao.putTrace(snapshot.trace.takeLast(AgentRunTelemetry.MAX_TRACE_STEPS).map {
                AgentMetricTraceEntity(id, it.sequence, it.elapsedMs, it.kind, it.round, it.label, it.success)
            })
            dao.clearModels(id)
            dao.putModels(snapshot.models.take(8).map {
                AgentMetricModelEntity(id, it.index, it.providerId, it.modelId, it.name, it.metrics, it.price, it.priceCaptured)
            })
            val knownChildren = dao.children(id).associateBy { it.scopeId }
            dao.putChildren(snapshot.children.filter { (knownChildren[it.scopeId]?.revision ?: 0) < it.revision }.map {
                AgentMetricChildEntity(id, it.scopeId, it.parentScopeId, it.index, it.kind, it.parentRound, it.toolCallId,
                    it.startedAt, it.completedAt, it.durationMs, it.status, it.revision,
                    it.providerId, it.modelId, it.name, it.metrics, it.price, it.priceCaptured)
            })
            dao.putRequests(snapshot.requests.take(AgentRequestLedger.MAX_REQUESTS_PER_SCOPE * 8).map { request ->
                AgentMetricRequestEntity(
                    id, request.requestId, request.scopeId, request.order,
                    request.providerId, request.modelId, request.name, request.round, request.attempt,
                    request.startedAt, request.startedElapsedMs, request.completedAt, request.completedElapsedMs,
                    request.status, request.httpCode, request.failureCode, request.responseMs, request.firstTextMs,
                    request.totalMs, request.usage, request.price, request.priceCaptured,
                )
            })
            dao.prune(MAX_COMPLETED)
        }
        // Local inference metrics use an independent database so adding hardware/cache counters
        // never changes the run-metrics schema or backup compatibility. Upserts are idempotent
        // across telemetry checkpoints and remain queryable after the run record is pruned.
        localSamples.forEach { request ->
            runCatching { LocalModelMetricsStore.recordSnapshot(context, id, request) }
        }
    }

    internal fun migrateLegacy(context: Context, db: AgentMetricsDatabase) {
        val prefs = context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        db.runInTransaction {
            val dao = db.dao()
            if (dao.meta(IMPORTED) != null) return@runInTransaction
            val raw = prefs.getString(LEGACY_KEY, null)?.takeIf { it.length <= 1_048_576 }
            val records = raw?.let { runCatching { JSONArray(it) }.getOrNull() }
            for (index in 0 until minOf(records?.length() ?: 0, MAX_COMPLETED)) {
                val item = records?.optJSONObject(index) ?: continue
                val id = item.optString("run_id").takeIf { it.isNotBlank() && it.length <= 256 } ?: continue
                if (dao.record(id) != null) continue
                val time = item.optLong("completed_at").coerceAtLeast(0L)
                // Legacy zeros and coverage were ambiguous; do not invent reported-zero usage.
                val calls = item.optInt("tool_calls").coerceAtLeast(0)
                val failures = item.optInt("failed_tools").coerceIn(0, calls)
                val metrics = AgentRunMetrics(
                    rounds = item.optInt("rounds").coerceAtLeast(0),
                    toolCalls = calls,
                    failedTools = failures,
                    unresolvedTools = calls - failures,
                    inputTokens = item.optLong("input_tokens").takeIf { it > 0 },
                    outputTokens = item.optLong("output_tokens").takeIf { it > 0 },
                    contextTokens = item.optInt("context_tokens").takeIf { it > 0 },
                    imageBytes = item.optLong("image_bytes").coerceAtLeast(0L),
                )
                dao.putRun(AgentRunMetricsRecord(id, id, "legacy", "legacy", "chat", "", "", "", "",
                    time, time, time, 0, AgentMetricStatus.LEGACY, 0, 0, metrics))
            }
            dao.putMeta(AgentMetricMetaEntity(IMPORTED, "1"))
            dao.prune(MAX_COMPLETED)
        }
        // Kept as migration evidence; the durable marker prevents reimport after clearing history.
    }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (failure: Exception) {
            writeFailure.set(failure)
            AndroidAgentLogger.warn("Metrics persistence failed: type=${failure.safeLogType()}")
        }
    }

    internal fun recoverInterrupted(db: AgentMetricsDatabase) = db.runInTransaction {
        val dao = db.dao()
        dao.abandonedRuns(ownerSession).forEach { record ->
            val detail = dao.detail(record.recordId) ?: return@forEach
            val steps = detail.trace.sortedBy { it.sequence }
            val interrupted = AgentMetricTraceEntity(record.recordId, (steps.lastOrNull()?.sequence ?: -1) + 1,
                record.durationMs, AgentMetricStatus.INTERRUPTED, null, "", null)
            val retained = (steps + interrupted).takeLast(AgentRunTelemetry.MAX_TRACE_STEPS)
            dao.putRun(record.copy(status = AgentMetricStatus.INTERRUPTED, completedAt = record.updatedAt,
                omittedTraceSteps = record.omittedTraceSteps + steps.size + 1 - retained.size))
            dao.clearTrace(record.recordId)
            dao.putTrace(retained)
            dao.putChildren(detail.children.filter { it.status == AgentMetricStatus.RUNNING }.map {
                it.copy(status = AgentMetricStatus.INTERRUPTED, completedAt = record.updatedAt.coerceAtLeast(it.startedAt), revision = it.revision + 1)
            })
            dao.putRequests(dao.requests(record.recordId).filter { it.status == AgentMetricStatus.RUNNING }.map {
                it.copy(
                    status = AgentMetricStatus.INTERRUPTED,
                    completedAt = record.updatedAt.coerceAtLeast(it.startedAt),
                    completedElapsedMs = record.durationMs,
                    totalMs = (record.durationMs - it.startedElapsedMs).coerceAtLeast(0L),
                    failureCode = "process_interrupted",
                )
            })
        }
        dao.prune(MAX_COMPLETED)
    }
}
