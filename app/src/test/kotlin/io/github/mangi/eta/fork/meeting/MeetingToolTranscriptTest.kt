package io.github.mangi.eta.fork.meeting

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.components.ConversationAuthorRole
import io.github.mangi.eta.ui.components.AgentTimelineEntry
import io.github.mangi.eta.ui.components.toTimelineEntries
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.screens.meeting.MeetingMarkdownExporter
import io.github.mangi.eta.ui.screens.meeting.conversationTimeline
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [33], application = Application::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.NATIVE)
class MeetingToolTranscriptTest {
    private lateinit var context: Context
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); MeetingDatabase.closeForTests(); context.deleteDatabase("eta_meeting.db") }
    @After fun cleanup() { MeetingDatabase.closeForTests(); context.deleteDatabase("eta_meeting.db") }
    private suspend fun room(): String = MeetingRepository.createRoom(context, "Room", MeetingParticipantDraft("lead", "Lead", "host"),
        listOf(MeetingParticipantDraft("coder", "Coder", "code"))).also { MeetingRepository.setRoomStatus(context, it, MeetingRoomStatus.RUNNING) }
    private fun tool(run: String = "run", phase: MeetingToolPhase = MeetingToolPhase.RUNNING) =
        MeetingToolActivity(run, 1, "call", false, "run_command", phase, "execute", "echo safe")
    private suspend fun record(room: String, value: MeetingToolActivity, profile: String = "coder", host: Boolean = false) =
        MeetingRepository.recordToolActivity(context, room, profile, profile, host, null, value)
    private suspend fun snapshot(room: String) = MeetingRepository.snapshot(context, room).first()!!

    @Test fun completionUpdatesTheSamePositionAndExportsReadableTextWithoutInternalJson() = runBlocking(Dispatchers.IO) {
        val room = room()
        val activity = tool()
        record(room, activity)
        val original = snapshot(room).messages.single()
        MeetingRepository.appendMessage(context, room, MeetingAuthorType.AGENT, "lead", "Lead", "other member")
        record(room, activity.copy(phase = MeetingToolPhase.SUCCESS, result = "finished", imageCount = 2))
        record(room, activity)
        val snapshot = snapshot(room)
        assertEquals(2, snapshot.messages.size)
        assertEquals(original.id, snapshot.messages.first().id)
        assertEquals(original.sortIndex, snapshot.messages.first().sortIndex)
        assertEquals(original.createdAt, snapshot.messages.first().createdAt)
        assertEquals(2, MeetingDatabase.get(context).meetingDao().toolActivityEvents(room).size)
        assertEquals(1, snapshot.timelineEvents.size)
        val projected = snapshot.toUiState().conversationTimeline(1)
        val card = projected.messages.first() as ToolActivityMessageUi
        assertEquals(ToolActivityStatusUi.Success, card.status)
        assertEquals("finished", card.resultSummary)
        assertEquals(2, card.imageCount)
        val markdown = MeetingMarkdownExporter.export(snapshot)
        assertTrue(markdown.contains("run_command"))
        assertTrue(markdown.contains("echo safe"))
        assertTrue(markdown.contains("finished"))
        assertFalse(markdown.contains("\"run_id\""))
        assertTrue(MeetingContextHistory.forParticipant(snapshot.messages, "coder").all { !it.content.contains("echo safe") })
    }

    @Test fun finishBeforeStartAndConflictingReplayCannotReopenOrChangeTheResult() = runBlocking(Dispatchers.IO) {
        val room = room()
        record(room, tool(phase = MeetingToolPhase.FAILED).copy(result = "failed"))
        record(room, tool())
        record(room, tool(phase = MeetingToolPhase.SUCCESS).copy(result = "late"))
        assertEquals(1, snapshot(room).messages.size)
        assertEquals(1, MeetingDatabase.get(context).meetingDao().toolActivityEvents(room).size)
        assertEquals(ToolActivityStatusUi.Failed, (snapshot(room).toUiState().conversationTimeline(1).messages.single() as ToolActivityMessageUi).status)
    }

    @Test fun separateParticipantsKeepIndependentWorkGroupsAndHistoricalAttribution() = runBlocking(Dispatchers.IO) {
        val room = room()
        record(room, tool("run-coder"))
        record(room, tool("run-host"), "lead", host = true)
        val timeline = snapshot(room).toUiState().conversationTimeline(1)
        assertEquals(ConversationAuthorRole.Agent, timeline.identities.getValue(tool("run-coder").messageId).role)
        assertEquals(ConversationAuthorRole.Host, timeline.identities.getValue(tool("run-host").messageId).role)
        val entries = timeline.messages.toTimelineEntries(workGroups = timeline.identities.mapValues { it.value.workGroup })
        assertEquals(2, entries.size)
        assertTrue(entries.all { it is AgentTimelineEntry.WorkProcess })
    }

    @Test fun actualExecutionBoundaryPreservesToolWaitOrderAndClosesAnUnfinishedToolBeforeRethrowing() = runBlocking(Dispatchers.IO) {
        val room = room()
        val participant = snapshot(room).participants.first { !it.isHost }
        val failure = runCatching {
            MeetingExecutionTranscript.run(context, participant, "actual-run", null) { emit ->
                emit(AgentEvent.ToolStarted(1, "call", "read_file", "read"))
                emit(AgentEvent.AccessibilityWaiting(1, AccessibilityWaitPhase.WAITING))
                throw IllegalStateException("execution failed")
            }
        }.exceptionOrNull()
        assertEquals("execution failed", failure!!.message)
        val snapshot = snapshot(room)
        assertTrue(snapshot.messages[0].id.startsWith("meeting-tool-"))
        assertTrue(snapshot.messages[1].id.startsWith("accessibility-"))
        val tools = snapshot.timelineEvents.filter { it.type.startsWith(MeetingToolActivity.EVENT_PREFIX) }
        assertEquals(MeetingToolPhase.INTERRUPTED, MeetingToolActivity.decode(tools.single().payloadJson)!!.phase)
        assertEquals("cancelled", org.json.JSONObject(snapshot.timelineEvents.last().payloadJson).getString("phase"))
    }

    @Test fun cancellingAnActualExecutionStillFlushesTheInterruptedCard() = runBlocking(Dispatchers.IO) {
        val room = room()
        val participant = snapshot(room).participants.first { !it.isHost }
        val entered = CountDownLatch(1)
        val job = launch {
            MeetingExecutionTranscript.run(context, participant, "cancelled-run", null) { emit ->
                emit(AgentEvent.ToolStarted(1, "call", "read_file", "read"))
                entered.countDown()
                Thread.sleep(60_000)
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        val snapshot = snapshot(room)
        assertEquals(MeetingToolPhase.INTERRUPTED, MeetingToolActivity.decode(snapshot.timelineEvents.single().payloadJson)!!.phase)
    }

    @Test fun processRecoveryClosesRunningToolsAtTheirOriginalPositionWithoutReplayingActions() = runBlocking(Dispatchers.IO) {
        val room = room()
        record(room, tool())
        val before = snapshot(room).messages.single()
        assertEquals(1, MeetingRepository.recoverInterruptedRuns(context))
        val after = snapshot(room)
        assertEquals(before.id, after.messages.first().id)
        assertEquals(before.sortIndex, after.messages.first().sortIndex)
        assertEquals(MeetingToolPhase.INTERRUPTED, MeetingToolActivity.decode(after.timelineEvents.single().payloadJson)!!.phase)
        assertEquals(0, MeetingRepository.recoverInterruptedRuns(context))
        record(room, tool(phase = MeetingToolPhase.SUCCESS))
        assertEquals(MeetingToolPhase.INTERRUPTED, MeetingToolActivity.decode(snapshot(room).timelineEvents.single().payloadJson)!!.phase)
    }

    @Test fun staleRunningMetadataIsNeverAnimatedForAnOlderRoundOrStoppedRoom() = runBlocking(Dispatchers.IO) {
        val room = room()
        record(room, tool())
        MeetingRepository.appendMessage(context, room, MeetingAuthorType.USER, null, "You", "next round")
        val oldCard = snapshot(room).toUiState().conversationTimeline(1, toolInterruptedDetail = "unconfirmed").messages.first() as ToolActivityMessageUi
        assertEquals(ToolActivityStatusUi.Unknown, oldCard.status)
        assertTrue(oldCard.resultSummary!!.contains("unconfirmed"))
        MeetingRepository.setRoomStatus(context, room, MeetingRoomStatus.WAITING_FOR_USER)
        assertEquals(ToolActivityStatusUi.Unknown, (snapshot(room).toUiState().conversationTimeline(1).messages.first() as ToolActivityMessageUi).status)
    }

    @Test fun sensitiveDetailsAreAbsentFromActualMessagesJournalAndMarkdown() = runBlocking(Dispatchers.IO) {
        val room = room()
        val sensitive = tool(phase = MeetingToolPhase.SUCCESS).copy(name = "mcp_remote_search",
            arguments = "secret-arguments", command = "secret-command", result = "secret-result")
        record(room, sensitive)
        val snapshot = snapshot(room)
        val stored = snapshot.messages.single().content + snapshot.timelineEvents.single().payloadJson + MeetingMarkdownExporter.export(snapshot)
        listOf("secret-arguments", "secret-command", "secret-result").forEach { assertFalse(stored.contains(it)) }
        val card = snapshot.toUiState().conversationTimeline(1, sensitiveToolDetail = "omitted").messages.single() as ToolActivityMessageUi
        assertEquals("omitted", card.resultSummary)
        assertNull(card.command)
    }

    @Test fun delayedEventsCannotCreateCardsInAnIdleOrDeletedRoom() = runBlocking(Dispatchers.IO) {
        val room = room()
        MeetingRepository.setRoomStatus(context, room, MeetingRoomStatus.IDLE)
        record(room, tool())
        record(room, tool(phase = MeetingToolPhase.SUCCESS))
        assertTrue(snapshot(room).messages.isEmpty())
        MeetingRepository.deleteRoom(context, room)
        record(room, tool())
        assertNull(MeetingRepository.snapshot(context, room).first())
    }
}
