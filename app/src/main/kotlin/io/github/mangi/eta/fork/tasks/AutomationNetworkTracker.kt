package io.github.mangi.eta.fork.tasks

internal enum class AutomationNetworkTransport { WIFI, MOBILE }

/** Default-network callbacks may overlap during a handover; emit changes in aggregate state. */
internal class AutomationNetworkTracker {
    private val networks = linkedMapOf<String, Set<AutomationNetworkTransport>>()

    @Synchronized fun update(id: String, validated: Boolean, transports: Set<AutomationNetworkTransport>): List<AutomationTrigger> {
        val wasConnected = networks.isNotEmpty()
        val before = networks.values.flatten().toSet()
        if (validated) networks[id] = transports else networks.remove(id)
        val connected = networks.isNotEmpty()
        val after = networks.values.flatten().toSet()
        return buildList {
            if (!wasConnected && connected) add(AutomationTrigger.NETWORK_CONNECTED)
            if (wasConnected && !connected) add(AutomationTrigger.NETWORK_DISCONNECTED)
            for (transport in AutomationNetworkTransport.entries) {
                if (transport !in before && transport in after) add(if (transport == AutomationNetworkTransport.WIFI) AutomationTrigger.WIFI_CONNECTED else AutomationTrigger.MOBILE_CONNECTED)
                if (transport in before && transport !in after) add(if (transport == AutomationNetworkTransport.WIFI) AutomationTrigger.WIFI_DISCONNECTED else AutomationTrigger.MOBILE_DISCONNECTED)
            }
        }
    }
}
