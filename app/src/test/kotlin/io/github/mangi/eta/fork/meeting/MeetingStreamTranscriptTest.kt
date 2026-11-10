package io.github.mangi.eta.fork.meeting

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentEvent.AssistantBlockKind
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
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
class MeetingStreamTranscriptTest {
    private lateinit var context: Context
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); MeetingDatabase.closeForTests(); context.deleteDatabase("eta_meeting.db") }
    @After fun cleanup() { MeetingDatabase.closeForTests(); context.deleteDatabase("eta_meeting.db") }
    private suspend fun room(): String = MeetingRepository.createRoom(context, "Room", MeetingParticipantDraft("lead", "Lead", "host"), emptyList())
        .also { MeetingRepository.setRoomStatus(context, it, MeetingRoomStatus.RUNNING) }
    private suspend fun snapshot(room: String) = MeetingRepository.snapshot(context, room).first()!!
    private fun stream(run: String = "run", round: Int = 1, kind: AssistantBlockKind = AssistantBlockKind.TEXT,
        phase: MeetingStreamPhase = MeetingStreamPhase.STREAMING, content: String = "partial") =
        MeetingStreamActivity(run, round, 0, kind, phase, content)
    private suspend fun save(room: String, stream: MeetingStreamActivity) =
        MeetingRepository.recordStreamActivity(context, room, "lead", "Lead", true, null, stream)

    @Test fun liveSnapshotsUpdateOneMessageAndOneMetadataRecordWithoutDuplicatingBody() = runBlocking(Dispatchers.IO) {
        val room = room()
        val activity = stream()
        save(room, activity)
        val first = snapshot(room).messages.single()
        repeat(20) { save(room, activity.copy(content = "expanded body $it")) }
        val snapshot = snapshot(room)
        assertEquals(1, snapshot.messages.size)
        assertEquals(first.id, snapshot.messages.single().id)
        assertEquals(first.createdAt, snapshot.messages.single().createdAt)
        assertEquals(first.sortIndex, snapshot.messages.single().sortIndex)
        assertEquals(1, MeetingDatabase.get(context).meetingDao().streamActivityEvents(room).size)
        assertFalse(snapshot.timelineEvents.single().payloadJson.contains("expanded body"))
        val timeline = snapshot.toUiState().conversationTimeline(1)
        val reply = timeline.messages.single() as AgentMessageUi
        assertTrue(reply.isStreaming)
        assertEquals("expanded body 19", reply.content)
        assertTrue(timeline.completedReplyIds.isEmpty())
        assertTrue(MeetingContextHistory.forParticipant(snapshot.messages, "lead").isEmpty())
    }

    @Test fun authoritativeResultPromotesTheLastRoundsPreviewInPlaceWithoutAnotherMessage() = runBlocking(Dispatchers.IO) {
        val room = room()
        save(room, stream(content = "transition", phase = MeetingStreamPhase.COMPLETED))
        save(room, stream(round = 2, kind = AssistantBlockKind.THINKING, content = "reason", phase = MeetingStreamPhase.COMPLETED))
        save(room, stream(round = 2, content = "draft final"))
        val before = snapshot(room).messages.last()
        val finalBody = "# Complete result\n" + "full".repeat(80_000)
        val message = MeetingRepository.appendMessage(context, room, MeetingAuthorType.HOST, "lead", "Lead", finalBody, streamRunId = "run")
        assertEquals(before.id, message.id)
        assertEquals(before.sortIndex, message.sortIndex)
        assertEquals(before.createdAt, message.createdAt)
        assertEquals(finalBody, message.content)
        save(room, stream(round = 2, content = "late delta"))
        save(room, stream(round = 3, content = "late different block"))
        val repeated = MeetingRepository.appendMessage(context, room, MeetingAuthorType.HOST, "lead", "Lead", "duplicate", streamRunId = "run")
        assertEquals(message, repeated)
        val snapshot = snapshot(room)
        assertEquals(3, snapshot.messages.size)
        val timeline = snapshot.toUiState().conversationTimeline(0)
        assertEquals(setOf(message.id), timeline.completedReplyIds)
        assertEquals(finalBody, (timeline.messages.last() as AgentMessageUi).content)
        assertTrue(timeline.messages[1] is ThinkingMessageUi)
        assertEquals(1, MeetingContextHistory.forParticipant(snapshot.messages, "lead").size)
        val markdown = MeetingMarkdownExporter.export(snapshot)
        assertTrue(markdown.contains(finalBody))
        assertFalse(markdown.contains("draft final"))
        assertFalse(markdown.contains("\"run_id\""))
    }

    @Test fun successfulResultCanReplaceAnInterruptedPreviewWhenProviderOmittedItsBlockEnd() = runBlocking(Dispatchers.IO) {
        val room = room()
        save(room, stream(phase = MeetingStreamPhase.INTERRUPTED))
        val final = MeetingRepository.appendMessage(context, room, MeetingAuthorType.AGENT, "lead", "Lead", "authoritative", streamRunId = "run")
        assertEquals(stream().messageId, final.id)
        assertEquals(1, snapshot(room).messages.size)
    }

    @Test fun nonStreamingResultStillClosesTheRunAgainstDelayedPreviewWrites() = runBlocking(Dispatchers.IO) {
        val room = room()
        val final = MeetingRepository.appendMessage(context, room, MeetingAuthorType.HOST, "lead", "Lead", "one-shot", streamRunId = "run")
        save(room, stream(content = "late start"))
        assertEquals(listOf(final), snapshot(room).messages)
    }

    @Test fun processRecoveryAndStoppedRoomPresentationNeverLeavePartialTextAnimating() = runBlocking(Dispatchers.IO) {
        val room = room()
        save(room, stream())
        save(room, stream(kind = AssistantBlockKind.THINKING, content = "partial reasoning"))
        assertEquals(1, MeetingRepository.recoverInterruptedRuns(context))
        val snapshot = snapshot(room)
        val timeline = snapshot.toUiState().conversationTimeline(1, streamInterruptedDetail = "Interrupted")
        assertFalse((timeline.messages[0] as AgentMessageUi).isStreaming)
        assertTrue((timeline.messages[0] as AgentMessageUi).content.endsWith("Interrupted"))
        assertFalse((timeline.messages[1] as ThinkingMessageUi).isStreaming)
        assertTrue(snapshot.timelineEvents.all { MeetingStreamActivity.decode(it.payloadJson, "")!!.phase == MeetingStreamPhase.INTERRUPTED })
        assertEquals(0, MeetingRepository.recoverInterruptedRuns(context))
        val markdown = MeetingMarkdownExporter.export(snapshot)
        assertTrue(markdown.contains("思考过程"))
        assertTrue(markdown.contains("此次输出已中断"))
    }

    @Test fun actualExecutionEmitsVisibleTextBeforeReturningAndPreservesFinalAuthoritativeOutput() = runBlocking(Dispatchers.IO) {
        val room = room()
        val participant = snapshot(room).participants.single()
        val release = CountDownLatch(1)
        val running = async {
            MeetingExecutionTranscript.run(context, participant, "live-run", null) { emit ->
                emit(AgentEvent.AssistantBlockDelta(1, AssistantBlockKind.TEXT, 0, 7, "Visible"))
                check(release.await(5, TimeUnit.SECONDS))
                emit(AgentEvent.AssistantBlockEnd(1, AssistantBlockKind.TEXT, 0, contentChars = 13, replacementContent = "Visible final"))
                emit(AgentEvent.RunFinished(1, 13))
                "Visible final"
            }
        }
        try {
            val visible = withTimeout(4_000) { MeetingRepository.snapshot(context, room).first { it?.messages?.isNotEmpty() == true }!! }
            assertTrue((visible.toUiState().conversationTimeline(1).messages.single() as AgentMessageUi).isStreaming)
            assertEquals("Visible", visible.messages.single().content)
        } finally { release.countDown() }
        val result = running.await()
        MeetingRepository.appendMessage(context, room, MeetingAuthorType.HOST, "lead", "Lead", result, streamRunId = "live-run")
        assertEquals(1, snapshot(room).messages.size)
        assertEquals("Visible final", snapshot(room).messages.single().content)
    }

    @Test fun hostSelectionStreamsCanBeDisabledWithoutHidingItsToolLifecycle() = runBlocking(Dispatchers.IO) {
        val room = room()
        val participant = snapshot(room).participants.single()
        MeetingExecutionTranscript.run(context, participant, "selection-run", null, recordStreams = false) { emit ->
            emit(AgentEvent.AssistantBlockDelta(1, AssistantBlockKind.TEXT, 0, 30, "{\"participants\":[\"coder\"]}"))
            emit(AgentEvent.ToolStarted(1, "call", "read_file", "read"))
            emit(AgentEvent.ToolFinished(1, "call", "read_file", "done", 0, 0, true))
        }
        val snapshot = snapshot(room)
        assertEquals(1, snapshot.messages.size)
        assertFalse(snapshot.messages.single().content.contains("participants"))
        assertTrue(MeetingDatabase.get(context).meetingDao().streamActivityEvents(room).isEmpty())
    }

    @Test fun deletedOrIdleRoomsCannotAcquireNewStreamsAndWrongAuthorsCannotOverwriteThem() = runBlocking(Dispatchers.IO) {
        val room = room()
        save(room, stream())
        MeetingRepository.recordStreamActivity(context, room, "another", "Another", false, null, stream(content = "wrong owner"))
        assertEquals("partial", snapshot(room).messages.single().content)
        MeetingRepository.setRoomStatus(context, room, MeetingRoomStatus.IDLE)
        save(room, stream(round = 2))
        assertEquals(1, snapshot(room).messages.size)
        MeetingRepository.deleteRoom(context, room)
        save(room, stream())
        assertNull(MeetingRepository.snapshot(context, room).first())
    }
}
