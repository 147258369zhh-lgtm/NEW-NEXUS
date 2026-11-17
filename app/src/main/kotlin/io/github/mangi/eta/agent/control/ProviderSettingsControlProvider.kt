package io.github.mangi.eta.agent.control

import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.data.repository.ProviderRepository
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

internal object ProviderSettingsControlProvider : EtaControlProvider {
    override val providerId = "provider_settings"
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override fun capabilities(): List<EtaControlRegistration> {
        val properties = JSONObject().put("name", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 100))
            .put("isEnabled", JSONObject().put("type", "boolean"))
            .put("systemPrompt", JSONObject().put("type", JSONArray(listOf("string", "null"))).put("maxLength", 64_000))
            .put("hostedWebSearchEnabled", JSONObject().put("type", "boolean"))
        return listOf(EtaControlRegistration(
            info = EtaControlCapabilityInfo("settings.provider.configure", "模型提供商配置", "模型", "修改已有提供商的名称、启用、专属提示和托管搜索；连接与鉴权在原模型页面配置",
                EtaControlDanger.CONFIRM, JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("providerId", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 256))
                    .put("value", JSONObject().put("type", "object").put("properties", properties).put("additionalProperties", false)))
                    .put("required", JSONArray(listOf("providerId", "value"))).put("additionalProperties", false), false),
            snapshot = { _, args -> snapshot(args.getString("providerId"), args.optBoolean("privateChange")) },
            rollback = { _, before ->
                val id = before.getString("providerId")
                if (!before.getBoolean("exists")) ProviderRepository.deleteProvider(id)
                else {
                    val current = requireNotNull(ProviderRepository.providerById(id)) { "PROVIDER_NOT_FOUND" }
                    check(privateHash(current) == before.getString("privateConfigHash")) { "PROVIDER_PRIVATE_CONFIG_CHANGED" }
                    ProviderRepository.updateProvider(restore(current, before.getJSONObject("value")))
                }
                snapshot(id)
            },
            execute = { _, args ->
                val current = requireNotNull(ProviderRepository.providerById(args.getString("providerId"))) { "PROVIDER_NOT_FOUND" }
                val next = publicModel(current)
                val patch = args.getJSONObject("value")
                patch.keys().forEach { next.put(it, patch.get(it)) }
                ProviderRepository.updateProvider(restore(current, next))
                snapshot(current.id)
            },
        ))
    }

    internal suspend fun snapshot(id: String, privateChange: Boolean = false): JSONObject = JSONObject().put("providerId", id).apply {
        val current = ProviderRepository.providerById(id)
        put("exists", current != null)
        if (current != null) {
            put("value", publicModel(current)).put("privateConfigHash", privateHash(current))
            put("modelIndexHash", SettingsControlSnapshots.hash(JSONObject(codec.encodeToString<ProviderSetting>(current)).getJSONArray("models")
                .let { JSONObject().put("models", it) }))
        }
        if (privateChange || current != null && endpointSensitive(current.baseUrl)) put("credentials", "private configuration changed; re-entry required")
    }

    internal fun publicModel(value: ProviderSetting): JSONObject = JSONObject(codec.encodeToString<ProviderSetting>(value)).apply {
        remove("apiKey"); remove("customHeaders"); remove("customBody"); remove("models")
        if (endpointSensitive(value.baseUrl)) put("baseUrl", runCatching {
            val uri = java.net.URI(value.baseUrl)
            java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
        }.getOrDefault("[unavailable]"))
    }

    internal fun privateHash(value: ProviderSetting): String = SettingsControlSnapshots.hash(JSONObject(codec.encodeToString<ProviderSetting>(value)).let {
        JSONObject().put("key", it.getString("apiKey")).put("headers", it.getJSONArray("customHeaders"))
            .put("body", it.getJSONArray("customBody")).put("authMode", value.authMode).put("endpoint", value.baseUrl)
    })

    internal fun fingerprint(value: ProviderSetting): String = SettingsControlSnapshots.hash(JSONObject()
        .put("public", publicModel(value)).put("private", privateHash(value)))

    private fun restore(current: ProviderSetting, public: JSONObject): ProviderSetting {
        val private = JSONObject(codec.encodeToString<ProviderSetting>(current))
        val restored = JSONObject(public.toString()).put("apiKey", current.apiKey).put("customHeaders", private.getJSONArray("customHeaders"))
            .put("customBody", private.getJSONArray("customBody")).put("models", private.getJSONArray("models"))
        if (endpointSensitive(current.baseUrl)) restored.put("baseUrl", current.baseUrl)
        return codec.decodeFromString<ProviderSetting>(restored.toString())
    }

    private fun endpointSensitive(value: String): Boolean = runCatching {
        val uri = java.net.URI(value); uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null
    }.getOrDefault(true)
}
