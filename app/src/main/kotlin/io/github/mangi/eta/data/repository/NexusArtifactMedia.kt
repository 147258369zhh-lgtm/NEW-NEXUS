package io.github.mangi.eta.data.repository

import java.util.Locale

/** Media metadata wins over a cosmetic display label; generic MIME values may use the real path. */
internal object NexusArtifactMedia {
    enum class Kind { IMAGE, AUDIO, VIDEO, HTML, FILE }

    fun mimeType(mediaType: String?, name: String, path: String? = null): String {
        val declared = mediaType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) }
        if (declared != null && declared !in GENERIC_TYPES) return ALIASES[declared] ?: declared
        return listOfNotNull(name, path).firstNotNullOfOrNull { candidate ->
            val extension = candidate.substringBefore('?').substringBefore('#').substringAfterLast('/')
                .substringAfterLast('\\').substringAfterLast('.', "").lowercase(Locale.ROOT)
            EXTENSIONS[extension]
        } ?: declared ?: "application/octet-stream"
    }

    fun kind(mediaType: String?, name: String, path: String? = null): Kind =
        mimeType(mediaType, name, path).let { mime -> when {
            mime == "text/html" || mime == "application/xhtml+xml" -> Kind.HTML
            mime in IMAGE_DECODERS -> Kind.IMAGE
            mime in AUDIO_CONTAINERS -> Kind.AUDIO
            mime in VIDEO_CONTAINERS -> Kind.VIDEO
            else -> Kind.FILE
        } }

    /** A preview route is a candidate, never a promise that a container's codec is supported. */
    fun previewCapability(mediaType: String?, name: String, path: String? = null): String = when (kind(mediaType, name, path)) {
        Kind.IMAGE -> "设备图片解码器；失败保留原文件"
        Kind.AUDIO, Kind.VIDEO -> "设备媒体解码器；容器内编码仍需播放时验证"
        Kind.HTML -> "独立作品预览"
        Kind.FILE -> "文件卡与有界预览"
    }

    private val IMAGE_DECODERS = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp", "image/heic", "image/heif", "image/avif", "image/svg+xml")
    private val AUDIO_CONTAINERS = setOf("audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg", "audio/flac", "audio/wav", "audio/amr", "audio/3gpp", "audio/3gpp2")
    private val VIDEO_CONTAINERS = setOf("video/mp4", "video/webm", "video/x-matroska", "video/3gpp", "video/3gpp2", "video/quicktime")

    fun detectedMimeType(mediaType: String?, name: String, path: String? = null, header: ByteArray): String {
        val declared = mimeType(mediaType, "")
        return if (declared !in GENERIC_TYPES) declared else sniff(header) ?: mimeType(mediaType, name, path)
    }

    /** Small header signatures cover extensionless native and transport media without decoding it. */
    private fun sniff(bytes: ByteArray): String? {
        fun matches(offset: Int, vararg values: Int): Boolean = bytes.size >= offset + values.size &&
            values.indices.all { (bytes[offset + it].toInt() and 255) == values[it] }
        fun ascii(offset: Int, value: String) = bytes.size >= offset + value.length &&
            value.indices.all { bytes[offset + it].toInt() == value[it].code }
        return when {
            matches(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
            matches(0, 0xff, 0xd8, 0xff) -> "image/jpeg"
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "audio/wav"
            ascii(0, "RIFF") && ascii(8, "AVI ") -> "video/x-msvideo"
            ascii(0, "fLaC") -> "audio/flac"
            ascii(0, "OggS") -> if (bytes.toString(Charsets.ISO_8859_1).contains("theora")) "video/ogg" else "audio/ogg"
            bytes.size >= 2 && (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 0xf6) == 0xf0 -> "audio/aac"
            ascii(0, "ID3") || bytes.size >= 2 && (bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 0xe0) == 0xe0 -> "audio/mpeg"
            ascii(4, "ftyp") -> when (bytes.copyOfRange(8, minOf(bytes.size, 12)).toString(Charsets.US_ASCII)) {
                "avif", "avis" -> "image/avif"
                "heic", "heix", "hevc", "hevx", "mif1", "msf1" -> "image/heif"
                "M4A ", "M4B " -> "audio/mp4"
                "qt  " -> "video/quicktime"
                else -> "video/mp4"
            }
            ascii(0, "%PDF-") -> "application/pdf"
            else -> bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart().lowercase(Locale.ROOT).let {
                when { it.startsWith("<!doctype html") || it.startsWith("<html") -> "text/html"
                    it.startsWith("<svg") || it.startsWith("<?xml") && it.contains("<svg") -> "image/svg+xml"
                    else -> null }
            }
        }
    }

    private val GENERIC_TYPES = setOf("application/octet-stream", "binary/octet-stream", "application/unknown")
    private val ALIASES = mapOf("image/jpg" to "image/jpeg", "audio/mp3" to "audio/mpeg",
        "audio/m4a" to "audio/mp4", "audio/x-m4a" to "audio/mp4", "audio/x-wav" to "audio/wav",
        "video/m4v" to "video/mp4", "video/mkv" to "video/x-matroska")
    private val EXTENSIONS = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp",
        "gif" to "image/gif", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
        "avif" to "image/avif", "svg" to "image/svg+xml", "tif" to "image/tiff", "tiff" to "image/tiff",
        "wav" to "audio/wav", "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac",
        "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg", "flac" to "audio/flac",
        "aif" to "audio/aiff", "aiff" to "audio/aiff", "amr" to "audio/amr",
        "mp4" to "video/mp4", "webm" to "video/webm", "m4v" to "video/mp4", "mkv" to "video/x-matroska",
        "3gp" to "video/3gpp", "3g2" to "video/3gpp2", "mov" to "video/quicktime", "avi" to "video/x-msvideo",
        "htm" to "text/html", "html" to "text/html", "xhtml" to "application/xhtml+xml",
        "css" to "text/css", "js" to "text/javascript", "json" to "application/json", "txt" to "text/plain",
        "pdf" to "application/pdf", "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf",
        "zip" to "application/zip", "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar", "xz" to "application/x-xz", "gz" to "application/gzip",
        "csv" to "text/csv", "tsv" to "text/tab-separated-values", "md" to "text/markdown",
        "xml" to "application/xml", "yaml" to "text/yaml", "yml" to "text/yaml", "log" to "text/plain",
        "apk" to "application/vnd.android.package-archive",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "eps" to "application/postscript", "ps" to "application/postscript", "mhtml" to "multipart/related",
        "sqlite" to "application/vnd.sqlite3", "db" to "application/vnd.sqlite3", "stl" to "model/stl",
        "obj" to "model/obj", "glb" to "model/gltf-binary", "otf" to "font/otf",
        "torrent" to "application/x-bittorrent", "mid" to "audio/midi", "midi" to "audio/midi",
    )
}
