package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
internal data class RoleplayBinding(
    val characterId: String,
    val cardSnapshotJson: String,
    val characterName: String,
    val avatarPath: String? = null,
    val userName: String = "用户",
    val userDescription: String = "",
)

@Serializable
internal data class UserPersona(val name: String = "用户", val description: String = "")

@Serializable
internal data class RoleplayMessageState(
    val links: Map<String, RoleplayMessageLink> = emptyMap(),
    val revisions: Map<String, RoleplayReplyRevision> = emptyMap(),
    val pendingRewrites: Map<String, String> = emptyMap(),
)

@Serializable
internal data class RoleplayMessageLink(val transcriptMessageId: String, val blockOrder: Int = 0)

@Serializable
internal data class RoleplayReplyRevision(
    val original: String,
    val candidates: List<String>,
    val selected: Int,
) {
    val content: String get() = candidates.getOrElse(selected) { original }
}
