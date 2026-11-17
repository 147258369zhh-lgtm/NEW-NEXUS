package io.github.mangi.eta.ui.components

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/** Lazy native playback: historical media does not prepare decoders or keep a polling timer. */
@Stable
internal class NexusMediaPlayback(private val context: Context, private val uri: String, initialDuration: Long?) {
    var player by mutableStateOf<ExoPlayer?>(null); private set
    var playing by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var failure by mutableStateOf<String?>(null); private set
    var duration by mutableLongStateOf(initialDuration ?: 0L); private set
    var position by mutableLongStateOf(0L); private set
    var muted by mutableStateOf(false); private set

    fun toggle() {
        try {
        val active = player ?: ExoPlayer.Builder(context).build().also { created ->
            player = created
            created.volume = if (muted) 0f else 1f
            created.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            created.setHandleAudioBecomingNoisy(true)
            created.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; updatePosition() }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    loading = playbackState == Player.STATE_BUFFERING
                    ready = playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED
                    if (ready) { duration = created.duration.coerceAtLeast(0); updatePosition() }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { loading = false; playing = false; failure = "暂时无法播放，点按播放可重试" }
            })
        }
        if (active.isPlaying) { active.pause(); return }
        if (active.playbackState == Player.STATE_IDLE || failure != null) {
            failure = null; loading = true
            active.setMediaItem(MediaItem.fromUri(uri)); active.prepare()
        }
        if (active.playbackState == Player.STATE_ENDED) active.seekTo(0)
        NexusMediaFocus.claim(active)
        active.play()
        } catch (_: Exception) { loading = false; playing = false; failure = "暂时无法播放，点按播放可重试" }
    }
    fun seek(value: Long) { position = value.coerceIn(0L, duration.coerceAtLeast(0L)); player?.seekTo(position) }
    fun toggleMute() { muted = !muted; player?.volume = if (muted) 0f else 1f }
    fun pause() { player?.pause(); updatePosition() }
    fun updatePosition() { position = player?.currentPosition?.coerceAtLeast(0L) ?: 0L }
    fun release() { player?.let { NexusMediaFocus.release(it); it.release() }; player = null }
}

private object NexusMediaFocus {
    private var current: ExoPlayer? = null
    fun claim(player: ExoPlayer) { if (current !== player) current?.pause(); current = player }
    fun release(player: ExoPlayer) { if (current === player) current = null }
}

@Composable
internal fun rememberNexusMediaPlayback(uri: String, durationMs: Long?): NexusMediaPlayback {
    val context = LocalContext.current.applicationContext
    val state = remember(uri) { NexusMediaPlayback(context, uri, durationMs) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(state, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) state.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); state.release() }
    }
    LaunchedEffect(state, state.playing) {
        state.updatePosition()
        while (state.playing) { delay(300); state.updatePosition() }
    }
    return state
}
