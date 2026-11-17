package io.github.mangi.eta.fork.tasks

import org.junit.Assert.*
import org.junit.Test

class AutomationNetworkTrackerTest {
    @Test fun handoverDoesNotReportAFalseOfflineGapOrDuplicateConnections() {
        val tracker = AutomationNetworkTracker()
        assertEquals(listOf(AutomationTrigger.NETWORK_CONNECTED, AutomationTrigger.WIFI_CONNECTED),
            tracker.update("wifi", true, setOf(AutomationNetworkTransport.WIFI)))
        assertTrue(tracker.update("wifi", true, setOf(AutomationNetworkTransport.WIFI)).isEmpty())
        assertEquals(listOf(AutomationTrigger.MOBILE_CONNECTED), tracker.update("mobile", true, setOf(AutomationNetworkTransport.MOBILE)))
        assertEquals(listOf(AutomationTrigger.WIFI_DISCONNECTED), tracker.update("wifi", false, emptySet()))
        assertEquals(listOf(AutomationTrigger.NETWORK_DISCONNECTED, AutomationTrigger.MOBILE_DISCONNECTED), tracker.update("mobile", false, emptySet()))
        assertTrue(tracker.update("mobile", false, emptySet()).isEmpty())
    }

    @Test fun validationLossAndRestorationProduceDistinctUsableNetworkTransitions() {
        val tracker = AutomationNetworkTracker()
        tracker.update("wifi", true, setOf(AutomationNetworkTransport.WIFI))
        assertEquals(setOf(AutomationTrigger.NETWORK_DISCONNECTED, AutomationTrigger.WIFI_DISCONNECTED),
            tracker.update("wifi", false, setOf(AutomationNetworkTransport.WIFI)).toSet())
        assertEquals(setOf(AutomationTrigger.NETWORK_CONNECTED, AutomationTrigger.WIFI_CONNECTED),
            tracker.update("wifi", true, setOf(AutomationNetworkTransport.WIFI)).toSet())
    }

    @Test fun ethernetAndVpnStillParticipateInOverallConnectivityWithoutFakeMobileEvents() {
        val tracker = AutomationNetworkTracker()
        assertEquals(listOf(AutomationTrigger.NETWORK_CONNECTED), tracker.update("ethernet", true, emptySet()))
        assertTrue(tracker.update("vpn", true, emptySet()).isEmpty())
        assertTrue(tracker.update("ethernet", false, emptySet()).isEmpty())
        assertEquals(listOf(AutomationTrigger.NETWORK_DISCONNECTED), tracker.update("vpn", false, emptySet()))
    }
}
