package io.github.zhh_lgtm.nexus.application.api

/** A Run works on this private copy; source writes require a separate checked publish operation. */
data class WorkspaceBinding(
    val id: String,
    val projectId: String,
    val runId: String,
    val hostPath: String,
    val guestPath: String = "/workspace",
    val sourceReadOnly: Boolean = true,
    val sourceReference: String,
) {
    init {
        require(id.isNotBlank() && projectId.isNotBlank() && runId.isNotBlank())
        require(hostPath.isNotBlank() && guestPath.startsWith('/') && sourceReference.isNotBlank())
    }
}
