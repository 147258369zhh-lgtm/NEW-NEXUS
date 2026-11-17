package io.github.mangi.eta.fork.tasks

import android.content.Context

/** Persisted admission limit for durable task execution. */
internal object AgentTaskSchedulerSettings {
    const val DEFAULT_MAX_CONCURRENT_RUNS = 1
    const val MAX_CONCURRENT_RUNS = 4
    private const val PREFS = "automation-scheduler"
    private const val KEY_MAX_CONCURRENT_RUNS = "max_concurrent_runs"

    fun maxConcurrentRuns(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_CONCURRENT_RUNS, DEFAULT_MAX_CONCURRENT_RUNS)
            .coerceIn(1, MAX_CONCURRENT_RUNS)

    fun setMaxConcurrentRuns(context: Context, value: Int): Int {
        val normalized = value.coerceIn(1, MAX_CONCURRENT_RUNS)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MAX_CONCURRENT_RUNS, normalized)
            .apply()
        return normalized
    }
}
