package io.github.mangi.eta.ui.components

import io.github.mangi.eta.fork.conversations.ConversationPaneOrganization
import io.github.mangi.eta.fork.conversations.MeetingPaneFolder
import io.github.mangi.eta.fork.meeting.MeetingRoomSummaryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPaneOrderingTest {
    @Test
    fun legacyChatFolderMembershipLeavesMeetingAtRoot() {
        val organization = ConversationPaneOrganization(
            ready = true,
            meetingFolders = mapOf("room" to "chat-folder-id"),
        )

        val groups = meetingPaneGroups(listOf(room("room")), organization, archived = false)

        assertTrue(groups.none { it.folder != null })
        assertEquals(listOf("room"), groups.single().rooms.map { it.id })
        assertEquals("meeting:root", groups.single().scope)
    }

    @Test
    fun onlyIndependentMeetingFoldersAppearIncludingEmptyNestedFolders() {
        val parent = MeetingPaneFolder("meeting-folder:parent", "工作", 1)
        val child = MeetingPaneFolder("meeting-folder:child", "计划", 2, parent.id)
        val organization = ConversationPaneOrganization(
            ready = true,
            meetingFolders = mapOf("room" to parent.id),
            meetingFolderDefinitions = listOf(parent, child),
        )

        val groups = meetingPaneGroups(listOf(room("room")), organization, archived = false)

        val folders = groups.filter { it.folder != null }
        assertEquals(listOf(parent.id, child.id), folders.map { it.folder!!.id })
        assertEquals(listOf(0, 1), folders.map { it.depth })
        assertEquals(listOf("room"), folders.first().rooms.map { it.id })
        assertTrue(folders.last().rooms.isEmpty())
    }

    @Test
    fun meetingPinnedOrderAndArchiveRemainIndependentOfChatOrder() {
        val organization = ConversationPaneOrganization(
            ready = true,
            orders = mapOf("chat:pinned" to listOf("first", "second"),
                "meeting:pinned" to listOf("second", "first")),
            meetingFolderDefinitions = listOf(MeetingPaneFolder("meeting-folder:one", "会议", 1)),
        )
        val rooms = listOf(room("first", pinnedAt = 1), room("second", pinnedAt = 2),
            room("archived", archivedAt = 3))

        val pinned = meetingPaneGroups(rooms, organization, archived = false).first { it.pinned }
        val archive = meetingPaneGroups(rooms, organization, archived = true)

        assertEquals(listOf("second", "first"), pinned.rooms.map { it.id })
        assertEquals(listOf("archived"), archive.single().rooms.map { it.id })
        assertTrue(archive.none { it.folder != null })
    }

    private fun room(id: String, pinnedAt: Long? = null, archivedAt: Long? = null) = MeetingRoomSummaryRow(
        id = id, title = id, hostProfile = "主持人", status = "IDLE", createdAt = 1,
        updatedAt = 1, participantCount = 2, pinnedAt = pinnedAt, archivedAt = archivedAt,
    )
}
