package io.github.mangi.eta.data.db

import androidx.room.*
import kotlinx.serialization.Serializable

/** 与分支一起提交的配置快照；目标库自己的回执使跨库重试不会覆盖用户后续修改。 */
@Serializable
@Entity(tableName = "conversation_branch_setups", foreignKeys = [
    ForeignKey(entity = ConversationBranchEntity::class, parentColumns = ["conversation_id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["setup_id"], unique = true)])
internal data class ConversationBranchSetupEntity(
    @PrimaryKey @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "setup_id") val setupId: String,
    @ColumnInfo(name = "folder_id") val folderId: String? = null,
    @ColumnInfo(name = "context_window") val contextWindow: Int? = null,
    @ColumnInfo(name = "project_id") val projectId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "completion_mode") val completionMode: String? = null,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
)

/** 同一 schema 分别用于会话扩展库和项目库，回执与该库的实际写入同事务。 */
@Entity(tableName = "branch_setup_receipts", indices = [Index("conversation_id")])
internal data class BranchSetupReceiptEntity(
    @PrimaryKey @ColumnInfo(name = "setup_id") val setupId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

internal fun createBranchSetupReceipts(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE branch_setup_receipts (setup_id TEXT NOT NULL PRIMARY KEY, conversation_id TEXT NOT NULL, created_at INTEGER NOT NULL)")
    db.execSQL("CREATE INDEX index_branch_setup_receipts_conversation_id ON branch_setup_receipts(conversation_id)")
}
