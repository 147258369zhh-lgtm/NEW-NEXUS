package io.github.mangi.eta.agent.connector

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ConnectorTransportLifecycleTest {
    @Test fun cancellingBlockedJsonBodyClosesOnlyThatCall() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = server().apply {
            createContext("/rpc") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                try {
                    exchange.responseBody.write('{'.code); exchange.responseBody.flush()
                    entered.countDown(); release.await(5, TimeUnit.SECONDS)
                } finally { exchange.close() }
            }
            start()
        }
        val transport = JsonRpcConnectorTransport(server.url("/rpc"), null, ConnectorSecurityManager())
        val controller = AgentRunController()
        val task = async(Dispatchers.IO) {
            RuntimeInvocationScope.withController(controller) {
                runBlocking(RuntimeInvocationScope.coroutineContext()) {
                    runCatching { transport.invoke(ConnectorInvocation("call", JSONObject().put("method", "read").put("confirmed", true))) }
                }
            }
        }
        try {
            assertTrue(entered.await(3000, TimeUnit.MILLISECONDS))
            controller.cancel()
            assertTrue(withTimeout(2000) { task.await() }.isFailure)
        } finally { controller.cancel(); transport.close(); release.countDown(); task.cancel(); server.shutdown() }
    }

    @Test fun sseStopsAtRequestedCountAndRejectsOversizedLine() = runBlocking {
        val server = server().apply {
            createContext("/events") { exchange ->
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                val body = (1..4).joinToString("") { "data: {\"value\":$it}\n\n" }.toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            }
            createContext("/large") { exchange ->
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                val body = ("data: " + "x".repeat(70_000) + "\n\n").toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            SseConnectorTransport(server.url("/events"), null, ConnectorSecurityManager()).use { transport ->
                val result = transport.invoke(ConnectorInvocation("listen", JSONObject().put("max_events", 2)))
                assertEquals(listOf(1, 2), result.events.map { it.getInt("value") })
            }
            SseConnectorTransport(server.url("/large"), null, ConnectorSecurityManager()).use { transport ->
                val result = runCatching { transport.invoke(ConnectorInvocation("listen")) }
                assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("单行过大"))
            }
        } finally { server.shutdown() }
    }

    private fun server() = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
    }
    private fun HttpServer.url(path: String) = "http://127.0.0.1:${address.port}$path"
    private fun HttpServer.shutdown() { stop(0); (executor as ExecutorService).shutdownNow() }
}
