package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class ConversationPaneUiState(
    val conversations: List<ConversationSummaryUi>,
    val selectedConversationId: String?,
    val searchQuery: String,
    val folders: List<ConversationFolderUi> = emptyList(),
    val archivedConversations: List<ConversationSummaryUi> = emptyList(),
    val archiveViewOpen: Boolean = false,
)

@Immutable
data class ConversationFolderUi(
    val id: String,
    val name: String,
    val sortOrder: Long,
    val parentId: String? = null,
)

@Immutable
data class ConversationSummaryUi(
    val id: String,
    val title: String,
    val preview: String,
    val timeLabel: String,
    val updatedAtMillis: Long = 0L,
    val mode: ConversationModeUi,
    val isPinned: Boolean = false,
    val isActiveRun: Boolean = false,
    val runStatus: String? = null,
    val characterName: String? = null,
    val characterAvatarPath: String? = null,
    val folderId: String? = null,
    val kind: ConversationKindUi = ConversationKindUi.Chat,
    val isArchived: Boolean = false,
    val isAutoArchived: Boolean = false,
    val autoArchiveExempt: Boolean = false,
    val folderSortOrder: Long = 0L,
    val branchParentConversationId: String? = null,
    val branchFamilyId: String? = null,
)

@Immutable
enum class ConversationKindUi {
    Chat,
    Meeting,
}

@Immutable
enum class ConversationModeUi {
    Chat,
    PhoneAgent,
    Terminal,
    Automation,
}
