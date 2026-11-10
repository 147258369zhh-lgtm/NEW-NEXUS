package io.github.mangi.eta.fork.conversations

import java.util.ArrayDeque

internal const val MAX_CONVERSATION_FOLDER_DEPTH = 8

internal class ConversationFolderException(val code: String) : IllegalArgumentException(code)

internal data class FolderTreeRow<T>(val item: T, val depth: Int)

/** Stable sibling order with defensive handling of orphaned or cyclic data from older backups. */
internal class ConversationFolderTree<T>(items: List<T>, private val id: (T) -> String, parent: (T) -> String?) {
    private val byId = items.distinctBy(id).associateBy(id)
    private val parents = byId.mapValuesTo(linkedMapOf()) { (_, item) -> parent(item)?.takeIf { it in byId } }
    private val children: Map<String?, List<T>>
    val rows: List<FolderTreeRow<T>>

    init {
        val done = hashSetOf<String>()
        for (start in byId.keys) {
            val path = linkedSetOf<String>()
            var cursor: String? = start
            while (cursor != null && cursor !in done) {
                if (!path.add(cursor)) { parents[cursor] = null; break }
                cursor = parents[cursor]
            }
            done += path
        }
        children = byId.values.groupBy { parents[id(it)] }
        val pending = ArrayDeque<FolderTreeRow<T>>()
        childrenOf(null).asReversed().forEach { pending.addLast(FolderTreeRow(it, 0)) }
        rows = buildList {
            while (pending.isNotEmpty()) {
                val row = pending.removeLast()
                add(row)
                childrenOf(id(row.item)).asReversed().forEach { pending.addLast(FolderTreeRow(it, row.depth + 1)) }
            }
        }
    }

    fun childrenOf(parentId: String?): List<T> = children[parentId].orEmpty()

    fun parentOf(folderId: String): String? = parents[folderId]

    fun ancestors(folderId: String): List<T> = buildList {
        var cursor = parents[folderId]
        while (cursor != null) { byId[cursor]?.let(::add); cursor = parents[cursor] }
    }.asReversed()

    fun subtreeIds(folderId: String): Set<String> = buildSet {
        val pending = ArrayDeque<String>().apply { add(folderId) }
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (add(current)) childrenOf(current).forEach { pending.addLast(id(it)) }
        }
    }

    fun canMove(folderId: String, destinationId: String?): Boolean {
        if (folderId !in byId || (destinationId != null && destinationId !in byId)) return false
        val subtree = subtreeIds(folderId)
        if (destinationId in subtree) return false
        val baseDepth = rows.first { id(it.item) == folderId }.depth
        val height = rows.filter { id(it.item) in subtree }.maxOf { it.depth } - baseDepth + 1
        val destinationDepth = destinationId?.let { ancestors(it).size + 1 } ?: 0
        return height + destinationDepth <= MAX_CONVERSATION_FOLDER_DEPTH
    }
}
