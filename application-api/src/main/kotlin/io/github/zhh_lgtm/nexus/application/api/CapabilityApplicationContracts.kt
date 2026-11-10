package io.github.zhh_lgtm.nexus.application.api

import io.github.zhh_lgtm.nexus.kernel.api.CapabilitySnapshot
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.HostId
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import io.github.zhh_lgtm.nexus.kernel.api.TaskId
import io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor

data class ToolGrant(
    val taskId: TaskId,
    val hostId: HostId,
    val hostKind: HostKind,
    val allowedTools: Set<String>,
    val allowedEffects: Set<EffectClass>,
    val highRiskApproved: Boolean,
    val validUntil: Long,
)

sealed interface CapabilityAuthorization {
    data object Allowed : CapabilityAuthorization
    data class Denied(val code: String, val reason: String) : CapabilityAuthorization
}

object CapabilityPolicyEngine {
    fun authorize(
        descriptor: ToolDescriptor,
        grant: ToolGrant,
        capabilities: Collection<CapabilitySnapshot>,
        now: Long,
    ): CapabilityAuthorization {
        if (now >= grant.validUntil) return denied("GRANT_EXPIRED", "Tool grant has expired")
        if (descriptor.hostKind != grant.hostKind) {
            return denied("HOST_MISMATCH", "Tool host is unavailable")
        }
        if ("*" !in grant.allowedTools && descriptor.id !in grant.allowedTools) {
            return denied("TOOL_NOT_GRANTED", "Tool is outside the current task grant")
        }
        if (descriptor.effectClass !in grant.allowedEffects) {
            return denied("EFFECT_NOT_GRANTED", "Tool effect is outside the current task grant")
        }
        if (descriptor.effectClass == EffectClass.HIGH_RISK && !grant.highRiskApproved) {
            return denied("LOCAL_CONFIRMATION_REQUIRED", "High-risk tools require local confirmation")
        }
        descriptor.requiredCapabilities.forEach { required ->
            val snapshot = capabilities.firstOrNull { it.capability == required && it.hostId == grant.hostId }
                ?: return denied("CAPABILITY_UNAVAILABLE", "Required capability is unavailable: $required")
            if (!snapshot.usable) return denied("CAPABILITY_NOT_AUTHORIZED", "Required capability is not authorized: $required")
            val validUntil = snapshot.validUntil
            if (validUntil != null && now >= validUntil) {
                return denied("CAPABILITY_EXPIRED", "Required capability grant has expired: $required")
            }
        }
        return CapabilityAuthorization.Allowed
    }

    private fun denied(code: String, reason: String) = CapabilityAuthorization.Denied(code, reason)
}

data class CapabilitySummary(
    val id: String,
    val namespace: String,
    val description: String,
    val effectClass: EffectClass,
    val available: Boolean,
    val active: Boolean,
)

data class CapabilityActivation(
    val toolId: String,
    val schemaRef: String,
    val expiresAt: Long,
)

interface CapabilityDirectory {
    fun list(query: String = ""): List<CapabilitySummary>
    fun describe(toolId: String): ToolDescriptor?
    fun activate(toolId: String, expiresAt: Long): CapabilityActivation
}

class InMemoryCapabilityDirectory(
    descriptors: Collection<ToolDescriptor>,
    private val available: (ToolDescriptor) -> Boolean = { true },
) : CapabilityDirectory {
    private val descriptors = descriptors.also { values ->
        require(values.map(ToolDescriptor::id).toSet().size == values.size) { "Duplicate capability id" }
    }.associateBy(ToolDescriptor::id)
    private val active = linkedMapOf<String, CapabilityActivation>()

    override fun list(query: String): List<CapabilitySummary> {
        val needle = query.trim()
        return synchronized(active) {
            descriptors.values.asSequence()
                .filter { descriptor ->
                    needle.isEmpty() || listOf(descriptor.id, descriptor.namespace, descriptor.shortDescription)
                        .any { it.contains(needle, ignoreCase = true) }
                }
                .map { descriptor ->
                    CapabilitySummary(
                        id = descriptor.id,
                        namespace = descriptor.namespace,
                        description = descriptor.shortDescription,
                        effectClass = descriptor.effectClass,
                        available = available(descriptor),
                        active = descriptor.id in active,
                    )
                }
                .sortedWith(compareBy(CapabilitySummary::namespace, CapabilitySummary::id))
                .toList()
        }
    }

    override fun describe(toolId: String): ToolDescriptor? = descriptors[toolId]

    override fun activate(toolId: String, expiresAt: Long): CapabilityActivation = synchronized(active) {
        val descriptor = descriptors[toolId] ?: error("Unknown capability: $toolId")
        require(available(descriptor)) { "Capability is unavailable: $toolId" }
        require(expiresAt > 0) { "Activation expiry must be positive" }
        CapabilityActivation(
            toolId = descriptor.id,
            schemaRef = descriptor.inputSchemaRef,
            expiresAt = expiresAt,
        ).also { active[toolId] = it }
    }
}

interface HostAdapter {
    val id: HostId
    fun status(): HostStatus
    fun listTools(): List<ToolDescriptor>
}

class HostAdapterRegistry {
    private val adapters = linkedMapOf<HostId, HostAdapter>()

    fun register(adapter: HostAdapter) = synchronized(adapters) {
        require(adapters.put(adapter.id, adapter) == null) { "Duplicate host adapter: ${adapter.id.value}" }
    }

    fun unregister(id: HostId): Boolean = synchronized(adapters) { adapters.remove(id) != null }

    fun list(): List<HostAdapter> = synchronized(adapters) { adapters.values.toList() }

    fun ready(): List<HostAdapter> = list().filter { it.status() == HostStatus.READY }
}

enum class HostStatus {
    STOPPED,
    STARTING,
    READY,
    DEGRADED,
    FAILED,
}
