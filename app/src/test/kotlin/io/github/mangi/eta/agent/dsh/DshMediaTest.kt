package io.github.mangi.eta.agent.dsh

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.connector.ConnectorConfigStore
import io.github.mangi.eta.agent.voice.BargeInMonitor
import io.github.mangi.eta.agent.voice.BargeInMonitorStatus
import io.github.mangi.eta.agent.voice.pcmToWav
import io.github.mangi.eta.fork.a2a.A2ASecretStore
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DshMediaTest {
    @Test fun actualMediaHttpDownloadFeedsTheOwnerAndPauseResumeAndNaturalCompletionReleaseItOnce() = runBlocking {
        MediaFixture().use { fixture ->
            fixture.session().use { session ->
                val connection = launch { session.maintainConnection() }
                val backend = TestMediaBackend()
                val controller = fixture.controller(this, backend)
                controller.surface(Any(), "chat", true)
                try {
                    val plugin = fixture.ready(session)
                    val effects = session.invokeFromTap(plugin, plugin.contributions.single { it.id == "speak" })
                    assertEquals(3, effects.values.size)
                    controller.apply("chat", null, session, plugin, JSONObject(), effects.values[0], effects) { true }
                    withTimeout(3000) { controller.state.first { it.audio == "playing" } }
                    assertEquals(1, fixture.downloads); assertTrue(fixture.lastQuery.contains("key=reply")); assertFalse(fixture.lastQuery.contains("token"))
                    assertTrue(fixture.cookieSeen)
                    assertTrue(backend.output.file!!.isFile); assertArrayEquals(fixture.audio, backend.output.file!!.readBytes())
                    controller.pauseOrResume(); assertEquals("paused", controller.state.value.audio); assertEquals(1, backend.output.pauses)
                    controller.pauseOrResume(); assertEquals("playing", controller.state.value.audio); assertEquals(2, backend.output.starts)
                    backend.output.ended()
                    assertTrue(backend.output.closed); assertFalse(backend.output.file!!.exists()); assertEquals("", controller.state.value.surface)
                    val events = fixture.socket.events()
                    assertEquals(listOf("audio.ended"), events.map { it.getString("event") })
                    backend.output.ended(); assertEquals(1, fixture.socket.events().size)
                    assertTrue(backend.leaseClosed)
                } finally { controller.stop(); connection.cancelAndJoin() }
            }
        }
    }

    @Test fun speechStartStopsLocalAudioBeforeNotifyingTheHostAndSpeechEndClosesTheRemainingRoles() = runBlocking {
        MediaFixture().use { fixture ->
            fixture.session().use { session ->
                val connection = launch { session.maintainConnection() }
                val backend = TestMediaBackend(); val controller = fixture.controller(this, backend)
                controller.surface(Any(), "chat", true)
                try {
                    val plugin = fixture.ready(session)
                    fixture.repository.setVoiceAllowed(fixture.host, plugin.pkg, true)
                    val effects = session.invokeFromTap(plugin, plugin.contributions.single { it.id == "speak" })
                    effects.values.forEach { controller.apply("chat", null, session, plugin, JSONObject(), it, effects) { true } }
                    withTimeout(3000) { controller.state.first { it.audio == "playing" && it.overlay && it.caption == "字幕更新" && it.listening } }
                    assertEquals(.5, controller.state.value.progress!!, 0.0)
                    assertTrue(backend.echoRequired)
                    fixture.socket.beforeEvent = { frame -> if (frame.optString("event") == "voice.speechStart") assertTrue("Audio must already be stopped", backend.output.closed) }
                    backend.voice.trigger()
                    assertTrue(backend.output.closed); assertNull(controller.state.value.audio); assertFalse(controller.state.value.overlay)
                    assertFalse(fixture.socket.events().any { it.optString("event") == "audio.stopped" })
                    assertTrue(fixture.socket.events().any { it.optString("event") == "voice.speechStart" })
                    backend.voice.speechEnded()
                    assertTrue(backend.voice.stopped); assertEquals("", controller.state.value.surface)
                    val events = fixture.socket.events()
                    assertTrue(events.map { it.getString("event") }.containsAll(listOf("voice.speechEnd", "voice.stopped", "overlay.closed")))
                    assertEquals(events.map { it.getLong("seq") }.sorted().distinct(), events.map { it.getLong("seq") })
                } finally { controller.stop(); connection.cancelAndJoin() }
            }
        }
    }

    @Test fun revocationAndBackgroundCloseImmediatelyAndCancelledDownloadsNeverStartAPlayer() = runBlocking {
        MediaFixture().use { fixture ->
            fixture.session().use { session ->
                val connection = launch { session.maintainConnection() }
                val backend = TestMediaBackend(); val controller = fixture.controller(this, backend)
                val token = Any(); controller.surface(token, "chat", true)
                try {
                    val plugin = fixture.ready(session)
                    fixture.repository.setVoiceAllowed(fixture.host, plugin.pkg, true)
                    val effects = session.invokeFromTap(plugin, plugin.contributions.single { it.id == "speak" })
                    controller.apply("chat", null, session, plugin, JSONObject(), effects.values[1], effects) { true }
                    assertTrue(controller.state.value.listening)
                    fixture.repository.setVoiceAllowed(fixture.host, plugin.pkg, false)
                    withTimeout(1000) { controller.state.first { it.surface.isEmpty() } }
                    assertTrue(backend.voice.stopped)
                    fixture.block = true
                    controller.apply("chat", null, session, plugin, JSONObject(), effects.values[0], effects) { true }
                    assertTrue(withContext(Dispatchers.IO) { fixture.entered.await(2, TimeUnit.SECONDS) })
                    controller.surface(token, "chat", false)
                    fixture.release.countDown(); yield()
                    assertEquals("", controller.state.value.surface); assertEquals(0, backend.output.starts)
                } finally { controller.stop(); fixture.release.countDown(); connection.cancelAndJoin() }
            }
        }
    }

    @Test fun microChannelIsReferenceCountedAndRejectsOldEpochSequenceAndInvalidModels() = runBlocking {
        MediaFixture().use { fixture ->
            fixture.session().use { session ->
                val connection = launch { session.maintainConnection() }
                try {
                    fixture.ready(session)
                    val stream = session.stream.value!!
                    val one = stream.subscribeMicro("media-plugin", "caption", JSONObject())
                    val two = stream.subscribeMicro("media-plugin", "caption", JSONObject())
                    assertEquals(one, two); assertEquals(1, fixture.socket.sent.count { it.optString("t") == "micro.sub" })
                    fixture.socket.micro(one, "1111111111111111.2", JSONObject().put("text", "latest"))
                    fixture.socket.micro(one, "1111111111111111.1", JSONObject().put("text", "old"))
                    fixture.socket.micro(one, "2222222222222222.3", JSONObject().put("text", "different epoch"))
                    fixture.socket.micro(one, "1111111111111111.3", JSONObject().put("progress", 2))
                    assertEquals("latest", (stream.microFeeds.value[one]!!.data as JSONObject).getString("text"))
                    stream.unsubscribeMicro(one); assertTrue(stream.microFeeds.value.containsKey(two))
                    stream.unsubscribeMicro(two); assertFalse(stream.microFeeds.value.containsKey(one))
                    assertEquals(1, fixture.socket.sent.count { it.optString("t") == "micro.unsub" })
                } finally { connection.cancelAndJoin() }
            }
        }
    }

    @Test fun mediaFormatErrorsAndForbiddenStaticDeferredOrGesturelessCapabilitiesAreRejected() = runBlocking {
        MediaFixture().use { fixture ->
            fixture.mime = "text/html"
            fixture.session().use { session ->
                val connection = launch { session.maintainConnection() }
                val backend = TestMediaBackend(); val controller = fixture.controller(this, backend)
                controller.surface(Any(), "chat", true)
                try {
                    val plugin = fixture.ready(session); val effects = session.invokeFromTap(plugin, plugin.contributions.single { it.id == "speak" })
                    controller.apply("chat", null, session, plugin, JSONObject(), effects.values[0], effects) { true }
                    withTimeout(2000) { controller.state.first { it.surface.isEmpty() } }
                    assertEquals(0, backend.output.starts)
                    assertEquals("audio.error", fixture.socket.events().single().getString("event"))
                    assertTrue(DshProtocol.effects(JSONArray(effects.values), static = true, features = DshProtocol.supportedFeatures).values.isEmpty())
                    assertTrue(DshProtocol.effects(JSONArray(effects.values), deferred = true, features = DshProtocol.supportedFeatures).values.isEmpty())
                    val changes = session.client.invoke(plugin.contributions.first(), JSONObject(), JSONObject(), session.stream.value!!.connectionId, null, null,
                        features = DshProtocol.supportedFeatures, userGesture = false)
                    assertTrue(changes.effects.values.isEmpty())
                } finally { controller.stop(); connection.cancelAndJoin() }
            }
        }
    }

    @Test fun shortAudioCannotBeResurrectedByItsLaterVoiceAndOverlayEffectsAndEventSequencesStayMonotonic() = runBlocking {
        MediaFixture().use { fixture -> fixture.session().use { session ->
            val connection = launch { session.maintainConnection() }; val backend = TestMediaBackend(); val controller = fixture.controller(this, backend)
            controller.surface(Any(), "chat", true)
            try {
                val plugin = fixture.ready(session); val effects = session.invokeFromTap(plugin, plugin.contributions.single { it.id == "speak" })
                controller.apply("chat", null, session, plugin, JSONObject(), effects.values[0], effects) { error("must not ask") }
                withTimeout(2000) { controller.state.first { it.audio == "playing" } }
                backend.output.ended()
                effects.values.drop(1).forEach { controller.apply("chat", null, session, plugin, JSONObject(), it, effects) { error("audio already ended") } }
                assertEquals("", controller.state.value.surface)
                val events = fixture.socket.events()
                assertEquals(listOf("audio.ended", "voice.stopped", "overlay.closed"), events.map { it.getString("event") })
                assertEquals(listOf(1L, 2L, 3L), events.map { it.getLong("seq") })
            } finally { controller.stop(); connection.cancelAndJoin() }
        } }
    }

    @Test fun anExpiredOrAbortedCapabilityBatchRetiresItsUnstartedRolesWithoutStartingResources() = runBlocking {
        MediaFixture().use { fixture -> fixture.session().use { session ->
            val connection = launch { session.maintainConnection() }; val backend = TestMediaBackend(); val controller = fixture.controller(this, backend)
            controller.surface(Any(), "chat", true)
            try {
                val plugin = fixture.ready(session); val original = session.invokeFromTap(plugin, plugin.contributions.single { it.id == "speak" })
                val expired = original.copy(gestureDeadlineNanos = System.nanoTime() - 1)
                DshEffectRouter.apply(expired, object : DshEffectTarget {
                    override suspend fun capability(effect: JSONObject, origin: DshEffects) = error("must not start")
                    override fun discardCapability(effect: JSONObject, origin: DshEffects) = controller.discard(session, plugin, effect, origin)
                    override suspend fun selectFiles(effect: JSONObject, origin: DshEffects): DshEffects = error("must not select")
                    override fun notice(text: String) = Unit
                    override fun copy(text: String) = Unit
                    override suspend fun insert(text: String) = false
                    override fun command(name: String, args: String) = false
                    override fun link(url: String) = Unit
                    override suspend fun open(page: String) = false
                    override suspend fun refresh(id: String) = Unit
                    override fun patch(id: String, data: JSONObject) = Unit
                })
                assertEquals(0, backend.output.starts); assertEquals("", controller.state.value.surface)
                assertEquals(listOf("audio.stopped", "voice.error", "overlay.closed"), fixture.socket.events().map { it.getString("event") })
                assertEquals(listOf(1L, 2L, 3L), fixture.socket.events().map { it.getLong("seq") })
            } finally { controller.stop(); connection.cancelAndJoin() }
        } }
    }
}

