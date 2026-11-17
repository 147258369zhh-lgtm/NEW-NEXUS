package io.github.mangi.eta.agent.dsh

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.connector.ConnectorConfigStore
import io.github.mangi.eta.fork.a2a.A2ASecretStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DshStreamAuthenticationTest {
    @Test fun anExpiredServerCookieIsClearedAndTheNextUpgradeLogsInOnlyToTheSameOrigin() = runBlocking {
        LoginFixture().use { fixture ->
            var saved: DshCredentials? = null
            val requests = CopyOnWriteArrayList<Request>()
            DshHostClient(fixture.endpoint, fixture.stale(), persistCredentials = { saved = it }).use { host ->
                DshStreamClient(host) { request, listener ->
                    requests += request; AuthenticationSocket(request, listener, 401).also { it.open() }
                }.use { rejected ->
                    val failure = runCatching { rejected.connect() }.exceptionOrNull() as DshException
                    assertEquals("DSH_AUTH_REQUIRED", failure.code)
                }
                assertNull(saved!!.cookie)
                assertEquals(0, fixture.logins.get())
                DshStreamClient(host) { request, listener ->
                    requests += request; AuthenticationSocket(request, listener).also { it.open() }
                }.use { renewed -> assertEquals("authenticated", renewed.connect()) }
                assertEquals(1, fixture.logins.get())
                assertEquals("dsh-auth-fixture=stale", requests[0].header("Cookie"))
                assertEquals("dsh-auth-fixture=fresh-1", requests[1].header("Cookie"))
                assertTrue(requests.all { it.url.toString() == fixture.endpoint + "dsh-go/v1/stream" && it.header("Authorization") == null && it.header("Origin") == null })
                assertEquals("dsh-auth-fixture=fresh-1", saved!!.cookie)
                assertTrue(runCatching {
                    host.rejectWebSocketAuthentication(Request.Builder().url("http://other.invalid/dsh-go/v1/stream").header("Cookie", saved!!.cookie!!).build())
                }.exceptionOrNull() is IllegalArgumentException)
                assertEquals("dsh-auth-fixture=fresh-1", host.webSocketRequest().header("Cookie"))
            }
        }
    }

    @Test fun aForbiddenUpgradeDoesNotDiscardAuthenticationOrAttemptToChangeOrigin() = runBlocking {
        LoginFixture().use { fixture ->
            var persisted = false
            DshHostClient(fixture.endpoint, fixture.stale(), persistCredentials = { persisted = true }).use { host ->
                DshStreamClient(host) { request, listener -> AuthenticationSocket(request, listener, 403).also { it.open() } }.use { stream ->
                    assertEquals("DSH_AUTH_FORBIDDEN", (runCatching { stream.connect() }.exceptionOrNull() as DshException).code)
                }
                assertFalse(persisted)
                assertEquals(0, fixture.logins.get())
                assertEquals("dsh-auth-fixture=stale", host.webSocketRequest().header("Cookie"))
            }
        }
    }

    @Test fun repeatedUnauthorizedUpgradesStopAfterOneRenewedCookieAttempt() = runBlocking {
        LoginFixture().use { fixture ->
            val context = RuntimeEnvironment.getApplication()
            val configs = ConnectorConfigStore(context, A2ASecretStore(context) { SecretKeySpec(ByteArray(32), "AES") })
            val repository = DshHostRepository(context, configs)
            val host = repository.save(null, "Authentication", fixture.endpoint, "fixture-token")
            configs.updateToken(host.id, fixture.stale().json(), identityChanged = false)
            val requests = CopyOnWriteArrayList<Request>()
            val thirdAttempt = CompletableDeferred<Unit>()
            DshUiSession(repository, host) { client -> DshStreamClient(client) { request, listener ->
                requests += request
                if (requests.size > 2) thirdAttempt.complete(Unit)
                AuthenticationSocket(request, listener, 401).also { it.open() }
            } }.use { session ->
                val exhausted = async(start = CoroutineStart.UNDISPATCHED) { session.notices.first { it.kind == "auth-required" } }
                val maintaining = launch { session.maintainConnection() }
                try {
                    withTimeout(4000) { exhausted.await() }
                    assertEquals(2, requests.size)
                    assertEquals(1, fixture.logins.get())
                    assertNull(DshCredentials.parse(configs.tokenOrThrow(host)).cookie)
                    // A third ordinary retry would happen after four seconds; authentication rejection stays stopped.
                    assertNull(withTimeoutOrNull(4300) { thirdAttempt.await() })
                    assertTrue(maintaining.isActive)
                } finally { maintaining.cancelAndJoin(); exhausted.cancel() }
            }
        }
    }

    private class LoginFixture : AutoCloseable {
        val logins = AtomicInteger()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = java.util.concurrent.Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
            createContext("/") { exchange ->
                if (exchange.requestURI.path == "/" && exchange.requestURI.rawQuery == "token=fixture-token") {
                    val value = logins.incrementAndGet()
                    exchange.responseHeaders.set("Set-Cookie", "dsh-auth-fixture=fresh-$value; Max-Age=3600; Path=/; HttpOnly; SameSite=Strict")
                    exchange.responseHeaders.set("Location", "/")
                    exchange.sendResponseHeaders(302, -1)
                } else exchange.sendResponseHeaders(401, -1)
                exchange.close()
            }
            start()
        }
        val endpoint = "http://127.0.0.1:${server.address.port}/"
        fun stale() = DshCredentials("fixture-token", "dsh-auth-fixture=stale", endpoint.removeSuffix("/"), System.currentTimeMillis() + 60_000)
        override fun close() { server.stop(0); (server.executor as java.util.concurrent.ExecutorService).shutdownNow() }
    }

    private class AuthenticationSocket(private val request: Request, private val listener: WebSocketListener, private val status: Int? = null) : WebSocket {
        fun open() {
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status ?: 101).message("Fixture").build()
            if (status == null) listener.onOpen(this, response) else listener.onFailure(this, java.io.IOException("Upgrade rejected"), response)
        }
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            if (text.contains("\"hello\"")) listener.onMessage(this, """{"t":"welcome","v":1,"conn":"authenticated","hb":25}""")
            return true
        }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }
}
