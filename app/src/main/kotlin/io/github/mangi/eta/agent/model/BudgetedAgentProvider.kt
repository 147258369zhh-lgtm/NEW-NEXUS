package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentBillingTokens
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage

/** Reserves before transport entry and before each compatibility retry's actual request event. */
internal class BudgetedAgentProvider private constructor(private val delegate: AgentProviderClient) : AgentProviderClient {
    override val id get() = delegate.id
    override val capabilities get() = delegate.capabilities
    override fun estimateInputTokens(request: ProviderRequest, runController: AgentRunController) = delegate.estimateInputTokens(request, runController)

    override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
        val local = delegate.id in setOf("litert_lm_builtin", "llama_cpp_gguf_builtin", "mnn_builtin")
        return if (local) io.github.mangi.eta.agent.runtime.LocalInferenceScheduling.execute(runController, request.effectiveConfig.model) {
            kotlinx.coroutines.runBlocking { LocalModelResourceOwner.prepareText(delegate.id) }
            completeAccounted(request, runController, onEvent, local)
        } else completeAccounted(request, runController, onEvent, local)
    }

    private fun completeAccounted(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit,
        local: Boolean): ProviderResponse {
        val budget = runController.modelBudget ?: return delegate.complete(request, runController, onEvent)
        var ticket = budget.reserve(request, local)
        var usage: AgentTokenUsage? = null
        var started = false
        var successful = false
        var ticketOpen = true
        try {
            val ceiling = listOfNotNull(request.budgetOutputTokenCeiling, ticket.outputTokenCeiling).minOrNull()
            val response = delegate.complete(request.copy(budgetOutputTokenCeiling = ceiling), runController) { event ->
                if (event == ProviderEvent.RequestStarted) {
                    if (started) {
                        budget.settle(ticket, usage, false)
                        ticketOpen = false
                        ticket = budget.reserve(request, local)
                        ticketOpen = true
                        usage = null
                    }
                    started = true
                }
                if (event is ProviderEvent.Usage) usage = merge(usage, event.usage)
                onEvent(event)
            }
            successful = true
            return response
        } finally {
            if (ticketOpen) budget.settle(ticket, if (started) usage else AgentTokenUsage(inputTokens = 0, outputTokens = 0,
                billingBasis = AgentBillingTokens.OPENAI), successful)
        }
    }

    private fun merge(previous: AgentTokenUsage?, next: AgentTokenUsage): AgentTokenUsage = if (previous == null) next else AgentTokenUsage(
        contextTokens = next.contextTokens ?: previous.contextTokens,
        inputTokens = next.inputTokens ?: previous.inputTokens, outputTokens = next.outputTokens ?: previous.outputTokens,
        reasoningTokens = next.reasoningTokens ?: previous.reasoningTokens, cachedTokens = next.cachedTokens ?: previous.cachedTokens,
        billingBasis = next.billingBasis.takeUnless { it == AgentBillingTokens.UNKNOWN } ?: previous.billingBasis,
        cacheWriteTokens = next.cacheWriteTokens ?: previous.cacheWriteTokens,
        toolUseTokens = next.toolUseTokens ?: previous.toolUseTokens, localRuntime = next.localRuntime ?: previous.localRuntime,
    )

    companion object {
        fun wrap(provider: AgentProviderClient): AgentProviderClient = if (provider is BudgetedAgentProvider) provider else BudgetedAgentProvider(provider)
    }
}
