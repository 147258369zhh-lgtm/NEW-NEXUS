package io.github.mangi.eta.fork.tasks

import kotlinx.coroutines.*
import org.json.JSONObject

/** Bounded interpreter. Callbacks perform the effects; failed or interrupted effects are never replayed. */
internal class WorkflowEngine(
    private val state: suspend () -> AutomationState,
    private val agent: suspend (WorkflowStep, String, String) -> AgentTaskExecutionResult,
    private val notify: suspend (String, String) -> Unit,
    private val journal: suspend (String, JSONObject) -> Unit,
) {
    suspend fun run(spec: AutomationSpec, input: String, runId: String): AgentTaskExecutionResult {
        spec.validate()
        var previous = ""
        var index = 0
        return try {
            withTimeout(spec.timeoutSeconds * 1000L) {
                while (index < spec.steps.size) {
                    ensureActive()
                    val step = spec.steps[index]
                    if (step.kind == WorkflowStepKind.CONDITION) {
                        val matched = step.conditions.matches(state())
                        journal("BRANCH", JSONObject().put("step", step.id).put("matched", matched))
                        index += if (matched) 1 else 1 + step.skipOnFalse
                        continue
                    }
                    for (iteration in 1..step.repeats) {
                        journal("STEP_STARTED", JSONObject().put("step", step.id).put("iteration", iteration).put("title", step.title))
                        var attempt = 0
                        var outcome: AgentTaskExecutionResult
                        while (true) {
                            outcome = try {
                                withTimeout(step.timeoutSeconds * 1000L) {
                                    when (step.kind) {
                                        WorkflowStepKind.WAIT -> { delay(step.waitSeconds * 1000L); AgentTaskExecutionResult.Succeeded("wait") }
                                        WorkflowStepKind.NOTIFY -> { notify(step.title, step.prompt.ifBlank { previous.take(2000) }); AgentTaskExecutionResult.Succeeded("notification") }
                                        else -> agent(step, JSONObject().put("task", input).put("previous_step_result", previous.take(12000)).toString(), "$runId:${step.id}:$iteration:$attempt")
                                    }
                                }
                            } catch (_: TimeoutCancellationException) { AgentTaskExecutionResult.Failed("STEP_TIMEOUT", "步骤超时，结果未确认，未自动重放") }
                            if (outcome !is AgentTaskExecutionResult.Failed || !outcome.retryable || attempt >= step.retries) break
                            attempt++
                            journal("STEP_RETRY", JSONObject().put("step", step.id).put("attempt", attempt))
                            delay(attempt * 1000L)
                        }
                        journal("STEP_FINISHED", JSONObject().put("step", step.id).put("iteration", iteration)
                            .put("success", outcome is AgentTaskExecutionResult.Succeeded)
                            .put("code", (outcome as? AgentTaskExecutionResult.Failed)?.code.orEmpty()))
                        when (outcome) {
                            is AgentTaskExecutionResult.Succeeded -> if (outcome.output.isNotBlank()) previous = outcome.output
                            is AgentTaskExecutionResult.Failed -> {
                                if (step.failure == WorkflowFailure.STOP) return@withTimeout outcome
                                previous = JSONObject().put("ok", false).put("step", step.id).put("code", outcome.code).put("message", outcome.message).toString()
                            }
                            else -> return@withTimeout outcome
                        }
                    }
                    index++
                }
                AgentTaskExecutionResult.Succeeded(runId, previous)
            }
        } catch (_: TimeoutCancellationException) { AgentTaskExecutionResult.Failed("WORKFLOW_TIMEOUT", "流程总超时，已停止后续步骤") }
    }
}
