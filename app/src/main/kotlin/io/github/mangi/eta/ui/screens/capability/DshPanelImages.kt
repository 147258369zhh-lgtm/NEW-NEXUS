package io.github.mangi.eta.ui.screens.capability

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A panel owns its images across feed updates; all decoded images share one small pixel budget. */
internal class DshPanelImages(parent: CoroutineScope, private val asset: suspend (String) -> ByteArray) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val admission = Semaphore(2)
    private val cache = linkedMapOf<String, Deferred<ImageBitmap?>>()
    private var pixels = 0L

    suspend fun image(src: String): ImageBitmap? {
        val result = synchronized(cache) {
            cache[src] ?: if (cache.size >= 16 || !job.isActive) null else scope.async(Dispatchers.IO) {
                admission.withPermit {
                    var reserved = 0L
                    var charged = false
                    try {
                        val bytes = if (src.startsWith("data:image/") && ";base64," in src) {
                            require(src.substringBefore(';') in setOf("data:image/png", "data:image/jpeg", "data:image/webp", "data:image/gif"))
                            require(src.length <= 90_000)
                            Base64.decode(src.substringAfter(";base64,"), Base64.DEFAULT).also { require(it.size <= 64 * 1024) }
                        } else { require(src.startsWith("asset:")); asset(src.removePrefix("asset:")) }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 4_000_000)
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 768) sample *= 2
                        reserved = ((bounds.outWidth + sample - 1) / sample).toLong() * ((bounds.outHeight + sample - 1) / sample)
                        synchronized(cache) { require(pixels + reserved <= 2_000_000); pixels += reserved; charged = true }
                        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                            BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false }))
                        currentCoroutineContext().ensureActive()
                        decoded.asImageBitmap()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (charged) synchronized(cache) { pixels -= reserved }; null }
                }
            }.also { cache[src] = it }
        }
        return result?.await()
    }

    override fun close() { job.cancel(); synchronized(cache) { cache.clear() } }
}
