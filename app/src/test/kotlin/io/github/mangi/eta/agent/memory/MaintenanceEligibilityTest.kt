package io.github.mangi.eta.agent.memory

import android.app.Application
import androidx.work.NetworkType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MaintenanceEligibilityTest {
    private val settings = MemoryAutomationSettings()
    @Test fun realWorkConstraintsAllowOfflineLocalModelsWhileKeepingChargingAndBatteryRequirements() {
        val constraints = MemoryMaintenanceScheduler.constraints(settings)
        assertEquals(NetworkType.NOT_REQUIRED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresCharging())
        assertTrue(constraints.requiresBatteryNotLow())
    }
    @Test fun localIdleWorkSkipsOnlyNetworkAndStillHonorsTheUserDevicePolicy() {
        assertNull(MaintenanceEligibility.evaluate(settings, true, false, false, true, false, false, 600_000))
        assertEquals("等待充电", MaintenanceEligibility.evaluate(settings, true, false, false, false, false, false, 600_000))
        assertEquals("等待熄屏", MaintenanceEligibility.evaluate(settings, true, false, true, true, false, false, 600_000))
        assertNotNull(MaintenanceEligibility.evaluate(settings, true, false, false, true, false, false, 60_000))
        assertNotNull(MaintenanceEligibility.evaluate(settings.copy(idleEnabled = false), true, false, false, true, true, true, 600_000))
    }
    @Test fun remoteWorkRequiresValidatedNetworkAndManualRunDoesNotSpendWithoutAConnection() {
        assertEquals("等待可用网络", MaintenanceEligibility.evaluate(settings, false, false, false, true, false, false, 600_000))
        assertEquals("等待非计费网络", MaintenanceEligibility.evaluate(settings, false, false, false, true, true, false, 600_000))
        assertNull(MaintenanceEligibility.evaluate(settings, false, false, false, true, true, true, 600_000))
        assertNull(MaintenanceEligibility.evaluate(settings, true, true, true, false, false, false, 0))
        assertNotNull(MaintenanceEligibility.evaluate(settings, false, true, true, false, false, false, 0))
    }

    @Test fun foregroundBudgetAndDevicePressurePauseAutomaticLearningButManualRunsContinue() {
        assertEquals("应用在前台", MaintenanceEligibility.evaluate(
            settings, true, false, false, true, true, true, 600_000, foreground = true))
        assertEquals("今日学习额度已用完", MaintenanceEligibility.evaluate(
            settings, true, false, false, true, true, true, 600_000, budgetExhausted = true))
        assertEquals("等待设备资源释放", MaintenanceEligibility.evaluate(
            settings, true, false, false, true, true, true, 600_000, resourcesLimited = true))
        assertNull(MaintenanceEligibility.evaluate(
            settings, true, true, true, false, false, false, 0,
            foreground = true, budgetExhausted = true, resourcesLimited = true))
        assertTrue(MaintenanceEligibility.isBudgetDenial("RUN_BUDGET_EXCEEDED_ROUNDS"))
        assertTrue(MaintenanceEligibility.isBudgetDenial("RUN_SOFT_BUDGET_NOT_APPROVED_DURATION"))
        assertFalse(MaintenanceEligibility.isBudgetDenial("RESOURCE_YIELD"))
        assertFalse(MaintenanceEligibility.isBudgetDenial(null))
    }
}
