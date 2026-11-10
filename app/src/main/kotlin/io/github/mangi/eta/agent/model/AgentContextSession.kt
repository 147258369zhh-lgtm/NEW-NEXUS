package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.prompt.PROMPT_INJECTION_MARKER
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray

/** 管理可替换的模型上下文；持久快照先提交，运行 transcript 始终追加。 */
internal class AgentContextSession(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val systemCount: Int,
    private val operationId: String,
    private val provider: AgentProviderClient,
    private val runController: AgentRunController,
    private val sensitiveIds: () -> Set<String>,
    private val onEvent: (AgentEvent) -> Unit,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit,
    private val transcriptSize: () -> Int = { 0 },
    private val roleplay: Boolean = false,
    private val metricsScope: io.github.mangi.eta.agent.runtime.AgentMetricScope? = null,
) {
    private val budget = ContextBudgetPolicy.forCompaction(config.knownContextWindow)
    private var inputTokens: Int? = null
    private var compacted = false
    private var lastDemotedToolResults = 0
    private var consumedSupplementCount = 0
    private var consumedUserTurns = (systemCount until messages.length()).sumOf {
        val message = messages.getJSONObject(it)
        message.optInt("_eta_compacted_users") +
            if (message.optString("role") == "user" && !message.optBoolean(PROMPT_INJECTION_MARKER)) 1 else 0
    }
    private var committedSnapshot: AgentContextSnapshot? = null

    fun snapshot(): AgentContextSnapshot? = committedSnapshot

    fun observeInputTokens(tokens: Int?) {
        inputTokens = tokens?.takeIf { it >= 0 }
    }

    fun userAppended() {
        consumedUserTurns++
        consumedSupplementCount++
    }

    private fun publishSnapshot(candidate: JSONArray = messages) {
        if (!compacted) return
        val snapshot = createSnapshot(candidate)
        snapshot.encode()
        onContextSnapshot(snapshot)
        committedSnapshot = snapshot
    }

    private fun createSnapshot(candidate: JSONArray): AgentContextSnapshot {
        val history = durableHistory(candidate)
        return AgentContextSnapshot(
            operationId = operationId,
            messages = history,
            coveredUserTurns = history.sumOf { it.compactedUserTurns },
            consumedUserTurns = consumedUserTurns,
            consumedSupplementCount = consumedSupplementCount,
            consumedTranscriptMessages = transcriptSize(),
            estimatedInputTokens = ContextBudgetPolicy.estimateMessages(candidate, systemCount),
            usableInputBudgetTokens = budget.usableInputTokens,
            outputReserveTokens = budget.outputReserveTokens,
            demotedToolResults = lastDemotedToolResults,
        )
    }

    fun compact(force: Boolean = false, final: Boolean = false, selection: IntRange? = null) {
        val before = inputTokens
        if (SignedToolContext.hasPendingResponse(messages)) {
            if (force) {
                throw SignedToolContext.compactionFailure(messages)
            }
            return
        }
        val estimatedBefore = ContextBudgetPolicy.estimateMessages(messages, systemCount)
        val shouldCompact = force || (
            config.autoCompactionEnabled &&
                !(compacted && before == null) &&
                ContextBudgetPolicy.shouldCompact(budget, before, estimatedBefore)
            )
        // Embedded runtimes enforce a deterministic suffix fit on every request. Re-running the
        // same small model to summarize its window adds a slow prefill + generation round and can
        // itself overflow on 4K devices.
        if (shouldCompact && provider.id in EMBEDDED_LOCAL_PROVIDERS) return
        if (!shouldCompact) {
            try {
                publishSnapshot()
            } catch (failure: Exception) {
                runController.throwIfCancelled()
                if (!final) throw failure
                committedSnapshot = createSnapshot(messages)
                onEvent(AgentEvent.ContextCompaction(operationId, "failed", before,
                    reasonCode = "CONTEXT_CHECKPOINT_FAILED"))
            }
            return
        }
        val operation = java.util.UUID.randomUUID().toString()
        val reportedBefore = before ?: estimatedBefore
        onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_STARTED, reportedBefore))
        try {
            val compactor = AgentContextCompactor(config, provider, runController, roleplay = roleplay, metricsScope = metricsScope)
            // UI selections are relative to persisted conversation history; provider system
            // messages are prepended here and must never be user-selectable.
            val absoluteSelection = selection?.let { (it.first + systemCount)..(it.last + systemCount) }
            val candidate = compactor.compact(messages, systemCount, sensitiveIds(), absoluteSelection)
            lastDemotedToolResults = compactor.lastDemotedToolResults
            runController.throwIfCancelled()
            val wasCompacted = compacted
            compacted = true
            try {
                publishSnapshot(candidate)
            } catch (failure: Exception) {
                compacted = wasCompacted
                throw failure
            }
            while (messages.length() > 0) messages.remove(messages.length() - 1)
            for (index in 0 until candidate.length()) messages.put(candidate.getJSONObject(index))
            inputTokens = null
            onEvent(AgentEvent.ContextCompaction(
                operation,
                AgentEvent.ContextCompaction.PHASE_COMPLETED,
                reportedBefore,
            ))
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            onEvent(AgentEvent.ContextCompaction(operation, "failed", reportedBefore,
                reasonCode = (failure as? AgentModelFailure)?.code ?: "CONTEXT_SUMMARY_FAILED"))
            if (!final) throw failure
            // 已完成的回答仍成功交付；完整快照随终态 outbox 保存，不依赖先前检查点写入成功。
            committedSnapshot = createSnapshot(messages)
        }
    }

    private fun durableHistory(source: JSONArray): List<AgentModelClient.ConversationMessage> {
        val durable = JSONArray()
        for (index in systemCount until source.length()) {
            val message = source.getJSONObject(index)
            if (!message.optBoolean("_eta_observation") && !message.optBoolean(PROMPT_INJECTION_MARKER)) {
                durable.put(message)
            }
        }
        return AgentConversationCodec.transcript(durable, 0, sensitiveIds())
    }

    private companion object {
        val EMBEDDED_LOCAL_PROVIDERS = setOf(
            LiteRtLmAgentProvider.id,
            LlamaCppAgentProvider.id,
            MnnAgentProvider.id,
        )
    }

}
