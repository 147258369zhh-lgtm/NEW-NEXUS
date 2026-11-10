package io.github.mangi.eta.ui.model

import io.github.mangi.eta.data.db.ConversationBranchEntity
import org.junit.Assert.*
import org.junit.Test

class ConversationBranchTreeTest {
    private fun node(id: String, parent: String? = null, family: String = "root") = ConversationBranchEntity(
        id, family, parent, parent, if (parent == null) null else "point", if (parent == null) null else 0, createdAt = 1)

    @Test fun preservesRealDepthAndStableSiblingOrder() {
        val tree = ConversationBranchTree.build(listOf(node("root"), node("b", "root"), node("a", "root"), node("deep", "a")))
        assertEquals(listOf("root", "a", "deep", "b"), tree.rows.map { it.node.conversationId })
        assertEquals(listOf(0, 1, 2, 1), tree.rows.map { it.depth })
        assertFalse(tree.invalid)
    }
    @Test fun collapseHidesOnlyDescendantsAndCanBeRebuiltWithoutLosingData() {
        val nodes = listOf(node("root"), node("a", "root"), node("b", "a"))
        assertEquals(1, ConversationBranchTree.build(nodes, setOf("root")).rows.size)
        assertEquals(3, ConversationBranchTree.build(nodes).rows.size)
    }
    @Test fun deletedParentStartsAForestWithoutReparentingOrLosingOriginalSource() {
        val survivor = node("a").copy(sourceConversationId = "deleted", sourceMessageId = "point", sourceSortIndex = 2)
        val tree = ConversationBranchTree.build(listOf(survivor, node("b", "a")))
        assertFalse(tree.invalid)
        assertEquals("deleted", tree.rows.first().node.sourceConversationId)
        assertEquals(listOf(0, 1), tree.rows.map { it.depth })
    }
    @Test fun cycleDiagnosticsNeverHideNodesOrRecurseForever() {
        val tree = ConversationBranchTree.build(listOf(node("a", "b"), node("b", "a"), node("root")), setOf("a"))
        assertTrue(tree.invalid)
        assertEquals(setOf("a", "b", "root"), tree.rows.map { it.node.conversationId }.toSet())
        assertEquals(3, tree.rows.size)
    }
    @Test fun deepChainsDoNotOverflowTheStackOrTruncateLevels() {
        val nodes = (0..4096).map { node("n$it", if (it == 0) null else "n${it - 1}") }
        val tree = ConversationBranchTree.build(nodes)
        assertEquals(4097, tree.rows.size)
        assertEquals(4096, tree.rows.last().depth)
        assertFalse(tree.invalid)
    }

    @Test fun searchShowsMatchingDescendantsAndTheirAncestorsDespiteCollapsedState() {
        val nodes = listOf(node("root"), node("a", "root"), node("deep", "a"), node("unrelated", "root"))
        val tree = ConversationBranchTree.search(nodes, "  TARGET  ", setOf("root")) { if (it == "deep") "Target project" else "Other" }
        assertEquals(listOf("root", "a", "deep"), tree.rows.map { it.node.conversationId })
        assertEquals(listOf(0, 1, 2), tree.rows.map { it.depth })
        assertEquals("a", tree.rows.last().node.parentConversationId)
        assertEquals(1, ConversationBranchTree.search(nodes, " ", setOf("root")) { "Other" }.rows.size)
    }

    @Test fun noMatchReturnsEmptyAndMissingOrCyclicParentsRemainDiagnosable() {
        val nodes = listOf(node("a", "b"), node("b", "a"), node("orphan", "missing"))
        val cycle = ConversationBranchTree.search(nodes, "match") { if (it == "a") "match" else "other" }
        assertTrue(cycle.invalid)
        assertEquals(setOf("a", "b"), cycle.rows.map { it.node.conversationId }.toSet())
        val orphan = ConversationBranchTree.search(nodes, "orphan") { it }
        assertTrue(orphan.invalid)
        assertEquals("missing", orphan.rows.single().node.parentConversationId)
        assertTrue(ConversationBranchTree.search(nodes, "none") { it }.rows.isEmpty())
    }
}
