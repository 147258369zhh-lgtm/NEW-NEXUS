package io.github.mangi.eta.data.repository

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Serializable
internal data class LocalModelPerformanceResult(
    val latencyMs: Long,
    val completionTokens: Int?,
    val tokensPerSecond: Double?,
    val measuredAt: Long = System.currentTimeMillis(),
)

internal data class LocalModelServiceDiscovery(
    val modelIds: List<String>,
    val latencyMs: Long,
    val sourceUrl: String,
)

internal data class LocalDevicePerformanceSnapshot(
    val socManufacturer: String,
    val socModel: String,
    val deviceModel: String,
    val androidSdk: Int,
    val cpuCores: Int,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val freeStorageBytes: Long,
    val thermalStatus: Int,
    val batteryPercent: Int?,
    val powerSaveMode: Boolean,
    val charging: Boolean = false,
    val batteryTemperatureC: Float? = null,
    val screenInteractive: Boolean = true,
    val deviceIdle: Boolean = false,
) {
    val socLabel: String
        get() = when {
            socModel.equals("SM8650", ignoreCase = true) -> "Snapdragon 8 Gen 3"
            socManufacturer.isBlank() -> socModel.ifBlank { "Unknown SoC" }
            else -> "$socManufacturer $socModel".trim()
        }

    val isFindX7Ultra: Boolean
        get() = deviceModel.equals("PHY110", ignoreCase = true) &&
            socModel.equals("SM8650", ignoreCase = true)
}

internal enum class LocalPerformanceState {
    OPTIMAL,
    WATCH,
    THROTTLED,
}

internal enum class LocalPerformanceWarning {
    LOW_AVAILABLE_MEMORY,
    LOW_STORAGE,
    THERMAL_PRESSURE,
    POWER_SAVING,
}

internal data class LocalModelPerformanceProfile(
    val contextWindow: Int,
    val threads: Int,
    val gpuLayers: Int,
    val memoryBudgetBytes: Long,
    val state: LocalPerformanceState,
    val warnings: List<LocalPerformanceWarning>,
)

/**
 * Device-aware local inference planning. This layer deliberately does not pretend to launch a
 * bundled runtime; it supplies conservative budgets and verifies an already running local
 * OpenAI-compatible service end to end.
 */
