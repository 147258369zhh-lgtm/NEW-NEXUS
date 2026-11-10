package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import org.junit.Assert.*
import org.junit.Test

class ConversationDrawerGroupsTest {
    private val folders = listOf(
        ConversationFolderUi("root", "Root", 0),
        ConversationFolderUi("child", "Child", 1, "root"),
        ConversationFolderUi("leaf", "Leaf", 2, "child"),
        ConversationFolderUi("other", "Other", 3),
    )
    private fun chat(id: String, folder: String? = null, pinned: Boolean = false) =
        ConversationSummaryUi(id, id, "", "", mode = ConversationModeUi.Chat, folderId = folder, isPinned = pinned)

    @Test fun emptyFoldersRemainVisibleAndNested() {
        val groups = emptyList<ConversationSummaryUi>().groupForDrawer(folders, archived = false)
        assertEquals(listOf("root", "other"), groups.map { (it.section as ConversationDrawerSection.Folder).folder.id })
        assertEquals("child", (groups[0].children.single().section as ConversationDrawerSection.Folder).folder.id)
        assertEquals(0, groups[0].conversationCount)
    }

    @Test fun searchRetainsMatchingBranchAndItsAncestors() {
        val groups = listOf(chat("match", "leaf")).groupForDrawer(folders, archived = false, searching = true)
        assertEquals(1, groups.size)
        assertEquals("root", (groups.single().section as ConversationDrawerSection.Folder).folder.id)
        val child = groups.single().children.single()
        assertEquals(listOf("match"), child.children.single().items.map { it.id })
        assertEquals(1, groups.single().conversationCount)
        assertTrue(emptyList<ConversationSummaryUi>().groupForDrawer(folders, false, searching = true).isEmpty())
    }

    @Test fun folderMembershipOrderTakesPriorityOverRecentActivity() {
        val chats = listOf(
            chat("recent", "root").copy(folderSortOrder = 2L, updatedAtMillis = 300L),
            chat("first", "root").copy(folderSortOrder = 0L, updatedAtMillis = 100L),
            chat("second", "root").copy(folderSortOrder = 1L, updatedAtMillis = 200L),
        )
        assertEquals(listOf("first", "second", "recent"), chats.groupForDrawer(folders, false).first().items.map { it.id })
    }

    @Test fun pinnedAndArchivedConversationsAreShownOnceAndUnknownFoldersRemainAccessible() {
        val chats = listOf(chat("pinned", "leaf", true), chat("normal", "leaf"), chat("orphan", "missing"))
        val groups = chats.groupForDrawer(folders, false)
        assertEquals(ConversationDrawerSection.Pinned, groups.first().section)
        fun allItems(group: ConversationDrawerGroup): List<String> = group.items.map { it.id } + group.children.flatMap(::allItems)
        assertEquals(listOf("normal", "orphan", "pinned"), groups.flatMap(::allItems).sorted())
        assertEquals(1, groups[1].conversationCount)
        val archive = chats.groupForDrawer(folders, true)
        assertEquals(ConversationDrawerSection.Archive, archive.single().section)
        assertEquals(chats, archive.single().items)
    }

    @Test fun corruptDeepTreesRenderWithinEightLevelsWithoutLosingFolders() {
        val deep = (0..99).map { ConversationFolderUi("$it", "$it", it.toLong(), if (it == 0) null else "${it - 1}") }
        val groups = listOf(chat("deep", "99")).groupForDrawer(deep, false)
        fun count(group: ConversationDrawerGroup): Int = 1 + group.children.sumOf(::count)
        fun height(group: ConversationDrawerGroup): Int = 1 + (group.children.maxOfOrNull(::height) ?: 0)
        assertEquals(100, count(groups.single()))
        assertEquals(8, height(groups.single()))
        assertEquals(1, groups.single().conversationCount)
    }
}
