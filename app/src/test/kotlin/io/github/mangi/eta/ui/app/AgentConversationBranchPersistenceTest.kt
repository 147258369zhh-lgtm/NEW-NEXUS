package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.data.db.ETA_DATABASE_VERSION
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ConversationBranchSetupEntity
import io.github.mangi.eta.ui.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentConversationBranchPersistenceTest {
    private val context = RuntimeEnvironment.getApplication()
    @Before fun reset() { EtaDatabase.closeForTests(); context.deleteDatabase("eta.db") }
    private fun source() = AgentChatHomeUiState(messages = listOf(UserMessageUi("u", "问题"), AgentMessageUi("a", "原回答")),
        journal = listOf(ConversationMessage("user", "问题", messageId = "u"), ConversationMessage("assistant", "原回答", messageId = "a")),
        input = "", isStreaming = false, thinkingEnabled = false)
    private fun snapshot(states: Map<String, AgentChatHomeUiState>, drafts: List<ConversationBranchDraft> = emptyList()) =
        AgentConversationStore.Snapshot(states.keys.lastOrNull(), states, states.keys.associateWith { it },
            states.keys.associateWith { 1L }, branchDrafts = drafts)
    private fun plan() = AgentConversationBranchPlanner.create("root", source(), mapOf("u" to 0, "a" to 1), "a", null, "child", 2)

    @Test fun messageCheckpointAndBranchWriteFailTogetherAndSameWriterCanRetry() = runBlocking(Dispatchers.IO) {
        val writer = AgentConversationPersistence()
        val initial = snapshot(mapOf("root" to source()))
        writer.save(context, initial)
        val plan = plan()
        val next = snapshot(mapOf("root" to source(), "child" to plan.state), listOf(plan.draft))
        val sql = EtaDatabase.get(context).openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_branch BEFORE INSERT ON conversation_branches WHEN NEW.conversation_id = 'child' BEGIN SELECT RAISE(ABORT, 'test rejection'); END")
        var rejected = false
        try { writer.save(context, next) } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        val dao = EtaDatabase.get(context).conversationDao()
        assertEquals(listOf("root"), dao.conversationIdentities().map { it.id })
        assertNull(dao.contextCheckpoint("child"))
        assertNull(dao.branch("root"))
        sql.execSQL("DROP TRIGGER reject_branch")
        writer.save(context, next)
        assertEquals(2, dao.messageCount("child"))
        assertNotNull(dao.contextCheckpoint("child"))
        assertEquals("root", dao.branch("child")?.parentConversationId)
        assertEquals(2, dao.messageOrigins("child").size)
    }

    @Test fun setupFailureRollsBackWholeBranchAndRetryWritesTheOriginalSnapshot() = runBlocking(Dispatchers.IO) {
        val writer = AgentConversationPersistence()
        writer.save(context, snapshot(mapOf("root" to source())))
        val plan = plan()
        val setup = ConversationBranchSetupEntity("child", "frozen-generation", folderId = "folder", contextWindow = 8192,
            projectId = "project", createdAt = 2)
        val next = snapshot(mapOf("root" to source(), "child" to plan.state), listOf(plan.draft.copy(setup = setup)))
        val sql = EtaDatabase.get(context).openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_setup BEFORE INSERT ON conversation_branch_setups BEGIN SELECT RAISE(ABORT, 'test rejection'); END")
        var rejected = false
        try { writer.save(context, next) } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        val dao = EtaDatabase.get(context).conversationDao()
        assertFalse(dao.conversationExists("child"))
        assertNull(dao.contextCheckpoint("child"))
        assertNull(dao.branch("root"))
        assertNull(dao.branchSetup("child"))
        assertTrue(dao.messageOrigins("child").isEmpty())
        sql.execSQL("DROP TRIGGER reject_setup")
        writer.save(context, next)
        assertEquals(setup, dao.branchSetup("child"))
        assertEquals(2, dao.messageCount("child"))
        assertEquals(2, dao.messageOrigins("child").size)
    }

    @Test fun independentEditPreservesOriginsAndParentDeleteKeepsChildrenThroughRestart() = runBlocking(Dispatchers.IO) {
        val writer = AgentConversationPersistence()
        val plan = plan()
        writer.save(context, snapshot(mapOf("root" to source(), "child" to plan.state), listOf(plan.draft)))
        val answer = plan.state.messages.last() as AgentMessageUi
        val edited = plan.state.copy(messages = plan.state.messages.dropLast(1) + answer.copy(content = "分支修改"))
        writer.save(context, snapshot(mapOf("root" to source(), "child" to edited)))
        var dao = EtaDatabase.get(context).conversationDao()
        assertEquals(2, dao.messageOrigins("child").size)
        assertEquals("原回答", dao.messagesPage("root", 8, 0).last().content)
        writer.save(context, snapshot(mapOf("child" to edited)))
        assertNull(dao.branch("child")?.parentConversationId)
        assertEquals("root", dao.branch("child")?.sourceConversationId)
        assertEquals("root", dao.branch("child")?.familyId)
        EtaDatabase.closeForTests()
        dao = EtaDatabase.get(context).conversationDao()
        assertEquals("分支修改", dao.messagesPage("child", 8, 0).last().content)
        assertEquals(2, dao.messageOrigins("child").size)
        assertNull(dao.messageRow("root", "a"))
    }

    @Test fun deletingCopiedMessageCleansOnlyItsOriginAndDeletingChildKeepsRoot() = runBlocking(Dispatchers.IO) {
        val writer = AgentConversationPersistence()
        val plan = plan()
        writer.save(context, snapshot(mapOf("root" to source(), "child" to plan.state), listOf(plan.draft)))
        writer.save(context, snapshot(mapOf("root" to source(), "child" to plan.state.copy(messages = plan.state.messages.take(1)))))
        val dao = EtaDatabase.get(context).conversationDao()
        assertEquals(1, dao.messageOrigins("child").size)
        writer.save(context, snapshot(mapOf("root" to source())))
        assertNotNull(dao.branch("root"))
        assertEquals(2, dao.messageCount("root"))
        assertTrue(dao.messageOrigins("child").isEmpty())
    }

    @Test fun staleDraftDoesNotRestoreDeletedParentOrOverwriteStoredNode() = runBlocking(Dispatchers.IO) {
        val writer = AgentConversationPersistence()
        val plan = plan()
        writer.save(context, snapshot(mapOf("root" to source(), "child" to plan.state), listOf(plan.draft)))
        writer.save(context, snapshot(mapOf("child" to plan.state), listOf(plan.draft)))
        val dao = EtaDatabase.get(context).conversationDao()
        assertNull(dao.branch("child")?.parentConversationId)
        assertEquals(listOf("child"), dao.conversationIdentities().map { it.id })
    }

    @Test fun version24UpgradeAddsBranchesWithoutChangingMessagesOrCheckpoint() = runBlocking(Dispatchers.IO) {
        AgentConversationPersistence().save(context, snapshot(mapOf("root" to source())))
        val sql = EtaDatabase.get(context).openHelper.writableDatabase
        sql.execSQL("DROP TABLE conversation_branch_setups")
        sql.execSQL("DROP TABLE conversation_message_origins")
        sql.execSQL("DROP TABLE conversation_branches")
        sql.version = 24
        EtaDatabase.closeForTests()
        val database = EtaDatabase.get(context)
        val dao = database.conversationDao()
        assertEquals(2, dao.messageCount("root"))
        assertEquals(ETA_DATABASE_VERSION, database.openHelper.writableDatabase.version)
        assertEquals("原回答", dao.messagesPage("root", 2, 0).last().content)
        assertNotNull(dao.contextCheckpoint("root"))
        assertTrue(dao.branchesPage(8, 0).isEmpty())
        assertTrue(dao.messageOriginsPage(8, 0).isEmpty())
    }
}