internal object LocalModelPerformanceMonitor {
    private val probeHttp = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    private val benchmarkHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun capture(context: Context): LocalDevicePerformanceSnapshot {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.getSystemService(BatteryManager::class.java)
        val batteryIntent = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }
        val batteryTemperatureC = batteryIntent
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeUnless { it == Int.MIN_VALUE }
            ?.div(10f)
        val storage = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
        return LocalDevicePerformanceSnapshot(
            socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER.orEmpty()
            } else {
                Build.MANUFACTURER.orEmpty()
            },
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL.orEmpty().ifBlank { Build.HARDWARE.orEmpty() }
            } else {
                Build.HARDWARE.orEmpty()
            },
            deviceModel = Build.MODEL.orEmpty(),
            androidSdk = Build.VERSION.SDK_INT,
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            totalRamBytes = memory.totalMem.coerceAtLeast(0L),
            availableRamBytes = memory.availMem.coerceAtLeast(0L),
            freeStorageBytes = storage?.availableBytes ?: 0L,
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                power.currentThermalStatus
            } else {
                PowerManager.THERMAL_STATUS_NONE
            },
            batteryPercent = battery
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .takeIf { it in 0..100 },
            powerSaveMode = power.isPowerSaveMode,
            charging = battery.isCharging,
            batteryTemperatureC = batteryTemperatureC,
            screenInteractive = power.isInteractive,
            deviceIdle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) power.isDeviceIdleMode else false,
        )
    }

    fun recommend(
        snapshot: LocalDevicePerformanceSnapshot,
        record: LocalModelRecord,
    ): LocalModelPerformanceProfile {
        val ramContext = when {
            snapshot.totalRamBytes >= 12L * GIB -> 8_192
            snapshot.totalRamBytes >= 8L * GIB -> 4_096
            snapshot.totalRamBytes >= 6L * GIB -> 4_096
            else -> 2_048
        }
        val modelContext = when {
            // Display names commonly use "Gemma 4" rather than the artifact name "gemma-4".
            record.displayName.contains("gemma", ignoreCase = true) -> 8_192
            record.displayName.contains("qwen", ignoreCase = true) && record.sizeBytes < GIB -> 8_192
            record.sizeBytes >= 4L * GIB -> 4_096
            else -> 4_096
        }
        var contextWindow = minOf(ramContext, modelContext)
        if (snapshot.thermalStatus >= 3) contextWindow = minOf(contextWindow, 2_048)
        else if (snapshot.thermalStatus >= 2) contextWindow = minOf(contextWindow, 4_096)
        if (snapshot.powerSaveMode) contextWindow = minOf(contextWindow, 4_096)

        val threads = if (snapshot.isFindX7Ultra) {
            4
        } else {
            (snapshot.cpuCores - 1).coerceIn(2, 4)
        }
        val gpuLayers = when (record.format) {
            // CPU is the portable baseline. GPU offload is an opt-in benchmark comparison.
            LocalModelFormat.GGUF -> 0
            else -> 0
        }
        val memoryNeed = maxOf(record.sizeBytes * 5 / 2, 2L * GIB)
        val memoryBudget = minOf(snapshot.totalRamBytes / 3, memoryNeed).coerceAtLeast(0L)
        val warnings = buildList {
            if (snapshot.availableRamBytes in 1 until memoryNeed) add(LocalPerformanceWarning.LOW_AVAILABLE_MEMORY)
            if (snapshot.freeStorageBytes in 1 until record.sizeBytes * 3 / 2) {
                add(LocalPerformanceWarning.LOW_STORAGE)
            }
            if (snapshot.thermalStatus >= 2) add(LocalPerformanceWarning.THERMAL_PRESSURE)
            if (snapshot.powerSaveMode) add(LocalPerformanceWarning.POWER_SAVING)
        }
        val state = when {
            snapshot.thermalStatus >= 3 ||
                LocalPerformanceWarning.LOW_AVAILABLE_MEMORY in warnings -> LocalPerformanceState.THROTTLED
            warnings.isNotEmpty() -> LocalPerformanceState.WATCH
            else -> LocalPerformanceState.OPTIMAL
        }
        return LocalModelPerformanceProfile(
            contextWindow = contextWindow,
            threads = threads,
            gpuLayers = gpuLayers,
            memoryBudgetBytes = memoryBudget,
            state = state,
            warnings = warnings,
        )
    }

    /**
     * Reads the standard OpenAI `GET /v1/models` response before saving a provider. Probing is
     * intentionally separate from the inference benchmark so an unavailable metadata endpoint
     * does not block users whose runtime supports chat completions only.
     */
    suspend fun discoverModels(endpoint: String): LocalModelServiceDiscovery = withContext(Dispatchers.IO) {
        val normalized = LocalModelRepository.validateServiceEndpoint(endpoint)
        val base = normalized.removeSuffix("/chat/completions").trimEnd('/')
        val candidates = buildList {
            add("$base/models")
            if (!base.endsWith("/v1", ignoreCase = true)) add("$base/v1/models")
        }.distinct()
        var lastFailure: Throwable? = null
        for (url in candidates) {
            val started = System.nanoTime()
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                probeHttp.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    require(response.isSuccessful) {
                        "本地服务返回 HTTP ${response.code}: ${body.take(180)}"
                    }
                    val ids = parseOpenAiModelIds(body)
                    return@withContext LocalModelServiceDiscovery(
                        modelIds = ids,
                        latencyMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L),
                        sourceUrl = url,
                    )
                }
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }
        throw IllegalStateException(
            lastFailure?.message ?: "本地服务没有返回可识别的模型列表",
            lastFailure,
        )
    }

    suspend fun benchmark(record: LocalModelRecord): LocalModelPerformanceResult = withContext(Dispatchers.IO) {
        val baseUrl = LocalModelRepository.validateServiceEndpoint(record.serviceEndpoint)
        require(record.serverModelId.isNotBlank()) { "请先填写本地服务模型 ID" }
        val provider = record.boundProviderId?.let { ProviderRepository.providerById(it) }
        val requestUrl = if (baseUrl.endsWith("/chat/completions")) {
            baseUrl
        } else {
            "$baseUrl/chat/completions"
        }
        val payload = JSONObject()
            .put("model", record.serverModelId)
            .put("stream", false)
            .put("temperature", 0)
            .put("max_tokens", 16)
            .put(
                "messages",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "Reply with exactly: OK"),
                ),
            )
            .toString()
        val request = Request.Builder()
            .url(requestUrl)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .apply {
                provider?.let { configured ->
                    configured.customHeaders.forEach { custom ->
                        if (custom.name.isNotBlank()) header(custom.name, custom.value)
                    }
                    if (!configured.apiKey.isNullOrBlank()) {
                        header("Authorization", "Bearer ${configured.apiKey}")
                    }
                }
            }
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val started = System.nanoTime()
        benchmarkHttp.newCall(request).execute().use { response ->
            val body = response.body.string()
            require(response.isSuccessful) {
                "本地服务返回 HTTP ${response.code}: ${body.take(220)}"
            }
            val latencyMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw IllegalStateException("本地服务没有返回兼容的 OpenAI JSON 响应")
            }
            val completionTokens = json.optJSONObject("usage")
                ?.optInt("completion_tokens", -1)
                ?.takeIf { it > 0 }
            val tokensPerSecond = completionTokens?.let {
                (it.toDouble() / (latencyMs / 1_000.0)).roundToInt(2)
            }
            LocalModelPerformanceResult(
                latencyMs = latencyMs,
                completionTokens = completionTokens,
                tokensPerSecond = tokensPerSecond,
            )
        }
    }

    private fun Double.roundToInt(decimals: Int): Double {
        val factor = 10.0.powCompat(decimals)
        return (this * factor).roundToInt() / factor
    }

    private fun Double.powCompat(exponent: Int): Double {
        var result = 1.0
        repeat(exponent) { result *= 10.0 }
        return result
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val GIB = 1024L * 1024 * 1024
}

internal fun parseOpenAiModelIds(body: String): List<String> = runCatching {
    val root = JSONObject(body)
    val data = root.optJSONArray("data") ?: return@runCatching emptyList()
    buildList {
        for (index in 0 until data.length()) {
            val id = data.optJSONObject(index)?.optString("id").orEmpty().trim()
            if (id.isNotEmpty()) add(id)
        }
    }.distinct().take(100)
}.getOrDefault(emptyList())
