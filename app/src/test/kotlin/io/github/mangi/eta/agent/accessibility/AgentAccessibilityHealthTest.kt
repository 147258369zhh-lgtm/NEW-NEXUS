package io.github.mangi.eta.agent.accessibility

import android.app.Application
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContextWrapper
import android.provider.Settings
import io.github.mangi.eta.ui.app.accessibilityPermissionHealthItem
import io.github.mangi.eta.ui.model.PermissionStatusUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36, 37], application = Application::class)
class AgentAccessibilityHealthTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun enabledSwitchWithoutBindingIsAWarningNotAvailable() {
        setConfigured(true)
        assertEquals(AccessibilityConnectionStatus.DISCONNECTED, AgentAccessibilityHealth.snapshot(context).status)
        val item = accessibilityPermissionHealthItem(context)
        assertEquals(PermissionStatusUi.Warning, item.status)
        assertTrue(item.summary.isNotBlank())
    }

    @Test
    fun globalSwitchOffDoesNotTreatAStaleListEntryAsEnabled() {
        setConfigured(true)
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        assertEquals(AccessibilityConnectionStatus.DISABLED, AgentAccessibilityHealth.snapshot(context).status)
        assertEquals(PermissionStatusUi.Missing, accessibilityPermissionHealthItem(context).status)
    }

    @Test
    fun anotherServiceDoesNotGrantEtaScreenAccess() {
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "other.app/.Service")
        assertEquals(AccessibilityConnectionStatus.DISABLED, AgentAccessibilityHealth.snapshot(context).status)
    }

    @Test
    fun fullAndShortComponentsAreEquivalentButClassNamesAreCaseSensitive() {
        val expected = ComponentName("sample.app", "sample.app.Service")
        assertTrue(AgentAccessibilityHealth.containsService("other.app/.Service:sample.app/.Service", expected))
        assertTrue(AgentAccessibilityHealth.containsService("sample.app/sample.app.Service", expected))
        assertFalse(AgentAccessibilityHealth.containsService("sample.app/.service", expected))
    }

    @Test
    fun unreadableSettingsRemainUnknownWithoutCrashingOrGrantingAccess() {
        val denied = object : ContextWrapper(context) {
            override fun getContentResolver(): ContentResolver = throw SecurityException("settings unavailable")
        }
        assertEquals(AccessibilityConnectionStatus.UNKNOWN, AgentAccessibilityHealth.snapshot(denied).status)
        assertEquals(PermissionStatusUi.Warning, accessibilityPermissionHealthItem(denied).status)
    }

    @Test
    fun actualConnectionWorksWithoutAFrameworkAndUnbindIsRecordedOnce() {
        setConfigured(true)
        val controller = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        val service = controller.get()
        val before = AgentAccessibilityHealth.history.value.disconnectCount
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            assertEquals(AccessibilityConnectionStatus.CONNECTED, AgentAccessibilityHealth.snapshot(context).status)
            assertEquals(PermissionStatusUi.Available, accessibilityPermissionHealthItem(context).status)
            service.onUnbind(null)
            assertEquals(AccessibilityConnectionStatus.DISCONNECTED, AgentAccessibilityHealth.snapshot(context).status)
        } finally {
            controller.destroy()
        }
        assertEquals(before + 1, AgentAccessibilityHealth.history.value.disconnectCount)
        assertFalse(AgentAccessibilityHealth.history.value.connected)
        assertEquals(AccessibilityDisconnectReason.UNBOUND, AgentAccessibilityHealth.history.value.lastDisconnectReason)
    }

    @Test
    fun destroyingAnOldServiceCannotDisconnectANewerBinding() {
        val first = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        val second = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(first.get(), "onServiceConnected")
            ReflectionHelpers.callInstanceMethod<Unit>(second.get(), "onServiceConnected")
            first.destroy()
            assertTrue(AgentAccessibilityHealth.history.value.connected)
            assertEquals(AccessibilityConnectionStatus.CONNECTED, AgentAccessibilityHealth.snapshot(context).status)
        } finally {
            second.destroy()
        }
    }

    @Test
    fun connectionChangesReachObserversWithoutReopeningThePage() = runBlocking {
        setConfigured(false)
        val controller = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        val subscribed = CompletableDeferred<Unit>()
        val connected = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Unconfined) {
            AgentAccessibilityHealth.observeChanges(context).collect {
                subscribed.complete(Unit)
                if (AgentAccessibilityHealth.snapshot(context).status == AccessibilityConnectionStatus.CONNECTED) {
                    connected.complete(Unit)
                }
            }
        }
        try {
            withTimeout(5_000L) { subscribed.await() }
            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "onServiceConnected")
            withTimeout(5_000L) { connected.await() }
        } finally {
            job.cancelAndJoin()
            controller.destroy()
        }
    }

    private fun setConfigured(enabled: Boolean) {
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, if (enabled) 1 else 0)
        Settings.Secure.putString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            if (enabled) ComponentName(context, AgentAccessibilityService::class.java).flattenToString() else "",
        )
    }
}
