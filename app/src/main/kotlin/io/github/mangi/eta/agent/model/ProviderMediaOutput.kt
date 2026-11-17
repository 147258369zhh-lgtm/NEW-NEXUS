package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.repository.NexusArtifactMedia
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Bounded transport attachments only. Remote URLs and prose never authorize a file read/download. */
internal object ProviderMediaOutput {
    private const val KEY = "_nexa_output_media"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_ENCODED = (MAX_BYTES + 2) / 3 * 4

    fun attach(message: JSONObject, parts: JSONArray): JSONObject = message.apply {
        val files = JSONArray()
        val links = mutableListOf<String>()
        for (index in 0 until parts.length()) {
            val part = parts.getJSONObject(index)
            if (part.has("data")) files.put(part)
            else part.optString("url").takeIf(String::isNotBlank)?.let { url ->
                links += part.optString("name").takeIf(String::isNotBlank)?.let { "$it: $url" } ?: url
            }
        }
        if (files.length() > 0) put(KEY, files)
        // Preserve a real remote reference as a link; it becomes a file only after the explicit download tool succeeds.
        if (links.isNotEmpty()) put("content", listOf(optString("content"), links.joinToString("\n"))
            .filter(String::isNotBlank).joinToString("\n\n"))
    }

    fun hasFiles(message: JSONObject): Boolean = (message.optJSONArray(KEY)?.length() ?: 0) > 0

    fun takeFiles(message: JSONObject): List<AgentModelClient.ToolFile> {
        val parts = message.remove(KEY) as? JSONArray ?: return emptyList()
        var total = 0L
        return buildList {
            for (index in 0 until minOf(parts.length(), 8)) {
                val item = parts.getJSONObject(index)
                val encoded = item.getString("data")
                if (encoded.length > MAX_ENCODED) invalid()
                val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { invalid() }
                total += bytes.size
                if (bytes.isEmpty() || bytes.size > MAX_BYTES || total > 16L * 1024 * 1024) invalid()
                val mime = NexusArtifactMedia.mimeType(item.optString("mime"), item.optString("name"))
                add(AgentModelClient.ToolFile(item.optString("name").ifBlank { "output-${index + 1}.${extension(mime)}" }, mime, bytes))
            }
        }
    }

    /** Collect only documented output/content containers, never tool arguments or arbitrary JSON fields. */
    fun collect(raw: Any?, target: JSONArray = JSONArray(), depth: Int = 0): JSONArray {
        if (raw == null || depth > 5) return target
        if (raw is JSONArray) {
            for (index in 0 until minOf(raw.length(), 2048)) collect(raw.opt(index), target, depth + 1)
            return target
        }
        val item = raw as? JSONObject ?: return target
        val type = item.optString("type")
        if (type == "image_generation_call") {
            add(target, item.optString("result"), "image/${item.optString("output_format", "png")}", item.optString("filename"))
        }
        if (type in setOf("image", "output_image", "image_url", "audio", "output_audio", "video", "output_video")) {
            val nested = item.optJSONObject("inline_data") ?: item.optJSONObject("inlineData") ?: item
            val mime = nested.optString("mime_type").ifBlank { nested.optString("mimeType") }.ifBlank {
                when {
                    type.contains("image") -> "image/png"
                    type.contains("audio") -> audioMime(item.optString("format"))
                    else -> "video/mp4"
                }
            }
            val value = nested.optString("data").ifBlank { nested.optString("b64_json") }.ifBlank {
                item.optJSONObject("image_url")?.optString("url") ?: item.optString("image_url")
            }.ifBlank { item.optString("url") }
            add(target, value, mime, item.optString("filename").ifBlank { item.optString("name") })
        }
        // OpenAI Chat audio is a named field rather than a content block.
        item.optJSONObject("audio")?.let { audio ->
            add(target, audio.optString("data"), audioMime(audio.optString("format")), audio.optString("filename"))
        }
        listOf("content", "output", "outputs", "images").forEach { key ->
            item.optJSONArray(key)?.let { collect(it, target, depth + 1) }
        }
        return target
    }

    fun add(target: JSONArray, raw: String, mimeType: String, name: String = "") {
        if (raw.isBlank()) return
        var mime = mimeType
        val encoded = if (raw.startsWith("data:", ignoreCase = true)) {
            val separator = raw.indexOf(',')
            if (separator < 0 || !raw.substring(0, separator).endsWith(";base64", ignoreCase = true)) return
            mime = raw.substring(5, separator).substringBefore(';')
            raw.substring(separator + 1)
        } else {
            // A URL is a reference, not bytes, and is never downloaded implicitly.
            if (raw.startsWith("http:", true) || raw.startsWith("https:", true)) {
                val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return
                if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || raw.any(Char::isISOControl)) return
                for (index in 0 until target.length()) if (target.getJSONObject(index).optString("url") == raw) return
                if (target.length() >= 8) invalid()
                target.put(JSONObject().put("url", uri.toASCIIString()).put("mime", mime).put("name", name.take(255)))
                return
            }
            if (raw.startsWith("content:", true) || raw.startsWith("file:", true)) return
            raw
        }
        if (encoded.length > MAX_ENCODED || target.length() >= 8) invalid()
        if (!mime.startsWith("image/") && !mime.startsWith("audio/") && !mime.startsWith("video/")) return
        for (index in 0 until target.length()) if (target.getJSONObject(index).optString("data") == encoded) return
        target.put(JSONObject().put("mime", mime).put("data", encoded).put("name", name.take(255)))
    }

    fun audioMime(format: String): String = when (format.lowercase()) {
        "mp3" -> "audio/mpeg"; "opus", "ogg" -> "audio/ogg"; "flac" -> "audio/flac"
        "aac" -> "audio/aac"; "m4a", "mp4" -> "audio/mp4"; else -> "audio/wav"
    }
    private fun extension(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"; "image/svg+xml" -> "svg"; "audio/mpeg" -> "mp3"; "audio/mp4" -> "m4a"
        "audio/ogg" -> "ogg"; "video/quicktime" -> "mov"; else -> mime.substringAfter('/').substringBefore('+')
    }
    private fun invalid(): Nothing = throw AgentModelFailure("MODEL_MEDIA_INVALID", false, "模型返回的媒体无效或超过保存容量限制。")
}
