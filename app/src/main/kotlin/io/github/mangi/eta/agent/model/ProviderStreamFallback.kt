package io.github.mangi.eta.agent.model

/** Only transport failures before visible output are safe to replay as a non-stream request. */
internal fun Throwable.canRetryAsNonStreaming(): Boolean {
    val exception = this as? Exception ?: return false
    val failure = AgentModelFailure.transport(exception) ?: return false
    return failure.code in setOf(
        "MODEL_TIMEOUT",
        "MODEL_CONNECTION_FAILED",
        "STREAM_INCOMPLETE",
    )
}
