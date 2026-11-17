package io.github.mangi.eta.agent.skill

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class SkillRecoveryRecord(
    val id: String,
    val originalTargetExisted: Boolean,
    val backupCompleted: Boolean = false,
    val newTargetCommitted: Boolean = false,
    val registrySnapshot: SkillRegistryRecoverySnapshot = SkillRegistryRecoverySnapshot(
        skillId = id,
        entryExisted = false,
    ),
    val dependencySnapshot: SkillDependencyRecoverySnapshot? = null,
)

internal data class SkillRegistryRecoverySnapshot(
    val skillId: String,
    val entryExisted: Boolean,
    val enabled: Boolean = false,
    val source: String = "",
    val installState: String = "",
)

internal data class RecoveredSkillOperation(
    val operationDirectory: File,
    val records: List<SkillRecoveryRecord>,
    val journalFileName: String = JOURNAL_FILE_NAME,
    val dependencyOwnerSnapshots: List<SkillDependencyRecoverySnapshot> =
        records.mapNotNull(SkillRecoveryRecord::dependencySnapshot),
    val committedRootIds: Set<String> = emptySet(),
    val parentOperationDirectory: File? = null,
    val isParentDependencyTransaction: Boolean = false,
) {
    val dependencySnapshots: List<SkillDependencyRecoverySnapshot>
        get() = dependencyOwnerSnapshots
    val committedWithoutRollback: Boolean
        get() = records.isEmpty() && dependencyOwnerSnapshots.isEmpty()
    val associatedRootIds: Set<String>
        get() = committedRootIds + records.map(SkillRecoveryRecord::id)
}

/** 文件与 registry 恢复完成后，在同一锁内复原依赖所有权；空快照保持旧版行为。 */
internal fun applyRecoveredDependencyOwners(
    recovered: List<RecoveredSkillOperation>,
    restore: (List<SkillDependencyRecoverySnapshot>) -> Unit,
) {
    val snapshots = recovered
        .flatMap(RecoveredSkillOperation::dependencySnapshots)
        .distinctBy(SkillDependencyRecoverySnapshot::ownerId)
    if (snapshots.isNotEmpty()) restore(snapshots)
}

internal class PendingSkillRecoveryJournal private constructor(
    private val operationDirectory: File,
    records: List<SkillRecoveryRecord>,
) {
    private var records = records

    fun markBackupCompleted(skillId: String) {
        update(skillId) { it.copy(backupCompleted = true) }
    }

    fun markNewTargetCommitted(skillId: String) {
        update(skillId) { it.copy(newTargetCommitted = true) }
    }

    fun clear() {
        Files.deleteIfExists(File(operationDirectory, JOURNAL_FILE_NAME).toPath())
    }

    private fun update(skillId: String, transform: (SkillRecoveryRecord) -> SkillRecoveryRecord) {
        var found = false
        records = records.map { record ->
            if (record.id == skillId) {
                found = true
                transform(record)
            } else {
                record
            }
        }
        check(found) { "恢复日志中不存在 Skill：$skillId" }
        writeJournalAtomically(operationDirectory, records)
    }

    companion object {
        fun begin(
            skillsRoot: File,
            operationDirectory: File,
            records: List<SkillRecoveryRecord>,
        ): PendingSkillRecoveryJournal {
            validateOperationDirectory(skillsRoot, operationDirectory)
            require(records.isNotEmpty()) { "恢复日志至少需要一个 Skill" }
            validateRecords(records)
            writeJournalAtomically(operationDirectory, records)
            return PendingSkillRecoveryJournal(operationDirectory, records)
        }
    }
}

