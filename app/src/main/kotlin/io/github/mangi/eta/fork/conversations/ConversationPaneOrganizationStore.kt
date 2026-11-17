package io.github.mangi.eta.fork.conversations

import android.content.Context
import io.github.mangi.eta.data.model.ConversationSubject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Meeting folders have their own namespace and never refer to chat-folder records. */
internal data class MeetingPaneFolder(
    val id: String,
    val name: String,
    val sortOrder: Long,
    val parentId: String? = null,
)

internal data class ConversationPaneOrganization(
    val ready: Boolean = false,
    val orders: Map<String, List<String>> = emptyMap(),
    val meetingFolders: Map<String, String> = emptyMap(),
    val meetingFolderDefinitions: List<MeetingPaneFolder> = emptyList(),
) {
    fun <T> ordered(scope: String, items: List<T>, id: (T) -> String): List<T> {
        val saved = orders[scope].orEmpty()
        if (saved.isEmpty()) return items
        val rank = saved.withIndex().associate { it.value to it.index }
        return items.sortedBy { rank[id(it)] ?: Int.MAX_VALUE }
    }
}

/** Placement is a UI command. Subject identity never changes when a row changes folders. */
internal data class ConversationPanePlacement(
    val subject: ConversationSubject,
    val pinned: Boolean,
    val folderId: String?,
    val scope: String,
    val members: List<String>,
    val neighborId: String? = null,
    val after: Boolean = false,
)

