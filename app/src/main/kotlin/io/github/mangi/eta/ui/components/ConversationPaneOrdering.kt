package io.github.mangi.eta.ui.components

import io.github.mangi.eta.fork.conversations.ConversationFolderTree
import io.github.mangi.eta.fork.conversations.ConversationPaneOrganization
import io.github.mangi.eta.fork.meeting.MeetingRoomSummaryRow
import io.github.mangi.eta.ui.model.ConversationFolderUi

internal fun ConversationDrawerGroup.withPaneOrder(organization: ConversationPaneOrganization): ConversationDrawerGroup = copy(
    items = organization.ordered("chat:${section.stableKey}", items) { it.id },
    children = children.map { it.withPaneOrder(organization) },
)

internal fun List<ConversationDrawerGroup>.paneGroupsByScope(): Map<String, ConversationDrawerGroup> = buildMap {
    fun append(group: ConversationDrawerGroup) {
        put("chat:${group.section.stableKey}", group)
        group.children.forEach(::append)
    }
    this@paneGroupsByScope.forEach(::append)
}

internal data class MeetingPaneGroup(
    val scope: String,
    val title: String,
    val rooms: List<MeetingRoomSummaryRow>,
    val folder: ConversationFolderUi? = null,
    val depth: Int = 0,
    val pinned: Boolean = false,
)

internal fun meetingPaneGroups(
    rooms: List<MeetingRoomSummaryRow>,
    organization: ConversationPaneOrganization,
    archived: Boolean,
): List<MeetingPaneGroup> = buildList {
    val folders = organization.meetingFolderDefinitions.sortedBy { it.sortOrder }.map {
        ConversationFolderUi(it.id, it.name, it.sortOrder, it.parentId)
    }
    val eligible = rooms.filter { (it.archivedAt != null) == archived }
    if (archived) {
        add(MeetingPaneGroup("meeting:archive", "已归档", eligible))
        return@buildList
    }
    val pinned = eligible.filter { it.pinnedAt != null }
    if (pinned.isNotEmpty()) add(MeetingPaneGroup("meeting:pinned", "置顶", pinned, pinned = true))
    val folderIds = folders.mapTo(hashSetOf()) { it.id }
    val grouped = eligible.filter { it.pinnedAt == null }.groupBy { room ->
        organization.meetingFolders[room.id]?.takeIf { it in folderIds }
    }
    ConversationFolderTree(folders, { it.id }, { it.parentId }).rows.forEach { row ->
        add(MeetingPaneGroup("meeting:folder:${row.item.id}", row.item.name, grouped[row.item.id].orEmpty(), row.item, row.depth))
    }
    add(MeetingPaneGroup("meeting:root", "会议室", grouped[null].orEmpty()))
}.map { it.copy(rooms = organization.ordered(it.scope, it.rooms) { room -> room.id }) }
