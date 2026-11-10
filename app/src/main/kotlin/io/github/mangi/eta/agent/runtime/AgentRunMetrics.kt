package io.github.mangi.eta.agent.runtime

/** Reported usage only. Null is unknown; zero is a reported zero. */
internal data class AgentRunMetrics(
    val rounds: Int = 0,
    val toolCalls: Int = 0,
    val succeededTools: Int = 0,
    val failedTools: Int = 0,
    val unresolvedTools: Int = 0,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val cachedTokens: Long? = null,
    val contextTokens: Int? = null,
    val inputReportedRounds: Int = 0,
    val outputReportedRounds: Int = 0,
    val imageBytes: Long = 0,
    val requestAttempts: Int = 0,
    val retries: Int = 0,
    val completedCompactions: Int = 0,
    val uncachedInputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val billableOutputTokens: Long? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val billingReportedRounds: Int = 0,
) {
    @get:androidx.room.Ignore
    val usageComplete: Boolean get() = rounds > 0 && inputReportedRounds == rounds && outputReportedRounds == rounds
    fun reportedTotalTokens(): Long? {
        val values = if (uncachedInputTokens != null && cacheReadTokens != null) {
            listOfNotNull(uncachedInputTokens, cacheReadTokens, cacheWriteTokens, billableOutputTokens ?: outputTokens)
        } else listOfNotNull(inputTokens, outputTokens)
        return values.takeIf { it.isNotEmpty() }?.sum()
    }
    fun successRate(): Float? = (succeededTools + failedTools).takeIf { it > 0 }
        ?.let { succeededTools.toFloat() / it }
}

/** A final/cumulative usage event replaces that round's counters instead of double counting it. */
internal class AgentRunMetricsCollector {
    private val rounds = linkedSetOf<Int>()
    private val usage = linkedMapOf<Int, AgentTokenUsage>()
    private val models = mutableMapOf<Int, Int>()
    private data class Tool(val round: Int, val success: Boolean?)
    private val tools = linkedMapOf<String, Tool>()
    private val imageBytes = mutableMapOf<Int, Long>()
    private val requests = mutableMapOf<Int, Int>()
    private val retries = mutableMapOf<Int, Int>()
    private val compactions = mutableMapOf<Int, MutableSet<String>>()
    private val initialImages = mutableMapOf<Int, Long>()
    private var currentModel = 0

    fun modelAttempt(index: Int) { currentModel = index }

    private fun ensureRound(value: Int) {
        rounds += value
        models.putIfAbsent(value, currentModel)
    }

    private fun tool(round: Int, id: String, hosted: Boolean, outcome: Boolean? = null) {
        ensureRound(round)
        val key = "${if (hosted) "hosted" else "local"}:$round:$id"
        tools[key] = Tool(round, outcome ?: tools[key]?.success)
    }

