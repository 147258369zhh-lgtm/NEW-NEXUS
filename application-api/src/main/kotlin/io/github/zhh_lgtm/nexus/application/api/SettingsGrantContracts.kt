package io.github.zhh_lgtm.nexus.application.api

/**
 * Identity of one non-user caller that may change settings.
 *
 * A blank [agent] acts as a wildcard for the actor; a null [runId] binds the grant to the agent
 * rather than to one run. Both are persisted verbatim so a revocation can name the same subject.
 */
data class SettingsGrantSubject(
    val actor: SettingsActorKind,
    val agent: String = "",
    val runId: String? = null,
) {
    fun covers(caller: SettingsGrantSubject): Boolean {
        if (actor != caller.actor) return false
        if (agent.isNotBlank() && !agent.equals(caller.agent, ignoreCase = true)) return false
        if (runId != null && runId != caller.runId) return false
        return true
    }
}

/** Immutable view of one issued settings grant; the store owns mutation and versioning. */
data class SettingsGrantSnapshot(
    val id: String,
    val version: Long,
    val subject: SettingsGrantSubject,
    val scope: List<String>,
    val issuedAt: Long,
    val validUntil: Long,
    val singleUse: Boolean = false,
    val revokedAt: Long? = null,
    val consumedAt: Long? = null,
)

/** The grant that authorised a decision, or null when no grant was needed. */
data class SettingsGrantEvaluation(
    val decision: SettingsGrantDecision,
    val grant: SettingsGrantSnapshot? = null,
)

sealed interface SettingsGrantDecision {
    data object Allowed : SettingsGrantDecision
    data class Denied(val code: String, val reason: String) : SettingsGrantDecision
}

/**
 * Pure grant evaluation. Persistence, epoch bumps and consumption live in the app module, so this
 * logic stays testable without Android and is shared by the execution gate and the audit view.
 *
 * A subject with no recorded grant keeps the legacy default authority; once any grant record exists
 * for the subject, only a live, in-scope, current-version, unexpired grant authorises a change.
 */
object SettingsGrantPolicy {
    const val MAX_SCOPE_PATTERNS = 64
    const val MAX_SCOPE_PATTERN_CHARS = 120

    fun scopeCovers(patterns: List<String>, settingId: String): Boolean = patterns.any { pattern ->
        when {
            pattern == "*" -> true
            pattern.endsWith("*") -> settingId.startsWith(pattern.dropLast(1), ignoreCase = true)
            else -> pattern.equals(settingId, ignoreCase = true)
        }
    }

    fun invalidScopeReason(patterns: List<String>): String? {
        if (patterns.isEmpty()) return "授权范围不能为空"
        if (patterns.size > MAX_SCOPE_PATTERNS) return "授权范围最多 $MAX_SCOPE_PATTERNS 项"
        patterns.forEach { pattern ->
            when {
                pattern.isBlank() -> return "授权范围包含空项"
                pattern.length > MAX_SCOPE_PATTERN_CHARS -> return "授权范围单项过长"
                pattern.count { it == '*' } > 1 -> return "授权范围每项最多一个通配符"
                '*' in pattern && !pattern.endsWith("*") -> return "通配符只能位于末尾"
                pattern.removeSuffix("*").isBlank() && pattern != "*" -> return "授权范围格式无效"
            }
        }
        return null
    }

    /** Grants recorded for this subject, live or revoked; empty means the subject is ungoverned. */
    fun governing(grants: List<SettingsGrantSnapshot>, caller: SettingsGrantSubject): List<SettingsGrantSnapshot> =
        grants.filter { it.subject.covers(caller) }

    fun evaluate(
        grants: List<SettingsGrantSnapshot>,
        caller: SettingsGrantSubject,
        settingId: String,
        currentVersion: Long,
        now: Long,
    ): SettingsGrantEvaluation {
        val governing = governing(grants, caller)
        if (governing.isEmpty()) return SettingsGrantEvaluation(SettingsGrantDecision.Allowed)

        val inScope = governing.filter { scopeCovers(it.scope, settingId) }
        if (inScope.isEmpty()) return denied("SETTINGS_GRANT_SCOPE_DENIED", "该授权不包含 $settingId")

        val unrevoked = inScope.filter { it.revokedAt == null }
        if (unrevoked.isEmpty()) return denied("SETTINGS_GRANT_REVOKED", "该设置授权已被撤销，请重新申请")

        val unconsumed = unrevoked.filter { it.consumedAt == null }
        if (unconsumed.isEmpty()) return denied("SETTINGS_GRANT_CONSUMED", "该一次性解锁已使用")

        val live = unconsumed.filter { now < it.validUntil }
        if (live.isEmpty()) return denied("SETTINGS_GRANT_EXPIRED", "该设置授权已过期，请重新申请")

        val current = live.filter { it.version == currentVersion }
        if (current.isEmpty()) return denied("SETTINGS_GRANT_STALE_VERSION", "该设置授权已被更新的撤权版本作废")

        // Prefer the narrowest scope, then the most recently issued grant; the id keeps the
        // choice stable when two grants tie on both.
        val chosen = current.maxWith(
            compareBy<SettingsGrantSnapshot> { specificity(it.scope, settingId) }
                .thenBy { it.issuedAt }
                .thenBy { it.id },
        )
        return SettingsGrantEvaluation(SettingsGrantDecision.Allowed, chosen)
    }

    private fun specificity(scope: List<String>, settingId: String): Int = scope.maxOfOrNull { pattern ->
        when {
            pattern == "*" -> 0
            pattern.endsWith("*") && settingId.startsWith(pattern.dropLast(1), ignoreCase = true) -> 1
            else -> 2
        }
    } ?: 0

    private fun denied(code: String, reason: String) =
        SettingsGrantEvaluation(SettingsGrantDecision.Denied(code, reason))
}
