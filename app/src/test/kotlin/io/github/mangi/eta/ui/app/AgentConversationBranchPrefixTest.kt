package io.github.mangi.eta.ui.app

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageLink
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.agent.roleplay.RoleplayReplyRevision
import io.github.mangi.eta.data.db.*
import io.github.mangi.eta.ui.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentConversationBranchPrefixTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dao get() = EtaDatabase.get(context).conversationDao()
    @Before fun reset() { EtaDatabase.closeForTests(); context.deleteDatabase("eta.db") }
    @After fun close() { EtaDatabase.closeForTests() }
    private suspend fun prepare(rows: List<ConversationMessageEntity>) {
        dao.insertConversations(listOf(ConversationEntity("root", "Source", false, createdAt = 1, updatedAt = 1)))
        dao.insertMessages(rows)
    }
    private fun row(id: String, index: Int, type: String, text: String = id) = ConversationMessageEntity(id, "root", index, type, text)
    private fun state(stored: AgentConversationStore.BranchSource, journal: List<ConversationMessage>) = AgentChatHomeUiState(
        messages = stored.messages, journal = journal, input = "", isStreaming = false, thinkingEnabled = false)
    private fun plan(stored: AgentConversationStore.BranchSource, journal: List<ConversationMessage>, point: String) =
        AgentConversationBranchPlanner.create("root", state(stored, journal), stored.positions, point, stored.node,
            childId = "child", now = 2, verifiedPrefix = stored.verifiedPrefix)

    @Test(timeout = 30_000) fun earlyBranchDoesNotLoadOrCountThirtyTwoThousandLaterMessages() = runBlocking(Dispatchers.IO) {
        prepare(listOf(row("u", 0, "user"), row("a", 1, "assistant")))
        // 全历史超出旧实现的 32000 条上限；后续正文并不属于本次分支。
        for (start in 2..33_001 step 1000) {
            dao.insertMessages((start until minOf(start + 1000, 33_002)).map { row("later-$it", it, if (it % 2 == 0) "user" else "assistant") })
        }
        val stored = AgentConversationStore.loadBranchSource(context, "root", "a")
        assertEquals(listOf("u", "a"), stored.messages.map { it.id })
        assertEquals(mapOf("u" to 0, "a" to 1), stored.positions)
        assertTrue(stored.verifiedPrefix)
        val result = plan(stored, listOf(ConversationMessage("user", "u", messageId = "u"),
            ConversationMessage("assistant", "a", messageId = "a"), ConversationMessage("user", "later", messageId = "later-2")), "a")
        assertEquals("journal", result.draft.node.historyMode)
        assertEquals(2, result.state.history.size)
        assertEquals(33_002, dao.messageCount("root"))
        var oversized = false
        try { AgentConversationStore.loadBranchSource(context, "root", "later-33001") }
        catch (failure: IllegalStateException) { oversized = failure.message == "BRANCH_HISTORY_TOO_LARGE" }
        assertTrue(oversized)
    }

    @Test fun cursorPagingKeepsSparseSortIndexesAndStopsAtTheExactPoint() = runBlocking(Dispatchers.IO) {
        val rows = (0..400).map { row("m$it", it * 7, if (it % 2 == 0) "user" else "assistant") }
        prepare(rows)
        val stored = AgentConversationStore.loadBranchSource(context, "root", "m299")
        assertEquals(300, stored.messages.size)
        assertEquals((0..299).map { "m$it" }, stored.messages.map { it.id })
        assertEquals(299 * 7, stored.positions["m299"])
        assertEquals(300, stored.positions.size)
        assertTrue(stored.messages.none { it.id == "m300" })
    }

    @Test fun finalReplyValidationReadsMetadataAfterPointWithoutCopyingAnotherTurn() = runBlocking(Dispatchers.IO) {
        prepare(listOf(row("u", 0, "user"), row("a", 3, "assistant"), row("second-block", 7, "assistant"),
            row("next-u", 9, "user"), row("next-a", 12, "assistant")))
        var rejected = false
        try { AgentConversationStore.loadBranchSource(context, "root", "a") } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertEquals(listOf("u", "a", "second-block"), AgentConversationStore.loadBranchSource(context, "root", "second-block").messages.map { it.id })
        dao.deleteMessagesFrom("root", 7)
        dao.insertMessages(listOf(row("tool", 7, "tool")))
        rejected = false
        try { AgentConversationStore.loadBranchSource(context, "root", "a") } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertEquals(listOf("u"), AgentConversationStore.loadBranchSource(context, "root", "u").messages.map { it.id })
    }

    @Test fun missingAndNonBranchablePointsFailInsteadOfCopyingTheWholeConversation() = runBlocking(Dispatchers.IO) {
        prepare(listOf(row("u", 0, "user"), row("tool", 1, "tool")))
        for (point in listOf("missing", "tool")) {
            var rejected = false
            try { AgentConversationStore.loadBranchSource(context, "root", point) } catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
        }
        assertEquals(2, dao.messageCount("root"))
    }

    @Test fun legacyPrefixTextStillMustAlignAndClosedToolsArePreserved() = runBlocking(Dispatchers.IO) {
        prepare(listOf(row("u", 0, "user", "问题"), row("a", 1, "assistant", "答案"), row("later", 2, "user")))
        val stored = AgentConversationStore.loadBranchSource(context, "root", "a")
        val journal = listOf(ConversationMessage("user", "问题"),
            ConversationMessage("assistant", "", toolCallsJson = """[{"id":"call","function":{"name":"search","arguments":"{}"}}]"""),
            ConversationMessage("tool", "旧执行结果", toolCallId = "call"), ConversationMessage("assistant", "答案"),
            ConversationMessage("user", "后续不复制"), ConversationMessage("assistant", "后续结果"))
        assertEquals("journal", plan(stored, journal, "a").draft.node.historyMode)
        assertEquals(4, plan(stored, journal, "a").state.history.size)
        assertEquals("visible_reconstruction", plan(stored, journal.mapIndexed { i, entry -> if (i == 0) entry.copy(content = "不匹配") else entry }, "a").draft.node.historyMode)
        val orphan = journal.filter { it.role != "tool" }
        assertThrows(IllegalArgumentException::class.java) { plan(stored, orphan, "a") }
        Unit
    }

    @Test fun roleplayFutureRevisionsDoNotForceEarlierPrefixToLoseItsToolHistory() = runBlocking(Dispatchers.IO) {
        prepare(listOf(row("u", 0, "user", "问题"), row("a", 1, "assistant", "答案"), row("next-u", 2, "user"), row("next-a", 3, "assistant")))
        val stored = AgentConversationStore.loadBranchSource(context, "root", "a")
        val journal = listOf(ConversationMessage("user", "问题", messageId = "u"), ConversationMessage("assistant", "答案", messageId = "a"),
            ConversationMessage("user", "后续", messageId = "next-u"), ConversationMessage("assistant", "后续答案", messageId = "next-a"))
        val source = state(stored, journal).copy(roleplay = RoleplayBinding("card", "{}", "角色"), roleplayMessages = RoleplayMessageState(
            links = mapOf("a" to RoleplayMessageLink("a"), "next-a" to RoleplayMessageLink("next-a")),
            revisions = mapOf("a" to RoleplayReplyRevision("答案", listOf("答案", "修改答案"), 1),
                "next-a" to RoleplayReplyRevision("后续答案", listOf("后续答案", "后续修改"), 1))))
        val result = AgentConversationBranchPlanner.create("root", source, stored.positions, "a", null, verifiedPrefix = true)
        assertEquals("journal", result.draft.node.historyMode)
        assertEquals("修改答案", result.state.history.last().content)
        assertEquals("修改答案", (result.state.messages.last() as AgentMessageUi).content)
        assertEquals(1, result.state.roleplayMessages.revisions.size)
        assertTrue(result.state.history.none { it.content.contains("后续") })
    }

    @Test fun summaryHistoryReconstructsOnlyTheVerifiedVisiblePrefix() = runBlocking(Dispatchers.IO) {
        prepare(listOf(row("u", 0, "user"), row("a", 1, "assistant"), row("later", 2, "user")))
        val stored = AgentConversationStore.loadBranchSource(context, "root", "a")
        val result = plan(stored, listOf(ConversationMessage("system", "包含未来的摘要", contextSummary = true)), "a")
        assertEquals("visible_reconstruction", result.draft.node.historyMode)
        assertEquals(2, result.state.messages.size)
        assertTrue(result.state.history.none { it.contextSummary || it.content.contains("未来") })
    }

    @Test fun enormousFutureBodyAndAttachmentsDoNotUseThePrefixCharacterBudget() = runBlocking(Dispatchers.IO) {
        prepare(listOf(row("u", 0, "user"), row("a", 1, "assistant"),
            row("future", 2, "user", "x".repeat(17 * 1024 * 1024)).copy(imagesJson = "[]")))
        val stored = AgentConversationStore.loadBranchSource(context, "root", "a")
        assertEquals(listOf("u", "a"), stored.messages.map { it.id })
        assertTrue(dao.branchPoint("root", "future") != null)
        assertEquals(3, dao.messageCount("root"))
    }

    @Test fun prefixMarkerDoesNotAllowWrongUserIdentityOrArbitraryMiddleWindow() = runBlocking(Dispatchers.IO) {
        prepare(listOf(row("u", 0, "user"), row("a", 1, "assistant"), row("future", 2, "user")))
        val stored = AgentConversationStore.loadBranchSource(context, "root", "a")
        val wrong = listOf(ConversationMessage("user", "u", messageId = "other-u"),
            ConversationMessage("assistant", "a", messageId = "a"), ConversationMessage("user", "future", messageId = "future"))
        assertEquals("visible_reconstruction", plan(stored, wrong, "a").draft.node.historyMode)
        assertThrows(IllegalArgumentException::class.java) {
            AgentConversationBranchPlanner.create("root", state(stored, wrong), stored.positions, "u", null, verifiedPrefix = true)
        }
        Unit
    }

    @Test fun editedRoleplayUserPointCopiesSelectedTextAndItsRevisionExplanation() = runBlocking(Dispatchers.IO) {
        prepare(listOf(row("u", 0, "user", "原问题"), row("a", 1, "assistant", "未来答案")))
        val stored = AgentConversationStore.loadBranchSource(context, "root", "u")
        val journal = listOf(ConversationMessage("user", "原问题", messageId = "u"),
            ConversationMessage("assistant", "未来答案", messageId = "a"))
        val source = state(stored, journal).copy(roleplay = RoleplayBinding("card", "{}", "角色"), roleplayMessages = RoleplayMessageState(
            links = mapOf("u" to RoleplayMessageLink("u")),
            revisions = mapOf("u" to RoleplayReplyRevision("原问题", listOf("原问题", "修改后的问题"), 1))))
        val result = AgentConversationBranchPlanner.create("root", source, stored.positions, "u", null, verifiedPrefix = true)
        assertEquals("journal", result.draft.node.historyMode)
        assertEquals("修改后的问题", result.state.history.last().content)
        assertEquals("修改后的问题", (result.state.messages.single() as UserMessageUi).content)
        assertEquals(listOf("system", "user"), result.state.history.map { it.role })
        assertTrue(result.state.history.none { it.content.contains("未来") })
    }
}
