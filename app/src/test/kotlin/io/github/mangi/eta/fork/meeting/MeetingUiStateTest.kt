package io.github.mangi.eta.fork.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingUiStateTest {
    @Test
    fun activeMentionOnlyMatchesTypingAtATokenBoundary() {
        assertEquals(ActiveMention("co", 0, 3), MeetingComposerState.activeMention("@co", 3))
        assertEquals(
            ActiveMention("plan", 4, 9),
            MeetingComposerState.activeMention("ask @plan next", 9),
        )
        assertNull(MeetingComposerState.activeMention("mail@co", 7))
        assertNull(MeetingComposerState.activeMention("@coder ready", 7))
    }

    @Test
    fun completingMentionReplacesOnlyTheActiveToken() {
        val text = "请 @co 检查"
        val active = MeetingComposerState.activeMention(text, 4)!!
        assertEquals(
            "请 @coder  检查",
            MeetingComposerState.completeMention(text, active, "coder"),
        )
    }

    @Test
    fun snapshotProjectsRunningFailedAndSummaryState() {
        val host = participant("lead", isHost = true)
        val coder = participant("coder")
        val summary = message("summary", MeetingAuthorType.HOST, "lead", "汇总完成")
        val state = MeetingRoomSnapshot(
            room = room(MeetingRoomStatus.RUNNING),
            participants = listOf(host, coder),
            messages = listOf(message("user", MeetingAuthorType.USER, null, "开始"), summary),
            tasks = listOf(
                task("old-failure", MeetingTaskStatus.FAILED, createdAt = 0),
                task("running", MeetingTaskStatus.RUNNING),
                task("failed", MeetingTaskStatus.FAILED),
                task("done", MeetingTaskStatus.COMPLETED),
            ),
        ).toUiState()

        assertTrue(state.isRunning)
        assertFalse(state.isFailed)
        assertEquals(1, state.completingTasks)
        assertEquals(1, state.failedTasks)
        assertEquals(3, state.latestRoundTasks.size)
        assertSame(host, state.host)
        assertSame(summary, state.latestSummary)
        assertEquals(listOf(host, coder), state.mentionCandidates)
    }

    @Test
    fun disabledParticipantsAreNotMentionCandidates() {
        val enabled = participant("coder")
        val disabled = participant("plan").copy(enabled = false)
        val state = MeetingRoomSnapshot(
            room = room(MeetingRoomStatus.IDLE),
            participants = listOf(enabled, disabled),
            messages = emptyList(),
            tasks = emptyList(),
        ).toUiState()

        assertEquals(listOf(enabled), state.mentionCandidates)
        assertEquals(listOf(enabled, disabled), state.participants)
    }

    @Test
    fun participantInspectionUsesLatestRoundTaskAndLatestVisibleActivity() {
        val coder = participant("coder")
        val older = message("older", MeetingAuthorType.AGENT, "coder", "旧结果").copy(createdAt = 2)
        val latest = message("latest", MeetingAuthorType.AGENT, "coder", "新结果").copy(createdAt = 3)
        val oldTask = task("old", MeetingTaskStatus.COMPLETED, createdAt = 2)
        val roundTask = task("round", MeetingTaskStatus.RUNNING, createdAt = 3)
        val state = MeetingRoomSnapshot(
            room = room(MeetingRoomStatus.RUNNING),
            participants = listOf(participant("lead", isHost = true), coder),
            messages = listOf(
                message("user", MeetingAuthorType.USER, null, "继续").copy(createdAt = 3),
                older,
                latest,
            ),
            tasks = listOf(oldTask, roundTask),
        ).toUiState()

        assertSame(roundTask, state.latestTaskFor("coder"))
        assertSame(latest, state.latestActivityFor("coder"))
    }

    @Test
    fun latestSummaryDoesNotShowPreviousRoundWhileCurrentRoundRuns() {
        val previousUser = message("old-user", MeetingAuthorType.USER, null, "旧任务").copy(createdAt = 1)
        val previousSummary = message("old-summary", MeetingAuthorType.HOST, "lead", "旧汇总").copy(createdAt = 2)
        val currentUser = message("new-user", MeetingAuthorType.USER, null, "新任务").copy(createdAt = 3)
        val state = MeetingRoomSnapshot(
            room = room(MeetingRoomStatus.RUNNING),
            participants = listOf(participant("lead", isHost = true)),
            messages = listOf(previousUser, previousSummary, currentUser),
            tasks = emptyList(),
        ).toUiState()

        assertNull(state.latestSummary)
    }

    private fun room(status: String) = MeetingRoomEntity(
        id = "room",
        title = "Release review",
        hostProfile = "lead",
        status = status,
        createdAt = 1,
        updatedAt = 2,
    )

    private fun participant(name: String, isHost: Boolean = false) = MeetingParticipantEntity(
        roomId = "room",
        profileName = name,
        displayName = name,
        profileSummary = name,
        isHost = isHost,
    )

    private fun message(id: String, authorType: String, profile: String?, content: String) =
        MeetingMessageEntity(
            id = id,
            roomId = "room",
            sortIndex = 0,
            authorType = authorType,
            authorProfile = profile,
            authorName = profile ?: "You",
            content = content,
            createdAt = 1,
        )

    private fun task(id: String, status: String, createdAt: Long = 1) = MeetingTaskEntity(
        id = id,
        roomId = "room",
        participantProfile = "coder",
        instruction = "work",
        status = status,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
