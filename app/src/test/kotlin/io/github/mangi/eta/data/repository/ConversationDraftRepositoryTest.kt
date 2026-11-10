package io.github.mangi.eta.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.data.model.ConversationSubject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36, 37], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class ConversationDraftRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("draft-test", Context.MODE_PRIVATE)
    private val chat = ConversationSubject.chat("same-id")
    private val meeting = ConversationSubject.meeting("same-id")

    @Before fun clear() { prefs.edit().clear().commit() }
    @After fun close() { scope.cancel() }

    @Test fun sameRawIdRemainsSeparateAcrossChatMeetingAndUnallocatedChat() = runBlocking {
        val store = ConversationDraftRepository(prefs, scope)
        val subjects = listOf(chat, meeting, ConversationSubject.chat(null), ConversationSubject.chat("draft"))
        subjects.forEachIndexed { index, subject -> store.save(store.open(subject), ConversationDraft("text-$index")).await() }
        val fresh = ConversationDraftRepository(prefs, scope)
        subjects.forEachIndexed { index, subject -> assertEquals("text-$index", fresh.load(fresh.open(subject))!!.text) }
    }

    @Test fun reopenedStoreRestoresExactWhitespaceUnicodeAndReversedSelection() = runBlocking {
        val store = ConversationDraftRepository(prefs, scope)
        val draft = ConversationDraft("  第一行\n@agent 👋 \n", 9, 2)
        store.save(store.open(chat), draft).await()
        assertEquals(draft, ConversationDraftRepository(prefs, scope).let { it.load(it.open(chat)) })
    }

    @Test fun rapidWritesAndClearAreOrderedWithoutResurrectingOldInput() = runBlocking {
        val store = ConversationDraftRepository(prefs, scope)
        val lease = store.open(chat)
        repeat(60) { store.save(lease, ConversationDraft("revision-$it")) }
        store.save(lease, ConversationDraft("")).await()
        store.flush()
        assertNull(ConversationDraftRepository(prefs, scope).let { it.load(it.open(chat)) })
    }

    @Test fun removingOneSubjectRejectsOldMountedEditorButKeepsOtherKind() = runBlocking {
        val store = ConversationDraftRepository(prefs, scope)
        val old = store.open(chat)
        store.save(old, ConversationDraft("deleted")).await()
        store.save(store.open(meeting), ConversationDraft("meeting")).await()
        store.remove(chat).await()
        store.save(old, ConversationDraft("late dispose")).await()
        assertNull(store.load(store.open(chat)))
        assertEquals("meeting", store.load(store.open(meeting))!!.text)
        store.save(store.open(chat), ConversationDraft("new lease")).await()
        assertEquals("new lease", store.load(store.open(chat))!!.text)
    }

    @Test fun logicalReplacementInvalidatesEveryExistingLease() = runBlocking {
        val store = ConversationDraftRepository(prefs, scope)
        val oldChat = store.open(chat)
        val oldMeeting = store.open(meeting)
        store.save(oldChat, ConversationDraft("old chat")).await()
        store.save(oldMeeting, ConversationDraft("old meeting")).await()
        store.clearAll().await()
        store.save(oldChat, ConversationDraft("late chat")).await()
        store.save(oldMeeting, ConversationDraft("late meeting")).await()
        assertTrue(prefs.all.isEmpty())
        assertNull(store.load(oldChat))
        assertNull(store.load(store.open(meeting)))
    }

    @Test fun readAfterCacheEvictionWaitsForEarlierQueuedWrite() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = DraftTestPreferences(prefs, beforeCommit = {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        })
        val store = ConversationDraftRepository(blocked, scope)
        try {
            repeat(40) { store.save(store.open(ConversationSubject.chat("item-$it")), ConversationDraft("value-$it")) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val read = async(Dispatchers.IO) { store.load(store.open(ConversationSubject.chat("item-0"))) }
            assertFalse(read.isCompleted)
            release.countDown()
            assertEquals("value-0", withTimeout(5_000) { read.await() }!!.text)
            store.flush()
        } finally { release.countDown() }
    }

    @Test fun failureOnOneSubjectCannotBeHiddenBySuccessfulWriteToAnother() = runBlocking {
        var failing = true
        val backend = DraftTestPreferences(prefs, reject = { key -> failing && key == chat.key })
        val store = ConversationDraftRepository(backend, scope)
        assertTrue(runCatching { store.save(store.open(chat), ConversationDraft("unsaved")).await() }.isFailure)
        store.save(store.open(meeting), ConversationDraft("saved")).await()
        assertTrue(runCatching { store.flush() }.isFailure)
        assertEquals("unsaved", store.load(store.open(chat))!!.text)
        failing = false
        store.retryCached(store.open(chat))!!.await()
        store.flush()
        assertEquals("unsaved", ConversationDraftRepository(prefs, scope).let { it.load(it.open(chat)) }!!.text)
    }

    @Test fun malformedOrFutureDraftFailsWithoutDeletingOriginalValue() = runBlocking {
        val store = ConversationDraftRepository(prefs, scope)
        for (value in listOf("broken", "{\"version\":2,\"text\":\"future\",\"start\":0,\"end\":0}")) {
            prefs.edit().putString(chat.key, value).commit()
            assertTrue(runCatching { store.load(store.open(chat)) }.isFailure)
            assertEquals(value, prefs.getString(chat.key, null))
        }
        store.save(store.open(chat), ConversationDraft("replacement", -9, 999)).await()
        assertEquals(ConversationDraft("replacement", 0, 11), ConversationDraftRepository(prefs, scope).let { it.load(it.open(chat)) })
    }

    @Test fun activeReadersAreTypedAndClosingAnOldRegistrationDoesNotCloseTheReplacement() = runBlocking {
        val store = ConversationDraftRepository(prefs, scope)
        val old = store.registerActive(store.open(chat)) { ConversationDraft("old editor") }
        val current = store.registerActive(store.open(chat)) { ConversationDraft("current editor") }
        val other = store.registerActive(store.open(meeting)) { ConversationDraft("meeting editor") }
        old.close()
        assertEquals("current editor", store.activeDraft(chat)!!.text)
        assertEquals("meeting editor", store.activeDraft(meeting)!!.text)
        store.remove(chat).await()
        assertNull(store.activeDraft(chat))
        assertEquals("meeting editor", store.activeDraft(meeting)!!.text)
        store.clearAll().await()
        assertNull(store.activeDraft(meeting))
        current.close(); other.close()
    }

    @Test fun newestLoadingEditorCannotExposeAnOlderMountedEditorsText() {
        val store = ConversationDraftRepository(prefs, scope)
        val old = store.registerActive(store.open(chat)) { ConversationDraft("stale editor") }
        val loading = store.registerActive(store.open(chat)) { null }
        assertNull(store.activeDraft(chat))
        loading.close()
        assertNull(store.activeDraft(chat)) // A departing old surface must never regain ownership.
        old.close()
        assertNull(store.activeDraft(chat))
    }

    @Test fun failedInputSurvivesRecentCacheEvictionAndCanStillBeRetried() = runBlocking {
        var reject = true
        val store = ConversationDraftRepository(DraftTestPreferences(prefs, reject = { reject && it == chat.key }), scope)
        assertTrue(runCatching { store.save(store.open(chat), ConversationDraft("only unsaved copy")).await() }.isFailure)
        repeat(40) { store.save(store.open(ConversationSubject.chat("other-$it")), ConversationDraft("text-$it")).await() }
        assertEquals("only unsaved copy", store.load(store.open(chat))!!.text)
        reject = false
        store.retryCached(store.open(chat))!!.await()
        store.flush()
        assertEquals("only unsaved copy", ConversationDraftRepository(prefs, scope).let { it.load(it.open(chat)) }!!.text)
    }
}

/** Keeps commit failure and latency independent of a real preference file's disk speed. */
internal class DraftTestPreferences(
    private val delegate: SharedPreferences,
    private val reject: (String?) -> Boolean = { false },
    private val beforeCommit: () -> Unit = {},
    private val beforeRead: () -> Unit = {},
) : SharedPreferences by delegate {
    override fun getString(key: String?, defValue: String?): String? {
        beforeRead()
        return delegate.getString(key, defValue)
    }
    override fun edit(): SharedPreferences.Editor {
        val editor = delegate.edit()
        return object : SharedPreferences.Editor by editor {
            var key: String? = null
            override fun putString(key: String?, value: String?): SharedPreferences.Editor { this.key = key; editor.putString(key, value); return this }
            override fun remove(key: String?): SharedPreferences.Editor { this.key = key; editor.remove(key); return this }
            override fun clear(): SharedPreferences.Editor { key = null; editor.clear(); return this }
            override fun commit(): Boolean { beforeCommit(); return if (reject(key)) false else editor.commit() }
        }
    }
}
