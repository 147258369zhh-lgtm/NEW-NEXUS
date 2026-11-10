package io.github.zhh_lgtm.nexus.kernel.api

enum class EffectClass {
    READ,
    REVERSIBLE_WRITE,
    IDEMPOTENT_WRITE,
    NON_IDEMPOTENT_WRITE,
    DESTRUCTIVE,
    EXTERNAL_SIDE_EFFECT,
    GUI_ACTION,
    HIGH_RISK,
}

enum class ReplayPolicy {
    AUTOMATIC,
    VERIFY_THEN_RESUME,
    REQUIRE_USER,
    NEVER,
}

enum class HostKind {
    NATIVE_ANDROID,
    LINUX,
    MCP,
    A2A,
    DSH,
    SKILLS,
}

data class ToolDescriptor(
    val id: String,
    val namespace: String,
    val shortDescription: String,
    val modelDescription: String,
    val inputSchemaRef: String,
    val outputSchemaRef: String? = null,
    val requiredCapabilities: Set<String> = emptySet(),
    val effectClass: EffectClass,
    val replayPolicy: ReplayPolicy,
    val timeoutMs: Long,
    val verifierId: String? = null,
    val concurrencyKey: String? = null,
    val presentationSchemaRef: String? = null,
    val hostKind: HostKind,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_./:-]{1,128}"))) { "Invalid tool id" }
        require(namespace.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Invalid namespace" }
        require(modelDescription.isNotBlank()) { "Model description must not be blank" }
        require(inputSchemaRef.isNotBlank()) { "Input schema reference is required" }
        require(timeoutMs > 0) { "Timeout must be positive" }
    }
}

data class CapabilitySnapshot(
    val hostId: HostId,
    val capability: String,
    val available: Boolean,
    val granted: Boolean,
    val authorized: Boolean,
    val backend: String? = null,
    val reason: String? = null,
    val validUntil: Long? = null,
) {
    val usable: Boolean get() = available && granted && authorized
}

data class ApprovalRequest(
    val id: String,
    val runId: RunId,
    val toolId: String,
    val effectClass: EffectClass,
    val summary: String,
    val command: String? = null,
    val affectedTargets: List<String> = emptyList(),
    val estimatedCost: String? = null,
    val expiresAt: Long,
)

data class PluginGrant(
    val pluginId: PluginId,
    val hostId: HostId,
    val taskId: TaskId,
    val capabilities: Set<String>,
    val effectLimits: Set<EffectClass>,
    val dataScopes: Set<String>,
    val validUntil: Long,
    val approvedBy: String,
)
