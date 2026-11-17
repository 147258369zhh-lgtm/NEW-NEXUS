package io.github.mangi.eta.fork.meeting

import android.content.Context
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.data.repository.ConversationDraft
import io.github.mangi.eta.data.repository.ConversationDraftRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MeetingArchiveDatabaseTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        MeetingDatabase.closeForTests()
        context.deleteDatabase("eta_meeting.db")
    }

    @After
    fun tearDown() {
        MeetingDatabase.closeForTests()
        context.deleteDatabase("eta_meeting.db")
    }

    @Test
    fun temporaryHostRoleSurvivesReopeningOnlyInItsOwnRoom() = runBlocking {
        val temporary = MeetingHostOverride("临时审查主持", "先比较独立证据，再汇总未决问题。")
        val room = MeetingRepository.createRoom(context, "Temporary",
            MeetingParticipantDraft("meeting-host", temporary.displayName, "主持"), emptyList(), hostOverride = temporary)
        val ordinary = MeetingRepository.createRoom(context, "Ordinary",
            MeetingParticipantDraft("meeting-host", "主持协调", "主持"), emptyList())
        MeetingDatabase.closeForTests()
        assertEquals(temporary, MeetingRepository.hostOverride(context, room))
        assertNull(MeetingRepository.hostOverride(context, ordinary))
        assertEquals("临时审查主持", MeetingRepository.snapshot(context, room).first()!!.participants.single().displayName)
    }

    @Test
    fun archivingAndRestoringARoomUpdatesSidebarSummary() = runBlocking {
        val roomId = MeetingRepository.createRoom(
            context = context,
            title = "Release review",
            host = MeetingParticipantDraft("lead", "Lead", "host"),
            participants = listOf(MeetingParticipantDraft("coder", "Coder", "reviewer")),
            now = 100,
        )

        assertNull(MeetingRepository.rooms(context).first().single().archivedAt)

        MeetingRepository.setRoomArchived(context, roomId, archived = true, now = 200)
        val archived = MeetingRepository.rooms(context).first().single()
        assertEquals(200L, archived.archivedAt)
        assertNotNull(MeetingRepository.room(context, roomId).first()?.archivedAt)

        MeetingRepository.setRoomArchived(context, roomId, archived = false, now = 300)
        assertNull(MeetingRepository.rooms(context).first().single().archivedAt)
    }

    @Test
    fun pinningAroomPersistsAndMovesItAheadOfUnpinnedRooms() = runBlocking {
        val first = MeetingRepository.createRoom(
            context, "First", MeetingParticipantDraft("lead", "Lead", "host"), emptyList(), 100,
        )
        val second = MeetingRepository.createRoom(
            context, "Second", MeetingParticipantDraft("lead", "Lead", "host"), emptyList(), 200,
        )

        MeetingRepository.setRoomPinned(context, first, pinned = true, now = 300)
        val summaries = MeetingRepository.rooms(context).first()
        assertEquals(listOf(first, second), summaries.map { it.id })
        assertEquals(300L, summaries.first().pinnedAt)

        MeetingRepository.setRoomPinned(context, first, pinned = false, now = 400)
        assertNull(MeetingRepository.room(context, first).first()?.pinnedAt)
    }

    @Test
    fun roomCompactionModelBindingPersistsAndCanReturnToGlobalInheritance() = runBlocking {
        val roomId = MeetingRepository.createRoom(
            context, "Room roles", MeetingParticipantDraft("lead", "Lead", "host"), emptyList(), 100,
        )
        val binding = io.github.mangi.eta.data.model.ModelRoleBinding("summary-model", "summary-provider")
        MeetingRepository.setRoomCompactionBinding(context, roomId, binding, now = 200)
        assertEquals(binding, MeetingRepository.roomCompactionBinding(context, roomId))

        MeetingRepository.setRoomCompactionBinding(context, roomId, null, now = 300)
        assertNull(MeetingRepository.roomCompactionBinding(context, roomId))
    }

    @Test fun deletingRoomInvalidatesMountedDraftWithoutDeletingSameIdChatDraft() = runBlocking {
        val roomId = MeetingRepository.createRoom(context, "Room", MeetingParticipantDraft("lead", "Lead", "host"), emptyList(), 100)
        val store = ConversationDraftRepository.get(context)
        val lease = store.open(ConversationSubject.meeting(roomId))
        val chat = store.open(ConversationSubject.chat(roomId))
        store.save(lease, ConversationDraft("meeting text")).await()
        store.save(chat, ConversationDraft("chat text")).await()
        MeetingRepository.deleteRoom(context, roomId)
        store.save(lease, ConversationDraft("late disposal")).await()
        store.flush()
        assertNull(MeetingRepository.room(context, roomId).first())
        assertNull(store.load(store.open(lease.subject)))
        assertEquals("chat text", store.load(chat)!!.text)
    }

    @Test fun recoverySettlesUnfinishedTasksEvenWhenRoomAlreadyFailedAndIsIdempotent() = runBlocking {
        val roomId = MeetingRepository.createRoom(
            context, "Interrupted", MeetingParticipantDraft("lead", "Lead", "host"), emptyList(), 100,
        )
        val dao = MeetingDatabase.get(context).meetingDao()
        val room = checkNotNull(dao.room(roomId)).copy(status = MeetingRoomStatus.FAILED, updatedAt = 200)
        dao.updateRoom(room)
        dao.insertTask(MeetingTaskEntity(
            id = "running-task", roomId = roomId, participantProfile = "lead", instruction = "check",
            status = MeetingTaskStatus.RUNNING, createdAt = 110, updatedAt = 110,
        ))
        dao.insertTask(MeetingTaskEntity(
            id = "unknown-task", roomId = roomId, participantProfile = "lead", instruction = "unknown",
            status = "future-unknown", createdAt = 120, updatedAt = 120,
        ))

        assertEquals(1, MeetingRepository.recoverInterruptedRuns(context))
        val recovered = dao.tasks(roomId)
        assertEquals(listOf("running-task", "unknown-task"), recovered.map { it.id })
        assertTrue(recovered.all { it.status == MeetingTaskStatus.FAILED })
        assertEquals(MeetingRoomStatus.WAITING_FOR_USER, dao.room(roomId)?.status)

        assertEquals(0, MeetingRepository.recoverInterruptedRuns(context))
        val recoveryMessages = dao.messages(roomId).count {
            it.authorType == MeetingAuthorType.SYSTEM && it.content.contains("应用退出时中断")
        }
        assertEquals(1, recoveryMessages)
    }
}
