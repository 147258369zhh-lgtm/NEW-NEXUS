package io.github.mangi.eta.ui.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.noties.jlatexmath.JLatexMathDrawable

/** Native TeX typesetting (fractions, matrices, scripts and aligned environments), never a WebView. */
internal object NexaMathRenderer {
    private val gate = Semaphore(1)
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    suspend fun render(latex: String, pixels: Float, color: Int): Bitmap? = withContext(Dispatchers.Default) {
        val key = "$pixels:$color:$latex"
        cache.get(key)?.let { return@withContext it }
        gate.withPermit {
            cache.get(key)?.let { return@withPermit it }
            currentCoroutineContext().ensureActive()
            try {
                require(latex.length <= 4096)
                require(!Regex("\\\\(?:includegraphics|input|include|write|openout|read|newcommand|renewcommand|def|gdef|loop|csname)\\b").containsMatchIn(latex))
                var depth = 0
                latex.forEach { if (it == '{') { depth++; require(depth <= 32) }; if (it == '}') depth-- }
                val drawable = JLatexMathDrawable.builder(latex).textSize(pixels.coerceIn(8f, 96f)).color(color).build()
                val width = drawable.intrinsicWidth.coerceAtLeast(1)
                val height = drawable.intrinsicHeight.coerceAtLeast(1)
                require(width <= 16384 && height <= 8192)
                val scale = minOf(1.0, kotlin.math.sqrt(1_000_000.0 / (width.toDouble() * height)))
                val w = (width * scale).toInt().coerceAtLeast(1); val h = (height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, w, h); drawable.draw(Canvas(bitmap))
                currentCoroutineContext().ensureActive()
                cache.put(key, bitmap); bitmap
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
}

@Composable
internal fun rememberMathBitmap(latex: String, fontSize: TextUnit, color: Color): Bitmap? {
    val pixels = with(LocalDensity.current) { fontSize.toPx() }
    val bitmap by produceState<Bitmap?>(null, latex, pixels, color) { value = NexaMathRenderer.render(latex, pixels, color.toArgb()) }
    return bitmap
}
