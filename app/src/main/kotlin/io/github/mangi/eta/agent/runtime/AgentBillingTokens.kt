package io.github.mangi.eta.agent.runtime

/** Protocol-specific token accounting. Raw provider counters remain available for reconciliation. */
internal data class AgentBillingTokens(
    val uncachedInput: Long?, val cacheRead: Long?, val cacheWrite: Long?, val output: Long?,
) {
    val complete: Boolean get() = listOf(uncachedInput, cacheRead, cacheWrite, output).all { it != null }

    companion object {
        const val UNKNOWN = 0
        const val INVALID = -1
        const val OPENAI = 1
        const val ANTHROPIC = 2
        const val GEMINI_INTERACTIONS = 3

        fun from(usage: AgentTokenUsage): AgentBillingTokens {
            if (listOf(usage.inputTokens, usage.outputTokens, usage.reasoningTokens, usage.cachedTokens,
                    usage.cacheWriteTokens, usage.toolUseTokens).any { it != null && it < 0 }) {
                return AgentBillingTokens(null, null, null, null)
            }
            if (usage.billingBasis !in OPENAI..GEMINI_INTERACTIONS) return AgentBillingTokens(null, null, null, null)
            val input = usage.inputTokens?.takeIf { it >= 0 }?.toLong()
            val cached = if (input != null) usage.cachedTokens?.takeIf { it >= 0 }?.toLong() ?: 0L else null
            if (usage.billingBasis != ANTHROPIC && input != null && cached != null && cached > input) {
                return AgentBillingTokens(null, null, null, null)
            }
            val written = if (input == null) null else if (usage.billingBasis == ANTHROPIC) {
                usage.cacheWriteTokens?.takeIf { it >= 0 }?.toLong() ?: 0L
            } else 0L
            val uncached = when {
                input == null -> null
                usage.billingBasis == ANTHROPIC -> input
                usage.billingBasis == GEMINI_INTERACTIONS && (usage.toolUseTokens ?: 0) > 0 -> null
                cached == null || cached > input -> null
                else -> input - cached
            }
            val output = usage.outputTokens?.takeIf { it >= 0 }?.toLong()?.let {
                // Interactions separates thought tokens; OpenAI/Anthropic output already includes reasoning.
                it + if (usage.billingBasis == GEMINI_INTERACTIONS) usage.reasoningTokens?.takeIf { n -> n >= 0 }?.toLong() ?: 0L else 0L
            }
            return AgentBillingTokens(uncached, cached, written, output)
        }
    }
}
