package io.github.mangi.eta.fork.a2a

import android.app.Application
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class A2AHttpLifecycleTest {
    private val response = """{"jsonrpc":"2.0","id":"rpc-1","result":{"id":"task","status":{"state":"TASK_STATE_COMPLETED"}}}"""

    @Test fun cancellingResponseBodyDoesNotWaitForTimeoutOrCloseOtherCalls() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val server = server().apply {
            createContext("/a2a") { exchange ->
                if (calls.incrementAndGet() == 1) {
                    exchange.sendResponseHeaders(200, 0)
                    try { exchange.responseBody.write('{'.code); exchange.responseBody.flush(); entered.countDown(); release.await(5, TimeUnit.SECONDS) }
                    finally { exchange.close() }
                } else {
                    val body = response.toByteArray(); exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
                }
            }
            start()
        }
        client(server).use { client ->
            val task = async(Dispatchers.IO) { client.getTask(server.url(), A2AGetTaskRequest("task")) }
            try {
                assertTrue(entered.await(3000, TimeUnit.MILLISECONDS))
                withTimeout(2000) { task.cancelAndJoin() }
                assertEquals(A2ATaskState.COMPLETED, client.getTask(server.url(), A2AGetTaskRequest("task")).status.state)
                assertEquals(2, calls.get())
            } finally { task.cancel(); release.countDown(); server.shutdown() }
        }
    }

    @Test fun takingOneStreamingEventCancelsTheRemainingNetworkRead() = runBlocking {
        val release = CountDownLatch(1)
        val server = server().apply {
            createContext("/a2a") { exchange ->
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                try {
                    val body = "data: {\"jsonrpc\":\"2.0\",\"id\":\"rpc-1\",\"result\":{\"task\":{\"id\":\"task\",\"status\":{\"state\":\"TASK_STATE_WORKING\"}}}}\n\n"
                    exchange.responseBody.write(body.toByteArray()); exchange.responseBody.flush()
                    release.await(5, TimeUnit.SECONDS)
                } finally { exchange.close() }
            }
            start()
        }
        try {
            client(server).use { client ->
                val events = withTimeout(2500) { client.subscribeToTask(server.url(), A2AGetTaskRequest("task")).take(1).toList() }
                assertEquals(A2ATaskState.WORKING, events.single().task!!.status.state)
            }
        } finally { release.countDown(); server.shutdown() }
    }

    @Test fun authenticatedRequestsDoNotFollowRedirects() = runBlocking {
        val followed = AtomicInteger()
        val server = server().apply {
            createContext("/a2a") { exchange -> exchange.responseHeaders.set("Location", "/redirected"); exchange.sendResponseHeaders(302, -1); exchange.close() }
            createContext("/redirected") { exchange -> followed.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
            start()
        }
        try {
            client(server).use { client ->
                val result = runCatching { client.getTask(server.url(), A2AGetTaskRequest("task"), "fixture-token") }
                assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("302"))
                assertEquals(0, followed.get())
            }
        } finally { server.shutdown() }
    }

    private fun client(server: HttpServer) = A2AHttpClient(a2aPeerPolicyForManualEndpoint(server.url()), requestIdFactory = { "rpc-1" })
    private fun server() = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
    }
    private fun HttpServer.url() = "http://127.0.0.1:${address.port}/a2a"
    private fun HttpServer.shutdown() { stop(0); (executor as ExecutorService).shutdownNow() }
}
