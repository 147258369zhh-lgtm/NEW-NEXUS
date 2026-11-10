package io.github.mangi.eta.ui.screens.meeting

import io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase
import io.github.mangi.eta.fork.meeting.*
import io.github.mangi.eta.ui.components.ConversationAuthorRole
import io.github.mangi.eta.ui.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import io.github.mangi.eta.agent.runtime.AgentEvent

class MeetingConversationTimelineTest {
    private val room = MeetingRoomEntity("room", "Room", "lead", MeetingRoomStatus.RUNNING, 1, 1)
    private fun message(id: String, author: String, content: String = "body", index: Int = 0) =
        MeetingMessageEntity(id, room.id, index, author, authorProfile = "lead", authorName = "Lead", content = content, createdAt = index.toLong())
    private fun state(messages: List<MeetingMessageEntity>, events: List<MeetingEventEntity> = emptyList(), status: String = room.status) =
        MeetingRoomSnapshot(room.copy(status = status), emptyList(), messages, emptyList(), events).toUiState()
    private fun waitEvent(phase: AccessibilityWaitPhase, id: Long = 1) = MeetingEventEntity(
        id = id, roomId = room.id, type = "accessibility_wait:accessibility-run-1",
        payloadJson = JSONObject().put("run_id", "run").put("outage", 1).put("phase", phase.wireValue).toString(), createdAt = 2,
    )

    @Test fun unchangedHistoricalMessagesKeepTheirInstancesWhenOneStreamAdvances() {
        val activity = MeetingStreamActivity("run", 1, 0, AgentEvent.AssistantBlockKind.TEXT, MeetingStreamPhase.STREAMING, "first")
        val event = MeetingEventEntity(id = 1, roomId = room.id, type = activity.eventType, payloadJson = activity.encode(false), createdAt = 1)
        val original = state(listOf(message("old", MeetingAuthorType.AGENT),
            message(activity.messageId, MeetingAuthorType.SYSTEM, "first", 1)), listOf(event))
        val first = original.conversationTimeline(1)
        val second = original.copy(messages = original.messages.map { if (it.id == activity.messageId) it.copy(content = "first second") else it })
            .conversationTimeline(1, previous = first)
        assertSame(first.messages.first(), second.messages.first())
        assertNotSame(first.messages.last(), second.messages.last())
        assertEquals("first second", (second.messages.last() as AgentMessageUi).content)
    }

    @Test fun streamPreviewWarningAndInterruptedStateAreVisibleWithoutMarkingItAFinalReply() {
        val activity = MeetingStreamActivity("run", 1, 0, AgentEvent.AssistantBlockKind.TEXT, MeetingStreamPhase.STREAMING, "partial", true)
        val event = MeetingEventEntity(id = 1, roomId = room.id, type = activity.eventType, payloadJson = activity.encode(false), createdAt = 1)
        val original = state(listOf(message(activity.messageId, MeetingAuthorType.SYSTEM, "partial")), listOf(event), MeetingRoomStatus.IDLE)
        val timeline = original.conversationTimeline(1, streamPreviewDetail = "Preview limit", streamInterruptedDetail = "Interrupted")
        val reply = timeline.messages.single() as AgentMessageUi
        assertFalse(reply.isStreaming)
        assertEquals("partial\n\nPreview limit\n\nInterrupted", reply.content)
        assertTrue(timeline.completedReplyIds.isEmpty())
    }

    @Test fun malformedAndMismatchedStreamMetadataFallsBackToTheStoredReadableText() {
        val activity = MeetingStreamActivity("run", 1, 0, AgentEvent.AssistantBlockKind.TEXT, MeetingStreamPhase.STREAMING, "readable")
        val event = MeetingEventEntity(id = 1, roomId = room.id, type = activity.eventType, payloadJson = activity.encode(false), createdAt = 1)
        listOf(event.copy(payloadJson = "{broken"), event.copy(roomId = "other"),
            event.copy(payloadJson = activity.copy(round = 2).encode(false))).forEach {
            val result = state(listOf(message(activity.messageId, MeetingAuthorType.SYSTEM, "readable")), listOf(it)).conversationTimeline(1)
            val fallback = result.messages.single() as AgentMessageUi
            assertEquals("readable", fallback.content)
            assertFalse(fallback.renderMarkdown)
            assertFalse(fallback.isStreaming)
        }
    }

