package io.github.mangi.eta.data.repository

import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.ReplayPolicy

/**
 * The durable review decision for a tool whose external effect could not be confirmed.
 * Recording a decision never executes the tool again; a rerun is a separate user action.
 */
internal enum class EffectReviewDecision {
    ACKNOWLEDGED,
    RERUN_REQUESTED,
    CANCELLED;

    val reference: String get() = "nexus-effect-review://${name.lowercase()}"

    companion object {
        fun fromReference(value: String?): EffectReviewDecision? =
            value?.substringAfterLast('/')?.let { raw ->
                entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            }
    }
}

internal data class NexusEffectReview(
    val effectClass: EffectClass,
    val replayPolicy: ReplayPolicy,
    val decision: EffectReviewDecision?,
    val resolvedAt: Long?,
) {
    val pending: Boolean get() = decision == null
}
