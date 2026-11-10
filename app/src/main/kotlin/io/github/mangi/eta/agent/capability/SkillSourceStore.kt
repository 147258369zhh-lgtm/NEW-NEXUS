package io.github.mangi.eta.agent.capability

import android.content.Context
import io.github.mangi.eta.agent.skill.GitHubSkillRepositoryParser
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** 多来源 Skill 目录配置。来源顺序稳定，单个来源失败不会删除其他来源。 */
class SkillSourceStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "capability-center/skill-sources.json")

    @Synchronized
    fun load(): List<SkillSourceDefinition> {
        if (!file.isFile) return defaultSources()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            require(root.optInt("version") == VERSION)
            val values = root.optJSONArray("sources") ?: JSONArray()
            buildList {
                for (index in 0 until values.length()) {
                    values.optJSONObject(index)?.let(::parseSource)?.let(::add)
                }
            }.distinctBy(SkillSourceDefinition::id).sortedWith(
                compareBy<SkillSourceDefinition> { it.priority }.thenBy { it.name.lowercase(Locale.ROOT) },
            )
        }.getOrElse { defaultSources() }
    }

    @Synchronized
    fun save(sources: List<SkillSourceDefinition>): List<SkillSourceDefinition> {
        val validated = sources
            .map(::validate)
            .distinctBy(SkillSourceDefinition::id)
            .take(MAX_SOURCES)
            .sortedWith(compareBy<SkillSourceDefinition> { it.priority }.thenBy { it.name })
        write(validated)
        return validated
    }

    @Synchronized
    fun add(
        type: String,
        name: String,
        location: String,
        ref: String? = null,
        path: String? = null,
        trust: String = SkillSourceTrust.CUSTOM,
    ): List<SkillSourceDefinition> {
        val current = load()
        check(current.size < MAX_SOURCES) { "最多添加 $MAX_SOURCES 个 Skill 来源" }
        val source = validate(
            SkillSourceDefinition(
                id = "source-${UUID.randomUUID()}",
                name = name,
                type = type,
                location = location,
                ref = ref,
                path = path,
                enabled = true,
                priority = (current.maxOfOrNull { it.priority } ?: 0) + 10,
                trust = trust,
                builtIn = false,
            ),
        )
        return save(current + source)
    }

    @Synchronized
    fun setEnabled(sourceId: String, enabled: Boolean): List<SkillSourceDefinition> {
        val current = load()
        require(current.any { it.id == sourceId }) { "Skill 来源不存在" }
        return save(current.map { if (it.id == sourceId) it.copy(enabled = enabled) else it })
    }

    @Synchronized
    fun delete(sourceId: String): List<SkillSourceDefinition> {
        val current = load()
        val source = current.firstOrNull { it.id == sourceId }
            ?: throw SkillSourceValidationException("Skill 来源不存在")
        if (source.builtIn) throw SkillSourceValidationException("内置来源不能删除，可以关闭")
        return save(current.filterNot { it.id == sourceId })
    }

    @Synchronized
    fun resetBuiltinSources(): List<SkillSourceDefinition> {
        val custom = load().filterNot(SkillSourceDefinition::builtIn)
        return save(defaultSources() + custom)
    }

    private fun validate(source: SkillSourceDefinition): SkillSourceDefinition {
        val id = source.id.trim()
        val name = source.name.trim()
        val location = source.location.trim()
        if (!SOURCE_ID.matches(id)) throw SkillSourceValidationException("来源 ID 无效")
        if (name.isBlank() || name.length > 80 || name.any(Char::isISOControl)) {
            throw SkillSourceValidationException("来源名称应为 1-80 个可见字符")
        }
        if (location.isBlank() || location.length > 2_048) {
            throw SkillSourceValidationException("来源地址不能为空且不能超过 2048 字符")
        }
        val trust = source.trust.takeIf { it in TRUST_VALUES } ?: SkillSourceTrust.CUSTOM
        return when (source.type) {
            SkillSourceType.GITHUB_REPOSITORY -> {
                val repository = GitHubSkillRepositoryParser.resolve(
                    repository = location,
                    explicitRef = source.ref,
                    explicitPath = source.path,
                )
                source.copy(
                    name = name,
                    location = repository.slug,
                    ref = repository.ref,
                    path = repository.path,
                    trust = trust,
                )
            }
            SkillSourceType.HTTP_CATALOG -> validateCatalogLocation(source.copy(name = name, trust = trust))
            else -> throw SkillSourceValidationException("不支持的 Skill 来源类型")
        }
    }

    private fun validateCatalogLocation(source: SkillSourceDefinition): SkillSourceDefinition {
        if (source.ref != null || source.path != null) {
            throw SkillSourceValidationException("目录来源不接受 Git ref 或路径")
        }
        val uri = runCatching { URI(source.location) }
            .getOrElse { throw SkillSourceValidationException("目录地址无效") }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            throw SkillSourceValidationException("自定义目录必须使用 HTTPS")
        }
        if (uri.rawUserInfo != null || uri.rawFragment != null || uri.rawQuery != null || uri.port !in setOf(-1, 443)) {
            throw SkillSourceValidationException("目录地址不能包含凭据、查询参数、片段或非标准端口")
        }
        return source.copy(location = uri.toASCIIString())
    }

    private fun parseSource(value: JSONObject): SkillSourceDefinition? = runCatching {
        SkillSourceDefinition(
            id = value.optString("id"),
            name = value.optString("name"),
            type = value.optString("type"),
            location = value.optString("location"),
            ref = value.optionalString("ref"),
            path = value.optionalString("path"),
            enabled = value.optBoolean("enabled", true),
            priority = value.optInt("priority", 100).coerceIn(0, 10_000),
            trust = value.optString("trust"),
            builtIn = value.optBoolean("builtIn", false),
        ).let(::validate)
    }.getOrNull()

    private fun write(sources: List<SkillSourceDefinition>) {
        val root = JSONObject()
            .put("version", VERSION)
            .put("sources", JSONArray().also { values ->
                sources.forEach { source ->
                    values.put(
                        JSONObject()
                            .put("id", source.id)
                            .put("name", source.name)
                            .put("type", source.type)
                            .put("location", source.location)
                            .put("ref", source.ref ?: JSONObject.NULL)
                            .put("path", source.path ?: JSONObject.NULL)
                            .put("enabled", source.enabled)
                            .put("priority", source.priority)
                            .put("trust", source.trust)
                            .put("builtIn", source.builtIn),
                    )
                }
            })
        val parent = requireNotNull(file.parentFile) { "Skill 来源配置目录无效" }
        parent.mkdirs()
        val temporary = File.createTempFile("eta-skill-sources-", ".tmp", parent)
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

    private fun defaultSources(): List<SkillSourceDefinition> = listOf(
        SkillSourceDefinition(
            id = BUILTIN_CURATED_ID,
            name = "OpenAI 精选 Skill",
            type = SkillSourceType.GITHUB_REPOSITORY,
            location = "openai/skills",
            ref = "main",
            path = "skills/.curated",
            enabled = true,
            priority = 10,
            trust = SkillSourceTrust.CURATED,
            builtIn = true,
        ),
    )

    private fun JSONObject.optionalString(key: String): String? =
        optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

    private companion object {
        const val VERSION = 1
        const val MAX_SOURCES = 32
        const val BUILTIN_CURATED_ID = "openai-curated"
        val SOURCE_ID = Regex("[a-z0-9][a-z0-9._-]{0,127}")
        val TRUST_VALUES = setOf(
            SkillSourceTrust.OFFICIAL,
            SkillSourceTrust.CURATED,
            SkillSourceTrust.COMMUNITY,
            SkillSourceTrust.CUSTOM,
        )
    }
}
