package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.ChunkedTextDao
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class ConversationQuestionWindowTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @After fun close() = EtaDatabase.closeForTests()

    private data class Fixture(
        val question: ConversationMessageEntity,
        val following: List<ConversationMessageEntity>,
        val questions: List<ConversationMessageEntity>,
    ) {
        val windowRows get() = listOf(question) + following
    }

    private suspend fun fixture(): Fixture {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(
            ConversationEntity("primary", "主会话", false, createdAt = 1L, updatedAt = 2L),
            ConversationEntity("other", "另一会话", false, createdAt = 1L, updatedAt = 1L),
        ))
        val earlier = ConversationMessageEntity("earlier-question", "primary", 10, "user", "更早的问题")
        val question = ConversationMessageEntity("old-question", "primary", 1_000, "user",
            "需要完整恢复的旧问题：" + "分块问题预览和正文。".repeat(3_000))
        // Gaps deliberately vary. Adding 128 to sort_index is not a valid page offset.
        val following = (1..260).map { index ->
            ConversationMessageEntity("after-$index", "primary", 1_000 + index * 7 + (index / 11) * 5,
                if (index == 64) "user" else "assistant", "本会话消息 $index")
        }
        dao.insertMessages(listOf(earlier,
            ConversationMessageEntity("before-answer", "primary", 47, "assistant", "早期答复"), question) + following + listOf(
            ConversationMessageEntity("other-question", "other", 1_000, "user", "不能混入的另一会话问题"),
            ConversationMessageEntity("other-answer", "other", 1_007, "assistant", "不能混入的另一会话答复"),
        ))
        val history = AgentConversationCodec.encodeConversationCheckpoint(listOf(
            AgentModelClient.ConversationMessage("assistant", "当前模型上下文保持原样。".repeat(2_000)),
        ))
        val journal = AgentConversationCodec.encodeTranscriptForStorage(listOf(
            AgentModelClient.ConversationMessage("user", "原始运行记录保持原样。".repeat(2_000)),
        ))
        dao.insertContextCheckpoints(listOf(ConversationContextCheckpointEntity("primary", history, journal)))
        dao.insertState(ConversationStateEntity(selectedConversationId = "primary"))
        return Fixture(question, following, listOf(earlier, question, following[63]))
    }

    @Test fun questionIndexContainsOnlyOrderedUserQuestionsWithBoundedChunkedPreviews() = runBlocking {
        val saved = fixture()
        val dao = EtaDatabase.get(context).conversationDao()
        assertTrue(dao.messageRow("primary", saved.question.id)!!.content.startsWith(ChunkedTextDao.REFERENCE_PREFIX))

        val index = dao.questionIndex("primary")

        assertEquals(saved.questions.map { it.id }, index.map { it.id })
        assertEquals(saved.questions.map { it.content.take(160) }, index.map { it.label })
        assertEquals(160, index.single { it.id == saved.question.id }.label.length)
        assertTrue(index.none { it.id.startsWith("other-") || it.label.startsWith(ChunkedTextDao.REFERENCE_PREFIX) })
    }

    @Test fun oldQuestionPagesUseRowOffsetsAndReadWithoutReplacingLiveContextOrCheckpoint() = runBlocking {
        val saved = fixture()
        val database = EtaDatabase.get(context)
        val dao = database.conversationDao()
        val live = AgentConversationStore.load(context).conversationsById.getValue("primary")
        val liveBefore = live.copy()
        assertFalse(live.messages.any { it.id == saved.question.id })
        val checkpointBefore = dao.contextCheckpoint("primary")
        val rawCheckpointBefore = dao.contextCheckpointRow("primary")
        val rowsBefore = dao.messageRows()
        val metadataBefore = dao.conversationEntityRows()
        val stateBefore = dao.state()
        // Navigation must work even when any attempted persistence is rejected by SQLite.
        listOf("conversations", "conversation_messages", "conversation_context_checkpoints",
            "conversation_state", "agent_text_chunks").forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                database.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER reject_navigation_${table}_${operation.lowercase()} BEFORE $operation ON $table " +
                        "BEGIN SELECT RAISE(ABORT, 'question navigation must be read only'); END",
                )
            }
        }

        val first = AgentConversationStore.questionWindow(context, "primary", saved.question.id)
        val second = AgentConversationStore.questionWindow(context, "primary", saved.question.id, page = 1)
        val final = AgentConversationStore.questionWindow(context, "primary", saved.question.id, page = 2)

        assertEquals(129, first.size)
        assertEquals(saved.windowRows.take(129).map { it.id }, first.map { it.id })
        assertEquals(saved.question.content, (first.first() as UserMessageUi).content)
        assertEquals(129, second.size)
        assertEquals(saved.windowRows.drop(128).take(129).map { it.id }, second.map { it.id })
        assertEquals(first[128].id, second.first().id) // the lookahead becomes the next visible row
        assertEquals(saved.windowRows.drop(256).map { it.id }, final.map { it.id })
        assertEquals(saved.windowRows.map { it.id },
            (first.take(128) + second.take(128) + final).map { it.id })
        assertTrue((first + second + final).none { it.id.startsWith("other-") })
        assertEquals(liveBefore, live)
        assertEquals(checkpointBefore, dao.contextCheckpoint("primary"))
        assertEquals(rawCheckpointBefore, dao.contextCheckpointRow("primary"))
        assertEquals(rowsBefore, dao.messageRows())
        assertEquals(metadataBefore, dao.conversationEntityRows())
        assertEquals(stateBefore, dao.state())
    }

    @Test fun nonUserMissingAndCrossConversationTargetsDoNotOpenAQuestionWindow() = runBlocking {
        fixture()
        assertTrue(AgentConversationStore.questionWindow(context, "primary", "before-answer").isEmpty())
        assertTrue(AgentConversationStore.questionWindow(context, "primary", "missing").isEmpty())
        assertTrue(AgentConversationStore.questionWindow(context, "primary", "other-question").isEmpty())
        assertTrue(AgentConversationStore.questionWindow(context, "other", "old-question").isEmpty())
    }
}
