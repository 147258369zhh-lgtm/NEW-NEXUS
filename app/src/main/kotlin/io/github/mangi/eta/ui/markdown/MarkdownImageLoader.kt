package io.github.mangi.eta.ui.markdown

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.caverock.androidsvg.SVG
import io.github.mangi.eta.ui.components.NexusImagePreview
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Every source uses intrinsic size, a transparent canvas and the same bounded decoder. */
@Composable
internal fun MarkdownImageBlock(block: MarkdownImage, modifier: Modifier = Modifier, onInternalOpen: (() -> Unit)? = null) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val hostView = LocalView.current
    var visible by remember(block.url) { mutableStateOf(false) }
    var retry by remember(block.url) { mutableIntStateOf(0) }
    var preview by remember(block.url) { mutableStateOf(false) }
    val state by produceState<ImageLoadState>(ImageLoadState.Loading, block.url, retry, visible) {
        if (visible && value !is ImageLoadState.Ready) {
            value = ImageLoadState.Loading
            value = MarkdownImageLoader.load(context, block.url)
        }
    }
    val label = block.alt.ifBlank { "图片" }
    BoxWithConstraints(modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = coordinates.isAttached && bounds.width > 0 && bounds.height > 0 && bounds.right > 0 && bounds.bottom > 0 && bounds.left < hostView.rootView.width && bounds.top < hostView.rootView.height
    }, contentAlignment = Alignment.TopStart) {
        when (val current = state) {
            ImageLoadState.Loading -> Text("正在加载图片", style = MiuixTheme.textStyles.footnote2, modifier = Modifier.padding(8.dp))
            is ImageLoadState.Ready -> {
                val density = LocalDensity.current
                val intrinsicWidth = with(density) { current.bitmap.width.toDp() }
                val intrinsicHeight = with(density) { current.bitmap.height.toDp() }
                val factor = minOf(1f, maxWidth.value / intrinsicWidth.value, 360f / intrinsicHeight.value)
                val imageModifier = Modifier.size(intrinsicWidth * factor, intrinsicHeight * factor)
                    .clickable { if (onInternalOpen != null) onInternalOpen() else preview = true }
                val animation = current.animation
                if (animation != null) {
                    val lifecycle = LocalLifecycleOwner.current.lifecycle
                    DisposableEffect(animation, lifecycle, visible) {
                        if (visible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) animation.start() else animation.stop()
                        val observer = LifecycleEventObserver { _, event ->
                            if (visible && event == Lifecycle.Event.ON_RESUME) animation.start()
                            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) animation.stop()
                        }
                        lifecycle.addObserver(observer)
                        onDispose { lifecycle.removeObserver(observer); animation.stop() }
                    }
                    AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_START; setImageDrawable(animation) } },
                        update = { it.setImageDrawable(animation) }, onRelease = { animation.stop(); it.setImageDrawable(null) }, modifier = imageModifier)
                } else Image(current.bitmap.asImageBitmap(), label, imageModifier, contentScale = ContentScale.Fit, alignment = Alignment.TopStart)
            }
            is ImageLoadState.Failed -> Column(Modifier.padding(8.dp)) {
                Text("图片无法解码，原文件仍可保存或打开", style = MiuixTheme.textStyles.footnote2)
                Row {
                    TextButton(text = "重试", onClick = { retry++ })
                    TextButton(text = "查看 / 保存", onClick = { if (onInternalOpen != null) onInternalOpen() else preview = true })
                    if (block.url.startsWith("https://") || block.url.startsWith("http://")) TextButton(text = "外部打开", onClick = { runCatching { uriHandler.openUri(block.url) } })
                }
            }
        }
    }
    NexusImagePreview(block.url, label, preview, { preview = false })
}

internal sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data class Ready(val bitmap: Bitmap, val animation: AnimatedImageDrawable? = null) : ImageLoadState
    data class Failed(val reason: String) : ImageLoadState
}

internal object MarkdownImageLoader {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    private val decoding = Semaphore(2)
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    // Lock striping also prevents duplicate downloads/decodes for the same source.
    private val locks = Array(16) { kotlinx.coroutines.sync.Mutex() }

