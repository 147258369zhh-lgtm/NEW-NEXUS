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
}
