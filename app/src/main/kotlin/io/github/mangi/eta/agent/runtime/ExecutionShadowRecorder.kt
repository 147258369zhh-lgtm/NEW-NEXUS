package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.kernel.api.NexusProduct
import io.github.zhh_lgtm.nexus.kernel.api.RunId
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Non-blocking shadow execution ledger. A bounded queue and one writer prevent diagnostics from
 * stalling the Agent loop; write failures are contained to the ledger file.
 */
internal class ExecutionShadowRecorder constructor(
    private val output: File,
    private val metadata: JSONObject,
    queueCapacity: Int = 2_048,
    private val openOutput: (File) -> OutputStream = { FileOutputStream(it, true) },
) : AutoCloseable {
    private val queue = ArrayBlockingQueue<String>(queueCapacity)
    private val producerLock = Any()
    private val closed = AtomicBoolean(false)
    private val sequence = AtomicLong(nextSequence(output))
    private val dropped = AtomicLong(0)
    private val writerFailure = AtomicReference<String?>(null)
    private val committedSequence = AtomicLong(-1)
    @Volatile private var terminalLine: String? = null
    private val writer = Thread(::writeLoop, "nexus-execution-ledger").apply {
        isDaemon = true
        start()
    }

    init {
        enqueue(record("run.started", JSONObject()))
    }

    fun record(event: AgentEvent): Unit = synchronized(producerLock) {
        if (closed.get()) return
        val shape = event.ledgerShape()
        enqueue(record(
            type = shape.type,
            payload = shape.payload
                .put("legacy_event_type", event.javaClass.simpleName)
                .put("dropped_total", dropped.get()),
            turnId = shape.turnId,
            itemId = shape.itemId,
            status = shape.status,
        ))
    }

    fun finish(result: AgentRuntimeWire.RunResult, cancelled: Boolean = false): Unit = synchronized(producerLock) {
        if (closed.get()) return
        // Reserve terminal outside the bounded queue: overflow must not strand the writer.
        terminalLine = record("run.terminal", JSONObject()
            .put("ok", result.ok)
            .put("error_chars", result.error.orEmpty().length)
            .put("content_chars", result.content.length)
            .put("transcript_messages", result.transcript.size)
            .put("dropped_total", dropped.get()), status = when { cancelled -> "cancelled"; result.ok -> "succeeded"; else -> "failed" })
        shutdown()
    }

    private fun record(
        type: String,
        payload: JSONObject,
        turnId: String? = null,
        itemId: String? = null,
        status: String? = null,
    ): String {
        val index = sequence.getAndIncrement()
        return JSONObject()
        .put("schema_version", NexusProduct.DATA_SCHEMA_VERSION)
        .put("event_id", UUID.nameUUIDFromBytes("${metadata.optString("run_id")}:$index".toByteArray(Charsets.UTF_8)).toString())
        .put("sequence", index)
        .put("created_at", System.currentTimeMillis())
        .put("type", type)
        .put("turn_id", turnId ?: JSONObject.NULL)
        .put("item_id", itemId ?: JSONObject.NULL)
        .put("status", status ?: JSONObject.NULL)
        .put("run", JSONObject(metadata.toString()))
        .put("payload", payload)
        .toString()
    }

    private fun enqueue(line: String) {
        if (writerFailure.get() != null || !queue.offer(line)) dropped.incrementAndGet()
    }

    private fun writeLoop() {
        try {
            output.parentFile?.mkdirs()
            openOutput(output).buffered().use { stream ->
                fun write(line: String) {
                    stream.write((line + "\n").toByteArray(Charsets.UTF_8))
                    stream.flush()
                    committedSequence.set(JSONObject(line).getLong("sequence"))
                }
                while (!closed.get() || queue.isNotEmpty()) {
                    queue.poll(50, TimeUnit.MILLISECONDS)?.let(::write)
                }
                terminalLine?.let(::write)
            }
        } catch (failure: Exception) {
            // A partial write is not a valid fact. Stop this shadow writer and expose failure.
            writerFailure.set(failure.javaClass.simpleName)
            queue.clear()
        }
    }

    override fun close() = synchronized(producerLock) { shutdown() }

    fun awaitClosed(timeoutMs: Long = 2_000): Boolean {
        writer.join(timeoutMs.coerceAtLeast(1))
        return !writer.isAlive
    }

    fun health() = Health(committedSequence.get(), dropped.get(), writerFailure.get(), closed.get() && !writer.isAlive)

    data class Health(val committedSequence: Long, val droppedEvents: Long, val failureType: String?, val writerStopped: Boolean)

    private fun shutdown() {
        closed.set(true)
    }

    companion object {

        fun create(context: Context, request: AgentRuntimeWire.RunRequest): ExecutionShadowRecorder? = runCatching {
            val app = context.applicationContext
            val mapping = NexusRunMapper.fromLegacy(request)
            val conversationId = mapping.request.sessionId?.value
                ?: AgentConversationIdentity.fromHandoff(request.handoff, request.runId)
            val source = request.handoff?.source?.takeIf(String::isNotBlank) ?: "ui"
            val directory = File(app.filesDir, "execution-ledger")
            prune(directory)
            val safeRunId = request.runId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val metadata = JSONObject()
                .put("run_id", request.runId)
                .put("canonical_run_id", mapping.request.runId.value)
                .put("session_id", conversationId)
                .put("source", mapping.request.source.name)
                .put("legacy_source", source)
                .put("mode", mapping.request.mode.name)
                .put("host_id", mapping.request.hostId.value)
                .put("task_id", mapping.request.taskId?.value ?: JSONObject.NULL)
                .put("budget", budgetJson(mapping.request.budget))
                .put("operation", request.operation)
            ExecutionShadowRecorder(File(directory, "$safeRunId.jsonl"), metadata)
        }.onFailure { failure ->
            AndroidAgentLogger.warn("Execution ledger initialization failed: type=${failure.safeLogType()}")
        }.getOrNull()

        fun readTimeline(context: Context, runId: String, afterSequence: Long = -1L): List<JSONObject> {
            val directory = File(context.applicationContext.filesDir, "execution-ledger")
            val safeRunId = runId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(directory, "$safeRunId.jsonl")
            if (!file.isFile) return emptyList()
            return file.useLines { lines ->
                lines.mapNotNull { line ->
                    runCatching { JSONObject(line) }
                        .getOrNull()
                        ?.takeIf { it.optLong("sequence", -1L) > afterSequence }
                }.toList()
            }
        }

        fun inspectionSummary(
            context: Context,
            metrics: List<AgentRunMetricsRecord>,
            limit: Int = 20,
            store: NexusExecutionStore = NexusExecutionStore.get(context),
            checkpoints: List<AgentRunCheckpointStore.Checkpoint> = AgentRunCheckpointStore.list(context),
            archivedRuns: List<AgentRunArchiveStore.ArchivedRun> = AgentRunArchiveStore.list(context),
        ): JSONObject {
            val requested = limit.coerceIn(1, 200)
            val requiredRuns = minOf(requested, 20)
            val files = File(context.applicationContext.filesDir, "execution-ledger")
                .listFiles { file -> file.isFile && file.extension == "jsonl" }
                .orEmpty().sortedByDescending(File::lastModified).take(requested)
            val reports = files.map { file ->
                val ledger = ExecutionLedgerInspector.inspect(file)
                val metadata = ledgerMetadata(file)
                val legacyRunId = ledger.runId
                val canonicalRunId = metadata?.optString("canonical_run_id")?.takeIf(String::isNotBlank)
                    ?: legacyRunId?.let { NexusRunMapper.canonicalRunId(it).value }
                val runMetrics = metricsFor(metrics, legacyRunId, canonicalRunId)
                val inspected = ExecutionLedgerInspector.inspect(file, runMetrics)
                comparisonRow(
                    ledger = inspected,
                    legacyRunId = legacyRunId,
                    canonicalRunId = canonicalRunId,
                    metrics = runMetrics,
                    store = store,
                    checkpoints = checkpoints,
                    archivedRuns = archivedRuns,
                )
            }
            val sampledLegacyRunIds = reports.mapNotNull { it.optString("runId").takeIf(String::isNotBlank) }.toSet()
            val sampledCanonicalRunIds = reports.mapNotNull {
                it.optString("canonicalRunId").takeIf(String::isNotBlank)
            }.toSet()
            val missing = metrics.take(requested).map { it.runId }.distinct().filter { runId ->
                runId !in sampledLegacyRunIds && NexusRunMapper.canonicalRunId(runId).value !in sampledCanonicalRunIds
            }
            val allComparing = reports.isNotEmpty() && reports.size >= requiredRuns &&
                reports.all { it.optBoolean("complete") } && missing.isEmpty()
            return JSONObject()
                .put("mode", "shadow")
                .put("recoveryAuthority", "legacy-checkpoint")
                .put("requiredRuns", requiredRuns)
                .put("sampledRuns", reports.size)
                .put("structurallyCompleteRuns", reports.count { it.optJSONObject("ledger")?.optBoolean("structurallyComplete") == true })
                .put("metricsMatchedRuns", reports.count { it.optString("metricsComparison") == "match" })
                .put("checkpointVerifiedRuns", reports.count { it.optJSONObject("checkpoint")?.optString("status") == "valid" })
                .put("uiProjectionMatchedRuns", reports.count { it.optJSONObject("uiProjection")?.optString("status") == "match" })
                .put("fullyMatchedRuns", reports.count { it.optBoolean("complete") })
                .put("missingOrUnsampledLedgerRunIds", JSONArray(missing))
                .put("fullComparisonComplete", allComparing)
                .put("checkpointAndUiComparison", if (allComparing) "complete" else "pending")
                .put("runs", JSONArray(reports))
        }

        private fun ledgerMetadata(file: File): JSONObject? = runCatching {
            file.useLines { lines -> lines.firstOrNull()?.let(::JSONObject) }
        }.getOrNull()

        private fun metricsFor(
            metrics: List<AgentRunMetricsRecord>,
            legacyRunId: String?,
            canonicalRunId: String?,
        ): AgentRunMetricsRecord? = metrics.firstOrNull { record ->
            record.recordId == record.runId && (
                record.runId == legacyRunId ||
                    canonicalRunId != null && NexusRunMapper.canonicalRunId(record.runId).value == canonicalRunId
                )
        }

        private fun comparisonRow(
            ledger: ExecutionLedgerInspection,
            legacyRunId: String?,
            canonicalRunId: String?,
            metrics: AgentRunMetricsRecord?,
            store: NexusExecutionStore,
            checkpoints: List<AgentRunCheckpointStore.Checkpoint>,
            archivedRuns: List<AgentRunArchiveStore.ArchivedRun>,
        ): JSONObject {
            val run = canonicalRunId?.let { runCatching { store.find(RunId(it)) }.getOrNull() }
            val canonical = run?.let { NexusExecutionDiagnostics.inspect(store, it, metrics) }
            val uiEvidence = run?.let { ExecutionRecoveryComparison.uiProjection(store, it) }
            val row = canonical?.let { JSONObject(it.toString()) }
                ?: JSONObject().put("issues", JSONArray()).put("complete", false)
            val ledgerJson = ledger.toJson()
            val issues = linkedSetOf<String>()
            ledger.issues.forEach(issues::add)
            row.optJSONArray("issues")?.let { array ->
                for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(issues::add)
            }
            val ledgerStatus = if (ledger.structurallyComplete) "match" else "mismatch"
            val metricsStatus = row.optString("metricsComparison", if (metrics == null) "missing" else "pending")
            val checkpoint = row.optJSONObject("checkpoint")
            val checkpointStatus = checkpoint?.optString("status") ?: "missing"
            val ui = uiEvidence?.toJson() ?: row.optJSONObject("uiProjection")
            val uiStatus = uiEvidence?.status ?: "missing"

            if (run == null) {
                issues += "CANONICAL_RUN_MISSING"
            } else {
                val canonicalStatus = run.state.name.lowercase()
                if (run.state !in NexusExecutionStore.terminalStates) issues += "CANONICAL_RUN_NOT_TERMINAL"
                if (ledger.terminalStatus != null && ledger.terminalStatus != canonicalStatus) {
                    issues += "LEDGER_TERMINAL_MISMATCH"
                }
                if (metrics != null && metrics.status != canonicalStatus) issues += "METRICS_TERMINAL_MISMATCH"
                val canonicalRequests = row.optInt("modelRequests", -1)
                if (canonicalRequests >= 0 && canonicalRequests != ledger.requestCount) {
                    issues += "LEDGER_REQUEST_COUNT_MISMATCH"
                }
                if (metrics != null && metrics.metrics.requestAttempts != ledger.requestCount) {
                    issues += "LEDGER_METRICS_REQUEST_COUNT_MISMATCH"
                }
                val canonicalTools = row.optInt("toolCalls", -1)
                if (canonicalTools >= 0 && canonicalTools != ledger.toolCalls) issues += "LEDGER_TOOL_COUNT_MISMATCH"
                val checkpointTranscripts = checkpoint?.optInt("transcriptMessages", -1) ?: -1
                if (checkpointTranscripts >= 0 && ledger.terminalTranscriptMessages != null &&
                    checkpointTranscripts != ledger.terminalTranscriptMessages
                ) {
                    issues += "TRANSCRIPT_COUNT_MISMATCH"
                }
                if (ui != null && ledger.toolCalls != ui.optInt("toolItems", -1)) {
                    issues += "UI_TOOL_COUNT_MISMATCH"
                }
                issues += compareLedgerToolsToUi(ledger, legacyRunId, uiEvidence)
            }

            val checkpointDigests = checkpoint?.optString("transcriptDigest")?.takeIf(String::isNotBlank)
            val contextDigests = checkpoint?.optString("contextDigest")?.takeIf(String::isNotBlank)
            val checkpointHasContext = checkpoint?.optBoolean("contextPresent", false) == true
            val legacy = checkpoints.firstOrNull { it.runId == legacyRunId }
            val legacyStatus = if (legacy == null) "not_retained" else {
                val transcriptMatches = checkpointDigests == null ||
                    checkpointDigests == ExecutionRecoveryComparison.transcriptDigest(legacy.transcript)
                val contextMatches = if (checkpointHasContext) {
                    legacy.contextSnapshot != null && contextDigests ==
                        ExecutionRecoveryComparison.contextDigest(legacy.contextSnapshot)
                } else legacy.contextSnapshot == null
                if (transcriptMatches && contextMatches) "match" else {
                    if (!transcriptMatches) issues += "LEGACY_TRANSCRIPT_MISMATCH"
                    if (!contextMatches) issues += "LEGACY_CONTEXT_MISMATCH"
                    "mismatch"
                }
            }
            val archived = archivedRuns.firstOrNull { it.result.runId == legacyRunId }
            val archivedStatus = if (archived == null) "not_retained" else {
                val transcriptMatches = checkpointDigests == null ||
                    checkpointDigests == ExecutionRecoveryComparison.transcriptDigest(archived.result.transcript)
                val contextMatches = if (checkpointHasContext) {
                    archived.result.contextSnapshot != null && contextDigests ==
                        ExecutionRecoveryComparison.contextDigest(archived.result.contextSnapshot)
                } else archived.result.contextSnapshot == null
                if (transcriptMatches && contextMatches) "match" else {
                    if (!transcriptMatches) issues += "ARCHIVE_TRANSCRIPT_MISMATCH"
                    if (!contextMatches) issues += "ARCHIVE_CONTEXT_MISMATCH"
                    "mismatch"
                }
            }

            val complete = run != null && ledgerStatus == "match" && metricsStatus == "match" &&
                checkpointStatus == "valid" && uiStatus == "match" && issues.isEmpty()
            val missingSources = JSONArray().apply {
                if (run == null) put("canonicalRun")
                if (!ledger.structurallyComplete) put("ledger")
                if (metricsStatus != "match") put("metrics")
                if (checkpointStatus != "valid") put("checkpoint")
                if (uiStatus != "match") put("uiProjection")
            }
            return row.put("runId", legacyRunId ?: JSONObject.NULL)
                .put("canonicalRunId", canonicalRunId ?: JSONObject.NULL)
                .put("ledger", ledgerJson)
                .put("metricsComparison", metricsStatus)
                .put("checkpoint", checkpoint ?: JSONObject().put("status", checkpointStatus))
                .put("uiProjection", ui ?: JSONObject().put("status", uiStatus))
                .put("legacyCheckpoint", JSONObject().put("status", legacyStatus))
                .put("archiveCheckpoint", JSONObject().put("status", archivedStatus))
                .put("missingSources", missingSources)
                .put("issues", JSONArray(issues.sorted()))
                .put("recoveryAuthority", "legacy-checkpoint")
                .put("complete", complete)
        }

        private fun compareLedgerToolsToUi(
            ledger: ExecutionLedgerInspection,
            legacyRunId: String?,
            ui: ExecutionRecoveryComparison.UiProjectionEvidence?,
        ): Set<String> {
            if (legacyRunId == null || ui == null) return emptySet()
            val issues = linkedSetOf<String>()
            val mapped = linkedMapOf<String, String?>()
            ledger.toolItems.keys.forEach { key ->
                val (turn, item) = key.split('\u0000', limit = 2).let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
                val round = turn.removePrefix("turn-").toIntOrNull()
                if (round == null || item.isBlank()) return@forEach
                val hosted = item.startsWith("hosted-tool-")
                val callId = item.removePrefix("hosted-tool-").removePrefix("tool-")
                if (callId.isBlank()) return@forEach
                mapped[NexusExecutionIds.tool(legacyRunId, round, callId, hosted)] = ledger.toolStates[key]
            }
            if (mapped.isEmpty() && ledger.toolItems.isNotEmpty()) {
                issues += "LEDGER_TOOL_IDENTITY_UNRESOLVED"
                return issues
            }
            val uiTools = ui.items.values.filter { it.kind == io.github.zhh_lgtm.nexus.kernel.api.ItemKind.TOOL_CALL }
                .associateBy { it.itemId.value }
            val uiIds = uiTools.keys
            if (mapped.keys != uiIds) issues += "UI_TOOL_ASSOCIATION_MISMATCH"
            mapped.forEach { (itemId, shadowState) ->
                val expected = when (shadowState) {
                    "succeeded" -> "SUCCEEDED"
                    "failed" -> "FAILED"
                    "unknown" -> "VERIFYING"
                    else -> null
                }
                if (expected != null && uiTools[itemId]?.state?.name != expected) issues += "UI_TOOL_STATE_MISMATCH"
            }
            return issues
        }

        private fun prune(directory: File) {
            if (!directory.isDirectory) return
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
            val files = directory.listFiles { file -> file.isFile && file.extension == "jsonl" }
                .orEmpty()
                .sortedByDescending(File::lastModified)
            files.filter { file ->
                // Incomplete runs may still be needed for checkpoint comparison or recovery.
                runCatching {
                    file.useLines { lines -> lines.lastOrNull()?.let(::JSONObject)?.optString("type") == "run.terminal" }
                }.getOrDefault(false)
            }.forEachIndexed { index, file ->
                if (index >= MAX_RETAINED_RUNS || file.lastModified() < cutoff) {
                    runCatching { file.delete() }
                }
            }
        }

        private const val MAX_RETAINED_RUNS = 200
        private const val RETENTION_DAYS = 30L

        private fun nextSequence(file: File): Long = if (!file.isFile) 0L else file.useLines { lines ->
            lines.mapNotNull { line -> runCatching { JSONObject(line).getLong("sequence") }.getOrNull() }
                .maxOrNull()?.plus(1) ?: 0L
        }

        private fun budgetJson(budget: io.github.zhh_lgtm.nexus.kernel.api.RunBudget): JSONObject = JSONObject()
            .put("soft_rounds", budget.softRounds ?: JSONObject.NULL)
            .put("hard_rounds", budget.hardRounds ?: JSONObject.NULL)
            .put("soft_duration_ms", budget.softDurationMs ?: JSONObject.NULL)
            .put("hard_duration_ms", budget.hardDurationMs ?: JSONObject.NULL)
            .put("soft_tool_calls", budget.softToolCalls ?: JSONObject.NULL)
            .put("hard_tool_calls", budget.hardToolCalls ?: JSONObject.NULL)
            .put("cost_limit_micros", budget.costLimitMicros ?: JSONObject.NULL)
    }
}

