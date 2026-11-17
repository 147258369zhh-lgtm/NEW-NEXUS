package io.github.mangi.eta.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Optional native region detail for seekable local JPEG/PNG/WebP; sampled preview is always retained. */
internal object NexaRegionImageLoader {
    data class Info(val width: Int, val height: Int)
    private val gate = Semaphore(1)
    suspend fun info(context: Context, source: String): Info? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source)
        if (uri.scheme !in setOf("file", "content")) return@withContext null
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                require(descriptor.statSize in 1..256L * 1024 * 1024)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
                require(bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp"))
                require(bounds.outWidth in 1..100_000 && bounds.outHeight in 1..100_000)
                // ImageDecoder orients the base bitmap, but BitmapRegionDecoder returns raw coordinates.
                // Retain the base preview for rotated/mirrored JPEGs rather than overlaying mismatched tiles.
                if (bounds.outMimeType == "image/jpeg") {
                    @Suppress("DEPRECATION")
                    val orientation = android.media.ExifInterface(descriptor.fileDescriptor)
                        .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
                    if (orientation != android.media.ExifInterface.ORIENTATION_NORMAL) return@use null
                }
                Info(bounds.outWidth, bounds.outHeight)
            }
        }.getOrNull()
    }
    @Suppress("DEPRECATION")
    suspend fun region(context: Context, source: String, rect: Rect, targetWidth: Int, targetHeight: Int): Bitmap? = withContext(Dispatchers.IO) {
        gate.withPermit {
            currentCoroutineContext().ensureActive()
            val result = runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(source), "r")?.use { descriptor ->
                    require(descriptor.statSize in 1..256L * 1024 * 1024)
                    val decoder = BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false) ?: return@use null
                    try {
                        require(rect.left >= 0 && rect.top >= 0 && rect.right <= decoder.width && rect.bottom <= decoder.height)
                        require(rect.width() > 0 && rect.height() > 0)
                        var sample = 1
                        while (rect.width() / (sample * 2) >= targetWidth.coerceAtLeast(1) && rect.height() / (sample * 2) >= targetHeight.coerceAtLeast(1)) sample *= 2
                        while ((rect.width().toLong() / sample) * (rect.height() / sample) > 1_000_000L) sample *= 2
                        decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                    } finally { decoder.recycle() }
                }
            }.getOrNull()
            currentCoroutineContext().ensureActive()
            result
        }
    }
}
