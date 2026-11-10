package io.github.mangi.eta.fork.meeting

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

internal object MeetingRoomStatus {
    const val IDLE = "idle"
    const val RUNNING = "running"
    const val WAITING_FOR_USER = "waiting_for_user"
    const val FAILED = "failed"
}

internal object MeetingTaskStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

internal object MeetingAuthorType {
    const val USER = "user"
    const val HOST = "host"
    const val AGENT = "agent"
    const val SYSTEM = "system"
}

@Entity(tableName = "meeting_rooms")
internal data class MeetingRoomEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "host_profile") val hostProfile: String,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long? = null,
    @ColumnInfo(name = "context_window_override") val contextWindowOverride: Int? = null,
)

@Entity(
    tableName = "meeting_participants",
    primaryKeys = ["room_id", "profile_name"],
    indices = [Index("room_id")],
)
internal data class MeetingParticipantEntity(
    @ColumnInfo(name = "room_id") val roomId: String,
    @ColumnInfo(name = "profile_name") val profileName: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "profile_summary") val profileSummary: String,
    @ColumnInfo(name = "provider_id") val providerId: String? = null,
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "is_host") val isHost: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    val enabled: Boolean = true,
)

@Entity(
    tableName = "meeting_messages",
    indices = [
        Index("room_id"),
        Index(value = ["room_id", "sort_index"], unique = true),
    ],
)
internal data class MeetingMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "room_id") val roomId: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    @ColumnInfo(name = "author_type") val authorType: String,
    @ColumnInfo(name = "author_profile") val authorProfile: String? = null,
    @ColumnInfo(name = "author_name") val authorName: String,
    val content: String,
    @ColumnInfo(name = "mentions_json") val mentionsJson: String = "[]",
    @ColumnInfo(name = "task_id") val taskId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "meeting_tasks",
    indices = [Index("room_id")],
)
internal data class MeetingTaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "room_id") val roomId: String,
    @ColumnInfo(name = "participant_profile") val participantProfile: String,
    @ColumnInfo(name = "instruction") val instruction: String,
    val status: String,
    @ColumnInfo(name = "result_message_id") val resultMessageId: String? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "meeting_events",
    indices = [Index("room_id")],
)
internal data class MeetingEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "room_id") val roomId: String,
    @ColumnInfo(name = "task_id") val taskId: String? = null,
    val type: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String = "{}",
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Compacted model context for one participant; the visible meeting transcript remains unchanged. */
@Entity(
    tableName = "meeting_participant_contexts",
    primaryKeys = ["room_id", "profile_name"],
    indices = [Index("room_id")],
)
internal data class MeetingParticipantContextEntity(
    @ColumnInfo(name = "room_id") val roomId: String,
    @ColumnInfo(name = "profile_name") val profileName: String,
    @ColumnInfo(name = "snapshot_json") val snapshotJson: String,
    @ColumnInfo(name = "last_message_sort_index") val lastMessageSortIndex: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

internal data class MeetingRoomSnapshot(
    val room: MeetingRoomEntity,
    val participants: List<MeetingParticipantEntity>,
    val messages: List<MeetingMessageEntity>,
    val tasks: List<MeetingTaskEntity>,
    val timelineEvents: List<MeetingEventEntity> = emptyList(),
    val messageAttachments: Map<String, io.github.mangi.eta.data.repository.ConversationAttachments> = emptyMap(),
)

internal data class MeetingRoomSummaryRow(
    val id: String,
    val title: String,
    @ColumnInfo(name = "host_profile") val hostProfile: String,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "participant_count") val participantCount: Int,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long? = null,
    @ColumnInfo(name = "context_window_override") val contextWindowOverride: Int? = null,
)
