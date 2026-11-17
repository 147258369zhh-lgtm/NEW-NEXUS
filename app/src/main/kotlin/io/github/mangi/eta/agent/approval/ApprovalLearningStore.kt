package io.github.mangi.eta.agent.approval

import android.content.Context
import io.github.mangi.eta.agent.control.SettingsControlSnapshots
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Exact action identity; unknown account/server versions never become reusable preferences. */
internal data class ActionScope(
    val tool: String, val hostIdentity: String, val schemaDigest: String, val effect: EffectClass,
    val project: String, val argumentsDigest: String, val learnable: Boolean,
    val targetLabel: String = "",
) {
    fun json() = JSONObject().put("tool", tool).put("host", hostIdentity).put("schema", schemaDigest)
        .put("effect_version", 1).put("effect", effect.name).put("project", project)
        .put("arguments", argumentsDigest).put("learnable", learnable).put("target", targetLabel)
    val key: String get() = SettingsControlSnapshots.hash(json())
}

internal data class ApprovalPreference(
    val key: String, val tool: String, val project: String, val effect: String, val approvals: Int,
    val refusals: Int, val expiresAt: Long, val revoked: Boolean, val lastEvidenceAt: Long,
    val targetLabel: String = "",
    val restoredPending: Boolean = false,
) {
    val isCandidate: Boolean get() = approvals >= 3 && refusals == 0 && !revoked && !restoredPending
}

