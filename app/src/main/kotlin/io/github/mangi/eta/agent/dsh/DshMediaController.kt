package io.github.mangi.eta.agent.dsh

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import io.github.mangi.eta.agent.connector.ConnectorConfigStore
import io.github.mangi.eta.agent.voice.BargeInMonitor
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class DshMediaSnapshot(val surface: String = "", val author: String = "", val audio: String? = null,
    val listening: Boolean = false, val waitingPermission: Boolean = false, val overlay: Boolean = false,
    val caption: String = "", val progress: Double? = null, val status: String? = null)

/** Foreground-only media facade. All control methods and backend callbacks are main-thread owned. */
internal class DshMediaController(
    context: Context,
    private val scope: CoroutineScope,
    private val backend: DshMediaBackend = DshAndroidMediaBackend(context.applicationContext),
    private val repository: DshHostRepository = DshHostRepository(context),
    private val download: suspend (DshUiSession, String, String, String, String?) -> File = { session, pkg, id, lease, key ->
        session.client.downloadMedia(pkg, id, lease, key, File(context.cacheDir, "dsh-media"))
    },
    private val acquire: suspend (DshUiSession) -> AutoCloseable = { session -> DshSharedSessions.acquireAsync(context, DshHostRepository(context), session.host).also { it.visible(true) } },
) {
    private data class Receipt(var sequence: Long = 0, val terminal: MutableSet<String> = mutableSetOf(), var hadAudio: Boolean = false)
    private class Owner(val surface: String, val subject: String?, val session: DshUiSession, val plugin: DshPlugin,
        val stream: DshStreamClient, val remote: JSONObject, val instance: String, val lease: String, val receipt: Receipt, parent: CoroutineScope) {
        val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        var reference: AutoCloseable? = null
        var audioLease: DshMediaAudioLease? = null
        var audio = false; var audioReady = false; var voice = false; var voiceArmed = false; var overlay = false; var speech = false; var stopAfterSpeech = false
        var hadAudio: Boolean get() = receipt.hadAudio; set(value) { receipt.hadAudio = value }
        @Volatile var audioStatus: String? = null
        var paused = false; var permission = false; var echoFallback = false
        var player: DshMediaOutput? = null; var file: File? = null; var loading: Job? = null; var monitor: BargeInMonitor? = null
        var caption = ""; var progress: Double? = null; var micro: Job? = null
        val terminal get() = receipt.terminal
        var permissionJob: Job? = null
        var screenGuard: AutoCloseable? = null
        var tokens = 4.0; var tokenAt = System.nanoTime()
    }
    private val context = context.applicationContext
    private val gate = Mutex()
    private val receipts = linkedMapOf<String, Receipt>()
    private var owner: Owner? = null
    private val surfaces = linkedMapOf<Any, Pair<String, Boolean>>()
    private val mutableSurfaces = MutableStateFlow<Any?>(null)
    private val mutableState = MutableStateFlow(DshMediaSnapshot())
    val state = mutableState.asStateFlow()
    val topSurface = mutableSurfaces.asStateFlow()

    fun surface(token: Any, key: String, visible: Boolean) {
        surfaces[token] = key to visible
        mutableSurfaces.value = surfaces.entries.lastOrNull { it.value.second }?.key
        val current = owner
        if (current != null && surfaces.values.none { it.first == current.surface && it.second }) stop("background")
        else if (visible && current != null && current.surface != key) stop("surface-changed")
    }
    fun removeSurface(token: Any) {
        surfaces.remove(token); mutableSurfaces.value = surfaces.entries.lastOrNull { it.value.second }?.key
        owner?.let { current -> if (surfaces.values.none { it.first == current.surface && it.second }) stop("surface-closed") }
    }
    private fun visible(key: String) = surfaces.values.any { it.first == key && it.second }
    private fun receipt(session: DshUiSession, stream: DshStreamClient, plugin: DshPlugin, instance: String, lease: String): Receipt {
        val key = "${session.host.id}:${session.host.credentialRevision}:${stream.connectionId}:${plugin.pkg}:$instance:$lease"
        val result = receipts.getOrPut(key) { Receipt() }
        while (receipts.size > 64) receipts.remove(receipts.keys.first())
        return result
    }
    fun discard(session: DshUiSession, plugin: DshPlugin, effect: JSONObject, origin: DshEffects) {
        val stream = session.stream.value ?: return
        if (stream.connectionId != origin.streamId || !stream.connected.value) return
        val type = effect.optString("type"); val op = effect.optString("op"); val instance = effect.optString("instance"); val lease = effect.optString("lease")
        if (!DshProtocol.id.matches(instance) || !DshProtocol.requestToken.matches(lease) || type !in plugin.capabilities) return
        val current = owner?.takeIf { it.session === session && it.plugin.pkg == plugin.pkg && it.instance == instance && it.lease == lease }
        if (type == "audio" && (op != "play" || current?.audio == true) || type == "voice" && (op != "start" || current?.voice == true)
            || type == "overlay" && (op != "show" || current?.overlay == true)) return
        val receipt = receipt(session, stream, plugin, instance, lease)
        if (!receipt.terminal.add(type)) return
        val event = when (type) { "audio" -> "audio.stopped"; "voice" -> "voice.error"; "overlay" -> "overlay.closed"; else -> return }
        runCatching { stream.capabilityEvent(lease, ++receipt.sequence, event, "not-started") }
    }

    suspend fun apply(surface: String, subject: String?, session: DshUiSession, plugin: DshPlugin, remote: JSONObject,
        effect: JSONObject, origin: DshEffects, voicePermission: suspend () -> Boolean) = gate.withLock {
        require(!origin.deferred && origin.gestureDeadlineNanos != null && System.nanoTime() <= origin.gestureDeadlineNanos)
        val stream = requireNotNull(session.stream.value)
        val type = effect.getString("type"); val op = effect.getString("op"); val instance = effect.getString("instance"); val lease = effect.getString("lease")
        require(type in setOf("audio", "voice", "overlay") && type in plugin.capabilities && type in stream.features
            && stream.connected.value && origin.streamId == stream.connectionId && visible(surface)
            && repository.isCurrent(session.host) && DshProtocol.id.matches(instance) && DshProtocol.requestToken.matches(lease))
        val started = type == "audio" && op == "play" || type == "voice" && op == "start" || type == "overlay" && op == "show"
        var current = owner?.takeIf { it.session === session && it.plugin.pkg == plugin.pkg && it.plugin.version == plugin.version && it.instance == instance && it.lease == lease && it.remote.toString() == DshProtocol.scope(remote).toString() }
        if (current == null) {
            require(started) { "媒体实例已经结束" }
            stop("replaced")
            // A short audio can finish before later effects in its same reply are processed.
            // Keep its event sequence and terminal roles so those effects cannot restart it.
            val receipt = receipt(session, stream, plugin, instance, lease)
            if (origin.values.any { it.optString("lease") == lease && it.optString("type") == "audio" && it.optString("op") == "play" }) receipt.hadAudio = true
            val next = Owner(surface, subject, session, plugin, stream, DshProtocol.scope(remote), instance, lease, receipt, scope)
            owner = next
            try { next.reference = acquire(session); check(owner === next && visible(surface)) }
            catch (failure: Exception) { if (owner === next) stop("cancelled"); throw failure }
            current = next
            try { watch(next) } catch (failure: Exception) { stop("initialization"); throw failure }
        }
        require(current.surface == surface && current.stream === stream)
        try {
            when (type) {
                "audio" -> when (op) {
                    "play" -> {
                        require(!current.audio && "audio" !in current.terminal)
                        val id = effect.getString("src").removePrefix("media:")
                        require(plugin.contributions.any { it.surface == "media" && it.id == id && it.ready }) { "插件未声明此音频" }
                        current.audio = true; current.hadAudio = true; current.audioStatus = "loading"; current.paused = false
                        audioLease(current)
                        current.loading = current.scope.launch {
                            try {
                                val file = download(session, plugin.pkg, id, lease, effect.optString("key").takeIf { effect.has("key") })
                                if (owner !== current || !current.audio) { file.delete(); return@launch }
                                current.file = file
                                val player = backend.output().also { current.player = it }
                                withTimeout(10_000) { player.prepare(file, ended = { if (owner === current) finishAudio(current, "audio.ended", "completed") },
                                    failed = { if (owner === current) finishAudio(current, "audio.error", "decoder") }) }
                                if (owner === current && current.audio) {
                                    current.audioReady = true
                                    if (!current.paused) { current.audioLease?.playbackFocus(); player.play(); current.audioStatus = "playing" }
                                    else current.audioStatus = "paused"
                                    publish(current)
                                }
                            } catch (cancelled: CancellationException) {
                                if (owner === current && current.audio) finishAudio(current, "audio.error", "timeout")
                                throw cancelled
                            } catch (_: Exception) { if (owner === current && current.audio) finishAudio(current, "audio.error", "playback") }
                        }
                    }
                    "pause" -> { require(current.audio); current.paused = true; if (current.audioReady) current.player?.pause(); current.audioStatus = "paused" }
                    "resume" -> { require(current.audio); current.paused = false; if (current.audioReady) current.player?.let { current.audioLease?.playbackFocus(); it.play(); current.audioStatus = "playing" } else current.audioStatus = "loading" }
                    "stop" -> finishAudio(current, "audio.stopped", "plugin")
                }
                "voice" -> when (op) {
                    "start" -> {
                        require(!current.voice && "voice" !in current.terminal)
                        if (current.hadAudio && "audio" in current.terminal) {
                            current.voice = true; endVoice(current, "voice.stopped", "audio-ended"); return@withLock
                        }
                        current.voice = true; current.permission = true; publish(current)
                        val allowed = coroutineScope {
                            val waiting = async { voicePermission() }.also { current.permissionJob = it }
                            try { withTimeoutOrNull(60_000) { waiting.await() } ?: false }
                            finally { waiting.cancel(); current.permissionJob = null }
                        }
                        current.permission = false
                        if (owner !== current) return@withLock
                        if (!allowed) endVoice(current, "voice.error", "declined")
                        else if (System.nanoTime() > origin.gestureDeadlineNanos) {
                            endVoice(current, "voice.error", "gesture-expired")
                            throw DshException("DSH_VOICE_APPROVED_RETRY", "授权已保存，请再次点击开启聆听")
                        }
                        else {
                            audioLease(current)
                            val monitor = backend.voice(current.hadAudio,
                                playing = { current.audioStatus == "playing" },
                                ended = { if (owner === current && current.voice && current.speech) { current.speech = false; event(current, "voice.speechEnd"); if (current.stopAfterSpeech) endVoice(current, "voice.stopped", "audio-ended") } },
                                failed = { if (owner === current && current.voice) endVoice(current, "voice.error", "microphone") })
                            current.monitor = monitor
                            monitor.start(onArmed = { status -> if (owner === current && current.voice) {
                                if (!status.canHearUser) endVoice(current, "voice.error", "microphone") else { current.voiceArmed = true; current.echoFallback = status.reason != null; publish(current) }
                            } }, onLevel = {}, onTriggered = { if (owner === current && current.voice && !current.speech) {
                                current.speech = true
                                if (current.audio) { clearAudio(current); current.terminal += "audio"; current.stopAfterSpeech = current.hadAudio; closeOverlay(current, "speech") }
                                event(current, "voice.speechStart"); publish(current)
                            } })
                        }
                    }
                    "stop" -> endVoice(current, "voice.stopped", "plugin")
                }
                "overlay" -> when (op) {
                    "show" -> {
                        require(!current.overlay && "overlay" !in current.terminal)
                        if (current.hadAudio && "audio" in current.terminal) {
                            current.overlay = true; closeOverlay(current, "audio-ended"); return@withLock
                        }
                        val resource = plugin.contributions.firstOrNull { it.surface == "overlays" && it.id == effect.optString("id") && it.ready }
                            ?: error("插件未声明此字幕")
                        current.overlay = true; current.caption = DshConversationFeeds.singleLine((resource.raw.opt("text") as? String).orEmpty(), 240)
                        val channel = resource.raw.optString("channel")
                        if (channel.isNotBlank()) {
                            require("micro" in stream.features && "micro" in plugin.capabilities && plugin.contributions.any { it.surface == "micro" && it.id == channel })
                            current.micro = current.scope.launch {
                              try {
                                val subscription = stream.subscribeMicro(plugin.pkg, channel, current.remote)
                                try { stream.microFeeds.collect { rows -> if (owner === current && current.overlay) {
                                    val feed = rows[subscription]; val value = feed?.data as? JSONObject
                                    if (feed?.active == false) closeOverlay(current, "channel-ended")
                                    else if (value != null) {
                                        if (value.has("text")) current.caption = DshConversationFeeds.singleLine((value.opt("text") as? String).orEmpty(), 240)
                                        if (value.has("progress")) current.progress = if (value.isNull("progress")) null else value.optDouble("progress").takeIf { it.isFinite() && it in 0.0..1.0 }
                                        publish(current)
                                    }
                                } } } finally { stream.unsubscribeMicro(subscription) }
                              } catch (cancelled: CancellationException) { throw cancelled }
                              catch (_: Exception) { if (owner === current) closeOverlay(current, "channel-unavailable") }
                            }
                        }
                    }
                    "hide" -> closeOverlay(current, "plugin")
                }
            }
            if (owner === current) { publish(current); releaseIfFinished(current) }
        } catch (failure: Exception) {
            if (owner === current) {
                when (type) { "audio" -> finishAudio(current, "audio.error", "operation"); "voice" -> endVoice(current, "voice.error", "permission"); "overlay" -> closeOverlay(current, "error") }
                releaseIfFinished(current)
            }
            throw failure
        }
    }

    private fun audioLease(current: Owner) { if (current.audioLease == null) current.audioLease = backend.lease { if (owner === current) stop("audio-owner") } }
    private fun watch(current: Owner) {
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { if (owner === current) stop("screen-off") } }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        current.screenGuard = AutoCloseable { runCatching { context.unregisterReceiver(receiver) } }
        current.scope.launch { current.session.notices.collect { if (owner === current && it.kind in setOf("disconnected", "changed")) stop(it.kind) } }
        current.scope.launch {
            combine(current.session.stream, current.session.catalog, ConnectorConfigStore.changes, DshHostRepository.scopeChanges) { stream, catalog, _, _ -> stream to catalog }.collect { (stream, catalog) ->
                val valid = runCatching { !(stream !== current.stream || !current.stream.connected.value || !repository.isCurrent(current.session.host) || !visible(current.surface)
                    || catalog?.plugins?.none { it.pkg == current.plugin.pkg && it.version == current.plugin.version && it.state == "ok" } == true
                    || current.voice && !current.permission && !repository.voiceAllowed(current.session.host, current.plugin.pkg)
                    || current.subject != null && repository.conversationScope(current.session.host, current.subject).toString() != current.remote.toString()) }.getOrDefault(false)
                if (owner === current && !valid) stop("revoked")
            }
        }
    }
    private fun event(current: Owner, value: String, reason: String? = null) {
        val role = value.substringBefore('.')
        val terminal = value in setOf("audio.ended", "audio.stopped", "audio.error", "voice.stopped", "voice.error", "overlay.closed")
        if (terminal && !current.terminal.add(role)) return
        if (!terminal) {
            val now = System.nanoTime(); current.tokens = minOf(4.0, current.tokens + (now - current.tokenAt) / 1e9 * 10); current.tokenAt = now
            if (current.tokens < 1) return; current.tokens--
        }
        runCatching { current.stream.capabilityEvent(current.lease, ++current.receipt.sequence, value, reason) }
    }
    private fun clearAudio(current: Owner) {
        current.audio = false; current.audioReady = false; current.audioStatus = null
        current.loading?.cancel(); current.loading = null
        runCatching { current.player?.close() }; current.player = null
        current.file?.delete(); current.file = null
    }
    private fun finishAudio(current: Owner, event: String, reason: String) {
        if (owner !== current || !current.audio) return
        clearAudio(current); this.event(current, event, reason)
        if (current.voice) { if (current.speech) current.stopAfterSpeech = true else endVoice(current, "voice.stopped", "audio-ended") }
        closeOverlay(current, "audio-ended"); publish(current); releaseIfFinished(current)
    }
    private fun endVoice(current: Owner, event: String, reason: String) {
        if (!current.voice) return
        current.voice = false; current.voiceArmed = false; current.permission = false
        current.permissionJob?.cancel(); current.permissionJob = null
        val monitor = current.monitor; current.monitor = null; monitor?.stop()
        if (monitor != null) scope.launch { monitor.awaitStopped() }
        this.event(current, event, reason); publish(current); releaseIfFinished(current)
    }
    private fun closeOverlay(current: Owner, reason: String) {
        if (!current.overlay) return
        current.overlay = false; current.micro?.cancel(); current.micro = null; current.caption = ""; current.progress = null
        event(current, "overlay.closed", reason); publish(current); releaseIfFinished(current)
    }
    private fun releaseIfFinished(current: Owner) {
        if (owner === current && !current.audio && !current.voice && !current.overlay) {
            owner = null; current.scope.cancel(); runCatching { current.audioLease?.close() }; runCatching { current.reference?.close() }; current.screenGuard?.close(); mutableState.value = DshMediaSnapshot()
        }
    }
    private fun publish(current: Owner) {
        if (owner === current) mutableState.value = DshMediaSnapshot(current.surface, current.plugin.title, current.audioStatus, current.voice && current.voiceArmed,
            current.permission, current.overlay, current.caption, current.progress, when {
                current.voice && !current.voiceArmed && !current.permission -> "麦克风启动中"
                current.voice && current.echoFallback -> "正在聆听 · 回声降级"
                else -> null
            })
    }
    fun pauseOrResume() { owner?.takeIf { it.audio && it.player != null }?.let { current ->
        runCatching { current.paused = !current.paused; if (current.audioReady) { if (current.paused) current.player?.pause() else { current.audioLease?.playbackFocus(); current.player?.play() } }
            current.audioStatus = if (current.paused) "paused" else if (current.audioReady) "playing" else "loading"; publish(current) }.onFailure { finishAudio(current, "audio.error", "playback") }
    } }
    fun hideOverlay() { owner?.let { closeOverlay(it, "user") } }
    fun stop(reason: String = "user") {
        val current = owner ?: return
        owner = null
        val audio = current.audio; clearAudio(current)
        val voice = current.voice; current.voice = false; current.monitor?.stop(); val monitor = current.monitor; current.monitor = null
        current.permissionJob?.cancel(); current.permissionJob = null
        val overlay = current.overlay; current.overlay = false; current.micro?.cancel()
        if (audio) event(current, "audio.stopped", reason)
        if (voice) event(current, "voice.stopped", reason)
        if (overlay) event(current, "overlay.closed", reason)
        current.scope.cancel(); runCatching { current.audioLease?.close() }; runCatching { current.reference?.close() }
        current.screenGuard?.close()
        if (monitor != null) scope.launch { monitor.awaitStopped() }
        mutableState.value = DshMediaSnapshot()
    }

    companion object {
        private var shared: DshMediaController? = null
        fun get(context: Context): DshMediaController {
            val app = context.applicationContext
            return shared?.takeIf { it.context === app } ?: DshMediaController(app, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
                .also { shared?.stop("replaced"); shared = it }
        }
        fun stopForInput(context: Context) { shared?.takeIf { it.context === context.applicationContext }?.stop("dictation") }
    }
}
