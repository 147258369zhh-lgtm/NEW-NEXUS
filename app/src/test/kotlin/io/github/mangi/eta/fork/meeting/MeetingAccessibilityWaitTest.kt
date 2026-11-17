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

    @Test fun participantWaitingBelongsToCurrentTask() = runBlocking(Dispatchers.IO) {
        val room = room()
        MeetingRepository.appendMessage(context, room, MeetingAuthorType.USER, null, "You", "work", now = 100)
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        val worker = MeetingParticipantEntity(roomId = room, profileName = "worker", displayName = "Worker", profileSummary = "")
        val task = MeetingTaskEntity(id = "current", roomId = room, participantProfile = "worker", instruction = "work",
            status = MeetingTaskStatus.RUNNING, createdAt = 101, updatedAt = 101)
        fun event(taskId: String?, type: String) = MeetingEventEntity(id = 1, roomId = room, taskId = taskId,
            type = type, payloadJson = "{\"profile\":\"worker\",\"phase\":\"waiting\"}", createdAt = 102)
        fun state(events: List<MeetingEventEntity>) = snapshot.copy(participants = snapshot.participants + worker,
            tasks = listOf(task), timelineEvents = events).toUiState()

        assertFalse(state(listOf(event("old", "accessibility_wait:old"))).isParticipantWaiting("worker"))
        assertFalse(state(listOf(event(null, "accessibility_wait:legacy"))).isParticipantWaiting("worker"))
        assertTrue(state(listOf(event("current", "accessibility_wait:current"))).isParticipantWaiting("worker"))
        assertTrue(state(listOf(event("current", "approval_wait:current" ).copy(payloadJson =
            "{\"profile\":\"worker\",\"phase\":\"REQUESTED\"}"))).isParticipantWaiting("worker"))
    }

    @Test fun accessibilityWaitPersistsTaskAssociation() = runBlocking(Dispatchers.IO) {
        val room = room()
        MeetingRepository.recordAccessibilityWait(context, room, "worker", "Worker", "run",
            AgentEvent.AccessibilityWaiting(1, AccessibilityWaitPhase.WAITING), "task")
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        assertEquals("task", snapshot.timelineEvents.single().taskId)
        assertEquals("task", snapshot.messages.single().taskId)
        MeetingRepository.recordAccessibilityWait(context, room, "worker", "Worker", "run",
            AgentEvent.AccessibilityWaiting(1, AccessibilityWaitPhase.RESTORED))
        assertEquals("task", MeetingRepository.snapshot(context, room).first()!!.messages.single().taskId)
    }

    @Test fun hostWaitingAcceptsCurrentTaskAndIndependentCoordination() = runBlocking(Dispatchers.IO) {
        val room = room()
        MeetingRepository.appendMessage(context, room, MeetingAuthorType.USER, null, "You", "work", now = 100)
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        val task = MeetingTaskEntity(id = "current", roomId = room, participantProfile = "lead", instruction = "work",
            status = MeetingTaskStatus.RUNNING, createdAt = 101, updatedAt = 101)
        fun event(taskId: String?) = MeetingEventEntity(id = 1, roomId = room, taskId = taskId,
            type = "accessibility_wait:run", payloadJson = "{\"profile\":\"lead\",\"phase\":\"waiting\"}", createdAt = 102)
        fun state(tasks: List<MeetingTaskEntity>, taskId: String?) = snapshot.copy(tasks = tasks,
            timelineEvents = listOf(event(taskId))).toUiState()

        assertFalse(state(listOf(task), "old").isParticipantWaiting("lead"))
        assertTrue(state(listOf(task), null).isParticipantWaiting("lead"))
        assertTrue(state(listOf(task), "current").isParticipantWaiting("lead"))
        assertTrue(state(emptyList(), null).isParticipantWaiting("lead"))
    }
}