    suspend fun load(context: Context, rawUrl: String): ImageLoadState = withContext(Dispatchers.IO) {
        cache.get(rawUrl)?.let { return@withContext ImageLoadState.Ready(it) }
        val lock = locks[(rawUrl.hashCode() and Int.MAX_VALUE) % locks.size]
        lock.lock()
        try {
            cache.get(rawUrl)?.let { return@withContext ImageLoadState.Ready(it) }
            decoding.withPermit {
                try {
                    val local = decodeLargeLocal(context, rawUrl)
                    if (local != null) {
                        currentCoroutineContext().ensureActive()
                        cache.put(rawUrl, local)
                        return@withPermit ImageLoadState.Ready(local)
                    }
                    val bytes = readBytes(context, rawUrl)
                    currentCoroutineContext().ensureActive()
                    val head = bytes.take(4096).toByteArray().toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
                    val svg = head.startsWith("<svg") || head.startsWith("<?xml") && head.contains("<svg")
                    val bitmap = if (svg) decodeSvg(bytes) else ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                        val size = boundedSize(info.size.width, info.size.height)
                        decoder.setTargetSize(size.first, size.second)
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = false
                    }
                    currentCoroutineContext().ensureActive()
                    val animation = if (!svg && bytes.size <= 2 * 1024 * 1024 && animationBudget(bytes, bitmap.width, bitmap.height)) {
                        runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                            decoder.setTargetSize(bitmap.width, bitmap.height)
                        } as? AnimatedImageDrawable }.getOrNull()
                    } else null
                    if (animation == null) cache.put(rawUrl, bitmap)
                    ImageLoadState.Ready(bitmap, animation)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { ImageLoadState.Failed(failure.message ?: "图片解码失败") }
            }
        } finally { lock.unlock() }
    }

    private fun decodeLargeLocal(context: Context, source: String): Bitmap? {
        val uri = Uri.parse(source)
        if (uri.scheme !in setOf("file", "content")) return null
        val size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: return null
        if (size <= MAX_IMAGE_BYTES || size > 256L * 1024 * 1024) return null
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val target = boundedSize(info.size.width, info.size.height)
                decoder.setTargetSize(target.first, target.second)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    private fun boundedSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && width <= 100_000 && height <= 100_000) { "图片尺寸超限" }
        val scale = minOf(1.0, 2048.0 / maxOf(width, height), kotlin.math.sqrt(2_000_000.0 / (width.toDouble() * height)))
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    private fun animationBudget(bytes: ByteArray, width: Int, height: Int): Boolean {
        if (width.toLong() * height > 500_000) return false
        val gif = bytes.size >= 6 && bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "GIF"
        val webp = bytes.size >= 12 && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
        if (!gif && !webp) return false
        // Conservative frame estimate: false positives simply use the first frame.
        val frames = if (gif) bytes.count { it == 0x2c.toByte() } else {
            var total = 0
            for (i in 0 until (bytes.size - 4).coerceAtLeast(0)) if (bytes[i] == 65.toByte() && bytes[i + 1] == 78.toByte() && bytes[i + 2] == 77.toByte() && bytes[i + 3] == 70.toByte()) total++
            total
        }
        return frames in 2..80 && width.toLong() * height * frames <= 8_000_000
    }

    private fun decodeSvg(bytes: ByteArray): Bitmap {
        require(bytes.size <= 1024 * 1024) { "SVG 过大" }
        val source = bytes.toString(Charsets.UTF_8)
        require(!Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(source)) { "SVG 外部实体不可用" }
        val document = Jsoup.parse(source, "", Parser.xmlParser())
        val root = document.children().singleOrNull { it.normalName() == "svg" } ?: error("SVG 无效")
        require(root.getAllElements().size <= 2000 && source.length <= 1_000_000) { "SVG 复杂度超限" }
        val forbidden = setOf("script", "foreignobject", "image", "audio", "video", "iframe", "style", "animate", "animatetransform", "set", "filter", "feimage")
        root.getAllElements().forEach { element ->
            require(element.normalName() !in forbidden) { "SVG 含不允许的节点" }
            element.attributes().forEach { attribute ->
                val key = attribute.key.lowercase()
                val value = attribute.value.trim()
                require(!key.startsWith("on") && key != "style" && key != "xml:base") { "SVG 含活动属性" }
                if (key == "href" || key == "xlink:href") require(value.startsWith('#') && value.length <= 128) { "SVG 外链不可用" }
                require(!Regex("url\\(\\s*['\"]?(?!#)", RegexOption.IGNORE_CASE).containsMatchIn(value)) { "SVG 外部资源不可用" }
            }
        }
        // No external resolver is registered; scripts/foreign content never enter a browser.
        val svg = SVG.getFromString(root.outerHtml())
        val viewBox = svg.documentViewBox
        val w = svg.documentWidth.takeIf { it > 0 } ?: viewBox?.width() ?: 512f
        val h = svg.documentHeight.takeIf { it > 0 } ?: viewBox?.height() ?: 512f
        val (width, height) = boundedSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
        svg.setDocumentWidth(width.toFloat()); svg.setDocumentHeight(height.toFloat())
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
    }

    /** Saving preserves original bytes even when the device cannot decode the image. */
    suspend fun exportOriginal(context: Context, rawUrl: String, destination: Uri) = withContext(Dispatchers.IO) {
        suspend fun copy(input: java.io.InputStream, sink: java.io.OutputStream) {
            val buffer = ByteArray(64 * 1024); var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer); if (count < 0) break
                total += count; require(total <= 256L * 1024 * 1024) { "图片保存上限 256 MiB，可外部打开原文件" }
                sink.write(buffer, 0, count)
            }
        }
        try {
            val output = context.contentResolver.openOutputStream(destination, "wt") ?: error("无法创建保存文件")
            output.use { sink ->
                when (Uri.parse(rawUrl).scheme?.lowercase()) {
                    "http", "https" -> client.newCall(Request.Builder().url(rawUrl).build()).execute().use { response ->
                        check(response.isSuccessful)
                        response.body.byteStream().use { copy(it, sink) }
                    }
                    "content", "file" -> (context.contentResolver.openInputStream(Uri.parse(rawUrl)) ?: error("无法读取图片")).use { copy(it, sink) }
                    "data" -> sink.write(readBytes(context, rawUrl))
                    else -> error("不支持的图片地址")
                }
                sink.flush()
            }
        } catch (failure: Throwable) { runCatching { context.contentResolver.delete(destination, null, null) }; throw failure }
    }

    private suspend fun readBytes(context: Context, rawUrl: String): ByteArray {
        require(rawUrl.length <= 18 * 1024 * 1024) { "图片地址超限" }
        val uri = Uri.parse(rawUrl)
        val bytes = when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val request = Request.Builder().url(rawUrl).header("Accept", "image/*").header("User-Agent", "NEXA-Markdown/1").build()
                val call = client.newCall(request)
                val response = suspendCancellableCoroutine<okhttp3.Response> { continuation ->
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: okhttp3.Call, failure: java.io.IOException) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                        }
                        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                            continuation.resume(response) { _, orphaned, _ -> orphaned.close() }
                        }
                    })
                }
                response.use {
                    check(it.isSuccessful) { "图片请求失败：HTTP ${it.code}" }
                    it.body.byteStream().use { stream -> readBounded(stream) }
                }
            }
            "content", "file" -> context.contentResolver.openInputStream(uri)?.use { readBounded(it) } ?: error("无法读取图片")
            "data" -> {
                val comma = rawUrl.indexOf(','); require(comma > 0)
                val meta = rawUrl.substring(0, comma); require(meta.startsWith("data:image/", true))
                val payload = rawUrl.substring(comma + 1)
                (if (meta.endsWith(";base64", true)) android.util.Base64.decode(payload, android.util.Base64.DEFAULT) else Uri.decode(payload).toByteArray()).also { require(it.size <= MAX_IMAGE_BYTES) }
            }
            else -> error("不支持的图片地址")
        }
        require(bytes.isNotEmpty()); return bytes
    }

    private suspend fun readBounded(stream: java.io.InputStream): ByteArray = ByteArrayOutputStream().use { output ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = stream.read(buffer); if (count < 0) break
            require(output.size().toLong() + count <= MAX_IMAGE_BYTES) { "图片超过 12 MiB，可从文件入口保存原图" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
}
