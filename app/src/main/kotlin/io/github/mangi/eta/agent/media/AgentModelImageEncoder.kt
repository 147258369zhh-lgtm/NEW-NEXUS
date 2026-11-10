package io.github.mangi.eta.agent.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.min
import kotlin.math.sqrt

internal const val MAX_AGENT_IMAGE_BYTES = 12 * 1024 * 1024

/** 仅负责为工具截图和聊天预览生成独立的图片副本。 */
internal object AgentModelImageEncoder {
    private const val MODEL_JPEG_QUALITY = 84
    private const val PREVIEW_JPEG_QUALITY = 80
    private const val MODEL_IMAGE_LONG_EDGE = 1_568
    private const val MODEL_IMAGE_MAX_PIXELS = 2_500_000L
    private const val ATTACHMENT_TARGET_BYTES = 480 * 1024
    private const val TOOL_VISION_TARGET_BYTES = 720 * 1024
    private const val MIN_MODEL_IMAGE_TARGET_BYTES = 96 * 1024
    private const val MIN_MODEL_IMAGE_EDGE = 320
    private const val MODEL_IMAGE_DIMENSION_RETRY_SCALE = 0.82
    private const val MAX_MODEL_IMAGE_ENCODING_ATTEMPTS = 6

    private data class EncodingProfile(
        val format: Bitmap.CompressFormat,
        val mimeType: String,
        val quality: Int,
        val maxLongEdge: Int? = null,
        val maxPixels: Long? = null,
        val targetBytes: Int? = null,
    )

    private data class ImageBounds(
        val width: Int,
        val height: Int,
        val mimeType: String,
    )

    private data class TargetSize(val width: Int, val height: Int)

    private val screenProfile = EncodingProfile(
        format = Bitmap.CompressFormat.PNG,
        mimeType = "image/png",
        quality = 100,
    )
    private val attachmentProfile = EncodingProfile(
        maxLongEdge = MODEL_IMAGE_LONG_EDGE,
        maxPixels = MODEL_IMAGE_MAX_PIXELS,
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = MODEL_JPEG_QUALITY,
        targetBytes = ATTACHMENT_TARGET_BYTES,
    )
    private val previewProfile = EncodingProfile(
        maxLongEdge = 512,
        maxPixels = 256_000L,
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = PREVIEW_JPEG_QUALITY,
    )
    private val toolVisionProfile = EncodingProfile(
        maxLongEdge = MODEL_IMAGE_LONG_EDGE,
        maxPixels = MODEL_IMAGE_MAX_PIXELS,
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = MODEL_JPEG_QUALITY,
        targetBytes = TOOL_VISION_TARGET_BYTES,
    )

    fun attachment(
        bytes: ByteArray,
        source: String,
        mimeHint: String,
        targetBytes: Int = ATTACHMENT_TARGET_BYTES,
    ): AgentModelClient.ModelImage? {
        if (!bytes.hasSupportedImageMagic()) return null
        val bounds = inspectBounds(bytes, mimeHint)
        val encoded = transcodeBytes(
            bytes = bytes,
            source = source,
            bounds = bounds,
            profile = attachmentProfile.copy(
                targetBytes = targetBytes.coerceIn(MIN_MODEL_IMAGE_TARGET_BYTES, ATTACHMENT_TARGET_BYTES),
            ),
        )
        // 普通附件保持 JPEG 兼容路径；截图不经过这里的解码与重编码。
        return encoded
    }

    fun screen(
        bitmap: Bitmap,
        source: String,
    ): AgentModelClient.ModelImage =
        // Bitmap 尚无文件编码，仅序列化一次 PNG；不缩放、不铺底、不转换像素。
        encodeBitmap(bitmap, source, screenProfile, flattenAlpha = false).copy(preserveOriginal = true)

    /** 文件工具图片在发送模型前统一编码为 JPEG。 */
    fun toolVision(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/jpeg",
    ): AgentModelClient.ModelImage? = runCatching {
        if (!bytes.hasSupportedImageMagic()) return@runCatching null
        transcodeBytes(
            bytes = bytes,
            source = source,
            bounds = inspectBounds(bytes, mimeHint),
            profile = toolVisionProfile,
        )
    }.getOrNull()