internal class MediaFixture : AutoCloseable {
    val context get() = RuntimeEnvironment.getApplication()
    val configs = ConnectorConfigStore(context, A2ASecretStore(context) { SecretKeySpec(ByteArray(32), "AES") })
    val repository = DshHostRepository(context, configs)
    val audio = pcmToWav(ByteArray(1600))
    var mime = "audio/wav"; var downloads = 0; var lastQuery = ""; var cookieSeen = false; var block = false
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    lateinit var socket: MediaSocket
    val lease = "L".repeat(32)
    val catalog = """{"v":1,"plugins":[{"pkg":"media-plugin","title":"朗读插件","version":"1","state":"ok","capabilities":["audio","voice","micro","overlay"],"contributes":{
        "message.actions":[{"id":"speak","label":"朗读","ready":true,"requires":["audio"]}],"media":[{"id":"speech"}],"micro":[{"id":"caption"}],
        "overlays":[{"id":"subtitle","channel":"caption","text":"加载字幕"}],"panels":[{"id":"now","title":"Now","ready":true,"micro":"caption"}]
    }}]}"""
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = java.util.concurrent.Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        createContext("/") { request ->
            val path = request.requestURI.path
            if (path == "/") {
                request.responseHeaders.set("Set-Cookie", "dsh-auth-media=signed; Max-Age=2592000; Path=/; HttpOnly")
                request.sendResponseHeaders(303, -1); request.close(); return@createContext
            }
            if (request.requestHeaders.getFirst("Cookie") != "dsh-auth-media=signed") { request.sendResponseHeaders(401, -1); request.close(); return@createContext }
            val body = when {
                path.endsWith("/contributions") -> catalog.toByteArray()
                path.endsWith("/invoke") -> """{"v":1,"effects":[{"type":"audio","op":"play","instance":"reader","lease":"$lease","src":"media:speech","key":"reply"},
                    {"type":"voice","op":"start","instance":"reader","lease":"$lease"},{"type":"overlay","op":"show","instance":"reader","lease":"$lease","id":"subtitle"}]}""".toByteArray()
                path.contains("/media/") -> { downloads++; lastQuery = request.requestURI.rawQuery; cookieSeen = true; request.responseHeaders.set("Content-Type", mime)
                    if (block) { request.sendResponseHeaders(200, 0); request.responseBody.write(1); request.responseBody.flush(); entered.countDown(); release.await(3, TimeUnit.SECONDS); request.close(); return@createContext }
                    audio
                }
                else -> "{}".toByteArray()
            }
            request.sendResponseHeaders(200, body.size.toLong()); request.responseBody.use { it.write(body) }
        }
        start()
    }
    val host = repository.save(null, "Media", "http://127.0.0.1:${server.address.port}/?token=fixture", "")
    fun session() = DshUiSession(repository, host) { client -> DshStreamClient(client) { request, listener -> MediaSocket(request, listener).also { socket = it; it.open() } } }
    suspend fun ready(session: DshUiSession): DshPlugin { withTimeout(2000) { session.stream.first { it != null }; session.catalog.first { it != null } }; return session.catalog.value!!.plugins.single() }
    fun controller(scope: CoroutineScope, backend: TestMediaBackend) = DshMediaController(context, scope, backend, repository,
        acquire = { AutoCloseable { } })
    override fun close() { release.countDown(); server.stop(0); (server.executor as java.util.concurrent.ExecutorService).shutdownNow() }
}

