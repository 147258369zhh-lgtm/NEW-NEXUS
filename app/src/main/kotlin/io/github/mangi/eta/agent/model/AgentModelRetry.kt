package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController

/** 重试只包围模型请求；完整响应返回前不提交历史或执行本地工具。 */
internal class AgentModelRetry(
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    data class Result(val round: Int, val response: ProviderResponse)

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
    ): Result {
        var round = initialRound
        var retries = 0
        while (true) {
            controller.throwIfCancelled()
            onEvent(AgentEvent.RoundStarted(round, request.messages.length()))
            var hostedToolStarted = false
            var callbackFailed = false
            try {
                val response = provider.complete(request, controller) { event ->
                    if (event is ProviderEvent.HostedToolStarted) hostedToolStarted = true
                    try {
                        onProviderEvent(round, event)
                    } catch (failure: Exception) {
                        callbackFailed = true
                        throw failure
                    }
                }
                return Result(round, response)
            } catch (failure: Exception) {
                controller.throwIfCancelled()
                if (callbackFailed || Thread.currentThread().isInterrupted) throw failure
                val classified = AgentModelFailure.transport(failure) ?: throw failure
                if (hostedToolStarted) throw AgentModelFailure(
                    classified.code, false, classified.message.orEmpty(), classified,
                    classified.localRuntime,
                )
                if (!classified.retryable) throw classified
                val maxRetries = maxRetriesFor(classified)
                if (retries == maxRetries) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "${classified.message} 已重试 $maxRetries 次仍未恢复，已保留此前完成的工具结果。",
                        classified,
                        classified.localRuntime,
                    )
                }
                retries += 1
                val delayMs = BASE_DELAY_MS shl (retries - 1)
                onEvent(AgentEvent.ModelRetryScheduled(round, retries, maxRetries, delayMs.toInt(), classified.code))
                waitBeforeRetry(controller, delayMs)
                controller.throwIfCancelled()
                // 展示保留失败尝试，模型上下文与最终思考摘要只接纳成功尝试。
                discardAttemptReasoning()
                round += 1
            }
        }
    }

    companion object {
        private const val DEFAULT_MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 2_000L

        private fun maxRetriesFor(failure: AgentModelFailure): Int = when (failure.code) {
            // The provider first retries these as a non-stream request. If that also fails,
            // another multi-minute sequence only makes chat feel hung.
            "MODEL_TIMEOUT", "HTTP_408", "STREAM_INCOMPLETE" -> 1
            "MODEL_CONNECTION_FAILED" -> 2
            else -> DEFAULT_MAX_RETRIES
        }
    }
}