    fun preview(
        context: Context,
        image: AgentModelClient.ModelImage,
    ): AgentModelClient.ModelImage? = runCatching {
        val width = image.width ?: return@runCatching null
        val height = image.height ?: return@runCatching null
        val target = targetSize(width, height, previewProfile)
        val openStream = referenceStreamFactory(context, image.reference) ?: return@runCatching null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(width, height, target)
        }
        val decoded = openStream().use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return@runCatching null
        try {
            encodeBitmap(decoded, image.source, previewProfile, flattenAlpha = true)
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }.getOrNull()

    private fun transcodeBytes(
        bytes: ByteArray,
        source: String,
        bounds: ImageBounds,
        profile: EncodingProfile,
    ): AgentModelClient.ModelImage? {
        if (bounds.width <= 0 || bounds.height <= 0) return null
        val target = bounds.targetSize(profile)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(bounds.width, bounds.height, target)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return try {
            encodeBitmap(
                bitmap = bitmap,
                source = source,
                profile = profile,
                flattenAlpha = profile.format == Bitmap.CompressFormat.JPEG &&
                    !bounds.mimeType.equals("image/jpeg", ignoreCase = true),
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun encodeBitmap(
        bitmap: Bitmap,
        source: String,
        profile: EncodingProfile,
        flattenAlpha: Boolean,
    ): AgentModelClient.ModelImage {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "图片位图不可用" }
        var target = targetSize(bitmap.width, bitmap.height, profile)
        var lastEncoded: ByteArray? = null
        var lastWidth = target.width
        var lastHeight = target.height
        for (attempt in 0 until MAX_MODEL_IMAGE_ENCODING_ATTEMPTS) {
            val encodedBitmap = renderBitmap(
                bitmap = bitmap,
                target = target,
                flattenAlpha = flattenAlpha,
            )
            try {
                lastWidth = encodedBitmap.width
                lastHeight = encodedBitmap.height
                lastEncoded = encodeBitmapBytes(encodedBitmap, profile)
                if (lastEncoded.size <= (profile.targetBytes ?: MAX_AGENT_IMAGE_BYTES)) {
                    return lastEncoded.toModelImage(source, profile, lastWidth, lastHeight)
                }
            } finally {
                if (encodedBitmap !== bitmap && !encodedBitmap.isRecycled) {
                    encodedBitmap.recycle()
                }
            }
            val next = TargetSize(
                width = (target.width * MODEL_IMAGE_DIMENSION_RETRY_SCALE).toInt().coerceAtLeast(1),
                height = (target.height * MODEL_IMAGE_DIMENSION_RETRY_SCALE).toInt().coerceAtLeast(1),
            )
            if (minOf(next.width, next.height) < MIN_MODEL_IMAGE_EDGE) break
            target = next
        }
        val encoded = checkNotNull(lastEncoded) { "图片编码失败" }
        if (profile.targetBytes != null && encoded.size > profile.targetBytes) {
            // Very detailed images can remain above the soft target. The 12 MiB hard limit is
            // still enforced so a single malformed image can never break Binder or HTTP framing.
            require(encoded.size <= MAX_AGENT_IMAGE_BYTES) {
                "图片数据过大：${encoded.size}"
            }
        }
        return encoded.toModelImage(source, profile, lastWidth, lastHeight)
    }

    private fun encodeBitmapBytes(
        bitmap: Bitmap,
        profile: EncodingProfile,
    ): ByteArray {
        val initialCapacity = minOf(
            bitmap.width * bitmap.height / 4,
            2 * 1024 * 1024,
        ).coerceAtLeast(32 * 1024)
        return ByteArrayOutputStream(initialCapacity).use { output ->
            check(bitmap.compress(profile.format, profile.quality, output)) { "图片编码失败" }
            output.toByteArray().also { encoded ->
                require(encoded.isNotEmpty() && encoded.size <= MAX_AGENT_IMAGE_BYTES) {
                    "图片数据过大：${encoded.size}"
                }
            }
        }
    }

    private fun ByteArray.toModelImage(
        source: String,
        profile: EncodingProfile,
        width: Int,
        height: Int,
    ): AgentModelClient.ModelImage = AgentModelClient.ModelImage(
        reference = "data:${profile.mimeType};base64,${Base64.encodeToString(this, Base64.NO_WRAP)}",
        mimeType = profile.mimeType,
        bytes = size,
        width = width,
        height = height,
        source = source,
    )

    private fun renderBitmap(
        bitmap: Bitmap,
        target: TargetSize,
        flattenAlpha: Boolean,
    ): Bitmap {
        if (
            target.width == bitmap.width &&
            target.height == bitmap.height &&
            !flattenAlpha
        ) {
            return bitmap
        }
        return Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also { output ->
            val canvas = Canvas(output)
            if (flattenAlpha) canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                Rect(0, 0, target.width, target.height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
            )
            output.setHasAlpha(false)
        }
    }

    private fun inspectBounds(bytes: ByteArray, mimeHint: String): ImageBounds {
        require(bytes.isNotEmpty()) { "图片内容为空" }
        require(bytes.size <= MAX_AGENT_IMAGE_BYTES) { "图片数据过大：${bytes.size}" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return ImageBounds(
            width = options.outWidth,
            height = options.outHeight,
            mimeType = options.outMimeType
                ?.takeIf { it.isNotBlank() }
                ?: mimeHint.normalizedAgentImageMimeType(),
        )
    }

    private fun ImageBounds.targetSize(profile: EncodingProfile): TargetSize =
        targetSize(width, height, profile)

    private fun targetSize(
        width: Int,
        height: Int,
        profile: EncodingProfile,
    ): TargetSize {
        if (width <= 0 || height <= 0) return TargetSize(width, height)
        var scale = profile.maxLongEdge
            ?.let { limit -> min(1.0, limit.toDouble() / maxOf(width, height)) }
            ?: 1.0
        val scaledPixels = width.toDouble() * height * scale * scale
        profile.maxPixels?.let { limit ->
            if (scaledPixels > limit) {
                scale *= sqrt(limit / scaledPixels)
            }
        }
        return TargetSize(
            width = (width * scale).toInt().coerceIn(1, width),
            height = (height * scale).toInt().coerceIn(1, height),
        )
    }

    private fun sampleSize(
        width: Int,
        height: Int,
        target: TargetSize,
    ): Int {
        var sample = 1
        while (sample <= 64) {
            val next = sample * 2
            val nextWidth = width.toDouble() / next
            val nextHeight = height.toDouble() / next
            if (
                nextWidth < target.width * 0.9 ||
                nextHeight < target.height * 0.9
            ) {
                break
            }
            sample = next
        }
        return sample
    }

    private fun referenceStreamFactory(
        context: Context,
        reference: String,
    ): (() -> InputStream)? {
        val trimmed = reference.trim()
        if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            val marker = trimmed.indexOf("base64,", ignoreCase = true)
            if (marker < 0) return null
            val decoded = Base64.decode(trimmed.substring(marker + "base64,".length), Base64.DEFAULT)
            if (decoded.isEmpty() || decoded.size > MAX_AGENT_IMAGE_BYTES) return null
            return { ByteArrayInputStream(decoded) }
        }
        val directFile = File(trimmed)
        if (directFile.isAbsolute && directFile.isFile) {
            return { directFile.inputStream() }
        }
        val uri = Uri.parse(trimmed)
        if (uri.scheme == "content") {
            return {
                context.contentResolver.openInputStream(uri)
                    ?: error("无法打开本地图片")
            }
        }
        val file = when (uri.scheme) {
            "file" -> uri.path?.let(::File)
            null, "" -> directFile
            else -> null
        } ?: return null
        if (!file.isFile) return null
        return { file.inputStream() }
    }
}

internal fun String.normalizedAgentImageMimeType(): String =
    substringBefore(';')
        .trim()
        .takeIf {
            it.startsWith("image/", ignoreCase = true) &&
                !it.equals("image/*", ignoreCase = true)
        }
        ?: "image/jpeg"

internal fun ByteArray.hasSupportedImageMagic(): Boolean {
    if (size < 8) return false
    if (this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) return true
    if (
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
        this[2] == 0x4E.toByte() && this[3] == 0x47.toByte()
    ) {
        return true
    }
    if (
        this[0] == 'G'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte()
    ) {
        return true
    }
    if (
        size >= 12 &&
        this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
        this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
        this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()
    ) {
        return true
    }
    if (
        size >= 12 &&
        this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
        this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()
    ) {
        val brand = String(this, 8, 4, Charsets.US_ASCII)
        return brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")
    }
    return false
}
