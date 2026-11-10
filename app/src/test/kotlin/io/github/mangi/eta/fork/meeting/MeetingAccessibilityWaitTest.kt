package io.github.mangi.eta.fork.meeting

import android.content.Context
import io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase
import io.github.mangi.eta.agent.accessibility.textResource
import io.github.mangi.eta.agent.runtime.AgentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import android.app.Application
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
class MeetingAccessibilityWaitTest {
    private lateinit var context: Context
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); MeetingDatabase.closeForTests(); context.deleteDatabase("eta_meeting.db") }
    @After fun cleanup() { MeetingDatabase.closeForTests(); context.deleteDatabase("eta_meeting.db") }

    private suspend fun room(): String = MeetingRepository.createRoom(context, "Meeting", MeetingParticipantDraft("lead", "Lead", "host"), emptyList()).also {
        MeetingRepository.setRoomStatus(context, it, MeetingRoomStatus.RUNNING)
    }
    private suspend fun record(room: String, phase: AccessibilityWaitPhase) = MeetingRepository.recordAccessibilityWait(context, room, "lead", "Lead", "run", AgentEvent.AccessibilityWaiting(1, phase))

    @Test fun phaseUpdatePreservesMessageLocationAndCannotReplayOldWaitingAfterRestored() = runBlocking(Dispatchers.IO) {
        val room = room()
        record(room, AccessibilityWaitPhase.WAITING)
        val first = MeetingRepository.snapshot(context, room).first()!!.messages.single()
        MeetingRepository.appendMessage(context, room, MeetingAuthorType.USER, null, "You", "followup")
        record(room, AccessibilityWaitPhase.RESTORED)
        record(room, AccessibilityWaitPhase.WAITING)
        val messages = MeetingRepository.snapshot(context, room).first()!!.messages
        assertEquals(2, messages.size)
        assertEquals(first.id, messages.first().id)
        assertEquals(first.sortIndex, messages.first().sortIndex)
        assertTrue(messages.first().content.contains(context.getString(AccessibilityWaitPhase.RESTORED.textResource())))
    }

    @Test fun processRecoveryEndsWaitingAndDoesNotRepeatTheGuiTask() = runBlocking(Dispatchers.IO) {
        val room = room()
        record(room, AccessibilityWaitPhase.WAITING)
        assertEquals(1, MeetingRepository.recoverInterruptedRuns(context))
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        assertEquals(MeetingRoomStatus.WAITING_FOR_USER, snapshot.room.status)
        assertTrue(snapshot.messages.first().content.contains(context.getString(AccessibilityWaitPhase.CANCELLED.textResource())))
        assertEquals(0, MeetingRepository.recoverInterruptedRuns(context))
    }

    @Test fun delayedEventCannotResurrectDeletedRoomOrStartWaitingInIdleRoom() = runBlocking(Dispatchers.IO) {
        val room = room()
        MeetingRepository.setRoomStatus(context, room, MeetingRoomStatus.WAITING_FOR_USER)
        record(room, AccessibilityWaitPhase.WAITING)
        assertTrue(MeetingRepository.snapshot(context, room).first()!!.messages.isEmpty())
        MeetingRepository.deleteRoom(context, room)
        record(room, AccessibilityWaitPhase.RESTORED)
        assertNull(MeetingRepository.snapshot(context, room).first())
    }

    @Test fun visibleTimelineReceivesOnlyTheNewestTypedPhaseForEachCard() = runBlocking(Dispatchers.IO) {
        val room = room()
        record(room, AccessibilityWaitPhase.WAITING)
        record(room, AccessibilityWaitPhase.RESTORED)
        MeetingRepository.appendEvent(context, room, null, "unrelated_event", org.json.JSONObject().put("secret", "not for display"))
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        assertEquals(1, snapshot.timelineEvents.size)
        assertEquals("restored", org.json.JSONObject(snapshot.timelineEvents.single().payloadJson).getString("phase"))
        assertEquals(snapshot.timelineEvents, snapshot.toUiState().timelineEvents)
        assertEquals(2, MeetingDatabase.get(context).meetingDao().accessibilityWaitEvents(room).size)
    }
}