private data class LedgerShape(
    val type: String,
    val payload: JSONObject,
    val turnId: String? = null,
    val itemId: String? = null,
    val status: String? = null,
)

private fun AgentEvent.ledgerShape(): LedgerShape = when (this) {
    is AgentEvent.RoundStarted -> LedgerShape(
        "turn.started",
        JSONObject().put("round", round).put("message_count", messageCount),
        turnId = "turn-$round",
        status = "started",
    )
    is AgentEvent.ProviderRequestStarted -> LedgerShape(
        "model.request.started", JSONObject().put("round", round), "turn-$round", status = "started",
    )
    is AgentEvent.ProviderResponseStarted -> LedgerShape(
        "model.response.started", JSONObject().put("round", round).put("http_code", httpCode), "turn-$round", status = "started",
    )
    is AgentEvent.AssistantReceived -> LedgerShape(
        "assistant.item.completed",
        JSONObject().put("round", round).put("content_chars", contentChars)
            .put("reasoning_chars", reasoningContent.length).put("tool_names", JSONArray(toolNames)),
        turnId = "turn-$round",
        itemId = "assistant-$round",
        status = "completed",
    )
    is AgentEvent.ToolStarted -> LedgerShape(
        "tool.item.started",
        JSONObject().put("round", round).put("name", name)
            .put("argument_chars", argsPreview.length).put("command_chars", command?.length ?: 0),
        turnId = "turn-$round",
        itemId = "tool-$toolCallId",
        status = "started",
    )
    is AgentEvent.ToolFinished -> LedgerShape(
        "tool.item.completed",
        JSONObject().put("round", round).put("name", name).put("result_chars", resultSummary.length)
            .put("success", success ?: JSONObject.NULL).put("image_count", imageCount).put("image_bytes", imageBytes),
        turnId = "turn-$round",
        itemId = "tool-$toolCallId",
        status = when (success) { true -> "succeeded"; false -> "failed"; null -> "unknown" },
    )
    is AgentEvent.HostedToolStarted -> LedgerShape(
        "hosted_tool.item.started", JSONObject().put("round", round).put("name", name),
        turnId = "turn-$round", itemId = "hosted-tool-$toolCallId", status = "started",
    )
    is AgentEvent.HostedToolFinished -> LedgerShape(
        "hosted_tool.item.completed", JSONObject().put("round", round).put("name", name).put("success", success),
        turnId = "turn-$round", itemId = "hosted-tool-$toolCallId", status = if (success) "succeeded" else "failed",
    )
    is AgentEvent.ContextCompaction -> LedgerShape(
        "context.compacted",
        JSONObject().put("phase", phase).put("tokens_before", tokensBefore ?: JSONObject.NULL)
            .put("tokens_after", tokensAfter ?: JSONObject.NULL).put("reason_code", reasonCode),
        status = phase,
    )
    is AgentEvent.UserSupplementReceived -> LedgerShape(
        "user.supplement", JSONObject().put("index", index).put("chars", text.length), itemId = "supplement-$index", status = "completed",
    )
    is AgentEvent.SubagentProgress -> LedgerShape(
        "subagent.progress", JSONObject().put("round", round).put("subagent_id", subagentId)
            .put("subagent_type", subagentType).put("snapshot_chars", snapshotJson.length),
        turnId = "turn-$round", itemId = "subagent-$subagentId", status = "running",
    )
    is AgentEvent.RunFinished -> LedgerShape(
        "run.finished", JSONObject().put("round", round).put("content_chars", contentChars), turnId = "turn-$round", status = "succeeded",
    )
    is AgentEvent.RunFailed -> LedgerShape(
        "run.failed", JSONObject().put("reason_chars", reason.length), status = "failed",
    )
    else -> LedgerShape("agent.event", JSONObject().put("event", javaClass.simpleName))
}
