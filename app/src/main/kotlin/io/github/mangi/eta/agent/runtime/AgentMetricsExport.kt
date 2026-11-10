package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal data class AgentMetricsExportResult(val exportedRuns: Int, val missingRuns: Int)

/** A bounded diagnostic document; no raw event, prompt, tool result or configuration is serialized. */
internal object AgentMetricsExport {
    internal const val MAX_RUNS = 256
    internal const val MAX_BYTES = 16L * 1024 * 1024

    suspend fun export(context: Context, destination: Uri, recordIds: List<String>): AgentMetricsExportResult =
        withContext(Dispatchers.IO) {
            withTimeout(60_000L) {
                require(recordIds.isNotEmpty() && recordIds.size <= MAX_RUNS)
                require(recordIds.all { it.isNotBlank() && it.length <= 288 })
                val ids = recordIds.distinct()
                AgentRunMetricsStore.flush()
                val db = AgentMetricsDatabase.get(context)
                AgentRunMetricsStore.migrateLegacy(context, db)
                var details = emptyList<AgentMetricDetail>()
                db.runInTransaction { details = ids.mapNotNull(db.dao()::detail) }
                ensureActive()
                if (details.isEmpty()) throw IOException("METRICS_RECORDS_MISSING")
                val result = AgentMetricsExportResult(details.size, ids.size - details.size)
                val temporary = File.createTempFile("eta-metrics-export-", ".json", context.cacheDir)
                try {
                    temporary.outputStream().buffered().use { writeDocument(it, details, result.missingRuns) }
                    if (temporary.length() > MAX_BYTES) throw IOException("METRICS_EXPORT_TOO_LARGE")
                    ensureActive()
                    val caller = currentCoroutineContext()
                    runInterruptible {
                        val output = context.contentResolver.openOutputStream(destination, "wt")
                            ?: throw IOException("METRICS_DOCUMENT_UNAVAILABLE")
                        output.buffered(64 * 1024).use { target ->
                            temporary.inputStream().use { source ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    caller.ensureActive()
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    target.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    ensureActive()
                    result
                } finally { temporary.delete() }
            }
        }

    internal suspend fun writeDocument(output: OutputStream, details: List<AgentMetricDetail>, missingRuns: Int = 0) {
        require(details.size <= MAX_RUNS)
        val header = JSONObject().put("type", "eta_run_diagnostics").put("version", 3)
            .put("exported_at", System.currentTimeMillis()).put("run_count", details.size).put("missing_runs", missingRuns)
            .put("usage_policy", "reported_values_only_null_is_unknown")
            .put("privacy", "structured_metadata_only_no_conversation_credentials_arguments_or_results")
            .put("cost_policy", "manual_rates_frozen_at_model_attempt_root_and_registered_children_separate_currencies_token_estimates_only")
            .put("request_policy", "one_row_per_actual_provider_http_attempt_null_usage_is_unknown")
        // One run at a time bounds the JSON allocation. The caller owns and closes its output.
        val writer = output.writer(Charsets.UTF_8)
        var writtenBytes = 0L
        fun write(value: String) {
            writtenBytes += value.toByteArray(Charsets.UTF_8).size
            if (writtenBytes > MAX_BYTES) throw IOException("METRICS_EXPORT_TOO_LARGE")
            writer.write(value)
        }
        write(header.toString().dropLast(1) + ",\"runs\":[")
        details.forEachIndexed { index, detail ->
            currentCoroutineContext().ensureActive()
            if (index > 0) write(",")
            write(runJson(detail).toString())
        }
        write("]}")
        writer.flush()
    }

    private fun runJson(detail: AgentMetricDetail): JSONObject {
        val run = detail.run
        val billable = detail.models + detail.children.map { it.asModel() }
        val costs = AgentTokenCostEstimator.summarize(billable, maxOf(1, billable.size))
        return JSONObject().put("record_id", run.recordId).put("run_id", run.runId)
            .put("source", run.source).put("operation", run.operation).put("conversation_id", run.conversationId)
            .put("room_id", run.roomId).put("task_id", run.taskId).put("project_id", run.projectId)
            .put("started_at", run.startedAt).put("updated_at", run.updatedAt)
            .put("completed_at", run.completedAt ?: JSONObject.NULL).put("duration_ms", run.durationMs)
            .put("status", run.status).put("revision", run.revision).put("usage", usageJson(run.metrics))
            .put("omitted_trace_steps", run.omittedTraceSteps)
            .put("token_cost", JSONObject().put("complete_model_attempts", costs.completeModels).put("model_attempts", costs.totalModels)
                .put("currencies", JSONArray(costs.currencies.map { JSONObject().put("currency", it.currency)
                    .put("known_amount", it.amount.stripTrailingZeros().toPlainString()) })))
            .put("models", JSONArray(detail.models.sortedBy { it.index }.take(8).map { model ->
                JSONObject().put("attempt", model.index).put("provider_id", model.providerId)
                    .put("model_id", model.modelId).put("name", model.name).put("usage", usageJson(model.metrics))
                    .put("price_captured", model.priceCaptured).put("price", model.price?.let(::priceJson) ?: JSONObject.NULL)
                    .put("token_cost", costJson(AgentTokenCostEstimator.estimate(model.metrics, model.price)))
            }))
            .put("children", JSONArray(detail.children.sortedBy { it.index }.map { child ->
                JSONObject().put("scope_id", child.scopeId).put("parent_scope_id", child.parentScopeId)
                    .put("kind", child.kind).put("index", child.index).put("parent_round", child.parentRound ?: JSONObject.NULL)
                    .put("tool_call_id", child.toolCallId).put("started_at", child.startedAt)
                    .put("completed_at", child.completedAt ?: JSONObject.NULL).put("duration_ms", child.durationMs)
                    .put("status", child.status).put("revision", child.revision).put("provider_id", child.providerId)
                    .put("model_id", child.modelId).put("name", child.name).put("usage", usageJson(child.metrics))
                    .put("price_captured", child.priceCaptured).put("price", child.price?.let(::priceJson) ?: JSONObject.NULL)
                    .put("token_cost", costJson(AgentTokenCostEstimator.estimate(child.metrics, child.price)))
            }))
            .put("requests", JSONArray(detail.requests.sortedBy { it.startedElapsedMs }.map { request ->
                JSONObject().put("request_id", request.requestId).put("scope_id", request.scopeId)
                    .put("provider_id", request.providerId).put("model_id", request.modelId).put("name", request.name)
                    .put("round", request.round ?: JSONObject.NULL).put("attempt", request.attempt)
                    .put("started_at", request.startedAt).put("started_elapsed_ms", request.startedElapsedMs)
                    .put("completed_at", request.completedAt ?: JSONObject.NULL)
                    .put("completed_elapsed_ms", request.completedElapsedMs ?: JSONObject.NULL)
                    .put("status", request.status).put("http_code", request.httpCode ?: JSONObject.NULL)
                    .put("failure_code", request.failureCode).put("response_ms", request.responseMs ?: JSONObject.NULL)
                    .put("first_text_ms", request.firstTextMs ?: JSONObject.NULL).put("total_ms", request.totalMs ?: JSONObject.NULL)
                    .put("usage", requestUsageJson(request.usage)).put("price_captured", request.priceCaptured)
                    .put("price", request.price?.let(::priceJson) ?: JSONObject.NULL)
                    .put("token_cost", costJson(AgentTokenCostEstimator.estimateRequest(request.usage, request.price)))
            }))
            .put("trace", JSONArray(detail.trace.sortedBy { it.sequence }.takeLast(AgentRunTelemetry.MAX_TRACE_STEPS).map { step ->
                JSONObject().put("sequence", step.sequence).put("elapsed_ms", step.elapsedMs).put("kind", step.kind)
                    .put("round", step.round ?: JSONObject.NULL).put("label", step.label)
                    .put("success", step.success ?: JSONObject.NULL)
            }))
    }

    private fun requestUsageJson(usage: AgentRequestUsage) = JSONObject()
        .put("input_tokens", usage.inputTokens ?: JSONObject.NULL)
        .put("output_tokens", usage.outputTokens ?: JSONObject.NULL)
        .put("reasoning_tokens", usage.reasoningTokens ?: JSONObject.NULL)
        .put("cached_tokens", usage.cachedTokens ?: JSONObject.NULL)
        .put("context_tokens", usage.contextTokens ?: JSONObject.NULL)
        .put("uncached_input_tokens", usage.uncachedInputTokens ?: JSONObject.NULL)
        .put("cache_read_tokens", usage.cacheReadTokens ?: JSONObject.NULL)
        .put("cache_write_tokens", usage.cacheWriteTokens ?: JSONObject.NULL)
        .put("billable_output_tokens", usage.billableOutputTokens ?: JSONObject.NULL)

    private fun usageJson(usage: AgentRunMetrics) = JSONObject().put("rounds", usage.rounds)
        .put("tool_calls", usage.toolCalls).put("succeeded_tools", usage.succeededTools)
        .put("failed_tools", usage.failedTools).put("unresolved_tools", usage.unresolvedTools)
        .put("input_tokens", usage.inputTokens ?: JSONObject.NULL).put("output_tokens", usage.outputTokens ?: JSONObject.NULL)
        .put("reasoning_tokens", usage.reasoningTokens ?: JSONObject.NULL).put("cached_tokens", usage.cachedTokens ?: JSONObject.NULL)
        .put("context_tokens", usage.contextTokens ?: JSONObject.NULL).put("input_reported_rounds", usage.inputReportedRounds)
        .put("output_reported_rounds", usage.outputReportedRounds).put("usage_complete", usage.usageComplete)
        .put("image_bytes", usage.imageBytes).put("requests", usage.requestAttempts)
        .put("retries", usage.retries).put("completed_compactions", usage.completedCompactions)
        .put("uncached_input_tokens", usage.uncachedInputTokens ?: JSONObject.NULL)
        .put("cache_read_tokens", usage.cacheReadTokens ?: JSONObject.NULL)
        .put("cache_write_tokens", usage.cacheWriteTokens ?: JSONObject.NULL)
        .put("billable_output_tokens", usage.billableOutputTokens ?: JSONObject.NULL)
        .put("billing_reported_rounds", usage.billingReportedRounds)

    private fun priceJson(price: AgentTokenPrice): JSONObject = JSONObject().put("revision", price.revision)
        .put("currency", price.currency).put("rate_unit", "per_million_tokens")
        .put("input", price.input).put("output", price.output)
        .put("cache_read", price.cacheRead ?: JSONObject.NULL).put("cache_write", price.cacheWrite ?: JSONObject.NULL)
        .put("captured_at", price.capturedAt)

    private fun costJson(cost: AgentTokenCost) = JSONObject().put("currency", cost.currency ?: JSONObject.NULL)
        .put("known_amount", cost.amount?.stripTrailingZeros()?.toPlainString() ?: JSONObject.NULL).put("complete", cost.complete)
}