/** Separate from model-writable learning_feedback and from settings grants. */
internal class ApprovalLearningStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nexa_execution_approval_v1", Context.MODE_PRIVATE)
    fun learningEnabled(): Boolean = prefs.getBoolean("learning_enabled", true)
    fun validityDays(): Int = prefs.getInt("validity_days", 7).coerceIn(1, 30)
    fun setLearningEnabledFromUi(enabled: Boolean) = synchronized(lock) {
        commit(prefs.edit().putBoolean("learning_enabled", enabled))
    }
    fun setValidityDaysFromUi(days: Int) = synchronized(lock) {
        require(days in setOf(1, 7, 30))
        commit(prefs.edit().putInt("validity_days", days))
    }
    fun list(): List<ApprovalPreference> = synchronized(lock) {
        val rows = rows()
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(::decode) }
            .sortedByDescending { it.lastEvidenceAt }
    }
    fun matching(scope: ActionScope): ApprovalPreference? {
        if (!scope.learnable) return null
        val preference = list().firstOrNull {
            it.key == scope.key && System.currentTimeMillis() < it.expiresAt
        }
        return preference?.takeIf { (learningEnabled() && !it.restoredPending) || it.revoked || it.refusals > 0 }
    }

    /** Called only by the visible local confirmation host's successful button resolution. */
    internal fun recordLocalUserDecision(scope: ActionScope, approved: Boolean, runId: String, requestId: String) = synchronized(lock) {
        val now = System.currentTimeMillis()
        appendAuditLocked(JSONObject().put("type", "local_user_decision").put("run_id", runId)
            .put("request_id", requestId).put("scope", scope.json()).put("approved", approved).put("at", now))
        if (!scope.learnable || !learningEnabled()) return@synchronized
        val all = rows()
        val entries = (0 until all.length()).mapNotNull { all.optJSONObject(it) }.toMutableList()
        val existing = entries.firstOrNull { it.optString("key") == scope.key }
        val current = existing?.takeIf { !it.optBoolean("revoked") && it.optLong("expires_at") > now }
            ?: JSONObject().put("key", scope.key).put("tool", scope.tool).put("project", scope.project)
                .put("scope", scope.json()).put("effect", scope.effect.name).put("target", scope.targetLabel)
                .put("approvals", 0).put("refusals", 0)
        current.put(if (approved) "approvals" else "refusals", current.optInt(if (approved) "approvals" else "refusals") + 1)
            .put("revoked", false).put("restored_pending", false).put("last_evidence_at", now)
            .put("expires_at", now + validityDays() * 86_400_000L)
        entries.removeAll { it.optString("key") == scope.key }
        entries.add(current)
        commit(prefs.edit().putString("preferences", JSONArray(entries.takeLast(200)).toString()))
    }

    /** Revocation is retained as a tombstone; deleting a row must not resurrect automatic reuse. */
    fun revokeFromUi(key: String): Boolean = synchronized(lock) {
        val all = rows()
        var found = false
        for (index in 0 until all.length()) all.optJSONObject(index)?.takeIf { it.optString("key") == key }?.let {
            it.put("revoked", true).put("approvals", 0).put("last_evidence_at", System.currentTimeMillis())
            found = true
        }
        if (found) {
            commit(prefs.edit().putString("preferences", all.toString()))
            appendAuditLocked(JSONObject().put("type", "local_user_revocation").put("scope_key", key)
                .put("at", System.currentTimeMillis()))
        }
        found
    }

    fun rememberPolicy(policy: RunPolicySnapshot) = synchronized(lock) {
        val policies = JSONObject(prefs.getString("policies", "{}").orEmpty())
        policies.put(policy.runId, policy.toJson())
        // Do not evict policy identities while persistent goals/checkpoints may still resume them.
        commit(prefs.edit().putString("policies", policies.toString()))
    }
    fun rememberedPolicy(runId: String): JSONObject? = synchronized(lock) {
        JSONObject(prefs.getString("policies", "{}").orEmpty()).optJSONObject(runId)
    }

    fun exportEvidence(): ApprovalPreferenceBackup = synchronized(lock) {
        val source = rows()
        ApprovalPreferenceBackup(exportedAt = System.currentTimeMillis(), validityDays = validityDays(),
            records = (0 until source.length()).map { ApprovalPreferenceEvidence.fromJson(source.getJSONObject(it)) })
            .also { it.validate() }
    }

    /** Imported positive evidence never supplies approval counts for automatic candidate reuse. */
    fun restoreEvidence(backup: ApprovalPreferenceBackup, clearRuntimeState: Boolean = false,
        localBlockers: ApprovalPreferenceBackup? = null) = synchronized(lock) {
        backup.validate(); localBlockers?.validate()
        val existing = rows()
        val merged = linkedMapOf<String, JSONObject>()
        for (i in 0 until existing.length()) {
            val raw = existing.getJSONObject(i)
            val record = ApprovalPreferenceEvidence.fromJson(raw).also { it.validate() }
            merged[record.key] = if (clearRuntimeState) record.json(pending = true) else JSONObject(raw.toString())
        }
        fun importEvidence(record: ApprovalPreferenceEvidence) {
            val previous = merged[record.key]
            val next = record.json(pending = true)
            if (previous != null) {
                next.put("revoked", previous.optBoolean("revoked") || record.revoked)
                    .put("refusals", maxOf(previous.optInt("refusals"), record.refusals))
                    .put("historical_approvals", maxOf(previous.optInt("historical_approvals"), record.historicalApprovals, record.approvals))
                if (!clearRuntimeState && !previous.optBoolean("restored_pending")) {
                    // Genuine evidence already on this device is never inflated by an import.
                    next.put("approvals", previous.optInt("approvals")).put("restored_pending", false)
                }
                if (next.optBoolean("revoked") || next.optInt("refusals") > 0) {
                    next.put("expires_at", maxOf(previous.optLong("expires_at"), record.expiresAt))
                }
            }
            merged[record.key] = next
        }
        backup.records.forEach(::importEvidence)
        localBlockers?.records?.forEach(::importEvidence)
        require(merged.values.count { it.optBoolean("revoked") || it.optInt("refusals") > 0 } <= 200) {
            "审批拒绝或撤销范围超过恢复限额，未删除任何负偏好"
        }
        // Local denials/revocations win retention too; imported evidence cannot evict them.
        val retained = merged.values.sortedWith(compareByDescending<JSONObject> { it.optBoolean("revoked") || it.optInt("refusals") > 0 }
            .thenByDescending { it.optLong("last_evidence_at") }).take(200)
        val editor = prefs.edit().putString("preferences", JSONArray(retained).toString())
            .putBoolean("learning_enabled", false).putInt("validity_days", backup.validityDays)
        if (clearRuntimeState) editor.putString("audit", "[]") // no restored single-action receipt
        commit(editor)
        appendAuditLocked(JSONObject().put("type", "evidence_restored_pending_review").put("records", backup.records.size)
            .put("at", System.currentTimeMillis()))
    }

    /** Mint and atomically spend exactly one decision for exactly one action; never a wildcard. */
    fun consumeSingleActionGrant(scope: ActionScope, runId: String, basis: String, validUntil: Long): String = synchronized(lock) {
        check(System.currentTimeMillis() < validUntil) { "APPROVAL_GRANT_EXPIRED" }
        val id = UUID.randomUUID().toString()
        appendAuditLocked(JSONObject().put("type", "grant_consumed").put("grant_id", id)
            .put("run_id", runId).put("scope", scope.json()).put("basis", basis)
            .put("valid_until", validUntil).put("at", System.currentTimeMillis()))
        id
    }
    fun auditDecision(scope: ActionScope, runId: String, decision: String, reason: String,
        parameters: JSONObject? = null) = synchronized(lock) {
        appendAuditLocked(JSONObject().put("type", "decision").put("run_id", runId).put("scope", scope.json())
            .put("decision", decision).put("reason", protectedReason(reason, parameters)).put("at", System.currentTimeMillis()))
    }
    fun executionResult(runId: String, tool: String, argumentsDigest: String, outcome: String) = synchronized(lock) {
        appendAuditLocked(JSONObject().put("type", "execution_result").put("run_id", runId).put("tool", tool)
            .put("arguments", argumentsDigest).put("outcome", outcome).put("at", System.currentTimeMillis()))
    }
    private fun rows(): JSONArray = JSONArray(prefs.getString("preferences", "[]").orEmpty())
    private fun protectedReason(reason: String, parameters: JSONObject?): String {
        var safe = reason.take(800)
        fun redact(value: Any?, sensitive: Boolean = false) {
            when (value) {
                is JSONObject -> value.keys().forEach { key -> redact(value.opt(key), sensitive ||
                    Regex("password|token|secret|api.?key|authorization|credential", RegexOption.IGNORE_CASE).containsMatchIn(key)) }
                is JSONArray -> (0 until value.length()).forEach { redact(value.opt(it), sensitive) }
                else -> if (sensitive && value != null && value != JSONObject.NULL && value.toString().length >= 3) {
                    safe = safe.replace(value.toString(), "[已保护]")
                }
            }
        }
        redact(parameters)
        return safe
    }
    private fun decode(j: JSONObject) = ApprovalPreference(j.getString("key"), j.getString("tool"), j.getString("project"),
        j.getString("effect"), j.optInt("approvals"), j.optInt("refusals"), j.optLong("expires_at"),
        j.optBoolean("revoked"), j.optLong("last_evidence_at"), j.optString("target"), j.optBoolean("restored_pending"))
    private fun appendAuditLocked(value: JSONObject) {
        val previous = runCatching { JSONArray(prefs.getString("audit", "[]").orEmpty()) }.getOrDefault(JSONArray())
        val keep = JSONArray()
        for (i in maxOf(0, previous.length() - 499) until previous.length()) keep.put(previous.get(i))
        keep.put(value)
        commit(prefs.edit().putString("audit", keep.toString()))
    }
    private fun commit(editor: android.content.SharedPreferences.Editor) {
        check(editor.commit()) { "APPROVAL_STORAGE_UNAVAILABLE" }
        revision.value += 1L
    }
    companion object {
        private val lock = Any()
        private val revision = MutableStateFlow(0L)
        val changes = revision.asStateFlow()
    }
}
