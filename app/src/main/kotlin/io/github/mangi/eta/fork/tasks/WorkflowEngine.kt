package io.github.mangi.eta.fork.tasks

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONObject

/** Bounded interpreter. Each settled step is written before the next effect can start. */
internal class WorkflowEngine(
    private val state: suspend () -> AutomationState,
    private val agent: suspend (WorkflowStep, String, String) -> AgentTaskExecutionResult,
    private val notify: suspend (String, String) -> Unit,
    private val journal: suspend (String, JSONObject) -> Unit,
    private val persistCheckpoint: suspend (WorkflowCheckpoint) -> Unit = { },
    private val clock: () -> Long = System::currentTimeMillis,
    private val acknowledge: suspend (AgentTaskExecutionResult) -> Unit = { },
) {
    suspend fun run(
        spec: AutomationSpec,
        input: String,
        runId: String,
        initialCheckpoint: WorkflowCheckpoint = WorkflowCheckpoint(),
    ): AgentTaskExecutionResult {
        spec.validate()
        validateCursor(spec, initialCheckpoint)?.let { reason ->
            return AgentTaskExecutionResult.Failed("WORKFLOW_CHECKPOINT_INVALID", reason)
        }
        if (initialCheckpoint.stepStarted) {
            return AgentTaskExecutionResult.Failed(
                "WORKFLOW_STEP_UNCONFIRMED",
                "上次步骤已开始但结果未确认，未自动重放",
            )
        }
        val runtimeStartedAt = initialCheckpoint.workflowStartedAt ?: clock()
        var checkpoint = initialCheckpoint.copy(workflowStartedAt = runtimeStartedAt)
        persistCheckpoint(checkpoint)
        val deadline = saturatingAdd(runtimeStartedAt, spec.timeoutSeconds * 1000L)
        val remaining = deadline - clock()
        if (remaining <= 0) return workflowTimedOut()
        var previous = checkpoint.previous
        // `task` and `previous` are render-time values; only settled step outputs are persisted.
        val variables = checkpoint.variables.toMutableMap()
        var index = checkpoint.nextStepIndex
        var nextIteration = checkpoint.nextIteration
        return try {
            withTimeout(remaining) {
                while (index < spec.steps.size) {
                    ensureActive()
                    val step = spec.steps[index]
                    if (step.kind == WorkflowStepKind.CONDITION) {
                        val evaluation = step.conditions.evaluate(state())
                        journal(
                            "BRANCH",
                            JSONObject().put("step", step.id).put("matched", evaluation.matched)
                                .put("reason", if (evaluation.matched) "" else evaluation.reason.orEmpty()),
                        )
                        val target = if (evaluation.matched) index + 1 else index + 1 + step.skipOnFalse
                        if (target > spec.steps.size) {
                            return@withTimeout AgentTaskExecutionResult.Failed(
                                "WORKFLOW_BRANCH_INVALID",
                                "条件分支越过最后一步，未继续执行",
                            )
                        }
                        index = target
                        nextIteration = 1
                        checkpoint = checkpoint.copy(
                            nextStepIndex = index,
                            nextIteration = 1,
                            previous = previous,
                            variables = variables.toMap(),
                            waitUntil = null,
                            stepStarted = false,
                        )
                        persistCheckpoint(checkpoint)
                        continue
                    }

                    var iteration = nextIteration
                    while (iteration <= step.repeats) {
                        ensureActive()
                        // WHILE is a pre-iteration gate. It is a pure state read, so a resumed
                        // iteration may recheck it without repeating a settled step.
                        if (step.loopMode == WorkflowLoopMode.WHILE) {
                            val gate = step.conditions.evaluate(state())
                            if (!gate.matched) {
                                journal(
                                    "LOOP_FINISHED",
                                    JSONObject().put("step", step.id).put("iteration", iteration)
                                        .put("mode", step.loopMode.name).put("reason", gate.reason.orEmpty()),
                                )
                                index += 1
                                nextIteration = 1
                                checkpoint = checkpoint.copy(
                                    nextStepIndex = index,
                                    nextIteration = 1,
                                    previous = previous,
                                    variables = variables.toMap(),
                                    waitUntil = null,
                                    stepStarted = false,
                                    definitionFingerprint = checkpoint.definitionFingerprint,
                                )
                                persistCheckpoint(checkpoint)
                                break
                            }
                        }
                        if (step.kind == WorkflowStepKind.WAIT) {
                            val waitUntil = checkpoint.waitUntil ?: saturatingAdd(clock(), step.waitSeconds * 1000L)
                            if (checkpoint.waitUntil == null) {
                                checkpoint = checkpoint.copy(waitUntil = waitUntil, stepStarted = false)
                                persistCheckpoint(checkpoint)
                                journal(
                                    "STEP_WAIT_SCHEDULED",
                                    JSONObject().put("step", step.id).put("iteration", iteration)
                                        .put("title", step.title).put("until", waitUntil),
                                )
                            }
                            val waitRemaining = waitUntil - clock()
                            if (waitRemaining > 0) {
                                return@withTimeout AgentTaskExecutionResult.Deferred(
                                    delayMillis = waitRemaining,
                                    reason = "等待步骤 ${step.title} 至原定时间",
                                    wakeAtEpochMillis = waitUntil,
                                )
                            }
                            variables[step.id] = "wait"
                            previous = "wait"
                            journal(
                                "STEP_FINISHED",
                                JSONObject().put("step", step.id).put("iteration", iteration)
                                    .put("title", step.title).put("success", true).put("code", ""),
                            )
                            val advanced = loopAdvance(step, index, iteration, previous, variables, runtimeStartedAt)
                            val movedToNextStep = advanced.nextStepIndex != index
                            index = advanced.nextStepIndex
                            nextIteration = advanced.nextIteration
                            checkpoint = advanced.copy(
                                waitUntil = null,
                                stepStarted = false,
                                definitionFingerprint = checkpoint.definitionFingerprint,
                            )
                            persistCheckpoint(checkpoint)
                            if (movedToNextStep) break
                            iteration = nextIteration
                            continue
                        }

                        // A resumed cursor that already started this step means its effect was not
                        // confirmed. Re-running it could duplicate an external side effect.
                        if (checkpoint.stepStarted) {
                            journal(
                                "STEP_UNCONFIRMED",
                                JSONObject().put("step", step.id).put("iteration", iteration).put("title", step.title),
                            )
                            return@withTimeout AgentTaskExecutionResult.Failed(
                                "WORKFLOW_STEP_UNCONFIRMED",
                                "步骤 ${step.title} 已开始但结果未确认，未自动重放",
                            )
                        }
                        checkpoint = checkpoint.copy(
                            nextStepIndex = index,
                            nextIteration = iteration,
                            previous = previous,
                            variables = variables.toMap(),
                            waitUntil = null,
                            stepStarted = true,
                        )
                        persistCheckpoint(checkpoint)
                        journal(
                            "STEP_STARTED",
                            JSONObject().put("step", step.id).put("iteration", iteration).put("title", step.title),
                        )
                        val renderedStep = try {
                            step.copy(prompt = renderVariables(step.prompt, variables, previous, input))
                        } catch (error: WorkflowVariableMissingException) {
                            journal(
                                "STEP_FINISHED",
                                JSONObject().put("step", step.id).put("iteration", iteration)
                                    .put("title", step.title).put("success", false).put("code", "WORKFLOW_VARIABLE_MISSING"),
                            )
                            return@withTimeout AgentTaskExecutionResult.Failed(
                                "WORKFLOW_VARIABLE_MISSING",
                                "步骤引用了未定义变量：${error.variable}",
                            )
                        }
                        var attempt = 0
                        var outcome: AgentTaskExecutionResult
                        while (true) {
                            outcome = try {
                                withTimeout(step.timeoutSeconds * 1000L) {
                                    when (renderedStep.kind) {
                                        WorkflowStepKind.NOTIFY -> {
                                            // Prior output is data, never a template source of its own.
                                            val text = renderedStep.prompt.ifBlank { previous.take(2000) }
                                            notify(renderedStep.title, text)
                                            AgentTaskExecutionResult.Succeeded("notification")
                                        }
                                        else -> agent(
                                            renderedStep,
                                            JSONObject()
                                                .put("task", input)
                                                .put("previous_step_result", previous.take(12000))
                                                .put("variables", JSONObject(variables.toSortedMap()))
                                                .toString(),
                                            "$runId:${step.id}:$iteration:$attempt",
                                        )
                                    }
                                }
                            } catch (_: TimeoutCancellationException) {
                                currentCoroutineContext().ensureActive()
                                AgentTaskExecutionResult.Failed("STEP_TIMEOUT", "步骤超时，结果未确认，未自动重放")
                            }
                            if (outcome !is AgentTaskExecutionResult.Failed || !outcome.retryable || attempt >= step.retries) break
                            attempt++
                            journal("STEP_RETRY", JSONObject().put("step", step.id).put("attempt", attempt))
                            delay(attempt * 1000L)
                        }
                        journal(
                            "STEP_FINISHED",
                            JSONObject().put("step", step.id).put("iteration", iteration)
                                .put("title", step.title).put("success", outcome is AgentTaskExecutionResult.Succeeded)
                                .put("code", (outcome as? AgentTaskExecutionResult.Failed)?.code.orEmpty()),
                        )
                        when (outcome) {
                            is AgentTaskExecutionResult.Succeeded -> {
                                previous = outcome.output.take(12_000)
                                variables[step.id] = previous
                            }
                            is AgentTaskExecutionResult.Failed -> {
                                if (step.failure == WorkflowFailure.STOP) return@withTimeout outcome
                                previous = JSONObject().put("ok", false).put("step", step.id)
                                    .put("code", outcome.code).put("message", outcome.message).toString()
                                variables[step.id] = previous
                            }
                            else -> return@withTimeout outcome
                        }
                        val advanced = loopAdvance(step, index, iteration, previous, variables, runtimeStartedAt)
                        val movedToNextStep = advanced.nextStepIndex != index
                        index = advanced.nextStepIndex
                        nextIteration = advanced.nextIteration
                        checkpoint = advanced.copy(
                            waitUntil = null,
                            stepStarted = false,
                            definitionFingerprint = checkpoint.definitionFingerprint,
                        )
                        persistCheckpoint(checkpoint)
                        try {
                            acknowledge(outcome)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // The durable checkpoint remains authoritative; keep the receipt/outbox.
                        }
                        if (movedToNextStep) break
                        iteration = nextIteration
                    }
                }
                checkpoint = checkpoint.copy(
                    nextStepIndex = index,
                    nextIteration = 1,
                    previous = previous,
                    variables = variables.toMap(),
                    waitUntil = null,
                    stepStarted = false,
                    definitionFingerprint = checkpoint.definitionFingerprint,
                )
                persistCheckpoint(checkpoint)
                AgentTaskExecutionResult.Succeeded(runId, previous)
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            workflowTimedOut()
        }
    }

    private fun advance(
        step: WorkflowStep,
        index: Int,
        iteration: Int,
        previous: String,
        variables: Map<String, String>,
        workflowStartedAt: Long,
    ): WorkflowCheckpoint {
        val hasMoreIterations = iteration < step.repeats
        return WorkflowCheckpoint(
            nextStepIndex = if (hasMoreIterations) index else index + 1,
            nextIteration = if (hasMoreIterations) iteration + 1 else 1,
            previous = previous,
            variables = variables,
            workflowStartedAt = workflowStartedAt,
        )
    }

    /** UNTIL runs once more only when the settled iteration did not yet satisfy its condition. */
    private suspend fun loopAdvance(
        step: WorkflowStep,
        index: Int,
        iteration: Int,
        previous: String,
        variables: Map<String, String>,
        workflowStartedAt: Long,
    ): WorkflowCheckpoint {
        if (step.loopMode == WorkflowLoopMode.UNTIL) {
            val stop = step.conditions.evaluate(state())
            if (stop.matched) {
                journal(
                    "LOOP_FINISHED",
                    JSONObject().put("step", step.id).put("iteration", iteration)
                        .put("mode", step.loopMode.name).put("reason", ""),
                )
                return WorkflowCheckpoint(
                    nextStepIndex = index + 1,
                    nextIteration = 1,
                    previous = previous,
                    variables = variables,
                    workflowStartedAt = workflowStartedAt,
                )
            }
        }
        return advance(step, index, iteration, previous, variables, workflowStartedAt)
    }

    private fun renderVariables(
        template: String,
        variables: Map<String, String>,
        previous: String,
        task: String,
    ): String =
        VARIABLE.replace(template) { match ->
            val key = match.groupValues[1]
            when (key) {
                "previous" -> previous
                "task" -> task
                else -> variables[key] ?: throw WorkflowVariableMissingException(key)
            }
        }

    private fun workflowTimedOut() = AgentTaskExecutionResult.Failed(
        "WORKFLOW_TIMEOUT",
        "流程总超时，已停止后续步骤",
    )

    /** Returns a stable reason when a recovered cursor no longer fits the persisted workflow. */
    private fun validateCursor(spec: AutomationSpec, checkpoint: WorkflowCheckpoint): String? {
        if (checkpoint.nextStepIndex !in 0..spec.steps.size) return "工作流断点步骤超出范围"
        if (checkpoint.nextStepIndex == spec.steps.size) {
            if (checkpoint.nextIteration != 1) return "工作流断点终态迭代无效"
            if (checkpoint.waitUntil != null) return "工作流终态断点不能保留等待期限"
            return null
        }
        val step = spec.steps[checkpoint.nextStepIndex]
        if (checkpoint.nextIteration !in 1..step.repeats) return "工作流断点迭代超出步骤次数"
        if (checkpoint.waitUntil != null && step.kind != WorkflowStepKind.WAIT) {
            return "工作流断点等待期限不属于 WAIT 步骤"
        }
        return null
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private class WorkflowVariableMissingException(val variable: String) : Exception(variable)

    companion object {
        private val VARIABLE = Regex("\\{\\{([A-Za-z0-9_][A-Za-z0-9_.-]{0,63})}}")
    }
}
