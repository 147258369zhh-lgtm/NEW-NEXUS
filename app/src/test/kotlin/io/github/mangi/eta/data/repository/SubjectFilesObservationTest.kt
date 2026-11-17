package io.github.mangi.eta.data.repository

import android.app.Application
import android.content.Context
import android.net.Uri
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.fork.meeting.MeetingAuthorType
import io.github.mangi.eta.fork.meeting.MeetingDatabase
import io.github.mangi.eta.fork.meeting.MeetingParticipantDraft
import io.github.mangi.eta.fork.meeting.MeetingRepository
import io.github.zhh_lgtm.nexus.kernel.api.PendingImageDraft
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SubjectFilesObservationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        EtaDatabase.closeForTests()
        MeetingDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        context.deleteDatabase("eta_meeting.db")
        File(context.filesDir, "workspace-artifacts.json").delete()
    }

    @After fun tearDown() {
        EtaDatabase.closeForTests()
        MeetingDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        context.deleteDatabase("eta_meeting.db")
        File(context.filesDir, "workspace-artifacts.json").delete()
    }

    @Test fun conversationFilesObserveAttachmentsAddedAfterOpening() = runBlocking(Dispatchers.IO) {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(ConversationEntity("chat", "Chat", false, createdAt = 1, updatedAt = 1)))
        val initial = CompletableDeferred<Unit>()
        val added = async {
            withTimeout(10_000) { SubjectFilesRepository.observeFiles(context, "conversation", "chat")
                .onEach { if (it.isEmpty()) initial.complete(Unit) }.first { it.isNotEmpty() } }
        }
        withTimeout(10_000) { initial.await() }
        val uri = Uri.parse("content://subject-files.test/conversation-image")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        dao.insertMessages(listOf(ConversationMessageEntity("message", "chat", 0, "user",
            "inspect", imagesJson = JSONArray().put(uri.toString()).toString())))
        val files = added.await()
        assertEquals(listOf("输入图片 1"), files.map { it.artifact.label })
        assertTrue(files.single().input)
        assertTrue(SubjectFilesRepository.files(context, "conversation", "other").isEmpty())
    }

    @Test fun meetingFilesObserveAttachmentsAddedAfterOpening() = runBlocking(Dispatchers.IO) {
        val room = MeetingRepository.createRoom(context, "Room", MeetingParticipantDraft("lead", "Lead", "host"), emptyList())
        val initial = CompletableDeferred<Unit>()
        val added = async {
            withTimeout(10_000) { SubjectFilesRepository.observeFiles(context, "meeting", room)
                .onEach { if (it.isEmpty()) initial.complete(Unit) }.first { it.isNotEmpty() } }
        }
        withTimeout(10_000) { initial.await() }
        val uri = Uri.parse("content://subject-files.test/meeting-image")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        MeetingRepository.appendMessage(context, room, MeetingAuthorType.USER, null, "You", "inspect",
            attachments = ConversationAttachments(images = listOf(PendingImageDraft("image", uri.toString(), "", "image/png"))))
        val files = added.await()
        assertEquals(listOf("输入图片 1"), files.map { it.artifact.label })
        assertTrue(files.single().input)
        assertTrue(SubjectFilesRepository.files(context, "meeting", "other").isEmpty())
    }

    @Test fun unreadableImageDoesNotHideOtherMeetingAttachments() = runBlocking(Dispatchers.IO) {
        val room = MeetingRepository.createRoom(context, "Room", MeetingParticipantDraft("lead", "Lead", "host"), emptyList())
        val missing = Uri.parse("content://subject-files.test/revoked")
        shadowOf(context.contentResolver).registerInputStream(missing, object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("Attachment is no longer readable")
        })
        val valid = Uri.parse("content://subject-files.test/valid")
        shadowOf(context.contentResolver).registerInputStream(valid, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        MeetingRepository.appendMessage(context, room, MeetingAuthorType.USER, null, "You", "inspect",
            attachments = ConversationAttachments(images = listOf(
                PendingImageDraft("missing", missing.toString(), "", "image/png"),
                PendingImageDraft("valid", valid.toString(), "", "image/png"),
            )))

        assertEquals(listOf("输入图片 2"), SubjectFilesRepository.files(context, "meeting", room).map { it.artifact.label })
    }
}
