package io.github.zhh_lgtm.nexus.application.api

enum class TransportState {
    STOPPED,
    STARTING,
    LISTENING,
    DEGRADED,
    FAILED,
}

data class TransportStatus(
    val state: TransportState,
    val address: String? = null,
    val reason: String? = null,
    val lastProbeAt: Long? = null,
)

/** Transport owns listening and reachability; A2A/DSH/MCP business code stays transport-agnostic. */
interface HostTransport {
    val id: String
    fun start()
    fun stop()
    fun currentAddress(): String?
    fun status(): TransportStatus = TransportStatus(
        state = if (currentAddress() == null) TransportState.STOPPED else TransportState.LISTENING,
        address = currentAddress(),
    )
}

data class ArtifactRecord(
    val id: String,
    val runId: String,
    val path: String,
    val label: String,
    val kind: String,
    val status: ArtifactStatus,
    val createdAt: Long,
    val verifiedAt: Long? = null,
    val projectId: String? = null,
)

enum class ArtifactStatus {
    PENDING,
    VERIFIED,
    FAILED,
}

interface ArtifactLedger {
    fun list(limit: Int = 40): List<ArtifactRecord>
    fun record(record: ArtifactRecord): ArtifactRecord
    fun updateStatus(id: String, status: ArtifactStatus, now: Long): Boolean
    fun remove(id: String): Boolean
}

interface DshSlotCatalog {
    val hostVersion: String
    fun supportedSlots(): Set<String>
    fun compatibility(slot: String): DshSlotCompatibility
}

enum class DshSlotCompatibility {
    NATIVE,
    WEBVIEW_FALLBACK,
    UNSUPPORTED,
}
