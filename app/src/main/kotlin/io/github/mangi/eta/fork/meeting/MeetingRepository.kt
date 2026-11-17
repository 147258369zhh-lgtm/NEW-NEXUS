package io.github.mangi.eta.fork.meeting

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase
import io.github.mangi.eta.agent.accessibility.textResource
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.data.repository.ConversationAttachmentDrafts
import io.github.mangi.eta.data.repository.ConversationAttachments
import io.github.mangi.eta.data.repository.ConversationDraftRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import org.json.JSONArray
import org.json.JSONObject

internal data class PlannedMeetingParticipant(
    val profileName: String, val displayName: String, val summary: String,
    val providerId: String, val modelId: String, val modelIdentity: String, val modelReason: String,
)
internal data class PlannedMeetingTask(val profileName: String, val instruction: String, val dependencies: List<String>)
internal data class CommittedMeetingPlan(val participants: List<MeetingParticipantEntity>, val tasks: List<MeetingTaskEntity>)

internal object MeetingRepository {
    val lifecycleMutex = Mutex()
    /** Save acceptance before the composer can clear input. Recovery never runs this work again. */
    suspend fun persistPendingTurn(context: Context, roomId: String, turn: QueuedMeetingTurn): QueuedMeetingTurn {
        val drafts = ConversationAttachmentDrafts.get(context)
        val owned = turn.copy(attachments = drafts.materialize(turn.attachments))
        val db = MeetingDatabase.get(context)
        db.withTransaction {
            checkNotNull(db.meetingDao().room(roomId)) { "会议室不存在" }
            appendEvent(context, roomId, null, "pending_turn:${turn.id}",
                JSONObject().put("text", owned.text).put("attachments", drafts.encode(owned.attachments)))
        }
        return owned
    }

    suspend fun forgetPendingTurn(context: Context, roomId: String, id: String) =
        MeetingDatabase.get(context).meetingDao().deleteEventType(roomId, "pending_turn:$id")

    private suspend fun recoverPendingTurnInputs(context: Context) {
        val drafts = ConversationAttachmentDrafts.get(context)
        MeetingDatabase.get(context).meetingDao().pendingTurnEvents().groupBy { it.roomId }.forEach { (room, events) ->
            if (MeetingRunCoordinator.isRunning(room)) return@forEach
            val turns = events.map { event ->
                val payload = JSONObject(event.payloadJson)
                QueuedMeetingTurn(payload.getString("text"), drafts.decode(payload.getString("attachments")),
                    event.type.removePrefix("pending_turn:"))
            }
            retainUnstartedTurns(context, room, turns)
        }
    }

    /** Stopped follow-ups remain visible with their attachments, but are never silently executed. */
    suspend fun retainUnstartedTurns(context: Context, roomId: String, turns: List<QueuedMeetingTurn>) {
        if (turns.isEmpty()) return
        val dao = MeetingDatabase.get(context).meetingDao()
        if (dao.room(roomId) == null) return
        var retained = 0
        turns.forEach { turn ->
            if (dao.message(turn.id) != null) {
                forgetPendingTurn(context, roomId, turn.id)
                return@forEach
            }
            appendMessage(context, roomId, MeetingAuthorType.USER, null, "You",
                AgentFileReferencePromptCodec.format(turn.text, turn.attachments.files.map { it.reference }),
                attachments = turn.attachments, messageId = turn.id)
            retained++
        }
        if (retained > 0) appendMessage(context, roomId, MeetingAuthorType.SYSTEM, null, "Meeting",
            "以上 $retained 条排队消息尚未执行，内容和附件已保留。请核对后重新发送需要继续的任务。")
    }

