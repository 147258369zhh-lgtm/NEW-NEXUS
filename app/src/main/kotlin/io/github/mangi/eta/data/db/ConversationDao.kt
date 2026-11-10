package io.github.mangi.eta.data.db

import android.database.Cursor
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
internal interface ConversationDao : ChunkedTextDao {
    @Query("SELECT EXISTS(SELECT 1 FROM conversations WHERE id = :id)")
    suspend fun conversationExists(id: String): Boolean
    @Query("SELECT * FROM conversation_branch_setups WHERE conversation_id = :id")
    suspend fun branchSetup(id: String): ConversationBranchSetupEntity?

    @Query("SELECT * FROM conversation_branch_setups ORDER BY conversation_id LIMIT :limit OFFSET :offset")
    suspend fun branchSetupsPage(limit: Int, offset: Int): List<ConversationBranchSetupEntity>

    @Query("SELECT * FROM conversation_branch_setups WHERE completed_at IS NULL ORDER BY created_at, conversation_id LIMIT :limit")
    suspend fun pendingBranchSetups(limit: Int): List<ConversationBranchSetupEntity>

    @Query("SELECT * FROM conversation_branch_setups")
    fun branchSetupsFlow(): kotlinx.coroutines.flow.Flow<List<ConversationBranchSetupEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBranchSetup(setup: ConversationBranchSetupEntity)

    @Query("UPDATE conversation_branch_setups SET completed_at = :now, completion_mode = :mode, error_code = NULL " +
        "WHERE conversation_id = :id AND setup_id = :setupId AND completed_at IS NULL")
    suspend fun completeBranchSetup(id: String, setupId: String, now: Long, mode: String): Int

    @Query("UPDATE conversation_branch_setups SET error_code = :code WHERE conversation_id = :id AND setup_id = :setupId AND completed_at IS NULL")
    suspend fun branchSetupError(id: String, setupId: String, code: String)

    @Query("SELECT * FROM conversation_branches ORDER BY created_at, conversation_id")
    fun branchesFlow(): kotlinx.coroutines.flow.Flow<List<ConversationBranchEntity>>

    @Query("SELECT * FROM conversation_branches WHERE conversation_id = :id")
    suspend fun branch(id: String): ConversationBranchEntity?

    @Query("SELECT * FROM conversation_branches ORDER BY conversation_id LIMIT :limit OFFSET :offset")
    suspend fun branchesPage(limit: Int, offset: Int): List<ConversationBranchEntity>

    @Query("SELECT * FROM conversation_message_origins ORDER BY message_id LIMIT :limit OFFSET :offset")
    suspend fun messageOriginsPage(limit: Int, offset: Int): List<ConversationMessageOriginEntity>

