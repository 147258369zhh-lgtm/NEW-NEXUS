package io.github.mangi.eta.agent.runtime

/**
 * Legacy statistics only ever stored one ambiguous completion value. A timestamp is accepted as
 * epoch milliseconds only when the value itself proves the unit: an integral JSON number inside a
 * plausible calendar range. Strings, fractions, 10-digit second values and out-of-range numbers
 * stay unknown; nothing is converted by guessing.
 */
internal object AgentMetricsTime {
    /** Explicit "not recorded" marker for the NOT NULL startedAt/updatedAt columns. */
    const val UNKNOWN = 0L

    /** 2000-01-01T00:00:00Z: smaller values are zeros, second counts or bogus epochs. */
    private const val MIN_EPOCH_MILLIS = 946_684_800_000L

    /** 2100-01-01T00:00:00Z: larger values cannot be a stored millisecond wall clock. */
    private const val MAX_EPOCH_MILLIS = 4_102_444_800_000L

    fun isKnown(value: Long?): Boolean = value != null && value in MIN_EPOCH_MILLIS..MAX_EPOCH_MILLIS

    fun known(value: Long?): Long? = value?.takeIf { it in MIN_EPOCH_MILLIS..MAX_EPOCH_MILLIS }

    /** Integral JSON numbers only; strings are never coerced, doubles never rounded. */
    fun fromLegacyJson(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }.let(::known)
}
