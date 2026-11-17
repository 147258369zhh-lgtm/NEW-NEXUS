package io.github.mangi.eta.agent.control

import android.app.Application
import io.github.mangi.eta.agent.runtime.AgentRuntimePolicy
import io.github.mangi.eta.config.Prefs
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RuntimePreferenceControlTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val plane get() = NexusSettingsControlPlane.get(context)
    @Before fun setup() { Prefs.initLocal(context); Prefs.localAgentPreferences()!!.edit().clear().commit() }

    @Test fun modelPageAndDeviceSwitchesUseTheActualRuntimePreferencesAndRecordTheLocalUser() = runBlocking {
        plane.setRuntimePreferenceFromUi(Prefs.Keys.AGENT_THINKING_ENABLED, false)
        plane.setRuntimePreferenceFromUi(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED, false)
        plane.setRuntimePreferenceFromUi(Prefs.Keys.AGENT_TERMINAL_TOOLS, false)
        val actual = AgentRuntimePolicy.permissions(Prefs.localAgentPreferences())
        assertFalse(actual.thinking); assertFalse(actual.autoCompaction); assertFalse(actual.terminalTools)
        assertNull(EtaAppControlConfirmation.pending.value)
        val audit = plane.audit().filter { it.optString("operation") == "execute" }
        assertEquals(3, audit.size)
        assertTrue(audit.all { it.getString("actor") == SettingsActorKind.LOCAL_USER.name && it.getBoolean("rollbackSafe") })
        val thinking = audit.single { it.getString("capabilityId") == RuntimePreferenceControlProvider.id(Prefs.Keys.AGENT_THINKING_ENABLED) }
        plane.rollbackJson(thinking.getString("id"), SettingsActorKind.LOCAL_USER)
        assertTrue(AgentRuntimePolicy.permissions(Prefs.localAgentPreferences()).thinking)
    }

    @Test fun agentCannotExpandToolAccessWithoutTheRealLocalApprovalAndRejectionKeepsItDisabled() = runBlocking {
        plane.setRuntimePreferenceFromUi(Prefs.Keys.AGENT_TERMINAL_TOOLS, false)
        val id = RuntimePreferenceControlProvider.id(Prefs.Keys.AGENT_TERMINAL_TOOLS)
        suspend fun change(approve: Boolean) {
            val pending = async(Dispatchers.IO) {
                runCatching { plane.applyJson(id, JSONObject().put("enabled", true), SettingsActorKind.LOCAL_AGENT, sourceAgent = "fixture-agent") }
            }
            try {
                val approval = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                assertEquals(id, approval.capabilityId)
                assertFalse(AgentRuntimePolicy.permissions(Prefs.localAgentPreferences()).terminalTools)
                if (approve) EtaAppControlConfirmation.approve(approval.id) else EtaAppControlConfirmation.cancel(approval.id)
                assertEquals(approve, withTimeout(3_000) { pending.await() }.isSuccess)
            } finally { pending.cancelAndJoin() }
        }
        change(false)
        assertFalse(AgentRuntimePolicy.permissions(Prefs.localAgentPreferences()).terminalTools)
        change(true)
        assertTrue(AgentRuntimePolicy.permissions(Prefs.localAgentPreferences()).terminalTools)
        assertTrue(plane.audit().any { it.optString("actor") == SettingsActorKind.LOCAL_AGENT.name })
    }

    @Test fun remoteWritesAndArbitraryPreferenceKeysAreRejected() = runBlocking {
        assertTrue(runCatching { plane.setRuntimePreferenceFromUi(Prefs.Keys.POWER_KEY_TAKEOVER, true) }.isFailure)
        assertTrue(runCatching { plane.applyJson(RuntimePreferenceControlProvider.id(Prefs.Keys.AGENT_BROWSER_TOOLS),
            JSONObject().put("enabled", false), SettingsActorKind.REMOTE_AGENT) }.isFailure)
        assertTrue(AgentRuntimePolicy.permissions(Prefs.localAgentPreferences()).browserTools)
        assertNull(EtaAppControlConfirmation.pending.value)
        Unit
    }
}
