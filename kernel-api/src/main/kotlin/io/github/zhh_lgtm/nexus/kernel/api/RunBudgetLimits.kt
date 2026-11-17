package io.github.zhh_lgtm.nexus.kernel.api

/** Null is unbounded. Combining scopes may tighten a limit, never enlarge it. */
fun RunBudget.intersect(other: RunBudget): RunBudget {
    fun ints(left: Int?, right: Int?) = listOfNotNull(left, right).minOrNull()
    fun longs(left: Long?, right: Long?) = listOfNotNull(left, right).minOrNull()
    val rounds = ints(hardRounds, other.hardRounds)
    val tools = ints(hardToolCalls, other.hardToolCalls)
    val duration = longs(hardDurationMs, other.hardDurationMs)
    require(costLimitMicros == null || other.costLimitMicros == null || costCurrency == other.costCurrency) {
        "COST_BUDGET_CURRENCY_MISMATCH"
    }
    return RunBudget(
        softRounds = ints(softRounds, other.softRounds)?.let { ints(it, rounds) },
        hardRounds = rounds,
        softDurationMs = longs(softDurationMs, other.softDurationMs)?.let { longs(it, duration) },
        hardDurationMs = duration,
        softToolCalls = ints(softToolCalls, other.softToolCalls)?.let { ints(it, tools) },
        hardToolCalls = tools,
        costLimitMicros = longs(costLimitMicros, other.costLimitMicros),
        costCurrency = if (costLimitMicros != null) costCurrency else if (other.costLimitMicros != null) other.costCurrency else costCurrency,
    )
}

/** Only the separately approved increase path may use this; independent narrower limits survive. */
fun RunBudget.withIncreasedGoalBudget(previous: RunBudget, approved: RunBudget): RunBudget {
    require(previous.costCurrency == approved.costCurrency) { "COST_BUDGET_CURRENCY_MISMATCH" }
    fun increased(old: Long?, next: Long?) = old == null && next == null || old != null && next != null && next >= old
    require(listOf(previous.hardRounds?.toLong() to approved.hardRounds?.toLong(),
        previous.hardToolCalls?.toLong() to approved.hardToolCalls?.toLong(),
        previous.hardDurationMs to approved.hardDurationMs, previous.costLimitMicros to approved.costLimitMicros,
        previous.softRounds?.toLong() to approved.softRounds?.toLong(),
        previous.softToolCalls?.toLong() to approved.softToolCalls?.toLong(), previous.softDurationMs to approved.softDurationMs)
        .all { (old, next) -> increased(old, next) }) { "BUDGET_INCREASE_MUST_BE_FINITE_AND_MONOTONIC" }
    fun lift(current: Long?, old: Long?, next: Long?): Long? = if (old != null && current == old) next else current
    fun liftInt(current: Int?, old: Int?, next: Int?): Int? = lift(current?.toLong(), old?.toLong(), next?.toLong())?.toInt()
    return RunBudget(liftInt(softRounds, previous.softRounds, approved.softRounds),
        liftInt(hardRounds, previous.hardRounds, approved.hardRounds),
        lift(softDurationMs, previous.softDurationMs, approved.softDurationMs),
        lift(hardDurationMs, previous.hardDurationMs, approved.hardDurationMs),
        liftInt(softToolCalls, previous.softToolCalls, approved.softToolCalls),
        liftInt(hardToolCalls, previous.hardToolCalls, approved.hardToolCalls),
        lift(costLimitMicros, previous.costLimitMicros, approved.costLimitMicros), costCurrency)
}
