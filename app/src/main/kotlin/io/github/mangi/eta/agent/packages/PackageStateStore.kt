package io.github.mangi.eta.agent.packages

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bounded package-private JSON state, separate from encrypted environment credentials. */
internal class PackageStateStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "capability-packages/state")
    fun invoke(id: String, arguments: JSONObject, writable: Boolean): JSONObject = synchronized(LOCK) {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}"))) { "PACKAGE_STATE_ID_INVALID" }
        val file = AtomicFile(File(root, "$id.json"))
        val current = if (file.baseFile.exists()) file.openRead().use { input ->
            val bytes = input.readNBytes(MAX_BYTES + 1); require(bytes.size <= MAX_BYTES) { "PACKAGE_STATE_SIZE_LIMIT" }
            JSONObject(bytes.toString(Charsets.UTF_8)).also { require(it.getInt("version") == 1 && it.getLong("revision") >= 0) }
        } else JSONObject().put("version", 1).put("revision", 0).put("values", JSONObject())
        val key = arguments.getString("key")
        require(key.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) && key !in setOf("__proto__", "prototype", "constructor")) { "PACKAGE_STATE_KEY_INVALID" }
        val values = current.getJSONObject("values")
        if (arguments.optString("action", "get") == "get") return@synchronized JSONObject().put("ok", true)
            .put("revision", current.getLong("revision")).put("value", values.opt(key) ?: JSONObject.NULL)
        require(writable) { "PACKAGE_STATE_READ_ONLY" }
        require(arguments.getLong("expectedRevision") == current.getLong("revision")) { "PACKAGE_STATE_REVISION_CHANGED" }
        when (arguments.getString("action")) {
            "put" -> { val value = arguments.get("value"); validate(value, 0); values.put(key, value) }
            "remove" -> values.remove(key)
            else -> error("PACKAGE_STATE_ACTION_INVALID")
        }
        require(values.length() <= 128) { "PACKAGE_STATE_KEY_LIMIT" }
        current.put("revision", Math.addExact(current.getLong("revision"), 1))
        val bytes = current.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "PACKAGE_STATE_SIZE_LIMIT" }
        check(root.isDirectory || root.mkdirs()) { "PACKAGE_STATE_STORAGE_UNAVAILABLE" }
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (failure: Throwable) { file.failWrite(output); throw failure }
        JSONObject().put("ok", true).put("revision", current.getLong("revision")).put("value", values.opt(key) ?: JSONObject.NULL)
    }
    fun clear(id: String) = synchronized(LOCK) {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")))
        AtomicFile(File(root, "$id.json")).delete()
    }
    private fun validate(value: Any?, depth: Int) {
        require(depth <= 16) { "PACKAGE_STATE_DEPTH_LIMIT" }
        when (value) {
            null, JSONObject.NULL -> Unit
            is String -> require(value.length <= MAX_BYTES)
            is Boolean -> Unit
            is Number -> require(value.toDouble().isFinite())
            is JSONArray -> { require(value.length() <= 256); for (index in 0 until value.length()) validate(value.get(index), depth + 1) }
            is JSONObject -> {
                require(value.length() <= 128)
                value.keys().forEach { key -> require(key.length <= 96 && key !in setOf("__proto__", "prototype", "constructor")); validate(value.get(key), depth + 1) }
            }
            else -> error("PACKAGE_STATE_VALUE_INVALID")
        }
    }
    private companion object { val LOCK = Any(); const val MAX_BYTES = 64 * 1024 }
}
