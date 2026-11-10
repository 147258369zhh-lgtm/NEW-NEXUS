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

    fun latestActivityFor(profileName: String): MeetingMessageEntity? =
        messages.lastOrNull { message ->
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
        val start = beforeCursor.lastIndexOf('@')
        if (start < 0) return null
        if (start > 0 && beforeCursor[start - 1].isLetterOrDigit()) return null
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
        text.replaceRange(active.replaceStart, active.replaceEnd, "@$profileName ")
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
