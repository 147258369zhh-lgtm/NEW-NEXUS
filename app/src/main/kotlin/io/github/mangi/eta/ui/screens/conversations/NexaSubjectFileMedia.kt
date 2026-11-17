package io.github.mangi.eta.ui.screens.conversations

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.repository.NexusArtifactAccess
import io.github.mangi.eta.data.repository.SubjectInsightFile
import io.github.mangi.eta.ui.markdown.ImageLoadState
import io.github.mangi.eta.ui.markdown.MarkdownImageLoader
import java.nio.ByteOrder
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private sealed interface SubjectFileMedia {
    data object Loading : SubjectFileMedia
    data object Failed : SubjectFileMedia
    data class Picture(val bitmap: Bitmap) : SubjectFileMedia
    data class Wave(val peaks: FloatArray, val seconds: Float) : SubjectFileMedia
}

@Composable
internal fun NexaSubjectFileMedia(file: SubjectInsightFile, category: NexaSubjectFileCategory, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val view = LocalView.current
    var visible by remember(file.artifact.id) { mutableStateOf(false) }
    val mediaCategory = category in setOf(NexaSubjectFileCategory.IMAGE, NexaSubjectFileCategory.VIDEO, NexaSubjectFileCategory.AUDIO)
    val media by produceState<SubjectFileMedia>(SubjectFileMedia.Loading, file.artifact, file.readable, visible) {
        value = SubjectFileMedia.Loading
        if (mediaCategory && visible) value = if (file.readable) SubjectFileMediaLoader.load(context, file, category) else SubjectFileMedia.Failed
    }
    val colors = MiuixTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHigh).onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = coordinates.isAttached && bounds.width > 0 && bounds.height > 0 && bounds.right > 0 && bounds.bottom > 0 &&
            bounds.left < view.rootView.width && bounds.top < view.rootView.height
    }, contentAlignment = Alignment.Center) {
        when (val state = media) {
            is SubjectFileMedia.Picture -> Image(state.bitmap.asImageBitmap(), file.artifact.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            is SubjectFileMedia.Wave -> Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                val waveColor = colors.primary
                Canvas(Modifier.fillMaxWidth().height(38.dp)) {
                    val spacing = size.width / state.peaks.size.coerceAtLeast(1)
                    state.peaks.forEachIndexed { index, peak ->
                        val height = peak.coerceIn(0f, 1f) * size.height
                        val x = (index + .5f) * spacing
                        drawLine(waveColor, Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2),
                            strokeWidth = (spacing * .55f).coerceAtLeast(1f), cap = StrokeCap.Round)
                    }
                }
                Text("前${ceil(state.seconds).toInt()}秒", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary, maxLines = 1)
            }
            else -> {
                val icon = when {
                    mediaCategory && state == SubjectFileMedia.Failed -> Icons.Rounded.ErrorOutline
                    mediaCategory -> Icons.Rounded.HourglassEmpty
                    file.directory -> Icons.Rounded.FolderOpen
                    category == NexaSubjectFileCategory.CODE -> Icons.Rounded.Code
                    else -> Icons.Rounded.Description
                }
                Icon(icon, if (mediaCategory && state == SubjectFileMedia.Failed) "媒体预览不可用，原文件操作仍保留" else category.label,
                    Modifier.size(28.dp), tint = if (state == SubjectFileMedia.Failed) colors.onSurfaceVariantSummary else colors.primary)
            }
        }
        if (category == NexaSubjectFileCategory.VIDEO && media is SubjectFileMedia.Picture) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)
            .size(24.dp).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, "视频", Modifier.size(20.dp), tint = Color.White)
        }
    }
}

/** Local verified media only: two workers, 64 MiB source cap, 256 px covers, bounded PCM excerpt. */
private object SubjectFileMediaLoader {
    private val permits = Semaphore(2)
    private val cache = object : LruCache<String, SubjectFileMedia>(4 * 1024 * 1024) {
        // Charge at least 64 KiB per entry, so tiny waveforms cannot grow thousands of retained keys.
        override fun sizeOf(key: String, value: SubjectFileMedia): Int = maxOf(64 * 1024, when (value) {
            is SubjectFileMedia.Picture -> value.bitmap.allocationByteCount
            is SubjectFileMedia.Wave -> value.peaks.size * 4
            else -> 1
        })
    }
    private val failures = LruCache<String, Long>(96)

