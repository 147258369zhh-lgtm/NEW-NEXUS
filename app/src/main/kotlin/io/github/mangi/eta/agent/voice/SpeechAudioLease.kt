package io.github.mangi.eta.agent.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import kotlinx.coroutines.CancellationException

/** Process-local audio owners; only the same explicitly paired duplex session may coexist. */
/**
 * [interruptOnScreenOff] 为 false 时锁屏不打断：聊天朗读需要在锁屏和后台继续，
 * 录音与助手浮窗仍随熄屏结束。
 */
internal class SpeechAudioLease(
    private val context: Context,
    private val interruptOnScreenOff: Boolean = true,
    private val duplexGroup: Any? = null,
    private val interrupt: () -> Unit,
) : Closeable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener({ change -> if (change < 0 && isOwned()) interrupt() }, Handler(Looper.getMainLooper()))
        .build()
    private var registered = false
    private var focused = false
    private var closed = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (isOwned()) interrupt() }
    }

    fun acquire(playback: Boolean, beforeFocus: () -> Unit = {}) {
        val previous = synchronized(owners) { owners.filter { it !== this && (duplexGroup == null || it.duplexGroup !== duplexGroup) } }
        previous.forEach { it.interrupt.invoke() }
        synchronized(owners) { owners.add(this) }
        mutableOccupied.value = true
        BackgroundWakeWordRuntime.pauseCapture()
        try {
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                if (interruptOnScreenOff) addAction(Intent.ACTION_SCREEN_OFF)
            }, Context.RECEIVER_NOT_EXPORTED)
            registered = true
            if (playback) requestPlaybackFocus(beforeFocus)
        } catch (error: RuntimeException) {
            close()
            throw error
        }
    }

    fun requestPlaybackFocus(beforeFocus: () -> Unit = {}) {
        if (!isOwned()) throw CancellationException("Speech owner replaced")
        beforeFocus()
        if (!isOwned()) throw CancellationException("Speech owner replaced")
        focused = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focused) throw SpeechFailure(SpeechErrorCode.AUDIO, "当前无法获得音频焦点，请稍后重试")
    }

    override fun close() {
        val shouldUnregister: Boolean
        val shouldAbandonFocus: Boolean
        synchronized(this) {
            if (closed) return
            closed = true
            shouldUnregister = registered
            registered = false
            shouldAbandonFocus = focused
            focused = false
        }
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        if (shouldUnregister) attempt { context.unregisterReceiver(receiver) }
        if (shouldAbandonFocus) attempt { manager.abandonAudioFocusRequest(focus) }
        attempt {
            synchronized(owners) {
                owners.remove(this)
                mutableOccupied.value = owners.isNotEmpty()
            }
        }
        failure?.let { throw it }
    }
    private fun isOwned() = synchronized(owners) { this in owners }

    companion object {
        private val owners = mutableSetOf<SpeechAudioLease>()
        private val mutableOccupied = kotlinx.coroutines.flow.MutableStateFlow(false)
        val occupied: kotlinx.coroutines.flow.StateFlow<Boolean> = mutableOccupied
    }
}
