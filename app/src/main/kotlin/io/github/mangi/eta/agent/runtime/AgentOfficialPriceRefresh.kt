package io.github.mangi.eta.agent.runtime

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object AgentOfficialPriceRefresh {
    private const val WORK_NAME = "nexus-official-price-refresh"
    private const val PREFERENCES = "official_price_refresh"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STATUS = "status"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val MAX_PAGE_BYTES = 2 * 1024 * 1024
    private const val OPENAI_PRICING_URL = "https://developers.openai.com/api/docs/pricing"
    private const val DEEPSEEK_PRICING_URL = "https://api-docs.deepseek.com/quick_start/pricing/"

    fun enabled(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        check(preferences(context).edit().putBoolean(KEY_ENABLED, enabled).commit()) {
            "PRICE_REFRESH_SETTING_UNAVAILABLE"
        }
        if (enabled) schedule(context) else WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(WORK_NAME)
    }

    fun status(context: Context): String? = preferences(context).getString(KEY_STATUS, null)

    fun checkedAt(context: Context): Long = preferences(context).getLong(KEY_CHECKED_AT, 0)

    fun schedule(context: Context) {
        if (!enabled(context)) return
        val request = PeriodicWorkRequestBuilder<AgentOfficialPriceRefreshWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun parseOpenAiPage(html: String, checkedOn: LocalDate = LocalDate.now(ZoneOffset.UTC)): List<OfficialTokenPrice> {
        require(html.length in 1..MAX_PAGE_BYTES)
        val standard = html.indexOf("&quot;tier&quot;:[0,&quot;standard&quot;]")
            .takeIf { it >= 0 } ?: html.indexOf("\"tier\":[0,\"standard\"]")
        require(standard >= 0) { "PRICE_PAGE_LAYOUT_CHANGED" }
        return AgentOfficialPriceCatalog.entries.mapNotNull { bundled ->
            val encodedMarker = "[0,&quot;" + bundled.modelId + "&quot;]"
            val plainMarker = "[0,\"" + bundled.modelId + "\"]"
            val start = html.indexOf(encodedMarker, standard).takeIf { it >= 0 }
                ?: html.indexOf(plainMarker, standard)
            if (start < 0) return@mapNotNull null
            val nextRow = Regex("\\],\\s*\\[1,\\[\\[0,").find(html, start + 1)?.range?.first
            val window = html.substring(start, minOf(html.length, start + 800, nextRow ?: Int.MAX_VALUE))
            val values = Regex("\\[0,([0-9]+(?:\\.[0-9]+)?)\\]")
                .findAll(window).map { it.groupValues[1] }.toList()
            if (values.size < 3) return@mapNotNull null
            bundled.copy(
                input = values.first(),
                output = values.last(),
                cacheRead = values.getOrNull(1),
                verifiedOn = checkedOn,
            )
        }.also {
            require(it.size == AgentOfficialPriceCatalog.entries.size) { "PRICE_PAGE_MODEL_ROWS_CHANGED" }
        }
    }

    fun parseDeepSeekPage(html: String, checkedOn: LocalDate = LocalDate.now(ZoneOffset.UTC)): DeepSeekPriceSchedule {
        require(html.length in 1..MAX_PAGE_BYTES)
        val modelHeader = html.indexOf("deepseek-flash")
        require(modelHeader >= 0) { "DEEPSEEK_PRICE_MODEL_ROW_MISSING" }
        val pricing = html.indexOf("PRICING", modelHeader)
        require(pricing > modelHeader) { "DEEPSEEK_PRICE_TABLE_MISSING" }
        val end = html.indexOf("Concurrency", pricing).takeIf { it > pricing } ?: minOf(html.length, pricing + 20_000)
        val values = Regex("\\$([0-9]+(?:\\.[0-9]+)?)")
            .findAll(html.substring(pricing, end)).map { it.groupValues[1] }.take(12).toList()
        require(values.size == 12) { "DEEPSEEK_PRICE_ROWS_CHANGED" }
        val models = mapOf(
            "deepseek-flash" to DeepSeekModelPrice(
                modelId = "deepseek-flash",
                offPeak = DeepSeekRate(values[0], values[4], values[8]),
                peak = DeepSeekRate(values[2], values[6], values[10]),
            ),
            "deepseek-v4-pro" to DeepSeekModelPrice(
                modelId = "deepseek-v4-pro",
                offPeak = DeepSeekRate(values[1], values[5], values[9]),
                peak = DeepSeekRate(values[3], values[7], values[11]),
            ),
        )
        return DeepSeekPriceSchedule(
            verifiedOn = checkedOn,
            models = models,
            sourceUrl = DEEPSEEK_PRICING_URL,
        )
    }

    internal fun fetchOpenAiPage(): String {
        return fetchPage(OPENAI_PRICING_URL, "developers.openai.com")
    }

    internal fun fetchDeepSeekPage(): String = fetchPage(DEEPSEEK_PRICING_URL, "api-docs.deepseek.com")

    private fun fetchPage(url: String, expectedHost: String): String {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == expectedHost) { "PRICE_SOURCE_INVALID" }
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("Accept", "text/html")
            setRequestProperty("User-Agent", "Nexus-Price-Catalog/1")
        }
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "PRICE_SOURCE_HTTP_" + connection.responseCode
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= MAX_PAGE_BYTES) { "PRICE_SOURCE_TOO_LARGE" }
                    output.write(buffer, 0, read)
                }
                return output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun recordStatus(context: Context, message: String, checkedAt: Long = System.currentTimeMillis()) {
        check(preferences(context).edit().putString(KEY_STATUS, message.take(300))
            .putLong(KEY_CHECKED_AT, checkedAt).commit()) { "PRICE_REFRESH_STATUS_UNAVAILABLE" }
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

internal class AgentOfficialPriceRefreshWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!AgentOfficialPriceRefresh.enabled(applicationContext)) return@withContext Result.success()
        AgentOfficialPriceRefresh.recordStatus(applicationContext, "正在检查官方价格")
        val openAi = runCatching {
            val entries = AgentOfficialPriceRefresh.parseOpenAiPage(AgentOfficialPriceRefresh.fetchOpenAiPage())
            AgentTokenPriceStore.applyOfficialPrices(applicationContext, entries)
            entries.first().verifiedOn
        }
        val deepSeek = runCatching {
            val schedule = AgentOfficialPriceRefresh.parseDeepSeekPage(AgentOfficialPriceRefresh.fetchDeepSeekPage())
            AgentDeepSeekPriceSchedule.save(applicationContext, schedule)
            schedule.verifiedOn
        }
        if (openAi.isSuccess || deepSeek.isSuccess) {
            val checked = listOfNotNull(openAi.getOrNull(), deepSeek.getOrNull()).maxOrNull()
            AgentOfficialPriceRefresh.recordStatus(applicationContext, "官方价格已检查 " + checked)
            Result.success()
        } else {
            val message = listOfNotNull(openAi.exceptionOrNull(), deepSeek.exceptionOrNull())
                .firstOrNull()?.message?.take(100) ?: "网络或页面结构异常"
            AgentOfficialPriceRefresh.recordStatus(
                applicationContext,
                "官方价格检查失败，已保留现有价格：" + message,
            )
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}
