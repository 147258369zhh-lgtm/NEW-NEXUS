package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.data.provider.BuiltinProviders
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.json.JSONObject

internal data class DeepSeekRate(val cacheHit: String, val cacheMiss: String, val output: String)

internal data class DeepSeekModelPrice(
    val modelId: String,
    val offPeak: DeepSeekRate,
    val peak: DeepSeekRate,
)

internal data class DeepSeekPriceSchedule(
    val verifiedOn: LocalDate,
    val models: Map<String, DeepSeekModelPrice>,
    val sourceUrl: String,
) {
    fun isPeak(now: Long = System.currentTimeMillis()): Boolean {
        val utc = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC)
        return utc.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) &&
            (utc.hour in 1..3 || utc.hour in 6..9)
    }

    fun rate(modelId: String, now: Long = System.currentTimeMillis()): DeepSeekRate? {
        val model = models[modelId] ?: return null
        return if (isPeak(now)) model.peak else model.offPeak
    }
}

internal object AgentDeepSeekPriceSchedule {
    private const val PREFERENCES = "deepseek_official_price_schedule"
    private const val KEY = "snapshot"
    private const val SOURCE = "https://api-docs.deepseek.com/quick_start/pricing/"
    val bundled = DeepSeekPriceSchedule(
        verifiedOn = LocalDate.of(2026, 11, 12),
        sourceUrl = SOURCE,
        models = mapOf(
            "deepseek-flash" to DeepSeekModelPrice(
                modelId = "deepseek-flash",
                offPeak = DeepSeekRate("0.003", "0.15", "0.6"),
                peak = DeepSeekRate("0.006", "0.3", "1.2"),
            ),
            "deepseek-v4-pro" to DeepSeekModelPrice(
                modelId = "deepseek-v4-pro",
                offPeak = DeepSeekRate("0.022", "0.66", "1.98"),
                peak = DeepSeekRate("0.044", "1.32", "3.96"),
            ),
        ),
    )

    fun read(context: Context): DeepSeekPriceSchedule = runCatching {
        val raw = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return@runCatching bundled
        decode(JSONObject(raw))
    }.getOrDefault(bundled)

    fun save(context: Context, schedule: DeepSeekPriceSchedule) {
        check(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(KEY, encode(schedule).toString()).commit()) {
            "DEEPSEEK_PRICE_SCHEDULE_UNAVAILABLE"
        }
    }

    fun currentPrice(context: Context, providerId: String, modelId: String, now: Long = System.currentTimeMillis()): AgentTokenPrice? {
        if (providerId != BuiltinProviders.DEEPSEEK_ID) return null
        val schedule = read(context)
        val rate = schedule.rate(modelId, now) ?: return null
        val period = if (schedule.isPeak(now)) "peak" else "offpeak"
        return AgentTokenPrice.create(
            currency = "USD",
            input = rate.cacheMiss,
            output = rate.output,
            cacheRead = rate.cacheHit,
            now = now,
        ).copy(revision = "official:deepseek:" + period + ":" + schedule.verifiedOn + ":v1")
    }

    fun decode(raw: JSONObject): DeepSeekPriceSchedule {
        val models = raw.getJSONObject("models")
        return DeepSeekPriceSchedule(
            verifiedOn = LocalDate.parse(raw.getString("verifiedOn")),
            sourceUrl = raw.getString("sourceUrl"),
            models = bundled.models.keys.associateWith { modelId ->
                val model = models.getJSONObject(modelId)
                DeepSeekModelPrice(
                    modelId = modelId,
                    offPeak = decodeRate(model.getJSONObject("offPeak")),
                    peak = decodeRate(model.getJSONObject("peak")),
                )
            },
        )
    }

    private fun encode(schedule: DeepSeekPriceSchedule): JSONObject = JSONObject()
        .put("verifiedOn", schedule.verifiedOn.toString())
        .put("sourceUrl", schedule.sourceUrl)
        .put("models", JSONObject().apply {
            schedule.models.forEach { (id, model) ->
                put(id, JSONObject()
                    .put("offPeak", encodeRate(model.offPeak))
                    .put("peak", encodeRate(model.peak)))
            }
        })

    private fun encodeRate(rate: DeepSeekRate) = JSONObject()
        .put("cacheHit", rate.cacheHit)
        .put("cacheMiss", rate.cacheMiss)
        .put("output", rate.output)

    private fun decodeRate(value: JSONObject) = DeepSeekRate(
        cacheHit = value.getString("cacheHit"),
        cacheMiss = value.getString("cacheMiss"),
        output = value.getString("output"),
    )
}
