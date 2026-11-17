package io.github.mangi.eta.agent.control

import android.content.Context
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import io.github.zhh_lgtm.nexus.application.api.SettingsGrantDecision
import io.github.zhh_lgtm.nexus.application.api.SettingsGrantEvaluation
import io.github.zhh_lgtm.nexus.application.api.SettingsGrantPolicy
import io.github.zhh_lgtm.nexus.application.api.SettingsGrantSnapshot
import io.github.zhh_lgtm.nexus.application.api.SettingsGrantSubject
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable settings grants: which non-user subject may change which setting ids, until when.
 *
 * Every subject carries its own version. Issuing a grant stores the subject's current version;
 * revoking the subject or any single grant bumps that version, so older grants for the same subject
 * stop authorising anything even though their rows stay readable. Single-use grants record their
 * consumption, so a restart cannot replay them.
 */
internal class SettingsGrantStore(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<SettingsGrantSnapshot> = synchronized(lock) { readGrants() }

    fun listFor(subject: SettingsGrantSubject): List<SettingsGrantSnapshot> = synchronized(lock) {
        SettingsGrantPolicy.governing(readGrants(), subject)
    }

    fun currentVersionFor(subject: SettingsGrantSubject): Long = synchronized(lock) {
        governingVersion(readVersions(), subject)
    }

    fun issue(
        subject: SettingsGrantSubject,
        scope: List<String>,
        validUntil: Long,
        singleUse: Boolean = false,
    ): SettingsGrantSnapshot = synchronized(lock) {
        val normalized = scope.map(String::trim).filter(String::isNotBlank)
        SettingsGrantPolicy.invalidScopeReason(normalized)?.let { reason ->
            throw EtaControlException("INVALID_GRANT_SCOPE", reason)
        }
        val now = clock()
        require(validUntil > now) { "授权期限必须晚于当前时间" }
        val versions = readVersions()
        // The issued version must match what evaluate() computes, otherwise a new grant would look
        // stale the moment a broader wildcard grant for the same subject was revoked.
        val version = governingVersion(versions, subject)
        val grants = readGrants()
        val snapshot = SettingsGrantSnapshot(
            id = UUID.randomUUID().toString(),
            version = version,
            subject = subject,
            scope = normalized,
            issuedAt = now,
            validUntil = validUntil,
            singleUse = singleUse,
        )
        write(grants + snapshot, versions)
        snapshot
    }

    /** Revokes one grant and bumps its subject version so every sibling grant goes stale too. */
    fun revoke(grantId: String): Boolean = synchronized(lock) {
        val grants = readGrants()
        val target = grants.firstOrNull { it.id == grantId && it.revokedAt == null } ?: return false
        val now = clock()
        val versions = readVersions().also { bump(it, target.subject) }
        // The version bump already invalidates siblings; marking them revoked keeps the stored
        // rows readable and shows that no grant for this subject is still live.
        write(
            grants.map { grant ->
                if (grant.subject.actor == target.subject.actor &&
                    (grant.subject.covers(target.subject) || target.subject.covers(grant.subject))
                ) {
                    grant.copy(revokedAt = grant.revokedAt ?: now)
                } else grant
            },
            versions,
        )
        true
    }

    /** Revokes every live grant covering [subject] and advances its version. */
    fun revokeSubject(subject: SettingsGrantSubject): Int = synchronized(lock) {
        val grants = readGrants()
        val now = clock()
        var revoked = 0
        val versions = readVersions().also { bump(it, subject) }
        write(
            grants.map { grant ->
                if (grant.revokedAt == null && grant.subject.covers(subject)) {
                    revoked++
                    grant.copy(revokedAt = now)
                } else grant
            },
            versions,
        )
        revoked
    }

    /**
     * Decides whether [subject] may change [settingId] right now. Read-only: consumption of a
     * single-use grant happens atomically in [consume] before the executor is entered.
     */
    fun authorize(subject: SettingsGrantSubject, settingId: String): SettingsGrantEvaluation {
        val state = synchronized(lock) { readGrants() to governingVersion(readVersions(), subject) }
        val evaluation = SettingsGrantPolicy.evaluate(state.first, subject, settingId, state.second, clock())
        // Remote agents stay request-only until a live grant under an explicit scope exists.
        if (subject.actor == SettingsActorKind.REMOTE_AGENT &&
            evaluation.decision == SettingsGrantDecision.Allowed && evaluation.grant == null
        ) {
            return SettingsGrantEvaluation(
                SettingsGrantDecision.Denied("REMOTE_SETTINGS_REQUEST_ONLY", "远端 Agent 只能提出设置修改请求"),
            )
        }
        return evaluation
    }

    fun consume(grantId: String): Boolean = synchronized(lock) {
        val grants = readGrants()
        val target = grants.firstOrNull { it.id == grantId && it.singleUse } ?: return false
        if (target.consumedAt != null || target.revokedAt != null || clock() >= target.validUntil ||
            target.version != governingVersion(readVersions(), target.subject)) return false
        write(grants.map { grant ->
            if (grant.id == grantId) grant.copy(consumedAt = clock()) else grant
        }, readVersions())
        true
    }

    /** A migration carries evidence and constraints, never a live settings execution permit. */
    fun invalidateAfterBackupRestore() = synchronized(lock) {
        val grants = readGrants()
        val now = clock()
        val versions = readVersions()
        grants.map { it.subject }.distinct().forEach { bump(versions, it) }
        write(grants.map { it.copy(revokedAt = it.revokedAt ?: now) }, versions)
    }

    /**
     * Effective revocation version for one caller: the higher of its own key and the actor-wide
     * key it inherits from. Missing keys read as 0, so a subject that never had a grant stays
     * ungoverned and keeps the legacy authority.
     */
    private fun governingVersion(versions: JSONObject, subject: SettingsGrantSubject): Long {
        val exact = subjectKey(subject)
        val wildcard = subjectKey(subject.copy(agent = "", runId = null))
        return maxOf(versions.optLong(exact, 0L), versions.optLong(wildcard, 0L))
    }

    /** Advances the subject's own key, and the inherited actor-wide key when one exists. */
    private fun bump(versions: JSONObject, subject: SettingsGrantSubject) {
        val exact = subjectKey(subject)
        versions.put(exact, versions.optLong(exact, 0L) + 1)
        val wildcard = subjectKey(subject.copy(agent = "", runId = null))
        if (wildcard != exact && versions.has(wildcard)) {
            versions.put(wildcard, versions.optLong(wildcard, 0L) + 1)
        }
    }

    // Unreadable grant storage reads as "no grants". That only ever removes extra authority:
    // remote callers stay request-only and a revoked grant can never be resurrected.
    private fun readGrants(): List<SettingsGrantSnapshot> = runCatching {
        val raw = preferences.getString(GRANTS, "[]").orEmpty()
        val array = JSONArray(raw.ifBlank { "[]" })
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(decodeGrant(it)) }
            }
        }
    }.getOrElse { emptyList() }

    private fun readVersions(): JSONObject = runCatching {
        JSONObject(preferences.getString(VERSIONS, "{}").orEmpty().ifBlank { "{}" })
    }.getOrElse { JSONObject() }

    private fun write(grants: List<SettingsGrantSnapshot>, versions: JSONObject) {
        val stored = JSONArray().also { array -> grants.forEach { array.put(encodeGrant(it)) } }
        check(preferences.edit().putString(GRANTS, stored.toString()).putString(VERSIONS, versions.toString()).commit()) {
            "设置授权无法写入"
        }
    }

    private fun encodeGrant(grant: SettingsGrantSnapshot) = JSONObject()
        .put("id", grant.id)
        .put("version", grant.version)
        .put("subject", encodeSubject(grant.subject))
        .put("scope", JSONArray(grant.scope))
        .put("issuedAt", grant.issuedAt)
        .put("validUntil", grant.validUntil)
        .put("singleUse", grant.singleUse)
        .put("revokedAt", grant.revokedAt ?: JSONObject.NULL)
        .put("consumedAt", grant.consumedAt ?: JSONObject.NULL)

    private fun decodeGrant(source: JSONObject) = SettingsGrantSnapshot(
        id = source.getString("id"),
        version = source.optLong("version", 0L),
        subject = decodeSubject(source.getJSONObject("subject")),
        scope = source.optJSONArray("scope")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty(),
        issuedAt = source.optLong("issuedAt", 0L),
        validUntil = source.optLong("validUntil", 0L),
        singleUse = source.optBoolean("singleUse", false),
        revokedAt = source.optLongOrNull("revokedAt"),
        consumedAt = source.optLongOrNull("consumedAt"),
    )

    private fun encodeSubject(subject: SettingsGrantSubject) = JSONObject()
        .put("actor", subject.actor.name)
        .put("agent", subject.agent)
        .put("runId", subject.runId ?: JSONObject.NULL)

    private fun decodeSubject(source: JSONObject) = SettingsGrantSubject(
        actor = SettingsActorKind.entries.firstOrNull { it.name == source.optString("actor") }
            ?: SettingsActorKind.REMOTE_AGENT,
        agent = source.optString("agent"),
        // org.json renders an explicit null as the string "null", so test presence directly.
        runId = if (source.has("runId") && !source.isNull("runId")) {
            source.optString("runId").takeIf(String::isNotBlank)
        } else null,
    )

    /** Stable, JSON-safe identity key; stored as one versions-object key per subject. */
    private fun subjectKey(subject: SettingsGrantSubject) = JSONObject()
        .put("actor", subject.actor.name)
        .put("agent", subject.agent.lowercase())
        .put("runId", subject.runId ?: JSONObject.NULL)
        .toString()

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key).takeIf { it > 0 } else null

    companion object {
        private val lock = Any()
        private const val PREFS = "nexus_settings_grants"
        private const val GRANTS = "grants"
        private const val VERSIONS = "subject_versions"
    }
}
