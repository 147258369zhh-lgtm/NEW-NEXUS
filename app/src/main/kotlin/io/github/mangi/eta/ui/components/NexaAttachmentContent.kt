package io.github.mangi.eta.ui.components

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalView
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.content.FileProvider
import androidx.media3.ui.compose.PlayerSurface
import io.github.mangi.eta.data.repository.NexusArtifactAccess
import io.github.mangi.eta.data.repository.NexusArtifactMedia
import io.github.mangi.eta.ui.markdown.MarkdownImage
import io.github.mangi.eta.ui.markdown.MarkdownImageBlock
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Created only after the caller validates a sent path or resolves a verified ledger record. */
internal data class NexaAttachmentSource(
    val uri: String,
    val label: String,
    val mime: String,
    val byteCount: Long? = null,
    val artifactId: String? = null,
    val validate: () -> Boolean = { true },
) {
    fun input(context: Context): InputStream {
        check(validate()) { "文件已不可读取" }
        val parsed = Uri.parse(uri)
        return when (parsed.scheme?.lowercase()) {
            "file", "content" -> context.contentResolver.openInputStream(parsed) ?: error("无法读取文件")
            "http", "https" -> {
                val response = AttachmentNetwork.client.newCall(okhttp3.Request.Builder().url(uri).build()).execute()
                if (!response.isSuccessful) { response.close(); error("文件请求失败") }
                object : java.io.FilterInputStream(response.body.byteStream()) {
                    override fun close() { try { super.close() } finally { response.close() } }
                }
            }
            "data" -> {
                require(uri.length <= 18 * 1024 * 1024 && uri.startsWith("data:image/", true)) { "不支持的内嵌文件" }
                val comma = uri.indexOf(','); require(comma > 0)
                val bytes = if (uri.substring(0, comma).endsWith(";base64", true)) android.util.Base64.decode(uri.substring(comma + 1), android.util.Base64.DEFAULT)
                    else Uri.decode(uri.substring(comma + 1)).toByteArray(Charsets.UTF_8)
                require(bytes.size <= 12 * 1024 * 1024)
                bytes.inputStream()
            }
            else -> error("不支持的文件地址")
        }
    }
    suspend fun export(context: Context, destination: Uri) = withContext(Dispatchers.IO) {
        try {
            val output = context.contentResolver.openOutputStream(destination, "wt") ?: error("无法保存文件")
            output.use { sink -> input(context).use { stream ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer); if (count < 0) break
                    total += count
                    if (uri.startsWith("http://") || uri.startsWith("https://")) require(total <= 256L * 1024 * 1024) { "网络文件超过保存限额" }
                    sink.write(buffer, 0, count)
                }
            } }
        } catch (failure: Throwable) { runCatching { context.contentResolver.delete(destination, null, null) }; throw failure }
    }
    private suspend fun shareUri(context: Context): Uri = withContext(Dispatchers.IO) {
        if (artifactId != null) return@withContext NexusArtifactAccess(context).prepareShare(artifactId)
        val root = File(context.cacheDir, "workspace-share").apply { check(isDirectory || mkdirs()) }
        val expiry = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        root.listFiles()?.filter { it.isFile && it.lastModified() < expiry }?.forEach { it.delete() }
        val file = File(root, "${UUID.randomUUID()}-${safeAttachmentName(label)}")
        try {
            file.outputStream().use { output -> input(context).use { stream ->
                val buffer = ByteArray(64 * 1024); var bytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer); if (count < 0) break
                    bytes += count; check(bytes <= 256L * 1024 * 1024) { "分享上限 256 MiB，可保存到文件" }
                    output.write(buffer, 0, count)
                }
            } }
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } catch (failure: Throwable) { file.delete(); throw failure }
    }
    suspend fun launch(context: Context, share: Boolean = false) {
        val uri = shareUri(context)
        withContext(Dispatchers.Main) {
            if (!share && mime == "application/vnd.android.package-archive" && !context.packageManager.canRequestPackageInstalls()) {
                // The system owns this permission. The card remains available for the next explicit click.
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(context, "允许此来源后，返回并再次点打开交给系统安装器", Toast.LENGTH_LONG).show()
                return@withContext
            }
            val intent = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(label, uri)
            context.startActivity((if (share) Intent.createChooser(intent, null) else intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

internal fun safeAttachmentName(label: String): String = label.substringAfterLast('/').substringAfterLast('\\')
    .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim('.', ' ').take(160).ifBlank { "NEXA-file" }

@Composable
internal fun rememberArtifactSource(uri: String, artifactId: String, mime: String, fallbackLabel: String): NexaAttachmentSource {
    val context = LocalContext.current
    val record by produceState<io.github.zhh_lgtm.nexus.application.api.ArtifactRecord?>(null, artifactId) {
        value = withContext(Dispatchers.IO) { NexusArtifactAccess(context).record(artifactId) }
    }
    return remember(uri, artifactId, mime, record) { NexaAttachmentSource(uri, record?.label ?: fallbackLabel, mime,
        artifactId = artifactId.takeIf { record != null }, validate = { record == null || NexusArtifactAccess(context).readableUri(artifactId) == uri }) }
}

@Composable
internal fun NexaAttachmentContent(source: NexaAttachmentSource, modifier: Modifier = Modifier, durationMs: Long? = null,
    width: Int? = null, height: Int? = null, onOpenImage: (() -> Unit)? = null) {
    val kind = NexusArtifactMedia.kind(source.mime, source.label)
    when (kind) {
        NexusArtifactMedia.Kind.IMAGE -> Column(modifier) {
            MarkdownImageBlock(MarkdownImage(0, source.uri, source.label), onInternalOpen = onOpenImage)
            // This always retains a save/open route, including undecodable or oversized images.
            NexaFileCard(source, allowPreview = false, compact = true)
        }
        NexusArtifactMedia.Kind.AUDIO, NexusArtifactMedia.Kind.VIDEO -> NexaPlayableAttachment(source, kind == NexusArtifactMedia.Kind.VIDEO, modifier, durationMs, width, height)
        else -> NexaFileCard(source, modifier)
    }
}

private object VideoFrames {
    private val limit = Semaphore(1)
    private val cache = object : android.util.LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    suspend fun load(context: Context, source: NexaAttachmentSource): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(source.uri)?.let { return@withContext it }
        limit.withPermit {
            cache.get(source.uri)?.let { return@withPermit it }
            currentCoroutineContext().ensureActive()
            if (!source.validate()) return@withPermit null
            runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, Uri.parse(source.uri))
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.coerceAtLeast(1) ?: 640
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.coerceAtLeast(1) ?: 360
                    val scale = minOf(1f, 720f / maxOf(width, height))
                    retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                }
            }.getOrNull()?.also { currentCoroutineContext().ensureActive(); cache.put(source.uri, it) }
        }
    }
}

