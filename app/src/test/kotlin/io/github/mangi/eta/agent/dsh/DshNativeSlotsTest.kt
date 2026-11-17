package io.github.mangi.eta.agent.dsh

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.connector.ConnectorConfigStore
import io.github.mangi.eta.agent.connector.SavedConnectorConfig
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.fork.a2a.A2ASecretStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DshNativeSlotsTest {
    @Test fun actualFeedOwnerReadsNativeContributionsAndKeepsRemoteNotesOnOnlyTheirExplicitLocalReply() = runBlocking {
        NativeSlotsFixture().use { fixture ->
            val subject = ConversationSubject.chat("local-conversation")
            fixture.repository.bindConversation(fixture.host, subject.key, "remote-session", "reply", "remote-turn-5")
            val owner = DshConversationFeeds(fixture.context, subject.key, fixture.repository) { fixture.lease(it, this) }
            val task = launch { owner.maintain() }
            try {
                val current = withTimeout(3000) { owner.snapshot.first { it.notes[DshHostRepository.localMessageKey("reply")]?.isNotEmpty() == true } }
                assertEquals("复制摘要", current.actions.single().title)
                assertEquals("状态 remote-session", current.strips.single().text)
                assertEquals("已确认", current.notes.getValue(DshHostRepository.localMessageKey("reply")).single().text)
                assertTrue(current.notes[DshHostRepository.localMessageKey("unrelated")].isNullOrEmpty())
                assertTrue(current.online.contains(fixture.host.id))
                assertTrue(fixture.sockets.single().sent.filter { it.optString("t") == "sub" }.all {
                    it.getJSONObject("scope").getString("sessionId") == "remote-session"
                })
                fixture.sockets.single().clearStrip()
                withTimeout(1000) { owner.snapshot.first { it.strips.isEmpty() && it.notes.isNotEmpty() } }
                fixture.repository.bindConversation(fixture.host, subject.key, "new-remote-session")
                owner.reload()
                withTimeout(3000) { owner.snapshot.first { it.notes.isEmpty() && it.strips.any { row -> row.text == "状态 new-remote-session" } } }
                assertEquals(1, fixture.sockets.size)
                assertTrue(fixture.sockets.single().sent.any { it.optString("t") == "unsub" })
                fixture.sockets.single().disconnect()
                withTimeout(1000) { owner.snapshot.first { it.online.isEmpty() && it.notes.isEmpty() } }
                fixture.repository.setEnabled(fixture.host, false)
                withTimeout(1000) { owner.configured.first { !it } }
                fixture.repository.setEnabled(fixture.host, true)
                withTimeout(3000) { owner.snapshot.first { it.online.contains(fixture.host.id) && it.strips.isNotEmpty() } }
                assertTrue(fixture.sockets.size >= 2)
            } finally { task.cancelAndJoin() }
            assertTrue(owner.snapshot.value.notes.isEmpty()); assertTrue(fixture.sockets.all { it.cancelled })
        }
    }

    @Test fun sharedHostLeasesDoNotCloseEachOthersClientAndOldEpochReleaseCannotCloseTheNewHost() {
        NativeSlotsFixture().use { fixture ->
            val first = DshSharedSessions.acquire(fixture.context, fixture.repository, fixture.host)
            val second = DshSharedSessions.acquire(fixture.context, fixture.repository, fixture.host)
            assertSame(first.session, second.session)
            first.close(); first.close(); assertTrue(second.session.client.isAvailable())
            fixture.configs.updateToken(fixture.host.id, DshCredentials(token = "new-key").json())
            val updated = fixture.configs.load().single()
            val replacement = DshSharedSessions.acquire(fixture.context, fixture.repository, updated)
            assertNotSame(second.session, replacement.session); assertFalse(second.session.client.isAvailable())
            second.close(); assertTrue(replacement.session.client.isAvailable())
            replacement.close(); assertFalse(replacement.session.client.isAvailable())
        }
    }

    @Test fun savingAHostNameReplacesTheStaleSessionAndKeepsTheConversationFeedConnected() = runBlocking {
        NativeSlotsFixture().use { fixture ->
            val subject = ConversationSubject.chat("rename-host")
            fixture.repository.bindConversation(fixture.host, subject.key, "remote-session")
            val owner = DshConversationFeeds(fixture.context, subject.key, fixture.repository) { fixture.lease(it, this) }
            val task = launch { owner.maintain() }
            try {
                withTimeout(3000) { owner.snapshot.first { it.strips.isNotEmpty() && it.online.contains(fixture.host.id) } }
                val oldSocket = fixture.sockets.single()
                val renamed = fixture.configs.save(fixture.host.copy(name = "Renamed"))
                assertEquals(fixture.host.credentialRevision, renamed.credentialRevision)
                assertTrue(renamed.configurationRevision > fixture.host.configurationRevision)
                val updated = withTimeout(3000) { owner.snapshot.first { snapshot ->
                    snapshot.actions.any { it.target.host.configurationRevision == renamed.configurationRevision } &&
                        snapshot.online.contains(renamed.id) && snapshot.strips.isNotEmpty()
                } }
                assertEquals("Renamed", updated.actions.single().target.host.name)
                assertEquals("状态 remote-session", updated.strips.single().text)
                assertTrue(oldSocket.cancelled)
                assertEquals(2, fixture.sockets.size)
            } finally { task.cancelAndJoin() }
        }
    }

    @Test fun anEmptyHostCatalogDoesNotExposeConversationPluginControls() = runBlocking {
        NativeSlotsFixture().use { fixture ->
            fixture.emptyCatalog = true
            val owner = DshConversationFeeds(fixture.context, ConversationSubject.chat("empty").key, fixture.repository) { fixture.lease(it, this) }
            val task = launch { owner.maintain() }
            try {
                val state = withTimeout(3000) { owner.snapshot.first { it.online.contains(fixture.host.id) } }
                assertFalse(state.available); assertTrue(state.actions.isEmpty()); assertTrue(state.strips.isEmpty()); assertTrue(state.notes.isEmpty())
            } finally { task.cancelAndJoin() }
        }
    }

    @Test fun conversationAndTurnAssociationAreAtomicIsolatedAndClearedBySessionChanges() {
        NativeSlotsFixture().use { fixture ->
            val chat = ConversationSubject.chat("same"); val meeting = ConversationSubject.meeting("same")
            fixture.repository.bindConversation(fixture.host, chat.key, "chat-session", "reply", "turn-a")
            fixture.repository.bindConversation(fixture.host, meeting.key, "meeting-session", "reply", "turn-b")
            val message = DshHostRepository.localMessageKey("reply")
            assertEquals("turn-a", fixture.repository.messageTurns(fixture.host, chat.key)[message])
            assertEquals("turn-b", fixture.repository.messageTurns(fixture.host, meeting.key)[message])
            assertThrows(IllegalArgumentException::class.java) { fixture.repository.bindConversation(fixture.host, chat.key, "changed", "reply", "bad\u0000turn") }
            assertEquals("chat-session", fixture.repository.conversationScope(fixture.host, chat.key).getString("sessionId"))
            assertEquals("turn-a", fixture.repository.messageTurns(fixture.host, chat.key)[message])
            fixture.repository.bindConversation(fixture.host, chat.key, "new-chat-session")
            assertTrue(fixture.repository.messageTurns(fixture.host, chat.key).isEmpty())
            assertEquals("turn-b", fixture.repository.messageTurns(fixture.host, meeting.key)[message])
        }
    }

    @Test fun commonEffectRouterKeepsExpiredGesturesFromClipboardCommandsAndNavigation() = runBlocking {
        val events = mutableListOf<String>()
        val target = object : DshEffectTarget {
            override suspend fun selectFiles(effect: JSONObject, origin: DshEffects): DshEffects = error("must not choose")
            override fun notice(text: String) { events += "notice" }
            override fun copy(text: String) { events += "copy" }
            override suspend fun insert(text: String): Boolean { events += "insert"; return true }
            override fun command(name: String, args: String): Boolean { events += "command"; return true }
            override fun link(url: String) { events += "link" }
            override suspend fun open(page: String): Boolean { events += "open"; return true }
            override suspend fun refresh(id: String) { events += "refresh" }
            override fun patch(id: String, data: JSONObject) { events += "patch" }
        }
        val effects = DshEffects(listOf(JSONObject().put("type", "copy").put("text", "private"), JSONObject().put("type", "command").put("name", "stop"),
            JSONObject().put("type", "open").put("page", "settings"), JSONObject().put("type", "refresh"), JSONObject().put("type", "patch").put("data", JSONObject())),
            gestureDeadlineNanos = System.nanoTime() - 1)
        DshEffectRouter.apply(effects, target)
        assertEquals(listOf("refresh", "patch"), events)
        assertEquals("🙂🙂", DshConversationFeeds.singleLine("🙂🙂🙂\n", 2))
    }
}