    suspend fun load(context: Context, file: SubjectInsightFile, category: NexaSubjectFileCategory): SubjectFileMedia = withContext(Dispatchers.IO) {
        permits.withPermit {
            try {
                val raw = NexusArtifactAccess(context).readableUri(file.artifact.id) ?: return@withPermit SubjectFileMedia.Failed
                val uri = Uri.parse(raw)
                if (uri.scheme !in setOf("file", "content")) return@withPermit SubjectFileMedia.Failed
                val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return@withPermit SubjectFileMedia.Failed
                if (size !in 0L..64L * 1024 * 1024) return@withPermit SubjectFileMedia.Failed
                val modified = sourceModified(context, uri)
                // Providers lacking modification metadata get a short version window instead of an indefinite stale cover.
                val version = "${file.artifact.verifiedAt}:${modified ?: (SystemClock.elapsedRealtime() / 60_000)}"
                val key = "$raw:$size:$category:$version"
                cache.get(key)?.let { return@withPermit it }
                failures.get(key)?.let { failedAt ->
                    if (SystemClock.elapsedRealtime() - failedAt < 30_000) return@withPermit SubjectFileMedia.Failed
                    failures.remove(key)
                }
                val result = try { when (category) {
                    // A fragment versions the shared decoder cache while retaining the exact authorized document path/query.
                    NexaSubjectFileCategory.IMAGE -> when (val image = MarkdownImageLoader.load(context, uri.buildUpon().fragment("nexa-preview=$version").build().toString())) {
                        is ImageLoadState.Ready -> {
                            // The shared image decoder is bounded; cards retain only a small static thumbnail.
                            val ratio = minOf(1f, 256f / maxOf(image.bitmap.width, image.bitmap.height))
                            SubjectFileMedia.Picture(Bitmap.createScaledBitmap(image.bitmap,
                                (image.bitmap.width * ratio).toInt().coerceAtLeast(1), (image.bitmap.height * ratio).toInt().coerceAtLeast(1), true))
                        }
                        else -> SubjectFileMedia.Failed
                    }
                    NexaSubjectFileCategory.VIDEO -> video(context, uri)
                    NexaSubjectFileCategory.AUDIO -> audio(context, uri)
                    else -> SubjectFileMedia.Failed
                } } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { SubjectFileMedia.Failed }
                currentCoroutineContext().ensureActive()
                if (NexusArtifactAccess(context).readableUri(file.artifact.id) != raw) return@withPermit SubjectFileMedia.Failed
                if (result is SubjectFileMedia.Picture || result is SubjectFileMedia.Wave) cache.put(key, result)
                else failures.put(key, SystemClock.elapsedRealtime())
                result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { SubjectFileMedia.Failed }
        }
    }

    private fun sourceModified(context: Context, uri: Uri): Long? = runCatching {
        if (uri.scheme == "file") File(uri.path ?: return null).lastModified().takeIf { it > 0 }
        else context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it > 0 } else null
        }
    }.getOrNull()

    private suspend fun video(context: Context, uri: Uri): SubjectFileMedia {
        val retriever = MediaMetadataRetriever()
        return try {
            currentCoroutineContext().ensureActive()
            retriever.setDataSource(context, uri)
            val bitmap = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 256)
                ?: return SubjectFileMedia.Failed
            currentCoroutineContext().ensureActive()
            SubjectFileMedia.Picture(bitmap)
        } finally { runCatching { retriever.release() } }
    }

    private suspend fun audio(context: Context, uri: Uri): SubjectFileMedia {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return SubjectFileMedia.Failed
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: return SubjectFileMedia.Failed)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            started = true
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var endInput = false
            var endOutput = false
            var scalarSamples = 0L
            var decodedBytes = 0L
            val peaks = FloatArray(36)
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 2500
            while (!endOutput && SystemClock.elapsedRealtime() < deadline && decodedBytes < 8L * 1024 * 1024) {
                currentCoroutineContext().ensureActive()
                if (!endInput) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: return SubjectFileMedia.Failed
                        val count = extractor.readSampleData(input, 0)
                        if (count < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            endInput = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = codec.outputFormat
                    sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                } else if (index >= 0) {
                    try {
                        require(sampleRate in 1..192_000 && channels in 1..8)
                        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT)
                        val output = codec.getOutputBuffer(index)?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val sampleBytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            while (output.remaining() >= sampleBytes && scalarSamples < sampleRate.toLong() * channels * 12 && decodedBytes < 8L * 1024 * 1024) {
                                val value = if (sampleBytes == 4) abs(output.float).takeIf { it.isFinite() }?.coerceAtMost(1f) ?: 0f else abs(output.short.toInt()) / 32768f
                                val bin = (scalarSamples * 3 / (sampleRate.toLong() * channels)).toInt().coerceIn(0, peaks.lastIndex)
                                peaks[bin] = maxOf(peaks[bin], value)
                                scalarSamples++
                                decodedBytes += sampleBytes
                            }
                            if (scalarSamples >= sampleRate.toLong() * channels * 12) endOutput = true
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) endOutput = true
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
            if (scalarSamples == 0L) return SubjectFileMedia.Failed
            val seconds = scalarSamples.toFloat() / (sampleRate * channels)
            return SubjectFileMedia.Wave(peaks.copyOf(ceil(seconds * 3).toInt().coerceIn(1, peaks.size)), seconds)
        } finally {
            if (started) runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }
}