/** Same on-demand player and lifecycle release for sent files and output artifacts. */
@Composable
private fun NexaPlayableAttachment(source: NexaAttachmentSource, video: Boolean, modifier: Modifier, durationMs: Long?, width: Int?, height: Int?) {
    val context = LocalContext.current
    val playback = rememberNexusMediaPlayback(source.uri, durationMs)
    val scope = rememberCoroutineScope()
    val hostView = LocalView.current
    var visible by remember(source.uri) { mutableStateOf(false) }
    var capacityFailure by remember(source.uri) { mutableStateOf<String?>(null) }
    var checking by remember(source.uri) { mutableStateOf(false) }
    var full by remember(source.uri) { mutableStateOf(false) }
    var saving by remember(source.uri) { mutableStateOf(false) }
    val frame by produceState<Bitmap?>(null, source.uri, video, visible) { if (video && visible) value = VideoFrames.load(context, source) }
    LaunchedEffect(visible) { if (!visible) playback.pause() }
    fun toggle() {
        if (playback.playing) { playback.pause(); return }
        scope.launch {
            checking = true
            try {
                capacityFailure = probeMediaDecoder(context, source, video)
                if (capacityFailure == null) playback.toggle()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { capacityFailure = "文件已不可读取，可从原文件位置恢复" }
            finally { checking = false }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(source.mime)) { target ->
        if (target != null) scope.launch {
            saving = true
            try { source.export(context, target) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(context, "文件保存失败", Toast.LENGTH_SHORT).show() }
            finally { saving = false }
        }
    }
    Column(modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = coordinates.isAttached && bounds.width > 0 && bounds.height > 0 && bounds.right > 0 && bounds.bottom > 0 && bounds.left < hostView.rootView.width && bounds.top < hostView.rootView.height
    }.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = .7f)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(source.label, style = MiuixTheme.textStyles.footnote1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (video && !full) {
            val ratio = if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height else frame?.let { it.width.toFloat() / it.height } ?: 16f / 9f
            Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio(ratio.coerceIn(.2f, 5f)), contentAlignment = Alignment.Center) {
                val player = playback.player
                if (player != null && playback.ready) PlayerSurface(player = player, modifier = Modifier.fillMaxSize())
                else if (frame != null) Image(frame!!.asImageBitmap(), source.label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Text("视频首帧不可用", style = MiuixTheme.textStyles.footnote2)
                if (!playback.playing) IconButton(onClick = ::toggle) { Icon(Icons.Rounded.PlayArrow, "播放视频") }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = !playback.loading && !checking, onClick = ::toggle) { Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "暂停" else "播放") }
            Text("${mediaTime(playback.position)} / ${mediaTime(playback.duration)}", style = MiuixTheme.textStyles.footnote2, modifier = Modifier.weight(1f))
            if (video) {
                IconButton(onClick = playback::toggleMute) { Icon(if (playback.muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, if (playback.muted) "取消静音" else "静音") }
                IconButton(onClick = { full = true }) { Icon(Icons.Rounded.Fullscreen, "全屏") }
            }
            NexaCompactAction(text = "", icon = Icons.Rounded.Download, enabled = !saving, modifier = Modifier.semantics { contentDescription = "保存" }, onClick = { save.launch(safeAttachmentName(source.label)) })
            NexaCompactAction(text = "", icon = Icons.Rounded.Share, modifier = Modifier.semantics { contentDescription = "分享" }, onClick = { scope.launch {
                try { source.launch(context, share = true) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { Toast.makeText(context, "无法分享文件", Toast.LENGTH_SHORT).show() }
            } })
        }
        if (playback.ready) Slider(playback.position.toFloat(), { playback.seek(it.toLong()) }, valueRange = 0f..playback.duration.coerceAtLeast(1L).toFloat(), enabled = playback.duration > 0, modifier = Modifier.fillMaxWidth())
        (capacityFailure ?: playback.failure)?.let {
            Text("当前设备无法播放此编码：$it", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
            NexaCompactAction(text = "外部打开", onClick = { scope.launch {
                try { source.launch(context) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { Toast.makeText(context, "没有可打开此文件的应用，可点保存", Toast.LENGTH_LONG).show() }
            } })
        }
    }
    if (full) androidx.compose.ui.window.Dialog(onDismissRequest = { full = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val player = playback.player
                if (player != null) PlayerSurface(player = player, modifier = Modifier.fillMaxSize())
                else if (frame != null) Image(frame!!.asImageBitmap(), source.label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(text = if (playback.playing) "暂停" else "播放", onClick = ::toggle)
                Box(Modifier.weight(1f)) { Slider(playback.position.toFloat(), { playback.seek(it.toLong()) }, valueRange = 0f..playback.duration.coerceAtLeast(1L).toFloat(), modifier = Modifier.fillMaxWidth()) }
                TextButton(text = "关闭", onClick = { full = false })
            }
        }
    }
}

private fun mediaTime(milliseconds: Long): String = (milliseconds.coerceAtLeast(0) / 1000).let { "%d:%02d".format(it / 60, it % 60) }

/** Track MIME and device codecs are probed together; failed probes defer to the player, never claim success. */
private suspend fun probeMediaDecoder(context: Context, source: NexaAttachmentSource, video: Boolean): String? = withContext(Dispatchers.IO) {
    check(source.validate()) { "文件已不可读取" }
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, Uri.parse(source.uri), null)
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val prefix = if (video) "video/" else "audio/"
        val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true }
        if (tracks.isEmpty()) return@withContext null // Some supported containers are handled by ExoPlayer's extractors.
        val supported = tracks.any { format -> format.getString(MediaFormat.KEY_MIME) == "audio/raw" || codecs.findDecoderForFormat(format) != null }
        if (supported) null else "设备没有匹配的${if (video) "视频" else "音频"}解码器，可保存或外部打开"
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    finally { extractor.release() }
}

private object AttachmentNetwork {
    val client = okhttp3.OkHttpClient.Builder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
}
