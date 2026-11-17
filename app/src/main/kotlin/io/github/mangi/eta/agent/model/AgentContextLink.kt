package io.github.mangi.eta.agent.model

import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * 长会话四层上下文的稳定关联：事件账本、压缩摘要、工作集/检查点、任务/工作流状态。
 *
 * 只保存标识、游标与摘要哈希，不复制四层数据，也不新增平行会话数据库。缺少本字段的旧快照
 * 仍走既有安全恢复路径；字段齐全时恢复必须先通过 [AgentContextRestore] 的逐层校验。
 */
@Serializable
internal data class AgentContextLink(
    val version: Int = 1,
    /** Runtime run 标识，与快照 operationId、账本映射的 run 一致。 */
    val runId: String,
    /** NexusExecutionStore 执行账本的 RunId，由 runId 稳定映射。 */
    val ledgerRunId: String,
    val conversationId: String,
    /** Goal/Plan/Notepad 工作流状态所在作用域。 */
    val scopeId: String,
    /** 摘要覆盖的用户回合游标，必须等于快照的 coveredUserTurns。 */
    val compactedThrough: Int,
    /** 摘要正文的脱敏哈希；正文只存在快照自身。 */
    val summaryDigest: String,
    /** 工作集/检查点引用，指向该 run 的持久 checkpoint。 */
    val checkpointRef: String,
    val goalRevision: Long = 0,
    val planRevision: Long = 0,
    /** 任务状态身份：作用域、工作流模式与 Goal 标识共同决定。 */
    val taskIdentity: String,
) {
    companion object {
        fun runtimeCheckpointRef(runId: String): String = "runtime-checkpoint:$runId"

        fun create(
            runId: String,
            ledgerRunId: String,
            conversationId: String,
            scopeId: String,
            compactedThrough: Int,
            messages: List<AgentModelClient.ConversationMessage>,
            checkpointRef: String,
            mode: String?,
            goalId: String?,
            goalRevision: Long = 0,
            planRevision: Long = 0,
        ): AgentContextLink = AgentContextLink(
            version = 2,
            runId = runId,
            ledgerRunId = ledgerRunId,
            conversationId = conversationId,
            scopeId = scopeId,
            compactedThrough = compactedThrough,
            summaryDigest = digest(messages),
            checkpointRef = checkpointRef,
            goalRevision = goalRevision,
            planRevision = planRevision,
            taskIdentity = taskIdentity(scopeId, mode, goalId, goalRevision, planRevision),
        )

        fun digest(messages: List<AgentModelClient.ConversationMessage>): String =
            sha256(AgentConversationCodec.encodeTranscriptForStorage(messages))

        fun taskIdentity(scopeId: String, mode: String?, goalId: String?): String =
            sha256(listOf(scopeId, mode.orEmpty(), goalId.orEmpty()).joinToString("\u0000"))

        fun taskIdentity(
            scopeId: String,
            mode: String?,
            goalId: String?,
            goalRevision: Long,
            planRevision: Long,
        ): String = sha256(listOf(
            scopeId, mode.orEmpty(), goalId.orEmpty(), goalRevision.toString(), planRevision.toString(),
        ).joinToString("\u0000"))

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** 恢复顺序固定：事件账本 → 摘要/检查点 → 工作集 → 任务状态；任一不匹配即失败关闭。 */
internal object AgentContextRestore {
    enum class Layer(val order: Int) {
        EVENT_LEDGER(1),
        SUMMARY_CHECKPOINT(2),
        WORKING_SET(3),
        TASK_STATE(4),
    }

    data class Decision(
        val snapshot: AgentContextSnapshot?,
        val layer: Layer? = null,
        val failure: String? = null,
    )

    /**
     * 无关联元数据的旧快照原样返回，沿用既有 operationId 校验；有关联元数据时按层比对，
     * 任一 scope、游标、哈希或任务身份不一致都不返回快照，调用方只能回退到事件账本/transcript。
     */
    fun decide(
        snapshot: AgentContextSnapshot?,
        runId: String,
        ledgerRunId: String,
        conversationId: String,
        scopeId: String,
        mode: String?,
        goalId: String?,
        goalRevision: Long = 0,
        planRevision: Long = 0,
    ): Decision {
        if (snapshot == null) return Decision(null)
        val link = snapshot.link ?: return Decision(snapshot)
        if (link.runId != runId || link.ledgerRunId != ledgerRunId) {
            return Decision(null, Layer.EVENT_LEDGER, "CONTEXT_LINK_RUN_MISMATCH")
        }
        if (link.compactedThrough != snapshot.coveredUserTurns ||
            link.summaryDigest != AgentContextLink.digest(snapshot.messages)
        ) {
            return Decision(null, Layer.SUMMARY_CHECKPOINT, "CONTEXT_LINK_SUMMARY_MISMATCH")
        }
        if (link.checkpointRef != AgentContextLink.runtimeCheckpointRef(runId)) {
            return Decision(null, Layer.WORKING_SET, "CONTEXT_LINK_WORKING_SET_MISMATCH")
        }
        if (link.conversationId != conversationId || link.scopeId != scopeId) {
            return Decision(null, Layer.TASK_STATE, "CONTEXT_LINK_SCOPE_MISMATCH")
        }
        val expectedTaskIdentity = if (link.version >= 2) {
            AgentContextLink.taskIdentity(scopeId, mode, goalId, goalRevision, planRevision)
        } else {
            AgentContextLink.taskIdentity(scopeId, mode, goalId)
        }
        if (link.taskIdentity != expectedTaskIdentity) {
            return Decision(null, Layer.TASK_STATE, "CONTEXT_LINK_TASK_MISMATCH")
        }
        return Decision(snapshot)
    }
}