internal class MediaSocket(private val request: Request, private val listener: WebSocketListener) : WebSocket {
    val sent = CopyOnWriteArrayList<JSONObject>()
    var beforeEvent: (JSONObject) -> Unit = {}
    fun events() = sent.filter { it.optString("t") == "cap.event" }
    fun open() = listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching").build())
    fun micro(sub: String, revision: String, data: JSONObject) = listener.onMessage(this, JSONObject().put("t", "micro.data").put("sub", sub).put("rev", revision).put("data", data).toString())
    override fun request() = request
    override fun queueSize() = 0L
    override fun send(text: String): Boolean {
        val message = JSONObject(text); sent += message
        if (message.optString("t") == "hello") listener.onMessage(this, """{"t":"welcome","v":1,"conn":"media-stream","hb":25,"features":["audio","voice","micro","overlay"]}""")
        if (message.optString("t") == "micro.sub") micro(message.getString("sub"), "1111111111111111.1", JSONObject().put("text", "字幕更新").put("progress", .5))
        if (message.optString("t") == "cap.event") beforeEvent(message)
        return true
    }
    override fun send(bytes: ByteString) = false
    override fun close(code: Int, reason: String?) = true
    override fun cancel() = Unit
}

internal class TestMediaBackend : DshMediaBackend {
    val output = TestMediaOutput()
    val voice = TestMediaMonitor()
    var leaseClosed = false; var echoRequired = false
    var interrupted: () -> Unit = {}
    override fun lease(interrupted: () -> Unit): DshMediaAudioLease { this.interrupted = interrupted; return object : DshMediaAudioLease {
        override fun playbackFocus() = Unit
        override fun close() { leaseClosed = true }
    } }
    override fun output(): DshMediaOutput = output
    override fun voice(requireEchoCancellation: Boolean, playing: () -> Boolean, ended: () -> Unit, failed: (String) -> Unit): BargeInMonitor {
        echoRequired = requireEchoCancellation; voice.speechEnded = ended; return voice
    }
}
internal class TestMediaOutput : DshMediaOutput {
    var starts = 0; var pauses = 0; var closed = false; var file: File? = null
    var ended: () -> Unit = {}
    override suspend fun prepare(file: File, ended: () -> Unit, failed: () -> Unit) { this.file = file; this.ended = ended }
    override fun play() { starts++ }
    override fun pause() { pauses++ }
    override fun close() { closed = true }
}
internal class TestMediaMonitor : BargeInMonitor {
    var trigger: () -> Unit = {}; var speechEnded: () -> Unit = {}; var stopped = false
    override fun start(onArmed: (BargeInMonitorStatus) -> Unit, onLevel: (Float) -> Unit, onTriggered: () -> Unit) { trigger = onTriggered; onArmed(BargeInMonitorStatus(true, "AEC")) }
    override fun stop() { stopped = true }
    override suspend fun awaitStopped(timeoutMs: Long) = Unit
}
