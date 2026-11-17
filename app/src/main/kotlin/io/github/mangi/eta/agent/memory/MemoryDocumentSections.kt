package io.github.mangi.eta.agent.memory

/** Offset-based editor: never re-render Markdown or drop unknown sections/preamble. */
internal object MemoryDocumentSections {
    data class Part(val key: String, val title: String, val start: Int, val end: Int, val missing: Boolean = false)
    fun parts(document: String): List<Part> {
        return partsInternal(document)
    }
    private fun partsInternal(document: String): List<Part> {
        val headings = Regex("(?m)^# ([^\\r\\n]+)\\r?$").findAll(document).toList()
        val parts = mutableListOf(Part("preamble", "前言", 0, headings.firstOrNull()?.range?.first ?: document.length))
        headings.forEachIndexed { index, match ->
            val start = (match.range.last + 1).let { if (document.getOrNull(it) == '\n') it + 1 else it }
            parts += Part("section:$index", match.groupValues[1], start, headings.getOrNull(index + 1)?.range?.first ?: document.length)
        }
        MemoryLayer.entries.filter { layer -> parts.none { it.title == layer.heading } }.forEach {
            parts += Part("new:${it.name}", it.heading, document.length, document.length, true)
        }
        return parts
    }
    fun content(document: String, part: Part): String = if (part.missing) "" else document.substring(part.start, part.end)
    fun replace(document: String, part: Part, value: String): String = if (part.missing) {
        if (value.isEmpty()) document else document + (if (document.isEmpty() || document.endsWith("\n\n")) "" else if (document.endsWith('\n')) "\n" else "\n\n") + "# ${part.title}\n$value"
    } else document.substring(0, part.start) + value +
        (if (part.end < document.length && value.isNotEmpty() && !value.endsWith('\n')) "\n" else "") + document.substring(part.end)
}
