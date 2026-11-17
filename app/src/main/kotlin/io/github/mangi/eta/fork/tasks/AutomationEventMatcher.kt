package io.github.mangi.eta.fork.tasks

import org.json.JSONObject

/** Applies trigger-owned source filters to a device event without reading model-provided hints. */
internal object AutomationEventMatcher {
    fun matches(trigger: AutomationTriggerEntity, type: AutomationTrigger, payload: JSONObject): Boolean {
        if (trigger.type != type.name) return false
        if (type !in setOf(AutomationTrigger.BLUETOOTH_CONNECTED, AutomationTrigger.BLUETOOTH_DISCONNECTED)) return true
        val requested = trigger.source.trim()
        if (requested.isEmpty()) return true
        return payload.optString("address").equals(requested, ignoreCase = true) ||
            payload.optString("name").equals(requested, ignoreCase = true)
    }
}
