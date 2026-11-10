package io.github.mangi.eta.agent.accessibility

import android.app.Application
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootAccessState
import io.github.mangi.eta.agent.device.RootAccessStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36, 37], application = Application::class)
class RootAccessibilityRecoveryTest {
    @Test
    fun recoveryIsOptInAndMissingRootNeverStartsTheHelper() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("eta_root_accessibility_recovery", 0)
        prefs.edit().clear().commit()
        assertFalse(RootAccessibilityRecovery.isEnabled(context))
        assertFalse(RootAccessibilityRecovery.canRecover(context))
        val disabled = RootAccessibilityRecovery.recoverBlocking(context)
        assertEquals("ACCESSIBILITY_UNAVAILABLE", disabled.code)
        assertFalse(disabled.recoveryRequested)
        prefs.edit().putBoolean("enabled", true).commit()
        val noRoot = RootAccessibilityRecovery.recoverBlocking(context)
        assertEquals("ROOT_REQUIRED", noRoot.code)
        assertFalse(noRoot.recoveryRequested)
        assertFalse(prefs.getBoolean("pending_rebind", false))
    }

    @Test
    fun mainThreadCannotRunTheBlockingRecoveryPath() {
        val result = RootAccessibilityRecovery.recoverBlocking(RuntimeEnvironment.getApplication())
        assertEquals("ACCESSIBILITY_RECOVERY_BACKGROUND_REQUIRED", result.code)
        assertFalse(result.recoveryRequested)
    }

    @Test
    fun interruptedRepairIsNotReplayedEvenWhenRootIsGranted() = withGrantedRoot {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("eta_root_accessibility_recovery", 0)
        prefs.edit().putBoolean("enabled", true).putBoolean("pending_rebind", true).commit()
        assertFalse(RootAccessibilityRecovery.canRecover(context))
        val result = RootAccessibilityRecovery.recoverBlocking(context)
        assertEquals("ACCESSIBILITY_REPAIR_UNCONFIRMED", result.code)
        assertFalse(result.recoveryRequested)
        assertTrue(prefs.getBoolean("pending_rebind", false))
    }

    @Test
    fun rootGrantDoesNotAuthorizeEnablingASystemServiceThatTheUserDisabled() = withGrantedRoot {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("eta_root_accessibility_recovery", 0).edit()
            .putBoolean("enabled", true).remove("pending_rebind").commit()
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        assertFalse(RootAccessibilityRecovery.canRecover(context))
        val result = RootAccessibilityRecovery.recoverBlocking(context)
        assertEquals("ACCESSIBILITY_UNAVAILABLE", result.code)
        assertFalse(result.recoveryRequested)
        assertEquals(0, Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED))
    }

    @Test
    fun helperRequiresRootIdentityAndAValidActiveLease() {
        val provider = provider()
        val extras = Bundle().apply { putInt("version", 1); putString("phase", "begin") }
        for (uid in listOf(Process.myUid(), Process.SYSTEM_UID, Process.ROOT_UID)) {
            ShadowBinder.setCallingUid(uid)
            try {
                assertTrue(provider.call(AccessibilityRecoveryCommandMain.CONTROL_METHOD, "not-issued", extras).isEmpty)
            } finally {
                ShadowBinder.setCallingUid(Process.myUid())
            }
        }
    }

    @Test
    fun protocolMismatchAndUnknownPhaseAreRejectedBeforeTouchingSettings() {
        ShadowBinder.setCallingUid(Process.ROOT_UID)
        try {
            val provider = provider()
            assertTrue(provider.call(AccessibilityRecoveryCommandMain.CONTROL_METHOD, "token", Bundle().apply {
                putInt("version", 999); putString("phase", "begin")
            }).isEmpty)
            assertTrue(provider.call(AccessibilityRecoveryCommandMain.CONTROL_METHOD, "token", Bundle().apply {
                putInt("version", 1); putString("phase", "write_arbitrary_setting")
            }).isEmpty)
        } finally {
            ShadowBinder.setCallingUid(Process.myUid())
        }
    }

    @Test
    fun activeLeaseAllowsRestoringTheCurrentTransactionAfterRecoveryIsTurnedOff() = withGrantedRoot {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("eta_root_accessibility_recovery", 0)
        prefs.edit().clear().putBoolean("enabled", true).commit()
        val token = "8af41619-bf69-4f4d-875c-e5645c46be31"
        ReflectionHelpers.setStaticField(RootAccessibilityRecovery::class.java, "lease",
            AccessibilityRecoveryLease(token, SystemClock.elapsedRealtime()))
        ShadowBinder.setCallingUid(Process.ROOT_UID)
        try {
            val provider = provider()
            fun call(phase: String) = provider.call(AccessibilityRecoveryCommandMain.CONTROL_METHOD, token,
                Bundle().apply { putInt("version", 1); putString("phase", phase) })
            assertTrue(call("begin").getBoolean("allowed"))
            assertTrue(prefs.getBoolean("pending_rebind", false))
            prefs.edit().putBoolean("enabled", false).commit()
            assertFalse(call("check").getBoolean("allowed"))
            assertTrue(call("restore").getBoolean("allowed"))
            assertTrue(call("complete").getBoolean("completed"))
            assertFalse(prefs.getBoolean("pending_rebind", true))
            assertFalse(call("restore").getBoolean("allowed"))
        } finally {
            ShadowBinder.setCallingUid(Process.myUid())
            ReflectionHelpers.setStaticField(RootAccessibilityRecovery::class.java, "lease", null)
        }
    }

    @Test
    fun malformedPartialOrDuplicateHelperResultsNeverClaimARebind() {
        val marker = AccessibilityRecoveryCommandMain.MARKER
        val result = BoundedRootCommandExecutor.Result(0, marker + "{\"version\":1,\"outcome\":\"REQUESTED\"}", "", false, false)
        assertEquals(AccessibilityRebindOutcome.REQUESTED, RootAccessibilityRecovery.parseOutcome(result))
        assertNull(RootAccessibilityRecovery.parseOutcome(result.copy(truncated = true)))
        assertNull(RootAccessibilityRecovery.parseOutcome(result.copy(timedOut = true)))
        assertNull(RootAccessibilityRecovery.parseOutcome(result.copy(stdout = result.stdout + "\n" + result.stdout)))
        assertNull(RootAccessibilityRecovery.parseOutcome(result.copy(stdout = marker + "{\"version\":2,\"outcome\":\"REQUESTED\"}")))
        assertNull(RootAccessibilityRecovery.parseOutcome(result.copy(stdout = marker + "{}")))
    }

    @Test
    fun vendorRiskExemptionFailureForcesManualRecoveryConfirmation() {
        val marker = AccessibilityRecoveryCommandMain.MARKER
        val result = BoundedRootCommandExecutor.Result(
            0,
            marker + "{\"version\":1,\"outcome\":\"REQUESTED\",\"exemption\":\"FAILED\"}",
            "",
            false,
            false,
        )
        assertEquals(AccessibilityRebindOutcome.MANUAL_REQUIRED, RootAccessibilityRecovery.parseOutcome(result))
    }

    @Test
    fun exemptionHelperResultMustBeExplicit() {
        val marker = AccessibilityRecoveryCommandMain.MARKER
        val result = BoundedRootCommandExecutor.Result(
            0,
            marker + "{\"version\":1,\"outcome\":\"REQUESTED\",\"exemption\":\"ALREADY_EXEMPT\"}",
            "",
            false,
            false,
        )
        assertEquals(
            VendorRiskCloseExemptionOutcome.ALREADY_EXEMPT,
            RootAccessibilityRecovery.parseRiskExemptionOutcome(result),
        )
        assertNull(RootAccessibilityRecovery.parseRiskExemptionOutcome(result.copy(truncated = true)))
        assertNull(
            RootAccessibilityRecovery.parseRiskExemptionOutcome(
                result.copy(stdout = marker + "{\"version\":1,\"outcome\":\"REQUESTED\"}"),
            ),
        )
    }

    private fun provider() = AgentAccessibilityHealthProvider().apply {
        attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply {
            authority = AccessibilityProtectionProtocol.HEALTH_AUTHORITY
        })
    }

    private fun withGrantedRoot(block: () -> Unit) = runBlocking(Dispatchers.IO) {
        val state = ReflectionHelpers.getStaticField<MutableStateFlow<RootAccessState>>(RootAccess::class.java, "mutableState")
        val previous = state.value
        state.value = RootAccessState(RootAccessStatus.GRANTED, suPresent = true)
        try { block() } finally {
            state.value = previous
            RuntimeEnvironment.getApplication().getSharedPreferences("eta_root_accessibility_recovery", 0).edit().clear().commit()
        }
    }
}
