package io.github.mangi.eta.agent.control

import android.content.Context
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import io.github.zhh_lgtm.nexus.application.api.SettingsGrantDecision
import io.github.zhh_lgtm.nexus.application.api.SettingsGrantSubject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsGrantStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var now = 1_000L
    private val subject = SettingsGrantSubject(SettingsActorKind.REMOTE_AGENT, "peer-a")
    private fun store() = SettingsGrantStore(context) { now }

    @Before fun clearStorage() {
        context.getSharedPreferences("nexus_settings_grants", Context.MODE_PRIVATE).edit().clear().commit()
        now = 1_000L
    }

    private fun deniedCode() = (store().authorize(subject, "settings.learning").decision
        as SettingsGrantDecision.Denied).code

    @Test fun remoteSubjectWithoutGrantStaysRequestOnlyButLocalAgentDoesNot() {
        assertEquals("REMOTE_SETTINGS_REQUEST_ONLY", deniedCode())
        val local = SettingsGrantSubject(SettingsActorKind.LOCAL_AGENT, "agent")
        assertEquals(SettingsGrantDecision.Allowed, store().authorize(local, "settings.learning").decision)
    }

    @Test fun issuedGrantSurvivesANewInstanceUntilItsExpiry() {
        val issued = store().issue(subject, listOf("settings.learning"), validUntil = 5_000)
        assertEquals(SettingsGrantDecision.Allowed, store().authorize(subject, "settings.learning").decision)
        assertEquals(issued.id, store().authorize(subject, "settings.learning").grant?.id)

        now = 5_000
        assertEquals("SETTINGS_GRANT_EXPIRED", deniedCode())
    }

    @Test fun singleUseGrantIsSpentOnlyOnceAcrossRestarts() {
        val issued = store().issue(subject, listOf("settings.learning"), validUntil = 9_000, singleUse = true)
        assertTrue(store().consume(issued.id))
        assertFalse(store().consume(issued.id))
        assertEquals("SETTINGS_GRANT_CONSUMED", deniedCode())
    }

    @Test fun revokingOneGrantAdvancesTheSubjectVersionAndInvalidatesItsSiblings() {
        val first = store().issue(subject, listOf("settings.learning"), validUntil = 9_000)
        val second = store().issue(subject, listOf("settings.learning"), validUntil = 9_000)
        assertEquals(first.version, second.version)

        assertTrue(store().revoke(first.id))
        assertEquals(1L, store().currentVersionFor(subject))
        assertEquals("SETTINGS_GRANT_REVOKED", deniedCode())
        assertEquals(2, store().list().count { it.revokedAt != null })

        // A grant issued after the revocation is current again.
        store().issue(subject, listOf("settings.learning"), validUntil = 9_000)
        assertEquals(SettingsGrantDecision.Allowed, store().authorize(subject, "settings.learning").decision)
    }

    @Test fun revokingTheSubjectClearsEveryScopeAndKeepsRowsReadable() {
        store().issue(subject, listOf("settings.learning"), validUntil = 9_000)
        store().issue(subject, listOf("settings.*"), validUntil = 9_000)
        assertEquals(2, store().revokeSubject(subject))
        assertEquals("SETTINGS_GRANT_REVOKED", deniedCode())
        assertEquals(2, store().list().size)
    }

    @Test fun grantScopeIsEnforcedAndValidated() {
        store().issue(subject, listOf("settings.learning"), validUntil = 9_000)
        assertEquals("SETTINGS_GRANT_SCOPE_DENIED",
            (store().authorize(subject, "settings.model_roles").decision as SettingsGrantDecision.Denied).code)
        assertTrue(runCatching { store().issue(subject, emptyList(), validUntil = 9_000) }.isFailure)
        assertTrue(runCatching { store().issue(subject, listOf("settings.learning"), validUntil = now) }.isFailure)
    }

    @Test fun planeGatesRemoteWritesAndOnlyLocalUserIssuesGrants() = runBlocking {
        val plane = NexusSettingsControlPlane.get(context)
        val remoteFailure = runCatching {
            plane.applyJson("settings.learning", org.json.JSONObject().put("enabled", false),
                SettingsActorKind.REMOTE_AGENT, "peer-a")
        }.exceptionOrNull()
        assertEquals("REMOTE_SETTINGS_REQUEST_ONLY", (remoteFailure as EtaControlException).code)

        val issued = plane.issueGrant(subject, listOf("settings.learning"),
            validUntil = System.currentTimeMillis() + 60_000, singleUse = true)
        assertEquals(issued.id, plane.listGrants().single { it.id == issued.id }.id)
        assertEquals(SettingsGrantDecision.Allowed,
            plane.grantDecision("settings.learning", SettingsActorKind.REMOTE_AGENT, "peer-a").decision)

        assertTrue(runCatching { plane.revokeGrantsFor(subject, SettingsActorKind.REMOTE_AGENT) }.isFailure)
        assertTrue(plane.revokeGrant(issued.id))
        assertEquals("SETTINGS_GRANT_REVOKED",
            (plane.grantDecision("settings.learning", SettingsActorKind.REMOTE_AGENT, "peer-a").decision
                as SettingsGrantDecision.Denied).code)
        assertNull(plane.listGrants().firstOrNull { it.id == "missing" })
    }

    @Test fun remoteWriteExecutesOnlyThroughALiveGrantAndSpendsAOneShotUnlock() = runBlocking {
        val plane = NexusSettingsControlPlane.get(context)
        val parameters = org.json.JSONObject().put("enabled", false)
        assertTrue(runCatching {
            plane.applyJson("settings.learning", parameters, SettingsActorKind.REMOTE_AGENT, "peer-a")
        }.isFailure)

        val issued = plane.issueGrant(subject, listOf("settings.learning"),
            validUntil = System.currentTimeMillis() + 60_000, singleUse = true)
        val applied = plane.applyJson("settings.learning", parameters, SettingsActorKind.REMOTE_AGENT, "peer-a")
        assertTrue(applied.getBoolean("ok"))
        assertEquals(false, applied.getJSONObject("after").getBoolean("enabled"))
        assertFalse(plane.listGrants().single { it.id == issued.id }.consumedAt == null)
        assertEquals(issued.id, plane.audit()
            .first { it.has("settingsGrantId") && !it.isNull("settingsGrantId") }
            .getString("settingsGrantId"))

        assertEquals("SETTINGS_GRANT_CONSUMED", (runCatching {
            plane.applyJson("settings.learning", parameters, SettingsActorKind.REMOTE_AGENT, "peer-a")
        }.exceptionOrNull() as EtaControlException).code)
        io.github.mangi.eta.fork.learning.LearningSettingsStore.setEnabled(context, true)
    }
}
