package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient

internal object AgentMetricStatus {
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val INTERRUPTED = "interrupted"
    const val LEGACY = "legacy"
}

internal data class AgentTraceStep(
    val sequence: Long, val elapsedMs: Long, val kind: String, val round: Int? = null,
    val label: String = "", val success: Boolean? = null,
)

internal data class AgentMetricModel(
    val index: Int, val providerId: String, val modelId: String, val name: String, val metrics: AgentRunMetrics,
    val price: AgentTokenPrice? = null, val priceCaptured: Boolean = false,
)

internal data class AgentRunTelemetrySnapshot(
    val runId: String, val source: String, val operation: String, val conversationId: String,
    val roomId: String, val taskId: String, val startedAt: Long, val updatedAt: Long,
    val completedAt: Long?, val durationMs: Long, val status: String, val revision: Long,
    val metrics: AgentRunMetrics, val models: List<AgentMetricModel>, val trace: List<AgentTraceStep>,
    val omittedTraceSteps: Long,
    val recordId: String = runId,
    val children: List<AgentMetricChild> = emptyList(),
    val requests: List<AgentMetricRequest> = emptyList(),
)

/** Captures facts once in Runtime; UI replay never enters this recorder. No text/arguments/results are kept. */
internal class AgentRunTelemetry(
    request: AgentRuntimeWire.RunRequest,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onSnapshot: (AgentRunTelemetrySnapshot) -> Unit = {},
    private val recordId: String = request.runId,
    private val priceResolver: (String, String) -> AgentTokenPrice? = { _, _ -> null },
) {
    private data class Model(val modelConfig: AgentModelClient.ModelConfig, val providerId: String, val id: String, val name: String,
        var price: AgentTokenPrice? = null, var priceCaptured: Boolean = false)
    private val runId = request.runId
    private val source = identifier(request.handoff?.source.orEmpty()).ifBlank { "runtime" }
    private val operation = identifier(request.operation)
    private val conversationId = request.handoff?.let { handoff ->
        if (handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE || AgentExternalArchivePayload.from(handoff.payload) != null) {
            identifier(AgentConversationIdentity.fromHandoff(handoff, runId))
        } else ""
    }.orEmpty()
    private val roomId = if (source == AgentRuntimeWire.MEETING_HANDOFF_SOURCE) identifier(request.handoff?.payload.orEmpty()) else ""
    private val taskId = if (source == "scheduled_task") identifier(request.handoff?.payload.orEmpty()) else ""
    private val lock = Any()
    private val startedAt = wallClock()
    private val startedMonotonic = monotonicClock()
    private val metrics = AgentRunMetricsCollector()
    private val modelConfigs = mutableListOf(descriptor(request.config))
    private val requestLedger = AgentRequestLedger("", wallClock, ::elapsed)
    private val seen = mutableSetOf<String>()
    private val trace = ArrayDeque<AgentTraceStep>()
    private var sequence = 0L
    private var revision = 0L
    private var lastFlush = startedMonotonic
    private var status = AgentMetricStatus.RUNNING
    private var completedAt: Long? = null
    private var finishedElapsed: Long? = null
    private var omitted = 0L
    private data class Child(
        val id: String, val parent: String, val index: Int, val kind: String, val parentRound: Int?, val toolCallId: String,
        val startedAt: Long, val startElapsed: Long, val model: Model,
        val requests: AgentRequestLedger,
        val metrics: AgentRunMetricsCollector = AgentRunMetricsCollector(),
        var status: String = AgentMetricStatus.RUNNING, var completedAt: Long? = null,
        var durationMs: Long = 0, var revision: Long = 1,
    )
    private val children = linkedMapOf<String, Child>()
    val scope: AgentMetricScope = createScope("")

    init {
        addStep("model", label = modelConfigs.first().id)
        publish()
    }

    fun modelAttempt(config: AgentModelClient.ModelConfig) = synchronized(lock) {
        if (status != AgentMetricStatus.RUNNING) return@synchronized
        val last = modelConfigs.last()
        val next = descriptor(config)
        if (last.providerId == next.providerId && last.id == next.id) {
            capturePrice(last)
            return@synchronized
        }
        if (modelConfigs.size >= 8) return@synchronized
        modelConfigs += next
        capturePrice(next)
        metrics.modelAttempt(modelConfigs.lastIndex)
        addStep("model", label = next.id)
        publish()
    }

    private val requestAttempts = mutableMapOf<Int, Int>()

    fun accept(event: AgentEvent) = synchronized(lock) {
        if (status != AgentMetricStatus.RUNNING) return@synchronized
        val usageBefore = if (event is AgentEvent.UsageReceived) metrics.snapshot() else null
        if (event is AgentEvent.RoundStarted || event is AgentEvent.ProviderRequestStarted) capturePrice(modelConfigs.last())
        val model = modelConfigs.last()
        val requestChanged = requestLedger.accept(event, model.modelConfig, model.price, model.priceCaptured)
        metrics.accept(event)
        val before = sequence
        fun once(key: String, kind: String, round: Int? = null, label: String = "", success: Boolean? = null) {
            if (seen.add(key)) addStep(kind, round, label, success)
        }
        when (event) {
            is AgentEvent.RunStarted -> once("start:${modelConfigs.lastIndex}", "start")
            is AgentEvent.RoundStarted -> once("round:${event.round}", "round", event.round)
            is AgentEvent.ProviderRequestStarted -> {
                requestAttempts[event.round] = (requestAttempts[event.round] ?: 0) + 1
                addStep("request", event.round)
            }
            is AgentEvent.ProviderResponseStarted -> addStep("response", event.round, event.httpCode.toString())
            is AgentEvent.AssistantBlockDelta -> if (requestAttempts[event.round] != null && event.kind == AgentEvent.AssistantBlockKind.TEXT && event.deltaChars > 0) {
                once("first_text:${event.round}:${requestAttempts[event.round]}", "first_text", event.round)
            }
            is AgentEvent.AssistantReceived -> if (requestAttempts[event.round] != null) once("assistant:${event.round}:${requestAttempts[event.round]}", "assistant", event.round)
            is AgentEvent.ToolStarted -> once("tool_start:${event.round}:${event.toolCallId}", "tool_start", event.round, toolName(event.name))
            is AgentEvent.ToolFinished -> once("tool_finish:${event.round}:${event.toolCallId}:${event.success}", "tool_finish", event.round, toolName(event.name), event.success)
            is AgentEvent.HostedToolStarted -> once("hosted_start:${event.round}:${event.toolCallId}", "hosted_start", event.round, toolName(event.name))
            is AgentEvent.HostedToolFinished -> once("hosted_finish:${event.round}:${event.toolCallId}:${event.success}", "hosted_finish", event.round, toolName(event.name), event.success)
            is AgentEvent.ModelRetryScheduled -> addStep("retry", event.round,
                event.reasonCode.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) }.orEmpty())
            is AgentEvent.ContextCompaction -> once("compact:${event.operationId}:${event.phase}", "compact",
                label = event.phase.takeIf { it in setOf("started", "completed", "failed") }.orEmpty())
            is AgentEvent.AccessibilityWaiting -> once("accessibility:${event.outage}:${event.phase.wireValue}", "accessibility",
                label = event.phase.wireValue)
            is AgentEvent.GuiControlChanged -> addStep("gui", label = event.status.wireValue)
            is AgentEvent.UserSupplementReceived -> once("supplement:${event.index}", "supplement")
            is AgentEvent.SubagentProgress -> once("subagent:${event.round}:${event.toolCallId}:${event.subagentId}", "subagent", event.round, toolName(event.subagentType))
            is AgentEvent.UsageReceived -> if (usageBefore != metrics.snapshot()) addStep("usage", event.round)
            else -> Unit
        }
        if ((before != sequence || requestChanged || event is AgentEvent.ToolImagesAttached) &&
            monotonicClock() - lastFlush >= CHECKPOINT_INTERVAL_MS) publish()
    }

    fun finish(ok: Boolean, cancelled: Boolean = false): AgentRunTelemetrySnapshot = synchronized(lock) {
        if (status == AgentMetricStatus.RUNNING) {
            requestLedger.finish(ok, cancelled)
            children.values.filter { it.status == AgentMetricStatus.RUNNING }.forEach { child ->
                closeChild(child, if (cancelled) AgentMetricStatus.CANCELLED else AgentMetricStatus.INTERRUPTED)
            }
            finishedElapsed = elapsed()
            status = when { cancelled -> AgentMetricStatus.CANCELLED; ok -> AgentMetricStatus.SUCCEEDED; else -> AgentMetricStatus.FAILED }
            completedAt = wallClock().coerceAtLeast(startedAt)
            addStep(status)
            publish()
        }
        snapshotLocked()
    }

    fun snapshot(): AgentRunTelemetrySnapshot = synchronized(lock) { snapshotLocked() }

    private fun addStep(kind: String, round: Int? = null, label: String = "", success: Boolean? = null) {
        if (trace.size == MAX_TRACE_STEPS) { trace.removeFirst(); omitted++ }
        trace += AgentTraceStep(sequence++, elapsed(), kind, round, label, success)
    }

    private fun elapsed() = finishedElapsed ?: (monotonicClock() - startedMonotonic).coerceAtLeast(0L)

    private fun publish() {
        revision++
        lastFlush = monotonicClock()
        try { onSnapshot(snapshotLocked()) } catch (_: Exception) { /* Telemetry cannot stop execution. */ }
    }

    private fun snapshotLocked(): AgentRunTelemetrySnapshot {
        return AgentRunTelemetrySnapshot(
            runId, source, operation, conversationId, roomId, taskId,
            startedAt, completedAt ?: wallClock().coerceAtLeast(startedAt), completedAt, elapsed(), status, revision,
            metrics.snapshot(), modelConfigs.mapIndexed { index, config ->
                AgentMetricModel(index, config.providerId, config.id, config.name, metrics.snapshot(index), config.price, config.priceCaptured)
            }, trace.toList(), omitted, recordId, children.values.map { child ->
                AgentMetricChild(child.id, child.parent, child.index, child.kind, child.parentRound, child.toolCallId,
                    child.startedAt, child.completedAt,
                    if (child.status == AgentMetricStatus.RUNNING) (elapsed() - child.startElapsed).coerceAtLeast(0) else child.durationMs,
                    child.status, child.revision, child.model.providerId, child.model.id, child.model.name,
                    child.metrics.snapshot(), child.model.price, child.model.priceCaptured)
            }, requestLedger.snapshot() + children.values.flatMap { it.requests.snapshot() },
        )
    }

    private fun capturePrice(model: Model) {
        if (model.priceCaptured) return
        model.priceCaptured = true
        model.price = try { priceResolver(model.providerId, model.id)?.copy(capturedAt = wallClock().coerceAtLeast(0)) }
            catch (_: Exception) { null }
    }

    private fun createScope(id: String): AgentMetricScope = AgentMetricScope(
        onEvent = { event ->
            if (id.isEmpty()) accept(event) else if (isMetricEvent(event)) synchronized(lock) {
                val child = children[id]
                if (status == AgentMetricStatus.RUNNING && child?.status == AgentMetricStatus.RUNNING) {
                    val before = child.metrics.snapshot()
                    val requestChanged = child.requests.accept(event, child.model.modelConfig, child.model.price, child.model.priceCaptured)
                    child.metrics.accept(event)
                    if (before != child.metrics.snapshot() || requestChanged) {
                        child.revision++
                        if (monotonicClock() - lastFlush >= CHECKPOINT_INTERVAL_MS) publish()
                    }
                }
            }
        },
        onFinish = { ok, cancelled -> synchronized(lock) {
            val child = children[id]
            if (status == AgentMetricStatus.RUNNING && child?.status == AgentMetricStatus.RUNNING) {
                closeChild(child, when { cancelled -> AgentMetricStatus.CANCELLED; ok -> AgentMetricStatus.SUCCEEDED; else -> AgentMetricStatus.FAILED })
                publish()
            }
        } },
        onChild = { kind, config, parentRound, toolCallId -> synchronized(lock) {
            if (status != AgentMetricStatus.RUNNING || id.isNotEmpty() && children[id]?.status != AgentMetricStatus.RUNNING) null
            else {
                require(kind in setOf("subagent", "compaction"))
                val model = descriptor(config)
                capturePrice(model)
                val childId = java.util.UUID.randomUUID().toString()
                children[childId] = Child(childId, id, children.size, kind, parentRound,
                    identifier(toolCallId), wallClock().coerceAtLeast(startedAt), elapsed(), model,
                    AgentRequestLedger(childId, wallClock, ::elapsed))
                addStep("child_start", parentRound, childId)
                publish()
                createScope(childId)
            }
        } },
    )

    private fun isMetricEvent(event: AgentEvent) = when (event) {
        is AgentEvent.RunStarted, is AgentEvent.RoundStarted, is AgentEvent.UsageReceived,
        is AgentEvent.ProviderRequestStarted, is AgentEvent.ProviderResponseStarted, is AgentEvent.AssistantBlockDelta,
        is AgentEvent.AssistantReceived, is AgentEvent.ModelRetryScheduled, is AgentEvent.RunFailed,
        is AgentEvent.ContextCompaction, is AgentEvent.AccessibilityWaiting,
        is AgentEvent.ToolStarted, is AgentEvent.ToolFinished, is AgentEvent.HostedToolStarted,
        is AgentEvent.HostedToolFinished, is AgentEvent.ToolImagesAttached -> true
        else -> false
    }

    private fun closeChild(child: Child, terminal: String) {
        child.requests.finish(
            ok = terminal == AgentMetricStatus.SUCCEEDED,
            cancelled = terminal == AgentMetricStatus.CANCELLED,
            interrupted = terminal == AgentMetricStatus.INTERRUPTED,
        )
        child.durationMs = (elapsed() - child.startElapsed).coerceAtLeast(0)
        child.completedAt = wallClock().coerceAtLeast(child.startedAt)
        child.status = terminal
        child.revision++
        addStep("child_finish", child.parentRound, child.id, when (terminal) {
            AgentMetricStatus.SUCCEEDED -> true
            AgentMetricStatus.FAILED -> false
            else -> null
        })
    }

    companion object {
        const val MAX_TRACE_STEPS = 512
        const val CHECKPOINT_INTERVAL_MS = 1000L
        private val ID = Regex("[A-Za-z0-9_.:-]{1,256}")
        private val TOOL_NAME = Regex("[A-Za-z0-9_./:-]{1,128}")
        private fun identifier(value: String) = value.takeIf { it.matches(ID) }.orEmpty()
        private fun toolName(value: String) = value.takeIf { it.matches(TOOL_NAME) }.orEmpty()
        private fun descriptor(config: AgentModelClient.ModelConfig) = Model(
            config, config.providerId.take(128), config.model.take(128),
            config.modelDisplayName.ifBlank { config.model }.filterNot(Char::isISOControl).take(128),
        )
    }
}
