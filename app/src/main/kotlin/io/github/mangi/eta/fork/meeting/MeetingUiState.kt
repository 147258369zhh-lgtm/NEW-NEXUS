package io.github.mangi.eta.fork.meeting

internal data class MeetingRoomUiState(
    val room: MeetingRoomEntity,
    val participants: List<MeetingParticipantEntity>,
    val messages: List<MeetingMessageEntity>,
    val completingTasks: Int,
    val failedTasks: Int,
    val mentionCandidates: List<MeetingParticipantEntity>,
    val latestRoundTasks: List<MeetingTaskEntity> = emptyList(),
    val timelineEvents: List<MeetingEventEntity> = emptyList(),
    val messageAttachments: Map<String, io.github.mangi.eta.data.repository.ConversationAttachments> = emptyMap(),
) {
    val host: MeetingParticipantEntity?
        get() = participants.firstOrNull(MeetingParticipantEntity::isHost)

    val isRunning: Boolean
        get() = room.status == MeetingRoomStatus.RUNNING

    val isWaiting: Boolean
        get() = room.status == MeetingRoomStatus.WAITING_FOR_USER

    val isFailed: Boolean
        get() = room.status == MeetingRoomStatus.FAILED

    val latestSummary: MeetingMessageEntity?
        get() {
            val latestUserIndex = messages.indexOfLast { it.authorType == MeetingAuthorType.USER }
            val currentRoundStart = (latestUserIndex + 1).coerceAtLeast(0)
            return messages.drop(currentRoundStart).lastOrNull { message ->
                message.authorType == MeetingAuthorType.HOST
            }
        }

    fun latestTaskFor(profileName: String): MeetingTaskEntity? =
        latestRoundTasks.lastOrNull { it.participantProfile == profileName }

    fun isParticipantWaiting(profileName: String): Boolean {
        if (!isRunning) return false
        val participant = participants.firstOrNull { it.profileName == profileName } ?: return false
        val task = latestTaskFor(profileName)
        if (!participant.isHost && task?.status != MeetingTaskStatus.RUNNING) return false
        val roundStart = messages.lastOrNull { it.authorType == MeetingAuthorType.USER }?.createdAt ?: return false
        return timelineEvents.asSequence().filter { it.createdAt >= roundStart &&
            ((participant.isHost && it.taskId == null) ||
                (task?.status == MeetingTaskStatus.RUNNING && it.taskId == task.id)) &&
            (it.type.startsWith("accessibility_wait:") || it.type.startsWith("approval_wait:")) }
            .groupBy { it.type }.values.any { events ->
                val latest = events.maxByOrNull { it.id } ?: return@any false
                runCatching { org.json.JSONObject(latest.payloadJson) }.getOrNull()?.let {
                    it.optString("profile") == profileName && it.optString("phase") in setOf("waiting", "REQUESTED")
                } == true
            }
    }

    fun latestActivityFor(profileName: String): MeetingMessageEntity? =
        messages.drop((messages.indexOfLast { it.authorType == MeetingAuthorType.USER } + 1).coerceAtLeast(0)).lastOrNull { message ->
            message.authorProfile == profileName &&
                (message.authorType == MeetingAuthorType.HOST || message.authorType == MeetingAuthorType.AGENT)
        }
}

internal data class ActiveMention(
    val query: String,
    val replaceStart: Int,
    val replaceEnd: Int,
)

internal object MeetingComposerState {
    fun activeMention(text: String, cursor: Int): ActiveMention? {
        val safeCursor = cursor.coerceIn(0, text.length)
        val beforeCursor = text.substring(0, safeCursor)
        var searchFrom = 0
        while (searchFrom < safeCursor) {
            val bracketStart = text.indexOf("@[", searchFrom)
            if (bracketStart < 0 || bracketStart >= safeCursor) break
            if (!MeetingMentionRouter.isMentionBoundary(text, bracketStart)) {
                searchFrom = bracketStart + 2
                continue
            }
            val value = StringBuilder()
            var position = bracketStart + 2
            var queryAtCursor: String? = null
            while (position < text.length) {
                if (position == safeCursor) queryAtCursor = value.toString()
                val character = text[position]
                if (character == ']') {
                    if (safeCursor <= position && queryAtCursor != null) {
                        return ActiveMention(queryAtCursor, bracketStart, position + 1)
                    }
                    break
                }
                if (character == '\\') {
                    val escaped = text.getOrNull(position + 1)
                    if (escaped == null || escaped != '\\' && escaped != ']' || safeCursor == position + 1) break
                    value.append(escaped)
                    position += 2
                } else {
                    value.append(character)
                    position++
                }
            }
            if (position == text.length && safeCursor == position) {
                return ActiveMention(value.toString(), bracketStart, position)
            }
            if (position >= safeCursor && queryAtCursor != null) {
                var end = safeCursor
                while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '-')) end++
                return ActiveMention(queryAtCursor, bracketStart, end)
            }
            searchFrom = position + 1
        }
        val start = beforeCursor.lastIndexOf('@')
        if (start < 0) return null
        if (!MeetingMentionRouter.isMentionBoundary(text, start)) return null
        val query = beforeCursor.substring(start + 1)
        if (query.any { character -> !character.isLetterOrDigit() && character != '_' && character != '-' }) {
            return null
        }
        var replaceEnd = safeCursor
        while (replaceEnd < text.length) {
            val character = text[replaceEnd]
            if (!character.isLetterOrDigit() && character != '_' && character != '-') break
            replaceEnd++
        }
        return ActiveMention(query, start, replaceEnd)
    }

    fun completeMention(text: String, active: ActiveMention, profileName: String): String =
        text.replaceRange(active.replaceStart, active.replaceEnd, "${MeetingMentionRouter.formatMention(profileName)} ")
}

internal fun MeetingRoomSnapshot.toUiState(): MeetingRoomUiState =
    run {
        val latestUserTime = messages.lastOrNull { it.authorType == MeetingAuthorType.USER }?.createdAt
        val roundTasks = if (latestUserTime == null) tasks else tasks.filter { it.createdAt >= latestUserTime }
        MeetingRoomUiState(
        room = room,
        participants = participants,
        messages = messages,
        completingTasks = roundTasks.count { task ->
            task.status == MeetingTaskStatus.QUEUED || task.status == MeetingTaskStatus.RUNNING
        },
        failedTasks = roundTasks.count { task -> task.status == MeetingTaskStatus.FAILED },
        mentionCandidates = participants.filter(MeetingParticipantEntity::enabled),
        latestRoundTasks = roundTasks,
        timelineEvents = timelineEvents,
        messageAttachments = messageAttachments,
        )
    }
