package io.github.mangi.eta.agent.skill

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONObject

/** 可展示、可追踪的 Skill 安装来源；不参与安装事务，也不保存任何凭据。 */
data class SkillProvenance(
    val skillId: String,
    val sourceId: String,
    val sourceName: String,
    val sourceType: String,
    val repository: String? = null,
    val ref: String? = null,
    val path: String? = null,
    val archiveSha256: String? = null,
    val version: String? = null,
    val installedAt: Long = System.currentTimeMillis(),
)

/** 独立于 Skill 文件事务保存来源元数据，损坏时按“无来源记录”处理。 */
internal class SkillProvenanceStore(
    context: Context,
) {
    private val file = File(context.applicationContext.filesDir, "capability-center/skill-provenance.json")

    @Synchronized
    fun readAll(): Map<String, SkillProvenance> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            require(root.optInt("version", 0) == VERSION)
            val records = root.getJSONObject("skills")
            buildMap {
                records.keys().forEach { key ->
                    parse(records.optJSONObject(key), key)?.let { put(it.skillId, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun record(provenance: SkillProvenance) {
        val updated = readAll().toMutableMap()
        updated[provenance.skillId] = provenance
        write(updated)
    }

    @Synchronized
    fun recordAll(entries: List<SkillProvenance>) {
        if (entries.isEmpty()) return
        val updated = readAll().toMutableMap()
        entries.forEach { updated[it.skillId] = it }
        write(updated)
    }

    @Synchronized
    fun remove(skillId: String) {
        val current = readAll()
        if (skillId !in current) return
        write(current - skillId)
    }

    private fun write(entries: Map<String, SkillProvenance>) {
        val root = JSONObject()
            .put("version", VERSION)
            .put("skills", JSONObject().also { target ->
                entries.toSortedMap().forEach { (id, value) -> target.put(id, encode(value)) }
            })
        val parent = requireNotNull(file.parentFile) { "Skill 来源记录目录无效" }
        parent.mkdirs()
        val temporary = File.createTempFile("eta-skill-provenance-", ".tmp", parent)
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

    private fun encode(value: SkillProvenance): JSONObject = JSONObject()
        .put("skillId", value.skillId)
        .put("sourceId", value.sourceId)
        .put("sourceName", value.sourceName)
        .put("sourceType", value.sourceType)
        .put("repository", value.repository ?: JSONObject.NULL)
        .put("ref", value.ref ?: JSONObject.NULL)
        .put("path", value.path ?: JSONObject.NULL)
        .put("archiveSha256", value.archiveSha256 ?: JSONObject.NULL)
        .put("version", value.version ?: JSONObject.NULL)
        .put("installedAt", value.installedAt)

    private fun parse(value: JSONObject?, fallbackId: String): SkillProvenance? {
        value ?: return null
        val skillId = value.optString("skillId").ifBlank { fallbackId }
        if (!SKILL_ID.matches(skillId)) return null
        return SkillProvenance(
            skillId = skillId,
            sourceId = value.optString("sourceId").take(128),
            sourceName = value.optString("sourceName").take(160),
            sourceType = value.optString("sourceType").take(40),
            repository = value.optionalString("repository"),
            ref = value.optionalString("ref"),
            path = value.optionalString("path"),
            archiveSha256 = value.optionalString("archiveSha256")?.takeIf { SHA256.matches(it) },
            version = value.optionalString("version"),
            installedAt = value.optLong("installedAt", 0L).coerceAtLeast(0L),
        )
    }

    private fun JSONObject.optionalString(key: String): String? =
        optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

    private companion object {
        const val VERSION = 1
        val SKILL_ID = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        val SHA256 = Regex("[a-f0-9]{64}")
    }
}
