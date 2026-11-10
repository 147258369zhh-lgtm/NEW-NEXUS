package io.github.mangi.eta.ui.model

import io.github.mangi.eta.data.db.ConversationBranchEntity

/** 迭代遍历，深层分支不溢栈；损坏的环仍显示全部节点并报告诊断。 */
internal object ConversationBranchTree {
    data class Row(val node: ConversationBranchEntity, val depth: Int, val hasChildren: Boolean)
    data class Tree(val rows: List<Row>, val invalid: Boolean)

    /** 搜索保留匹配节点的真实祖先；不改父关系，也不让折叠隐藏命中项。 */
    fun search(nodes: List<ConversationBranchEntity>, query: String, collapsed: Set<String> = emptySet(),
        title: (String) -> String): Tree {
        val text = query.trim()
        val tree = build(nodes, if (text.isEmpty()) collapsed else emptySet())
        if (text.isEmpty()) return tree
        val byId = nodes.associateBy { it.conversationId }
        val visible = mutableSetOf<String>()
        for (node in nodes) {
            if (!title(node.conversationId).contains(text, ignoreCase = true)) continue
            var current: ConversationBranchEntity? = node
            while (current != null && visible.add(current.conversationId)) {
                current = current.parentConversationId?.let(byId::get)
            }
        }
        return tree.copy(rows = tree.rows.filter { it.node.conversationId in visible })
    }

    fun build(nodes: List<ConversationBranchEntity>, collapsed: Set<String> = emptySet()): Tree {
        val byId = nodes.associateBy { it.conversationId }
        val children = nodes.groupBy { it.parentConversationId }.mapValues { (_, values) ->
            values.sortedWith(compareBy<ConversationBranchEntity> { it.createdAt }.thenBy { it.conversationId })
        }
        var invalid = byId.size != nodes.size
        val verified = mutableSetOf<String>()
        for (node in nodes) {
            var current: ConversationBranchEntity? = node
            val path = mutableSetOf<String>()
            while (current != null && current.conversationId !in verified) {
                if (!path.add(current.conversationId)) { invalid = true; break }
                val parentId = current.parentConversationId
                val parent = parentId?.let(byId::get)
                if (parentId != null && (parent == null || parent.familyId != current.familyId)) invalid = true
                current = parent
            }
            verified += path
        }
        val rows = mutableListOf<Row>()
        val visited = mutableSetOf<String>()
        fun visit(root: ConversationBranchEntity) {
            val stack = ArrayDeque<Pair<ConversationBranchEntity, Int>>()
            stack.addLast(root to 0)
            while (stack.isNotEmpty()) {
                val (node, depth) = stack.removeLast()
                if (!visited.add(node.conversationId)) continue
                val descendants = children[node.conversationId].orEmpty()
                rows += Row(node, depth, descendants.isNotEmpty())
                // 折叠只影响展示，后代仍标记已覆盖；损坏图禁用折叠以保留全部诊断节点。
                if (!invalid && node.conversationId in collapsed) {
                    val hidden = ArrayDeque(descendants)
                    while (hidden.isNotEmpty()) {
                        val next = hidden.removeFirst()
                        if (visited.add(next.conversationId)) hidden.addAll(children[next.conversationId].orEmpty())
                    }
                } else descendants.asReversed().forEach { stack.addLast(it to depth + 1) }
            }
        }
        nodes.filter { it.parentConversationId !in byId }.forEach(::visit)
        nodes.filter { it.conversationId !in visited }.forEach(::visit)
        return Tree(rows, invalid)
    }
}
