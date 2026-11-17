package io.github.mangi.eta.agent.memory

internal data class TaskMemoryItem(
    val id: String,
    val scope: String,
    val content: String,
    val source: String,
    val core: Boolean = false,
    val title: String = "",
)

internal data class TaskMemorySelection(val selected: List<TaskMemoryItem>, val omitted: Map<String, String>) {
    fun render(): String = selected.joinToString("\n\n", transform = TaskMemorySelector::render)
}

/** Deterministic lexical fallback: scope filtering precedes ranking, and entries never get cut. */
internal object TaskMemorySelector {
    fun budget(contextWindow: Int?): Int = ((contextWindow?.takeIf { it > 0 } ?: 16_000) / 24).coerceAtMost(8000)
    fun coreBudget(contextWindow: Int?): Int = (budget(contextWindow) / 2).coerceAtMost(1200)
    fun render(item: TaskMemoryItem): String {
        val source = if (item.source.startsWith("MEMORY.md@")) "MEMORY.md@" + item.source.removePrefix("MEMORY.md@").take(8) else item.source
        return "[${item.scope} · $source] ${item.title}\n${item.content}"
    }

    fun select(items: List<TaskMemoryItem>, scopes: Set<String>, query: String, contextWindow: Int?): TaskMemorySelection {
        var remaining = budget(contextWindow)
        var coreRemaining = coreBudget(contextWindow)
        val seen = mutableSetOf<String>()
        val omitted = linkedMapOf<String, String>()
        val selected = mutableListOf<TaskMemoryItem>()
        val eligible = items.filter { item ->
            (item.scope in scopes).also { if (!it) omitted[item.id] = "scope" }
        }.filter { it.content.isNotBlank() }
        val ranked = eligible.sortedWith(compareByDescending<TaskMemoryItem> { it.core }
            .thenByDescending { relevance(query, it.title + "\n" + it.content) }
            .thenByDescending { it.scope != "global" })
        ranked.forEach { item ->
            val score = relevance(query, item.title + "\n" + item.content)
            val resident = item.core && render(item).length + 2 <= coreRemaining
            val key = MemoryAutomationPolicy.dedupKey(item.content)
            val size = render(item).length + 2
            val reason = when {
                !resident && score == 0 -> "unrelated"
                key in seen -> "duplicate"
                size > remaining -> "budget"
                else -> null
            }
            if (reason != null) omitted[item.id] = reason else {
                selected += item
                seen += key
                remaining -= size
                if (resident) coreRemaining -= size
            }
        }
        return TaskMemorySelection(selected, omitted)
    }

    fun relevance(query: String, text: String): Int {
        val haystack = text.lowercase()
        val normalized = query.take(2000).lowercase().trim()
        if (normalized.isBlank()) return 0
        val terms = Regex("[a-z0-9_./:-]{2,}|[\\u4e00-\\u9fff]{2,}").findAll(normalized).flatMap { match ->
            if (match.value.first() in '\u4e00'..'\u9fff') match.value.windowed(2).asSequence() else sequenceOf(match.value)
        }.filterNot { it in stopWords }.distinct().take(80).toList()
        return terms.count { haystack.contains(it) } + if (haystack.contains(normalized)) 4 else 0
    }

    private val stopWords = setOf("the", "and", "for", "with", "this", "that", "please", "任务", "进行", "请你", "我们", "当前", "需要")

    /** A bullet (including its indented continuation) or paragraph is the smallest portable unit. */
    fun documentItems(scope: String, markdown: String, revision: String): List<TaskMemoryItem> {
        var heading = "前言"
        var core = false
        val result = mutableListOf<TaskMemoryItem>()
        val body = StringBuilder()
        fun flush() {
            val content = body.toString().trim()
            if (content.isNotEmpty()) result += TaskMemoryItem("$scope:doc:${result.size}", scope, content,
                "MEMORY.md@$revision", core, heading)
            body.clear()
        }
        markdown.lineSequence().forEach { line ->
            when {
                line.startsWith("# ") -> { flush(); heading = line.removePrefix("# ").trim(); core = heading == MemoryLayer.CORE.heading }
                line.isBlank() -> flush()
                Regex("^(?:[-*+] |\\d+\\. ).+").matches(line) -> { flush(); body.appendLine(line) }
                else -> body.appendLine(line)
            }
        }
        flush()
        return result
    }
}
