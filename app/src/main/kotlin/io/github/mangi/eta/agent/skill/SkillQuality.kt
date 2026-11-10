package io.github.mangi.eta.agent.skill

import java.util.concurrent.TimeUnit

enum class SkillQualityIssue {
    NONE,
    STALE,
    HIGH_FAILURE_RATE,
}

data class SkillQualityReport(
    val issue: SkillQualityIssue,
    val useCount: Int,
    val failureCount: Int,
    val failureRate: Float,
    val daysSinceUse: Long?,
)

/** Local-only health heuristic. It recommends review and never disables a Skill by itself. */
internal object SkillQualityPolicy {
    fun evaluate(
        entry: SkillIndexEntry,
        usage: SkillUsageRecord?,
        now: Long = System.currentTimeMillis(),
    ): SkillQualityReport {
        val useCount = usage?.useCount ?: 0
        val failureCount = usage?.failureCount ?: 0
        val failureRate = if (useCount > 0) failureCount.toFloat() / useCount else 0f
        val activityAt = usage?.lastUsedAt?.takeIf { it > 0L }
            ?: entry.provenance?.installedAt?.takeIf { it > 0L }
        val days = activityAt?.let { timestamp ->
            TimeUnit.MILLISECONDS.toDays((now.coerceAtLeast(timestamp) - timestamp).coerceAtLeast(0L))
        }
        val issue = when {
            !entry.enabled -> SkillQualityIssue.NONE
            useCount >= MIN_FAILURE_SAMPLE && failureRate >= HIGH_FAILURE_RATE ->
                SkillQualityIssue.HIGH_FAILURE_RATE
            useCount == 0 && days != null && days >= STALE_DAYS -> SkillQualityIssue.STALE
            useCount > 0 && days != null && days >= STALE_DAYS -> SkillQualityIssue.STALE
            else -> SkillQualityIssue.NONE
        }
        return SkillQualityReport(issue, useCount, failureCount, failureRate, days)
    }

    const val STALE_DAYS = 90L
    const val MIN_FAILURE_SAMPLE = 5
    const val HIGH_FAILURE_RATE = 0.6f
}