    @Query("SELECT * FROM conversation_message_origins WHERE conversation_id = :id")
    suspend fun messageOrigins(id: String): List<ConversationMessageOriginEntity>
    @Query("SELECT o.* FROM conversation_message_origins o JOIN conversation_messages m ON m.id = o.message_id " +
        "WHERE o.conversation_id = :id AND m.sort_index >= :start")
    suspend fun messageOriginsFrom(id: String, start: Int): List<ConversationMessageOriginEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBranch(branch: ConversationBranchEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageOrigin(origin: ConversationMessageOriginEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM conversations WHERE id = :conversationId)")
    fun conversationExistsFlow(conversationId: String): kotlinx.coroutines.flow.Flow<Boolean>

    @Query(
        "SELECT c.id, c.title, c.reasoning_effort, c.created_at, c.updated_at, c.model_id, c.is_pinned, " +
            "(c.roleplay_json != '') AS has_roleplay, " +
            "m.id AS message_id, m.type AS message_type, m.tool_name, " +
            "COALESCE((SELECT substr(content, 1, 256) FROM agent_text_chunks " +
            "WHERE owner_table = 'conversation_messages' AND owner_id = m.id AND field = 'content' " +
            "AND chunk_index = 0), substr(m.content, 1, 256)) AS message_preview " +
            "FROM conversations c LEFT JOIN conversation_messages m ON m.conversation_id = c.id " +
            "AND m.sort_index = (SELECT MAX(sort_index) FROM conversation_messages WHERE conversation_id = c.id) " +
            "ORDER BY c.updated_at DESC, c.id ASC"
    )
    suspend fun conversationIndex(): List<ConversationIndexRow>

    @Query("SELECT id, title, created_at, updated_at, is_pinned FROM conversations")
    suspend fun conversationIdentities(): List<ConversationIdentity>
    @Query("SELECT id, title, created_at, updated_at, is_pinned FROM conversations")
    fun conversationIdentitiesFlow(): kotlinx.coroutines.flow.Flow<List<ConversationIdentity>>

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId AND id = :messageId")
    suspend fun messageRow(conversationId: String, messageId: String): ConversationMessageEntity?

    @Query("SELECT id, sort_index, type FROM conversation_messages WHERE conversation_id = :conversationId AND id = :messageId")
    suspend fun branchPoint(conversationId: String, messageId: String): ConversationBranchPoint?

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId AND sort_index BETWEEN :start AND :end ORDER BY sort_index")
    suspend fun messageRowsRange(conversationId: String, start: Int, end: Int): List<ConversationMessageEntity>

    @Query("SELECT MIN(sort_index) FROM conversation_messages WHERE conversation_id = :conversationId AND type = 'user' AND sort_index > :after")
    suspend fun nextUserMessageIndex(conversationId: String, after: Int): Int?

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_messages WHERE conversation_id = :conversationId " +
        "AND sort_index > :after AND (:before IS NULL OR sort_index < :before) AND type IN ('assistant', 'tool', 'tool_summary'))")
    suspend fun hasReplyContinuation(conversationId: String, after: Int, before: Int?): Boolean

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId AND sort_index > :after AND sort_index <= :through " +
        "ORDER BY sort_index LIMIT :limit")
    suspend fun messagePrefixRowsPage(conversationId: String, after: Long, through: Int, limit: Int): List<ConversationMessageEntity>

    @Query("SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = :conversationId AND sort_index <= :through")
    suspend fun messagePrefixCount(conversationId: String, through: Int): Int

    @Query("UPDATE conversations SET title = :title, updated_at = :updatedAt, is_pinned = :isPinned WHERE id = :conversationId")
    suspend fun updateConversationTitle(conversationId: String, title: String, updatedAt: Long, isPinned: Boolean = false)

    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, roleplay_json, revisions_json, created_at, updated_at, model_id, is_pinned " +
            "FROM conversations WHERE id = :conversationId"
    )
    suspend fun conversationMetadataRow(conversationId: String): ConversationMetadata?

    @Transaction
    suspend fun conversation(conversationId: String): ConversationMetadata? =
        conversationMetadataRow(conversationId)?.let { restoreMetadata(it) }

    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, roleplay_json, revisions_json, created_at, updated_at, model_id, is_pinned " +
            "FROM conversations ORDER BY updated_at DESC"
    )
    suspend fun conversationMetadataRows(): List<ConversationMetadata>

    @Transaction
    suspend fun conversations(): List<ConversationMetadata> = conversationMetadataRows().map { restoreMetadata(it) }

    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, roleplay_json, revisions_json, created_at, updated_at, model_id, is_pinned " +
            "FROM conversations ORDER BY updated_at DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun conversationMetadataPage(limit: Int, offset: Int): List<ConversationMetadata>

    @Transaction
    suspend fun conversationsPage(limit: Int, offset: Int): List<ConversationMetadata> =
        conversationMetadataPage(limit, offset).map { restoreMetadata(it) }

    suspend fun restoreMetadata(row: ConversationMetadata) = row.copy(
        appliedRuntimeRunIdsJson = restoreText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
        roleplayJson = restoreText("conversations", row.id, "roleplay", row.roleplayJson),
        revisionsJson = restoreText("conversations", row.id, "revisions", row.revisionsJson),
    )

    @Query("SELECT roleplay_json FROM conversations WHERE id = :conversationId")
    suspend fun roleplayJsonRow(conversationId: String): String?

    @Transaction
    suspend fun roleplayJson(conversationId: String): String? = roleplayJsonRow(conversationId)?.let {
        restoreText("conversations", conversationId, "roleplay", it)
    }

    @Query("SELECT * FROM conversation_messages ORDER BY conversation_id ASC, sort_index ASC")
    suspend fun messageRows(): List<ConversationMessageEntity>

    @Transaction
    suspend fun messages(): List<ConversationMessageEntity> = messageRows().map { restoreMessage(it) }

    @Query("SELECT * FROM conversations ORDER BY updated_at ASC")
    suspend fun conversationEntityRows(): List<ConversationEntity>

    @Transaction
    suspend fun conversationEntities(): List<ConversationEntity> = conversationEntityRows().map { row ->
        restoreConversation(row)
    }

    @Query("SELECT * FROM conversations ORDER BY updated_at ASC, id ASC LIMIT :limit OFFSET :offset")
    suspend fun conversationEntityRowsPage(limit: Int, offset: Int): List<ConversationEntity>

    suspend fun restoreConversation(row: ConversationEntity) = row.copy(
        appliedRuntimeRunIdsJson = restoreText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
        roleplayJson = restoreText("conversations", row.id, "roleplay", row.roleplayJson),
        revisionsJson = restoreText("conversations", row.id, "revisions", row.revisionsJson),
    )

    @Query("SELECT * FROM conversation_messages ORDER BY conversation_id ASC, sort_index ASC LIMIT :limit OFFSET :offset")
    suspend fun allMessageRowsPage(limit: Int, offset: Int): List<ConversationMessageEntity>

    @Query("SELECT * FROM conversation_context_checkpoints ORDER BY conversation_id ASC")
    suspend fun contextCheckpointRows(): List<ConversationContextCheckpointEntity>

    @Transaction
    suspend fun contextCheckpoints(): List<ConversationContextCheckpointEntity> = contextCheckpointRows().map { restoreCheckpoint(it) }

    @Query("SELECT * FROM conversation_context_checkpoints ORDER BY conversation_id ASC LIMIT :limit OFFSET :offset")
    suspend fun contextCheckpointRowsPage(limit: Int, offset: Int): List<ConversationContextCheckpointEntity>

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId ORDER BY sort_index ASC LIMIT :limit OFFSET :offset")
    suspend fun messageRowsPage(conversationId: String, limit: Int, offset: Int): List<ConversationMessageEntity>

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId AND type IN ('user', 'assistant') " +
        "ORDER BY sort_index ASC LIMIT :limit OFFSET :offset")
    suspend fun legacyMessageRowsPage(conversationId: String, limit: Int, offset: Int): List<ConversationMessageEntity>

    @Transaction
    suspend fun messagesPage(conversationId: String, limit: Int, offset: Int): List<ConversationMessageEntity> =
        messageRowsPage(conversationId, limit, offset).map { restoreMessage(it) }

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId AND sort_index < :before " +
        "ORDER BY sort_index DESC LIMIT :limit")
    suspend fun messageRowsBefore(conversationId: String, before: Int, limit: Int): List<ConversationMessageEntity>

    @Transaction
    suspend fun messagesBefore(conversationId: String, before: Int, limit: Int): List<ConversationMessageEntity> =
        messageRowsBefore(conversationId, before, limit).asReversed().map { restoreMessage(it) }

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_messages WHERE conversation_id = :conversationId AND sort_index < :before)")
    suspend fun hasMessagesBefore(conversationId: String, before: Int): Boolean

    @Query("SELECT COALESCE(MAX(sort_index) + 1, 0) FROM conversation_messages WHERE conversation_id = :conversationId")
    suspend fun nextMessageIndex(conversationId: String): Int

    @Query("SELECT DISTINCT conversation_id FROM conversation_messages WHERE type = 'system_notice' AND content IN (:codes)")
    suspend fun searchSystemNotices(codes: List<String>): List<String>

    @Query("SELECT id, conversation_id, type, content, tool_name, arguments_summary, result_summary, tools_json " +
        "FROM conversation_messages ORDER BY conversation_id, sort_index")
    fun messageSearchCursor(): Cursor

    @Query("SELECT id, content FROM conversation_messages WHERE conversation_id = :conversationId AND id IN (:messageIds)")
    suspend fun messageContents(conversationId: String, messageIds: List<String>): List<ConversationMessageContent>

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_messages WHERE conversation_id = :conversationId AND id = :messageId)")
    suspend fun hasMessage(conversationId: String, messageId: String): Boolean

    @Query("UPDATE conversation_messages SET content = :content, is_edited = CASE WHEN type = 'user' THEN 1 ELSE is_edited END " +
        "WHERE conversation_id = :conversationId AND id = :messageId")
    suspend fun updateMessageContentRow(conversationId: String, messageId: String, content: String)

    @Transaction
    suspend fun updateMessageContent(conversationId: String, messageId: String, content: String): Boolean {
        if (!hasMessage(conversationId, messageId)) return false
        updateMessageContentRow(conversationId, messageId, storeText("conversation_messages", messageId, "content", content))
        return true
    }

    @Query("SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = :conversationId")
    suspend fun messageCount(conversationId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_messages WHERE conversation_id = :conversationId AND id = :messageId AND type = 'assistant')")
    suspend fun hasAssistantMessage(conversationId: String, messageId: String): Boolean

    @Query("SELECT * FROM conversation_context_checkpoints WHERE conversation_id = :conversationId")
    suspend fun contextCheckpointRow(conversationId: String): ConversationContextCheckpointEntity?

    @Query("UPDATE agent_text_chunks SET field = :toField WHERE owner_table = :table AND owner_id = :owner AND field = :fromField")
    suspend fun moveTextChunks(table: String, owner: String, fromField: String, toField: String)

    @Transaction
    suspend fun contextCheckpoint(conversationId: String): ConversationContextCheckpointEntity? =
        contextCheckpointRow(conversationId)?.let { restoreCheckpoint(it) }

    suspend fun restoreCheckpoint(row: ConversationContextCheckpointEntity) = row.copy(
        historyJson = restoreText("conversation_context_checkpoints", row.conversationId, "history", row.historyJson),
        journalJson = restoreText("conversation_context_checkpoints", row.conversationId, "journal", row.journalJson),
    )

    @Query("SELECT * FROM conversation_state WHERE id = :id")
    suspend fun state(id: String = ConversationStateEntity.SINGLETON_ID): ConversationStateEntity?

    @Upsert
    suspend fun insertConversationRow(conversation: ConversationEntity)

    @Transaction
    suspend fun insertConversations(conversations: List<ConversationEntity>) {
        conversations.forEach { row ->
            insertConversationRow(row.copy(
                appliedRuntimeRunIdsJson = storeText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
                roleplayJson = storeText("conversations", row.id, "roleplay", row.roleplayJson),
                revisionsJson = storeText("conversations", row.id, "revisions", row.revisionsJson),
            ))
        }
    }

    @Upsert
    suspend fun insertMessageRow(message: ConversationMessageEntity)

    @Transaction
    suspend fun insertMessages(messages: List<ConversationMessageEntity>) {
        messages.forEach { row ->
            insertMessageRow(row.copy(
                content = storeText("conversation_messages", row.id, "content", row.content),
                imagesJson = storeText("conversation_messages", row.id, "images", row.imagesJson),
            ))
        }
    }

    suspend fun restoreMessage(row: ConversationMessageEntity) = row.copy(
        content = restoreText("conversation_messages", row.id, "content", row.content),
        imagesJson = restoreText("conversation_messages", row.id, "images", row.imagesJson),
    )

    @Upsert
    suspend fun insertContextCheckpointRow(checkpoint: ConversationContextCheckpointEntity)

    @Query("SELECT * FROM conversation_context_checkpoints WHERE journal_json = history_json AND journal_json != ''")
    suspend fun identicalContextCheckpoints(): List<ConversationContextCheckpointEntity>

    /** 旧库中逐字节相同的 journal 分块可安全删除；差异记录保持原样。 */
    @Transaction
    suspend fun dedupeIdenticalContextCheckpoints(): Int {
        val rows = identicalContextCheckpoints()
        rows.forEach { row ->
            storeText("conversation_context_checkpoints", row.conversationId, "journal", "")
            insertContextCheckpointRow(row.copy(journalJson = ""))
        }
        return rows.size
    }

    @Transaction
    suspend fun insertContextCheckpoints(checkpoints: List<ConversationContextCheckpointEntity>) {
        checkpoints.forEach { row ->
            insertContextCheckpointRow(row.copy(
                historyJson = storeText("conversation_context_checkpoints", row.conversationId, "history", row.historyJson),
                journalJson = storeText("conversation_context_checkpoints", row.conversationId, "journal", row.journalJson),
            ))
        }
    }

    @Transaction
    suspend fun updateContextCheckpoint(conversationId: String, historyJson: String?, journalJson: String?) {
        val previous = contextCheckpointRow(conversationId)
            ?: ConversationContextCheckpointEntity(conversationId, "[]")
        // 旧 checkpoint 可能把相同内容只保存在 history 字段。必须先读取旧 history，
        // 因为下面写入新 history 会替换同名的分块记录。
        val previousJournal = runCatching {
            if (previous.journalJson.isNotBlank()) {
                restoreText("conversation_context_checkpoints", conversationId, "journal", previous.journalJson)
            } else {
                restoreText("conversation_context_checkpoints", conversationId, "history", previous.historyJson)
            }
        }.getOrNull()
        // 如果旧 journal 与 history 共用一份内容，压缩 history 前可直接把整组分块改挂到 journal，
        // 无需复制、解码后重写完整历史，也避免大对话在压缩时产生瞬时双份峰值。
        val reusePreviousHistoryChunks = historyJson != null &&
            journalJson == null &&
            previous.journalJson.isBlank() &&
            previous.historyJson.startsWith(ChunkedTextDao.REFERENCE_PREFIX) &&
            previousJournal != null && previousJournal != historyJson
        if (reusePreviousHistoryChunks) {
            moveTextChunks(
                "conversation_context_checkpoints",
                conversationId,
                "history",
                "journal",
            )
        }
        val storedHistory = historyJson?.let {
            storeText("conversation_context_checkpoints", conversationId, "history", it)
        } ?: previous.historyJson
        val historyText = runCatching {
            restoreText("conversation_context_checkpoints", conversationId, "history", storedHistory)
        }.getOrNull()
        // history 与 journal 在多数回合内容逐字节相同。journal 为空时读取端会回退到 history，
        // 因而同内容只保留一份分块，避免 checkpoint 每次增长都写两份大字段。
        val storedJournal = when {
            journalJson != null && historyText != null && journalJson == historyText ->
                storeText("conversation_context_checkpoints", conversationId, "journal", "")
            journalJson != null ->
                storeText("conversation_context_checkpoints", conversationId, "journal", journalJson)
            historyText != null -> {
                // 相同内容会只保存 history，并让 journal_json 留空。上下文压缩改变 history 时，
                // 必须先把先前的 history 物化为 journal，否则未变化的完整过程会被压缩摘要覆盖。
                when {
                    previousJournal == null -> previous.journalJson
                    previousJournal == historyText -> storeText("conversation_context_checkpoints", conversationId, "journal", "")
                    previous.journalJson.isNotBlank() -> previous.journalJson
                    reusePreviousHistoryChunks -> previous.historyJson
                    else -> storeText("conversation_context_checkpoints", conversationId, "journal", previousJournal)
                }
            }
            else -> previous.journalJson
        }
        insertContextCheckpointRow(previous.copy(historyJson = storedHistory, journalJson = storedJournal))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: ConversationStateEntity)

    @Query("DELETE FROM conversations")
    suspend fun deleteConversations()

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM conversation_messages")
    suspend fun deleteMessages()

    @Query("DELETE FROM conversation_messages WHERE conversation_id = :conversationId AND sort_index >= :fromIndex")
    suspend fun deleteMessagesFrom(conversationId: String, fromIndex: Int)

    @Query("DELETE FROM conversation_context_checkpoints")
    suspend fun deleteContextCheckpoints()

    @Query("DELETE FROM conversation_state")
    suspend fun deleteState()

    @Transaction
    suspend fun replaceAll(
        conversations: List<ConversationEntity>,
        messages: List<ConversationMessageEntity>,
        contextCheckpoints: List<ConversationContextCheckpointEntity> = emptyList(),
        state: ConversationStateEntity?,
    ) {
        deleteMessages()
        deleteContextCheckpoints()
        deleteConversations()
        deleteState()
        insertConversations(conversations)
        insertContextCheckpoints(contextCheckpoints)
        insertMessages(messages)
        state?.let { insertState(it) }
    }
}
