package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.workflow.GoalPlanNotepadStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.fork.projects.ProjectDatabase
import io.github.mangi.eta.fork.projects.ProjectRepository
import io.github.mangi.eta.fork.projects.ProjectSubjectType
import io.github.mangi.eta.fork.tasks.AgentTaskDatabase
import io.github.mangi.eta.fork.tasks.AutomationSpec
import io.github.zhh_lgtm.nexus.kernel.api.GoalId
import io.github.zhh_lgtm.nexus.kernel.api.GoalState
import io.github.zhh_lgtm.nexus.kernel.api.RunBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Trusted admission metadata: no model instructions, credentials or user content. */
internal data class NexusRunContext(
    val projectId: String? = null,
    val goalId: GoalId? = null,
    val goalBudget: RunBudget? = null,
) {
    init { require(goalId != null || goalBudget == null) }
}

/** Resolve once inside successful admission; execution must use the committed association. */
internal class NexusRunContextResolver(context: Context) {
    private val appContext = context.applicationContext

    fun resolve(request: AgentRuntimeWire.RunRequest): NexusRunContext = runBlocking(Dispatchers.IO) {
        val handoff = request.handoff
        val ui = handoff?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload) }
        val packageSubject = RuntimePackageActions.subject(handoff)
        val conversationId = ui?.conversationId?.takeIf(String::isNotBlank) ?: packageSubject?.takeIf { it.kind == io.github.mangi.eta.data.model.ConversationSubject.Kind.CHAT }?.id
        val meetingId = handoff?.takeIf { it.source == AgentRuntimeWire.MEETING_HANDOFF_SOURCE }
            ?.payload?.trim()?.takeIf(String::isNotBlank) ?: packageSubject?.takeIf { it.kind == io.github.mangi.eta.data.model.ConversationSubject.Kind.MEETING }?.id
        val taskId = handoff?.takeIf { it.source == "scheduled_task" }
            ?.payload?.trim()?.takeIf(String::isNotBlank)
        val pendingBranch = conversationId?.let { EtaDatabase.get(appContext).conversationDao().branchSetup(it) }
            ?.takeIf { it.completedAt == null }
        val inheritedProjectPending = pendingBranch != null &&
            !ProjectDatabase.get(appContext).projectDao().hasBranchSetupReceipt(pendingBranch.setupId)
        val projectId = when {
            request.operation == AgentRuntimeWire.OP_MODEL_TASK -> {
                RuntimeModelTasks.kind(request)
                org.json.JSONObject(handoff?.payload.orEmpty()).optString("project_id").takeIf(String::isNotBlank)
            }
            inheritedProjectPending -> pendingBranch?.projectId
            conversationId != null -> ProjectRepository.bindingForSubject(
                appContext, ProjectSubjectType.CONVERSATION, conversationId,
            )?.projectId
            meetingId != null -> ProjectRepository.bindingForSubject(
                appContext, ProjectSubjectType.MEETING, meetingId,
            )?.projectId
            taskId != null -> AgentTaskDatabase.get(appContext).agentTaskDao().definition(taskId)
                ?.let { AutomationSpec.parse(it.policyJson).projectId }
            else -> null
        }
        if (projectId != null) {
            val project = ProjectDatabase.get(appContext).projectDao().project(projectId)
            check(project != null && project.archivedAt == null) { "PROJECT_UNAVAILABLE" }
        }
        val goal = if (conversationId != null && ui?.workflowMode?.uppercase() in setOf("GOAL", "PLAN")) {
            GoalPlanNotepadStore.get(appContext).executionSnapshot(conversationId).goal?.also {
                check(it.sessionId.value == conversationId) { "GOAL_SCOPE_MISMATCH" }
            }?.takeUnless { it.state in setOf(GoalState.COMPLETED, GoalState.FAILED, GoalState.CANCELLED) }
        } else null
        NexusRunContext(projectId, goal?.goalId, goal?.budget)
    }
}
