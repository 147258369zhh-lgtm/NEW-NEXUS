package io.github.mangi.eta.agent.control

import android.content.Context
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

internal object ModelSettingsControlProvider : EtaControlProvider {
    override val providerId = "model_settings"
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val publicFields = setOf("modelId", "displayName", "isEnabled", "contextWindowOverride", "inputModalities", "outputModalities",
        "attachment", "toolCall", "reasoningOverride", "reasoningCapabilitiesOverride", "structuredOutput", "supportsTemperature")

    override fun capabilities(): List<EtaControlRegistration> {
        val properties = JSONObject()
        publicFields.forEach { key -> properties.put(key, when (key) {
            "modelId", "displayName" -> JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 256)
            "isEnabled" -> JSONObject().put("type", "boolean")
            "contextWindowOverride" -> JSONObject().put("type", JSONArray(listOf("integer", "null"))).put("minimum", 1).put("maximum", 100_000_000)
            "inputModalities", "outputModalities" -> JSONObject().put("type", "array").put("maxItems", 16)
                .put("uniqueItems", true).put("items", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 32))
            "reasoningCapabilitiesOverride" -> JSONObject().put("type", JSONArray(listOf("object", "null")))
            else -> JSONObject().put("type", JSONArray(listOf("boolean", "null")))
        }) }
        return listOf(EtaControlRegistration(
            info = EtaControlCapabilityInfo("settings.model.configure", "模型参数", "模型", "修改已有模型的上下文、思考、工具和多模态能力；密钥仍由原模型设置管理",
                EtaControlDanger.WRITE, JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("providerId", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 256))
                    .put("modelId", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 256))
                    .put("value", JSONObject().put("type", "object").put("properties", properties).put("additionalProperties", false)))
                    .put("required", JSONArray(listOf("providerId", "modelId", "value"))).put("additionalProperties", false), false),
            snapshot = { _, parameters -> snapshot(parameters) },
            rollback = { _, before ->
                val providerId = before.getString("providerId")
                val id = before.getString("modelId")
                if (!before.getBoolean("exists")) ModelRepository.deleteModel(providerId, id)
                else {
                    val current = requireNotNull(model(providerId, id)) { "MODEL_NOT_FOUND" }
                    check(privateHash(current) == before.getString("privateConfigHash")) { "MODEL_PRIVATE_CONFIG_CHANGED" }
                    ModelRepository.saveModel(providerId, codec.decodeFromString<Model>(before.getJSONObject("model").toString())
                        .copy(customHeaders = current.customHeaders, customBody = current.customBody))
                }
                snapshot(JSONObject().put("providerId", providerId).put("modelId", id))
            },
            execute = { _, parameters ->
                val providerId = parameters.getString("providerId")
                val current = requireNotNull(model(providerId, parameters.getString("modelId"))) { "MODEL_NOT_FOUND" }
                val patch = parameters.getJSONObject("value")
                require(patch.keys().asSequence().all { it in publicFields }) { "MODEL_PARAMETER_UNSUPPORTED" }
                val next = JSONObject(codec.encodeToString(current))
                patch.keys().forEach { next.put(it, patch.get(it)) }
                val saved = ModelRepository.saveModel(providerId, codec.decodeFromString<Model>(next.toString()))
                publicModel(saved)
            },
        ))
    }

    internal suspend fun model(providerId: String, id: String): Model? =
        requireNotNull(ProviderRepository.providerById(providerId)) { "PROVIDER_NOT_FOUND" }.models.singleOrNull { it.id == id }

    internal suspend fun uiParameters(providerId: String, draft: Model): JSONObject {
        val before = model(providerId, draft.id)
        return JSONObject().put("providerId", providerId).put("modelId", draft.id)
            .put("value", publicModel(draft)).put("privateConfigChanged", before?.let(::privateHash) != privateHash(draft) &&
                (before != null || draft.customHeaders.isNotEmpty() || draft.customBody.isNotEmpty()))
    }

    private suspend fun snapshot(parameters: JSONObject): JSONObject {
        val providerId = parameters.getString("providerId")
        val id = parameters.getString("modelId")
        val current = model(providerId, id)
        return JSONObject().put("providerId", providerId).put("modelId", id).put("exists", current != null).apply {
            if (current != null) put("model", publicModel(current)).put("privateConfigHash", privateHash(current))
            if (parameters.optBoolean("privateConfigChanged")) put("credentials", "private configuration changed; rollback requires local re-entry")
        }
    }

    internal fun publicModel(model: Model): JSONObject = JSONObject(codec.encodeToString(model)).apply {
        remove("customHeaders"); remove("customBody")
    }

    private fun privateHash(model: Model): String = SettingsControlSnapshots.hash(JSONObject()
        .put("headers", JSONObject(codec.encodeToString(model)).getJSONArray("customHeaders"))
        .put("body", JSONObject(codec.encodeToString(model)).getJSONArray("customBody")))
}
