package io.github.mangi.eta.agent.skill

import java.util.Locale

/**
 * 轻量、本地、无额外模型调用的 Skill 相关度排序。
 * 结果只决定首轮暴露顺序，不替代 Agent 调用 skills_list 的主动发现。
 */
internal object SkillMatcher {
    fun rankForTask(
        skills: List<SkillIndexEntry>,
        task: String,
        limit: Int = 60,
    ): List<SkillIndexEntry> {
        if (skills.size <= limit) return skills
        val taskProfile = profile(task)
        if (taskProfile.terms.isEmpty()) return skills.take(limit)
        return skills
            .mapIndexed { index, skill ->
                RankedSkill(skill, score(taskProfile, skill), index)
            }
            .sortedWith(
                compareByDescending<RankedSkill> { it.score }
                    .thenBy { it.originalIndex },
            )
            .take(limit)
            .map(RankedSkill::skill)
    }

    internal fun score(task: String, skill: SkillIndexEntry): Int =
        score(profile(task), skill)

    private fun score(task: TaskProfile, skill: SkillIndexEntry): Int {
        if (task.terms.isEmpty()) return 0
        val name = normalize(skill.name + " " + skill.id)
        val description = normalize(skill.description)
        val metadata = normalize(skill.metadata.values.joinToString(" "))
        var score = 0
        task.terms.forEach { term ->
            val weight = term.length.coerceIn(2, 8)
            if (term in name) score += weight * 8
            if (term in description) score += weight * 3
            if (term in metadata) score += weight * 2
        }
        if (name.isNotBlank() && normalize(skill.name) in task.normalized) score += 40
        return score
    }

    private fun profile(value: String): TaskProfile {
        val normalized = normalize(value)
        val terms = linkedSetOf<String>()
        TOKEN.findAll(normalized).forEach { match ->
            val token = match.value.trim()
            if (token.length >= 2) terms += token
        }
        val cjkRuns = CJK_RUN.findAll(normalized).map { it.value }.toList()
        cjkRuns.forEach { run ->
            if (run.length == 2) {
                terms += run
            } else if (run.length > 2) {
                for (index in 0..run.length - 2) {
                    terms += run.substring(index, index + 2)
                }
            }
        }
        return TaskProfile(normalized, terms.take(160).toSet())
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[\\p{P}\\p{S}]+"), " ")
            .replace(Regex("[ ]+"), " ")
            .trim()

    private data class TaskProfile(
        val normalized: String,
        val terms: Set<String>,
    )

    private data class RankedSkill(
        val skill: SkillIndexEntry,
        val score: Int,
        val originalIndex: Int,
    )

    private val TOKEN = Regex("[a-z0-9_+-]{2,}|[\\p{IsHan}]{2,}")
    private val CJK_RUN = Regex("[\\p{IsHan}]{2,}")
}
