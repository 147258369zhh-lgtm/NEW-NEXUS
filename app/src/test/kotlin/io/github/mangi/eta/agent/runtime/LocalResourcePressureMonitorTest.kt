package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LocalResourcePressureMonitorTest {
    @Test fun actualSystemObserversForwardChangesAndReleaseTheirRegistrations() {
        val app = RuntimeEnvironment.getApplication()
        var changes = 0
        val monitor = LocalResourcePressureMonitor(app) { changes++ }
        app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(changes > 0)
        monitor.close()
        val closed = changes
        app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(closed, changes)
    }
}
