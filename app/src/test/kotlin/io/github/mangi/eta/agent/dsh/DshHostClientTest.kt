package io.github.mangi.eta.agent.dsh

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.capability.NexusRunHosts
import io.github.mangi.eta.agent.connector.ConnectorConfigStore
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.fork.a2a.A2ASecretStore
import io.github.zhh_lgtm.nexus.application.api.InMemoryCapabilityDirectory
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DshHostClientTest {
    @Test fun actualLoginCookieCatalogScopedViewAndSingleInvokeUseThePublishedProtocol() = runBlocking {
        Fixture().use { fixture ->
            var saved: DshCredentials? = null
            DshHostClient(fixture.endpoint, DshCredentials(token = "fixture-token"), persistCredentials = { saved = it }).use { client ->
                val catalog = client.contributions()
                assertEquals("@fixture/reports", catalog.plugins.single().pkg)
                assertEquals(1, fixture.logins.get()); assertNotNull(saved!!.cookie)
                assertFalse(saved!!.json().contains("fixture-cookie=") && saved!!.cookie!!.startsWith("Bearer"))
                val view = client.view("@fixture/reports", "usage", JSONObject().put("sessionId", "actual-remote-session").put("unknown", "discard"))
                assertEquals(42, (view.data as JSONObject).getInt("cost"))
                assertEquals("actual-remote-session", fixture.lastSession)
                assertFalse(fixture.lastViewQuery.contains("unknown"))
                val item = catalog.plugins.single().contributions.single { it.id == "copy" }
                val result = client.invoke(item, JSONObject(), JSONObject(), null, null, null)
                assertEquals("copy", result.effects.values.single().getString("type"))
                assertEquals(1, fixture.invokes.get())
                assertTrue(fixture.lastInvoke.getBoolean("userGesture"))
                assertNull(fixture.authorization); assertNull(fixture.origin)
            }
            DshHostClient(fixture.endpoint, saved!!).use { it.contributions() }
            assertEquals(1, fixture.logins.get())
            fixture.rejectInvoke = true
            DshHostClient(fixture.endpoint, saved!!).use { client ->
                val item = client.contributions().plugins.single().contributions.single { it.id == "copy" }
                val result = runCatching { client.invoke(item, JSONObject(), JSONObject(), null, null, null) }
                assertEquals("DSH_AUTH_REQUIRED", (result.exceptionOrNull() as DshException).code)
            }
            assertEquals(2, fixture.invokes.get()); assertEquals(1, fixture.logins.get())
        }
    }

    @Test fun actualConfiguredHostEntersRunDirectoryAndRevokesOnManualCredentialChanges() = runBlocking {
        Fixture().use { fixture ->
            val context = RuntimeEnvironment.getApplication()
            val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
            val configs = ConnectorConfigStore(context, A2ASecretStore(context) { key })
            val repository = DshHostRepository(context, configs)
            val host = repository.save(null, "Reports", fixture.endpoint + "?token=fixture-token", "")
            assertFalse(host.endpoint.contains("token")); assertEquals(1L, host.credentialRevision)
            val snapshot = DshRunSnapshot(listOf(host))
            NexusRunHosts(InMemoryCapabilityDirectory(emptyList()), snapshot.bindings(context, repository)).use { directory ->
                val name = DshRunSnapshot.name(host.id, "view")
                assertEquals(HostKind.DSH, directory.describe(name)!!.hostKind)
                assertTrue(directory.list("Reports").any { it.id == name })
                val result = directory.execute(AgentModelClient.ToolCall("read", name, """{"pkg":"@fixture/reports","id":"usage"}"""))
                assertEquals(42, JSONObject(result.content).getJSONObject("data").getInt("cost"))
                assertTrue(directory.isAvailable(name))
                assertEquals(1L, configs.load().single().credentialRevision)
                assertTrue(configs.exportCredentials()[host.id]!!.contains("dsh-auth-"))
                configs.updateToken(host.id, DshCredentials(token = "replacement").json())
                assertFalse(directory.isAvailable(name))
                assertEquals("HOST_TOOL_UNAVAILABLE", JSONObject(directory.execute(AgentModelClient.ToolCall("later", name, "{}")).content).getString("code"))
            }
            val refreshed = configs.load().single()
            repository.setEnabled(refreshed, false)
            assertFalse(repository.isCurrent(refreshed))
            repository.delete(refreshed)
            assertTrue(configs.load().isEmpty())
            assertThrows(IllegalStateException::class.java) { configs.tokenOrThrow(refreshed) }
            Unit
        }
    }

    @Test fun cancellingARealHttpBodyDoesNotWaitForTheHostTimeout() = runBlocking {
        Fixture().use { fixture ->
            fixture.blockCatalog = true
            DshHostClient(fixture.endpoint, DshCredentials(token = "fixture-token")).use { client ->
                val task = async(Dispatchers.IO) { client.contributions() }
                try {
                    assertTrue(fixture.catalogEntered.await(3000, java.util.concurrent.TimeUnit.MILLISECONDS))
                    withTimeout(2000) { task.cancelAndJoin() }
                } finally { task.cancel(); fixture.release.countDown() }
            }
        }
    }

    @Test fun actualUiInvokeFiltersMessageContextAndExpiresTheOriginalTapWithoutSending() = runBlocking {
        Fixture().use { fixture ->
            val context = RuntimeEnvironment.getApplication()
            val configs = ConnectorConfigStore(context, A2ASecretStore(context) { SecretKeySpec(ByteArray(32), "AES") })
            val repository = DshHostRepository(context, configs)
            val host = repository.save(null, "Messages", fixture.endpoint + "?token=fixture-token", "")
            val contribution = DshContribution("@fixture/reports", "message", "message.actions", "Copy", "copy", true, false, null, null, JSONObject())
            val plugin = DshPlugin(contribution.pkg, "Messages", "1", "ok", setOf("message", "draft", "clipboard"), listOf(contribution))
            DshUiSession(repository, host).use { session ->
                session.invokeFromTap(plugin, contribution, sharedContext = JSONObject().put("message", "Selected answer").put("draft", "Private draft").put("clipboard", "Private clipboard"))
                assertEquals("Selected answer", fixture.lastInvoke.getJSONObject("context").getString("message"))
                assertEquals(1, fixture.lastInvoke.getJSONObject("context").length())
                assertEquals(0, fixture.lastInvoke.getJSONObject("scope").length())
                val expired = runCatching { session.invokeFromTap(plugin, contribution, tappedAtNanos = System.nanoTime() - 4_000_000_000L) }
                assertEquals("DSH_TAP_TIMEOUT", (expired.exceptionOrNull() as DshException).code)
                assertEquals(1, fixture.invokes.get())
            }
        }
    }

    @Test fun explicitRemoteSessionBindingsSurviveCatalogRefreshAndRemainIsolatedByHostAndConversationKind() = runBlocking {
        Fixture().use { fixture ->
            val context = RuntimeEnvironment.getApplication()
            val configs = ConnectorConfigStore(context, A2ASecretStore(context) { SecretKeySpec(ByteArray(32), "AES") })
            val repository = DshHostRepository(context, configs)
            val host = repository.save(null, "Linked", fixture.endpoint + "?token=fixture-token", "")
            val other = repository.save(null, "Other", fixture.endpoint + "?token=fixture-token", "")
            val chat = io.github.mangi.eta.data.model.ConversationSubject.chat("same")
            val meeting = io.github.mangi.eta.data.model.ConversationSubject.meeting("same")
            repository.bindConversation(host, chat.key, "actual-chat-session")
            repository.bindConversation(host, meeting.key, "actual-meeting-session")
            repository.bindConversation(other, chat.key, "different-host-session")
            repository.refresh(host)
            assertTrue("files" in repository.cached(host)!!.plugins.single().capabilities)
            assertEquals("actual-chat-session", repository.conversationScope(host, chat.key).getString("sessionId"))
            assertEquals("actual-meeting-session", repository.conversationScope(host, meeting.key).getString("sessionId"))
            assertEquals("different-host-session", repository.conversationScope(other, chat.key).getString("sessionId"))
            val name = DshRunSnapshot.name(host.id, "view")
            NexusRunHosts(InMemoryCapabilityDirectory(emptyList()), DshRunSnapshot(listOf(host), chat.key).bindings(context, repository)).use { directory ->
                directory.execute(AgentModelClient.ToolCall("linked", name, """{"pkg":"@fixture/reports","id":"usage"}"""))
                assertEquals("actual-chat-session", fixture.lastSession)
                repository.bindConversation(host, chat.key, "changed-session")
                assertFalse(directory.isAvailable(name))
            }
            repository.bindConversation(host, chat.key, "")
            assertEquals(0, repository.conversationScope(host, chat.key).length())
            assertThrows(IllegalArgumentException::class.java) { repository.bindConversation(host, chat.key, "remote\u0000invalid") }
            configs.updateToken(host.id, DshCredentials(token = "fixture-token").json())
            assertEquals(0, repository.conversationScope(configs.load().single { it.id == host.id }, meeting.key).length())
        }
    }

    @Test fun anInputChangeCarriesNoUserGestureAndCannotTurnAResponseIntoClipboardAccess() = runBlocking {
        Fixture().use { fixture ->
            val context = RuntimeEnvironment.getApplication()
            val repository = DshHostRepository(context, ConnectorConfigStore(context, A2ASecretStore(context) { SecretKeySpec(ByteArray(32), "AES") }))
            val host = repository.save(null, "Forms", fixture.endpoint + "?token=fixture-token", "")
            val contribution = DshContribution("@fixture/reports", "form", "panels", "Form", "", true, false, null, null, JSONObject())
            val plugin = DshPlugin(contribution.pkg, "Forms", "1", "ok", emptySet(), listOf(contribution), setOf("files"))
            DshUiSession(repository, host).use { session ->
                val reply = session.invokeFromTap(plugin, contribution, action = "changed", value = JSONObject().put("value", "edited"), userGesture = false)
                assertFalse(fixture.lastInvoke.getBoolean("userGesture")); assertTrue(reply.values.isEmpty()); assertNull(reply.gestureDeadlineNanos)
            }
        }
    }

    private class Fixture : AutoCloseable {
        val logins = AtomicInteger(); val invokes = AtomicInteger()
        val catalogEntered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        var rejectInvoke = false; var blockCatalog = false
        var authorization: String? = null; var origin: String? = null
        var lastSession = ""; var lastViewQuery = ""; var lastInvoke = JSONObject()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = java.util.concurrent.Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
            createContext("/") { exchange ->
                authorization = exchange.requestHeaders.getFirst("Authorization"); origin = exchange.requestHeaders.getFirst("Origin")
                val path = exchange.requestURI.path
                if (path == "/") {
                    if (exchange.requestURI.rawQuery == "token=fixture-token") {
                        logins.incrementAndGet(); exchange.responseHeaders.set("Set-Cookie", "dsh-auth-fixture=v1.fixture.signature; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict")
                        exchange.responseHeaders.set("Location", "./"); exchange.sendResponseHeaders(303, -1)
                    } else exchange.sendResponseHeaders(401, -1)
                    exchange.close(); return@createContext
                }
                if (exchange.requestHeaders.getFirst("Cookie") != "dsh-auth-fixture=v1.fixture.signature") {
                    exchange.sendResponseHeaders(401, -1); exchange.close(); return@createContext
                }
                val body = when {
                    path == "/dsh-go/v1/contributions" -> {
                        if (blockCatalog) {
                            exchange.sendResponseHeaders(200, 0)
                            try { exchange.responseBody.write('{'.code); exchange.responseBody.flush(); catalogEntered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                            finally { exchange.close() }
                            return@createContext
                        }
                        """{"v":1,"bridge":"0.4.0","rev":"catalog-1","plugins":[{"pkg":"@fixture/reports","title":"Reports","version":"1","state":"ok","native":true,"wants":[],"capabilities":["files"],"contributes":{"panels":[{"id":"usage","title":"Usage","ready":true,"live":true}],"composer.actions":[{"id":"copy","label":"Copy","ready":true}]}}]}"""
                    }
                    path == "/dsh-go/v1/views/@fixture/reports/usage" -> {
                        lastViewQuery = exchange.requestURI.rawQuery
                        lastSession = lastViewQuery.split('&').firstOrNull { it.startsWith("sessionId=") }?.substringAfter('=').orEmpty()
                        """{"v":1,"pkg":"@fixture/reports","id":"usage","title":"Usage","view":{"type":"metric","label":"Cost","value":{"$":"data.cost"}},"data":{"cost":42},"rev":"data-1","live":true}"""
                    }
                    path == "/dsh-go/v1/invoke" -> {
                        invokes.incrementAndGet(); lastInvoke = JSONObject(exchange.requestBody.bufferedReader().readText())
                        if (rejectInvoke) { exchange.sendResponseHeaders(401, -1); exchange.close(); return@createContext }
                        """{"v":1,"effects":[{"type":"copy","text":"report"}]}"""
                    }
                    else -> { exchange.sendResponseHeaders(404, -1); exchange.close(); return@createContext }
                }.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val endpoint = "http://127.0.0.1:${server.address.port}/"
        override fun close() { release.countDown(); server.stop(0); (server.executor as java.util.concurrent.ExecutorService).shutdownNow() }
    }
}
