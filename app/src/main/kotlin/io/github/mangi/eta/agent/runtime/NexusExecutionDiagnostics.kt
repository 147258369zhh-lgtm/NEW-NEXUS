package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.kernel.api.*
import org.json.JSONArray
import org.json.JSONObject

/** Actual stored evidence comparison, with missing sources and corrupt checkpoints kept explicit. */
internal object NexusExecutionDiagnostics {
    fun report(store: NexusExecutionStore, metrics: List<AgentRunMetricsRecord>, limit: Int = 20): JSONObject {
        val requiredRuns = minOf(limit.coerceIn(1, 200), 20)
        val byRun=metrics.filter { it.recordId == it.runId }.associateBy { NexusRunMapper.canonicalRunId(it.runId) }
        val rows=store.recent(limit).map { run -> inspect(store,run,byRun[run.request.runId]) }
        return JSONObject().put("sampledRuns",rows.size)
            .put("matchingMetricsRuns",rows.count { it.optString("metricsComparison")=="match" })
            .put("verifiedCheckpoints",rows.count { it.optString("checkpointVerification")=="valid" })
            .put("matchedUiProjections",rows.count { it.optJSONObject("uiProjection")?.optString("status")=="match" })
            .put("requiredRuns",requiredRuns)
            .put("runs",JSONArray(rows)).put("recoveryEnabled",false)
            .put("fullComparisonComplete",rows.size>=requiredRuns && rows.all { it.optBoolean("complete") })
            .put("uiProjectionComparison",if(rows.isNotEmpty() && rows.all { it.optJSONObject("uiProjection")?.optString("status")=="match" }) "complete" else "pending")
    }

    fun inspect(store: NexusExecutionStore, run: io.github.zhh_lgtm.nexus.application.api.ExecutionRunSnapshot,
        metrics: AgentRunMetricsRecord?): JSONObject {
        var after=-1L
        val tools=linkedSetOf<String>()
        val settled=linkedSetOf<String>()
        val workflowStarts = linkedSetOf<String>()
        val workflowSettled = linkedSetOf<String>()
        var workflowRevisions = 0
        var requests=0
        var last=-1L
        var gap=false
        while(true) {
            val page=store.timeline(run.request.runId,after,200)
            if(page.isEmpty()) break
            page.forEach { commit ->
                if(commit.event.sequence != last+1) gap=true
                last=commit.event.sequence
                when(commit.event.type) {
                    RunEventType.TOOL_ITEM_STARTED -> commit.event.itemId?.value?.let(tools::add)
                    RunEventType.TOOL_ITEM_COMPLETED -> if(commit.fact.success != null) commit.event.itemId?.value?.let(settled::add)
                    RunEventType.MODEL_REQUEST_STARTED -> requests++
                    RunEventType.WORKFLOW_UPDATE_STARTED -> commit.event.itemId?.value?.let(workflowStarts::add)
                    RunEventType.WORKFLOW_UPDATE_FAILED, RunEventType.GOAL_UPDATED,
                    RunEventType.PLAN_UPDATED, RunEventType.NOTEPAD_UPDATED -> {
                        commit.event.itemId?.value?.let(workflowSettled::add)
                        if (commit.fact.success == true && commit.fact.payloadRef != null) workflowRevisions++
                    }
                    else -> Unit
                }
            }
            after=page.last().event.sequence
        }
        val checkpointEvidence=ExecutionRecoveryComparison.checkpoint(store,run)
        val checkpointStatus=checkpointEvidence.status
        val omittedImages=checkpointEvidence.omittedInputImages
        val uiEvidence=ExecutionRecoveryComparison.uiProjection(store,run)
        val issues=mutableListOf<String>()
        val changed=store.find(run.request.runId)?.lastSequence!=run.lastSequence
        if(changed) issues+="RUN_CHANGED_DURING_INSPECTION"
        else if(gap || last!=run.lastSequence) issues+="EVENT_SEQUENCE_MISMATCH"
        if(checkpointStatus !in setOf("valid","missing")) issues+="CHECKPOINT_INVALID"
        if(uiEvidence.status=="mismatch") issues+="UI_PROJECTION_MISMATCH"
        else if(uiEvidence.status=="missing") issues+="UI_PROJECTION_MISSING"
        else if(uiEvidence.status=="unreadable") issues+="UI_PROJECTION_UNREADABLE"
        val unresolvedWorkflow = workflowStarts.count { it !in workflowSettled }
        if (unresolvedWorkflow > 0) issues += "UNRESOLVED_WORKFLOW_EFFECT"
        val expectedStatus = if (run.state in NexusExecutionStore.terminalStates) run.state.name.lowercase()
            else AgentMetricStatus.RUNNING
        val metricsStatus=when {
            changed -> "pending"
            metrics==null -> "missing"
            metrics.metrics.toolCalls!=tools.size || metrics.metrics.requestAttempts!=requests || metrics.status!=expectedStatus -> {
                issues+="METRICS_MISMATCH";"mismatch"
            }
            else -> "match"
        }
        val modelBudget = runCatching { store.modelBudgetTotals(run.request.runId) }
        val modelBudgetJson = modelBudget.getOrNull()?.let { totals -> JSONObject()
            .put("familyRunId", totals.familyRunId.value).put("requests", totals.requests)
            .put("openRequests", totals.openRequests).put("unknownCharges", totals.unknownCharges)
            .put("knownCostMicros", totals.knownCostMicros).put("currency", totals.currency)
            .put("costLimitMicros", totals.costLimitMicros ?: JSONObject.NULL)
            .put("reservedCostMicros", totals.reservedCostMicros) }
        if (modelBudget.isFailure) issues += "MODEL_BUDGET_UNAVAILABLE"
        val pendingEffectReviews = store.pendingEffectReviewCount(run.request.runId)
        if (pendingEffectReviews > 0) issues += "EFFECT_REVIEW_REQUIRED"
        val complete = !changed && gap.not() && metricsStatus=="match" && checkpointStatus=="valid" &&
            uiEvidence.status=="match" && issues.isEmpty()
        return JSONObject().put("runId",run.request.runId.value).put("source",run.request.source.name)
            .put("state",run.state.name).put("parentRunId",run.request.parentRunId?.value ?: JSONObject.NULL)
            .put("lastSequence",run.lastSequence).put("checkpointSequence",run.checkpointSequence ?: JSONObject.NULL)
            .put("checkpointVerification",checkpointStatus).put("unresolvedToolEffects",tools.count { it !in settled })
            .put("pendingEffectReviews", pendingEffectReviews)
            .put("unresolvedWorkflowEffects", unresolvedWorkflow).put("workflowRevisions", workflowRevisions)
            .put("inputImagesRequireReattach",omittedImages>0)
            .put("toolCalls",tools.size).put("modelRequests",requests).put("metricsComparison",metricsStatus)
            .put("issues",JSONArray(issues)).put("recoveryAuthority","legacy-checkpoint")
            .put("checkpoint",checkpointEvidence.toJson()).put("uiProjection",uiEvidence.toJson())
            .put("complete",complete)
            .put("modelBudget", modelBudgetJson ?: JSONObject.NULL)
    }
}
