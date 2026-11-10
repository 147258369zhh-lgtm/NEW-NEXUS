package io.github.mangi.eta.fork.meeting

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.data.repository.ConversationAttachmentDrafts
import io.github.mangi.eta.data.repository.ConversationAttachments
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.screens.meeting.MeetingMarkdownExporter
import io.github.mangi.eta.ui.screens.meeting.conversationTimeline
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class MeetingAttachmentsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() { MeetingDatabase.closeForTests(); context.deleteDatabase("eta_meeting.db") }
    @After fun cleanup() { MeetingDatabase.closeForTests(); context.deleteDatabase("eta_meeting.db") }
    private suspend fun room() = MeetingRepository.createRoom(context, "Attachments", MeetingParticipantDraft("lead", "Lead", "host"), emptyList())
    private fun attachments() = ConversationAttachments(
        images = listOf(PendingImageUi("image", "file://${context.filesDir}/picture.image", "file://${context.filesDir}/preview.image", "image/png")),
        files = listOf(PendingFileReferenceUi("file", AgentFileReference("Brief", "/storage/emulated/0/brief.txt", AgentFileReferenceKind.File))),
    )

    @Test fun userMessageAndAttachmentMetadataCommitTogetherAndProjectSharedPreview() = runBlocking(Dispatchers.IO) {
        val room = room(); val value = attachments()
        val message = MeetingRepository.appendMessage(context, room, MeetingAuthorType.USER, null, "You",
            AgentFileReferencePromptCodec.format("Inspect", value.files.map { it.reference }), attachments = value)
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        assertEquals(value, snapshot.messageAttachments[message.id])
        val preview = snapshot.toUiState().conversationTimeline(1).messages.single() as UserMessageUi
        assertEquals(listOf(value.images.single().dataUrl), preview.images)
        assertEquals(listOf("/storage/emulated/0/brief.txt"), AgentFileReferencePromptCodec.parse(preview.content).references.map { it.absolutePath })
        val metadata = MeetingDatabase.get(context).meetingDao().messageAttachmentEvents(room).single().payloadJson
        assertFalse(metadata.contains(context.filesDir.path))
        MeetingDatabase.closeForTests()
        assertEquals(value, MeetingRepository.snapshot(context, room).first()!!.messageAttachments[message.id])
    }

    @Test fun participantHistoryIncludesImageOnceAndRetainsIsolatedAssistantResults() = runBlocking(Dispatchers.IO) {
        val room = room(); val value = attachments()
        MeetingRepository.appendMessage(context, room, MeetingAuthorType.USER, null, "You", "Inspect image", attachments = value)
        MeetingRepository.appendMessage(context, room, MeetingAuthorType.AGENT, "other", "Other", "Other private result")
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        val history = MeetingRepository.participantHistory(context, room, "lead", snapshot.messages)
        assertEquals(1, history.size)
        assertTrue(history.single().contentJson.contains("image_url"))
        assertFalse(history.single().contentJson.contains("file:"))
        assertFalse(history.any { it.content.contains("Other private result") })
    }

    @Test fun metadataWriteFailureRollsBackTheUserMessageAndItsOrder() = runBlocking(Dispatchers.IO) {
        val room = room()
        MeetingDatabase.get(context).openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_attachment BEFORE INSERT ON meeting_events
            WHEN NEW.type LIKE 'message_attachments:%' BEGIN SELECT RAISE(ABORT, 'fixture'); END
        """.trimIndent())
        assertTrue(runCatching { MeetingRepository.appendMessage(context, room, MeetingAuthorType.USER, null, "You", "inspect", attachments = attachments()) }.isFailure)
        assertTrue(MeetingRepository.snapshot(context, room).first()!!.messages.isEmpty())
        MeetingDatabase.get(context).openHelper.writableDatabase.execSQL("DROP TRIGGER reject_attachment")
        val message = MeetingRepository.appendMessage(context, room, MeetingAuthorType.USER, null, "You", "retry", attachments = attachments())
        assertEquals(0, message.sortIndex)
    }

    @Test fun deletingMeetingInvalidatesOpenAttachmentDraftAndRemovesMetadataOnly() = runBlocking(Dispatchers.IO) {
        val room = room(); val store = ConversationAttachmentDrafts.get(context)
        val lease = store.open(ConversationSubject.meeting(room)); val value = attachments()
        store.save(lease, value).await()
        MeetingRepository.appendMessage(context, room, MeetingAuthorType.USER, null, "You", "inspect", attachments = value)
        MeetingRepository.deleteRoom(context, room)
        store.save(lease, value).await(); store.flush()
        assertFalse(store.isValid(lease)); assertTrue(store.load(store.open(ConversationSubject.meeting(room))).isEmpty)
        assertTrue(MeetingDatabase.get(context).meetingDao().messageAttachmentEvents(room).isEmpty())
    }

    @Test fun markdownListsLocalImagesWithAnExplicitMigrationBoundary() = runBlocking(Dispatchers.IO) {
        val room = room()
        MeetingRepository.appendMessage(context, room, MeetingAuthorType.USER, null, "You", "inspect", attachments = attachments())
        val markdown = MeetingMarkdownExporter.export(MeetingRepository.snapshot(context, room).first()!!)
        assertTrue(markdown.contains("![附件图片 1](<file:"))
        assertTrue(markdown.contains("迁移完整内容请使用完整备份"))
        assertFalse(markdown.contains("eta-file:"))
    }
}
