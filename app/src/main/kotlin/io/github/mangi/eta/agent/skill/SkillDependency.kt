package io.github.mangi.eta.agent.skill

/** Parses optional dependency metadata without treating descriptive prose as executable requirements. */
internal object SkillDependencyParser {
    private val KEYS = listOf(
        "dependencies",
        "requires",
        "requirements",
        "required-tools",
        "required_tools",
        "tools",
    )

    fun parse(
        frontmatter: Map<String, String>,
        metadata: Map<String, String>,
    ): List<String> = buildList {
        KEYS.forEach { key ->
            frontmatter[key]?.let(::parseValue)?.let(::addAll)
            metadata[key]?.let(::parseValue)?.let(::addAll)
        }
    }.map(String::trim)
        .filter { it.isNotEmpty() && it.length <= 160 && it.none(Char::isISOControl) }
        .distinctBy { it.lowercase() }
        .take(MAX_DEPENDENCIES)

    private fun parseValue(raw: String): List<String> {
        val normalized = raw.trim().removePrefix("[").removeSuffix("]")
        if (normalized.isBlank()) return emptyList()
        return normalized.split(',', ';', '\n')
            .map { value -> value.trim().removePrefix("-").trim().trim('\'', '"') }
            .filter(String::isNotBlank)
    }

    private const val MAX_DEPENDENCIES = 32
}
