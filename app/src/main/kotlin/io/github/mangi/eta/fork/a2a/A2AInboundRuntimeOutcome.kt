package io.github.mangi.eta.fork.a2a

import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * Translates one local Runtime result into an inbound A2A turn outcome.
 *
 * A run that stopped on a local prompt or approval becomes a waiting task. Anything else that is
 * not ok stays a failure so the peer never reads a paused or broken turn as a finished answer.
 */
internal object A2AInboundRuntimeOutcome {
    /** Interaction primitive: asks the caller for a decision and grants no local capability. */
    const val INPUT_TOOL = "request_user_input"

    /**
     * 失败对端只收到固定文案。Runtime 的错误可能带本机路径、提示词或凭据形态片段，
     * 原始 error.message 不得进入 JSON-RPC 错误响应。
     */
    const val GENERIC_FAILURE_MESSAGE = "Local Agent execution failed; review the task on the device."

    private const val INPUT_CODE = "A2A_" + AgentRuntimeWire.RunPause.KIND_INPUT_REQUIRED
    private const val AUTH_CODE = "A2A_" + AgentRuntimeWire.RunPause.KIND_AUTH_REQUIRED

    fun map(
        result: AgentRuntimeWire.RunResult,
        access: A2AToolAccess,
    ): A2AInboundExecutionOutcome {
        if (result.ok) {
            val answer = A2AOutputSanitizer.sanitize(result.content, access).trim()
            if (answer.isBlank()) throw A2AProtocolException("The local Agent returned an empty answer")
            return A2AInboundExecutionOutcome.Completed(answer)
        }
        val pause = result.pause
        return when (pause?.kind ?: result.failureCode) {
            AgentRuntimeWire.RunPause.KIND_INPUT_REQUIRED, INPUT_CODE -> A2AInboundExecutionOutcome.InputRequired(
                A2AInboundStatusSummary.inputRequired(),
            )
            AgentRuntimeWire.RunPause.KIND_AUTH_REQUIRED, AUTH_CODE -> A2AInboundExecutionOutcome.AuthRequired(
                A2AInboundStatusSummary.authorizationRequired(pause?.reason.orEmpty()),
                pause = pause,
            )
            else -> {
                AndroidAgentLogger.warn(
                    "A2A runtime failure mapped to generic status: " +
                        "code=${result.failureCode.orEmpty().take(64)}, " +
                        "error_chars=${result.error?.length ?: 0}",
                )
                throw A2AProtocolException(GENERIC_FAILURE_MESSAGE)
            }
        }
    }
}

/**
 * Builds the status message a remote peer may read. Input content is local-only: heuristic
 * redaction cannot reliably identify personal information or arbitrary secrets.
 */
internal object A2AInboundStatusSummary {
    private const val MAX_MESSAGE_CHARS = 600
    private const val MAX_QUESTION_CHARS = 360
    private const val INPUT_REQUIRED_MESSAGE = "需要设备所有者提供输入。"

    private val secretPatterns = listOf(
        Regex("(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9._~+/=-]{6,}"),
        Regex("(?i)\\b(?:sk|pk|rk|ghp|gho|ghs|ghu|glpat|xox[abposr]|ak|aiza|dsh)[-_][A-Za-z0-9_-]{6,}"),
        Regex(
            "(?i)\\b(?:api[-_]?key|access[-_]?token|refresh[-_]?token|token|secret|password|passwd|" +
                "authorization|credential)s?\\b\\s*[:=]\\s*\\S+",
        ),
        Regex("\\b[A-Fa-f0-9]{32,}\\b"),
        Regex("\\b[A-Za-z0-9+/=_-]{40,}\\b"),
    )

    fun inputRequired(): String = INPUT_REQUIRED_MESSAGE

    fun authorizationRequired(reason: String): String {
        val safeReason = summarize(reason, MAX_QUESTION_CHARS)
        return (
            if (safeReason.isBlank()) {
                "Authorization required: the device owner must approve work this task cannot perform."
            } else {
                "Authorization required: $safeReason"
            }
            ).take(MAX_MESSAGE_CHARS)
    }

    /** Bounded, single-line text with credential-shaped values removed. */
    fun summarize(text: String, limit: Int): String {
        var value = text.replace(Regex("[\\u0000-\\u001F\\u007F]+"), " ")
        secretPatterns.forEach { pattern -> value = pattern.replace(value, "[redacted]") }
        value = value.replace(Regex("\\s+"), " ").trim()
        if (value.length > limit) value = value.take(limit).trimEnd() + "…"
        return value
    }
}
