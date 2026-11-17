package io.github.mangi.eta.agent.runtime

import android.content.Context
import java.math.BigDecimal
import java.math.MathContext
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class AgentCnyRate(val currency: String, val multiplier: BigDecimal, val date: String, val source: String)
internal data class AgentCnyEstimate(val amount: BigDecimal?, val complete: Boolean, val converted: Int, val total: Int,
    val rates: List<AgentCnyRate>)

/** FX never changes execution budgets or the provider's native-currency pricing. */
internal object AgentCnyExchangeStore {
    private const val PREFS = "nexa_metric_cny_exchange_v1"
    private const val URL = "https://api.frankfurter.dev/v1/latest?base=CNY"
    private val lock = Any()
    val revision = MutableStateFlow(0L)
    private var refreshing = false
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // Verified reference-rate response on 2026-11-15; explicitly dated offline fallback.
    // Rates are foreign currency per CNY, so conversion to CNY takes their reciprocal.
    private val bundled = JSONObject("""{"date":"2026-11-13","rates":{"USD":0.14988,"EUR":0.13562,"GBP":0.11332,"HKD":1.176,"JPY":23.981,"AUD":0.22089,"CAD":0.2137,"SGD":0.19306}}""")

    fun automatic(context: Context): Boolean = prefs(context).getBoolean("automatic", true)
    fun current(context: Context, currency: String): AgentCnyRate? = synchronized(lock) {
        if (currency == "CNY") return@synchronized AgentCnyRate(currency, BigDecimal.ONE, "", "人民币")
        val p = prefs(context)
        val manual = p.getString("manual.$currency", null)
        if (manual != null) return@synchronized runCatching {
            AgentCnyRate(currency, checked(manual), p.getString("manualDate.$currency", "").orEmpty(), "手动")
        }.getOrNull()
        val cached = runCatching { JSONObject(p.getString("reference", null).orEmpty()) }.getOrNull()
        val json = cached ?: bundled
        val quote = json.optJSONObject("rates")?.optString(currency)?.takeIf(String::isNotBlank) ?: return@synchronized null
        runCatching { AgentCnyRate(currency, BigDecimal.ONE.divide(checked(quote), MathContext.DECIMAL128),
            json.getString("date"), if (cached == null) "随包参考" else "联网参考") }.getOrNull()
    }

    suspend fun setAutomatic(context: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        synchronized(lock) { check(prefs(context).edit().putBoolean("automatic", enabled).commit()); revision.value++ }
    }
    suspend fun setManual(context: Context, currency: String, multiplier: String?) = withContext(Dispatchers.IO) {
        require(currency.matches(Regex("[A-Z]{3}")) && currency != "CNY")
        java.util.Currency.getInstance(currency)
        synchronized(lock) {
            val edit = prefs(context).edit()
            if (multiplier == null) edit.remove("manual.$currency").remove("manualDate.$currency")
            else { edit.putString("manual.$currency", checked(multiplier).toPlainString())
                .putString("manualDate.$currency", LocalDate.now().toString()) }
            check(edit.commit()); revision.value++
        }
    }
    private fun checked(value: String, maxLength: Int = 32): BigDecimal {
        require(value.length <= maxLength && value.matches(Regex("[0-9]+(?:\\.[0-9]+)?")))
        return BigDecimal(value).also { require(it > BigDecimal.ZERO && it <= BigDecimal("1000000")) }
    }

