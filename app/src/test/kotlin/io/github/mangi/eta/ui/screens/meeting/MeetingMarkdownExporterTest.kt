package io.github.mangi.eta.ui.screens.meeting

import io.github.mangi.eta.fork.meeting.MeetingAuthorType
import io.github.mangi.eta.fork.meeting.MeetingMessageEntity
import io.github.mangi.eta.fork.meeting.MeetingParticipantEntity
import io.github.mangi.eta.fork.meeting.MeetingRoomEntity
import io.github.mangi.eta.fork.meeting.MeetingRoomSnapshot
import io.github.mangi.eta.fork.meeting.MeetingRoomStatus
import io.github.mangi.eta.fork.meeting.MeetingTaskEntity
import io.github.mangi.eta.fork.meeting.MeetingTaskStatus
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingMarkdownExporterTest {
    @Test
    fun exportContainsRosterConversationAndTasks() {
        val snapshot = MeetingRoomSnapshot(
            room = MeetingRoomEntity(
                id = "room",
                title = "Release review",
                hostProfile = "host",
                status = MeetingRoomStatus.WAITING_FOR_USER,
                createdAt = 1,
                updatedAt = 2,
                archivedAt = 2,
            ),
            participants = listOf(
                participant("host", "Host", isHost = true),
                participant("coder", "Coder"),
            ),
            messages = listOf(
                message("m1", MeetingAuthorType.USER, "You", "Ship it"),
                message("m2", MeetingAuthorType.HOST, "Host", "Approved"),
            ),
            tasks = listOf(
                MeetingTaskEntity(
                    id = "task",
                    roomId = "room",
                    participantProfile = "coder",
                    instruction = "Check risks",
                    status = MeetingTaskStatus.COMPLETED,
                    resultMessageId = "m2",
                    createdAt = 1,
                    updatedAt = 2,
                ),
            ),
        )

        val text = MeetingMarkdownExporter.export(snapshot, exportedAt = 3)

        assertTrue(text.contains("# Release review"))
        assertTrue(text.contains("Coder (`@coder`)"))
        assertTrue(text.contains("### Host · 主持人"))
        assertTrue(text.contains("`coder` · 已完成"))
        assertTrue(text.contains("- 归档：已归档"))
    }

    @Test
    fun filenameIsSafe() {
        assertTrue(MeetingMarkdownExporter.defaultFileName("A/B: C?").contains("Eta-AB C-"))
    }

    private fun participant(name: String, display: String, isHost: Boolean = false) =
        MeetingParticipantEntity(
            roomId = "room",
            profileName = name,
            displayName = display,
            profileSummary = "role",
            isHost = isHost,
        )

    private fun message(id: String, type: String, author: String, content: String) =
        MeetingMessageEntity(
            id = id,
            roomId = "room",
            sortIndex = id.last().digitToInt(),
            authorType = type,
            authorProfile = author.lowercase(),
            authorName = author,
            content = content,
            createdAt = 1,
        )
}
