package io.github.mangi.eta.ui.components

import io.github.mangi.eta.fork.conversations.ConversationFolderTree
import io.github.mangi.eta.fork.conversations.MAX_CONVERSATION_FOLDER_DEPTH
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi

internal data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
    val children: List<ConversationDrawerGroup> = emptyList(),
) {
    val conversationCount: Int = items.size + children.sumOf { it.conversationCount }
}

internal sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data object Archive : ConversationDrawerSection
    data class Folder(val folder: ConversationFolderUi) : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data class Dated(val label: String) : ConversationDrawerSection
}

internal fun List<ConversationSummaryUi>.groupForDrawer(
    folders: List<ConversationFolderUi>,
    archived: Boolean,
    searching: Boolean = false,
): List<ConversationDrawerGroup> {
    if (archived) {
        return if (isEmpty()) emptyList() else listOf(ConversationDrawerGroup(ConversationDrawerSection.Archive, this))
    }
    val groups = mutableListOf<ConversationDrawerGroup>()
    val pinned = filter(ConversationSummaryUi::isPinned)
    if (pinned.isNotEmpty()) groups += ConversationDrawerGroup(ConversationDrawerSection.Pinned, pinned)
    val knownFolderIds = folders.mapTo(hashSetOf(), ConversationFolderUi::id)
    val tree = ConversationFolderTree(folders, { it.id }, { it.parentId })
    // Bound rendering depth even when a damaged backup contains an excessively deep tree.
    val parents = tree.rows.associate { row -> row.item.id to
        if (row.depth < MAX_CONVERSATION_FOLDER_DEPTH) tree.parentOf(row.item.id)
        else tree.ancestors(row.item.id)[MAX_CONVERSATION_FOLDER_DEPTH - 2].id
    }
    val children = tree.rows.map { it.item }.groupBy { parents[it.id] }
    val members = filterNot(ConversationSummaryUi::isPinned).groupBy { it.folderId }
    val folderGroups = mutableMapOf<String, ConversationDrawerGroup>()
    for (row in tree.rows.asReversed()) {
        val folder = row.item
        val nested = children[folder.id].orEmpty().mapNotNull { folderGroups[it.id] }
        val items = members[folder.id].orEmpty().sortedWith(
            compareBy<ConversationSummaryUi> { it.folderSortOrder }.thenByDescending { it.updatedAtMillis },
        )
        val group = ConversationDrawerGroup(ConversationDrawerSection.Folder(folder), items, nested)
        if (!searching || group.conversationCount > 0) folderGroups[folder.id] = group
    }
    groups += children[null].orEmpty().mapNotNull { folderGroups[it.id] }
    val ungrouped = filter { conversation ->
        !conversation.isPinned &&
            (conversation.folderId == null || conversation.folderId !in knownFolderIds)
    }
    for (conversation in ungrouped) {
        val section = conversation.drawerSection()
        val last = groups.lastOrNull()
        if (last?.section == section && section !is ConversationDrawerSection.Folder) {
            groups[groups.lastIndex] = last.copy(items = last.items + conversation)
        } else {
            groups += ConversationDrawerGroup(section = section, items = listOf(conversation))
        }
    }
    return groups
}

private fun ConversationSummaryUi.drawerSection(): ConversationDrawerSection = when {
    isActiveRun || isUpdatedToday(updatedAtMillis) -> ConversationDrawerSection.Today
    else -> ConversationDrawerSection.Dated(timeLabel)
}

private fun isUpdatedToday(timestampMillis: Long): Boolean {
    if (timestampMillis <= 0L) return true
    val now = java.util.Calendar.getInstance()
    val target = java.util.Calendar.getInstance().apply { timeInMillis = timestampMillis }
    return now.get(java.util.Calendar.ERA) == target.get(java.util.Calendar.ERA) &&
        now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
}
