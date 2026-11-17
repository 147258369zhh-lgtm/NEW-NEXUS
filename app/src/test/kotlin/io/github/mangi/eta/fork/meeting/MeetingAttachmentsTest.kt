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
        images = listOf(PendingImageUi("image", File(context.filesDir, "picture.image").toURI().toString(),
            File(context.filesDir, "preview.image").toURI().toString(), "image/png")),
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

    @Test fun stoppedQueuedInputsRetainAttachmentsWithoutDuplicatingAnAlreadyStoredCurrentTurn() = runBlocking(Dispatchers.IO) {
        val room = room()
        val current = QueuedMeetingTurn("current", attachments())
        val pending = QueuedMeetingTurn("pending", attachments())
        MeetingRepository.appendMessage(context, room, MeetingAuthorType.USER, null, "You", "current",
            messageId = current.id, attachments = current.attachments)
        repeat(2) { MeetingRepository.retainUnstartedTurns(context, room, listOf(current, pending)) }
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        assertEquals(setOf(current.id, pending.id), snapshot.messages.filter { it.authorType == MeetingAuthorType.USER }.map { it.id }.toSet())
        assertEquals(3, snapshot.messages.size)
        assertEquals(pending.attachments, snapshot.messageAttachments[pending.id])
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun participantHistoryIncludesImageOnceAndRetainsIsolatedAssistantResults() = runBlocking(Dispatchers.IO) {
        val room = room(); val value = attachments()
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            File(context.filesDir, "picture.image").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
        MeetingRepository.appendMessage(context, room, MeetingAuthorType.USER, null, "You", "Inspect image", attachments = value)
        MeetingRepository.appendMessage(context, room, MeetingAuthorType.AGENT, "other", "Other", "Other private result")
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        val history = MeetingRepository.participantHistory(context, room, "lead", snapshot.messages)
        assertEquals(1, history.size)
        assertTrue("Historical image missing: ${value.images.single().uri}; path=${android.net.Uri.parse(value.images.single().uri).path}; history=${history.single().content}",
            history.single().contentJson.contains("image_url"))
        val modelContent = io.github.mangi.eta.agent.model.AgentConversationCodec.toJsonObject(history.single()).getJSONArray("content")
        assertEquals("image_url", modelContent.getJSONObject(1).getString("type"))
        assertFalse(history.single().contentJson.contains("file:"))
        assertFalse(history.any { it.content.contains("Other private result") })
    }

    @Test fun missingHistoricalImageIsReportedWithoutSendingInvalidPlaceholder() = runBlocking(Dispatchers.IO) {
        val room = room()
        File(context.filesDir, "picture.image").delete()
        MeetingRepository.appendMessage(context, room, MeetingAuthorType.USER, null, "You", "Inspect missing image", attachments = attachments())
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        val history = MeetingRepository.participantHistory(context, room, "lead", snapshot.messages)
        assertTrue(history.single().content.contains("部分历史图片无法读取"))
        assertEquals("", history.single().contentJson)
    }

    @Test fun acceptedPendingInputSurvivesProcessRecoveryWithNoAutomaticExecution() = runBlocking(Dispatchers.IO) {
        val room = room()
        val turn = MeetingRepository.persistPendingTurn(context, room,
            QueuedMeetingTurn("排队后尚未执行", ConversationAttachments(files = attachments().files)))
        assertEquals(1, MeetingDatabase.get(context).meetingDao().pendingTurnEvents().size)
        MeetingRepository.recoverInterruptedRuns(context)
        MeetingRepository.recoverInterruptedRuns(context)
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        assertEquals(2, snapshot.messages.size)
        assertEquals(turn.id, snapshot.messages.single { it.authorType == MeetingAuthorType.USER }.id)
        assertEquals(turn.attachments, snapshot.messageAttachments[turn.id])
        assertTrue(snapshot.tasks.isEmpty())
        assertTrue(MeetingDatabase.get(context).meetingDao().pendingTurnEvents().isEmpty())
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
