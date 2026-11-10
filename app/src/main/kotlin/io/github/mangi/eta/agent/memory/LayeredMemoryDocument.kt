package io.github.mangi.eta.agent.memory

/** Markdown-compatible memory layers. Existing MEMORY.md files remain valid and portable. */
internal enum class MemoryLayer(val heading: String, val priority: Int) {
    CORE("核心记忆", 0),
    PROJECT("项目记忆", 1),
    TASK("任务记忆", 2),
    LONG_TERM("长期记忆", 3),
    WORKING("工作记忆", 4),
    OTHER("其他记忆", 5),
}

internal data class LayeredMemoryDocument(
    val sections: Map<MemoryLayer, String> = emptyMap(),
) {
    fun render(): String = MemoryLayer.entries
        .mapNotNull { layer -> sections[layer]?.trim()?.takeIf { it.isNotEmpty() }?.let { "# ${layer.heading}\n$it" } }
        .joinToString("\n\n")

    fun promptContent(maxChars: Int): String = MemoryLayer.entries
        .sortedBy { it.priority }
        .mapNotNull { layer -> sections[layer]?.trim()?.takeIf { it.isNotEmpty() } }
        .joinToString("\n\n")
        .take(maxChars.coerceAtLeast(0))

    fun supportingContent(maxChars: Int): String = MemoryLayer.entries
        .filterNot { it == MemoryLayer.CORE }
        .sortedBy { it.priority }
        .mapNotNull { layer -> sections[layer]?.trim()?.takeIf { it.isNotEmpty() }?.let { "# ${layer.heading}\n$it" } }
        .joinToString("\n\n")
        .take(maxChars.coerceAtLeast(0))

    companion object {
        fun parse(markdown: String): LayeredMemoryDocument {
            val lines = markdown.lines()
            val parsed = linkedMapOf<MemoryLayer, StringBuilder>()
            var current: MemoryLayer? = null
            lines.forEach { line ->
                val heading = line.trim().removePrefix("# ").trim()
                val next = MemoryLayer.entries.firstOrNull { it.heading.equals(heading, true) }
                if (next != null) {
                    current = next
                    parsed.getOrPut(next) { StringBuilder() }
                } else if (current != null) {
                    parsed.getValue(current!!).appendLine(line)
                }
            }
            return LayeredMemoryDocument(parsed.mapValues { it.value.toString().trim() })
        }

        fun fromLegacy(markdown: String): LayeredMemoryDocument = parse(markdown).let { parsed ->
            if (parsed.sections.isNotEmpty()) parsed
            else LayeredMemoryDocument(mapOf(MemoryLayer.CORE to markdown.trim()))
        }
    }
}
