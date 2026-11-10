package io.github.mangi.eta.agent.tool

/** Chooses the smallest useful observation while retaining a screenshot fallback. */
internal class AgentObservationPlanner(
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    enum class Level { CACHED, STRUCTURED, SCREENSHOT }

    data class Decision(val level: Level, val reason: String)

    private var lastSignature: String? = null
    private var lastAt = 0L
    private var unchangedCount = 0

    fun decide(
        requiresFresh: Boolean,
        screenshotRequested: Boolean = false,
        actionName: String? = null,
    ): Decision {
        if (screenshotRequested) return Decision(Level.SCREENSHOT, "model_requested")
        if (requiresFresh) return Decision(Level.STRUCTURED, "tool_requires_fresh_observation")
        if (lastSignature != null && clockMs() - lastAt <= cacheTtlMs) {
            return Decision(Level.CACHED, "recent_observation")
        }
        return Decision(Level.STRUCTURED, actionName?.let { "after_$it" } ?: "cache_expired")
    }

    fun record(signature: String, changed: Boolean) {
        if (lastSignature == signature && !changed) unchangedCount++ else unchangedCount = 0
        lastSignature = signature
        lastAt = clockMs()
    }

    fun shouldBreakLoop(): Boolean = unchangedCount >= LOOP_THRESHOLD

    fun clear() {
        lastSignature = null
        lastAt = 0L
        unchangedCount = 0
    }

    companion object {
        const val DEFAULT_CACHE_TTL_MS = 1_500L
        const val LOOP_THRESHOLD = 2
    }
}
