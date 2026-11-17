package io.github.mangi.eta.agent.skill

/** Resolves persisted composer IDs against the current run's enabled, compatible and in-scope index. */
internal object SelectedSkillResolver {
    data class Result(val skills: List<SkillIndexEntry>, val error: String? = null)

    fun resolve(
        availableSkills: List<SkillIndexEntry>,
        requestedIds: List<String>,
        limit: Int = SkillCoordination.DEFAULT_LIMIT,
    ): Result {
        if (requestedIds.isEmpty()) return Result(emptyList())
        val ids = requestedIds.map(String::trim)
        if (limit <= 0 || ids.size > limit || ids.any { it.isBlank() || it.length > 160 } || ids.distinct().size != ids.size) {
            return Result(emptyList(), "SELECTED_SKILL_SELECTION_INVALID")
        }
        val byId = availableSkills.filter { it.installed && it.enabled }.associateBy { it.id }
        val resolved = ids.mapNotNull(byId::get)
        return if (resolved.size == ids.size) Result(resolved)
        else Result(emptyList(), "SELECTED_SKILL_UNAVAILABLE")
    }
}
