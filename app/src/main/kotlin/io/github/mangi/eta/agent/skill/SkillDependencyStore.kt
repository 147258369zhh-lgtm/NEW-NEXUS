package io.github.mangi.eta.agent.skill

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * 记录“哪个已安装 Skill 依赖了哪些 Skill”，用于卸载时保护共享依赖。
 *
 * 这里只保存所有权关系，不复制依赖目录，也不参与安装事务。记录损坏时按“无依赖关系”
 * 处理，与来源记录一致的降级策略；真正的安装/删除仍由 Skill 安装事务负责。
 */
internal class SkillDependencyStore(
    context: Context,
    private val file: File = File(
        context.applicationContext.filesDir,
        "capability-center/skill-dependencies.json",
    ),
) {
    /** All IDs listed by a rollback-ahead parent are new and must disappear. */
    fun removeParentRollback(installedIds: Collection<String>) {
        val ids = installedIds.toList().asReversed().distinct()
        if (ids.isEmpty()) return
        val current = readAllStrict().toMutableMap()
        ids.forEach(current::remove)
        write(current)
    }

    @Synchronized
    fun readAll(): Map<String, Set<String>> {
        return runCatching { readAllStrict() }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun readAllStrict(): Map<String, Set<String>> {
        if (!file.isFile) return emptyMap()
        val root = JSONObject(file.readText(Charsets.UTF_8))
        require(root.optInt("version", 0) == VERSION) { "Skill 依赖记录版本无效" }
        val owners = root.optJSONObject("owners")
            ?: throw IllegalStateException("Skill 依赖记录缺少 owners")
        return buildMap {
            owners.keys().forEach { owner ->
                require(SKILL_ID.matches(owner)) { "Skill 依赖记录包含无效 owner" }
                val values = owners.optJSONArray(owner)
                    ?: throw IllegalStateException("Skill 依赖记录格式无效：$owner")
                val dependencies = buildSet {
                    for (index in 0 until values.length()) {
                        val dependency = values.optString(index).trim()
                        require(SKILL_ID.matches(dependency) && dependency != owner) {
                            "Skill 依赖记录包含无效依赖：$owner"
                        }
                        add(dependency)
                    }
                }
                if (dependencies.isNotEmpty()) put(owner, dependencies)
            }
        }
    }

    @Synchronized
    fun tryCaptureOwners(ownerIds: Collection<String>): List<SkillDependencyRecoverySnapshot> {
        return try {
            captureOwners(ownerIds)
        } catch (failure: Throwable) {
            ownerIds.distinct().map { owner ->
                require(SKILL_ID.matches(owner)) { "Skill 依赖 owner 无效" }
                SkillDependencyRecoverySnapshot(owner, ownerExisted = false, setOf())
            }
        }
    }

    @Synchronized
    fun recordOwner(ownerId: String, dependencyIds: Collection<String>) {
        recordOwners(mapOf(ownerId to dependencyIds.toSet()))
    }

    @Synchronized
    fun recordOwners(ownerDependencies: Map<String, Set<String>>) {
        ownerDependencies.forEach { (owner, dependencies) ->
            require(SKILL_ID.matches(owner)) { "Skill 依赖 owner 无效" }
            require(dependencies.all { SKILL_ID.matches(it) && it != owner }) {
                "Skill 依赖边无效：$owner"
            }
        }
        val current = readAllStrict().toMutableMap()
        ownerDependencies.forEach { (owner, dependencies) ->
            if (dependencies.isEmpty()) current.remove(owner) else current[owner] = dependencies
        }
        write(current)
    }

    @Synchronized
    fun captureOwners(ownerIds: Collection<String>): List<SkillDependencyRecoverySnapshot> {
        val current = readAllStrict()
        return ownerIds.distinct().map { owner ->
            require(SKILL_ID.matches(owner)) { "Skill 依赖 owner 无效" }
            val dependencies = current[owner]
            SkillDependencyRecoverySnapshot(
                ownerId = owner,
                ownerExisted = dependencies != null,
                dependencyIds = dependencies.orEmpty(),
            )
        }
    }

    @Synchronized
    fun restoreOwners(snapshots: List<SkillDependencyRecoverySnapshot>) {
        if (snapshots.isEmpty()) return
        val current = readAllStrict().toMutableMap()
        snapshots.forEach { snapshot ->
            require(SKILL_ID.matches(snapshot.ownerId)) { "Skill 依赖 owner 快照无效" }
            require(snapshot.dependencyIds.all {
                SKILL_ID.matches(it) && it != snapshot.ownerId
            }) { "Skill 依赖边快照无效：${snapshot.ownerId}" }
            if (snapshot.ownerExisted) {
                current[snapshot.ownerId] = snapshot.dependencyIds
            } else {
                current.remove(snapshot.ownerId)
            }
        }
        write(current)
    }

    /** 将依赖写入与可恢复安装边界合并；失败时不留下可见的半提交边。 */
    @Synchronized
    fun commitInstallOwners(
        owners: Map<String, Set<String>>,
        records: List<SkillRecoveryRecord>,
    ) {
        val snapshots = captureOwners(records.map(SkillRecoveryRecord::id))
        try {
            recordOwners(owners)
        } catch (failure: Throwable) {
            runCatching { restoreOwners(snapshots) }.onFailure { failure.addSuppressed(it) }
            throw failure
        }
    }

    @Synchronized
    fun removeOwner(ownerId: String) {
        val current = readAll()
        if (ownerId !in current) return
        write(current - ownerId)
    }

    /** 返回仍然依赖该 Skill 的已安装 Skill；不包含它自己。 */
    fun dependentsOf(skillId: String): Set<String> = readAll()
        .filterValues { skillId in it }
        .keys
        .filterTo(linkedSetOf()) { it != skillId }

    @Synchronized
    internal fun write(owners: Map<String, Set<String>>) {
        val parent = requireNotNull(file.parentFile) { "Skill 依赖记录目录无效" }
        check(parent.mkdirs() || parent.isDirectory) { "无法创建 Skill 依赖记录目录" }
        val root = JSONObject()
            .put("version", VERSION)
            .put("owners", JSONObject().also { target ->
                owners.toSortedMap().forEach { (owner, dependencies) ->
                    target.put(
                        owner,
                        org.json.JSONArray(dependencies.toSortedSet().toList()),
                    )
                }
            })
        val temporary = File.createTempFile("eta-skill-dependencies-", ".tmp", parent)
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

    private companion object {
        const val VERSION = 1
        val SKILL_ID = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    }
}

internal data class SkillDependencyRecoverySnapshot(
    val ownerId: String,
    val ownerExisted: Boolean,
    val dependencyIds: Set<String>,
)