    @Test fun malformedOrUnmatchedToolMetadataKeepsTheOriginalReadableNotice() {
        val tool = MeetingToolActivity("run", 1, "call", false, "read_file", MeetingToolPhase.SUCCESS)
        val message = message(tool.messageId, MeetingAuthorType.SYSTEM, "Tool completed: readable summary")
        val event = MeetingEventEntity(id = 1, roomId = room.id, type = tool.eventType, payloadJson = tool.encode(false), createdAt = 1)
        listOf(event.copy(payloadJson = "{broken"), event.copy(roomId = "other"),
            event.copy(payloadJson = tool.copy(callId = "other").encode(false))).forEach {
            val projected = state(listOf(message), listOf(it)).conversationTimeline(1).messages.single() as AgentMessageUi
            assertEquals(message.content, projected.content)
            assertFalse(projected.renderMarkdown)
        }
    }

    @Test fun completedTaskCannotLeaveItsOldToolAnimatingWhileAnotherMemberRuns() {
        val tool = MeetingToolActivity("run", 1, "call", false, "read_file", MeetingToolPhase.RUNNING)
        val message = message(tool.messageId, MeetingAuthorType.SYSTEM).copy(taskId = "done-task")
        val event = MeetingEventEntity(id = 1, roomId = room.id, type = tool.eventType, payloadJson = tool.encode(false), createdAt = 1)
        val base = state(listOf(message), listOf(event))
        val task = MeetingTaskEntity("done-task", room.id, "lead", "work", MeetingTaskStatus.COMPLETED, createdAt = 0, updatedAt = 2)
        val projected = base.copy(latestRoundTasks = listOf(task)).conversationTimeline(1, toolInterruptedDetail = "Result unconfirmed")
        val card = projected.messages.single() as ToolActivityMessageUi
        assertEquals(ToolActivityStatusUi.Unknown, card.status)
        assertEquals("Result unconfirmed", card.resultSummary)
    }

    @Test fun importedSensitiveToolFieldsAreAlsoSanitizedAtTheDisplayBoundary() {
        val tool = MeetingToolActivity("run", 1, "call", false, "mcp_search", MeetingToolPhase.SUCCESS,
            arguments = "private args", command = "private command", result = "private result")
        val event = MeetingEventEntity(id = 1, roomId = room.id, type = tool.eventType, payloadJson = tool.encode(false), createdAt = 1)
        val projected = state(listOf(message(tool.messageId, MeetingAuthorType.SYSTEM)), listOf(event))
            .conversationTimeline(1, sensitiveToolDetail = "Hidden")
        val card = projected.messages.single() as ToolActivityMessageUi
        assertEquals("", card.argumentsSummary)
        assertNull(card.command)
        assertEquals("Hidden", card.resultSummary)
    }

    @Test fun transcriptPreservesOrderMarkdownAndEveryParticipantsIndependentResult() {
        val messages = listOf(
            message("user", MeetingAuthorType.USER, "@coder do this"),
            message("coder", MeetingAuthorType.AGENT, "```kotlin\nval x = 1\n```", 1).copy(authorProfile = "coder", authorName = "Coder"),
            message("lead", MeetingAuthorType.HOST, "## Summary\n[Evidence](https://example.com)", 2),
            message("notice", MeetingAuthorType.SYSTEM, "Waiting for user", 3),
        )
        val result = state(messages).conversationTimeline(3)
        assertEquals(messages.map { it.id }, result.messages.map { it.id })
        assertTrue(result.messages.first() is UserMessageUi)
        assertEquals(messages[1].content, (result.messages[1] as AgentMessageUi).content)
        assertTrue((result.messages[2] as AgentMessageUi).renderMarkdown)
        assertFalse((result.messages[3] as AgentMessageUi).renderMarkdown)
        assertEquals(setOf("coder", "lead"), result.completedReplyIds)
        assertEquals("Coder", result.identities.getValue("coder").name)
        assertEquals(ConversationAuthorRole.Host, result.identities.getValue("lead").role)
        assertTrue(result.identities.getValue("lead").isSummary)
        assertEquals(3, result.identities.getValue("lead").previewLines)
        assertEquals(2L, result.identities.getValue("lead").createdAt)
    }

