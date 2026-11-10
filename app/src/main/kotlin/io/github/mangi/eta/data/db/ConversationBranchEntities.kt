package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 分叉点校验只读身份与类型，不提前装载正文/图片或工具参数。 */
internal data class ConversationBranchPoint(
    val id: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    val type: String,
)

/** 分支的真实父关系与来源身份分开保存；删除父会话不会删除后代或伪造新父节点。 */
@Serializable
@Entity(tableName = "conversation_branches", foreignKeys = [
    ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["parent_conversation_id"], onDelete = ForeignKey.SET_NULL),
], indices = [Index("family_id"), Index("parent_conversation_id")])
internal data class ConversationBranchEntity(
    @PrimaryKey @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "family_id") val familyId: String,
    @ColumnInfo(name = "parent_conversation_id") val parentConversationId: String? = null,
    @ColumnInfo(name = "source_conversation_id") val sourceConversationId: String? = null,
    @ColumnInfo(name = "source_message_id") val sourceMessageId: String? = null,
    @ColumnInfo(name = "source_sort_index") val sourceSortIndex: Int? = null,
    @ColumnInfo(name = "history_mode") val historyMode: String = "journal",
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Serializable
@Entity(tableName = "conversation_message_origins", foreignKeys = [
    ForeignKey(entity = ConversationMessageEntity::class, parentColumns = ["id"], childColumns = ["message_id"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
], indices = [Index("conversation_id")])
internal data class ConversationMessageOriginEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "source_conversation_id") val sourceConversationId: String,
    @ColumnInfo(name = "source_message_id") val sourceMessageId: String,
    @ColumnInfo(name = "source_sort_index") val sourceSortIndex: Int,
)
