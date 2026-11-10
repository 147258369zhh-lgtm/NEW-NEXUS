package io.github.mangi.eta.agent.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal object AgentTokenPriceStore {
    fun prices(context: Context) = flow {
        emitAll(AgentMetricsDatabase.get(context).dao().pricesFlow())
    }.flowOn(Dispatchers.IO)

    /** Called on the execution worker before the model attempt, never by a Service constructor. */
    fun resolve(context: Context, providerId: String, modelId: String): AgentTokenPrice? =
        AgentMetricsDatabase.get(context).dao().price(providerId, modelId)?.price

    suspend fun save(context: Context, providerId: String, modelId: String, price: AgentTokenPrice) = withContext(Dispatchers.IO) {
        require(providerId.isNotBlank() && providerId.length <= 128 && modelId.isNotBlank() && modelId.length <= 128)
        AgentMetricsDatabase.get(context).dao().putPrice(AgentTokenPriceEntity(providerId, modelId, price))
    }

    suspend fun remove(context: Context, providerId: String, modelId: String) = withContext(Dispatchers.IO) {
        AgentMetricsDatabase.get(context).dao().deletePrice(providerId, modelId)
    }
}
