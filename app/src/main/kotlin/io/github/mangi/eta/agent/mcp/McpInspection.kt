package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancels HTTP/SSE on page disposal or the overall deadline, even with keepalive traffic. */
internal suspend fun <T> inspectMcp(
    server: McpServerSetting,
    bearerToken: String?,
    timeoutMillis: Long = 60_000L,
    operation: (McpClient) -> T,
): T = withTimeout(timeoutMillis) {
    if (server.launchCommand.isNotBlank()) {
        McpProcessManager.ensureStarted(McpServerRepository.appContext(), server)
        McpProcessManager.awaitReady(server)
    }
    suspendCancellableCoroutine { continuation ->
        val client = try {
            McpClientFactory.create(server, bearerToken)
        } catch (failure: Exception) {
            continuation.resumeWithException(failure)
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { client.close() }
        CoroutineScope(continuation.context).launch(Dispatchers.IO) {
            try {
                val result = client.use(operation)
                continuation.resume(result)
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    }
}
