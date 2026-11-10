package io.github.mangi.eta.fork.conversations

import org.junit.Assert.*
import org.junit.Test

class ConversationFolderTreeTest {
    private data class Item(val id: String, val parent: String? = null)
    private fun tree(vararg items: Item) = ConversationFolderTree(items.toList(), { it.id }, { it.parent })

    @Test fun preorderPreservesSiblingOrderAndComputesAncestorsAndSubtrees() {
        val tree = tree(Item("b"), Item("b2", "b"), Item("a"), Item("b1", "b"), Item("leaf", "b2"))
        assertEquals(listOf("b", "b2", "leaf", "b1", "a"), tree.rows.map { it.item.id })
        assertEquals(listOf(0, 1, 2, 1, 0), tree.rows.map { it.depth })
        assertEquals(listOf("b", "b2"), tree.ancestors("leaf").map { it.id })
        assertEquals(setOf("b", "b2", "leaf", "b1"), tree.subtreeIds("b"))
        assertEquals("b2", tree.parentOf("leaf"))
        assertFalse(tree.canMove("b", "leaf"))
        assertFalse(tree.canMove("b", "b"))
        assertFalse(tree.canMove("b", "missing"))
        assertFalse(tree.canMove("missing", null))
        assertTrue(tree.canMove("b2", "a"))
        assertTrue(tree.canMove("leaf", null))
    }

    @Test fun orphanAndCycleRecoveryRetainsEveryFolderOnce() {
        val tree = tree(Item("a", "b"), Item("b", "c"), Item("c", "a"), Item("orphan", "missing"), Item("self", "self"))
        assertEquals(setOf("a", "b", "c", "orphan", "self"), tree.rows.map { it.item.id }.toSet())
        assertEquals(5, tree.rows.size)
        assertNull(tree.parentOf("orphan"))
        assertNull(tree.parentOf("self"))
        tree.rows.forEach { assertTrue(tree.ancestors(it.item.id).size < 5) }
    }

    @Test fun movementCountsTheHeightOfTheWholeSubtree() {
        val items = (0..7).map { Item("$it", if (it == 0) null else "${it - 1}") } + listOf(Item("branch"), Item("leaf", "branch"))
        val tree = ConversationFolderTree(items, { it.id }, { it.parent })
        assertFalse(tree.canMove("branch", "6"))
        assertTrue(tree.canMove("branch", "5"))
        assertFalse(tree.canMove("leaf", "7"))
        assertTrue(tree.canMove("leaf", "6"))
    }
}