internal class NativeSlotsFixture : AutoCloseable {
    val context get() = RuntimeEnvironment.getApplication()
    val configs = ConnectorConfigStore(context, A2ASecretStore(context) { SecretKeySpec(ByteArray(32), "AES") })
    val repository = DshHostRepository(context, configs)
    val sockets = CopyOnWriteArrayList<NativeSlotsSocket>()
    var emptyCatalog = false
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = java.util.concurrent.Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        createContext("/") { request ->
            val body = """{"v":1,"rev":"stable","plugins":[{"pkg":"native","title":"Native","version":"1","state":"ok","wants":[],"contributes":{
                "composer.actions":[{"id":"copy","label":"复制摘要","icon":"file","ready":true,"effect":{"type":"copy","text":"summary"}}],
                "composer.strip":[{"id":"status","text":"状态","icon":"chart","ready":true,"live":true,"tappable":true,"panel":"overview"}],
                "panels":[{"id":"overview","title":"Overview","ready":true}],"turn.note":[{"id":"notes","ready":true}]
            }}]}"""
            val bytes = (if (emptyCatalog) """{"v":1,"rev":"empty","plugins":[]}""" else body).toByteArray()
            request.sendResponseHeaders(200, bytes.size.toLong()); request.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val host = repository.save(null, "Fixture", "http://127.0.0.1:${server.address.port}/", "")
    fun lease(host: SavedConnectorConfig, parent: CoroutineScope): DshSharedSessions.Lease {
        var connected: Job? = null
        val session = DshUiSession(repository, host) { client -> DshStreamClient(client) { request, listener ->
            NativeSlotsSocket(request, listener).also { sockets += it; it.open() }
        } }
        return DshSharedSessions.Lease(session, onVisibility = { visible ->
            if (visible && connected?.isActive != true) connected = parent.launch { session.maintainConnection() } else if (!visible) connected?.cancel()
        }, release = { connected?.cancel(); session.close() })
    }
    override fun close() { server.stop(0); (server.executor as java.util.concurrent.ExecutorService).shutdownNow() }
}

internal class NativeSlotsSocket(private val request: Request, private val listener: WebSocketListener) : WebSocket {
    val sent = CopyOnWriteArrayList<JSONObject>()
    var cancelled = false
    fun open() = listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching").build())
    fun disconnect() = listener.onFailure(this, java.io.IOException("disconnected"), null)
    fun emit(message: JSONObject) = listener.onMessage(this, message.toString())
    fun clearStrip() {
        val sub = sent.last { it.optString("t") == "sub" && it.optString("id") == "status" }.getString("sub")
        listener.onMessage(this, JSONObject().put("t", "data").put("sub", sub).put("data", JSONObject().put("text", JSONObject.NULL)).put("rev", "2").toString())
    }
    override fun request() = request
    override fun queueSize() = 0L
    override fun send(text: String): Boolean {
        val message = JSONObject(text); sent += message
        if (message.optString("t") == "hello") listener.onMessage(this, """{"t":"welcome","v":1,"conn":"native-stream","hb":25}""")
        if (message.optString("t") == "sub") {
            val id = message.getString("id")
            val data = if (id == "notes") JSONObject().put("remote-turn-5", JSONObject().put("text", "已确认").put("tone", "good"))
                else JSONObject().put("text", "状态 " + message.getJSONObject("scope").optString("sessionId")).put("tone", "info")
            listener.onMessage(this, JSONObject().put("t", "data").put("sub", message.getString("sub")).put("data", data).put("rev", "1").toString())
        }
        return true
    }
    override fun send(bytes: ByteString) = false
    override fun close(code: Int, reason: String?) = true
    override fun cancel() { cancelled = true }
}
