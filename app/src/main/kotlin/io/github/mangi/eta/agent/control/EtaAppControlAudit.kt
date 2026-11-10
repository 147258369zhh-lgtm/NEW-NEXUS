package io.github.mangi.eta.agent.control

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, bounded audit trail for software-butler capabilities.
 *
 * The audit trail is intentionally separate from the user-visible conversation history. It records
 * actual registry calls, keeps enough context to explain changes, and redacts credential-shaped
 * fields before anything reaches disk.
 */
internal class EtaAppControlAuditStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "eta_app_control/audit.json").apply {
        parentFile?.mkdirs()
    }

    fun list(limit: Int = DEFAULT_LIMIT): List<JSONObject> = synchronized(lock) {
        readEntries().takeLast(limit.coerceIn(1, MAX_ENTRIES))
    }

    fun append(
        operation: String,
        capabilityId: String,
        parameters: JSONObject,
        success: Boolean,
        errorCode: String = "",
        durationMs: Long,
        before: JSONObject? = null,
        after: JSONObject? = null,
    ): JSONObject = synchronized(lock) {
        val entry = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("timestamp", System.currentTimeMillis())
            .put("actor", "software_butler")
            .put("operation", operation)
            .put("capabilityId", capabilityId)
            .put("parameters", redactObject(parameters, depth = 0))
            .put("success", success)
            .put("errorCode", errorCode)
            .put("durationMs", durationMs.coerceAtLeast(0L))
            .put("undone", false)
        before?.let { entry.put("before", redactObject(it, depth = 0)) }
        after?.let { entry.put("after", redactObject(it, depth = 0)) }
        val entries = (readEntries() + entry).takeLast(MAX_ENTRIES)
        writeEntries(entries)
        entry
    }

    fun markUndone(auditId: String): Boolean = synchronized(lock) {
        var changed = false
        val entries = readEntries().map { entry ->
            if (entry.optString("id") != auditId) entry else {
                changed = true
                JSONObject(entry.toString()).put("undone", true)
            }
        }
        if (changed) writeEntries(entries)
        changed
    }

    fun summary(): JSONObject = synchronized(lock) {
        val entries = readEntries()
        JSONObject()
            .put("total", entries.size)
            .put("failed", entries.count { !it.optBoolean("success", false) })
            .put("lastTimestamp", entries.lastOrNull()?.optLong("timestamp") ?: 0L)
    }

    private fun readEntries(): List<JSONObject> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readText(StandardCharsets.UTF_8).let { raw ->
                val array = JSONArray(raw.ifBlank { "[]" })
                buildList {
                    for (index in 0 until minOf(array.length(), MAX_ENTRIES)) {
                        array.optJSONObject(index)?.let(::add)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeEntries(entries: List<JSONObject>) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(JSONArray(entries).toString(), StandardCharsets.UTF_8)
        runCatching {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun redactObject(source: JSONObject, depth: Int): JSONObject {
        if (depth >= MAX_DEPTH) return JSONObject().put("truncated", true)
        return JSONObject().also { target ->
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                target.put(key.take(MAX_KEY_LENGTH), redactValue(key, source.opt(key), depth))
            }
        }
    }

    private fun redactValue(key: String, value: Any?, depth: Int): Any = when {
        isSensitiveKey(key) -> REDACTED
        value == null || value == JSONObject.NULL -> JSONObject.NULL
        value is JSONObject -> redactObject(value, depth + 1)
        value is JSONArray -> JSONArray().also { target ->
            for (index in 0 until minOf(value.length(), MAX_ARRAY_ITEMS)) {
                target.put(redactValue(key, value.opt(index), depth + 1))
            }
            if (value.length() > MAX_ARRAY_ITEMS) target.put("truncated")
        }
        value is String -> redactString(value).take(MAX_STRING_LENGTH)
        value is Number || value is Boolean -> value
        else -> value.toString().take(MAX_STRING_LENGTH)
    }

    private fun redactString(value: String): String = INLINE_SECRET.replace(value) { match ->
        "${match.groupValues[1]}=$REDACTED"
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase()
        return SENSITIVE_PARTS.any(normalized::contains)
    }

    private companion object {
        const val MAX_ENTRIES = 300
        const val DEFAULT_LIMIT = 50
        const val MAX_DEPTH = 4
        const val MAX_ARRAY_ITEMS = 20
        const val MAX_STRING_LENGTH = 500
        const val MAX_KEY_LENGTH = 128
        const val REDACTED = "[REDACTED]"
        val SENSITIVE_PARTS = listOf(
            "password",
            "passwd",
            "secret",
            "token",
            "api_key",
            "apikey",
            "authorization",
            "credential",
            "cookie",
            "private_key",
            "access_key",
            "refresh_key",
        )
        val INLINE_SECRET = Regex(
            "(?i)((?:password|passwd|secret|token|api[_-]?key|authorization|credential)=)[^&\\s]+",
        )
        val lock = Any()
    }
}
