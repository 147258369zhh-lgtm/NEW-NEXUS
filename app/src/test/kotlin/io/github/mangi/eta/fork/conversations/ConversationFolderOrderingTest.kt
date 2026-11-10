package io.github.mangi.eta.fork.conversations

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationFolderOrderingTest {
    @Test
    fun folders_use_sort_order_then_localized_name() {
        val folders = listOf(
            folder("b", "Beta", 1),
            folder("a", "alpha", 1),
            folder("first", "First", 0),
        )

        assertEquals(
            listOf("first", "a", "b"),
            ConversationFolderOrdering.sortFolders(folders).map { it.id },
        )
    }

    @Test
    fun folder_members_sort_by_manual_order_then_recency() {
        val extensions = listOf(
            extension("old", "folder", 2, 100L),
            extension("new", "folder", 2, 300L),
            extension("manual-first", "folder", 1, 1L),
            extension("other", "other", 0, 999L),
        )

        assertEquals(
            listOf("manual-first", "new", "old"),
            ConversationFolderOrdering.sortInFolder(extensions, "folder").map { it.conversationId },
        )
    }

    private fun folder(id: String, name: String, sortOrder: Long) = ConversationFolder(
        id = id,
        name = name,
        sortOrder = sortOrder,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun extension(
        id: String,
        folderId: String,
        sortOrder: Long,
        lastActiveAt: Long,
    ) = ConversationExtension(
        conversationId = id,
        folderId = folderId,
        folderSortOrder = sortOrder,
        lastActiveAt = lastActiveAt,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
