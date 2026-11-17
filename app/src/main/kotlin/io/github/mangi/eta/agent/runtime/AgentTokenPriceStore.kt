package io.github.mangi.eta.agent.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal object AgentTokenPriceStore {
    private const val TOMBSTONE_PREFERENCES = "official_token_price_tombstones"
    private val seeded = AtomicBoolean(false)

    fun prices(context: Context) = flow {
        ensureOfficialSeeds(context)
        emitAll(AgentMetricsDatabase.get(context).dao().pricesFlow())
    }.flowOn(Dispatchers.IO)

    /** Called on the execution worker before the model attempt, never by a Service constructor. */
    fun resolve(context: Context, providerId: String, modelId: String): AgentTokenPrice? =
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            ensureOfficialSeeds(context)
            val stored = AgentMetricsDatabase.get(context).dao().price(providerId, modelId)?.price
            if (stored != null && !stored.revision.startsWith("official:")) return@runBlocking stored
            if (isTombstoned(context, providerId, modelId)) return@runBlocking null
            AgentDeepSeekPriceSchedule.currentPrice(context, providerId, modelId) ?: stored
        }

    suspend fun save(context: Context, providerId: String, modelId: String, price: AgentTokenPrice) = withContext(Dispatchers.IO) {
        require(providerId.isNotBlank() && providerId.length <= 128 && modelId.isNotBlank() && modelId.length <= 128)
        tombstones(context).edit().remove(tombstoneKey(providerId, modelId)).commit()
        AgentMetricsDatabase.get(context).dao().putPrice(AgentTokenPriceEntity(providerId, modelId, price))
    }

    suspend fun remove(context: Context, providerId: String, modelId: String) = withContext(Dispatchers.IO) {
        check(tombstones(context).edit().putBoolean(tombstoneKey(providerId, modelId), true).commit()) {
            "PRICE_TOMBSTONE_UNAVAILABLE"
        }
        AgentMetricsDatabase.get(context).dao().deletePrice(providerId, modelId)
    }

    @Synchronized private fun ensureOfficialSeeds(context: Context) {
        if (!seeded.compareAndSet(false, true)) return
        try {
            val seed = {
                val dao = AgentMetricsDatabase.get(context).dao()
                val removed = tombstones(context)
                AgentOfficialPriceCatalog.entries.forEach { entry ->
                    if (removed.getBoolean(tombstoneKey(entry.providerId, entry.modelId), false)) return@forEach
                    val existing = dao.price(entry.providerId, entry.modelId)?.price
                    val official = AgentOfficialPriceCatalog.asTokenPrice(entry)
                    if (existing == null || existing.revision.startsWith("official:") && existing != official) {
                        dao.putPrice(AgentTokenPriceEntity(entry.providerId, entry.modelId, official))
                    }
                }
            }
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) { seed() }
            } else seed()
        } catch (failure: Throwable) {
            seeded.set(false)
            throw failure
        }
    }

    internal suspend fun applyOfficialPrices(context: Context, entries: List<OfficialTokenPrice>) = withContext(Dispatchers.IO) {
        val dao = AgentMetricsDatabase.get(context).dao()
        val removed = tombstones(context)
        entries.forEach { entry ->
            if (removed.getBoolean(tombstoneKey(entry.providerId, entry.modelId), false)) return@forEach
            val existing = dao.price(entry.providerId, entry.modelId)?.price
            if (existing == null || existing.revision.startsWith("official:")) {
                dao.putPrice(AgentTokenPriceEntity(
                    entry.providerId,
                    entry.modelId,
                    AgentOfficialPriceCatalog.asTokenPrice(entry),
                ))
            }
        }
    }

    internal fun resetForTests() {
        seeded.set(false)
    }

    private fun tombstones(context: Context) = context.applicationContext
        .getSharedPreferences(TOMBSTONE_PREFERENCES, Context.MODE_PRIVATE)

    private fun tombstoneKey(providerId: String, modelId: String): String =
        providerId.replace("\u0000", "_") + "\u0000" + modelId.replace("\u0000", "_")

    private fun isTombstoned(context: Context, providerId: String, modelId: String): Boolean =
        tombstones(context).getBoolean(tombstoneKey(providerId, modelId), false)
}
