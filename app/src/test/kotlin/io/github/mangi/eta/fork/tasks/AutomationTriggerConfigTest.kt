package io.github.mangi.eta.fork.tasks

import io.github.mangi.eta.ui.screens.tasks.AutomationTriggerGroup
import io.github.mangi.eta.ui.screens.tasks.automationTriggerGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTriggerConfigTest {
    @Test fun location_round_trip_preserves_region() {
        val area = LocationAutomationConfig(31.2304, 121.4737, 500)

        assertEquals(area, LocationAutomationConfig.parse(area.encode()))
    }

    @Test fun invalid_battery_and_location_sources_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AutomationTriggerConfig.validate(AutomationTrigger.BATTERY_BELOW, "0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutomationTriggerConfig.validate(AutomationTrigger.LOCATION_ENTER, "121.4,31.2,100")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutomationTriggerConfig.validate(AutomationTrigger.LOCATION_EXIT, "31.2,121.4,20")
        }
    }

    @Test fun every_non_manual_trigger_has_a_visible_editor_group() {
        val grouped = AutomationTrigger.entries.groupBy(::automationTriggerGroup)

        assertEquals(AutomationTrigger.entries.toSet(), grouped.values.flatten().toSet())
        AutomationTriggerGroup.entries
            .filterNot { it == AutomationTriggerGroup.SCHEDULE }
            .forEach { group -> assertTrue("missing trigger for $group", grouped[group].orEmpty().isNotEmpty()) }
    }

    @Test fun app_open_requires_a_real_package_source() {
        AutomationTriggerConfig.validate(AutomationTrigger.APP_OPENED, "com.example.notes")

        assertThrows(IllegalArgumentException::class.java) {
            AutomationTriggerConfig.validate(AutomationTrigger.APP_OPENED, "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutomationTriggerConfig.validate(AutomationTrigger.APP_OPENED, "Notes")
        }
    }
}
