package io.github.mangi.eta.ui.app

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37], application = Application::class)
class PermissionSettingsNavigationTest {
    @Test
    fun colorOsCommandUsesInstalledPackageAndAppUserInsteadOfForegroundUser() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageName() = "example.installed.eta"
            override fun getApplicationInfo() = ApplicationInfo(super.getApplicationInfo()).apply { uid = 1_210_001 }
        }
        val command = colorOsBackgroundSettingsCommand(context)
        assertTrue(command.contains("--user 12 "))
        assertTrue(command.contains("--es pkgName 'example.installed.eta'"))
        assertFalse(command.contains("--user current"))
        assertFalse(command.contains("--es pkgName io.github.mangi.eta"))
    }

    @Test
    fun missingRomActivityFallsBackWithoutCrashingAndStopsAtFirstSuccess() {
        val actions = mutableListOf<String?>()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) {
                actions += intent.action
                if (actions.size == 1) throw ActivityNotFoundException()
                assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            }
        }
        assertTrue(openFirstAvailableSettings(context, listOf(
            Intent("missing.rom.settings"), Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS), Intent("not.opened"),
        )))
        assertEquals(listOf("missing.rom.settings", Settings.ACTION_APPLICATION_DETAILS_SETTINGS), actions)
    }

    @Test
    fun unavailableSettingsReturnFailureInsteadOfClaimingSuccess() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) { throw SecurityException("restricted") }
        }
        assertFalse(openFirstAvailableSettings(context, listOf(Intent("missing"))))
    }

    @Test
    fun amErrorsWithZeroExitCodeStillTriggerFallback() {
        val result = BoundedRootCommandExecutor.Result(0, "Starting: Intent {}", "", false, false)
        assertTrue(backgroundSettingsOpened(result))
        assertFalse(backgroundSettingsOpened(result.copy(stdout = "Error type 3\nError: Activity class does not exist.")))
        assertFalse(backgroundSettingsOpened(result.copy(stderr = "SecurityException: Permission denied")))
        assertFalse(backgroundSettingsOpened(result.copy(timedOut = true)))
    }
}
