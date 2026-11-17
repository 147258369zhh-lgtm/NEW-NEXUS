package io.github.zhh_lgtm.nexus.application.api

import io.github.zhh_lgtm.nexus.kernel.api.RunId

/** No credentials, prompts or response text enter the durable request budget. */
data class ModelBudgetRequest(
    val requestId: String,
    val runId: RunId,
    val providerId: String,
    val modelId: String,
    val purpose: String,
    val currency: String? = null,
    val priceRevision: String? = null,
    val inputRate: String? = null,
    val outputRate: String? = null,
    val cacheReadRate: String? = null,
    val cacheWriteRate: String? = null,
    val reservedCostMicros: Long? = null,
) {
    init {
        require(requestId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,189}")))
        require(providerId.length <= 256 && modelId.length <= 256)
        require(purpose.matches(Regex("[A-Z_]{1,64}")))
        require(currency == null || currency.matches(Regex("[A-Z]{3}")))
        require(priceRevision == null || priceRevision.length <= 256)
        require(reservedCostMicros == null || reservedCostMicros >= 0)
        require(reservedCostMicros == null || currency != null && priceRevision != null)
        listOf(inputRate, outputRate, cacheReadRate, cacheWriteRate).forEach {
            require(it == null || it.matches(Regex("[0-9]{1,7}(?:\\.[0-9]{1,8})?")))
        }
    }
}

data class ModelBudgetSettlement(
    val requestId: String,
    val costMicros: Long?,
    val usageComplete: Boolean,
    val successful: Boolean,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
) {
    init {
        require(costMicros == null || costMicros >= 0)
        require(!usageComplete || costMicros != null)
        listOf(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens).forEach { require(it == null || it >= 0) }
    }
}

data class ModelBudgetRecord(val request: ModelBudgetRequest, val familyRunId: RunId,
    val reservedSequence: Long, val settledSequence: Long?, val settlement: ModelBudgetSettlement?)

data class ModelBudgetTotals(val familyRunId: RunId, val requests: Long, val openRequests: Long,
    val unknownCharges: Long, val knownCostMicros: Long, val currency: String, val costLimitMicros: Long? = null,
    val reservedCostMicros: Long = 0)

class ModelBudgetDenied(val code: String) : IllegalStateException(code)

interface ModelBudgetStore {
    fun reserveModelRequest(request: ModelBudgetRequest, now: Long): ModelBudgetRecord
    fun settleModelRequest(runId: RunId, settlement: ModelBudgetSettlement, now: Long): ModelBudgetRecord
    fun modelBudgetTotals(runId: RunId): ModelBudgetTotals
    fun modelBudgetRequests(runId: RunId): List<ModelBudgetRecord>
}
