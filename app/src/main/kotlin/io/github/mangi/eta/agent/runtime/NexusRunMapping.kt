package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentRunBudgets
import java.security.MessageDigest
import io.github.zhh_lgtm.nexus.kernel.api.HostId
import io.github.zhh_lgtm.nexus.kernel.api.RunBudget
import io.github.zhh_lgtm.nexus.kernel.api.RunId
import io.github.zhh_lgtm.nexus.kernel.api.RunMode
import io.github.zhh_lgtm.nexus.kernel.api.RunRequest
import io.github.zhh_lgtm.nexus.kernel.api.RunSource
import io.github.zhh_lgtm.nexus.kernel.api.SessionId
import io.github.zhh_lgtm.nexus.kernel.api.TaskId
import io.github.zhh_lgtm.nexus.kernel.api.intersect

/** Canonical metadata bridge for the existing Runtime wire request. */
internal data class NexusRunMapping(
    val request: RunRequest,
    val legacySource: String,
)

internal object NexusRunMapper {
    fun canonicalRunId(legacyId: String): RunId = RunId(stableId("run",legacyId))
    private const val SCHEDULED_SOURCE = "scheduled_task"
    private const val A2A_SOURCE = "a2a"
    private const val CONVERSATION_COMPACTION_SOURCE = "conversation_compaction"

    fun fromLegacy(
        legacy: AgentRuntimeWire.RunRequest,
        budget: RunBudget = AgentRunBudgets.forSource(legacy.handoff?.source),
        context: NexusRunContext = NexusRunContext(),
    ): NexusRunMapping {
        val source = source(legacy)
        val mode = mode(legacy, source)
        val conversationId = if (legacy.operation == AgentRuntimeWire.OP_MODEL_TASK || legacy.handoff?.source in setOf(AgentRuntimeWire.MEETING_HANDOFF_SOURCE, SCHEDULED_SOURCE, A2A_SOURCE)) {
            legacy.modelSessionId.takeIf(String::isNotBlank)
                ?: AgentConversationIdentity.fromHandoff(legacy.handoff, legacy.runId)
        } else AgentConversationIdentity.fromHandoff(legacy.handoff, legacy.runId)
        val taskId = legacy.handoff?.payload
            ?.takeIf { source == RunSource.AUTOMATION && it.isNotBlank() }
            ?.let { TaskId(stableId("task", it)) }
        val taskBudget = if (legacy.operation == AgentRuntimeWire.OP_MODEL_TASK)
            budget.intersect(RunBudget(hardDurationMs = RuntimeModelTasks.kind(legacy).timeoutMs)) else budget
        val request = RunRequest(
            runId = canonicalRunId(legacy.runId),
            source = source,
            mode = mode,
            hostId = HostId("native-android"),
            sessionId = SessionId(stableId("session", conversationId)),
            taskId = taskId,
            projectId = context.projectId,
            goalId = context.goalId,
            budget = context.goalBudget?.let(taskBudget::intersect) ?: taskBudget,
        )
        return NexusRunMapping(request, legacy.handoff?.source.orEmpty())
    }

    fun source(legacy: AgentRuntimeWire.RunRequest): RunSource {
        if (legacy.operation == AgentRuntimeWire.OP_PACKAGE_ACTION) {
            require(legacy.handoff?.source == RuntimePackageActions.SOURCE) { "PACKAGE_SOURCE_REQUIRED" }
            return RunSource.PLUGIN
        }
        if (legacy.operation == AgentRuntimeWire.OP_MODEL_TASK) return when (RuntimeModelTasks.kind(legacy)) {
            RuntimeModelTaskKind.MEMORY, RuntimeModelTaskKind.SKILL, RuntimeModelTaskKind.NOTEPAD -> RunSource.NOTE_CONSOLIDATION
            RuntimeModelTaskKind.TRANSCRIPT -> RunSource.CHAT
        }
        return when (legacy.handoff?.source) {
        AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE ->
            if (AgentUiHandoffPayload.from(legacy.handoff.payload).workflowMode.equals("GOAL", ignoreCase = true)) RunSource.GOAL
            else RunSource.CHAT
        AgentRuntimeWire.MEETING_HANDOFF_SOURCE ->
            if (legacy.promptScope.meetingHost ||
                (legacy.promptScope.stage == io.github.mangi.eta.agent.prompt.PromptStage.AUTO &&
                    legacy.promptScope.meetingParticipant.isBlank())) RunSource.MEETING_HOST
            else RunSource.MEETING_PARTICIPANT
        AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE -> RunSource.CHAT
        SCHEDULED_SOURCE -> RunSource.AUTOMATION
        A2A_SOURCE -> RunSource.A2A_INBOUND
        CONVERSATION_COMPACTION_SOURCE -> RunSource.NOTE_CONSOLIDATION
        else -> RunSource.CHAT
        }
    }

    fun mode(legacy: AgentRuntimeWire.RunRequest, source: RunSource = source(legacy)): RunMode = when (source) {
        RunSource.MEETING_HOST -> RunMode.MEETING_HOST
        RunSource.MEETING_PARTICIPANT -> RunMode.MEETING_PARTICIPANT
        RunSource.SUBAGENT -> RunMode.TASK
        RunSource.AUTOMATION -> RunMode.TASK
        RunSource.A2A_INBOUND, RunSource.A2A_OUTBOUND -> RunMode.A2A
        RunSource.GOAL -> RunMode.GOAL
        RunSource.NOTE_CONSOLIDATION -> RunMode.TASK
        RunSource.PLUGIN -> RunMode.TASK
        RunSource.CHAT -> when {
            legacy.operation in setOf(AgentRuntimeWire.OP_COMPACT, AgentRuntimeWire.OP_MODEL_TASK) -> RunMode.TASK
            legacy.handoff?.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE &&
                AgentUiHandoffPayload.from(legacy.handoff.payload).workflowMode.equals("PLAN", ignoreCase = true) -> RunMode.PLAN
            else -> RunMode.CHAT
        }
    }

    /** Legacy profile/session names may contain Unicode; preserve identity without stripping collisions. */
    private fun stableId(kind: String, value: String): String =
        if (value.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$"))) value
        else "$kind-" + MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
