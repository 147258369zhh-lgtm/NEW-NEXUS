package io.github.mangi.eta.data.repository

import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.ProviderClientFactory
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderRequestPurpose
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.hasUsableConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal enum class ProviderHealthState { HEALTHY, CATALOG_AVAILABLE, FAILED }

internal data class ProviderHealthRecord(
    val state: ProviderHealthState,
    val modelId: String,
    val checkedAt: Long,
    val message: String = "",
)

internal data class ProviderProbeResult(val modelId: String, val message: String)

/** Latest non-secret connection probe result. Normal requests do not write this store. */
internal object ProviderConnectionHealthStore {
    private const val PREFS = "provider_connection_health"
    private const val PREFIX = "health."
    private val lock = Any()
    private var appContext: Context? = null
    private val mutableRecords = MutableStateFlow<Map<String, ProviderHealthRecord>>(emptyMap())
    val records: StateFlow<Map<String, ProviderHealthRecord>> = mutableRecords.asStateFlow()

    fun observe(context: Context): StateFlow<Map<String, ProviderHealthRecord>> {
        val app = context.applicationContext
        synchronized(lock) {
            if (appContext !== app) {
                appContext = app
                mutableRecords.value = readAll(prefs(app))
            }
        }
        return records
    }

    fun recordSuccess(context: Context, providerId: String, modelId: String) = record(
        context, providerId, modelId, ProviderHealthState.HEALTHY, "连接测试成功",
    )

    fun recordCatalogSuccess(context: Context, providerId: String) = record(
        context, providerId, "", ProviderHealthState.CATALOG_AVAILABLE, "模型目录连接成功",
    )

    fun recordCatalogFailure(context: Context, providerId: String) = record(
        context, providerId, "", ProviderHealthState.FAILED, "模型目录检测失败",
    )

    fun recordFailure(context: Context, providerId: String, modelId: String, message: String) = record(
        context,
        providerId,
        modelId,
        ProviderHealthState.FAILED,
        message.replace(Regex("\\s+"), " ").trim().take(160),
    )

    fun clear(context: Context, providerId: String) {
        val prefs = prefs(context.applicationContext)
        prefs.edit().remove(PREFIX + providerId).apply()
        synchronized(lock) { mutableRecords.value = mutableRecords.value - providerId }
    }

    private fun record(
        context: Context,
        providerId: String,
        modelId: String,
        state: ProviderHealthState,
        message: String,
    ) {
        if (providerId.isBlank()) return
        val value = ProviderHealthRecord(state, modelId, System.currentTimeMillis(), message)
        val json = JSONObject().put("state", state.name).put("modelId", modelId)
            .put("checkedAt", value.checkedAt).put("message", message)
        prefs(context.applicationContext).edit().putString(PREFIX + providerId, json.toString()).commit()
        synchronized(lock) { mutableRecords.value = mutableRecords.value + (providerId to value) }
    }

    private fun readAll(prefs: SharedPreferences): Map<String, ProviderHealthRecord> = buildMap {
        prefs.all.forEach { (key, raw) ->
            if (!key.startsWith(PREFIX) || raw !is String) return@forEach
            runCatching {
                val json = JSONObject(raw)
                val state = ProviderHealthState.valueOf(json.getString("state"))
                val checkedAt = json.optLong("checkedAt", 0L)
                val modelId = json.optString("modelId")
                if (checkedAt <= 0L || modelId.isBlank() && state == ProviderHealthState.HEALTHY) return@runCatching
                put(key.removePrefix(PREFIX), ProviderHealthRecord(
                    state, modelId, checkedAt, json.optString("message").take(240),
                ))
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

internal object ProviderConnectionProbe {
    suspend fun test(context: Context, provider: ProviderSetting): Result<ProviderProbeResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(provider.hasUsableConnection()) { "连接尚未配置或已停用" }
            val model = provider.models.firstOrNull { it.isEnabled && (it.outputModalities.isEmpty() ||
                it.outputModalities.any { modality -> modality.equals("text", ignoreCase = true) }) }
                ?: error("请先添加并启用至少一个文本模型")
            val config = RuntimeConfigRepository.buildRuntimeConfig(provider, model).copy(
                supportsTools = false,
                terminalTools = false,
                browserTools = false,
                deviceDirectTools = false,
                deviceSensitiveReadTools = false,
                deviceSensitiveActionTools = false,
                subagentTools = false,
                hostedWebSearchEnabled = false,
                supportsSearchSuggestions = false,
                autoCompactionEnabled = false,
            )
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "This is a short connection check. Reply with OK and no tool calls."))
                .put(JSONObject().put("role", "user").put("content", "Reply OK."))
            ProviderClientFactory.getClient(config).complete(
                ProviderRequest(config, messages, JSONArray(), purpose = ProviderRequestPurpose.CHAT),
                AgentRunController(),
            )
            ProviderProbeResult(model.id, "${model.displayName} 连接成功")
        }
    }
}
