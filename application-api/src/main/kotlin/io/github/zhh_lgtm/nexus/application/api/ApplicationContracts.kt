package io.github.zhh_lgtm.nexus.application.api

import io.github.zhh_lgtm.nexus.kernel.api.ExecutionEvent
import io.github.zhh_lgtm.nexus.kernel.api.RunId
import io.github.zhh_lgtm.nexus.kernel.api.RunRequest
import io.github.zhh_lgtm.nexus.kernel.api.SessionId

sealed interface RunAdmission {
    data class Accepted(val request: RunRequest) : RunAdmission
    data class Rejected(val code: String, val message: String) : RunAdmission
}

fun interface RunEventListener {
    fun onEvent(event: ExecutionEvent)
}

fun interface ApplicationSubscription {
    fun close()
}

interface RunApplicationApi {
    fun submit(request: RunRequest, listener: RunEventListener): RunAdmission
    fun cancel(runId: RunId, reason: String)
}

data class ConversationSummary(
    val sessionId: SessionId,
    val title: String,
    val updatedAt: Long,
    val pinned: Boolean,
    val archived: Boolean,
    val running: Boolean,
)

interface ConversationQueryFacade {
    fun recent(limit: Int, includeArchived: Boolean = false): List<ConversationSummary>
    fun find(sessionId: SessionId): ConversationSummary?
}

data class ArtifactSummary(
    val id: String,
    val displayName: String,
    val mediaType: String?,
    val bytes: Long,
    val direction: ArtifactDirection,
)

enum class ArtifactDirection {
    INPUT,
    OUTPUT,
    TEMPORARY,
}

interface ConversationArtifactFacade {
    fun list(sessionId: SessionId): List<ArtifactSummary>
}
