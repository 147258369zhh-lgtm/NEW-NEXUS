package io.github.mangi.eta.ui.app

import android.app.Application
import android.content.Context
import android.content.ComponentName
import android.net.Uri
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.data.repository.ConversationDraft
import io.github.mangi.eta.data.repository.ConversationDraftRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.IncomingShareRequest
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.ConversationHistoryLoadState
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "en-rUS")
class AgentAppStateConversationLoadingTest {
    private lateinit var context: Context
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(RuntimeEnvironment.getApplication()).declareActionUnbindable(AgentRuntimeWire.ACTION_BIND)
        // This fixture exercises local stores, not Binder. Robolectric's default bind succeeds
        // with a null ComponentName, which Room's optional metrics invalidation client rejects.
        shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
            ComponentName(context, "androidx.room.MultiInstanceInvalidationService"),
        )
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        // Keep startup migration/scheduler/metrics workers out of this local state fixture.
        Prefs.initLocal(context)
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
    }

    @After
    fun tearDown() {
        scope.cancel()
        awaitCondition { job.isCompleted }
        EtaDatabase.closeForTests()
    }

    @Test
    fun fastConversationSwitchesKeepTheLastSelectionAndRestoreEveryBody() {
        seed("first" to conversation("first"), "second" to conversation("second"), "third" to conversation("third"))
        val state = AgentAppState(context, scope)

        state.selectConversation("second")
        state.selectConversation("third")
        awaitLoaded(state, "third")
        assertEquals(listOf("third-0", "third-1", "third-2"), state.homeState.messages.map { it.id })

        state.selectConversation("second")
        awaitLoaded(state, "second")
        assertEquals(listOf("second-0", "second-1", "second-2"), state.homeState.messages.map { it.id })
        state.selectConversation("first")
        awaitLoaded(state, "first")
        assertEquals(listOf("first-0", "first-1", "first-2"), state.homeState.messages.map { it.id })
        assertEquals(9, runBlocking {
            val dao = EtaDatabase.get(context).conversationDao()
            listOf("first", "second", "third").sumOf { dao.messageCount(it) }
        })
    }

    @Test
    fun deletingTheSelectedConversationLoadsTheNextConversation() {
        seed("first" to conversation("first"), "second" to conversation("second"))
        val state = AgentAppState(context, scope)

        state.deleteConversation("first")

        awaitLoaded(state, "second")
        assertEquals(listOf("second-0", "second-1", "second-2"), state.homeState.messages.map { it.id })
        awaitCondition { runBlocking { EtaDatabase.get(context).conversationDao().messageCount("first") == 0 } }
        assertEquals(3, runBlocking { EtaDatabase.get(context).conversationDao().messageCount("second") })
    }

    @Test
    fun deletingAnUnloadedConversationKeepsOtherStoredConversationsIntact() {
        seed("first" to conversation("first"), "second" to conversation("second"), "third" to conversation("third"))
        val state = AgentAppState(context, scope)

        state.deleteConversation("second")
        awaitCondition { runBlocking { EtaDatabase.get(context).conversationDao().messageCount("second") == 0 } }
        state.selectConversation("third")

        awaitLoaded(state, "third")
        assertEquals("third", state.conversationPaneState.selectedConversationId)
        assertEquals(3, state.homeState.messages.size)
        assertEquals(3, runBlocking { EtaDatabase.get(context).conversationDao().messageCount("first") })
        assertEquals(setOf("first", "third"), state.conversationPaneState.conversations.map { it.id }.toSet())
    }

    @Test
    fun loadingAnEarlierPageThenDeletingATurnPreservesTheEarlierPrefix() {
        seed("first" to conversation("first", count = 150))
        val state = AgentAppState(context, scope)
        assertTrue(state.homeState.hasEarlierMessages)
        assertEquals(128, state.homeState.messages.size)

        state.loadEarlierMessages()
        awaitCondition { !state.isLoadingEarlierMessages && !state.homeState.hasEarlierMessages }
        assertEquals((0 until 150).map { "first-$it" }, state.homeState.messages.map { it.id })
        state.deleteMessageTurn("first-149")
        awaitCondition { runBlocking { EtaDatabase.get(context).conversationDao().messageCount("first") == 149 } }

        val messages = runBlocking { AgentConversationStore.loadAllMessages(context, "first") }
        assertEquals((0 until 149).map { "first-$it" }, messages.map { it.id })
    }

    @Test
    fun searchAndExportIncludeMessagesOutsideTheLoadedWindow() {
        seed("first" to conversation("first"), "second" to conversation("second", count = 150))
        val state = AgentAppState(context, scope)

        state.updateSearchQuery("second-message-0-end")
        awaitCondition { state.conversationPaneState.conversations.map { it.id } == listOf("second") }
        assertEquals("first", state.conversationPaneState.selectedConversationId)

        val markdown = runBlocking { state.exportConversationMarkdown("second") }
        assertTrue(requireNotNull(markdown).contains("second-message-0-end"))
        assertTrue(markdown.contains("second-message-149-end"))
        assertFalse(state.isLoadingConversation)
    }

    @Test
    fun repeatedPrefetchRequestsLoadOnlyOnePage() {
        seed("first" to conversation("first", count = 600))
        val state = AgentAppState(context, scope)

        repeat(10) { state.loadEarlierMessages() }
        awaitCondition { !state.isLoadingEarlierMessages }

        assertEquals(256, state.homeState.messages.size)
        assertEquals(256, state.homeState.messages.map { it.id }.distinct().size)
        assertEquals("first-344", state.homeState.messages.first().id)
    }

    @Test
    fun switchingConversationCancelsAnOutstandingHistoryRead() {
        seed("first" to conversation("first", count = 600), "second" to conversation("second"))
        val state = AgentAppState(context, scope)

        state.loadEarlierMessages()
        state.selectConversation("second")
        awaitLoaded(state, "second")

        assertEquals(ConversationHistoryLoadState.Idle, state.historyLoadState)
        assertEquals(listOf("second-0", "second-1", "second-2"), state.homeState.messages.map { it.id })
        assertEquals(600, runBlocking { EtaDatabase.get(context).conversationDao().messageCount("first") })
    }

    @Test
    fun failedPageKeepsTheCurrentWindowAndCanBeRetried() {
        val original = conversation("first", count = 300)
        val longMessage = UserMessageUi("first-100", "历史正文".repeat(6_000))
        seed("first" to original.copy(messages = original.messages.map { if (it.id == longMessage.id) longMessage else it }))
        val state = AgentAppState(context, scope)
        EtaDatabase.get(context).openHelper.writableDatabase.execSQL(
            "DELETE FROM agent_text_chunks WHERE owner_table = 'conversation_messages' AND owner_id = 'first-100'",
        )

        state.loadEarlierMessages()
        awaitCondition { state.historyLoadState == ConversationHistoryLoadState.Failed }
        assertEquals(128, state.homeState.messages.size)
        assertEquals("first-172", state.homeState.messages.first().id)

        runBlocking {
            EtaDatabase.get(context).conversationDao().insertMessages(listOf(
                ConversationMessageEntity(longMessage.id, "first", 100, "user", longMessage.content),
            ))
        }
        state.loadEarlierMessages()
        awaitCondition { !state.isLoadingEarlierMessages }
        assertEquals(ConversationHistoryLoadState.Idle, state.historyLoadState)
        assertEquals(longMessage, state.homeState.messages.first { it.id == longMessage.id })
        assertEquals(300, runBlocking { EtaDatabase.get(context).conversationDao().messageCount("first") })
    }

    @Test
    fun returningToTheLatestMessagesReleasesOldPagesWithoutDeletingHistory() {
        seed("first" to conversation("first", count = 600))
        val state = AgentAppState(context, scope)
        repeat(3) {
            state.loadEarlierMessages()
            awaitCondition { !state.isLoadingEarlierMessages }
        }
        assertEquals(512, state.homeState.messages.size)

        state.updateHistoryViewport(atLatest = true)
        awaitCondition { !state.isLoadingEarlierMessages }
        assertEquals(128, state.homeState.messages.size)
        assertEquals("first-472", state.homeState.messages.first().id)
        assertEquals(600, runBlocking { EtaDatabase.get(context).conversationDao().messageCount("first") })

        state.loadEarlierMessages()
        awaitCondition { !state.isLoadingEarlierMessages }
        assertEquals(256, state.homeState.messages.size)
        assertEquals("first-344", state.homeState.messages.first().id)
    }

    @Test
    fun scrollingBackToHistoryCancelsPageReleaseWithoutCancellingTheNextRead() {
        seed("first" to conversation("first", count = 600))
        val state = AgentAppState(context, scope)
        repeat(3) {
            state.loadEarlierMessages()
            awaitCondition { !state.isLoadingEarlierMessages }
        }
        state.updateHistoryViewport(atLatest = true)
        state.updateHistoryViewport(atLatest = false)
        state.loadEarlierMessages()
        awaitCondition { !state.isLoadingEarlierMessages }

        assertEquals(600, state.homeState.messages.size)
        assertFalse(state.homeState.hasEarlierMessages)
        assertEquals(ConversationHistoryLoadState.Idle, state.historyLoadState)
    }

    @Test
    fun concurrentResultsAndStopRemainBoundToTheirConversations() {
        seed("first" to conversation("first"), "second" to conversation("second"))
        val state = AgentAppState(context, scope)
        val runs = state.runStoreForTest()
        state.registerRunForTest("run-a", "first")
        state.selectConversation("second")
        awaitLoaded(state, "second")
        state.registerRunForTest("run-b", "second")
        state.selectConversation("first")
        awaitLoaded(state, "first")

        state.stopCurrentRun()
        assertTrue(runs.isStopRequested("run-a"))
        assertFalse(runs.isStopRequested("run-b"))
        state.completeRunForTest("run-b", "乙任务结果")
        awaitCondition { runs.observedRunIds == setOf("run-a") }

        assertEquals("first", state.conversationPaneState.selectedConversationId)
        assertTrue(state.homeState.isStreaming)
        assertFalse(state.homeState.messages.filterIsInstance<AgentMessageUi>().any { it.content == "乙任务结果" })
        assertEquals(setOf("run-a"), runs.observedRunIds)

        state.completeRunForTest("run-a", "甲任务结果")
        awaitCondition { !state.homeState.isStreaming }
        state.selectConversation("second")
        awaitLoaded(state, "second")
        assertTrue(state.homeState.messages.filterIsInstance<AgentMessageUi>().any { it.content == "乙任务结果" })
        assertFalse(state.homeState.messages.filterIsInstance<AgentMessageUi>().any { it.content == "甲任务结果" })
    }

    @Test
    fun deletingAnActiveConversationWaitsForItsResultAndKeepsOtherRuns() {
        seed("first" to conversation("first"), "second" to conversation("second"))
        val state = AgentAppState(context, scope)
        val runs = state.runStoreForTest()
        state.registerRunForTest("run-a", "first")
        state.selectConversation("second")
        awaitLoaded(state, "second")
        state.registerRunForTest("run-b", "second")

        state.deleteConversation("first")
        assertTrue(state.conversationPaneState.conversations.any { it.id == "first" })
        assertTrue(runs.isStopRequested("run-a"))
        assertFalse(runs.isStopRequested("run-b"))

        state.completeRunForTest("run-a", "已保存的结果")
        awaitCondition { state.conversationPaneState.conversations.none { it.id == "first" } }
        assertEquals(setOf("run-b"), runs.observedRunIds)
        assertTrue(state.homeState.isStreaming)
        state.completeRunForTest("run-a", "迟到的结果")
        assertFalse(state.conversationPaneState.conversations.any { it.id == "first" })
        state.completeRunForTest("run-b", "另一个任务结果")
    }

    @Test
    fun backgroundQueueContinuesWithItsCompletedHistoryWhileAnotherConversationRuns() {
        seed("first" to conversation("first"), "second" to conversation("second"))
        val state = AgentAppState(context, scope)
        val runs = state.runStoreForTest()
        state.registerRunForTest("run-a", "first")
        state.selectConversation("second")
        awaitLoaded(state, "second")
        state.registerRunForTest("run-b", "second")
        state.sendCurrentMessage("后台后续消息")
        state.selectConversation("first")
        awaitLoaded(state, "first")

        state.completeRunForTest("run-b", "后台已完成的回复")
        awaitCondition {
            runBlocking { AgentConversationStore.loadConversation(context, "second") }
                ?.journal?.any { it.role == "user" && it.content == "后台后续消息" } == true
        }
        val saved = requireNotNull(runBlocking { AgentConversationStore.loadConversation(context, "second") })
        val answerIndex = saved.journal.indexOfFirst { it.content == "后台已完成的回复" }
        val followupIndex = saved.journal.indexOfFirst { it.content == "后台后续消息" }
        assertTrue(answerIndex >= 0 && answerIndex < followupIndex)
        assertEquals("first", state.conversationPaneState.selectedConversationId)
        assertTrue(state.homeState.isStreaming)
        assertEquals("run-a", runs.activeRunId("first"))
        awaitCondition { runs.observedRunIds == setOf("run-a") }
        state.completeRunForTest("run-a", "前台任务完成")
    }

    @Test
    fun shareMergesTheLiveEditorInsteadOfTheStaleBusinessInputAndIsConsumedOnce() {
        seed("first" to conversation("first", count = 0))
        val state = AgentAppState(context, scope)
        val store = ConversationDraftRepository.get(context)
        val subject = ConversationSubject.chat("first")
        store.registerActive(store.open(subject)) { ConversationDraft("typed locally") }.use {
            state.acceptSharedContent(IncomingShareRequest(text = "incoming share"))
            assertEquals("first", state.conversationPaneState.selectedConversationId)
            assertEquals("typed locally\nincoming share", state.homeState.input)
            val version = state.homeState.inputSyncVersion
            // Equivalent to the mounted editor's explicit handoff before acknowledging the model.
            runBlocking { store.save(store.open(subject), ConversationDraft(state.homeState.input)).await() }
            state.consumeInputSync("first", version, state.homeState.input)
            assertEquals("", state.homeState.input)
            assertEquals("typed locally\nincoming share", runBlocking { store.load(store.open(subject)) }!!.text)
        }
    }

    @Test
    fun incomingShareWithoutARestoredEditorOpensAnotherChatAndKeepsTheUnseenDraft() {
        seed("first" to conversation("first", count = 0))
        val state = AgentAppState(context, scope)
        val store = ConversationDraftRepository.get(context)
        val subject = ConversationSubject.chat("first")
        runBlocking { store.save(store.open(subject), ConversationDraft("unseen draft")).await() }
        state.acceptSharedContent(IncomingShareRequest(text = "new share"))
        assertTrue(state.conversationPaneState.selectedConversationId != "first")
        assertEquals("new share", state.homeState.input)
        assertEquals("unseen draft", runBlocking { store.load(store.open(subject)) }!!.text)
    }

    @Test
    fun delayedInputAcknowledgementCannotClearANewerShareOrAnotherConversation() {
        seed("first" to conversation("first", count = 0))
        val state = AgentAppState(context, scope)
        state.acceptSharedContent(IncomingShareRequest(text = "first share"))
        val previousId = state.conversationPaneState.selectedConversationId
        val previousVersion = state.homeState.inputSyncVersion
        state.acceptSharedContent(IncomingShareRequest(text = "second share"))
        val currentId = state.conversationPaneState.selectedConversationId
        state.consumeInputSync(previousId, previousVersion, "first share")
        state.consumeInputSync(currentId, state.homeState.inputSyncVersion - 1, "second share")
        state.consumeInputSync(currentId, state.homeState.inputSyncVersion, "wrong text")
        assertEquals("second share", state.homeState.input)
        state.consumeInputSync(currentId, state.homeState.inputSyncVersion, "second share")
        assertEquals("", state.homeState.input)
    }

    @Test
    fun rejectedBlankInputHasNoReceiptButAQueuedMessageIsAcceptedByItsOwningRun() {
        seed("first" to conversation("first"))
        val state = AgentAppState(context, scope)
        assertFalse(state.sendCurrentMessage(" \n "))
        state.registerRunForTest("running", "first")
        assertTrue(state.sendCurrentMessage("next task"))
        assertEquals(listOf("next task"), state.homeState.queuedMessages.map { it.content })
        state.stopCurrentRun()
        state.completeRunForTest("running", "stopped")
    }

    @Test
    fun deletingAChatInvalidatesItsDraftAfterCommitAndKeepsTheSameIdMeetingDraft() {
        seed("first" to conversation("first"), "second" to conversation("second"))
        val state = AgentAppState(context, scope)
        val store = ConversationDraftRepository.get(context)
        val old = store.open(ConversationSubject.chat("first"))
        val other = store.open(ConversationSubject.meeting("first"))
        runBlocking {
            store.save(old, ConversationDraft("deleted chat")).await()
            store.save(other, ConversationDraft("meeting remains")).await()
        }
        store.registerActive(old) { ConversationDraft("mounted editor") }.use {
            state.deleteConversation("first")
            awaitCondition { store.activeDraft(old.subject) == null }
            runBlocking { store.save(old, ConversationDraft("late disposal")).await(); store.flush() }
            assertEquals(null, runBlocking { store.load(store.open(old.subject)) })
            assertEquals("meeting remains", runBlocking { store.load(other) }!!.text)
        }
    }

    @Test
    fun slowImageResultIsDiscardedEvenWhenTheUserSwitchesAwayAndBackToTheSameChat() {
        seed("first" to conversation("first"), "second" to conversation("second"))
        val state = AgentAppState(context, scope)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uri = Uri.parse("content://draft-image/slow.png")
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jOioAAAAASUVORK5CYII=")
        shadowOf(context.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(png) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return super.read(bytes, offset, length)
            }
        })
        try {
            state.attachImage(uri.toString())
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            state.selectConversation("second")
            awaitLoaded(state, "second")
            state.selectConversation("first")
            awaitLoaded(state, "first")
            release.countDown()
            awaitCondition {
                ShadowToast.getTextOfLatestToast() == context.getString(io.github.mangi.eta.R.string.state_ui_conversation_switched_selected_path_not_added_5bf91e)
            }
            assertTrue(state.homeState.pendingImages.isEmpty())
            state.selectConversation("second")
            awaitLoaded(state, "second")
            assertTrue(state.homeState.pendingImages.isEmpty())
        } finally { release.countDown() }
    }

    private fun AgentAppState.runStoreForTest(): AgentRunStore =
        AgentAppState::class.java.getDeclaredField("runStore").apply { isAccessible = true }.get(this) as AgentRunStore

    private fun AgentAppState.registerRunForTest(runId: String, conversationId: String) {
        check(runStoreForTest().register(runId, conversationId))
        AgentAppState::class.java.getDeclaredMethod("setConversationStreaming", String::class.java,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(this, runId, true)
    }

    private fun AgentAppState.completeRunForTest(runId: String, content: String) {
        val result = AgentRuntimeWire.RunResult(
            runId, true, content,
            transcript = listOf(AgentModelClient.ConversationMessage(role = "assistant", content = content)),
        )
        AgentAppState::class.java.getDeclaredMethod("applyRunResult", String::class.java,
            AgentRuntimeWire.RunResult::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(this, runId, result, false)
    }

    private fun conversation(id: String, count: Int = 3): AgentChatHomeUiState {
        val messages = (0 until count).map { index -> UserMessageUi("$id-$index", "$id-message-$index-end") }
        val history = messages.map { message ->
            AgentModelClient.ConversationMessage(role = "user", content = message.content, messageId = message.id)
        }
        return AgentChatHomeUiState(
            messages = messages, history = history, journal = history,
            input = "", isStreaming = false, thinkingEnabled = false,
        )
    }

    private fun seed(vararg conversations: Pair<String, AgentChatHomeUiState>) = runBlocking {
        val states = linkedMapOf(*conversations)
        AgentConversationStore.save(
            context, states.keys.first(), states,
            states.keys.associateWith { it },
            states.keys.associateWith { 1L },
        )
    }

    private fun awaitLoaded(state: AgentAppState, id: String) {
        awaitCondition {
            state.conversationPaneState.selectedConversationId == id &&
                state.homeState.isConversationLoaded && !state.isLoadingConversation
        }
        assertFalse(state.conversationLoadFailed)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
            Thread.sleep(1)
        }
        assertTrue("异步会话操作未在限定时间内完成", condition())
    }
}
