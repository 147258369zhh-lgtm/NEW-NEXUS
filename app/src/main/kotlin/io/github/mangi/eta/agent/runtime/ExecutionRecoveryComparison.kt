package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.data.repository.EffectReviewDecision
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.application.api.ExecutionCommit
import io.github.zhh_lgtm.nexus.application.api.ExecutionRunSnapshot
import io.github.zhh_lgtm.nexus.kernel.api.ExecutionItem
import io.github.zhh_lgtm.nexus.kernel.api.ItemKind
import io.github.zhh_lgtm.nexus.kernel.api.RunEventType
import io.github.zhh_lgtm.nexus.kernel.api.RunState
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only evidence comparison. Checkpoint/transcript/context and UI Item projection are read
 * through their existing owners; no recovery source is changed and no tool is replayed.
 */
internal object ExecutionRecoveryComparison {
    private const val MAX_REPORTED_IDS = 20

    data class CheckpointEvidence(
        val status: String,
        val digest: String?,
        val throughSequence: Long?,
        val inputMessages: Int,
        val transcriptMessages: Int,
        val contextMessages: Int?,
        val contextPresent: Boolean,
        val transcriptDigest: String?,
        val contextDigest: String?,
        val omittedInputImages: Int,
        val issues: Set<String>,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("status", status)
            .put("digestVerified", !digest.isNullOrBlank())
            .put("digest", digest ?: JSONObject.NULL)
            .put("throughSequence", throughSequence ?: JSONObject.NULL)
            .put("inputMessages", inputMessages)
            .put("transcriptMessages", transcriptMessages)
            .put("contextMessages", contextMessages ?: JSONObject.NULL)
            .put("contextPresent", contextPresent)
            .put("transcriptDigest", transcriptDigest ?: JSONObject.NULL)
            .put("contextDigest", contextDigest ?: JSONObject.NULL)
            .put("inputImagesRequireReattach", omittedInputImages > 0)
            .put("issues", JSONArray(issues.sorted()))
    }

    data class UiProjectionEvidence(
        val status: String,
        val itemCount: Int,
        val toolItems: Int,
        val openItems: Int,
        val kinds: Map<String, Int>,
        val missingItemIds: List<String>,
        val unexpectedItemIds: List<String>,
        val issues: Set<String>,
        val items: Map<String, ExecutionItem>,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("status", status)
            .put("items", itemCount)
            .put("toolItems", toolItems)
            .put("openItems", openItems)
            .put("toolItemIds", JSONArray(items.values.filter { it.kind == ItemKind.TOOL_CALL }
                .map { it.itemId.value }.sorted().take(MAX_REPORTED_IDS * 2)))
            .put("toolStates", JSONObject().also { json ->
                items.values.filter { it.kind == ItemKind.TOOL_CALL }.sortedBy { it.itemId.value }
                    .take(MAX_REPORTED_IDS * 2).forEach { item ->
                        json.put(item.itemId.value, item.state?.name ?: JSONObject.NULL)
                    }
            })
            .put("kinds", JSONObject().also { json -> kinds.toSortedMap().forEach { (kind, count) -> json.put(kind, count) } })
            .put("missingItemIds", JSONArray(missingItemIds))
            .put("unexpectedItemIds", JSONArray(unexpectedItemIds))
            .put("issues", JSONArray(issues.sorted()))
    }

    private data class ExpectedItem(
        var kind: ItemKind,
        var finished: Boolean = false,
        var terminalState: RunState? = null,
    )

