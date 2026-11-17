package io.github.mangi.eta.agent.packages

import android.content.Context
import io.github.mangi.eta.data.repository.McpSecretStore
import org.json.JSONObject

/** Package-scoped encrypted configuration; never falls back to OS or other packages' variables. */
internal class PackageEnvironmentStore(context: Context, private val secrets: McpSecretStore = McpSecretStore(context)) {
    fun names(id: String): List<String> = synchronized(LOCK) { read(id).keys().asSequence().sorted().toList() }
    fun value(id: String, name: String): String? = synchronized(LOCK) { require(NAME.matches(name)); read(id).optString(name).takeIf { it.isNotEmpty() } }
    fun put(id: String, name: String, value: String?) = synchronized(LOCK) {
        require(NAME.matches(name)) { "配置名称只支持字母、数字和下划线" }
        require(value == null || value.toByteArray().size <= 4096) { "单项配置超过 4 KiB" }
        val current = read(id)
        if (value.isNullOrEmpty()) current.remove(name) else current.put(name, value)
        require(current.length() <= 32 && current.toString().toByteArray().size <= 32 * 1024) { "扩展包配置超过大小限制" }
        if (current.length() == 0) secrets.clear(alias(id)) else secrets.setBearerToken(alias(id), current.toString())
    }
    fun clear(id: String) = synchronized(LOCK) { secrets.clear(alias(id)) }
    fun putValues(id: String, values: JSONObject) = synchronized(LOCK) {
        require(values.length() <= 32)
        val current = read(id)
        values.keys().forEach { key ->
            require(NAME.matches(key)) { "配置名称无效" }
            val value = values.getString(key)
            require(value.toByteArray().size <= 4096) { "单项配置超过 4 KiB" }
            if (value.isEmpty()) current.remove(key) else current.put(key, value)
        }
        require(current.length() <= 32 && current.toString().toByteArray().size <= 32 * 1024) { "扩展包配置超过大小限制" }
        if (current.length() == 0) secrets.clear(alias(id)) else secrets.setBearerToken(alias(id), current.toString())
    }
    fun portable(id: String): String? = synchronized(LOCK) { secrets.bearerToken(alias(id)) }
    fun restore(id: String, raw: String) = synchronized(LOCK) {
        val value = JSONObject(raw)
        require(value.length() <= 32 && raw.toByteArray().size <= 32 * 1024)
        value.keys().forEach { key -> require(NAME.matches(key) && value.getString(key).toByteArray().size <= 4096) }
        secrets.setBearerToken(alias(id), raw)
    }
    private fun read(id: String): JSONObject = secrets.bearerToken(alias(id))?.let(::JSONObject) ?: JSONObject()
    private fun alias(id: String): String { require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}"))); return "package-env:$id" }
    companion object { private val LOCK = Any(); private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}") }
}
