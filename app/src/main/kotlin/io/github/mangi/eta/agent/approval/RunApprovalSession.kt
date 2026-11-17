package io.github.mangi.eta.agent.approval

import android.content.Context
import io.github.mangi.eta.agent.control.EtaControlCapabilityInfo
import io.github.mangi.eta.agent.control.SettingsControlSnapshots
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.AgentPermissionMode
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import org.json.JSONObject

/** Execution owner, never reachable as a model tool or an authorization-setting capability. */
internal class RunApprovalSession(context: Context, private val task: String, private val project: String) {
    val store = ApprovalLearningStore(context)

    fun scope(info: EtaControlCapabilityInfo, parameters: JSONObject, effect: EffectClass?,
        digest: String?, explicit: ActionScope?): ActionScope = explicit ?: ActionScope(
        info.id, "native-android", SettingsControlSnapshots.hash(info.parameters), effect ?: EffectClass.HIGH_RISK,
        project, digest ?: SettingsControlSnapshots.hash(parameters), false,
    )

    fun review(mode: AgentPermissionMode, controller: AgentRunController, scope: ActionScope,
        parameters: JSONObject): ApprovalReview {
        if (mode != AgentPermissionMode.SMART_APPROVAL) return ApprovalReview("ask_user", "由用户批准此动作")
        val preference = store.matching(scope)
        if (preference?.revoked == true || (preference?.refusals ?: 0) > 0) {
            return ApprovalReview("ask_user", "该动作有真实用户拒绝或撤销记录，需要用户重新决定")
        }
        val evidence = JSONObject().put("matching_approvals", preference?.approvals ?: 0)
            .put("candidate", preference?.isCandidate == true).put("expires_at", preference?.expiresAt ?: JSONObject.NULL)
            .put("scope_is_verified_for_reuse", scope.learnable)
        val input = JSONObject().put("task", task.take(12_000)).put("action_scope", scope.json())
            .put("parameters", parameters).put("user_preference_evidence", evidence)
        val reviewer = controller.approvalReviewer ?: return ApprovalReview("ask_user", "独立审查未就绪，交由用户决定")
        return reviewer(input).copy(evidenceExpiresAt = preference?.expiresAt,
            usesLearnedPreference = preference?.isCandidate == true)
    }
}
