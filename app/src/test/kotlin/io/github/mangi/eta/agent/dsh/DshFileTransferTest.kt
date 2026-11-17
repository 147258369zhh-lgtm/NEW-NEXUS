package io.github.mangi.eta.agent.dsh

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.connector.ConnectorConfigStore
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
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DshFileTransferTest {
    @Test fun actualSelectionReadsOnlyPickedContentAndCompletesTheHostFlowOnce() = runBlocking {
        Fixture().use { fixture ->
            fixture.session().use { session ->
                val connection = launch { session.maintainConnection() }
                try {
                    withTimeout(2000) { session.stream.first { it != null } }
                    val plugin = fixture.plugin(session)
                    val tap = session.invokeFromTap(plugin, plugin.contributions.single())
                    assertEquals("files", fixture.socket.sent.first().getJSONArray("features").getString(0))
                    val provider = fixture.provider()
                    provider.source.writeText("selected contents")
                    val reader = DshPickedFileReader(fixture.context)
                    val result = session.selectFiles(plugin, tap.values.single(), tap,
                        select = { images, maximum -> assertFalse(images); assertEquals(2, maximum); listOf(fixture.uri) },
                        read = reader::read, stillCurrent = { true })
                    assertEquals("toast", result.values.single().getString("type"))
                    assertEquals("selected contents", fixture.uploads.single().toString(Charsets.UTF_8))
                    assertEquals(listOf("selected"), fixture.completions)
                    assertEquals("report.txt", fixture.lastName)
                    assertEquals("selected contents", provider.source.readText())
                    assertEquals(listOf("r"), provider.modes)
                    assertFalse(session.fileHold.value); assertNull(session.fileProgress.value)
                    assertTrue(runCatching { session.selectFiles(plugin, tap.values.single(), tap, { _, _ -> error("must not repick") }, reader::read) { true } }.isFailure)
                    assertEquals(1, fixture.uploads.size)
                    assertEquals(listOf("selected"), fixture.completions)
                } finally { connection.cancelAndJoin() }
            }
        }
    }

    @Test fun cancellingAChooserDoesNotUploadItsLateResultOrReplayAnUnknownCompletion() = runBlocking {
        Fixture().use { fixture ->
            fixture.session().use { session ->
                val connection = launch { session.maintainConnection() }
                try {
                    withTimeout(2000) { session.stream.first { it != null } }
                    val plugin = fixture.plugin(session)
                    val tap = session.invokeFromTap(plugin, plugin.contributions.single())
                    val selection = CompletableDeferred<List<Uri>>()
                    val task = async { session.selectFiles(plugin, tap.values.single(), tap, { _, _ -> selection.await() }, { _, _ -> error("must not read") }) { true } }
                    yield(); assertTrue(session.fileHold.value)
                    session.cancelCurrent(); task.cancelAndJoin()
                    selection.complete(listOf(fixture.uri))
                    assertTrue(fixture.uploads.isEmpty()); assertEquals(listOf("cancelled"), fixture.completions)
                    assertFalse(session.fileHold.value)
                } finally { connection.cancelAndJoin() }
            }
        }
        Fixture().use { fixture ->
            fixture.failCompletion = true
            fixture.session().use { session ->
                val connection = launch { session.maintainConnection() }
                try {
                    withTimeout(2000) { session.stream.first { it != null } }
                    val plugin = fixture.plugin(session); val tap = session.invokeFromTap(plugin, plugin.contributions.single())
                    assertTrue(runCatching { session.selectFiles(plugin, tap.values.single(), tap, { _, _ -> emptyList() }, { _, _ -> error("must not read") }) { true } }.isFailure)
                    assertEquals(listOf("cancelled"), fixture.completions)
                } finally { connection.cancelAndJoin() }
            }
        }
    }

    @Test fun aBrokenUploadSettlesTheWholeBatchAsErrorAndDisconnectInvalidatesAnOpenChooser() = runBlocking {
        Fixture().use { fixture ->
            fixture.badReceipt = true
            fixture.session().use { session ->
                val connection = launch { session.maintainConnection() }
                try {
                    withTimeout(2000) { session.stream.first { it != null } }
                    val plugin = fixture.plugin(session); val tap = session.invokeFromTap(plugin, plugin.contributions.single())
                    val temporary = File(fixture.context.cacheDir, "fixture-upload").apply { writeText("data") }
                    assertTrue(runCatching { session.selectFiles(plugin, tap.values.single(), tap, { _, _ -> listOf(fixture.uri) },
                        { _, _ -> DshPickedFile(temporary, "fixture.txt", temporary.length()) }) { true } }.isFailure)
                    assertFalse(temporary.exists()); assertEquals(listOf("error"), fixture.completions)
                    assertEquals(1, fixture.uploads.size)
                } finally { connection.cancelAndJoin() }
            }
        }
        Fixture().use { fixture ->
            fixture.session().use { session ->
                val connection = launch { session.maintainConnection() }
                try {
                    withTimeout(2000) { session.stream.first { it != null } }
                    val plugin = fixture.plugin(session); val tap = session.invokeFromTap(plugin, plugin.contributions.single())
                    val chosen = CompletableDeferred<List<Uri>>()
                    val task = async { session.selectFiles(plugin, tap.values.single(), tap, { _, _ -> chosen.await() }, { _, _ -> error("must not read") }) { true } }
                    yield(); fixture.socket.disconnect()
                    withTimeout(1000) { task.join() }
                    chosen.complete(listOf(fixture.uri))
                    assertTrue(task.isCancelled); assertTrue(fixture.uploads.isEmpty()); assertTrue(fixture.completions.isEmpty())
                } finally { connection.cancelAndJoin() }
            }
        }
    }

    @Test fun providerReadsCountRealBytesAndAStuckNativeWorkerCannotBeReplacedByAnother() = runBlocking {
        Fixture().use { fixture ->
            val provider = fixture.provider(); provider.source.writeText("too large")
            provider.declared = null
            val reader = DshPickedFileReader(fixture.context, idleMs = 80)
            assertTrue(runCatching { reader.read(fixture.uri, 3) }.isFailure)
            provider.block = CountDownLatch(1)
            try {
                var outcome: Result<DshPickedFile>
                withTimeout(1500) {
                    do { outcome = runCatching { reader.read(fixture.uri, 32) }; if ((outcome.exceptionOrNull() as? DshException)?.code == "DSH_FILE_IO_BUSY") delay(5) }
                    while ((outcome.exceptionOrNull() as? DshException)?.code == "DSH_FILE_IO_BUSY")
                }
                assertEquals("DSH_FILE_READ_TIMEOUT", (outcome.exceptionOrNull() as DshException).code)
                assertEquals("DSH_FILE_IO_BUSY", (runCatching { reader.read(fixture.uri, 32) }.exceptionOrNull() as DshException).code)
            } finally { provider.block!!.countDown(); provider.block = null }
            withTimeout(1500) {
                while (true) {
                    val next = runCatching { reader.read(fixture.uri, 32) }
                    if ((next.exceptionOrNull() as? DshException)?.code == "DSH_FILE_IO_BUSY") { delay(5); continue }
                    next.getOrThrow().use { assertEquals("too large", it.file.readText()) }; break
                }
            }
            assertTrue(File(fixture.context.cacheDir, "dsh-picked-files").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun fileEffectsRequireNegotiatedCapabilitiesAndNeverSurviveStaticDeferredOrCallbackBoundaries() {
        val effect = JSONObject().put("type", "pickFile").put("action", "read").put("request", "T".repeat(32))
            .put("maxFiles", 2).put("maxBytes", DshProtocol.FILE_BYTES).put("maxTotalBytes", DshProtocol.FILE_TOTAL_BYTES)
        val array = JSONArray().put(effect)
        assertEquals(1, DshProtocol.effects(array, features = setOf("files")).values.size)
        assertTrue(DshProtocol.effects(array).values.isEmpty())
        assertTrue(DshProtocol.effects(array, static = true, features = setOf("files")).values.isEmpty())
        assertTrue(DshProtocol.effects(array, deferred = true, features = setOf("files")).values.isEmpty())
        assertTrue(DshProtocol.effects(array, fileResult = true, features = setOf("files")).values.isEmpty())
        effect.put("maxFiles", 1.5)
        assertTrue(DshProtocol.effects(array, features = setOf("files")).values.isEmpty())
    }

    private class Fixture : AutoCloseable {
        val context get() = RuntimeEnvironment.getApplication()
        val uri: Uri = Uri.parse("content://nexus.dsh.picked/report")
        val uploads = CopyOnWriteArrayList<ByteArray>(); val completions = CopyOnWriteArrayList<String>()
        var lastName = ""; var badReceipt = false; var failCompletion = false
        lateinit var socket: Socket
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = java.util.concurrent.Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                var code = 200
                val reply = when {
                    path.endsWith("/contributions") -> """{"v":1,"plugins":[{"pkg":"picked","title":"Picked","state":"ok","capabilities":["files"],"contributes":{"composer.tools":[{"id":"select","label":"Select","ready":true}]}}]}"""
                    path.endsWith("/invoke") -> """{"v":1,"effects":[{"type":"pickFile","action":"read","request":"${"T".repeat(32)}","maxFiles":2,"maxBytes":8388608,"maxTotalBytes":16777216}]}"""
                    path.endsWith("/upload") -> {
                        assertTrue(exchange.requestURI.rawQuery.contains("stream=connection-1"))
                        lastName = java.net.URLDecoder.decode(exchange.requestURI.rawQuery.substringAfter("name="), "UTF-8")
                        val bytes = exchange.requestBody.readBytes(); uploads += bytes
                        """{"v":1,"file":{"handle":"${"H".repeat(32)}","access":"read","bytes":${bytes.size + if (badReceipt) 1 else 0}}}"""
                    }
                    path.endsWith("/complete") -> {
                        completions += JSONObject(exchange.requestBody.bufferedReader().readText()).getString("status")
                        if (failCompletion) code = 500
                        """{"v":1,"effects":[{"type":"toast","text":"完成"},{"type":"insert","text":"not permitted"}]}"""
                    }
                    else -> { code = 404; "{}" }
                }.toByteArray()
                exchange.sendResponseHeaders(code, reply.size.toLong()); exchange.responseBody.use { it.write(reply) }
            }
            start()
        }
        fun session(): DshUiSession {
            val configs = ConnectorConfigStore(context, A2ASecretStore(context) { SecretKeySpec(ByteArray(32), "AES") })
            val repository = DshHostRepository(context, configs)
            val host = repository.save(null, "Files", "http://127.0.0.1:${server.address.port}/", "")
            return DshUiSession(repository, host) { client -> DshStreamClient(client) { request, listener -> Socket(request, listener).also { socket = it; it.open() } } }
        }
        suspend fun plugin(session: DshUiSession) = session.client.contributions().plugins.single()
        fun provider(): PickedProvider = PickedProvider().also {
            it.attachInfo(context, ProviderInfo().apply { authority = uri.authority; exported = true; grantUriPermissions = true })
            ShadowContentResolver.registerProviderInternal(requireNotNull(uri.authority), it)
        }
        override fun close() { server.stop(0); (server.executor as java.util.concurrent.ExecutorService).shutdownNow() }
    }

    private class Socket(private val request: Request, private val listener: WebSocketListener) : WebSocket {
        val sent = mutableListOf<JSONObject>()
        fun open() = listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching").build())
        fun disconnect() = listener.onFailure(this, java.io.IOException("disconnected"), null)
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            val value = JSONObject(text); sent += value
            if (value.optString("t") == "hello") listener.onMessage(this, """{"t":"welcome","v":1,"conn":"connection-1","hb":25,"features":["files"]}""")
            return true
        }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }
}

class PickedProvider : ContentProvider() {
    lateinit var source: File
    var declared: Long? = 17
    var block: CountDownLatch? = null
    val modes = CopyOnWriteArrayList<String>()
    override fun onCreate(): Boolean { source = File(requireNotNull(context).cacheDir, "picked-source.txt"); return true }
    override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor {
        block?.await(3, TimeUnit.SECONDS)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>("../report.txt", declared)) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        query(uri, projection, null as Bundle?, null)
    override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor {
        modes += mode; check(mode == "r")
        return AssetFileDescriptor(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor = openAssetFile(uri, mode, null)
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("read only")
}