    /**
     * A previous process cannot prove that an interrupted participant run produced no side effects.
     * Reopen the room for an explicit user retry instead of silently replaying the turn.
     */
    suspend fun recoverInterruptedRuns(context: Context): Int = lifecycleMutex.withLock {
        val dao = MeetingDatabase.get(context).meetingDao()
        // A room can already be failed/waiting while one of its tasks retained a queued,
        // running or unknown status after process death. The task itself is authoritative for
        // recovery ownership; room status alone must not hide that unresolved work.
        val rooms = dao.recoverableRooms()
        var recovered = 0
        rooms.forEach { room ->
            if (MeetingRunCoordinator.isRunning(room.id)) return@forEach
            dao.streamActivityEvents(room.id).forEach { event ->
                val message = dao.message(event.type.removePrefix(MeetingStreamActivity.EVENT_PREFIX))
                val activity = message?.let { MeetingStreamActivity.decode(event.payloadJson, it.content) }
                if (activity?.phase == MeetingStreamPhase.STREAMING && activity.eventType == event.type) {
                    recordStreamActivity(context, room.id, message.authorProfile.orEmpty(), message.authorName,
                        JSONObject(event.payloadJson).optBoolean("is_host"), message.taskId,
                        activity.copy(phase = MeetingStreamPhase.INTERRUPTED))
                }
            }
            dao.toolActivityEvents(room.id).groupBy { it.type }.values.forEach { history ->
                val event = history.last()
                val tool = MeetingToolActivity.decode(event.payloadJson)
                if (tool?.phase == MeetingToolPhase.RUNNING && tool.eventType == event.type) {
                    val payload = JSONObject(event.payloadJson)
                    val message = dao.message(tool.messageId)
                    recordToolActivity(context, room.id, message?.authorProfile.orEmpty(), message?.authorName.orEmpty(),
                        payload.optBoolean("is_host"), message?.taskId, tool.copy(phase = MeetingToolPhase.INTERRUPTED))
                }
            }
            dao.accessibilityWaitEvents(room.id).groupBy { it.type }.values.forEach { history ->
                val latest = JSONObject(history.last().payloadJson)
                if (latest.optString("phase") == AccessibilityWaitPhase.WAITING.wireValue) {
                    recordAccessibilityWait(context, room.id, latest.optString("profile"), latest.optString("author"),
                        latest.optString("run_id"), AgentEvent.AccessibilityWaiting(latest.optInt("outage"), AccessibilityWaitPhase.CANCELLED), history.last().taskId)
                }
            }
            dao.unfinishedTasks(room.id).forEach { task ->
                updateTask(
                    context = context,
                    task = task,
                    status = MeetingTaskStatus.FAILED,
                    errorMessage = "应用进程中断，未自动重放可能产生外部副作用的任务",
                )
            }
            dao.workflowCheckpoints(room.id).groupBy { event -> JSONObject(event.payloadJson).optString("run_id") }
                .filterKeys(String::isNotBlank).forEach { (runId, checkpoints) ->
                    val terminal = checkpoints.any { event ->
                        val body = JSONObject(event.payloadJson)
                        body.optString("stage_id") == "run" && body.optString("state") in setOf("completed", "partial", "interrupted", "cancelled", "failed")
                    }
                    if (!terminal) appendEvent(context, room.id, null, "workflow_checkpoint:$runId:run:1:interrupted",
                        JSONObject().put("run_id", runId).put("stage_id", "run").put("state", "interrupted")
                            .put("attempt", 1).put("retry", "user_required").put("committed_outputs_preserved", true))
                }
            appendMessage(
                context = context,
                roomId = room.id,
                authorType = MeetingAuthorType.SYSTEM,
                authorProfile = null,
                authorName = "Meeting",
                content = "上一轮在应用退出时中断。为避免重复执行外部操作，本轮没有自动重放；请检查已有结果后明确再次发送。",
            )
            appendEvent(
                context = context,
                roomId = room.id,
                taskId = null,
                type = "interrupted_run_recovered",
                payload = JSONObject().put("retry", "user_required"),
            )
            dao.updateRoom(
                room.copy(
                    status = MeetingRoomStatus.WAITING_FOR_USER,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            recovered++
        }
        recoverPendingTurnInputs(context)
        recovered
    }

    fun rooms(context: Context): Flow<List<MeetingRoomSummaryRow>> =
        MeetingDatabase.get(context).meetingDao().roomSummariesFlow()

    val activeRoomIds: Flow<Set<String>> get() = MeetingRunCoordinator.activeRoomIds

    suspend fun hostOverride(context: Context, roomId: String): MeetingHostOverride? =
        MeetingDatabase.get(context).meetingDao().latestEvent(roomId, "room_created")?.let { event ->
            MeetingHostOverride.decode(JSONObject(event.payloadJson).optJSONObject("host_override"))
        }

    suspend fun firstActiveRoomId(context: Context): String? =
        MeetingDatabase.get(context).meetingDao().roomsFlow().first()
            .firstOrNull { it.archivedAt == null }
            ?.id

    fun room(context: Context, roomId: String): Flow<MeetingRoomEntity?> =
        MeetingDatabase.get(context).meetingDao().roomFlow(roomId)

    fun snapshot(context: Context, roomId: String): Flow<MeetingRoomSnapshot?> {
        val db = MeetingDatabase.get(context)
        val dao = db.meetingDao()
        // Message text and its stream/tool metadata must come from the same committed state.
        return kotlinx.coroutines.flow.flow {
            var cachedAttachmentEvents: List<MeetingEventEntity> = emptyList()
            var cachedAttachments: Map<String, ConversationAttachments> = emptyMap()
            db.invalidationTracker.createFlow("meeting_rooms", "meeting_participants", "meeting_messages", "meeting_tasks", "meeting_events")
            .collect {
                val snapshot = db.withTransaction {
                val room = dao.room(roomId) ?: return@withTransaction null
                val timelineEvents = dao.timelineEvents(roomId)
                val attachmentEvents = timelineEvents.filter { it.type.startsWith("message_attachments:") }
                if (attachmentEvents != cachedAttachmentEvents) {
                    cachedAttachments = decodeMessageAttachments(context, attachmentEvents)
                    cachedAttachmentEvents = attachmentEvents
                }
                MeetingRoomSnapshot(
                    room = room,
                    participants = dao.participants(roomId),
                    messages = dao.messages(roomId),
                    tasks = dao.tasks(roomId),
                    timelineEvents = timelineEvents,
                    messageAttachments = cachedAttachments,
                )
                }
                emit(snapshot)
            }
        }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    suspend fun createRoom(
        context: Context,
        title: String,
        host: MeetingParticipantDraft,
        participants: List<MeetingParticipantDraft>,
        now: Long = System.currentTimeMillis(),
        templateId: String? = null,
        templateVersion: Int = 1,
        hostOverride: MeetingHostOverride? = null,
    ): String {
        require(title.isNotBlank()) { "会议室名称不能为空" }
        require(host.profileName.isNotBlank()) { "主持人不能为空" }
        require(hostOverride?.validate() != false) { "临时主持名称或职责无效" }
        val workflow = templateId?.let { id ->
            requireNotNull(MeetingWorkflowCatalog.find(id)) { "会议用途不可用" }
                .also { require(it.version == templateVersion) { "会议用途版本已变化，请重新选择" } }
        }
        val roomId = UUID.randomUUID().toString()
        val normalizedParticipants = buildList {
            add(host.copy(isHost = true))
            participants
                .filter { it.profileName.isNotBlank() && it.profileName != host.profileName }
                .forEach { add(it.copy(isHost = false)) }
        }.distinctBy { it.profileName }
        val database = MeetingDatabase.get(context)
        database.withTransaction {
            val dao = database.meetingDao()
            dao.insertRoom(
                MeetingRoomEntity(
                    id = roomId,
                    title = title.trim(),
                    hostProfile = host.profileName,
                    status = MeetingRoomStatus.IDLE,
                    createdAt = now,
                    updatedAt = now,
                    workflowSnapshotJson = workflow?.encode(),
                ),
            )
            dao.insertParticipants(
                normalizedParticipants.mapIndexed { index, draft ->
                    MeetingParticipantEntity(
                        roomId = roomId,
                        profileName = draft.profileName,
                        displayName = draft.displayName.trim().ifBlank { draft.profileName },
                        profileSummary = draft.profileSummary.trim(),
                        providerId = draft.providerId?.trim()?.takeIf(String::isNotEmpty),
                        modelId = draft.modelId?.trim()?.takeIf(String::isNotEmpty),
                        isHost = index == 0,
                        sortOrder = index,
                        enabled = true,
                    )
                },
            )
            dao.insertEvent(
                MeetingEventEntity(
                    roomId = roomId,
                    type = "room_created",
                    payloadJson = JSONObject()
                        .put("host", host.profileName)
                        .put("participants", JSONArray(normalizedParticipants.map { it.profileName }))
                        .apply { hostOverride?.let { put("host_override", it.encode()) } }
                        .toString(),
                    createdAt = now,
                ),
            )
        }
        return roomId
    }

    /** 将 Runtime 等待阶段呈现在原会议消息流；相同断线更新原位置，不挤出新对话。 */
    suspend fun recordAccessibilityWait(
        context: Context, roomId: String, profile: String, author: String, runId: String,
        event: AgentEvent.AccessibilityWaiting, taskId: String? = null,
    ) {
        val database = MeetingDatabase.get(context)
        database.withTransaction {
            val dao = database.meetingDao()
            val room = dao.room(roomId) ?: return@withTransaction
            if (event.phase == AccessibilityWaitPhase.WAITING && room.status != MeetingRoomStatus.RUNNING) return@withTransaction
            val messageId = "accessibility-$runId-${event.outage}"
            val type = "accessibility_wait:$messageId"
            val previousEvent = dao.latestEvent(roomId, type)
            val previous = previousEvent?.let { JSONObject(it.payloadJson).optString("phase") }
            if (previous == event.phase.wireValue || (previous != null && previous != "waiting")) return@withTransaction
            val existing = dao.message(messageId)
            val now = System.currentTimeMillis()
            val content = context.getString(io.github.mangi.eta.R.string.accessibility_wait_title) + "\n" + context.getString(event.phase.textResource())
            dao.insertMessage(existing?.copy(content = content, taskId = taskId ?: existing.taskId) ?: MeetingMessageEntity(
                id = messageId, roomId = roomId, sortIndex = dao.maxMessageSortIndex(roomId) + 1,
                authorType = MeetingAuthorType.SYSTEM, authorProfile = profile, authorName = author,
                content = content, taskId = taskId, createdAt = now,
            ))
            dao.insertEvent(MeetingEventEntity(roomId = roomId, taskId = taskId ?: previousEvent?.taskId, type = type,
                payloadJson = JSONObject().put("run_id", runId).put("profile", profile).put("author", author)
                    .put("outage", event.outage).put("phase", event.phase.wireValue).toString(), createdAt = now))
            dao.updateRoom(room.copy(updatedAt = now))
        }
    }

    /** A tool lifecycle updates one readable transcript position and a small typed journal. */
    suspend fun recordToolActivity(
        context: Context, roomId: String, profile: String, author: String,
        isHost: Boolean, taskId: String?, activity: MeetingToolActivity,
    ) {
        val tool = activity.durable()
        val database = MeetingDatabase.get(context)
        database.withTransaction {
            val dao = database.meetingDao()
            val room = dao.room(roomId) ?: return@withTransaction
            val existing = dao.message(tool.messageId)
            if (existing != null && (existing.roomId != roomId || existing.authorProfile != profile)) return@withTransaction
            if ((existing == null || tool.phase == MeetingToolPhase.RUNNING) && room.status != MeetingRoomStatus.RUNNING) return@withTransaction
            val previous = dao.latestEvent(roomId, tool.eventType)?.let { MeetingToolActivity.decode(it.payloadJson) }
            if (previous == tool || previous?.phase?.terminal == true) return@withTransaction
            if (previous != null && previous.name != tool.name) return@withTransaction
            val phaseLabel = context.getString(when (tool.phase) {
                MeetingToolPhase.RUNNING -> R.string.tool_status_running
                MeetingToolPhase.SUCCESS -> R.string.tool_status_success
                MeetingToolPhase.FAILED -> R.string.tool_status_failed
                MeetingToolPhase.UNKNOWN, MeetingToolPhase.INTERRUPTED -> R.string.tool_status_unknown
            })
            val content = buildString {
                append(tool.name).append(" · ").append(phaseLabel)
                if (AgentSensitiveToolPolicy.isSensitive(tool.name)) append("\n").append(context.getString(R.string.meeting_tool_sensitive_hidden))
                tool.arguments.takeIf(String::isNotBlank)?.let { append("\n").append(it) }
                tool.command?.takeIf(String::isNotBlank)?.let { append("\n").append(it) }
                tool.result?.takeIf(String::isNotBlank)?.let { append("\n").append(it) }
                if (tool.phase == MeetingToolPhase.INTERRUPTED) append("\n").append(context.getString(R.string.meeting_tool_interrupted))
            }
            val now = System.currentTimeMillis()
            dao.insertMessage(existing?.copy(content = content) ?: MeetingMessageEntity(
                id = tool.messageId, roomId = roomId, sortIndex = dao.maxMessageSortIndex(roomId) + 1,
                authorType = MeetingAuthorType.SYSTEM, authorProfile = profile, authorName = author,
                content = content, taskId = taskId, createdAt = now,
            ))
            dao.insertEvent(MeetingEventEntity(roomId = roomId, taskId = taskId, type = tool.eventType,
                payloadJson = tool.encode(isHost), createdAt = now))
            dao.updateRoom(room.copy(updatedAt = now))
        }
    }

    /** Incremental text updates one row and one metadata record, rather than journaling every token. */
    suspend fun recordStreamActivity(
        context: Context, roomId: String, profile: String, author: String,
        isHost: Boolean, taskId: String?, activity: MeetingStreamActivity,
    ) {
        require(activity.kind != AgentEvent.AssistantBlockKind.TOOL_CALL)
        val database = MeetingDatabase.get(context)
        database.withTransaction {
            val dao = database.meetingDao()
            val room = dao.room(roomId) ?: return@withTransaction
            if (dao.latestEvent(roomId, "stream_result:${activity.runId}") != null) return@withTransaction
            val existing = dao.message(activity.messageId)
            if (existing != null && (existing.roomId != roomId || existing.authorProfile != profile ||
                    existing.authorType != MeetingAuthorType.SYSTEM)) return@withTransaction
            if ((existing == null || activity.phase == MeetingStreamPhase.STREAMING) && room.status != MeetingRoomStatus.RUNNING) return@withTransaction
            val event = dao.latestEvent(roomId, activity.eventType)
            val previous = event?.let { MeetingStreamActivity.decode(it.payloadJson, existing?.content.orEmpty()) }
            if (previous?.phase == MeetingStreamPhase.SETTLED || previous?.phase == MeetingStreamPhase.INTERRUPTED || previous == activity) return@withTransaction
            val bounded = activity.copy(content = activity.content.take(MeetingStreamActivity.MAX_PREVIEW_CHARS),
                truncated = activity.truncated || activity.content.length > MeetingStreamActivity.MAX_PREVIEW_CHARS)
            val now = System.currentTimeMillis()
            dao.insertMessage(existing?.copy(content = bounded.content) ?: MeetingMessageEntity(
                id = bounded.messageId, roomId = roomId, sortIndex = dao.maxMessageSortIndex(roomId) + 1,
                authorType = MeetingAuthorType.SYSTEM, authorProfile = profile, authorName = author,
                content = bounded.content, taskId = taskId, createdAt = now,
            ))
            if (event == null) dao.insertEvent(MeetingEventEntity(roomId = roomId, taskId = taskId, type = bounded.eventType,
                payloadJson = bounded.encode(isHost), createdAt = now))
            else dao.updateEvent(event.copy(payloadJson = bounded.encode(isHost)))
            dao.updateRoom(room.copy(updatedAt = now))
        }
    }

    suspend fun appendMessage(
        context: Context,
        roomId: String,
        authorType: String,
        authorProfile: String?,
        authorName: String,
        content: String,
        mentions: List<String> = emptyList(),
        taskId: String? = null,
        messageId: String = UUID.randomUUID().toString(),
        now: Long = System.currentTimeMillis(),
        streamRunId: String? = null,
        attachments: ConversationAttachments = ConversationAttachments(),
    ): MeetingMessageEntity {
        val database = MeetingDatabase.get(context)
        var persisted = false
        val result = database.withTransaction {
            val dao = database.meetingDao()
            suspend fun recordStreamResult(message: MeetingMessageEntity) {
                if (streamRunId != null) dao.insertEvent(MeetingEventEntity(roomId = roomId, taskId = taskId,
                    type = "stream_result:$streamRunId", payloadJson = JSONObject().put("message_id", message.id).toString(), createdAt = now))
            }
            if (streamRunId != null && authorType in setOf(MeetingAuthorType.AGENT, MeetingAuthorType.HOST)) {
                dao.latestEvent(roomId, "stream_result:$streamRunId")?.let { event ->
                    dao.message(JSONObject(event.payloadJson).getString("message_id"))?.takeIf { it.roomId == roomId }?.let {
                        return@withTransaction it
                    }
                }
                val candidates = dao.streamActivityEvents(roomId).mapNotNull { event ->
                    val metadata = MeetingStreamActivity.decode(event.payloadJson, "")
                    if (metadata?.runId == streamRunId && metadata.kind == AgentEvent.AssistantBlockKind.TEXT &&
                        metadata.eventType == event.type) event to metadata else null
                }
                val candidate = candidates.maxWithOrNull(compareBy({ it.second.round }, { it.second.attempt }))
                val existing = candidate?.let { dao.message(it.second.messageId) }?.takeIf {
                    it.roomId == roomId && it.authorProfile == authorProfile
                }
                if (candidate != null && existing != null) {
                    // Late stream saves cannot overwrite this promoted authoritative result.
                    val message = existing.copy(authorType = authorType, authorName = authorName, content = content,
                        mentionsJson = JSONArray(mentions.distinct()).toString(), taskId = taskId)
                    dao.insertMessage(message)
                    persisted = true
                    dao.updateEvent(candidate.first.copy(payloadJson = candidate.second.copy(phase = MeetingStreamPhase.SETTLED,
                        truncated = false).encode(authorType == MeetingAuthorType.HOST)))
                    dao.room(roomId)?.let { dao.updateRoom(it.copy(updatedAt = now)) }
                    recordStreamResult(message)
                    return@withTransaction message
                }
            }
            val sortIndex = dao.maxMessageSortIndex(roomId) + 1
            val message = MeetingMessageEntity(
                id = messageId,
                roomId = roomId,
                sortIndex = sortIndex,
                authorType = authorType,
                authorProfile = authorProfile,
                authorName = authorName,
                content = content,
                mentionsJson = JSONArray(mentions.distinct()).toString(),
                taskId = taskId,
                createdAt = now,
            )
            dao.insertMessage(message)
            if (authorType == MeetingAuthorType.USER) dao.deleteEventType(roomId, "pending_turn:$messageId")
            persisted = true
            if (!attachments.isEmpty) {
                require(authorType == MeetingAuthorType.USER) { "Only user messages accept attachments" }
                dao.insertEvent(MeetingEventEntity(roomId = roomId, taskId = taskId, type = "message_attachments:${message.id}",
                    payloadJson = ConversationAttachmentDrafts.get(context).encode(attachments), createdAt = now))
            }
            recordStreamResult(message)
            dao.room(roomId)?.let { dao.updateRoom(it.copy(updatedAt = now)) }
            message
        }
        if (persisted && authorType in setOf(MeetingAuthorType.USER, MeetingAuthorType.AGENT, MeetingAuthorType.HOST)) {
            io.github.mangi.eta.agent.packages.PackageMessageEvents.committed(context, listOf(
                io.github.mangi.eta.agent.packages.PackagePersistedMessage(io.github.mangi.eta.data.model.ConversationSubject.meeting(roomId),
                    result.id, if (authorType == MeetingAuthorType.USER) "user" else "ai", result.content, now)))
        }
        return result
    }

    suspend fun createTask(
        context: Context,
        roomId: String,
        participantProfile: String,
        instruction: String,
        now: Long = System.currentTimeMillis(),
    ): MeetingTaskEntity {
        val task = MeetingTaskEntity(
            id = UUID.randomUUID().toString(),
            roomId = roomId,
            participantProfile = participantProfile,
            instruction = instruction,
            status = MeetingTaskStatus.QUEUED,
            createdAt = now,
            updatedAt = now,
        )
        val dao = MeetingDatabase.get(context).meetingDao()
        dao.insertTask(task)
        appendEvent(context, roomId, task.id, "task_queued", JSONObject().put("participant", participantProfile), now)
        return task
    }

    suspend fun commitPlannedDispatch(context: Context, roomId: String, recruits: List<PlannedMeetingParticipant>, plans: List<PlannedMeetingTask>): CommittedMeetingPlan {
        val database = MeetingDatabase.get(context)
        return database.withTransaction {
            val participants = createPlannedParticipants(context, roomId, recruits)
            val tasks = createPlannedTasks(context, roomId, plans)
            CommittedMeetingPlan(participants, tasks)
        }
    }

    suspend fun createPlannedTasks(context: Context, roomId: String, plans: List<PlannedMeetingTask>): List<MeetingTaskEntity> {
        val database = MeetingDatabase.get(context)
        return database.withTransaction {
            val dao = database.meetingDao()
            requireNotNull(dao.room(roomId)) { "MEETING_NOT_FOUND" }
            val members = dao.participants(roomId)
            require(plans.map { it.profileName }.distinct().size == plans.size &&
                MeetingPlanScheduler.validDependencies(plans.associate { it.profileName to it.dependencies })) { "INVALID_PLAN_DEPENDENCIES" }
            require(plans.all { plan -> members.any { it.profileName == plan.profileName && it.enabled && !it.isHost } }) { "PARTICIPANT_UNAVAILABLE" }
            val now = System.currentTimeMillis()
            plans.map { plan ->
                val task = MeetingTaskEntity(id = UUID.randomUUID().toString(), roomId = roomId,
                    participantProfile = plan.profileName, instruction = plan.instruction, status = MeetingTaskStatus.QUEUED,
                    createdAt = now, updatedAt = now)
                dao.insertTask(task)
                dao.insertEvent(MeetingEventEntity(roomId = roomId, taskId = task.id, type = "task_queued",
                    payloadJson = JSONObject().put("participant", plan.profileName).put("depends_on", JSONArray(plan.dependencies)).toString(), createdAt = now))
                task
            }
        }
    }

    suspend fun createHostTask(context: Context, roomId: String, profileName: String, instruction: String): MeetingTaskEntity {
        val database = MeetingDatabase.get(context)
        return database.withTransaction {
            val dao = database.meetingDao()
            requireNotNull(dao.room(roomId)) { "MEETING_NOT_FOUND" }
            require(dao.participants(roomId).any { it.profileName == profileName && it.enabled && it.isHost }) { "HOST_UNAVAILABLE" }
            val now = System.currentTimeMillis()
            val task = MeetingTaskEntity(id = UUID.randomUUID().toString(), roomId = roomId,
                participantProfile = profileName, instruction = instruction, status = MeetingTaskStatus.QUEUED,
                createdAt = now, updatedAt = now)
            dao.insertTask(task)
            dao.insertEvent(MeetingEventEntity(roomId = roomId, taskId = task.id, type = "task_queued",
                payloadJson = JSONObject().put("participant", profileName).put("depends_on", JSONArray()).toString(), createdAt = now))
            task
        }
    }

    suspend fun createDynamicParticipant(
        context: Context,
        roomId: String,
        profileName: String,
        displayName: String,
        profileSummary: String,
        providerId: String? = null,
        modelId: String? = null,
    ): MeetingParticipantEntity {
        val normalizedProfile = profileName.trim().lowercase()
        require(Regex("[a-z0-9][a-z0-9_-]{1,47}").matches(normalizedProfile)) {
            "动态 Agent profile 名称无效"
        }
        val safeDisplayName = displayName.trim().take(80).ifBlank { normalizedProfile }
        val safeSummary = profileSummary.trim().take(2_000)
        val database = MeetingDatabase.get(context)
        return database.withTransaction {
            val dao = database.meetingDao()
            requireNotNull(dao.room(roomId)) { "MEETING_NOT_FOUND" }
            val existing = dao.participants(roomId).firstOrNull { it.profileName == normalizedProfile }
            if (existing != null) return@withTransaction existing
            val participant = MeetingParticipantEntity(
                roomId = roomId,
                profileName = normalizedProfile,
                displayName = safeDisplayName,
                profileSummary = safeSummary,
                providerId = providerId?.takeIf(String::isNotBlank),
                modelId = modelId?.takeIf(String::isNotBlank),
                isHost = false,
                sortOrder = (dao.participants(roomId).maxOfOrNull { it.sortOrder } ?: 0) + 1,
                enabled = true,
            )
            dao.insertParticipants(listOf(participant))
            dao.insertEvent(
                MeetingEventEntity(
                    roomId = roomId,
                    type = "dynamic_agent_created",
                    payloadJson = JSONObject()
                        .put("profile", normalizedProfile)
                        .put("displayName", safeDisplayName)
                        .toString(),
                    createdAt = System.currentTimeMillis(),
                ),
            )
            participant
        }
    }

    /** Commit all planned recruits and their model decisions together, or none of them. */
    suspend fun createPlannedParticipants(context: Context, roomId: String, drafts: List<PlannedMeetingParticipant>): List<MeetingParticipantEntity> {
        if (drafts.isEmpty()) return emptyList()
        return MeetingDatabase.get(context).withTransaction {
            val dao = MeetingDatabase.get(context).meetingDao()
            requireNotNull(dao.room(roomId)) { "MEETING_NOT_FOUND" }
            val existing = dao.participants(roomId)
            val names = drafts.map { it.profileName.trim().lowercase() }
            require(names.distinct().size == names.size && names.all { name ->
                Regex("[a-z0-9][a-z0-9_-]{1,47}").matches(name) && existing.none { it.profileName.equals(name, true) }
            }) { "PARTICIPANT_NAME_CONFLICT" }
            val firstOrder = (existing.maxOfOrNull { it.sortOrder } ?: 0) + 1
            val created = drafts.mapIndexed { index, draft -> MeetingParticipantEntity(
                roomId = roomId, profileName = names[index], displayName = draft.displayName.trim().take(80).ifBlank { names[index] },
                profileSummary = draft.summary.trim().take(2_000), providerId = draft.providerId, modelId = draft.modelId,
                isHost = false, sortOrder = firstOrder + index, enabled = true,
            ) }
            dao.insertParticipants(created)
            created.zip(drafts).forEach { (participant, draft) ->
                val now = System.currentTimeMillis()
                dao.insertEvent(MeetingEventEntity(roomId = roomId, type = "dynamic_agent_created",
                    payloadJson = JSONObject().put("profile", participant.profileName).put("displayName", participant.displayName).toString(), createdAt = now))
                dao.insertEvent(MeetingEventEntity(roomId = roomId, type = "participant_model_selected",
                    payloadJson = JSONObject().put("profile", participant.profileName).put("provider_id", draft.providerId)
                        .put("model_id", draft.modelId).put("model_identity", draft.modelIdentity).put("reason", draft.modelReason).toString(), createdAt = now))
            }
            created
        }
    }

    suspend fun updateTask(
        context: Context,
        task: MeetingTaskEntity,
        status: String,
        resultMessageId: String? = task.resultMessageId,
        errorMessage: String? = task.errorMessage,
        now: Long = System.currentTimeMillis(),
    ) {
        val database = MeetingDatabase.get(context)
        database.withTransaction {
        database.meetingDao().updateTask(task.copy(
                status = status,
                resultMessageId = resultMessageId,
                errorMessage = errorMessage,
                updatedAt = now,
            ),
        )
        database.meetingDao().insertEvent(MeetingEventEntity(
            roomId = task.roomId, taskId = task.id, type = "task_$status",
            payloadJson = JSONObject().put("participant", task.participantProfile).apply {
                errorMessage?.let { put("error", it.take(1_000)) }
            }.toString(), createdAt = now,
        ))
        }
    }

    suspend fun setRoomStatus(
        context: Context,
        roomId: String,
        status: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val dao = MeetingDatabase.get(context).meetingDao()
        val room = dao.room(roomId) ?: return
        dao.updateRoom(room.copy(status = status, updatedAt = now))
        appendEvent(context, roomId, null, "room_$status", JSONObject(), now)
    }

    suspend fun renameRoom(
        context: Context,
        roomId: String,
        title: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val normalized = title.trim()
        if (normalized.isEmpty()) return
        val database = MeetingDatabase.get(context)
        database.withTransaction {
            val dao = database.meetingDao()
            val room = dao.room(roomId) ?: return@withTransaction
            dao.updateRoom(room.copy(title = normalized.take(120), updatedAt = now))
            dao.insertEvent(
                MeetingEventEntity(
                    roomId = roomId,
                    type = "room_renamed",
                    payloadJson = JSONObject().put("title", normalized.take(120)).toString(),
                    createdAt = now,
                ),
            )
        }
    }

    suspend fun setRoomArchived(
        context: Context,
        roomId: String,
        archived: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        val database = MeetingDatabase.get(context)
        database.withTransaction {
            val dao = database.meetingDao()
            val room = dao.room(roomId) ?: return@withTransaction
            require(!archived || room.status != MeetingRoomStatus.RUNNING) {
                "会议室运行结束后才能归档"
            }
            val archivedAt = if (archived) now else null
            if (room.archivedAt == archivedAt) return@withTransaction
            dao.updateRoom(room.copy(archivedAt = archivedAt, updatedAt = now))
            dao.insertEvent(
                MeetingEventEntity(
                    roomId = roomId,
                    type = if (archived) "room_archived" else "room_restored",
                    payloadJson = JSONObject()
                        .put("archivedAt", archivedAt)
                        .toString(),
                    createdAt = now,
                ),
            )
        }
    }

    suspend fun setRoomPinned(
        context: Context,
        roomId: String,
        pinned: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        val database = MeetingDatabase.get(context)
        database.withTransaction {
            val dao = database.meetingDao()
            val room = dao.room(roomId) ?: return@withTransaction
            if ((room.pinnedAt != null) == pinned) return@withTransaction
            val pinnedAt = if (pinned) now else null
            if (room.pinnedAt == pinnedAt) return@withTransaction
            dao.updateRoom(room.copy(pinnedAt = pinnedAt, updatedAt = now))
            dao.insertEvent(
                MeetingEventEntity(
                    roomId = roomId,
                    type = if (pinned) "room_pinned" else "room_unpinned",
                    payloadJson = JSONObject().put("pinnedAt", pinnedAt).toString(),
                    createdAt = now,
                ),
            )
        }
    }

    suspend fun setRoomContextWindowOverride(
        context: Context,
        roomId: String,
        contextWindow: Int?,
        now: Long = System.currentTimeMillis(),
    ) {
        require(contextWindow == null || contextWindow in 1_024..16_000_000) {
            "CONTEXT_WINDOW_INVALID"
        }
        val database = MeetingDatabase.get(context)
        database.withTransaction {
            val dao = database.meetingDao()
            val room = dao.room(roomId) ?: throw IllegalArgumentException("MEETING_NOT_FOUND")
            dao.updateRoom(room.copy(contextWindowOverride = contextWindow, updatedAt = now))
            dao.insertEvent(
                MeetingEventEntity(
                    roomId = roomId,
                    type = "room_context_window_changed",
                    payloadJson = org.json.JSONObject().put("contextWindow", contextWindow).toString(),
                    createdAt = now,
                ),
            )
        }
    }

    suspend fun roomCompactionBinding(context: Context, roomId: String): io.github.mangi.eta.data.model.ModelRoleBinding? =
        MeetingDatabase.get(context).meetingDao().room(roomId)?.let { room ->
            room.compactionModelId?.let { modelId ->
                io.github.mangi.eta.data.model.ModelRoleBinding(
                    modelId = modelId,
                    providerId = room.compactionProviderId,
                )
            }
        }

    suspend fun setRoomCompactionBinding(
        context: Context,
        roomId: String,
        binding: io.github.mangi.eta.data.model.ModelRoleBinding?,
        now: Long = System.currentTimeMillis(),
    ) {
        val database = MeetingDatabase.get(context)
        database.withTransaction {
            val dao = database.meetingDao()
            val room = dao.room(roomId) ?: throw IllegalArgumentException("MEETING_NOT_FOUND")
            dao.updateRoom(room.copy(
                compactionModelId = binding?.modelId,
                compactionProviderId = binding?.providerId,
                updatedAt = now,
            ))
            dao.insertEvent(
                MeetingEventEntity(
                    roomId = roomId,
                    type = "room_compaction_model_changed",
                    payloadJson = org.json.JSONObject()
                        .put("modelId", binding?.modelId ?: org.json.JSONObject.NULL)
                        .put("providerId", binding?.providerId ?: org.json.JSONObject.NULL)
                        .toString(),
                    createdAt = now,
                ),
            )
        }
    }

    suspend fun appendEvent(
        context: Context,
        roomId: String,
        taskId: String?,
        type: String,
        payload: JSONObject,
        now: Long = System.currentTimeMillis(),
    ) {
        MeetingDatabase.get(context).meetingDao().insertEvent(
            MeetingEventEntity(
                roomId = roomId,
                taskId = taskId,
                type = type,
                payloadJson = payload.toString(),
                createdAt = now,
            ),
        )
    }

    suspend fun deleteRoom(context: Context, roomId: String) {
        val database = MeetingDatabase.get(context)
        database.withTransaction {
            val dao = database.meetingDao()
            dao.deleteMessages(roomId)
            dao.deleteTasks(roomId)
            dao.deleteEvents(roomId)
            dao.deleteParticipantContexts(roomId)
            dao.deleteParticipants(roomId)
            dao.deleteRoom(roomId)
        }
        ConversationDraftRepository.get(context).remove(ConversationSubject.meeting(roomId))
        ConversationAttachmentDrafts.get(context).remove(ConversationSubject.meeting(roomId))
    }

    suspend fun participantHistory(
        context: Context,
        roomId: String,
        profileName: String,
        messages: List<MeetingMessageEntity>,
        isHost: Boolean = true,
    ): List<AgentModelClient.ConversationMessage> {
        val attachments = decodeMessageAttachments(context, MeetingDatabase.get(context).meetingDao().messageAttachmentEvents(roomId))
        val checkpoint = MeetingDatabase.get(context).meetingDao()
            .participantContext(roomId, profileName)
            ?: return MeetingContextHistory.forParticipant(messages, profileName, attachments, isHost)
        val compacted = AgentContextSnapshot.decode(checkpoint.snapshotJson)?.messages
            ?: return MeetingContextHistory.forParticipant(messages, profileName, attachments, isHost)
        val appended = MeetingContextHistory.forParticipant(
            messages = messages.filter { it.sortIndex > checkpoint.lastMessageSortIndex },
            participantProfile = profileName,
            attachments = attachments,
            isHost = isHost,
        )
        return compacted + appended
    }

    private fun decodeMessageAttachments(context: Context, events: List<MeetingEventEntity>): Map<String, ConversationAttachments> =
        events.filter { it.type.startsWith("message_attachments:") }.groupBy { it.type.removePrefix("message_attachments:") }
            .mapNotNull { (id, records) -> runCatching {
                id to ConversationAttachmentDrafts.get(context).decode(records.maxBy { it.id }.payloadJson)
            }.getOrNull() }.toMap()

    suspend fun saveParticipantContext(
        context: Context,
        roomId: String,
        profileName: String,
        snapshot: AgentContextSnapshot?,
        lastMessageSortIndex: Int,
        now: Long = System.currentTimeMillis(),
    ) {
        snapshot ?: return
        MeetingDatabase.get(context).meetingDao().upsertParticipantContext(
            MeetingParticipantContextEntity(
                roomId = roomId,
                profileName = profileName,
                snapshotJson = snapshot.encode(),
                lastMessageSortIndex = lastMessageSortIndex,
                updatedAt = now,
            ),
        )
    }
}

internal data class MeetingParticipantDraft(
    val profileName: String,
    val displayName: String,
    val profileSummary: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val isHost: Boolean = false,
)
