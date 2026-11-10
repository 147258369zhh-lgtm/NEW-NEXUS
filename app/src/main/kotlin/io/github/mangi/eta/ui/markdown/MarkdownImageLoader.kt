package io.github.mangi.eta.ui.markdown

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MarkdownImageBlock(block: MarkdownImage, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val state by produceState<ImageLoadState>(ImageLoadState.Loading, block.url) {
        value = MarkdownImageLoader.load(context, block.url)
    }
    val label = block.alt.ifBlank { block.url }
    when (val current = state) {
        ImageLoadState.Loading -> ImagePlaceholder(label, loading = true, modifier)
        is ImageLoadState.Ready -> Image(
            bitmap = current.bitmap.asImageBitmap(),
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .squircleSurface(MiuixTheme.colorScheme.surfaceContainerHigh, 12.dp)
                .clickable { runCatching { uriHandler.openUri(block.url) } },
        )
        is ImageLoadState.Failed -> ImagePlaceholder(
            label = label,
            loading = false,
            modifier = modifier.clickable { runCatching { uriHandler.openUri(block.url) } },
        )
    }
}

@Composable
private fun ImagePlaceholder(label: String, loading: Boolean, modifier: Modifier) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .squircleSurface(colors.surfaceContainerHigh, 12.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (loading) Icons.Outlined.Image else Icons.Outlined.BrokenImage,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (loading) colors.onSurfaceVariantSummary else colors.error,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (loading) "正在加载图片" else "图片无法显示",
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data class Ready(val bitmap: Bitmap) : ImageLoadState
    data class Failed(val reason: String) : ImageLoadState
}

private object MarkdownImageLoader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(context: Context, rawUrl: String): ImageLoadState = withContext(Dispatchers.IO) {
        cache.get(rawUrl)?.let { return@withContext ImageLoadState.Ready(it) }
        runCatching {
            val bytes = readBytes(context, rawUrl)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "图片格式无效" }
            var sample = 1
            while (options.outWidth / sample > 3_840 || options.outHeight / sample > 3_840) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: error("图片解码失败")
            cache.put(rawUrl, bitmap)
            ImageLoadState.Ready(bitmap)
        }.getOrElse { ImageLoadState.Failed(it.message ?: it.javaClass.simpleName) }
    }

    private fun readBytes(context: Context, rawUrl: String): ByteArray {
        val uri = Uri.parse(rawUrl)
        val input = when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val request = Request.Builder()
                    .url(rawUrl)
                    .header("Accept", "image/*")
                    .header("User-Agent", "Nexus-Markdown/1")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    error("图片请求失败：HTTP ${response.code}")
                }
                response.body.byteStream().use { stream -> readBounded(stream, MAX_IMAGE_BYTES) }
            }
            "content", "file" -> context.contentResolver.openInputStream(uri)
                ?.use { readBounded(it, MAX_IMAGE_BYTES) }
                ?: error("无法读取图片")
            "data" -> decodeDataUri(rawUrl)
            else -> error("不支持的图片地址")
        }
        require(input.isNotEmpty()) { "图片为空" }
        return input
    }

    private fun readBounded(stream: java.io.InputStream, limit: Int): ByteArray = ByteArrayOutputStream().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "图片超过大小限制" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun decodeDataUri(raw: String): ByteArray {
        val comma = raw.indexOf(',')
        require(comma > 0) { "图片 data URI 无效" }
        val meta = raw.substring(0, comma)
        val payload = raw.substring(comma + 1)
        return if (meta.endsWith(";base64", ignoreCase = true)) {
            android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        } else {
            Uri.decode(payload).toByteArray(Charsets.UTF_8)
        }.also { require(it.size <= MAX_IMAGE_BYTES) { "图片超过大小限制" } }
    }

    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
}
