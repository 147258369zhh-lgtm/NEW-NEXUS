package io.github.mangi.eta.fork.tasks

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationEventMatcherTest {
    @Test fun blankBluetoothSourceKeepsTheExistingAnyDeviceBehavior() {
        val trigger = trigger(AutomationTrigger.BLUETOOTH_CONNECTED, "")

        assertTrue(AutomationEventMatcher.matches(trigger, AutomationTrigger.BLUETOOTH_CONNECTED,
            JSONObject().put("address", "AA:BB:CC:DD:EE:FF").put("name", "Headphones")))
    }

    @Test fun bluetoothSourceMatchesAddressOrNameAndRejectsOtherDevices() {
        val byAddress = trigger(AutomationTrigger.BLUETOOTH_DISCONNECTED, "aa:bb:cc:dd:ee:ff")
        val payload = JSONObject().put("address", "AA:BB:CC:DD:EE:FF").put("name", "Headphones")
        assertTrue(AutomationEventMatcher.matches(byAddress, AutomationTrigger.BLUETOOTH_DISCONNECTED, payload))
        assertTrue(AutomationEventMatcher.matches(trigger(AutomationTrigger.BLUETOOTH_DISCONNECTED, "headphones"),
            AutomationTrigger.BLUETOOTH_DISCONNECTED, payload))
        assertFalse(AutomationEventMatcher.matches(trigger(AutomationTrigger.BLUETOOTH_DISCONNECTED, "Keyboard"),
            AutomationTrigger.BLUETOOTH_DISCONNECTED, payload))
        assertFalse(AutomationEventMatcher.matches(byAddress, AutomationTrigger.BLUETOOTH_CONNECTED, payload))
    }

    private fun trigger(type: AutomationTrigger, source: String) = AutomationTriggerEntity(
        id = "trigger",
        definitionId = "task",
        type = type.name,
        source = source,
    )
}
