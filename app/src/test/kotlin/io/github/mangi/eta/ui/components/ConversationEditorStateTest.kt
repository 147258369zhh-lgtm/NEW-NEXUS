package io.github.mangi.eta.ui.components

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.text.TextRange
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.data.repository.ConversationDraft
import io.github.mangi.eta.data.repository.ConversationDraftRepository
import io.github.mangi.eta.data.repository.DraftTestPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36, 37], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class ConversationEditorStateTest {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("editor-test", Context.MODE_PRIVATE)
    private val subject = ConversationSubject.chat("editor")
    private val store get() = ConversationDraftRepository(prefs, io)

    @Before fun clear() { prefs.edit().clear().commit() }
    @After fun close() { main.cancel(); io.cancel(); shadowOf(Looper.getMainLooper()).idle() }

    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 4_000_000_000
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(1) }
        assertTrue(condition())
    }
    private fun seed(text: String, selectionStart: Int = text.length, selectionEnd: Int = selectionStart) = runBlocking {
        val repository = store
        repository.save(repository.open(subject), ConversationDraft(text, selectionStart, selectionEnd)).await()
    }

    @Test fun restorePreservesSelectionAndUnchangedDisposalDoesNotClearDraft() {
        seed("stored text", 8, 2)
        val repository = store
        val editor = ConversationEditorState(repository, subject)
        main.launch { editor.restore() }
        idleUntil { editor.status.value == ConversationDraftStatus.READY }
        assertEquals("stored text", editor.field.text.toString())
        assertEquals(TextRange(8, 2), editor.field.selection)
        editor.persistNow()
        runBlocking { repository.flush() }
        assertEquals("stored text", runBlocking { store.let { it.load(it.open(subject)) } }!!.text)
    }

    @Test fun disposeBeforeRestoreLeavesStoredDraftUntouched() {
        seed("keep")
        val repository = store
        ConversationEditorState(repository, subject).persistNow(force = true)
        runBlocking { repository.flush() }
        assertEquals("keep", runBlocking { repository.load(repository.open(subject)) }!!.text)
    }

    @Test fun userTextBeforeReadCompletesWinsEvenBeforeSnapshotObserverRuns() {
        seed("old")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repository = ConversationDraftRepository(DraftTestPreferences(prefs, beforeRead = {
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
        }), io)
        val editor = ConversationEditorState(repository, subject)
        try {
            main.launch { editor.restore() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            editor.field.edit { replace(0, length, "typed meanwhile") }
            release.countDown()
            idleUntil { editor.status.value == ConversationDraftStatus.READY }
            runBlocking { repository.flush() }
            assertEquals("typed meanwhile", editor.field.text.toString())
            assertEquals("typed meanwhile", runBlocking { repository.load(repository.open(subject)) }!!.text)
        } finally { release.countDown() }
    }

    @Test fun explicitClearDuringPendingRestoreDoesNotBringBackOldText() {
        seed("old")
        val repository = store
        val editor = ConversationEditorState(repository, subject)
        editor.replace("")
        main.launch { editor.restore() }
        idleUntil { editor.status.value == ConversationDraftStatus.READY }
        runBlocking { repository.flush() }
        assertEquals("", editor.field.text.toString())
        assertNull(runBlocking { store.let { it.load(it.open(subject)) } })
    }

    @Test fun transientImeInputThenEraseBeforeSnapshotObservationStillBlocksLateRestore() {
        seed("old stored text")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repository = ConversationDraftRepository(DraftTestPreferences(prefs, beforeRead = {
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
        }), io)
        val editor = ConversationEditorState(repository, subject)
        try {
            main.launch { editor.restore() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            editor.onUserEdit()
            editor.field.edit { replace(0, length, "typed") }
            editor.field.edit { replace(0, length, "") }
            release.countDown()
            idleUntil { editor.status.value == ConversationDraftStatus.READY }
            runBlocking { repository.flush() }
            assertEquals("", editor.snapshot().text)
            assertNull(runBlocking { store.let { it.load(it.open(subject)) } })
        } finally { release.countDown() }
    }

    @Test fun explicitIncomingTextHasPriorityOverOldDiskDraft() {
        seed("old")
        val repository = store
        val editor = ConversationEditorState(repository, subject, "incoming share")
        main.launch { editor.restore() }
        idleUntil { editor.status.value == ConversationDraftStatus.READY }
        runBlocking { repository.flush() }
        assertEquals("incoming share", editor.field.text.toString())
        assertEquals("incoming share", runBlocking { store.let { it.load(it.open(subject)) } }!!.text)
    }

    @Test fun unreadableDraftIsNotOverwrittenUntilUserSuppliesNewText() {
        prefs.edit().putString(subject.key, "broken draft").commit()
        val repository = store
        val editor = ConversationEditorState(repository, subject)
        main.launch { editor.restore() }
        idleUntil { editor.status.value == ConversationDraftStatus.LOAD_FAILED }
        editor.persistNow()
        runBlocking { repository.flush() }
        assertEquals("broken draft", prefs.getString(subject.key, null))
        editor.replace("new text")
        idleUntil { editor.status.value == ConversationDraftStatus.READY }
        assertEquals("new text", runBlocking { store.load(store.open(subject)) }!!.text)
    }

    @Test fun reopeningFailedSaveKeepsCachedTextVisibleAndReportsRetryFailure() {
        var reject = true
        val repository = ConversationDraftRepository(DraftTestPreferences(prefs, reject = { reject }), io)
        val first = ConversationEditorState(repository, subject)
        first.replace("unsaved input")
        idleUntil { first.status.value == ConversationDraftStatus.SAVE_FAILED }
        val second = ConversationEditorState(repository, subject)
        main.launch { second.restore() }
        idleUntil { second.status.value == ConversationDraftStatus.SAVE_FAILED }
        assertEquals("unsaved input", second.field.text.toString())
        reject = false
        second.persistNow(force = true)
        idleUntil { second.status.value == ConversationDraftStatus.READY }
        runBlocking { repository.flush() }
        assertEquals("unsaved input", runBlocking { store.let { it.load(it.open(subject)) } }!!.text)
    }

    @Test fun activeReaderOnlyExposesRestoredOrExplicitlySuppliedInput() {
        seed("stored")
        val repository = store
        val editor = ConversationEditorState(repository, subject)
        editor.registerActive().use {
            assertNull(repository.activeDraft(subject))
            main.launch { editor.restore() }
            idleUntil { editor.status.value == ConversationDraftStatus.READY }
            editor.field.edit { replace(0, length, "live text without a debounce") }
            assertEquals("live text without a debounce", repository.activeDraft(subject)!!.text)
        }
        assertNull(repository.activeDraft(subject))
    }

    @Test fun receiptCannotEraseTextEnteredAfterTheSubmittedSnapshot() {
        val repository = store
        val editor = ConversationEditorState(repository, subject)
        editor.replace("submitted")
        val submitted = editor.snapshot()
        editor.replace("new draft")
        editor.clearSubmitted(submitted)
        assertEquals("new draft", editor.snapshot().text)
        editor.clearSubmitted(editor.snapshot())
        runBlocking { repository.flush() }
        assertEquals("", editor.snapshot().text)
        assertNull(runBlocking { store.let { it.load(it.open(subject)) } })
    }

    @Test fun restoreFailureDoesNotExposeAnUnreadEmptyEditorToIncomingShares() {
        prefs.edit().putString(subject.key, "broken draft").commit()
        val repository = store
        val editor = ConversationEditorState(repository, subject)
        editor.registerActive().use {
            main.launch { editor.restore() }
            idleUntil { editor.status.value == ConversationDraftStatus.LOAD_FAILED }
            assertNull(repository.activeDraft(subject))
            editor.persistNow(force = true)
            runBlocking { repository.flush() }
            assertEquals("broken draft", prefs.getString(subject.key, null))
            editor.replace("explicit replacement")
            idleUntil { editor.status.value == ConversationDraftStatus.READY }
            assertEquals("explicit replacement", repository.activeDraft(subject)!!.text)
        }
    }

    @Test fun replacingMountedEditorTransfersLiveInputAndRejectsItsLateDisposal() {
        val repository = store
        val first = ConversationEditorState(repository, subject)
        first.registerActive().use {
            main.launch { first.restore() }
            idleUntil { first.status.value == ConversationDraftStatus.READY }
            first.field.edit { replace(0, length, "not yet debounced") }
            val second = ConversationEditorState(repository, subject)
            second.registerActive().use {
                main.launch { second.restore() }
                idleUntil { second.status.value == ConversationDraftStatus.READY }
                assertEquals("not yet debounced", second.snapshot().text)
                second.replace("new owner's input")
                first.replace("late old surface")
                first.persistNow(force = true)
                runBlocking { repository.flush() }
                assertEquals("new owner's input", repository.activeDraft(subject)!!.text)
                assertEquals("new owner's input", runBlocking { store.let { it.load(it.open(subject)) } }!!.text)
            }
            assertNull(repository.activeDraft(subject))
        }
    }

    @Test fun explicitIncomingInputIsNotReplacedByThePreviousMountedEditorsHandoff() {
        val repository = store
        val first = ConversationEditorState(repository, subject, "old page")
        first.registerActive().use {
            main.launch { first.restore() }
            idleUntil { first.status.value == ConversationDraftStatus.READY }
            val second = ConversationEditorState(repository, subject, "new share")
            second.registerActive().use {
                main.launch { second.restore() }
                idleUntil { second.status.value == ConversationDraftStatus.READY }
                runBlocking { repository.flush() }
                assertEquals("new share", second.snapshot().text)
                assertEquals("new share", runBlocking { store.let { it.load(it.open(subject)) } }!!.text)
            }
        }
    }
}