/** Separate from runtime settings and both transcript databases; included by full shared-prefs backup. */
internal class ConversationPaneOrganizationStore private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("nexa_pane_organization", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val state = MutableStateFlow(read())

    private fun read(): ConversationPaneOrganization {
        val json = runCatching { JSONObject(preferences.getString("layout", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val orders = json.optJSONObject("orders") ?: JSONObject()
        val folders = json.optJSONObject("meetingFolders") ?: JSONObject()
        val definitions = json.optJSONArray("meetingFolderDefinitions") ?: JSONArray()
        val meetingFolders = (0 until definitions.length()).mapNotNull { index ->
            val folder = definitions.optJSONObject(index) ?: return@mapNotNull null
            val id = folder.optString("id").takeIf { it.startsWith("meeting-folder:") } ?: return@mapNotNull null
            val name = folder.optString("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
            MeetingPaneFolder(id, name, folder.optLong("sortOrder"), folder.optString("parentId").takeIf(String::isNotBlank))
        }.distinctBy { it.id }.sortedBy { it.sortOrder }
        val validFolderIds = meetingFolders.mapTo(hashSetOf()) { it.id }
        return ConversationPaneOrganization(ready = true,
            orders = orders.keys().asSequence().associateWith { scope ->
                val ids = orders.optJSONArray(scope) ?: JSONArray()
                (0 until ids.length()).mapNotNull { ids.optString(it).takeIf(String::isNotBlank) }.distinct()
            },
            meetingFolders = folders.keys().asSequence().mapNotNull { id ->
                // Version 1 incorrectly used chat folder IDs. Leave those chats untouched;
                // meetings with no independent destination return to the meeting root.
                folders.optString(id).takeIf { it in validFolderIds }?.let { id to it }
            }.toMap(), meetingFolderDefinitions = meetingFolders)
    }

    private suspend fun place(command: ConversationPanePlacement) = mutex.withLock {
        val current = state.value
        if (command.subject.kind == ConversationSubject.Kind.MEETING && command.folderId != null &&
            current.meetingFolderDefinitions.none { it.id == command.folderId }) {
            throw ConversationFolderException("NOT_FOUND")
        }
        val prefix = "${command.subject.kind.value}:"
        val existing = current.orders[command.scope].orEmpty()
        // Preserve hidden search results and their relative order; never replace with a filtered list.
        val members = (existing + command.members).distinct().filterNot { it == command.subject.id }.toMutableList()
        val neighbor = command.neighborId?.let(members::indexOf) ?: -1
        val index = if (neighbor < 0) members.size else neighbor + if (command.after) 1 else 0
        members.add(index, command.subject.id)
        val orders = current.orders.mapValues { (scope, ids) ->
            if (scope.startsWith(prefix)) ids.filterNot { it == command.subject.id } else ids
        } + (command.scope to members)
        val folders = if (command.subject.kind == ConversationSubject.Kind.MEETING && !command.pinned) {
            if (command.folderId == null) current.meetingFolders - command.subject.id
            else current.meetingFolders + (command.subject.id to command.folderId)
        } else current.meetingFolders
        persist(current.copy(orders = orders, meetingFolders = folders))
    }

    private suspend fun validateMeetingFolder(id: String?) = mutex.withLock {
        requireParent(state.value.meetingFolderDefinitions, id)
    }

    private fun persist(next: ConversationPaneOrganization) {
        val json = JSONObject().put("version", 2)
            .put("orders", JSONObject().also { obj -> next.orders.forEach { (scope, ids) -> obj.put(scope, JSONArray(ids)) } })
            .put("meetingFolders", JSONObject(next.meetingFolders))
            .put("meetingFolderDefinitions", JSONArray().also { array -> next.meetingFolderDefinitions.forEach { folder ->
                array.put(JSONObject().put("id", folder.id).put("name", folder.name)
                    .put("sortOrder", folder.sortOrder).put("parentId", folder.parentId))
            } })
        check(preferences.edit().putString("layout", json.toString()).commit()) { "列表顺序保存失败，请重试" }
        state.value = next.copy(ready = true)
    }

    private fun folderName(name: String): String = name.trim().also {
        if (it.isEmpty() || it.length > 80 || it.any { char -> char.isISOControl() || char == '/' || char == '\\' }) {
            throw ConversationFolderException("INVALID_NAME")
        }
    }

    private fun requireUniqueName(folders: List<MeetingPaneFolder>, name: String, parentId: String?, except: String? = null) {
        if (folders.any { it.id != except && it.parentId == parentId && it.name == name }) {
            throw ConversationFolderException("DUPLICATE_NAME")
        }
    }

    private fun requireParent(folders: List<MeetingPaneFolder>, parentId: String?) {
        if (parentId != null && folders.none { it.id == parentId }) throw ConversationFolderException("NOT_FOUND")
    }

    private suspend fun createMeetingFolder(name: String, parentId: String?) = mutex.withLock {
        val current = state.value
        val folders = current.meetingFolderDefinitions
        val normalized = folderName(name)
        requireParent(folders, parentId)
        val tree = ConversationFolderTree(folders, { it.id }, { it.parentId })
        if (parentId != null && tree.ancestors(parentId).size + 2 > MAX_CONVERSATION_FOLDER_DEPTH) {
            throw ConversationFolderException("DEPTH_LIMIT")
        }
        requireUniqueName(folders, normalized, parentId)
        val folder = MeetingPaneFolder("meeting-folder:${UUID.randomUUID()}", normalized,
            (folders.maxOfOrNull { it.sortOrder } ?: 0L).coerceAtLeast(0L) + 1L, parentId)
        persist(current.copy(meetingFolderDefinitions = folders + folder))
    }

    private suspend fun renameMeetingFolder(id: String, name: String) = mutex.withLock {
        val current = state.value
        val folders = current.meetingFolderDefinitions
        val folder = folders.firstOrNull { it.id == id } ?: throw ConversationFolderException("NOT_FOUND")
        val normalized = folderName(name)
        requireUniqueName(folders, normalized, folder.parentId, id)
        persist(current.copy(meetingFolderDefinitions = folders.map { if (it.id == id) it.copy(name = normalized) else it }))
    }

    private suspend fun moveMeetingFolder(id: String, parentId: String?) = mutex.withLock {
        val current = state.value
        val folders = current.meetingFolderDefinitions
        val folder = folders.firstOrNull { it.id == id } ?: throw ConversationFolderException("NOT_FOUND")
        requireParent(folders, parentId)
        val tree = ConversationFolderTree(folders, { it.id }, { it.parentId })
        if (parentId in tree.subtreeIds(id)) throw ConversationFolderException("CYCLE")
        if (!tree.canMove(id, parentId)) throw ConversationFolderException("DEPTH_LIMIT")
        requireUniqueName(folders, folder.name, parentId, id)
        persist(current.copy(meetingFolderDefinitions = folders.map { if (it.id == id) it.copy(parentId = parentId) else it }))
    }

    private suspend fun pinMeetingFolder(id: String, pinned: Boolean) = mutex.withLock {
        val current = state.value
        val folders = current.meetingFolderDefinitions
        val folder = folders.firstOrNull { it.id == id } ?: throw ConversationFolderException("NOT_FOUND")
        val order = when {
            pinned && folder.sortOrder >= 0 -> (folders.minOfOrNull { it.sortOrder } ?: 0L).coerceAtMost(0L) - 1L
            !pinned && folder.sortOrder < 0 -> (folders.maxOfOrNull { it.sortOrder } ?: 0L).coerceAtLeast(0L) + 1L
            else -> folder.sortOrder
        }
        persist(current.copy(meetingFolderDefinitions = folders.map { if (it.id == id) it.copy(sortOrder = order) else it }))
    }

    private suspend fun deleteMeetingFolder(id: String) = mutex.withLock {
        val current = state.value
        val folders = current.meetingFolderDefinitions
        val folder = folders.firstOrNull { it.id == id } ?: throw ConversationFolderException("NOT_FOUND")
        val remaining = folders.filterNot { it.id == id }
        remaining.filter { it.parentId == id }.forEach { requireUniqueName(remaining, it.name, folder.parentId, it.id) }
        val memberIds = current.meetingFolders.filterValues { it == id }.keys
        val members = (current.orders["meeting:folder:$id"].orEmpty() + memberIds).distinct().filter { it in memberIds }
        val parentId = folder.parentId
        val destinationScope = parentId?.let { "meeting:folder:$it" } ?: "meeting:root"
        val orders = current.orders - "meeting:folder:$id"
        persist(current.copy(
            meetingFolderDefinitions = remaining.map { if (it.parentId == id) it.copy(parentId = parentId) else it },
            meetingFolders = current.meetingFolders.filterKeys { it !in members } +
                if (parentId == null) emptyMap() else members.associateWith { parentId },
            orders = orders + (destinationScope to (orders[destinationScope].orEmpty() + members).distinct()),
        ))
    }

    companion object {
        @Volatile private var instance: ConversationPaneOrganizationStore? = null
        private fun get(context: Context): ConversationPaneOrganizationStore = instance ?: synchronized(this) {
            instance ?: ConversationPaneOrganizationStore(context).also { instance = it }
        }
        fun observe(context: Context): Flow<ConversationPaneOrganization> = flow {
            emitAll(get(context).state)
        }.flowOn(Dispatchers.IO)
        suspend fun savePlacement(context: Context, command: ConversationPanePlacement) = withContext(Dispatchers.IO) {
            get(context).place(command)
        }
        suspend fun validateMeetingFolder(context: Context, id: String?) = withContext(Dispatchers.IO) {
            get(context).validateMeetingFolder(id)
        }
        suspend fun createMeetingFolder(context: Context, name: String, parentId: String?) = withContext(Dispatchers.IO) {
            get(context).createMeetingFolder(name, parentId)
        }
        suspend fun renameMeetingFolder(context: Context, id: String, name: String) = withContext(Dispatchers.IO) {
            get(context).renameMeetingFolder(id, name)
        }
        suspend fun moveMeetingFolder(context: Context, id: String, parentId: String?) = withContext(Dispatchers.IO) {
            get(context).moveMeetingFolder(id, parentId)
        }
        suspend fun setMeetingFolderPinned(context: Context, id: String, pinned: Boolean) = withContext(Dispatchers.IO) {
            get(context).pinMeetingFolder(id, pinned)
        }
        suspend fun deleteMeetingFolder(context: Context, id: String) = withContext(Dispatchers.IO) {
            get(context).deleteMeetingFolder(id)
        }
    }
}
