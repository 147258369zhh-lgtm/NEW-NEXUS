package io.github.mangi.eta.data.repository

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
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
@Config(sdk = [33, 36, 37], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ConversationAttachmentDraftsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val stores = mutableListOf<ConversationAttachmentDrafts>()
    private val chat = ConversationSubject.chat("shared-id")
    private val meeting = ConversationSubject.meeting("shared-id")
    private fun store() = ConversationAttachmentDrafts(context).also { stores += it }
    private fun value(name: String = "a") = ConversationAttachments(files = listOf(
        PendingFileReferenceUi(name, AgentFileReference("文件 $name", "/storage/emulated/0/$name.txt", AgentFileReferenceKind.File)),
    ))
    private fun image() = PendingImageUi("picture", "data:image/png;base64,${Base64.encodeToString(byteArrayOf(1, 2, 3, 4), Base64.NO_WRAP)}",
        "data:image/png;base64,${Base64.encodeToString(byteArrayOf(5, 6), Base64.NO_WRAP)}", "image/png")

    @Before fun clear() {
        File(context.filesDir, "conversation_attachment_drafts").deleteRecursively()
        File(context.filesDir, "conversation_attachment_images").deleteRecursively()
    }
    @After fun close() { stores.forEach { it.close() } }

    @Test fun newChatAndMatchingChatMeetingIdsRestoreIndependentlyAfterReopening() = runBlocking {
        val store = store()
        val subjects = listOf(chat, meeting, ConversationSubject.chat(null))
        subjects.forEachIndexed { index, subject -> store.save(store.open(subject), value("$index")).await() }
        store.flush()
        val fresh = store()
        subjects.forEachIndexed { index, subject -> assertEquals(value("$index"), fresh.load(fresh.open(subject))) }
    }

    @Test fun rapidChangesAndAcceptedClearNeverBringBackSubmittedAttachments() = runBlocking {
        val store = store(); val lease = store.open(chat)
        repeat(100) { store.save(lease, value("$it")) }
        store.save(lease, ConversationAttachments()).await(); store.flush()
        assertTrue(store().let { it.load(it.open(chat)) }.isEmpty)
    }

    @Test fun deletedEntityInvalidatesPendingImportButKeepsMatchingOtherKind() = runBlocking {
        val store = store(); val old = store.open(chat)
        store.save(old, value()).await(); store.save(store.open(meeting), value("meeting")).await()
        store.remove(chat).await(); store.save(old, value("late")).await()
        assertFalse(store.isValid(old))
        val fresh = store()
        assertTrue(fresh.load(fresh.open(chat)).isEmpty)
        assertEquals(value("meeting"), fresh.load(fresh.open(meeting)))
    }

    @Test fun logicalImportClearRejectsAllOldLeasesAndDoesNotDeleteAcceptedImageBytes() = runBlocking {
        val store = store(); val lease = store.open(chat)
        val owned = store.materialize(ConversationAttachments(images = listOf(image())))
        store.save(lease, owned).await(); store.clearAll().await(); store.save(lease, owned).await()
        assertTrue(store().let { it.load(it.open(chat)) }.isEmpty)
        assertTrue(File(Uri.parse(owned.images.single().uri).path.orEmpty()).exists() || File(context.filesDir, "conversation_attachment_images").isDirectory)
    }

    @Test fun imageBytesBecomePrivateFilesWithSmallMetadataAndPortablePaths() = runBlocking {
        val store = store()
        store.save(store.open(chat), ConversationAttachments(images = listOf(image()))).await()
        val restored = store().let { it.load(it.open(chat)) }.images.single()
        assertTrue(restored.uri.startsWith("file:")); assertTrue(restored.dataUrl.startsWith("file:"))
        assertTrue(File(context.filesDir, "conversation_attachment_images").listFiles().orEmpty().isNotEmpty())
        val metadata = File(context.filesDir, "conversation_attachment_drafts").listFiles()!!.single().readText()
        assertFalse(metadata.contains("base64")); assertFalse(metadata.contains(context.filesDir.path))
        assertTrue(metadata.contains("eta-file:") || metadata.contains("eta-path:") || metadata.contains("file:"))
    }

    @Test fun repeatedImageSavesDeduplicateOwnedBytes() = runBlocking {
        val store = store(); val draft = ConversationAttachments(images = listOf(image()))
        repeat(20) { store.save(store.open(chat), draft).await() }
        assertEquals(2, File(context.filesDir, "conversation_attachment_images").listFiles()!!.size)
    }

    @Test fun unreadableMetadataIsAnErrorAndIsNotSilentlyOverwrittenWithEmptyDraft() = runBlocking {
        val store = store(); store.save(store.open(chat), value()).await()
        val source = File(context.filesDir, "conversation_attachment_drafts").listFiles()!!.single()
        source.writeText("{broken")
        assertTrue(runCatching { store().let { it.load(it.open(chat)) } }.isFailure)
        assertEquals("{broken", source.readText())
    }

    @Test fun writeFailureRetainsPendingDraftAndBlocksBackupFlushUntilRetried() = runBlocking {
        val dir = File(context.filesDir, "conversation_attachment_drafts"); dir.writeText("obstruction")
        val store = store(); val lease = store.open(chat)
        assertTrue(runCatching { store.save(lease, value()).await() }.isFailure)
        assertEquals(value(), store.load(lease))
        assertTrue(runCatching { store.flush() }.isFailure)
        check(dir.delete()); store.save(lease, value()).await(); store.flush()
        assertEquals(value(), store().let { it.load(it.open(chat)) })
    }

    @Test fun invalidPortablePathAndUnknownVersionAreRejected() {
        val store = store()
        val json = JSONObject(store.encode(value()))
        json.getJSONArray("files").getJSONObject(0).put("path", "eta-path:../../outside")
        assertTrue(runCatching { store.decode(json.toString()) }.isFailure)
        json.put("version", 99)
        assertTrue(runCatching { store.decode(json.toString()) }.isFailure)
    }

    @Test fun privateImportedFilePathsRebaseToThisInstallAndExternalPathsStayExact() {
        val store = store()
        val privateFile = File(context.filesDir, "imports/测试.txt")
        val draft = ConversationAttachments(files = value().files + PendingFileReferenceUi("private",
            AgentFileReference("测试", privateFile.path, AgentFileReferenceKind.File)))
        val encoded = store.encode(draft)
        assertFalse(encoded.contains(context.filesDir.path))
        assertEquals(draft, store.decode(encoded))
    }
}
