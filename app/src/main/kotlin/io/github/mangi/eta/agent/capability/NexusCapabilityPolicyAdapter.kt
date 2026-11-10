package io.github.mangi.eta.agent.capability

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.zhh_lgtm.nexus.application.api.CapabilityAuthorization
import io.github.zhh_lgtm.nexus.application.api.CapabilityPolicyEngine
import io.github.zhh_lgtm.nexus.application.api.ToolGrant
import io.github.zhh_lgtm.nexus.kernel.api.CapabilitySnapshot
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.HostId
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import io.github.zhh_lgtm.nexus.kernel.api.TaskId
import io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor

internal object NexusCapabilityPolicyAdapter {
    fun authorize(
        descriptor: ToolDescriptor,
        capabilities: AgentToolCapabilities,
        taskId: String,
        highRiskApproved: Boolean,
        now: Long = System.currentTimeMillis(),
    ): CapabilityAuthorization {
        val hostId = hostId(descriptor.hostKind)
        val grant = ToolGrant(
            taskId = TaskId(taskId),
            hostId = hostId,
            hostKind = descriptor.hostKind,
            allowedTools = setOf("*"),
            allowedEffects = EffectClass.entries.toSet(),
            highRiskApproved = highRiskApproved,
            validUntil = Long.MAX_VALUE,
        )
        return CapabilityPolicyEngine.authorize(descriptor, grant, snapshots(capabilities, hostId), now)
    }

    private fun snapshots(capabilities: AgentToolCapabilities, hostId: HostId): List<CapabilitySnapshot> = listOf(
        snapshot(hostId, "device.root", capabilities.rootAvailable),
        snapshot(hostId, "device.lsposed", capabilities.lsposedAvailable),
        snapshot(hostId, "device.accessibility", capabilities.accessibilityAvailable || capabilities.accessibilityRecoveryAvailable),
        snapshot(hostId, "device.coloros", capabilities.colorOs),
        snapshot(hostId, "device.notifications", capabilities.notificationsAllowed),
        snapshot(hostId, "device.usage", capabilities.usageAllowed),
        snapshot(hostId, "device.location", capabilities.locationAllowed),
        snapshot(hostId, "calendar.read", capabilities.calendarReadable),
        snapshot(hostId, "calendar.write", capabilities.calendarWritable),
    )

    private fun snapshot(hostId: HostId, name: String, usable: Boolean) = CapabilitySnapshot(
        hostId = hostId,
        capability = name,
        available = usable,
        granted = usable,
        authorized = usable,
        backend = "native-android",
    )

    private fun hostId(kind: HostKind) = HostId(kind.name.lowercase().replace('_', '-'))
}
