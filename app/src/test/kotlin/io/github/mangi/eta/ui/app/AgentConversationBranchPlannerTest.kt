package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.roleplay.*
import io.github.mangi.eta.data.db.ConversationBranchEntity
import io.github.mangi.eta.ui.model.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentConversationBranchPlannerTest {
    private fun source() = AgentChatHomeUiState(messages = listOf(
        UserMessageUi("u1", "查资料", images = listOf("saved-image")),
        ToolActivityMessageUi("tool", "search", ToolActivityStatusUi.Success, "查询", resultSummary = "资料"),
        AgentMessageUi("a1", "答复"), UserMessageUi("u2", "未来指令"), AgentMessageUi("a2", "未来结果")),
        history = listOf(ConversationMessage("assistant", "含未来的摘要", contextSummary = true)),
        journal = listOf(ConversationMessage("user", "查资料", messageId = "u1"),
            ConversationMessage("assistant", "", toolCallsJson = """[{"id":"call","type":"function","function":{"name":"search","arguments":"{}"}}]""", messageId = "call-entry"),
            ConversationMessage("tool", "真实结果", toolCallId = "call"), ConversationMessage("assistant", "答复", messageId = "a1"),
            ConversationMessage("user", "未来指令", messageId = "u2"), ConversationMessage("assistant", "未来结果", messageId = "a2")),
        input = "未发送", isStreaming = false, thinkingEnabled = true, modelId = "model",
        appliedRuntimeRunIds = listOf("old-run"), queuedMessages = listOf(QueuedMessageUi("queued", "不能重跑")),
        pendingImages = listOf(PendingImageUi("pending", "uri", "data", "image/png")))

    private fun plan(state: AgentChatHomeUiState = source(), target: String = "a1") =
        AgentConversationBranchPlanner.create("root", state, state.messages.mapIndexed { i, m -> m.id to (i * 3) }.toMap(),
            target, null, childId = "child", now = 9)

    @Test fun copiesClosedToolHistoryAndRemapsEveryIdentityWithoutFutureOrRunState() {
        val original = source()
        val result = plan(original)
        assertEquals(4, result.state.history.size)
        assertTrue(result.state.history.none { it.content.contains("未来") || it.contextSummary })
        assertEquals("真实结果", result.state.history[2].content)
        val callId = JSONArray(result.state.history[1].toolCallsJson).getJSONObject(0).getString("id")
        assertNotEquals("call", callId)
        assertEquals(callId, result.state.history[2].toolCallId)
        assertTrue(result.state.messages.none { m -> original.messages.any { it.id == m.id } })
        assertEquals(listOf("saved-image"), (result.state.messages[0] as UserMessageUi).images)
        assertTrue(result.state.appliedRuntimeRunIds.isEmpty())
        assertTrue(result.state.queuedMessages.isEmpty())
        assertTrue(result.state.pendingImages.isEmpty())
        assertEquals("", result.state.input)
        assertEquals("model", result.state.modelId)
        assertEquals(6, result.draft.node.sourceSortIndex)
        assertEquals(listOf(0, 3, 6), result.draft.origins.map { it.sourceSortIndex })
        assertEquals("journal", result.draft.node.historyMode)
        assertEquals(source(), original)
    }

    @Test fun userPointCopiesOnlyTheUserEntryAndDoesNotReplayItsFollowingTools() {
        val result = plan(target = "u1")
        assertEquals(listOf("user"), result.state.history.map { it.role })
        assertEquals(1, result.state.messages.size)
    }

    @Test fun summaryOrAmbiguousLegacyAlignmentReconstructsOnlyVisiblePrefixWithExplicitMode() {
        val state = source().copy(journal = source().history)
        val result = plan(state)
        assertEquals("visible_reconstruction", result.draft.node.historyMode)
        assertTrue(result.state.history.none { it.content.contains("未来") || it.contextSummary || it.role == "tool" })
        assertTrue(result.state.history.any { it.content.contains("原会话工具记录") })
    }

    @Test fun identicalTextWithWrongStableUserIdentityCannotBeAlignedByOrdinal() {
        val state = source().copy(journal = source().journal.map { if (it.messageId == "u1") it.copy(messageId = "other-user") else it })
        assertEquals("visible_reconstruction", plan(state).draft.node.historyMode)
    }

    @Test fun legacyBlankIdsRequireTextAlignmentAndPreserveRealToolsWhenAligned() {
        val state = source().copy(journal = source().journal.map { it.copy(messageId = "") })
        assertEquals("journal", plan(state).draft.node.historyMode)
        assertEquals("真实结果", plan(state).state.history.first { it.role == "tool" }.content)
    }

    @Test fun rejectsUnfinishedToolsInsteadOfDuplicatingExecution() {
        val state = source().copy(journal = source().journal.filter { it.role != "tool" })
        assertThrows(IllegalArgumentException::class.java) { plan(state) }
        assertFalse(AgentConversationBranchPlanner.closedTools(listOf(ConversationMessage("tool", "orphan", toolCallId = "orphan"))))
    }

    @Test fun refusesPartialWindowsOrActiveEditingAndStreaming() {
        listOf(source().copy(hasEarlierMessages = true), source().copy(isStreaming = true), source().copy(isCompacting = true),
            source().copy(isConversationLoaded = false)).forEach { state -> assertThrows(IllegalArgumentException::class.java) { plan(state) } }
    }

    @Test fun refusesAnIntermediateAssistantBlockOrUnsavedMessage() {
        val state = source().copy(messages = source().messages.toMutableList().apply { add(3, AgentMessageUi("second-block", "后半段")) })
        assertThrows(IllegalArgumentException::class.java) { plan(state) }
        assertThrows(IllegalArgumentException::class.java) {
            AgentConversationBranchPlanner.create("root", source(), emptyMap(), "a1", null)
        }
    }

    @Test fun forksOfForksKeepTheirFamilyAndImmediateParent() {
        val result = AgentConversationBranchPlanner.create("parent", source(), source().messages.mapIndexed { i, m -> m.id to i }.toMap(),
            "a1", ConversationBranchEntity("parent", "original-root", "root", "root", "point", 0, createdAt = 1), childId = "child")
        assertEquals("original-root", result.draft.node.familyId)
        assertEquals("parent", result.draft.node.parentConversationId)
        assertNull(result.draft.root)
    }

    @Test fun roleplayEditsLinksAndCandidatesRemainIndependentAndPendingRewritesAreCleared() {
        val original = source().copy(roleplay = RoleplayBinding("card", "{}", "人物"), roleplayMessages = RoleplayMessageState(
            links = mapOf("u1" to RoleplayMessageLink("u1"), "a1" to RoleplayMessageLink("a1"), "a2" to RoleplayMessageLink("a2")),
            revisions = mapOf("a1" to RoleplayReplyRevision("答复", listOf("答复", "修改"), 1),
                "a2" to RoleplayReplyRevision("未来结果", listOf("未来结果"), 0)), pendingRewrites = mapOf("run" to "a1")))
        val result = plan(original)
        assertTrue(result.state.roleplayMessages.pendingRewrites.isEmpty())
        assertEquals(1, result.state.roleplayMessages.revisions.size)
        assertEquals("修改", result.state.history.last().content)
        assertEquals(result.state.history.last().messageId, result.state.roleplayMessages.links[(result.state.messages.last() as AgentMessageUi).id]?.transcriptMessageId)
    }

    @Test fun removesOpaqueProviderBlocksAndSignaturesButKeepsText() {
        val raw = """[{"type":"thinking","thinking":"private","signature":"secret"},{"type":"redacted_thinking","data":"private"},{"type":"thought","signature":"secret"},{"type":"text","text":"可见正文","signature":"secret"}]"""
        val result = AgentConversationBranchPlanner.stripOpaqueBlocks(raw)
        assertEquals("可见正文", JSONArray(result).getJSONObject(0).getString("text"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("private"))
        assertEquals("{\"type\":\"text\",\"text\":\"正文\"}", AgentConversationBranchPlanner.stripOpaqueBlocks("""{"type":"text","text":"正文","encrypted_content":"secret"}"""))
    }
}
