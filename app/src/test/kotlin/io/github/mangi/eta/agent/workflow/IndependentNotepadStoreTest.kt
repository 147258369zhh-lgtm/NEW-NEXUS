package io.github.mangi.eta.agent.workflow

import android.app.Application
import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [PosixAtomicFileRename::class])
class IndependentNotepadStoreTest {
    @Test fun extensionlessAttachmentsUseKnownMimeTypesOnly() {
        assertEquals("png", IndependentNotepadStore.attachmentSuffix("picture", "image/png"))
        assertEquals("jpg", IndependentNotepadStore.attachmentSuffix("picture", "image/jpeg"))
        assertEquals("bin", IndependentNotepadStore.attachmentSuffix("picture", "application/unknown"))
        assertEquals("txt", IndependentNotepadStore.attachmentSuffix("notes.txt", "image/png"))
    }
    private val context get() = RuntimeEnvironment.getApplication() as Application

    @Before fun clean() {
        File(context.filesDir, "independent-notepad").deleteRecursively()
        File(context.filesDir, "workflow-state").deleteRecursively()
    }

    @Test fun independentRecordsSurviveReopenAndRejectStaleWrites() {
        val first = IndependentNotepadStore(context)
        val second = IndependentNotepadStore(context)
        val entry = NotepadEntry("one", 123, "original", draft = "draft")
        first.save(0, listOf(entry))
        assertEquals(entry, second.read().entries.single())
        assertThrows(IllegalStateException::class.java) { second.save(0, emptyList()) }
        assertEquals(entry, first.read().entries.single())
    }

    @Test fun successiveSavesPersistTheLatestRevision() {
        val store = IndependentNotepadStore(context)
        val first = NotepadEntry("one", 1, "first")
        store.save(0, listOf(first))
        val latest = store.save(1, listOf(first.copy(text = "second")))
        assertEquals(latest, IndependentNotepadStore(context).read())
        assertThrows(NotepadRevisionConflict::class.java) { store.save(1, emptyList()) }
    }

    @Test fun interruptedAtomicWriteRecoversBackupBeforeRevisionCheck() {
        val store = IndependentNotepadStore(context)
        val entry = NotepadEntry("one", 1, "original", draft = "draft")
        store.save(0, listOf(entry))
        val base = File(context.filesDir, "independent-notepad/entries.json")
        assertTrue(base.renameTo(File(base.path + ".bak")))
        assertEquals(entry, store.read().entries.single())
        assertThrows(NotepadRevisionConflict::class.java) { store.save(0, emptyList()) }
    }

    @Test fun importsLegacyNotesWithoutRemovingThem() {
        val directory = File(context.filesDir, "workflow-state").apply { mkdirs() }
        val file = File(directory, "old.json")
        file.writeText("""{"version":1,"scope_id":"conv-old","notepad":{"revision":2,"content":"old note","created_at":123}}""")
        val store = IndependentNotepadStore(context)
        val legacy = store.legacyEntries().single()
        assertEquals("old note", legacy.text)
        assertEquals("legacy:conv-old:2", legacy.source)
        store.save(0, listOf(legacy))
        assertTrue(file.exists())
    }

    @Test fun cleanupPreservesReferencedAttachments() {
        val store = IndependentNotepadStore(context)
        val kept = store.saveAudio(byteArrayOf(1, 2, 3))
        val discarded = store.saveAudio(byteArrayOf(4, 5, 6))
        store.save(0, listOf(NotepadEntry("one", 1, "", listOf(kept))))
        store.deleteUnreferenced(listOf(kept, discarded))
        assertTrue(store.attachment(kept).exists())
        assertFalse(store.attachment(discarded).exists())
    }

    @Test fun attachmentOperationsCannotAccessOrDeleteTheNoteDatabase() {
        val store = IndependentNotepadStore(context)
        val entry = NotepadEntry("one", 1, "private note")
        store.save(0, listOf(entry))
        assertThrows(IllegalArgumentException::class.java) { store.attachment("entries.json") }
        assertThrows(IllegalArgumentException::class.java) { store.attachment("../entries.json") }
        assertThrows(IllegalArgumentException::class.java) { store.deleteUnreferenced(listOf("entries.json")) }
        assertEquals(entry, store.read().entries.single())
    }

    @Test fun transcriptsStayAssociatedWithTheirAudioAcrossReopen() {
        val store = IndependentNotepadStore(context)
        val first = store.saveAudio(byteArrayOf(1))
        val second = store.saveAudio(byteArrayOf(2))
        val entry = NotepadEntry("note", 1, "edited text", listOf(first, second),
            transcripts = mapOf(first to "first transcript", second to "second transcript"), transcriptsInText = setOf(first))
        store.save(0, listOf(entry))
        assertEquals(entry, IndependentNotepadStore(context).read().entries.single())
        val saved = store.save(1, listOf(entry.copy(attachments = listOf(second))))
        val restored = store.read().entries.single()
        assertEquals(saved.entries.single(), restored)
        assertEquals(mapOf(second to "second transcript"), restored.transcripts)
        assertTrue(restored.transcriptsInText.isEmpty())
        assertTrue(store.attachment(first).exists())
    }

    @Test fun editingTextKeepsOnlyTranscriptsStillPresentInText() {
        val entry = NotepadEntry("note", 1, "intro\nfirst transcript\nsecond transcript", listOf("a.wav", "b.wav"),
            transcripts = mapOf("a.wav" to "first transcript", "b.wav" to "second transcript"),
            transcriptsInText = setOf("a.wav", "b.wav"))
        val edited = entry.withEditorChanges("intro\nfirst transcript", entry.attachments)
        assertEquals(setOf("a.wav"), edited.transcriptsInText)
        assertEquals(entry.transcripts, edited.transcripts)
        val removed = edited.withEditorChanges(edited.text, listOf("b.wav"))
        assertTrue(removed.transcriptsInText.isEmpty())
        assertEquals(mapOf("b.wav" to "second transcript"), removed.transcripts)
    }

    @Test fun reopenedEditorCanKeepAndDiscardAnUnsavedAttachment() {
        val original = IndependentNotepadStore(context)
        val name = original.saveAudio(byteArrayOf(1, 2, 3))
        val reopened = IndependentNotepadStore(context)
        assertTrue(reopened.attachment(name).exists())
        reopened.deleteUnreferenced(listOf(name))
        assertFalse(reopened.attachment(name).exists())
    }
}

// Match Android's rename-over-existing behavior when these tests run on Windows.
@Implements(AtomicFile::class)
class PosixAtomicFileRename {
    companion object {
        @JvmStatic
        @Implementation
        fun rename(source: File, target: File) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
