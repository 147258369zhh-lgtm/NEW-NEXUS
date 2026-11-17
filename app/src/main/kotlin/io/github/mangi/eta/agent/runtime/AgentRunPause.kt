package io.github.mangi.eta.agent.runtime

/**
 * Ends a run that would otherwise block on a local prompt or approval the entry surface cannot
 * answer. The caller still owns the decision: the safe summary travels back in the run result and
 * the turn is never answered with a guessed value.
 */
internal class AgentRunPauseException(
    val pause: AgentRuntimeWire.RunPause,
) : RuntimeException(
    when (pause.kind) {
        AgentRuntimeWire.RunPause.KIND_AUTH_REQUIRED -> "需要本机授权后才能继续本次任务"
        else -> "需要补充输入后才能继续本次任务"
    },
)
