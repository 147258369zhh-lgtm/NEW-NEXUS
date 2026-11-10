package io.github.mangi.eta.fork.conversations

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ConversationExtensionDao {
    @Query("SELECT EXISTS(SELECT 1 FROM branch_setup_receipts WHERE setup_id = :id)")
    suspend fun hasBranchSetupReceipt(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBranchSetupReceipt(receipt: io.github.mangi.eta.data.db.BranchSetupReceiptEntity)

    @Query("DELETE FROM branch_setup_receipts WHERE conversation_id = :id")
    suspend fun deleteBranchSetupReceipts(id: String)

    @Query("SELECT * FROM conversation_folders ORDER BY sort_order ASC, name COLLATE NOCASE ASC")
    fun foldersFlow(): Flow<List<ConversationFolderEntity>>

    @Query("SELECT * FROM conversation_folders ORDER BY sort_order ASC, name COLLATE NOCASE ASC")
    suspend fun folders(): List<ConversationFolderEntity>

    @Query("SELECT * FROM conversation_folders WHERE id = :id")
    suspend fun folder(id: String): ConversationFolderEntity?

    @Query("SELECT * FROM conversation_extensions WHERE folder_id = :folderId ORDER BY folder_sort_order, last_active_at DESC")
    suspend fun folderMembers(folderId: String): List<ConversationExtensionEntity>

    @Query("SELECT * FROM conversation_extensions ORDER BY last_active_at DESC")
    fun extensionsFlow(): Flow<List<ConversationExtensionEntity>>

    @Query("SELECT * FROM conversation_extensions WHERE conversation_id = :conversationId")
    suspend fun extension(conversationId: String): ConversationExtensionEntity?

    @Query("SELECT * FROM conversation_extensions WHERE conversation_id IN (:conversationIds)")
    suspend fun extensions(conversationIds: List<String>): List<ConversationExtensionEntity>

    @Query("SELECT * FROM conversation_extensions")
    suspend fun extensions(): List<ConversationExtensionEntity>

    @Query("SELECT * FROM conversation_archive_settings WHERE id = 0")
    fun archiveSettingsFlow(): Flow<ConversationArchiveSettingsEntity?>

    @Query("SELECT * FROM conversation_archive_settings WHERE id = 0")
    suspend fun archiveSettings(): ConversationArchiveSettingsEntity?

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM conversation_folders")
    suspend fun maxFolderSortOrder(): Long

    @Query("SELECT COALESCE(MAX(folder_sort_order), -1) FROM conversation_extensions WHERE folder_id = :folderId")
    suspend fun maxFolderConversationSortOrder(folderId: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExtensions(extensions: List<ConversationExtensionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExtension(extension: ConversationExtensionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFolder(folder: ConversationFolderEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateFolderEntity(folder: ConversationFolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArchiveSettings(settings: ConversationArchiveSettingsEntity)

    @Insert
    suspend fun insertArchiveEvent(event: ConversationArchiveEventEntity)

    @Query("UPDATE conversation_extensions SET last_active_at = :now, updated_at = :now WHERE conversation_id = :conversationId")
    suspend fun touchLastActive(conversationId: String, now: Long)

    @Query("UPDATE conversation_extensions SET folder_id = :folderId, folder_sort_order = :sortOrder, updated_at = :now WHERE conversation_id = :conversationId")
    suspend fun updateFolder(conversationId: String, folderId: String?, sortOrder: Long, now: Long)

    @Query("UPDATE conversation_extensions SET archived_at = :archivedAt, archive_reason = :reason, updated_at = :now WHERE conversation_id = :conversationId")
    suspend fun markArchived(conversationId: String, archivedAt: Long, reason: String, now: Long)

    @Query("UPDATE conversation_extensions SET archived_at = NULL, archive_reason = NULL, updated_at = :now WHERE conversation_id = :conversationId")
    suspend fun markRestored(conversationId: String, now: Long)

    @Query("UPDATE conversation_extensions SET auto_archive_exempt = :exempt, updated_at = :now WHERE conversation_id = :conversationId")
    suspend fun setArchiveExempt(conversationId: String, exempt: Boolean, now: Long)

    @Query("UPDATE conversation_extensions SET kind = :kind, updated_at = :now WHERE conversation_id = :conversationId")
    suspend fun setKind(conversationId: String, kind: String, now: Long)

    @Query("UPDATE conversation_extensions SET context_window_override = :contextWindow, updated_at = :now WHERE conversation_id = :conversationId")
    suspend fun setContextWindowOverride(conversationId: String, contextWindow: Int?, now: Long): Int

    @Query("UPDATE conversation_extensions SET folder_id = NULL, folder_sort_order = 0, updated_at = :now WHERE folder_id = :folderId")
    suspend fun clearFolderMembers(folderId: String, now: Long)

    @Query("DELETE FROM conversation_folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: String)

    @Query("DELETE FROM conversation_extensions WHERE conversation_id = :conversationId")
    suspend fun deleteExtension(conversationId: String)

    @Query("DELETE FROM conversation_archive_events WHERE conversation_id = :conversationId")
    suspend fun deleteArchiveEvents(conversationId: String)
}
