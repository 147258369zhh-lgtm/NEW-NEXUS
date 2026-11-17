package io.github.mangi.eta.agent.capability

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Callback
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Per-owner calls only. Closing a tool never shuts down the app's shared connection pool. */
internal class CapabilityHttpSession(client: OkHttpClient) : AutoCloseable {
    private val client = client.newBuilder().retryOnConnectionFailure(false).build()
    private val lock = Any()
    private val calls = mutableSetOf<Call>()
    private var closed = false
    val isClosed: Boolean get() = synchronized(lock) { closed }

    fun <T> execute(request: Request, read: (Response) -> T): T {
        val call = synchronized(lock) {
            if (closed) throw ApiConnectorException("API_EXECUTOR_CLOSED", "该任务的外部连接已关闭")
            client.newCall(request).also { calls += it }
        }
        val cancellation = io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.controller?.register(call::cancel)
        try { return call.execute().use(read) }
        finally { cancellation?.close(); synchronized(lock) { calls.remove(call) } }
    }

    /** The continuation stays cancellable while the response body is being consumed. */
    suspend fun <T> executeSuspending(request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = synchronized(lock) {
            if (closed) {
                continuation.resumeWith(Result.failure(ApiConnectorException("API_EXECUTOR_CLOSED", "该任务的外部连接已关闭")))
                return@suspendCancellableCoroutine
            }
            client.newCall(request).also { calls += it }
        }
        val binding = io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.controller?.register(call::cancel)
        continuation.invokeOnCancellation { call.cancel() }
        fun finish() { binding?.close(); synchronized(lock) { calls.remove(call) } }
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    try { continuation.resumeWith(Result.failure(e)) } finally { finish() }
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = runCatching { response.use { if (!continuation.isActive) throw IOException("请求已取消"); read(it) } }
                        continuation.resumeWith(result)
                    } finally { finish() }
                }
            })
        } catch (failure: Exception) {
            finish()
            continuation.resumeWith(Result.failure(failure))
        }
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            calls.toList()
        }
        pending.forEach(Call::cancel)
    }
}
