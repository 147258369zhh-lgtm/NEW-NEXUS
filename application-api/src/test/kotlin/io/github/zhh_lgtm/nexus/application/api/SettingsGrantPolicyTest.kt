package io.github.zhh_lgtm.nexus.application.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGrantPolicyTest {
    private val remote = SettingsGrantSubject(SettingsActorKind.REMOTE_AGENT, "peer-a", "run-1")

    private fun grant(
        id: String = "g1",
        subject: SettingsGrantSubject = remote,
        scope: List<String> = listOf("settings.learning"),
        version: Long = 1,
        validUntil: Long = 2_000,
        singleUse: Boolean = false,
        revokedAt: Long? = null,
        consumedAt: Long? = null,
    ) = SettingsGrantSnapshot(id, version, subject, scope, 1_000, validUntil, singleUse, revokedAt, consumedAt)

    private fun code(result: SettingsGrantEvaluation): String =
        (result.decision as SettingsGrantDecision.Denied).code

    @Test
    fun ungovernedSubjectKeepsLegacyAuthority() {
        val result = SettingsGrantPolicy.evaluate(emptyList(), remote, "settings.learning", 0, 1_500)
        assertEquals(SettingsGrantDecision.Allowed, result.decision)
        assertNull(result.grant)
    }

    @Test
    fun liveGrantInScopeAllowsAndIsReported() {
        val result = SettingsGrantPolicy.evaluate(listOf(grant()), remote, "settings.learning", 1, 1_500)
        assertEquals(SettingsGrantDecision.Allowed, result.decision)
        assertEquals("g1", result.grant?.id)
    }

    @Test
    fun outOfScopeIsDenied() {
        assertEquals("SETTINGS_GRANT_SCOPE_DENIED",
            code(SettingsGrantPolicy.evaluate(listOf(grant()), remote, "settings.model_roles", 1, 1_500)))
    }

    @Test
    fun expiredRevokedConsumedAndStaleVersionAreDenied() {
        assertEquals("SETTINGS_GRANT_EXPIRED",
            code(SettingsGrantPolicy.evaluate(listOf(grant(validUntil = 1_500)), remote, "settings.learning", 1, 1_500)))
        assertEquals("SETTINGS_GRANT_REVOKED",
            code(SettingsGrantPolicy.evaluate(listOf(grant(revokedAt = 1_200)), remote, "settings.learning", 1, 1_500)))
        assertEquals("SETTINGS_GRANT_CONSUMED",
            code(SettingsGrantPolicy.evaluate(listOf(grant(consumedAt = 1_200, singleUse = true)),
                remote, "settings.learning", 1, 1_500)))
        assertEquals("SETTINGS_GRANT_STALE_VERSION",
            code(SettingsGrantPolicy.evaluate(listOf(grant(version = 1)), remote, "settings.learning", 2, 1_500)))
    }

    @Test
    fun subjectScopingSeparatesAgentsRunsAndActors() {
        val agentOnly = grant(subject = SettingsGrantSubject(SettingsActorKind.REMOTE_AGENT, "peer-a"))
        val runOnly = grant(subject = SettingsGrantSubject(SettingsActorKind.REMOTE_AGENT, "peer-a", "run-9"))
        val otherActor = grant(subject = SettingsGrantSubject(SettingsActorKind.LOCAL_AGENT, "peer-a", "run-1"))
        assertTrue(SettingsGrantPolicy.evaluate(listOf(agentOnly), remote, "settings.learning", 1, 1_500).decision is SettingsGrantDecision.Allowed)
        // Grants bound to another run, agent or actor do not govern this caller at all.
        assertNull(SettingsGrantPolicy.evaluate(listOf(runOnly), remote, "settings.learning", 1, 1_500).grant)
        assertNull(SettingsGrantPolicy.evaluate(listOf(otherActor), remote, "settings.learning", 1, 1_500).grant)
        assertNull(SettingsGrantPolicy.evaluate(
            listOf(grant(subject = SettingsGrantSubject(SettingsActorKind.REMOTE_AGENT, "peer-b"))),
            remote, "settings.learning", 1, 1_500,
        ).grant)
    }

    @Test
    fun wildcardAndPrefixScopesMatchTheirOwnIds() {
        assertTrue(SettingsGrantPolicy.scopeCovers(listOf("settings.*"), "settings.memory.enabled"))
        assertTrue(SettingsGrantPolicy.scopeCovers(listOf("*"), "anything.here"))
        assertTrue(SettingsGrantPolicy.scopeCovers(listOf("settings.learning"), "SETTINGS.LEARNING"))
        assertEquals(false, SettingsGrantPolicy.scopeCovers(listOf("settings.learning"), "settings.learning_extra"))
    }

    @Test
    fun scopeValidationRejectsEmptyAndMalformedPatterns() {
        assertEquals(null, SettingsGrantPolicy.invalidScopeReason(listOf("settings.learning", "settings.model_*")))
        assertTrue(SettingsGrantPolicy.invalidScopeReason(emptyList()) != null)
        assertTrue(SettingsGrantPolicy.invalidScopeReason(listOf(" ")) != null)
        assertTrue(SettingsGrantPolicy.invalidScopeReason(listOf("settings.*.x")) != null)
        assertTrue(SettingsGrantPolicy.invalidScopeReason(listOf("*settings")) != null)
    }

    @Test
    fun mostSpecificScopeWinsWhenSeveralGrantsAreLive() {
        val broad = grant(id = "broad", scope = listOf("settings.*"))
        val exact = grant(id = "exact", scope = listOf("settings.learning"))
        val result = SettingsGrantPolicy.evaluate(listOf(broad, exact), remote, "settings.learning", 1, 1_500)
        assertEquals("exact", result.grant?.id)
    }
}
