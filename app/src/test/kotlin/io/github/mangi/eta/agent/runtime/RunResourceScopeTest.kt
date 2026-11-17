package io.github.mangi.eta.agent.runtime

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.capability.CapabilityHttpSession
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RunResourceScopeTest {
    @Test fun nestedHostCancellationInterruptsItsRealHttpBodyAndKeepsTheEnclosingRunUsable() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = java.util.concurrent.Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
            createContext("/blocked") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                try { exchange.responseBody.write('{'.code); exchange.responseBody.flush(); entered.countDown(); release.await(5, TimeUnit.SECONDS) }
                finally { exchange.close() }
            }
            createContext("/ready") { exchange -> val bytes = "ready".toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
            start()
        }
        val owner = AgentRunController(); val scope = RunResourceScope()
        CapabilityHttpSession(OkHttpClient()).use { http ->
            val task = async(Dispatchers.IO) {
                runCatching { RuntimeInvocationScope.withController(owner) { RuntimeInvocationScope.withResources(scope) {
                    runBlocking(RuntimeInvocationScope.coroutineContext()) {
                        http.executeSuspending(Request.Builder().url("http://127.0.0.1:${server.address.port}/blocked").build()) { it.body.string() }
                    }
                } } }
            }
            try {
                assertTrue(entered.await(3000, TimeUnit.MILLISECONDS)); scope.close()
                assertTrue(withTimeout(2000) { task.await() }.isFailure)
                assertFalse(owner.isCancelled)
                val next = RuntimeInvocationScope.withController(owner) {
                    http.execute(Request.Builder().url("http://127.0.0.1:${server.address.port}/ready").build()) { it.body.string() }
                }
                assertEquals("ready", next)
            } finally { scope.close(); task.cancel(); release.countDown(); server.stop(0); (server.executor as java.util.concurrent.ExecutorService).shutdownNow() }
        }
    }

    @Test fun closingAScopeEndsAnApprovalStylePauseWithoutCancellingTheParentRun() = runBlocking {
        val owner = AgentRunController().apply { pause() }
        val scope = RunResourceScope()
        val entered = CountDownLatch(1)
        val task = async(Dispatchers.IO) { runCatching { RuntimeInvocationScope.withController(owner) {
            RuntimeInvocationScope.withResources(scope) { entered.countDown(); owner.throwIfCancelled() }
        } } }
        try {
            assertTrue(entered.await(1000, TimeUnit.MILLISECONDS)); scope.close()
            assertTrue(withTimeout(1500) { task.await() }.exceptionOrNull() is AgentRunCancelledException)
            assertFalse(owner.isCancelled); assertTrue(owner.isPaused)
        } finally { owner.resume(); scope.close(); task.cancelAndJoin() }
    }
}
