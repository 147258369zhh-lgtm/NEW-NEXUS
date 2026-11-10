package io.github.mangi.eta.agent.skill

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** Privacy-preserving local counters used to identify stale or unreliable Skills. */
data class SkillUsageRecord(
    val skillId: String,
    val useCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastUsedAt: Long = 0L,
)

internal class SkillUsageStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "capability-center/skill-usage.json")

    @Synchronized
    fun readAll(): Map<String, SkillUsageRecord> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            require(root.optInt("version", 0) == VERSION)
            val skills = root.getJSONObject("skills")
            buildMap {
                skills.keys().forEach { key ->
                    parse(skills.optJSONObject(key), key)?.let { put(it.skillId, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun recordRun(skillIds: Collection<String>, successful: Boolean, now: Long = System.currentTimeMillis()) {
        val ids = skillIds.asSequence()
            .map(String::trim)
            .filter(SKILL_ID::matches)
            .distinct()
            .take(MAX_SKILLS_PER_RUN)
            .toList()
        if (ids.isEmpty()) return
        val updated = readAll().toMutableMap()
        ids.forEach { skillId ->
            val previous = updated[skillId] ?: SkillUsageRecord(skillId)
            updated[skillId] = previous.copy(
                useCount = previous.useCount.saturatedIncrement(),
                successCount = previous.successCount.saturatedIncrement(successful),
                failureCount = previous.failureCount.saturatedIncrement(!successful),
                lastUsedAt = maxOf(previous.lastUsedAt, now.coerceAtLeast(0L)),
            )
        }
        val bounded = updated.entries
            .sortedByDescending { it.value.lastUsedAt }
            .take(MAX_SKILLS)
            .associate { it.key to it.value }
        write(bounded)
    }

    @Synchronized
    fun remove(skillId: String) {
        val current = readAll()
        if (skillId !in current) return
        write(current - skillId)
    }

    private fun write(entries: Map<String, SkillUsageRecord>) {
        val root = JSONObject()
            .put("version", VERSION)
            .put("skills", JSONObject().also { target ->
                entries.toSortedMap().forEach { (id, value) ->
                    target.put(
                        id,
                        JSONObject()
                            .put("skillId", value.skillId)
                            .put("useCount", value.useCount)
                            .put("successCount", value.successCount)
                            .put("failureCount", value.failureCount)
                            .put("lastUsedAt", value.lastUsedAt),
                    )
                }
            })
        val parent = requireNotNull(file.parentFile) { "Skill 使用记录目录无效" }
        check(parent.mkdirs() || parent.isDirectory) { "无法创建 Skill 使用记录目录" }
        val temporary = File.createTempFile("eta-skill-usage-", ".tmp", parent)
        try {
            temporary.writeText(root.toString(), Charsets.UTF_8)
            runCatching {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun parse(value: JSONObject?, fallbackId: String): SkillUsageRecord? {
        value ?: return null
        val skillId = value.optString("skillId").ifBlank { fallbackId }
        if (!SKILL_ID.matches(skillId)) return null
        return SkillUsageRecord(
            skillId = skillId,
            useCount = value.optInt("useCount", 0).coerceIn(0, MAX_COUNTER),
            successCount = value.optInt("successCount", 0).coerceIn(0, MAX_COUNTER),
            failureCount = value.optInt("failureCount", 0).coerceIn(0, MAX_COUNTER),
            lastUsedAt = value.optLong("lastUsedAt", 0L).coerceAtLeast(0L),
        )
    }

    private fun Int.saturatedIncrement(increment: Boolean = true): Int =
        if (increment) (this + 1).coerceAtMost(MAX_COUNTER) else this

    private companion object {
        const val VERSION = 1
        const val MAX_SKILLS = 512
        const val MAX_SKILLS_PER_RUN = 200
        const val MAX_COUNTER = 1_000_000_000
        val SKILL_ID = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    }
}
