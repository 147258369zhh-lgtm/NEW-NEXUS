package io.github.mangi.eta.agent.control

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.WebToolBackend
import io.github.mangi.eta.data.model.WebToolsSettings
import io.github.mangi.eta.data.repository.*
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

internal object WebToolsSettingsControlProvider : EtaControlProvider {
    override val providerId = "web_tools_settings"
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    override fun capabilities(): List<EtaControlRegistration> = listOf(EtaControlRegistration(
        info = EtaControlCapabilityInfo("settings.web_tools", "网页搜索与读取", "工具", "切换已配置的网页搜索、读取后端；连接地址和密钥在原设置页管理",
            EtaControlDanger.WRITE, JSONObject().put("type", "object").put("properties", JSONObject().put("value", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("searchBackend", backend()).put("fetchBackend", backend())).put("additionalProperties", false)))
                .put("required", JSONArray().put("value")).put("additionalProperties", false), true),
        read = { snapshot(it) },
        snapshot = { context, args -> snapshot(context, args.optBoolean("privateChange")) },
        rollback = { context, before ->
            val current = WebToolsSettingsRepository.settings()
            check(privateHash(context, current) == before.getString("privateConfigHash")) { "WEB_PRIVATE_CONFIG_CHANGED" }
            val value = before.getJSONObject("value")
            val restored = codec.decodeFromString<WebToolsSettings>(value.toString()).let {
                current.copy(searchBackend = it.searchBackend, fetchBackend = it.fetchBackend,
                    deepSeek = it.deepSeek, customSearch = it.customSearch.copy(headersText = current.customSearch.headersText, bodyTemplate = current.customSearch.bodyTemplate),
                    customFetch = it.customFetch.copy(headersText = current.customFetch.headersText, bodyTemplate = current.customFetch.bodyTemplate))
            }
            validate(context, restored)
            SettingsDataStore.setWebToolsSettings(restored)
            snapshot(context)
        },
        execute = { context, args ->
            val before = WebToolsSettingsRepository.settings()
            val patch = args.getJSONObject("value")
            val updated = before.copy(searchBackend = if (patch.has("searchBackend")) WebToolBackend.valueOf(patch.getString("searchBackend")) else before.searchBackend,
                fetchBackend = if (patch.has("fetchBackend")) WebToolBackend.valueOf(patch.getString("fetchBackend")) else before.fetchBackend)
            validate(context, updated)
            SettingsDataStore.setWebToolsSettings(updated)
            snapshot(context)
        },
    ))
    private fun backend() = JSONObject().put("type", "string").put("enum", JSONArray(WebToolBackend.entries.map { it.name }))

    internal suspend fun snapshot(context: Context, privateChange: Boolean = false): JSONObject {
        val config = WebToolsSettingsRepository.settings()
        return JSONObject().put("value", publicValue(config)).put("privateConfigHash", privateHash(context, config)).apply {
            if (privateChange || listOf(config.deepSeek.baseUrl, config.customSearch.url, config.customFetch.url).any { '?' in it || '#' in it || '@' in it })
                put("credentials", "private configuration changed; re-entry required")
        }
    }

    internal fun publicValue(value: WebToolsSettings): JSONObject = JSONObject(codec.encodeToString(value)).apply {
        getJSONObject("customSearch").remove("headersText"); getJSONObject("customSearch").remove("bodyTemplate")
        getJSONObject("customFetch").remove("headersText"); getJSONObject("customFetch").remove("bodyTemplate")
        fun scrub(row: JSONObject, key: String) {
            val raw = row.getString(key)
            if ('?' in raw || '#' in raw || '@' in raw) row.put(key, raw.substringBefore('?').substringBefore('#').let {
                if ('@' in it) "[private endpoint]" else it
            })
        }
        scrub(getJSONObject("deepSeek"), "baseUrl"); scrub(getJSONObject("customSearch"), "url"); scrub(getJSONObject("customFetch"), "url")
    }

    internal fun privateHash(context: Context, config: WebToolsSettings): String = SettingsControlSnapshots.hash(JSONObject()
        .put("searchHeaders", config.customSearch.headersText).put("searchBody", config.customSearch.bodyTemplate)
        .put("fetchHeaders", config.customFetch.headersText).put("fetchBody", config.customFetch.bodyTemplate)
        .put("searchUrl", config.customSearch.url).put("fetchUrl", config.customFetch.url).put("deepSeekUrl", config.deepSeek.baseUrl)
        .put("ciphertext", context.getSharedPreferences("eta_web_tools_secrets", Context.MODE_PRIVATE).getString("credentials", null)))

    private suspend fun validate(context: Context, config: WebToolsSettings) = validateWebToolsConfiguration(config,
        WebToolsSettingsRepository.credentials(context), WebToolsSettingsRepository.deepSeekProviderCredentials() != null)
}
