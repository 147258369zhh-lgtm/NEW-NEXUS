package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.fork.conversations.ConversationExtensionDatabase
import io.github.mangi.eta.fork.conversations.ConversationExtensionRepository
import io.github.mangi.eta.fork.conversations.IDLE_ARCHIVE_REASON
import io.github.mangi.eta.fork.meeting.MeetingDatabase
import io.github.mangi.eta.fork.meeting.MeetingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class ArchiveEntryType { Conversation, Meeting }
internal data class ArchiveEntry(
    val id: String,
    val type: ArchiveEntryType,
    val title: String,
    val archivedAt: Long,
    val preview: String,
    val detail: String,
) { val key: String get() = "${type.name}:$id" }
internal data class ArchivePage(val entries: List<ArchiveEntry>, val hasMore: Boolean)

/** Searches every archived owner as necessary, returning at most one bounded page, never full transcripts. */
internal object UnifiedConversationArchiveRepository {
    suspend fun page(context: Context, query: String, type: ArchiveEntryType?, after: ArchiveEntry?, limit: Int = 40): ArchivePage = withContext(Dispatchers.IO) {
        val size = limit.coerceIn(1, 80)
        val term = query.trim().take(256)
        val entries = mutableListOf<ArchiveEntry>()
        if (type == null || type == ArchiveEntryType.Conversation) {
            val extensions = ConversationExtensionDatabase.get(context).conversationExtensionDao()
            val conversations = EtaDatabase.get(context).conversationDao()
            var before = after?.archivedAt
            var afterId = after?.takeIf { it.type == ArchiveEntryType.Conversation }?.id.orEmpty()
            var includeSame = after == null || after.type == ArchiveEntryType.Conversation
            var found = 0
            while (found < size + 1) {
                currentCoroutineContext().ensureActive()
                val owners = extensions.archivedPage(before, afterId, includeSame, 40)
                if (owners.isEmpty()) break
                val identities = conversations.archiveIdentities(owners.map { it.conversationId }).associateBy { it.id }
                val titleMatches = identities.values.filter { it.title.contains(term, ignoreCase = true) }.map { it.id }.toSet()
                val contentMatches = if (term.isEmpty()) emptySet() else ConversationSearchRepository.searchArchivedIds(
                    context, term, owners.map { it.conversationId }.filter { it !in titleMatches && it in identities })
                for (owner in owners) {
                    val identity = identities[owner.conversationId] ?: continue
                    if (term.isNotEmpty() && identity.id !in titleMatches && identity.id !in contentMatches) continue
                    val folder = owner.folderId?.let { extensions.folder(it)?.name }
                    entries += ArchiveEntry(identity.id, ArchiveEntryType.Conversation, identity.title,
                        checkNotNull(owner.archivedAt), conversations.archivePreview(identity.id).orEmpty(),
                        listOfNotNull(if (owner.archiveReason == IDLE_ARCHIVE_REASON) "自动归档" else "手动归档", folder?.let { "目录：$it" }).joinToString(" · "))
                    if (++found >= size + 1) break
                }
                if (owners.size < 40) break
                before = owners.last().archivedAt
                afterId = owners.last().conversationId
                includeSame = true
            }
        }
        if (type == null || type == ArchiveEntryType.Meeting) {
            val sameType = after?.type == ArchiveEntryType.Meeting
            MeetingDatabase.get(context).meetingDao().archivedPage(after?.archivedAt,
                if (sameType) after!!.id else "", true, term, size + 1).forEach { room ->
                entries += ArchiveEntry(room.id, ArchiveEntryType.Meeting, room.title, room.archivedAt,
                    room.preview, "${room.messageCount} 条消息")
            }
        }
        val sorted = entries.sortedWith(compareByDescending<ArchiveEntry> { it.archivedAt }.thenBy { it.type.ordinal }.thenBy { it.id })
        ArchivePage(sorted.take(size), sorted.size > size)
    }

    suspend fun restore(context: Context, entry: ArchiveEntry) = withContext(Dispatchers.IO) {
        when (entry.type) {
            ArchiveEntryType.Conversation -> {
                check(EtaDatabase.get(context).conversationDao().conversationExists(entry.id)) { "会话不存在" }
                val database = ConversationExtensionDatabase.get(context)
                database.withTransaction {
                    checkNotNull(database.conversationExtensionDao().extension(entry.id)) { "归档记录不存在" }
                    ConversationExtensionRepository.restoreConversation(context, entry.id)
                }
            }
            ArchiveEntryType.Meeting -> {
                val database = MeetingDatabase.get(context)
                database.withTransaction {
                    checkNotNull(database.meetingDao().room(entry.id)) { "会议室不存在" }
                    MeetingRepository.setRoomArchived(context, entry.id, false)
                }
            }
        }
    }
}