    fun checkpoint(store: NexusExecutionStore, run: ExecutionRunSnapshot): CheckpointEvidence {
        val result = runCatching { store.checkpoint(run.request.runId) }
        if (result.isFailure) {
            return CheckpointEvidence("corrupt", null, null, 0, 0, null, false, null, null, 0,
                setOf("CHECKPOINT_UNREADABLE"))
        }
        val checkpoint = result.getOrNull()
            ?: return CheckpointEvidence("missing", null, null, 0, 0, null, false, null, null, 0,
                setOf("CHECKPOINT_MISSING"))

        val issues = linkedSetOf<String>()
        val json = runCatching { JSONObject(checkpoint.payload) }.getOrNull()
        if (json == null) {
            return CheckpointEvidence("invalid_content", checkpoint.sha256, checkpoint.throughSequence,
                0, 0, null, false, null, null, 0, setOf("CHECKPOINT_CONTENT_UNREADABLE"))
        }
        if (checkpoint.sha256.isBlank()) issues += "CHECKPOINT_DIGEST_MISSING"
        if (checkpoint.throughSequence > run.lastSequence || checkpoint.throughSequence != run.checkpointSequence) {
            issues += "CHECKPOINT_CURSOR_MISMATCH"
        }
        if (json.optString("format") != "nexus-runtime-checkpoint") issues += "CHECKPOINT_FORMAT_MISMATCH"
        if (json.optInt("schema_version", -1) != 1) issues += "CHECKPOINT_SCHEMA_MISMATCH"
        if (json.optString("run_id") != run.request.runId.value) issues += "CHECKPOINT_RUN_ID_MISMATCH"

        val input = json.optJSONArray("input")
        if (input == null) issues += "CHECKPOINT_INPUT_MISSING" else if (input.length() == 0) issues += "CHECKPOINT_INPUT_EMPTY"
        val transcript = json.optJSONArray("transcript")
        if (transcript == null) issues += "CHECKPOINT_TRANSCRIPT_MISSING"
        val transcriptHash = transcript?.let(::transcriptDigest)
        if (transcript != null && transcriptHash == null) issues += "CHECKPOINT_TRANSCRIPT_INVALID"

        val contextPresent = !json.isNull("context") && json.has("context")
        val context = if (contextPresent) json.optJSONObject("context") else null
        if (contextPresent && context == null) issues += "CHECKPOINT_CONTEXT_INVALID"
        val contextHash = context?.let { contextDigest(it) }
        if (context != null && contextHash == null) issues += "CHECKPOINT_CONTEXT_INVALID"

        val status = when {
            "CHECKPOINT_CURSOR_MISMATCH" in issues -> "cursor_mismatch"
            issues.isEmpty() -> "valid"
            else -> "invalid_content"
        }
        return CheckpointEvidence(
            status = status,
            digest = checkpoint.sha256,
            throughSequence = checkpoint.throughSequence,
            inputMessages = input?.length() ?: 0,
            transcriptMessages = transcript?.length() ?: 0,
            contextMessages = context?.optJSONArray("messages")?.length(),
            contextPresent = contextPresent,
            transcriptDigest = transcriptHash,
            contextDigest = contextHash,
            omittedInputImages = json.optInt("omitted_input_images", 0),
            issues = issues,
        )
    }

    fun uiProjection(store: NexusExecutionStore, run: ExecutionRunSnapshot): UiProjectionEvidence = runCatching {
        readUiProjection(store, run)
    }.getOrElse {
        UiProjectionEvidence("unreadable", 0, 0, 0, emptyMap(), emptyList(), emptyList(),
            setOf("UI_PROJECTION_UNREADABLE"), emptyMap())
    }

    private fun readUiProjection(store: NexusExecutionStore, run: ExecutionRunSnapshot): UiProjectionEvidence {
        val issues = linkedSetOf<String>()
        val commits = readCommits(store, run)
        val expected = linkedMapOf<String, ExpectedItem>()
        var abnormalTerminal = false
        commits.forEach { commit ->
            val event = commit.event
            if (event.type in setOf(RunEventType.RUN_FAILED, RunEventType.RUN_CANCELLED,
                    RunEventType.RUN_INTERRUPTED)) abnormalTerminal = true
            val itemId = event.itemId?.value ?: return@forEach
            val kind = expectedKind(commit)
            if (kind == null) {
                issues += "UI_ITEM_KIND_UNRESOLVED"
                return@forEach
            }
            val item = expected.getOrPut(itemId) { ExpectedItem(kind) }
            if (item.kind != kind) issues += "UI_ITEM_KIND_CONFLICT"
            if (isFinished(commit)) item.finished = true
            if (kind == ItemKind.TOOL_CALL) {
                when (event.type) {
                    RunEventType.TOOL_ITEM_COMPLETED -> item.terminalState = when (commit.fact.success) {
                        true -> RunState.SUCCEEDED
                        false -> RunState.FAILED
                        null -> RunState.VERIFYING
                    }
                    RunEventType.TOOL_EFFECT_REVIEW_RESOLVED -> item.terminalState =
                        if (EffectReviewDecision.fromReference(commit.fact.payloadRef) == EffectReviewDecision.CANCELLED)
                            RunState.CANCELLED else RunState.INTERRUPTED
                    else -> Unit
                }
            }
        }
        if (abnormalTerminal) {
            expected.values.filterNot { it.finished }.forEach { it.terminalState = RunState.INTERRUPTED }
        }

        val stored = store.items(run.request.runId).associateBy { it.itemId.value }
        val missing = expected.keys.filterNot(stored::containsKey).take(MAX_REPORTED_IDS)
        val unexpected = stored.keys.filterNot(expected::containsKey).take(MAX_REPORTED_IDS)
        if (missing.isNotEmpty()) issues += "UI_ITEM_MISSING"
        if (unexpected.isNotEmpty()) issues += "UI_ITEM_UNEXPECTED"
        expected.forEach { (itemId, projection) ->
            val item = stored[itemId] ?: return@forEach
            if (item.kind != projection.kind) issues += "UI_ITEM_KIND_MISMATCH"
            if (projection.finished && item.completedAt == null) issues += "UI_ITEM_COMPLETION_MISSING"
            if (!projection.finished && item.completedAt != null) issues += "UI_ITEM_UNEXPECTED_COMPLETION"
            projection.terminalState?.let { expectedState ->
                if (item.state != expectedState) issues += "UI_TOOL_STATE_MISMATCH"
            }
            if (run.state in NexusExecutionStore.terminalStates && item.completedAt == null &&
                item.state in setOf(RunState.RUNNING, RunState.WAITING_USER, RunState.WAITING_AUTH,
                    RunState.WAITING_EVENT, RunState.PAUSED, RunState.VERIFYING)) {
                issues += "UI_ITEM_LEFT_OPEN"
            }
        }
        val kinds = stored.values.groupingBy { it.kind.name }.eachCount()
        val status = when {
            expected.isEmpty() && stored.isEmpty() -> "match"
            stored.isEmpty() -> "missing"
            issues.isEmpty() -> "match"
            else -> "mismatch"
        }
        return UiProjectionEvidence(
            status = status,
            itemCount = stored.size,
            toolItems = kinds[ItemKind.TOOL_CALL.name] ?: 0,
            openItems = stored.values.count { it.completedAt == null },
            kinds = kinds,
            missingItemIds = missing,
            unexpectedItemIds = unexpected,
            issues = issues,
            items = stored,
        )
    }