    @Test fun oldSummaryIsNotRepositionedOrCollapsedIntoTheNewRound() {
        val result = state(listOf(
            message("old-summary", MeetingAuthorType.HOST),
            message("new-user", MeetingAuthorType.USER, index = 1),
            message("reply", MeetingAuthorType.AGENT, index = 2),
        )).conversationTimeline(0)
        assertEquals(listOf("old-summary", "new-user", "reply"), result.messages.map { it.id })
        assertTrue(result.identities.values.none { it.isSummary })
        assertNull(result.identities.getValue("old-summary").previewLines)
        assertEquals(setOf("old-summary", "reply"), result.completedReplyIds)
    }

    @Test fun summaryIsOnlyEmittedOnceAndTitleOnlyPreviewRemainsExplicit() {
        val result = state(listOf(message("summary", MeetingAuthorType.HOST))).conversationTimeline(0)
        assertEquals(1, result.messages.size)
        assertEquals(0, result.identities.getValue("summary").previewLines)
    }

    @Test fun typedWaitingUsesLatestEventEvenWhenEventsArriveUnsorted() {
        val result = state(listOf(message("accessibility-run-1", MeetingAuthorType.SYSTEM)),
            listOf(waitEvent(AccessibilityWaitPhase.RESTORED, 3), waitEvent(AccessibilityWaitPhase.WAITING, 1)))
            .conversationTimeline(1)
        val notice = result.messages.single() as SystemNoticeMessageUi
        assertEquals(SystemNoticeCode.AccessibilityWaiting, notice.code)
        assertEquals(AccessibilityWaitPhase.RESTORED.wireValue, notice.detail)
        assertFalse(notice.running)
    }

    @Test fun waitingOnlyOffersLiveControlsForTheCurrentRunningRound() {
        val wait = message("accessibility-run-1", MeetingAuthorType.SYSTEM, index = 1)
        val user = message("user", MeetingAuthorType.USER)
        val live = state(listOf(user, wait), listOf(waitEvent(AccessibilityWaitPhase.WAITING)))
        assertTrue((live.conversationTimeline(1).messages.last() as SystemNoticeMessageUi).running)
        val stopped = live.copy(room = room.copy(status = MeetingRoomStatus.WAITING_FOR_USER))
        assertFalse((stopped.conversationTimeline(1).messages.last() as SystemNoticeMessageUi).running)
        val nextRound = live.copy(messages = live.messages + user.copy(id = "next", sortIndex = 2))
        assertFalse((nextRound.conversationTimeline(1).messages[1] as SystemNoticeMessageUi).running)
    }

    @Test fun legacyOrMalformedWaitMetadataFallsBackToReadableText() {
        val wait = message("accessibility-run-1", MeetingAuthorType.SYSTEM, "原始可读说明")
        val valid = waitEvent(AccessibilityWaitPhase.WAITING)
        listOf(
            emptyList(),
            listOf(valid.copy(payloadJson = "{bad")),
            listOf(valid.copy(roomId = "other-room")),
            listOf(valid.copy(payloadJson = valid.payloadJson.replace("run", "different"))),
            listOf(valid.copy(payloadJson = valid.payloadJson.replace("waiting", "unknown-phase"))),
        ).forEach { events ->
            val projected = state(listOf(wait), events).conversationTimeline(1).messages.single() as AgentMessageUi
            assertEquals(wait.content, projected.content)
            assertFalse(projected.renderMarkdown)
        }
    }

    @Test fun plainSystemTextCannotImpersonateATypedActiveWait() {
        val result = state(listOf(message("notice", MeetingAuthorType.SYSTEM, "accessibility_wait waiting")))
            .conversationTimeline(1)
        assertTrue(result.messages.single() is AgentMessageUi)
        assertEquals(ConversationAuthorRole.System, result.identities.getValue("notice").role)
    }

    @Test fun historyIdentityUsesItsStoredTaskAndNeverTheLatestParticipantsLiveStatus() {
        val result = state(listOf(
            message("old", MeetingAuthorType.AGENT).copy(taskId = "task-old"),
            message("new", MeetingAuthorType.AGENT, index = 1).copy(taskId = "task-new"),
        )).conversationTimeline(1)
        assertEquals("task-old", result.identities.getValue("old").workGroup)
        assertEquals("task-new", result.identities.getValue("new").workGroup)
    }
}
