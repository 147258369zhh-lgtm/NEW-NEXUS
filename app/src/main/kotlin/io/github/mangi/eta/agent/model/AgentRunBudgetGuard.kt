package io.github.mangi.eta.agent.model

import io.github.zhh_lgtm.nexus.kernel.api.RunBudget
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ScheduledFuture
import io.github.zhh_lgtm.nexus.kernel.api.intersect

internal enum class RunBudgetDimension { ROUNDS, TOOL_CALLS, DURATION }
internal data class SoftRunBudgetLimit(val dimension: RunBudgetDimension, val observed: Long,
    val softLimit: Long, val hardLimit: Long?, val budget: RunBudget? = null)

/** Run-scoped hard limits that prevent malformed model loops from running forever. */
internal class AgentRunBudgetGuard(
    budget: RunBudget,
    private val initialRound: Int = 1,
    private val clockNanos: () -> Long = System::nanoTime,
    private val approveSoftLimit: (SoftRunBudgetLimit) -> Boolean = { false },
    private val approveHardLimit: (SoftRunBudgetLimit) -> Boolean = { false },
) {
    private val startedNanos = clockNanos()
    @Volatile private var budget = budget
    private val stateLock = Any()
    private val approvalLock = Any()
    private val approvedLimits = mutableMapOf<RunBudgetDimension, Long>()
    private val deadlineUpdates = CopyOnWriteArraySet<() -> Unit>()
    private var toolCalls = 0
    private var roundsUsed = 0L

    fun checkRound(round: Int) {
        val observed = (round.toLong() - initialRound + 1).coerceAtLeast(0)
        while (true) {
            val current = budget
            val roundLimit = current.hardRounds
            if (roundLimit != null && observed > roundLimit) {
                if (extendHard(RunBudgetDimension.ROUNDS, observed, roundLimit.toLong(), current)) continue
                fail("ROUNDS", "模型回合", observed, roundLimit.toLong())
            }
            checkDuration()
            val usedTools = toolCallsUsed().toLong()
            val toolLimit = current.hardToolCalls
            if (toolLimit != null && usedTools > toolLimit) {
                if (extendHard(RunBudgetDimension.TOOL_CALLS, usedTools, toolLimit.toLong(), current)) continue
                fail("TOOL_CALLS", "工具调用", usedTools, toolLimit.toLong())
            }
            checkSoft(RunBudgetDimension.ROUNDS, observed)
            checkSoft(RunBudgetDimension.TOOL_CALLS, toolCallsUsed().toLong())
            checkSoftDuration()
            synchronized(stateLock) {
                if (budget == current) {
                    roundsUsed = maxOf(roundsUsed, observed)
                    return
                }
            }
        }
    }

    fun beforeToolCall() = reserveToolCalls(1)

    /** Hosted tools have already started on the server; account them and approve at a safe boundary. */
    fun beforeHostedToolCall() {
        checkDuration()
        synchronized(stateLock) {
            val next = toolCalls.toLong() + 1
            toolCalls = next.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    /** A child must stop the entire parent run when its shared allowance is exhausted. */
    fun beforeChildToolCall(parentController: AgentRunController) {
        try { beforeToolCall() }
        catch(failure: AgentModelFailure) { parentController.abort(failure); throw failure }
    }

    /** Reserve a parallel batch before any member starts, so rejection cannot hide partial work. */
    fun reserveToolCalls(count: Int) {
        require(count >= 0)
        if (count == 0) { checkDuration(); return }
        while (true) {
            val before = synchronized(stateLock) { toolCalls }
            val current = budget
            val next = before.toLong() + count
            checkDuration()
            val toolLimit = current.hardToolCalls
            if (toolLimit != null && next > toolLimit) {
                if (extendHard(RunBudgetDimension.TOOL_CALLS, next, toolLimit.toLong(), current)) continue
                fail("TOOL_CALLS", "工具调用", next, toolLimit.toLong())
            }
            checkSoft(RunBudgetDimension.TOOL_CALLS, next)
            checkSoftDuration()
            synchronized(stateLock) {
                if (before == toolCalls && current == budget) {
                    toolCalls = next.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    return
                }
            }
        }
    }

    fun checkDuration() {
        while (true) {
            val current = budget
            val limit = current.hardDurationMs ?: return
            val elapsed = elapsedMillis()
            if (elapsed < limit) return
            if (!extendHard(RunBudgetDimension.DURATION, elapsed, limit, current)) {
                fail("DURATION", "运行时长（毫秒）", elapsed, limit)
            }
        }
    }

    private fun extendHard(dimension: RunBudgetDimension, observed: Long, limit: Long, current: RunBudget): Boolean =
        synchronized(approvalLock) {
            if (budget != current) return@synchronized true
            if (!approveHardLimit(SoftRunBudgetLimit(dimension, observed, limit, limit, current))) return@synchronized false
            // A callback must commit an actual increase. A bare approval cannot create an
            // unbounded busy loop at an exhausted hard limit.
            budget != current
        }

    /** Goal creation may narrow this same run; counters and elapsed time are never reset. */
    fun restrictBudget(limit: RunBudget) {
        val changed = synchronized(stateLock) {
            val restricted = budget.intersect(limit)
            if (restricted == budget) false else { budget = restricted; true }
        }
        if (changed) deadlineUpdates.forEach { it() }
    }

    fun effectiveBudget(): RunBudget = budget

    private fun applyApprovedIncrease(increased: RunBudget) {
        synchronized(stateLock) { budget = increased }
        deadlineUpdates.forEach { it() }
    }

    private fun elapsedMillis() = TimeUnit.NANOSECONDS.toMillis((clockNanos() - startedNanos).coerceAtLeast(0L))

    private fun checkSoftDuration() = checkSoft(RunBudgetDimension.DURATION, elapsedMillis())

    private fun checkSoft(dimension: RunBudgetDimension, observed: Long) = synchronized(approvalLock) {
        while (true) {
            val current = budget
            val (soft, hard) = when (dimension) {
                RunBudgetDimension.ROUNDS -> current.softRounds?.toLong() to current.hardRounds?.toLong()
                RunBudgetDimension.TOOL_CALLS -> current.softToolCalls?.toLong() to current.hardToolCalls?.toLong()
                RunBudgetDimension.DURATION -> current.softDurationMs to current.hardDurationMs
            }
            if (soft == null || (if (dimension == RunBudgetDimension.DURATION) observed < soft else observed <= soft) ||
                approvedLimits[dimension] == soft) return@synchronized
            if (!approveSoftLimit(SoftRunBudgetLimit(dimension, observed, soft, hard, current))) {
                throw AgentModelFailure("RUN_SOFT_BUDGET_NOT_APPROVED_${dimension.name}", false,
                    "任务已达到软预算，未获继续确认。已完成的结果已保留。")
            }
            checkDuration()
            if (budget == current) {
                approvedLimits[dimension] = soft
                return@synchronized
            }
        }
    }

    /** Cancel blocked HTTP/native/tool resources even when no event or next round arrives. */
    fun watch(controller: AgentRunController): AutoCloseable {
        checkDuration()
        val active = AtomicBoolean(true)
        val watchLock = Any()
        var future: ScheduledFuture<*>? = null
        var generation = 0L
        val reschedule: () -> Unit = {
            synchronized(watchLock) {
                if (active.get()) {
                    future?.cancel(false)
                    val revision = ++generation
                    budget.hardDurationMs?.let { limit ->
                        val remainingNanos = (TimeUnit.MILLISECONDS.toNanos(limit) - (clockNanos() - startedNanos)).coerceAtLeast(0)
                        future = deadlines.schedule({
                            val expired = synchronized(watchLock) { generation == revision && active.get() }
                            if (expired) {
                                try { checkDuration() } catch (failure: RuntimeException) { controller.abort(failure) }
                            }
                        }, remainingNanos, TimeUnit.NANOSECONDS)
                    }
                }
            }
        }
        deadlineUpdates.add(reschedule)
        val limitsBinding = controller.observeBudgetLimits(::restrictBudget)
        val increaseBinding = controller.observeApprovedBudgetIncreases(::applyApprovedIncrease)
        reschedule()
        return AutoCloseable {
            synchronized(watchLock) { active.set(false); future?.cancel(false) }
            limitsBinding.close()
            increaseBinding.close()
            deadlineUpdates.remove(reschedule)
        }
    }

    fun toolCallsUsed(): Int = synchronized(stateLock) { toolCalls }
    fun roundsUsed(): Long = synchronized(stateLock) { roundsUsed }

    private fun fail(code: String, label: String, observed: Long, limit: Long): Nothing {
        throw failure(code, label, observed, limit)
    }

    private fun failure(code: String, label: String, observed: Long, limit: Long) = AgentModelFailure(
            code = "RUN_BUDGET_EXCEEDED_$code",
            retryable = false,
            message = "本次任务已达到${label}安全上限（$observed / $limit）。已完成的工具结果已保留；请缩小任务范围或明确增加预算后继续。",
        )

    companion object {
        private val deadlines = ScheduledThreadPoolExecutor(2) { task ->
            Thread(task, "nexus-run-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

internal object AgentRunBudgets {
    fun default(): RunBudget = RunBudget(
        softRounds = 48,
        hardRounds = 64,
        softDurationMs = 45L * 60_000L,
        hardDurationMs = 60L * 60_000L,
        softToolCalls = 96,
        hardToolCalls = 128,
    )

    fun forSource(source: String?): RunBudget = when (source?.lowercase()) {
        "plugin_action" -> RunBudget(hardRounds = 1, hardDurationMs = 150_000, hardToolCalls = 65)
        "memory_extraction", "skill_evolution" -> RunBudget(hardRounds = 3, hardDurationMs = 90_000, hardToolCalls = 1)
        "transcript_refine" -> RunBudget(hardRounds = 1, hardDurationMs = 8_000, hardToolCalls = 1)
        "a2a" -> RunBudget(
            softRounds = 12,
            hardRounds = 24,
            softDurationMs = 10L * 60_000L,
            hardDurationMs = 20L * 60_000L,
            softToolCalls = 24,
            hardToolCalls = 48,
        )
        "scheduled_task" -> RunBudget(
            softRounds = 36,
            hardRounds = 80,
            softDurationMs = 45L * 60_000L,
            hardDurationMs = 90L * 60_000L,
            softToolCalls = 128,
            hardToolCalls = 256,
        )
        "meeting" -> RunBudget(
            softRounds = 24,
            hardRounds = 48,
            softDurationMs = 30L * 60_000L,
            hardDurationMs = 60L * 60_000L,
            softToolCalls = 64,
            hardToolCalls = 128,
        )
        "voice", "eta_voice" -> RunBudget(
            softRounds = 16,
            hardRounds = 32,
            softDurationMs = 10L * 60_000L,
            hardDurationMs = 20L * 60_000L,
            softToolCalls = 32,
            hardToolCalls = 64,
        )
        else -> default()
    }

    fun forSubagent(maxToolCalls: Int?): RunBudget = RunBudget(
        softRounds = 16,
        hardRounds = 32,
        softDurationMs = 15L * 60_000L,
        hardDurationMs = 30L * 60_000L,
        softToolCalls = maxToolCalls?.let { (it * 3 / 4).coerceAtLeast(1) },
        hardToolCalls = maxToolCalls ?: 64,
    )
}
