package io.github.mangi.eta.agent.dsh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class DshFeed(val data: Any?, val revision: String?, val active: Boolean = true)
internal data class DshStreamNotice(val kind: String, val subscription: String? = null)

/** One UI owner per host. Read subscriptions can reconnect; pending effects are never replayed. */
internal class DshStreamClient(private val host: DshHostClient,
    private val socketFactory: (okhttp3.Request, WebSocketListener) -> WebSocket = host.client::newWebSocket) : AutoCloseable {
    private data class Subscription(val pkg: String, val id: String, val scope: JSONObject, var lastResync: Long = 0)
    private data class Delivery(val value: JSONObject, val receivedAt: Long)
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val welcome = CompletableDeferred<String>()
    private val subscriptions = linkedMapOf<String, Subscription>()
    private data class MicroSubscription(val pkg: String, val id: String, val scope: String, var references: Int = 1, var epoch: String? = null, var sequence: Long = 0)
    private val microSubscriptions = linkedMapOf<String, MicroSubscription>()
    private val mutableMicroFeeds = MutableStateFlow<Map<String, DshFeed>>(emptyMap())
    val microFeeds = mutableMicroFeeds.asStateFlow()
    private val deliveries = linkedMapOf<String, Delivery>()
    private val waits = linkedMapOf<String, CompletableDeferred<DshEffects>>()
    private val consumed = linkedSetOf<String>()
    private val mutableFeeds = MutableStateFlow<Map<String, DshFeed>>(emptyMap())
    private val mutableNotices = MutableSharedFlow<DshStreamNotice>(extraBufferCapacity = 16)
    private val mutableConnected = MutableStateFlow(false)
    @Volatile var features: Set<String> = emptySet()
        private set
    val feeds = mutableFeeds.asStateFlow()
    val notices = mutableNotices.asSharedFlow()
    val connected = mutableConnected.asStateFlow()
    private val heartbeat = Executors.newSingleThreadScheduledExecutor { Thread(it, "dsh-host-heartbeat").apply { isDaemon = true } }
    @Volatile private var socket: WebSocket? = null
    @Volatile private var lastReceived = System.nanoTime()
    @Volatile private var currentConnection: String? = null
    val connectionId: String? get() = currentConnection.takeIf { mutableConnected.value }

    suspend fun connect(): String {
        check(!closed.get() && socket == null)
        val request = host.webSocketRequest()
        val created = socketFactory(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get() || !host.isAvailable()) { webSocket.cancel(); disconnected("DSH_CONNECTION_REVOKED"); return }
                webSocket.send(JSONObject().put("t", "hello").put("v", JSONArray().put(1)).put("locale", "zh-CN")
                    .put("features", JSONArray(DshProtocol.supportedFeatures.toList())).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed.get()) return
                if (!host.isAvailable()) { disconnected("DSH_CONNECTION_REVOKED"); webSocket.cancel(); return }
                try { accept(DshProtocol.parse(text, 48 * 1024)) }
                catch (_: Exception) { disconnected("DSH_STREAM_INVALID"); webSocket.close(1009, "Invalid frame") }
            }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { disconnected("DSH_STREAM_BINARY_UNSUPPORTED"); webSocket.close(1003, "Text only") }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = if (response != null && response.request.url == request.url) when (response.code) {
                    401 -> "DSH_AUTH_REQUIRED"
                    403 -> "DSH_AUTH_FORBIDDEN"
                    else -> "DSH_STREAM_DISCONNECTED"
                } else "DSH_STREAM_DISCONNECTED"
                disconnected(code)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { disconnected("DSH_STREAM_DISCONNECTED") }
        })
        socket = created
        if (closed.get()) created.cancel()
        return try { withTimeout(10000) { welcome.await() } } catch (failure: Exception) {
            try {
                if (failure is DshException && failure.code == "DSH_AUTH_REQUIRED") host.rejectWebSocketAuthentication(request)
            } finally { close() }
            throw failure
        }
    }

    fun subscribe(pkg: String, id: String, scope: JSONObject, initial: DshFeed = DshFeed(null, null)): String = synchronized(lock) {
        require(DshProtocol.packageId.matches(pkg) && DshProtocol.id.matches(id))
        check(connected.value && subscriptions.size < 32)
        val sub = UUID.randomUUID().toString().replace("-", "").take(24)
        subscriptions[sub] = Subscription(pkg, id, DshProtocol.scope(scope))
        mutableFeeds.value = mutableFeeds.value + (sub to initial)
        sendSubscription(sub)
        sub
    }
    fun unsubscribe(sub: String) = synchronized(lock) {
        subscriptions.remove(sub); mutableFeeds.value = mutableFeeds.value - sub
        send(JSONObject().put("t", "unsub").put("sub", sub))
    }
    fun subscribeMicro(pkg: String, id: String, scope: JSONObject): String = synchronized(lock) {
        require(DshProtocol.packageId.matches(pkg) && DshProtocol.id.matches(id))
        check(connected.value && "micro" in features)
        val scoped = DshProtocol.scope(scope)
        val scopeKey = DshProtocol.optionKey(scoped)
        microSubscriptions.entries.firstOrNull { it.value.pkg == pkg && it.value.id == id && it.value.scope == scopeKey }?.let { it.value.references++; return@synchronized it.key }
        check(microSubscriptions.size < 8 && microSubscriptions.values.count { it.pkg == pkg } < 2)
        val sub = UUID.randomUUID().toString().replace("-", "").take(24)
        microSubscriptions[sub] = MicroSubscription(pkg, id, scopeKey)
        mutableMicroFeeds.value = mutableMicroFeeds.value + (sub to DshFeed(null, null))
        send(JSONObject().put("t", "micro.sub").put("sub", sub).put("pkg", pkg).put("id", id).put("scope", scoped))
        sub
    }
    fun unsubscribeMicro(sub: String) = synchronized(lock) {
        val current = microSubscriptions[sub]
        if (current != null && --current.references <= 0 && microSubscriptions.remove(sub) != null) {
            mutableMicroFeeds.value = mutableMicroFeeds.value - sub
            send(JSONObject().put("t", "micro.unsub").put("sub", sub))
        }
    }
    fun capabilityEvent(lease: String, sequence: Long, event: String, reason: String? = null): Boolean = synchronized(lock) {
        require(DshProtocol.requestToken.matches(lease) && sequence in 1..9007199254740991L)
        require(event in setOf("audio.ended", "audio.stopped", "audio.error", "voice.speechStart", "voice.speechEnd", "voice.stopped", "voice.error", "overlay.closed"))
        if (!connected.value || !host.isAvailable()) return@synchronized false
        val frame = JSONObject().put("t", "cap.event").put("lease", lease).put("seq", sequence).put("event", event)
        reason?.let { frame.put("reason", it.take(120)) }
        send(frame); true
    }

    suspend fun awaitPending(id: String, timeoutMs: Long): DshEffects {
        val pending = synchronized(lock) {
            require(id.length in 1..64 && id !in consumed && waits.size < 32)
            if (!connected.value) throw DshException("DSH_STREAM_DISCONNECTED", "连接中断，延后操作未完成")
            CompletableDeferred<DshEffects>().also { waits[id] = it; deliveries.remove(id)?.let { receipt ->
                if (System.nanoTime() - receipt.receivedAt <= TimeUnit.SECONDS.toNanos(5)) deliver(id, receipt.value)
                else it.completeExceptionally(DshException("DSH_PENDING_EXPIRED", "延后结果已过期"))
            } }
        }
        return try { withTimeoutOrNull(timeoutMs.coerceIn(1000, 60000)) { pending.await() }
            ?: throw DshException("DSH_PENDING_TIMEOUT", "延后操作超时，结果已作废") }
        finally {
            synchronized(lock) { waits.remove(id); consumed.add(id); while (consumed.size > 128) consumed.remove(consumed.first()) }
            if (!pending.isCompleted) send(JSONObject().put("t", "cancel").put("pending", id))
        }
    }
    fun cancelPending(id: String) = synchronized(lock) {
        send(JSONObject().put("t", "cancel").put("pending", id)); waits.remove(id)?.cancel(); deliveries.remove(id); consumed.add(id)
    }

    private fun accept(message: JSONObject) = synchronized(lock) {
        lastReceived = System.nanoTime()
        when (message.optString("t")) {
            "welcome" -> {
                require(message.optInt("v") == 1 && !welcome.isCompleted)
                val connection = message.getString("conn").also { require(it.length in 1..64 && it.none(Char::isISOControl)) }
                val accepted = message.optJSONArray("features") ?: JSONArray()
                features = (0 until minOf(accepted.length(), 16)).map { accepted.optString(it) }.toSet() intersect DshProtocol.supportedFeatures
                currentConnection = connection; mutableConnected.value = true; welcome.complete(connection)
                val seconds = message.optInt("hb", 25).coerceIn(5, 60)
                heartbeat.scheduleAtFixedRate({
                    if (!host.isAvailable()) { disconnected("DSH_CONNECTION_REVOKED"); socket?.cancel() }
                    else if (System.nanoTime() - lastReceived > TimeUnit.SECONDS.toNanos(seconds * 2L)) { disconnected("DSH_HEARTBEAT_TIMEOUT"); socket?.cancel() }
                    else send(JSONObject().put("t", "ping").put("at", System.currentTimeMillis()))
                }, seconds.toLong(), seconds.toLong(), TimeUnit.SECONDS)
            }
            "data" -> {
                val sub = message.getString("sub"); if (sub !in subscriptions) return@synchronized
                val data = message.opt("data")?.takeUnless { it == JSONObject.NULL }
                require((data?.toString()?.toByteArray()?.size ?: 0) <= 32 * 1024)
                mutableFeeds.value = mutableFeeds.value + (sub to DshFeed(data, revision(message)))
            }
            "micro.data" -> {
                val sub = message.optString("sub"); val current = microSubscriptions[sub] ?: return@synchronized
                val revision = message.opt("rev") as? String ?: return@synchronized
                if (!revision.matches(Regex("[a-f0-9]{16}\\.[1-9][0-9]{0,15}"))) return@synchronized
                val epoch = revision.substringBefore('.'); val sequence = revision.substringAfter('.').toLongOrNull() ?: return@synchronized
                if (current.epoch != null && current.epoch != epoch || sequence <= current.sequence) return@synchronized
                val data = DshProtocol.micro(message.opt("data")) ?: return@synchronized
                current.epoch = epoch; current.sequence = sequence
                mutableMicroFeeds.value = mutableMicroFeeds.value + (sub to DshFeed(data, revision))
            }
            "micro.end" -> {
                val sub = message.optString("sub"); microSubscriptions.remove(sub)
                mutableMicroFeeds.value[sub]?.let { mutableMicroFeeds.value = mutableMicroFeeds.value + (sub to it.copy(active = false)) }
                mutableNotices.tryEmit(DshStreamNotice("micro.end", sub))
            }
            "patch" -> {
                val sub = message.getString("sub"); val subscription = subscriptions[sub] ?: return@synchronized
                val current = mutableFeeds.value[sub] ?: return@synchronized
                if (current.revision != message.optString("base")) {
                    val now = System.nanoTime()
                    if (now - subscription.lastResync >= TimeUnit.SECONDS.toNanos(1)) { subscription.lastResync = now; sendSubscription(sub) }
                    return@synchronized
                }
                val patch = message.getJSONObject("merge")
                require(patch.toString().toByteArray().size <= 16 * 1024)
                mutableFeeds.value = mutableFeeds.value + (sub to DshFeed(DshProtocol.mergePatch(current.data, patch), revision(message)))
            }
            "stale" -> mutableNotices.tryEmit(DshStreamNotice("stale", message.optString("sub")))
            "changed" -> mutableNotices.tryEmit(DshStreamNotice("changed"))
            "end" -> {
                val sub = message.getString("sub"); subscriptions.remove(sub)
                mutableFeeds.value[sub]?.let { mutableFeeds.value = mutableFeeds.value + (sub to it.copy(active = false)) }
                mutableNotices.tryEmit(DshStreamNotice("end", sub))
            }
            "done", "void" -> {
                val id = message.getString("pending").also { require(it.length in 1..64) }
                if (id in consumed) return@synchronized
                if (id in waits) deliver(id, message)
                else {
                    val now = System.nanoTime()
                    deliveries.entries.removeIf { now - it.value.receivedAt > TimeUnit.SECONDS.toNanos(5) }
                    if (deliveries.size < 32) deliveries[id] = Delivery(message, now)
                }
            }
            "error" -> if (!welcome.isCompleted) disconnected("DSH_STREAM_UNSUPPORTED")
        }
    }
    private fun deliver(id: String, message: JSONObject) {
        val wait = waits[id] ?: return
        if (message.getString("t") == "void") wait.completeExceptionally(DshException("DSH_PENDING_CANCELLED", "延后操作已取消或过期"))
        else wait.complete(DshProtocol.effects(message.optJSONArray("effects") ?: JSONArray(), deferred = true))
    }
    private fun sendSubscription(sub: String) {
        val value = subscriptions.getValue(sub)
        val message = JSONObject().put("t", "sub").put("sub", sub).put("pkg", value.pkg).put("id", value.id).put("scope", value.scope)
        mutableFeeds.value[sub]?.revision?.let { message.put("rev", it) }
        send(message)
    }
    private fun send(message: JSONObject) {
        val encoded = message.toString()
        require(encoded.toByteArray().size <= 4096)
        if (closed.get()) return
        if (socket?.send(encoded) == false) disconnected("DSH_STREAM_DISCONNECTED")
    }
    private fun revision(value: JSONObject): String? = value.optString("rev").takeIf { value.opt("rev") != JSONObject.NULL && it.length in 1..256 }
    private fun disconnected(code: String) = synchronized(lock) {
        mutableConnected.value = false
        features = emptySet()
        val failure = DshException(code, "DSH 实时连接已断开")
        if (!welcome.isCompleted) welcome.completeExceptionally(failure)
        waits.values.forEach { it.completeExceptionally(failure) }; deliveries.clear()
        mutableFeeds.value = mutableFeeds.value.mapValues { it.value.copy(active = false) }
        mutableMicroFeeds.value = mutableMicroFeeds.value.mapValues { it.value.copy(active = false) }
        mutableNotices.tryEmit(DshStreamNotice("disconnected"))
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        disconnected("DSH_STREAM_CLOSED"); heartbeat.shutdownNow(); socket?.cancel(); socket = null
        synchronized(lock) { subscriptions.clear(); microSubscriptions.clear(); deliveries.clear(); waits.clear() }
    }
}