internal class SkillRecoveryRequiredException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** 必须在持有 [SkillMutationLock] 的跨进程锁时调用。 */
internal fun recoverPendingSkillOperations(
    skillsRoot: File,
    directoryMover: SkillDirectoryMover = AtomicSkillDirectoryMover,
    excludedOperationDirectories: Set<File> = emptySet(),
): List<RecoveredSkillOperation> {
    val workRoot = skillInstallerWorkRoot(skillsRoot)
    if (!Files.exists(workRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
    if (!workRoot.isDirectory || Files.isSymbolicLink(workRoot.toPath())) {
        throw SkillRecoveryRequiredException("Skill 恢复目录不安全")
    }
    val operations = workRoot.listFiles()
        ?.filter { it.name.startsWith(OPERATION_PREFIX) || it.name.startsWith("dependency-operation-") }
        ?.sortedBy { it.name }
        ?: throw SkillRecoveryRequiredException("无法读取 Skill 恢复目录")
    val excludedOperations = excludedOperationDirectories
        .mapTo(mutableSetOf()) { operation -> operation.canonicalFile }
    val committedParents = operations.mapNotNull { operation ->
        if (!operation.name.startsWith("dependency-operation-")) return@mapNotNull null
        val journalFile = File(operation, JOURNAL_FILE_NAME)
        if (!Files.exists(journalFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
        validateOperationDirectory(skillsRoot, operation)
        SkillDependencyTransactionJournal.committedRootId(skillsRoot, operation, journalFile)
            ?.let { rootId -> rootId to operation }
    }.toMap()
    val committedDependencyParents = buildMap {
        operations.forEach { operation ->
            if (!operation.name.startsWith("dependency-operation-")) return@forEach
            val journalFile = File(operation, JOURNAL_FILE_NAME)
            if (!Files.exists(journalFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
            validateOperationDirectory(skillsRoot, operation)
            SkillDependencyTransactionJournal.committedDependencyIds(skillsRoot, journalFile)
                .forEach { skillId -> put(skillId, operation) }
        }
    }
    val committedRootIds = committedParents.keys
    val committedChildIds = committedRootIds + committedDependencyParents.keys
    val recovered = operations.mapNotNull { operation ->
        if (operation.canonicalFile in excludedOperations) return@mapNotNull null
        val journalFile = File(operation, JOURNAL_FILE_NAME)
        if (!Files.exists(journalFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
        if (operation.name.startsWith("dependency-operation-")) {
            SkillDependencyTransactionJournal.recover(skillsRoot, operation, journalFile)
        } else {
            recoverOperation(
                skillsRoot,
                operation,
                journalFile,
                directoryMover,
                committedRootIds,
                committedParents,
                committedChildIds,
                committedDependencyParents,
            )
        }
    }
    val duplicateIds = recovered
        .flatMap { it.records }
        .groupBy { it.id }
        .filterValues { it.size > 1 }
        .keys
    if (duplicateIds.isNotEmpty()) {
        recoveryFailure("多个待恢复事务包含相同 Skill")
    }
    return recovered
}

private fun recoverOperation(
    skillsRoot: File,
    operation: File,
    journalFile: File,
    directoryMover: SkillDirectoryMover,
    committedRootIds: Set<String>,
    committedParents: Map<String, File>,
    committedChildIds: Set<String>,
    committedDependencyParents: Map<String, File>,
): RecoveredSkillOperation {
    try {
        validateOperationDirectory(skillsRoot, operation)
        if (Files.isSymbolicLink(journalFile.toPath()) || !journalFile.isFile) {
            recoveryFailure("Skill 恢复日志不是普通文件")
        }
        if (journalFile.length() !in 1..MAX_JOURNAL_BYTES) {
            recoveryFailure("Skill 恢复日志大小无效")
        }
        val journalRecords = parseJournal(journalFile)
        val records = journalRecords.filterNot { it.id in committedChildIds }
        if (records.isEmpty()) {
            val roots = committedRootIds.intersect(journalRecords.mapTo(mutableSetOf()) { it.id })
            val dependencies = committedDependencyParents.keys.intersect(
                journalRecords.mapTo(mutableSetOf()) { it.id },
            )
            return RecoveredSkillOperation(
                operationDirectory = operation,
                records = emptyList(),
                committedRootIds = roots + dependencies,
                parentOperationDirectory = (roots + dependencies).singleOrNull()?.let { id ->
                    committedParents[id] ?: committedDependencyParents[id]
                },
            )
        }
        val backupRoot = File(operation, BACKUP_DIRECTORY_NAME)
        records.forEach { record ->
            val target = File(skillsRoot, record.id)
            val backup = File(backupRoot, record.id)
            val backupExists = Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)
            if (record.originalTargetExisted) {
                when {
                    backupExists -> restoreBackup(
                        skillsRoot,
                        operation,
                        backupRoot,
                        record.id,
                        directoryMover,
                    )
                    record.backupCompleted -> recoveryFailure("旧 Skill 备份缺失：${record.id}")
                    !isSafeExistingTarget(skillsRoot, target) ->
                        recoveryFailure("旧 Skill 目标与恢复日志不一致：${record.id}")
                }
            } else {
                if (backupExists) recoveryFailure("全新 Skill 不应存在旧备份：${record.id}")
                if (!deleteSkillPathWithoutFollowingLinks(skillsRoot, target)) {
                    recoveryFailure("无法移除未完成安装的 Skill：${record.id}")
                }
            }
        }
        return RecoveredSkillOperation(operationDirectory = operation, records = records)
    } catch (error: SkillRecoveryRequiredException) {
        throw error
    } catch (error: Exception) {
        throw SkillRecoveryRequiredException("Skill 自动恢复失败", error)
    }
}

internal fun completeRecoveredSkillOperations(
    skillsRoot: File,
    recovered: List<RecoveredSkillOperation>,
    dependencyRestore: ((List<SkillDependencyRecoverySnapshot>) -> Unit)? = null,
) {
    val workRoot = skillInstallerWorkRoot(skillsRoot)
    // Clear the committed root journal first while the parent rollback record is still durable.
    // Dependency journals and then the parent are cleared only after every root is durable.
    val ordered = recovered.sortedWith(
        compareBy<RecoveredSkillOperation> { recovery ->
            when {
                recovery.isParentDependencyTransaction -> 2
                recovery.operationDirectory.name.startsWith("dependency-operation-") -> 1
                else -> 0
            }
        },
    )
    ordered.forEach { recovery ->
        val operation = recovery.operationDirectory
        validateOperationDirectory(skillsRoot, operation)
        if (dependencyRestore != null) {
            applyRecoveredDependencyOwners(listOf(recovery), dependencyRestore)
        }
        Files.deleteIfExists(File(operation, recovery.journalFileName).toPath())
        // journal 删除即表示文件与 registry 已共同恢复完成；残留的无 journal 暂存目录
        // 不再影响索引，清理失败也不能把已经完成的恢复重新标记为待处理。
        deleteSkillPathWithoutFollowingLinks(workRoot, operation)
    }
}

internal fun createSkillRecoveryOperationDirectory(skillsRoot: File): File {
    val workRoot = prepareSkillInstallerWorkRoot(skillsRoot)
    repeat(8) {
        val operation = File(workRoot, "$OPERATION_PREFIX${UUID.randomUUID()}")
        if (operation.mkdir()) return operation
    }
    throw IOException("无法创建 Skill 安装事务")
}

private fun restoreBackup(
    skillsRoot: File,
    operation: File,
    backupRoot: File,
    skillId: String,
    directoryMover: SkillDirectoryMover,
) {
    if (
        Files.isSymbolicLink(backupRoot.toPath()) || !backupRoot.isDirectory ||
        !isStrictChild(operation, backupRoot)
    ) {
        recoveryFailure("Skill 备份根目录不安全")
    }
    val backup = File(backupRoot, skillId)
    if (
        Files.isSymbolicLink(backup.toPath()) || !backup.isDirectory ||
        !isStrictChild(backupRoot, backup)
    ) {
        recoveryFailure("旧 Skill 备份不安全：$skillId")
    }
    val recoveryRoot = File(operation, RECOVERY_DIRECTORY_NAME)
    if (!recoveryRoot.mkdirs() && !recoveryRoot.isDirectory) {
        recoveryFailure("无法创建 Skill 恢复暂存目录")
    }
    if (Files.isSymbolicLink(recoveryRoot.toPath()) || !isStrictChild(operation, recoveryRoot)) {
        recoveryFailure("Skill 恢复暂存目录不安全")
    }
    val staging = File(recoveryRoot, skillId)
    if (!deleteSkillPathWithoutFollowingLinks(operation, staging)) {
        recoveryFailure("无法清理 Skill 恢复暂存目录")
    }
    copyDirectoryWithoutFollowingLinks(backup, staging)

    val target = File(skillsRoot, skillId)
    if (!deleteSkillPathWithoutFollowingLinks(skillsRoot, target)) {
        recoveryFailure("无法清理未完成提交的 Skill：$skillId")
    }
    directoryMover.move(staging, target)
}

private fun copyDirectoryWithoutFollowingLinks(source: File, target: File) {
    if (Files.isSymbolicLink(source.toPath()) || !source.isDirectory) {
        recoveryFailure("Skill 备份目录不安全")
    }
    if (!target.mkdir()) recoveryFailure("无法创建 Skill 恢复副本")
    val children = source.listFiles() ?: recoveryFailure("无法读取 Skill 备份目录")
    children.forEach { child ->
        if (Files.isSymbolicLink(child.toPath())) {
            recoveryFailure("Skill 备份包含符号链接")
        }
        val destination = File(target, child.name)
        when {
            child.isDirectory -> copyDirectoryWithoutFollowingLinks(child, destination)
            child.isFile -> Files.copy(child.toPath(), destination.toPath())
            else -> recoveryFailure("Skill 备份包含不支持的文件类型")
        }
    }
}

private fun parseJournal(journalFile: File): List<SkillRecoveryRecord> {
    val json = runCatching { JSONObject(journalFile.readText(Charsets.UTF_8)) }
        .getOrElse { recoveryFailure("Skill 恢复日志格式无效") }
    val version = json.optInt("version", -1)
    if (version !in JOURNAL_VERSION..DEPENDENCY_JOURNAL_VERSION) {
        recoveryFailure("Skill 恢复日志版本不受支持")
    }
    val entries = json.optJSONArray("skills") ?: recoveryFailure("Skill 恢复日志缺少 skills")
    if (entries.length() !in 1..MAX_JOURNAL_SKILLS) {
        recoveryFailure("Skill 恢复日志条目数无效")
    }
    val records = (0 until entries.length()).map { index ->
        val entry = entries.optJSONObject(index) ?: recoveryFailure("Skill 恢复日志条目无效")
        val id = entry.optString("id")
        if (!entry.has("originalTargetExisted") || entry.opt("originalTargetExisted") !is Boolean) {
            recoveryFailure("Skill 恢复日志缺少原目标状态")
        }
        if (!entry.has("backupCompleted") || entry.opt("backupCompleted") !is Boolean) {
            recoveryFailure("Skill 恢复日志缺少备份状态")
        }
        if (!entry.has("newTargetCommitted") || entry.opt("newTargetCommitted") !is Boolean) {
            recoveryFailure("Skill 恢复日志缺少提交状态")
        }
        SkillRecoveryRecord(
            id = id,
            originalTargetExisted = entry.getBoolean("originalTargetExisted"),
            backupCompleted = entry.getBoolean("backupCompleted"),
            newTargetCommitted = entry.getBoolean("newTargetCommitted"),
            registrySnapshot = parseRegistrySnapshot(entry, id),
            dependencySnapshot = if (version >= DEPENDENCY_JOURNAL_VERSION) {
                parseDependencySnapshot(entry, id)
            } else null,
        )
    }
    validateRecords(records)
    return records
}

private fun writeJournalAtomically(
    operationDirectory: File,
    records: List<SkillRecoveryRecord>,
) {
    val json = JSONObject()
        .put(
            "version",
            if (records.any { it.dependencySnapshot != null }) DEPENDENCY_JOURNAL_VERSION else JOURNAL_VERSION,
        )
        .put(
            "skills",
            JSONArray().apply {
                records.forEach { record ->
                    put(
                        JSONObject()
                            .put("id", record.id)
                            .put("originalTargetExisted", record.originalTargetExisted)
                            .put("backupCompleted", record.backupCompleted)
                            .put("newTargetCommitted", record.newTargetCommitted)
                            .put(
                                "registry",
                                JSONObject()
                                    .put("entryExisted", record.registrySnapshot.entryExisted)
                                    .put("enabled", record.registrySnapshot.enabled)
                                    .put("source", record.registrySnapshot.source)
                                    .put("installState", record.registrySnapshot.installState)
                            )
                            .also { entry ->
                                record.dependencySnapshot?.let { snapshot ->
                                    entry.put(
                                        "dependencies",
                                        JSONObject()
                                            .put("ownerExisted", snapshot.ownerExisted)
                                            .put("dependencyIds", JSONArray(snapshot.dependencyIds.sorted())),
                                    )
                                }
                            }
                        ,
                    )
                }
            }
        )
        .put("dependencies", JSONArray().apply {
            records.mapNotNull(SkillRecoveryRecord::dependencySnapshot).forEach { snapshot ->
                put(
                    JSONObject()
                        .put("ownerId", snapshot.ownerId)
                        .put("ownerExisted", snapshot.ownerExisted)
                        .put("dependencyIds", JSONArray(snapshot.dependencyIds.sorted()))
                )
            }
        })
    val journal = File(operationDirectory, JOURNAL_FILE_NAME)
    val temporary = File(operationDirectory, ".$JOURNAL_FILE_NAME-${UUID.randomUUID()}")
    try {
        FileOutputStream(temporary).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                journal.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                journal.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    } finally {
        Files.deleteIfExists(temporary.toPath())
    }
}

private fun validateOperationDirectory(skillsRoot: File, operationDirectory: File) {
    val workRoot = skillInstallerWorkRoot(skillsRoot)
    if (
        Files.isSymbolicLink(operationDirectory.toPath()) || !operationDirectory.isDirectory ||
        operationDirectory.parentFile?.canonicalFile != workRoot.canonicalFile ||
        !OPERATION_NAME_REGEX.matches(operationDirectory.name)
    ) {
        recoveryFailure("Skill 操作目录不安全")
    }
}

private fun validateRecords(records: List<SkillRecoveryRecord>) {
    if (records.map { it.id }.distinct().size != records.size) {
        recoveryFailure("Skill 恢复日志包含重复 id")
    }
    records.forEach { record ->
        if (record.id.length !in 1..64 || !SKILL_ID_REGEX.matches(record.id)) {
            recoveryFailure("Skill 恢复日志包含非法 id")
        }
        if (!record.originalTargetExisted && record.backupCompleted) {
            recoveryFailure("全新 Skill 的恢复日志包含非法备份状态")
        }
        if (record.originalTargetExisted && record.newTargetCommitted && !record.backupCompleted) {
            recoveryFailure("替换 Skill 的恢复日志状态不一致")
        }
        val registry = record.registrySnapshot
        if (registry.skillId != record.id) recoveryFailure("Skill 恢复日志 registry id 不一致")
        record.dependencySnapshot?.let { dependencies ->
            if (
                dependencies.ownerId != record.id ||
                dependencies.dependencyIds.any { !SKILL_ID_REGEX.matches(it) || it == record.id } ||
                dependencies.dependencyIds.size != dependencies.dependencyIds.distinct().size ||
                (!dependencies.ownerExisted && dependencies.dependencyIds.isNotEmpty())
            ) recoveryFailure("Skill 恢复日志包含非法依赖边")
        }
        if (registry.entryExisted) {
            if (
                registry.source !in VALID_REGISTRY_SOURCES ||
                registry.installState !in VALID_INSTALL_STATES ||
                (registry.source == USER_SKILL_SOURCE &&
                    registry.installState != INSTALL_STATE_INSTALLED_VALUE)
            ) {
                recoveryFailure("Skill 恢复日志包含非法 registry 状态")
            }
        } else if (
            registry.enabled || registry.source.isNotEmpty() || registry.installState.isNotEmpty()
        ) {
            recoveryFailure("不存在的 registry 快照包含额外状态")
        }
    }
}

private fun parseRegistrySnapshot(
    entry: JSONObject,
    skillId: String,
): SkillRegistryRecoverySnapshot {
    val registry = entry.optJSONObject("registry")
        ?: recoveryFailure("Skill 恢复日志缺少 registry 快照")
    val booleanKeys = listOf("entryExisted", "enabled")
    if (booleanKeys.any { key -> !registry.has(key) || registry.opt(key) !is Boolean }) {
        recoveryFailure("Skill 恢复日志 registry 快照无效")
    }
    if (!registry.has("source") || registry.opt("source") !is String) {
        recoveryFailure("Skill 恢复日志 registry source 无效")
    }
    if (!registry.has("installState") || registry.opt("installState") !is String) {
        recoveryFailure("Skill 恢复日志 registry installState 无效")
    }
    return SkillRegistryRecoverySnapshot(
        skillId = skillId,
        entryExisted = registry.getBoolean("entryExisted"),
        enabled = registry.getBoolean("enabled"),
        source = registry.getString("source"),
        installState = registry.getString("installState"),
    )
}

private fun parseDependencySnapshot(
    entry: JSONObject,
    skillId: String,
): SkillDependencyRecoverySnapshot {
    val dependencies = entry.optJSONObject("dependencies")
        ?: recoveryFailure("Skill 恢复日志缺少依赖快照")
    if (!dependencies.has("ownerExisted") || dependencies.opt("ownerExisted") !is Boolean) {
        recoveryFailure("Skill 恢复日志依赖 owner 快照无效")
    }
    val ids = dependencies.optJSONArray("dependencyIds")
        ?: recoveryFailure("Skill 恢复日志依赖边无效")
    val dependencyIds = (0 until ids.length()).map { index -> ids.optString(index) }
    if (
        dependencyIds.distinct().size != dependencyIds.size ||
        dependencyIds.any { !SKILL_ID_REGEX.matches(it) || it == skillId } ||
        (!dependencies.getBoolean("ownerExisted") && dependencyIds.isNotEmpty())
    ) {
        recoveryFailure("Skill 恢复日志包含非法依赖快照")
    }
    return SkillDependencyRecoverySnapshot(
        ownerId = skillId,
        ownerExisted = dependencies.getBoolean("ownerExisted"),
        dependencyIds = dependencyIds.toSet(),
    )
}

private fun isSafeExistingTarget(skillsRoot: File, target: File): Boolean =
    !Files.isSymbolicLink(target.toPath()) && target.isDirectory && isStrictChild(skillsRoot, target)

private fun isStrictChild(root: File, target: File): Boolean {
    val rootPath = root.canonicalFile.toPath()
    val targetPath = runCatching { target.canonicalFile.toPath() }.getOrNull() ?: return false
    return targetPath.startsWith(rootPath) && targetPath != rootPath
}

internal fun skillInstallerWorkRoot(skillsRoot: File): File = File(
    requireNotNull(skillsRoot.canonicalFile.parentFile) { "Skills 目录必须有父目录" },
    ".eta-skill-installer",
)

private fun recoveryFailure(message: String): Nothing =
    throw SkillRecoveryRequiredException(message)

private const val JOURNAL_VERSION = 1
private const val DEPENDENCY_JOURNAL_VERSION = 2
internal const val JOURNAL_FILE_NAME = "pending-install.json"
private const val BACKUP_DIRECTORY_NAME = "backup"
private const val RECOVERY_DIRECTORY_NAME = "recovery"
private const val OPERATION_PREFIX = "operation-"
private const val MAX_JOURNAL_BYTES = 256L * 1024L
private const val MAX_JOURNAL_SKILLS = 2_048
private const val INSTALL_STATE_INSTALLED_VALUE = "installed"
private const val INSTALL_STATE_REMOVED_BUILTIN_VALUE = "removed_builtin"
private val VALID_REGISTRY_SOURCES = setOf(USER_SKILL_SOURCE, BUILTIN_SKILL_SOURCE)
private val VALID_INSTALL_STATES = setOf(
    INSTALL_STATE_INSTALLED_VALUE,
    INSTALL_STATE_REMOVED_BUILTIN_VALUE,
)
private val OPERATION_NAME_REGEX = Regex(
    "^(?:dependency-)?operation-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
)
private val SKILL_ID_REGEX = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
