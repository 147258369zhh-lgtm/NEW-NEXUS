package io.github.mangi.eta.fork.conversations

internal const val CHAT_CONVERSATION_KIND = "chat"
internal const val MEETING_CONVERSATION_KIND = "meeting"

internal const val MANUAL_ARCHIVE_REASON = "manual"
internal const val IDLE_ARCHIVE_REASON = "idle"

internal data class ConversationFolder(
    val id: String,
    val name: String,
    val sortOrder: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val parentId: String? = null,
)

internal data class ConversationExtension(
    val conversationId: String,
    val kind: String = CHAT_CONVERSATION_KIND,
    val folderId: String? = null,
    val folderSortOrder: Long = 0L,
    val lastActiveAt: Long,
    val archivedAt: Long? = null,
    val archiveReason: String? = null,
    val autoArchiveExempt: Boolean = false,
    val contextWindowOverride: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

internal data class ConversationArchiveSettings(
    val enabled: Boolean = true,
    val idleDays: Int = 30,
    val exemptPinned: Boolean = true,
    val exemptMeetings: Boolean = true,
    val exemptRunning: Boolean = true,
    val lastCheckedAt: Long? = null,
    val lastError: String? = null,
)

internal data class ConversationExtensionSnapshot(
    val folders: List<ConversationFolder> = emptyList(),
    val conversations: Map<String, ConversationExtension> = emptyMap(),
    val archiveSettings: ConversationArchiveSettings = ConversationArchiveSettings(),
)

internal data class ConversationArchiveCandidate(
    val conversationId: String,
    val isPinned: Boolean,
    val isMeeting: Boolean,
    val isRunning: Boolean,
) {
    fun isEligible(settings: ConversationArchiveSettings): Boolean =
        !isMeeting || !settings.exemptMeetings
}

internal object ConversationFolderOrdering {
    fun sortFolders(folders: List<ConversationFolder>): List<ConversationFolder> =
        folders.sortedWith(compareBy<ConversationFolder> { it.sortOrder }.thenBy { it.name.lowercase() })

    fun sortInFolder(
        conversations: List<ConversationExtension>,
        folderId: String,
    ): List<ConversationExtension> = conversations
        .filter { it.folderId == folderId }
        .sortedWith(
            compareBy<ConversationExtension> { it.folderSortOrder }
                .thenByDescending { it.lastActiveAt },
        )
}