    suspend fun refresh(context: Context, force: Boolean = false): String = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (refreshing) return@withContext "汇率正在更新"
            if (!force && (!automatic(context) || prefs(context).getString("checkedDay", null) == LocalDate.now().toString()))
                return@withContext "使用已缓存汇率"
            refreshing = true
        }
        try {
            val connection = URI(URL).toURL().openConnection() as HttpURLConnection
            val json = try {
                connection.connectTimeout = 5000; connection.readTimeout = 5000; connection.instanceFollowRedirects = false
                check(connection.responseCode == 200) { "汇率服务暂不可用" }
                val bytes = connection.inputStream.use { stream ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) { val n = stream.read(buffer); if (n < 0) break
                        check(out.size() + n <= 65536) { "汇率响应过大" }; out.write(buffer, 0, n) }; out.toByteArray()
                }
                JSONObject(bytes.toString(Charsets.UTF_8))
            } finally { connection.disconnect() }
            check(json.getString("base") == "CNY" && BigDecimal(json.get("amount").toString()).compareTo(BigDecimal.ONE) == 0)
            val date = LocalDate.parse(json.getString("date")); check(!date.isAfter(LocalDate.now().plusDays(1)))
            val rates = json.getJSONObject("rates"); rates.keys().forEach { code ->
                check(code.matches(Regex("[A-Z]{3}"))); checked(rates.get(code).toString())
            }; check(rates.has("USD"))
            synchronized(lock) {
                val cached = runCatching { JSONObject(prefs(context).getString("reference", null).orEmpty()) }.getOrNull()
                if (cached == null || !LocalDate.parse(cached.getString("date")).isAfter(date)) {
                    check(prefs(context).edit().putString("reference", json.toString())
                        .putString("checkedDay", LocalDate.now().toString()).commit()); revision.value++
                }
            }
            "参考汇率已更新 · $date"
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { "更新失败，保留已有参考汇率" }
        finally { synchronized(lock) { refreshing = false } }
    }

    /** Freeze each run/currency once. Existing records capture their first conversion date. */
    fun capture(context: Context, recordId: String, currencies: Collection<String>) = synchronized(lock) {
        val p = prefs(context); val edit = p.edit(); var changed = false
        val hash = digest(recordId)
        currencies.distinct().filter { it != "CNY" }.forEach { currency ->
            val key = "quote.$hash.$currency"
            if (!p.contains(key)) current(context, currency)?.let { rate ->
                edit.putString(key, JSONObject().put("multiplier", rate.multiplier.toPlainString())
                    .put("date", rate.date).put("source", rate.source).toString()); changed = true
            }
        }
        if (changed) { check(edit.commit()); revision.value++ }
    }
    private fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun frozen(context: Context, recordId: String, currency: String): AgentCnyRate? {
        if (currency == "CNY") return current(context, currency)
        val json = prefs(context).getString("quote.${digest(recordId)}.$currency", null) ?: return null
        return runCatching { JSONObject(json).let { AgentCnyRate(currency, checked(it.getString("multiplier"), maxLength = 64),
            it.getString("date"), it.getString("source")) } }.getOrNull()
    }
    fun retain(context: Context, recordIds: Collection<String>) = synchronized(lock) {
        val keys = recordIds.map(::digest).toSet(); val p = prefs(context); val edit = p.edit(); var changed = false
        p.all.keys.filter { it.startsWith("quote.") && it.split('.').getOrNull(1) !in keys }.forEach {
            edit.remove(it); changed = true
        }; if (changed) { check(edit.commit()); revision.value++ }
    }
    fun estimate(context: Context, models: List<AgentMetricModelEntity>): AgentCnyEstimate = synchronized(lock) {
        var amount: BigDecimal? = null; var complete = models.isNotEmpty(); var converted = 0
        val used = mutableListOf<AgentCnyRate>()
        models.forEach { model ->
            val cost = AgentTokenCostEstimator.estimate(model.metrics, model.price)
            val value = cost.amount
            val rate = cost.currency?.let { frozen(context, model.recordId, it) }
            if (value != null && (value.signum() == 0 || rate != null)) {
                amount = (amount ?: BigDecimal.ZERO) + value.multiply(rate?.multiplier ?: BigDecimal.ONE)
                converted++; if (rate != null && rate.currency != "CNY") used += rate
                if (!cost.complete) complete = false
            } else complete = false
        }
        AgentCnyEstimate(amount, complete, converted, models.size, used.distinct())
    }
    fun convert(context: Context, cost: AgentTokenCost, recordId: String? = null): AgentTokenCost {
        val currency = cost.currency ?: return AgentTokenCost("CNY", null, false)
        val value = cost.amount ?: return AgentTokenCost("CNY", null, false)
        if (value.signum() == 0) return AgentTokenCost("CNY", value, cost.complete)
        val rate = if (recordId == null) current(context, currency) else frozen(context, recordId, currency)
        return AgentTokenCost("CNY", rate?.let { value.multiply(it.multiplier) }, cost.complete && rate != null)
    }
}
