package io.github.mangi.eta.fork.meeting

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface MeetingDao {
    @Query("SELECT r.id, r.title, r.archived_at AS archivedAt, " +
        "(SELECT COUNT(*) FROM meeting_messages WHERE room_id = r.id) AS messageCount, " +
        "COALESCE((SELECT substr(content, 1, 240) FROM meeting_messages WHERE room_id = r.id ORDER BY sort_index DESC LIMIT 1), '') AS preview " +
        "FROM meeting_rooms r WHERE r.archived_at IS NOT NULL AND " +
        "(:before IS NULL OR r.archived_at < :before OR (:includeSameTime AND r.archived_at = :before AND r.id > :afterId)) AND " +
        "(:query = '' OR instr(lower(r.title), lower(:query)) > 0 OR EXISTS(" +
        "SELECT 1 FROM meeting_messages WHERE room_id = r.id AND instr(lower(content), lower(:query)) > 0)) " +
        "ORDER BY r.archived_at DESC, r.id LIMIT :limit")
    suspend fun archivedPage(before: Long?, afterId: String, includeSameTime: Boolean, query: String, limit: Int): List<ArchivedMeetingRow>

    @Query("SELECT * FROM meeting_rooms ORDER BY (pinned_at IS NULL), pinned_at DESC, updated_at DESC")
    fun roomsFlow(): Flow<List<MeetingRoomEntity>>

    @Query(
        "SELECT meeting_rooms.id, meeting_rooms.title, meeting_rooms.host_profile, " +
            "meeting_rooms.status, meeting_rooms.created_at, meeting_rooms.updated_at, " +
            "meeting_rooms.archived_at, meeting_rooms.pinned_at, meeting_rooms.context_window_override, " +
            "COUNT(meeting_participants.profile_name) AS participant_count " +
            "FROM meeting_rooms LEFT JOIN meeting_participants " +
            "ON meeting_participants.room_id = meeting_rooms.id AND meeting_participants.enabled = 1 " +
            "GROUP BY meeting_rooms.id ORDER BY (meeting_rooms.pinned_at IS NULL), meeting_rooms.pinned_at DESC, meeting_rooms.updated_at DESC"
    )
    fun roomSummariesFlow(): Flow<List<MeetingRoomSummaryRow>>

    @Query("SELECT * FROM meeting_rooms WHERE id = :roomId")
    fun roomFlow(roomId: String): Flow<MeetingRoomEntity?>

    @Query("SELECT * FROM meeting_rooms WHERE id = :roomId")
    suspend fun room(roomId: String): MeetingRoomEntity?

    @Query(
        "SELECT * FROM meeting_rooms WHERE status = 'running' OR id IN (" +
            "SELECT room_id FROM meeting_tasks " +
            "WHERE status NOT IN ('completed', 'failed', 'cancelled')) " +
            "ORDER BY updated_at, id",
    )
    suspend fun recoverableRooms(): List<MeetingRoomEntity>

    @Query(
        "SELECT * FROM meeting_tasks WHERE room_id = :roomId " +
            "AND status NOT IN ('completed', 'failed', 'cancelled') ORDER BY created_at, id",
    )
    suspend fun unfinishedTasks(roomId: String): List<MeetingTaskEntity>

    // The room UI still shows disabled agents so their process and history remain inspectable.
    // Dispatch queries filter enabled participants separately.
    @Query("SELECT * FROM meeting_participants WHERE room_id = :roomId ORDER BY is_host DESC, sort_order ASC")
    fun participantsFlow(roomId: String): Flow<List<MeetingParticipantEntity>>

    @Query("SELECT * FROM meeting_participants WHERE room_id = :roomId ORDER BY is_host DESC, sort_order ASC")
    suspend fun participants(roomId: String): List<MeetingParticipantEntity>

    @Query("SELECT * FROM meeting_messages WHERE room_id = :roomId ORDER BY sort_index ASC")
    fun messagesFlow(roomId: String): Flow<List<MeetingMessageEntity>>

    @Query("SELECT * FROM meeting_messages WHERE room_id = :roomId ORDER BY sort_index ASC")
    suspend fun messages(roomId: String): List<MeetingMessageEntity>

    @Query("SELECT * FROM meeting_messages WHERE id = :messageId")
    suspend fun message(messageId: String): MeetingMessageEntity?

    @Query("SELECT * FROM meeting_events WHERE room_id = :roomId AND type = :type ORDER BY id DESC LIMIT 1")
    suspend fun latestEvent(roomId: String, type: String): MeetingEventEntity?

    @Query("SELECT * FROM meeting_events WHERE room_id = :roomId AND type LIKE 'workflow_checkpoint:%' ORDER BY id")
    suspend fun workflowCheckpoints(roomId: String): List<MeetingEventEntity>

    @Query("SELECT * FROM meeting_events WHERE type LIKE 'pending_turn:%' ORDER BY id")
    suspend fun pendingTurnEvents(): List<MeetingEventEntity>

    @Query("DELETE FROM meeting_events WHERE room_id = :roomId AND type = :type")
    suspend fun deleteEventType(roomId: String, type: String)

    @Query("SELECT * FROM meeting_events WHERE room_id = :roomId AND type LIKE 'accessibility_wait:%' ORDER BY id ASC")
    suspend fun accessibilityWaitEvents(roomId: String): List<MeetingEventEntity>

    // Only the most recent phase for each visible card; do not load the whole event journal into the UI.
    @Query("SELECT * FROM meeting_events WHERE room_id = :roomId " +
        "AND (type LIKE 'accessibility_wait:%' OR type LIKE 'approval_wait:%' OR type LIKE 'tool_activity:%' OR type LIKE 'stream_activity:%' OR type LIKE 'message_attachments:%') " +
        "AND id IN (SELECT MAX(id) FROM meeting_events WHERE room_id = :roomId " +
        "AND (type LIKE 'accessibility_wait:%' OR type LIKE 'approval_wait:%' OR type LIKE 'tool_activity:%' OR type LIKE 'stream_activity:%' OR type LIKE 'message_attachments:%') GROUP BY type) ORDER BY id")
    fun timelineEventsFlow(roomId: String): Flow<List<MeetingEventEntity>>

    @Query("SELECT * FROM meeting_events WHERE room_id = :roomId " +
        "AND (type LIKE 'accessibility_wait:%' OR type LIKE 'approval_wait:%' OR type LIKE 'tool_activity:%' OR type LIKE 'stream_activity:%' OR type LIKE 'message_attachments:%') " +
        "AND id IN (SELECT MAX(id) FROM meeting_events WHERE room_id = :roomId " +
        "AND (type LIKE 'accessibility_wait:%' OR type LIKE 'approval_wait:%' OR type LIKE 'tool_activity:%' OR type LIKE 'stream_activity:%' OR type LIKE 'message_attachments:%') GROUP BY type) ORDER BY id")
    suspend fun timelineEvents(roomId: String): List<MeetingEventEntity>

    @Query("SELECT * FROM meeting_events WHERE room_id = :roomId AND type LIKE 'tool_activity:%' ORDER BY id")
    suspend fun toolActivityEvents(roomId: String): List<MeetingEventEntity>

    @Query("SELECT * FROM meeting_events WHERE room_id = :roomId AND type LIKE 'stream_activity:%' ORDER BY id")
    suspend fun streamActivityEvents(roomId: String): List<MeetingEventEntity>

    @Query("SELECT * FROM meeting_events WHERE room_id = :roomId AND type LIKE 'message_attachments:%' ORDER BY id")
    suspend fun messageAttachmentEvents(roomId: String): List<MeetingEventEntity>

    @Query("SELECT * FROM meeting_tasks WHERE room_id = :roomId ORDER BY created_at ASC")
    fun tasksFlow(roomId: String): Flow<List<MeetingTaskEntity>>

    @Query("SELECT * FROM meeting_tasks WHERE room_id = :roomId ORDER BY created_at ASC")
    suspend fun tasks(roomId: String): List<MeetingTaskEntity>

    @Query("SELECT COALESCE(MAX(sort_index), -1) FROM meeting_messages WHERE room_id = :roomId")
    suspend fun maxMessageSortIndex(roomId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoom(room: MeetingRoomEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParticipants(participants: List<MeetingParticipantEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MeetingMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: MeetingTaskEntity)

    @Insert
    suspend fun insertEvent(event: MeetingEventEntity)

    @Update
    suspend fun updateEvent(event: MeetingEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParticipantContext(context: MeetingParticipantContextEntity)

    @Query(
        "SELECT * FROM meeting_participant_contexts WHERE room_id = :roomId AND profile_name = :profileName",
    )
    suspend fun participantContext(roomId: String, profileName: String): MeetingParticipantContextEntity?

    @Update
    suspend fun updateRoom(room: MeetingRoomEntity)

    @Update
    suspend fun updateTask(task: MeetingTaskEntity)

    @Query("DELETE FROM meeting_rooms WHERE id = :roomId")
    suspend fun deleteRoom(roomId: String)

    @Query("DELETE FROM meeting_participants WHERE room_id = :roomId")
    suspend fun deleteParticipants(roomId: String)

    @Query("DELETE FROM meeting_messages WHERE room_id = :roomId")
    suspend fun deleteMessages(roomId: String)

    @Query("DELETE FROM meeting_tasks WHERE room_id = :roomId")
    suspend fun deleteTasks(roomId: String)

    @Query("DELETE FROM meeting_events WHERE room_id = :roomId")
    suspend fun deleteEvents(roomId: String)

    @Query("DELETE FROM meeting_participant_contexts WHERE room_id = :roomId")
    suspend fun deleteParticipantContexts(roomId: String)
}
