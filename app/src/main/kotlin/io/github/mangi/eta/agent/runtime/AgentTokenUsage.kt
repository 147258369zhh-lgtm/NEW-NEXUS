package io.github.mangi.eta.agent.runtime

internal data class AgentTokenUsage(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    val billingBasis: Int = AgentBillingTokens.UNKNOWN,
    val cacheWriteTokens: Int? = null,
    val toolUseTokens: Int? = null,
    val localRuntime: LocalRuntimeMetrics? = null,
) {
    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null && cacheWriteTokens == null && toolUseTokens == null && localRuntime == null
}
