package io.github.mangi.eta.agent.dsh

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import io.github.mangi.eta.agent.voice.BargeInMonitor
import io.github.mangi.eta.agent.voice.SpeechAudioLease
import io.github.mangi.eta.agent.voice.SpeechBargeInMonitor
import java.io.File
import java.io.Closeable
import kotlinx.coroutines.suspendCancellableCoroutine

internal interface DshMediaOutput : AutoCloseable {
    suspend fun prepare(file: File, ended: () -> Unit, failed: () -> Unit)
    fun play()
    fun pause()
}
internal interface DshMediaBackend {
    fun lease(interrupted: () -> Unit): DshMediaAudioLease
    fun output(): DshMediaOutput
    fun voice(requireEchoCancellation: Boolean, playing: () -> Boolean, ended: () -> Unit, failed: (String) -> Unit): BargeInMonitor
}
internal interface DshMediaAudioLease : AutoCloseable { fun playbackFocus() }

/** Reuses the app's existing audio lease and VAD; no network recognizer is used for speech edges. */
internal class DshAndroidMediaBackend(private val context: Context) : DshMediaBackend {
    override fun lease(interrupted: () -> Unit): DshMediaAudioLease {
        val lease = SpeechAudioLease(context, interrupt = interrupted).also { it.acquire(playback = false) }
        return object : DshMediaAudioLease {
            override fun playbackFocus() = lease.requestPlaybackFocus()
            override fun close() = lease.close()
        }
    }
    override fun output(): DshMediaOutput = LocalMediaOutput()
    override fun voice(requireEchoCancellation: Boolean, playing: () -> Boolean, ended: () -> Unit, failed: (String) -> Unit): BargeInMonitor =
        SpeechBargeInMonitor(context, requireEchoCancellation, continuousEdges = true, onSpeechEnded = ended, onFailure = failed,
            conservativeWithoutEcho = true, playbackActive = playing)

    private class LocalMediaOutput : DshMediaOutput {
        private var player: MediaPlayer? = MediaPlayer().apply { setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()) }
        override suspend fun prepare(file: File, ended: () -> Unit, failed: () -> Unit): Unit = suspendCancellableCoroutine { result ->
            val current = requireNotNull(player)
            var ready = false
            current.setOnPreparedListener { if (player === it && result.isActive) { ready = true; result.resumeWith(Result.success(Unit)) } }
            current.setOnCompletionListener { if (player === it && ready) ended() }
            current.setOnErrorListener { media, _, _ ->
                if (player === media) { if (!ready && result.isActive) result.resumeWith(Result.failure(java.io.IOException("Audio decoder failed"))) else failed() }
                true
            }
            result.invokeOnCancellation { close() }
            try { current.setDataSource(file.absolutePath); current.prepareAsync() }
            catch (failure: Exception) { if (result.isActive) result.resumeWith(Result.failure(failure)) }
        }
        override fun play() { requireNotNull(player).start() }
        override fun pause() { player?.let { if (it.isPlaying) it.pause() } }
        override fun close() { val current = player; player = null; current?.setOnPreparedListener(null); current?.setOnCompletionListener(null); current?.setOnErrorListener(null); current?.release() }
    }
}