    fun transcriptDigest(messages: List<io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage>): String =
        sha256(AgentConversationCodec.encodeTranscriptForStorage(messages))

    fun transcriptDigest(array: JSONArray): String? = runCatching {
        transcriptDigest(AgentConversationCodec.decodeTranscript(array.toString()))
    }.getOrNull()

    fun contextDigest(snapshot: AgentContextSnapshot?): String? = snapshot?.let { sha256(it.encode()) }

    private fun contextDigest(json: JSONObject): String? = runCatching {
        AgentContextSnapshot.decode(json.toString())?.let { sha256(it.encode()) }
    }.getOrNull()

    private fun readCommits(store: NexusExecutionStore, run: ExecutionRunSnapshot): List<ExecutionCommit> {
        val commits = mutableListOf<ExecutionCommit>()
        var after = -1L
        while (true) {
            val page = store.timeline(run.request.runId, after, 200)
            if (page.isEmpty()) break
            commits += page
            after = page.last().event.sequence
        }
        return commits
    }

    private fun expectedKind(commit: ExecutionCommit): ItemKind? = when (commit.event.type) {
        RunEventType.MODEL_BUDGET_RESERVED, RunEventType.MODEL_BUDGET_SETTLED -> ItemKind.MODEL_REQUEST
        RunEventType.TOOL_ITEM_STARTED, RunEventType.TOOL_ITEM_COMPLETED,
        RunEventType.TOOL_EFFECT_REVIEW_RESOLVED -> ItemKind.TOOL_CALL
        RunEventType.ASSISTANT_ITEM_STARTED, RunEventType.ASSISTANT_ITEM_COMPLETED -> ItemKind.ASSISTANT_MESSAGE
        RunEventType.CONTEXT_COMPACTED -> ItemKind.CONTEXT_COMPACTION
        RunEventType.APPROVAL_REQUIRED, RunEventType.APPROVAL_RESOLVED -> ItemKind.APPROVAL
        RunEventType.USER_INPUT_REQUIRED, RunEventType.USER_INPUT_RESOLVED -> ItemKind.USER_INPUT_REQUEST
        RunEventType.EVENT_WAIT_REQUIRED, RunEventType.EVENT_WAIT_RESOLVED -> ItemKind.EVENT_WAIT
        RunEventType.PLAN_UPDATED -> ItemKind.PLAN_REVISION
        RunEventType.NOTEPAD_UPDATED -> ItemKind.NOTEPAD_REVISION
        RunEventType.GOAL_UPDATED -> ItemKind.GOAL_UPDATE
        RunEventType.WORKFLOW_UPDATE_STARTED, RunEventType.WORKFLOW_UPDATE_FAILED -> when (commit.fact.toolName) {
            "goal_manage" -> ItemKind.GOAL_UPDATE
            "plan_manage" -> ItemKind.PLAN_REVISION
            "notepad_manage" -> ItemKind.NOTEPAD_REVISION
            else -> null
        }
        else -> null
    }

    private fun isFinished(commit: ExecutionCommit): Boolean = when (commit.event.type) {
        RunEventType.TOOL_ITEM_COMPLETED, RunEventType.ASSISTANT_ITEM_COMPLETED,
        RunEventType.APPROVAL_RESOLVED, RunEventType.PLAN_UPDATED, RunEventType.GOAL_UPDATED,
        RunEventType.NOTEPAD_UPDATED, RunEventType.WORKFLOW_UPDATE_FAILED,
        RunEventType.MODEL_BUDGET_SETTLED, RunEventType.USER_INPUT_RESOLVED,
        RunEventType.EVENT_WAIT_RESOLVED, RunEventType.TOOL_EFFECT_REVIEW_RESOLVED -> true
        RunEventType.CONTEXT_COMPACTED -> commit.fact.success != null
        else -> false
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
