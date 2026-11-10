package io.github.mangi.eta.fork.projects

/** Stable subject types that can own a project context. */
internal object ProjectSubjectType {
    const val CONVERSATION = "conversation"
    const val MEETING = "meeting"
}

internal class ProjectException(val code: String) : IllegalArgumentException(code)

internal data class ProjectDraft(
    val name: String,
    val providerId: String,
    val rootIdentityKey: String,
    val rootDisplayName: String,
    val source: String,
)

internal data class ProjectSubjectBinding(
    val projectId: String,
    val subjectType: String,
    val subjectId: String,
)