    fun accept(event: AgentEvent) {
        when (event) {
            is AgentEvent.RunStarted -> initialImages.putIfAbsent(currentModel, event.initialImageBytes.coerceAtLeast(0).toLong())
            is AgentEvent.RoundStarted -> ensureRound(event.round)
            is AgentEvent.ProviderRequestStarted -> {
                ensureRound(event.round)
                requests[event.round] = (requests[event.round] ?: 0) + 1
            }
            is AgentEvent.ModelRetryScheduled -> retries[currentModel] = (retries[currentModel] ?: 0) + 1
            is AgentEvent.ContextCompaction -> if (event.phase == AgentEvent.ContextCompaction.PHASE_COMPLETED) {
                compactions.getOrPut(currentModel) { mutableSetOf() } += event.operationId
            }
            is AgentEvent.UsageReceived -> {
                ensureRound(event.round)
                val prior = usage[event.round] ?: AgentTokenUsage()
                fun valid(value: Int?, old: Int?) = value?.takeIf { it >= 0 } ?: old
                usage[event.round] = AgentTokenUsage(
                    valid(event.usage.contextTokens, prior.contextTokens),
                    valid(event.usage.inputTokens, prior.inputTokens),
                    valid(event.usage.outputTokens, prior.outputTokens),
                    valid(event.usage.reasoningTokens, prior.reasoningTokens),
                    valid(event.usage.cachedTokens, prior.cachedTokens),
                    if (listOf(event.usage.inputTokens, event.usage.outputTokens, event.usage.reasoningTokens,
                            event.usage.cachedTokens, event.usage.cacheWriteTokens, event.usage.toolUseTokens).any { it != null && it < 0 }) {
                        AgentBillingTokens.INVALID
                    } else if (prior.billingBasis == AgentBillingTokens.INVALID) prior.billingBasis
                    else event.usage.billingBasis.takeIf { it != AgentBillingTokens.UNKNOWN } ?: prior.billingBasis,
                    valid(event.usage.cacheWriteTokens, prior.cacheWriteTokens),
                    valid(event.usage.toolUseTokens, prior.toolUseTokens),
                )
            }
            is AgentEvent.ToolStarted -> tool(event.round, event.toolCallId, false)
            is AgentEvent.HostedToolStarted -> tool(event.round, event.toolCallId, true)
            is AgentEvent.ToolFinished -> tool(event.round, event.toolCallId, false, event.success)
            is AgentEvent.HostedToolFinished -> tool(event.round, event.toolCallId, true, event.success)
            is AgentEvent.ToolImagesAttached -> {
                ensureRound(event.round)
                imageBytes[event.round] = (imageBytes[event.round] ?: 0L) + event.imageBytes.coerceAtLeast(0)
            }
            else -> Unit
        }
    }

    fun snapshot(modelIndex: Int? = null): AgentRunMetrics {
        val selectedRounds = rounds.filter { modelIndex == null || models[it] == modelIndex }
        val selectedUsage = selectedRounds.mapNotNull(usage::get)
        val billing = selectedUsage.map(AgentBillingTokens::from)
        fun billingSum(field: (AgentBillingTokens) -> Long?): Long? = billing.mapNotNull(field)
            .takeIf { it.isNotEmpty() }?.sum()
        fun sum(field: (AgentTokenUsage) -> Int?): Long? = selectedUsage.mapNotNull(field)
            .takeIf { it.isNotEmpty() }?.sumOf(Int::toLong)
        val outcomes = tools.values.filter { it.round in selectedRounds }.map { it.success }
        return AgentRunMetrics(
            rounds = selectedRounds.size,
            toolCalls = outcomes.size,
            succeededTools = outcomes.count { it == true },
            failedTools = outcomes.count { it == false },
            unresolvedTools = outcomes.count { it == null },
            inputTokens = sum { it.inputTokens }, outputTokens = sum { it.outputTokens },
            reasoningTokens = sum { it.reasoningTokens }, cachedTokens = sum { it.cachedTokens },
            contextTokens = selectedUsage.lastOrNull { it.contextTokens != null }?.contextTokens,
            inputReportedRounds = selectedUsage.count { it.inputTokens != null },
            outputReportedRounds = selectedUsage.count { it.outputTokens != null },
            imageBytes = selectedRounds.sumOf { imageBytes[it] ?: 0L } +
                initialImages.filterKeys { modelIndex == null || it == modelIndex }.values.sum(),
            requestAttempts = selectedRounds.sumOf { requests[it] ?: 0 },
            retries = retries.filterKeys { modelIndex == null || it == modelIndex }.values.sum(),
            completedCompactions = compactions.filterKeys { modelIndex == null || it == modelIndex }.values.sumOf { it.size },
            uncachedInputTokens = billingSum { it.uncachedInput }, cacheReadTokens = billingSum { it.cacheRead },
            cacheWriteTokens = billingSum { it.cacheWrite }, billableOutputTokens = billingSum { it.output },
            billingReportedRounds = billing.count { it.complete },
        )
    }
}
