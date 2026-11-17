package io.github.mangi.eta.agent.approval

import io.github.mangi.eta.data.model.AgentPermissionMode
import io.github.mangi.eta.data.model.AgentPermissionState
import io.github.mangi.eta.agent.runtime.AgentToolPolicy
import org.json.JSONObject

/** A run can lose authority immediately, but a later global relaxation cannot widen it. */
internal class RunPolicySnapshot(
    val runId: String,
    val capturedAt: Long,
    val capturedRevision: Long,
    val capturedMode: AgentPermissionMode,
    toolPolicy: AgentToolPolicy,
) {
    @Volatile private var restriction = capturedMode
    @Volatile var toolPolicy: AgentToolPolicy = toolPolicy
        private set
    @Volatile var persistRestriction: (() -> Unit)? = null

    @Synchronized fun restrictTools(policy: AgentToolPolicy) {
        toolPolicy = toolPolicy.intersect(policy)
        persistRestriction?.invoke()
    }

    @Synchronized fun effectiveMode(state: AgentPermissionState): AgentPermissionMode {
        val sinceCapture = when {
            state.lastManualRestrictionRevision > capturedRevision -> AgentPermissionMode.MANUAL_APPROVAL
            state.lastSmartRestrictionRevision > capturedRevision -> AgentPermissionMode.SMART_APPROVAL
            else -> capturedMode
        }
        restriction = listOf(restriction, state.mode, sinceCapture).maxBy(::strictness)
        return restriction
    }

    fun toJson() = JSONObject().put("run_id", runId).put("captured_at", capturedAt)
        .put("captured_revision", capturedRevision).put("mode", capturedMode.wireValue)
        .put("allowed_tools", toolPolicy.allowedTools?.let { org.json.JSONArray(it) } ?: JSONObject.NULL)
        .put("denied_tools", org.json.JSONArray(toolPolicy.disallowedTools))

    companion object {
        private fun strictness(mode: AgentPermissionMode) = when (mode) {
            AgentPermissionMode.FULL_ACCESS -> 0
            AgentPermissionMode.SMART_APPROVAL -> 1
            AgentPermissionMode.MANUAL_APPROVAL -> 2
        }
    }
}

/** Weak ownership avoids keeping finished Runs alive; live runs only ever intersect authority. */
internal object ActiveRunPolicies {
    private val policies = java.util.concurrent.ConcurrentHashMap<String, java.lang.ref.WeakReference<RunPolicySnapshot>>()
    fun register(policy: RunPolicySnapshot) { policies[policy.runId] = java.lang.ref.WeakReference(policy) }
    fun restrictTools(policy: AgentToolPolicy) {
        policies.entries.forEach { (id, weak) ->
            weak.get()?.restrictTools(policy) ?: policies.remove(id, weak)
        }
    }
}
