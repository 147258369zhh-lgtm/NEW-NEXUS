package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.capability.NexusToolEffectClassifier
import io.github.mangi.eta.agent.workflow.WorkflowRevisionStore
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI

internal data class NexusExecutionStep(val title: String, val description: String, val verification: String)
internal data class NexusExecutionDetail(
    val runId: String,
    val itemId: String,
    val kind: ItemKind,
    val state: RunState?,
    val startedAt: Long,
    val completedAt: Long?,
    val toolName: String?,
    val errorCode: String?,
    val revisionKind: String? = null,
    val revision: Long? = null,
    val planId: String? = null,
    val steps: List<NexusExecutionStep> = emptyList(),
    val text: String? = null,
    val answer: String? = null,
    val contentUnavailable: Boolean = false,
    val effectReview: NexusEffectReview? = null,
)

/** Read-only application entry: a message reference can never approve or restart an old action. */
internal class NexusExecutionDetails(context: Context) {
    private val appContext = context.applicationContext

    suspend fun read(itemId: String): NexusExecutionDetail? = withContext(Dispatchers.IO) {
        if (!itemId.matches(Regex("item-[a-f0-9]{64}"))) return@withContext null
        val store = NexusExecutionStore.get(appContext)
        val item = store.item(ItemId(itemId)) ?: return@withContext null
        val events = store.itemEvents(item.runId, item.itemId)
        val event = events.lastOrNull() ?: return@withContext null
        val toolName = events.firstNotNullOfOrNull { it.fact.toolName }
        val unknownCompletion = events.lastOrNull {
            it.fact.type == RunEventType.TOOL_ITEM_COMPLETED && it.fact.success == null
        }
        val reviewResolution = events.lastOrNull { it.fact.type == RunEventType.TOOL_EFFECT_REVIEW_RESOLVED }
        val effectReview = unknownCompletion?.let {
            val name = toolName ?: return@let null
            val effect = NexusToolEffectClassifier.classify(name)
            if (effect == EffectClass.READ) null else NexusEffectReview(
                effectClass = effect,
                replayPolicy = NexusToolEffectClassifier.replayPolicy(effect),
                decision = EffectReviewDecision.fromReference(reviewResolution?.fact?.payloadRef),
                resolvedAt = reviewResolution?.fact?.createdAt,
            )
        }
        val base = NexusExecutionDetail(item.runId.value, item.itemId.value, item.kind, item.state, item.startedAt,
            item.completedAt, toolName, event.fact.errorCode, effectReview = effectReview)
        val ref = item.payloadRef?.takeIf { it.startsWith("nexus-workflow://") } ?: return@withContext base
        val kind = runCatching { URI(ref).path.split('/').filter(String::isNotBlank).firstOrNull() }.getOrNull()
            ?.takeIf { it in setOf("plan", "goal", "notepad", "input") } ?: return@withContext base.copy(contentUnavailable = true)
        val expectedKind = when (item.kind) {
            ItemKind.PLAN_REVISION -> "plan"
            ItemKind.GOAL_UPDATE -> "goal"
            ItemKind.NOTEPAD_REVISION -> "notepad"
            ItemKind.USER_INPUT_REQUEST -> "input"
            ItemKind.TOOL_CALL -> when (event.fact.toolName) {
                "plan_manage" -> "plan"
                "goal_manage" -> "goal"
                "notepad_manage" -> "notepad"
                else -> null
            }
            else -> null
        }
        if (expectedKind != kind) return@withContext base.copy(contentUnavailable = true)
        val body = runCatching { WorkflowRevisionStore(appContext).readCommitted(kind, ref) }.getOrNull()
            ?: return@withContext base.copy(contentUnavailable = true)
        base.withBody(kind, body)
    }

    /** Records an explicit local review. It never repeats the original tool call. */
    suspend fun resolveEffectReview(itemId: String, decision: EffectReviewDecision): Boolean = withContext(Dispatchers.IO) {
        if (!itemId.matches(Regex("item-[a-f0-9]{64}"))) return@withContext false
        NexusExecutionStore.get(appContext).resolveEffectReview(ItemId(itemId), decision, System.currentTimeMillis())
        true
    }

    private fun NexusExecutionDetail.withBody(kind: String, body: JSONObject): NexusExecutionDetail = copy(
        revisionKind = kind,
        revision = if (body.has("revision")) body.optLong("revision") else null,
        planId = body.optString("plan_id").takeIf(String::isNotBlank),
        steps = if (kind == "plan") body.optJSONArray("steps")?.let { array ->
            (0 until minOf(array.length(), 200)).map { index -> array.getJSONObject(index).let {
                NexusExecutionStep(it.optString("title"), it.optString("description"), it.optString("verification"))
            } }
        }.orEmpty() else emptyList(),
        text = when (kind) {
            "goal" -> body.optString("objective")
            "notepad" -> body.optString("content", body.optString("text"))
            "input" -> body.optString("question")
            else -> null
        }?.take(24_000),
        answer = body.optJSONObject("result")?.optString("answer")?.takeIf(String::isNotBlank)?.take(4_000),
    )
}
