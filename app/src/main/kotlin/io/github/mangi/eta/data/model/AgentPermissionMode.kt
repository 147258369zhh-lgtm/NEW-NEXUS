package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AgentPermissionMode(val wireValue: String) {
    FULL_ACCESS("full_access"),
    SMART_APPROVAL("smart_approval"),
    MANUAL_APPROVAL("manual_approval"),
    ;

    companion object {
        fun fromWireValue(value: String?): AgentPermissionMode =
            entries.firstOrNull { it.wireValue == value } ?: SMART_APPROVAL
    }
}

/** Durable restriction revisions survive a restrict-then-relax sequence between tool calls. */
data class AgentPermissionState(
    val mode: AgentPermissionMode,
    val revision: Long,
    val lastSmartRestrictionRevision: Long,
    val lastManualRestrictionRevision: Long,
)
