package io.github.mangi.eta.fork.conversations

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_folders",
    indices = [Index(value = ["sort_order"]), Index(value = ["parent_id", "name"], unique = true)],
)
internal data class ConversationFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
)

@Entity(
    tableName = "conversation_extensions",
    indices = [Index(value = ["folder_id", "folder_sort_order"]), Index(value = ["archived_at"]), Index(value = ["last_active_at"])],
)
internal data class ConversationExtensionEntity(
    @PrimaryKey @ColumnInfo(name = "conversation_id") val conversationId: String,
    val kind: String = CHAT_CONVERSATION_KIND,
    @ColumnInfo(name = "folder_id") val folderId: String? = null,
    @ColumnInfo(name = "folder_sort_order") val folderSortOrder: Long = 0L,
    @ColumnInfo(name = "last_active_at") val lastActiveAt: Long,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
    @ColumnInfo(name = "archive_reason") val archiveReason: String? = null,
    @ColumnInfo(name = "auto_archive_exempt", defaultValue = "0") val autoArchiveExempt: Boolean = false,
    @ColumnInfo(name = "context_window_override") val contextWindowOverride: Int? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "conversation_archive_events",
    indices = [Index(value = ["conversation_id", "created_at"])],
)
internal data class ConversationArchiveEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val type: String,
    @ColumnInfo(name = "reason") val reason: String,
    @ColumnInfo(name = "is_automatic") val isAutomatic: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "conversation_archive_settings")
internal data class ConversationArchiveSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val enabled: Boolean = true,
    @ColumnInfo(name = "idle_days") val idleDays: Int = 30,
    @ColumnInfo(name = "exempt_pinned") val exemptPinned: Boolean = true,
    @ColumnInfo(name = "exempt_meetings") val exemptMeetings: Boolean = true,
    @ColumnInfo(name = "exempt_running") val exemptRunning: Boolean = true,
    @ColumnInfo(name = "last_checked_at") val lastCheckedAt: Long? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

internal fun ConversationFolderEntity.toModel() = ConversationFolder(
    id = id,
    name = name,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
    parentId = parentId,
)

internal fun ConversationExtensionEntity.toModel() = ConversationExtension(
    conversationId = conversationId,
    kind = kind,
    folderId = folderId,
    folderSortOrder = folderSortOrder,
    lastActiveAt = lastActiveAt,
    archivedAt = archivedAt,
    archiveReason = archiveReason,
    autoArchiveExempt = autoArchiveExempt,
    contextWindowOverride = contextWindowOverride,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ConversationArchiveSettingsEntity.toModel() = ConversationArchiveSettings(
    enabled = enabled,
    idleDays = idleDays.coerceIn(MIN_ARCHIVE_IDLE_DAYS, MAX_ARCHIVE_IDLE_DAYS),
    exemptPinned = exemptPinned,
    exemptMeetings = exemptMeetings,
    exemptRunning = exemptRunning,
    lastCheckedAt = lastCheckedAt,
    lastError = lastError,
)

internal fun ConversationArchiveSettings.toEntity() = ConversationArchiveSettingsEntity(
    enabled = enabled,
    idleDays = idleDays.coerceIn(MIN_ARCHIVE_IDLE_DAYS, MAX_ARCHIVE_IDLE_DAYS),
    exemptPinned = exemptPinned,
    exemptMeetings = exemptMeetings,
    exemptRunning = exemptRunning,
    lastCheckedAt = lastCheckedAt,
    lastError = lastError,
)

internal const val MIN_ARCHIVE_IDLE_DAYS = 1
internal const val MAX_ARCHIVE_IDLE_DAYS = 3650
