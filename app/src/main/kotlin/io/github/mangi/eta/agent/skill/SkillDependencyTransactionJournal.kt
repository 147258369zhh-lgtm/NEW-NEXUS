package io.github.mangi.eta.agent.skill

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parent transaction for a market install that may commit several dependency
 * transactions before the root Skill. It is a rollback-ahead journal: an
 * incomplete root always restores every dependency and ownership snapshot.
 */
internal class SkillDependencyTransactionJournal private constructor(
    private val skillsRoot: File,
    val operationDirectory: File,
    private val state: State,
) {
    private var current = state

    val ownerSnapshots: List<SkillDependencyRecoverySnapshot>
        get() = current.ownerSnapshots

    val committedDependencyIds: Set<String>
        get() = current.committedDependencyIds

    internal fun replaceOwnerSnapshotFromParent(snapshots: List<SkillDependencyRecoverySnapshot>) {
        replaceOwnerSnapshot(snapshots)
    }

    @Synchronized
    fun markRootCommitted() {
        check(!current.rootCommitted) { "Skill 依赖事务已经提交根 Skill" }
        current = current.copy(rootCommitted = true)
        writeAtomically(operationDirectory, current)
    }

    /** Records that one child install became durable before its own recovery journal is removed. */
    @Synchronized
    fun markDependencyCommitted(skillId: String) {
        check(!current.rootCommitted) { "根 Skill 已提交，不能再登记依赖" }
        check(current.installedDependencies.any { it.skillId == skillId }) {
            "依赖事务中没有计划安装的 Skill：$skillId"
        }
        if (skillId in current.committedDependencyIds) return
        current = current.copy(committedDependencyIds = current.committedDependencyIds + skillId)
        writeAtomically(operationDirectory, current)
    }

    /** Adds a dependency to the durable plan before an enclosing resolver begins its install loop. */
    @Synchronized
    fun addPlannedDependency(dependency: InstalledDependency) {
        check(!current.rootCommitted) { "根 Skill 已提交，不能再追加依赖" }
        require(dependency.skillId != current.rootId) { "根 Skill 不能作为自身依赖" }
        require(dependency.registrySnapshot.skillId == dependency.skillId) { "依赖 registry id 不一致" }
        require(!dependency.registrySnapshot.entryExisted) { "依赖事务不能包含已存在的依赖" }
        if (current.installedDependencies.any { it.skillId == dependency.skillId }) return
        val updated = current.copy(installedDependencies = current.installedDependencies + dependency)
        validateState(updated, skillsRoot)
        current = updated
        writeAtomically(operationDirectory, current)
    }

    @Synchronized
    fun replaceOwnerSnapshot(snapshots: List<SkillDependencyRecoverySnapshot>) {
        if (current.rootCommitted) return
        val existingIds = current.ownerSnapshots.mapTo(mutableSetOf()) { it.ownerId }
        val merged = current.ownerSnapshots + snapshots.filter { it.ownerId !in existingIds }
        current = current.copy(ownerSnapshots = merged)
        writeAtomically(operationDirectory, current)
    }

    @Synchronized
    fun clear() {
        Files.deleteIfExists(File(operationDirectory, JOURNAL_FILE_NAME).toPath())
        deleteSkillPathWithoutFollowingLinks(
            skillInstallerWorkRoot(skillsRoot),
            operationDirectory,
        )
    }

    @Synchronized
    fun complete() {
        check(current.rootCommitted) { "根 Skill 尚未提交，不能完成依赖事务" }
        Files.deleteIfExists(File(operationDirectory, JOURNAL_FILE_NAME).toPath())
        deleteSkillPathWithoutFollowingLinks(
            skillInstallerWorkRoot(skillsRoot),
            operationDirectory,
        )
    }

    /** Allows a committed parent to clear its own journal without touching dependency files. */
    @Synchronized
    fun completeAfterRootCommitted() {
        check(current.rootCommitted) { "根 Skill 尚未提交，不能完成依赖事务" }
        clear()
    }

    internal data class State(
        val ownerSnapshots: List<SkillDependencyRecoverySnapshot>,
        val installedDependencies: List<InstalledDependency>,
        val rootId: String,
        val rootCommitted: Boolean = false,
        val committedDependencyIds: Set<String> = emptySet(),
    )

    internal data class InstalledDependency(
        val skillId: String,
        val registrySnapshot: SkillRegistryRecoverySnapshot,
    )

    companion object {
        fun begin(
            skillsRoot: File,
            ownerSnapshots: List<SkillDependencyRecoverySnapshot>,
            installedDependencies: List<InstalledDependency>,
            rootId: String = "",
            transactionId: String = UUID.randomUUID().toString(),
        ): SkillDependencyTransactionJournal {
            val state = State(
                ownerSnapshots = ownerSnapshots.distinctBy { it.ownerId },
                installedDependencies = installedDependencies.distinctBy { it.skillId },
                rootId = rootId,
                committedDependencyIds = emptySet(),
            )
            validateState(state, skillsRoot)
            val workRoot = prepareSkillInstallerWorkRoot(skillsRoot).canonicalFile
            require(TRANSACTION_ID.matches(transactionId)) { "Skill 依赖事务 id 无效" }
            val operation = File(workRoot, "dependency-operation-$transactionId").canonicalFile
            if (!operation.mkdir()) {
                throw IOException("无法创建 Skill 依赖事务")
            }
            try {
                writeAtomically(operation, state)
            } catch (failure: Throwable) {
                deleteSkillPathWithoutFollowingLinks(
                    skillInstallerWorkRoot(skillsRoot),
                    operation,
                )
                throw failure
            }
            return SkillDependencyTransactionJournal(
                skillsRoot,
                operation,
                state,
            )
        }

        /** Reattaches to a planned parent journal before the first child dependency commits. */
        fun attach(
            skillsRoot: File,
            operationDirectory: File,
        ): SkillDependencyTransactionJournal {
            val journal = File(operationDirectory, JOURNAL_FILE_NAME)
            if (Files.isSymbolicLink(journal.toPath()) || !journal.isFile) {
                throw SkillRecoveryRequiredException("Skill 依赖事务日志不是普通文件")
            }
            if (journal.length() !in 1..MAX_JOURNAL_BYTES) {
                throw SkillRecoveryRequiredException("Skill 依赖事务日志大小无效")
            }
            val value = runCatching { JSONObject(journal.readText(Charsets.UTF_8)) }
                .getOrElse { throw SkillRecoveryRequiredException("Skill 依赖事务日志格式无效") }
            val state = parse(value, skillsRoot)
            return SkillDependencyTransactionJournal(skillsRoot, operationDirectory, state)
        }

        internal fun recover(
            skillsRoot: File,
            operation: File,
            journal: File,
        ): RecoveredSkillOperation? {
            if (Files.isSymbolicLink(journal.toPath()) || !journal.isFile) {
                throw SkillRecoveryRequiredException("Skill 依赖事务日志不是普通文件")
            }
            if (journal.length() !in 1..MAX_JOURNAL_BYTES) {
                throw SkillRecoveryRequiredException("Skill 依赖事务日志大小无效")
            }
            val value = runCatching { JSONObject(journal.readText(Charsets.UTF_8)) }
                .getOrElse {
                    throw SkillRecoveryRequiredException("Skill 依赖事务日志格式无效")
                }
            val state = parse(value, skillsRoot)
            if (state.rootCommitted) {
                return RecoveredSkillOperation(
                    operationDirectory = operation,
                    records = emptyList(),
                    journalFileName = JOURNAL_FILE_NAME,
                    dependencyOwnerSnapshots = emptyList(),
                    committedRootIds = if (state.rootId.isBlank()) emptySet() else setOf(state.rootId),
                    isParentDependencyTransaction = true,
                )
            }
            val committedDependencies = state.installedDependencies.filter { dependency ->
                dependency.skillId in state.committedDependencyIds
            }
            committedDependencies.forEach { dependency ->
                val target = File(skillsRoot, dependency.skillId)
                if (!deleteSkillPathWithoutFollowingLinks(skillsRoot, target)) {
                    throw SkillRecoveryRequiredException(
                        "无法移除未完成根 Skill 的依赖：${dependency.skillId}",
                    )
                }
            }
            return RecoveredSkillOperation(
                operationDirectory = operation,
                records = committedDependencies.map { dependency ->
                    SkillRecoveryRecord(
                        id = dependency.skillId,
                        originalTargetExisted = false,
                        registrySnapshot = dependency.registrySnapshot,
                    )
                },
                journalFileName = JOURNAL_FILE_NAME,
                dependencyOwnerSnapshots = state.ownerSnapshots,
                isParentDependencyTransaction = true,
            )
        }

        private fun parse(value: JSONObject, skillsRoot: File): State {
            if (value.optInt("version", -1) != VERSION) {
                throw SkillRecoveryRequiredException("Skill 依赖事务版本不受支持")
            }
            val rootId = value.optString("rootId")
            if (rootId.isNotEmpty() && !SKILL_ID.matches(rootId)) {
                throw SkillRecoveryRequiredException("Skill 依赖事务根 id 无效")
            }
            val ownersArray = value.optJSONArray("owners")
                ?: throw SkillRecoveryRequiredException("Skill 依赖事务缺少 owner 快照")
            val ownerSnapshots = (0 until ownersArray.length()).map { index ->
                parseOwner(ownersArray.optJSONObject(index))
            }
            val dependenciesArray = value.optJSONArray("installedDependencies")
                ?: throw SkillRecoveryRequiredException("Skill 依赖事务缺少依赖清单")
            val dependencies = (0 until dependenciesArray.length()).map { index ->
                val entry = dependenciesArray.optJSONObject(index)
                    ?: throw SkillRecoveryRequiredException("Skill 依赖事务清单无效")
                val id = entry.optString("id")
                val registry = parseRegistry(entry.optJSONObject("registry"), id)
                InstalledDependency(id, registry)
            }
            val state = State(
                ownerSnapshots = ownerSnapshots,
                installedDependencies = dependencies,
                rootId = rootId,
                rootCommitted = value.optBoolean("rootCommitted", false),
                committedDependencyIds = value.optJSONArray("committedDependencies")?.let { array ->
                    (0 until array.length()).map { index -> array.optString(index) }.toSet()
                }.orEmpty(),
            )
            validateState(state, skillsRoot)
            return state
        }

        internal fun committedRootId(
            skillsRoot: File,
            operation: File,
            journal: File,
        ): String? {
            if (Files.isSymbolicLink(journal.toPath()) || !journal.isFile) {
                throw SkillRecoveryRequiredException("Skill 依赖事务日志不是普通文件")
            }
            if (journal.length() !in 1..MAX_JOURNAL_BYTES) {
                throw SkillRecoveryRequiredException("Skill 依赖事务日志大小无效")
            }
            val value = runCatching { JSONObject(journal.readText(Charsets.UTF_8)) }
                .getOrElse {
                    throw SkillRecoveryRequiredException("Skill 依赖事务日志格式无效")
                }
            val state = parse(value, skillsRoot)
            return state.rootId.takeIf { state.rootCommitted }
        }

        internal fun committedDependencyIds(
            skillsRoot: File,
            journal: File,
        ): Set<String> {
            if (Files.isSymbolicLink(journal.toPath()) || !journal.isFile) {
                throw SkillRecoveryRequiredException("Skill 依赖事务日志不是普通文件")
            }
            if (journal.length() !in 1..MAX_JOURNAL_BYTES) {
                throw SkillRecoveryRequiredException("Skill 依赖事务日志大小无效")
            }
            val value = runCatching { JSONObject(journal.readText(Charsets.UTF_8)) }
                .getOrElse { throw SkillRecoveryRequiredException("Skill 依赖事务日志格式无效") }
            return parse(value, skillsRoot).committedDependencyIds
        }

        private fun parseOwner(entry: JSONObject?): SkillDependencyRecoverySnapshot {
            if (entry == null) {
                throw SkillRecoveryRequiredException("Skill 依赖事务 owner 快照无效")
            }
            val ownerId = entry.optString("ownerId")
            if (!entry.has("ownerExisted") || entry.opt("ownerExisted") !is Boolean) {
                throw SkillRecoveryRequiredException("Skill 依赖事务 owner 状态无效")
            }
            val array = entry.optJSONArray("dependencyIds")
                ?: throw SkillRecoveryRequiredException("Skill 依赖事务 owner 边无效")
            val dependencyIds = (0 until array.length()).map { index -> array.optString(index) }
            if (
                !SKILL_ID.matches(ownerId) ||
                dependencyIds.any { !SKILL_ID.matches(it) || it == ownerId } ||
                dependencyIds.distinct().size != dependencyIds.size ||
                (!entry.getBoolean("ownerExisted") && dependencyIds.isNotEmpty())
            ) {
                throw SkillRecoveryRequiredException("Skill 依赖事务 owner 快照内容无效")
            }
            return SkillDependencyRecoverySnapshot(
                ownerId = ownerId,
                ownerExisted = entry.getBoolean("ownerExisted"),
                dependencyIds = dependencyIds.toSet(),
            )
        }

        private fun parseRegistry(
            entry: JSONObject?,
            skillId: String,
        ): SkillRegistryRecoverySnapshot {
            if (entry == null || !entry.has("entryExisted") || entry.opt("entryExisted") !is Boolean) {
                throw SkillRecoveryRequiredException("Skill 依赖事务 registry 快照无效")
            }
            val entryExisted = entry.getBoolean("entryExisted")
            if (!entryExisted) return SkillRegistryRecoverySnapshot(skillId, false)
            val source = entry.optString("source")
            val installState = entry.optString("installState")
            return SkillRegistryRecoverySnapshot(
                skillId = skillId,
                entryExisted = true,
                enabled = entry.getBoolean("enabled"),
                source = source,
                installState = installState,
            )
        }

        private fun validateState(state: State, skillsRoot: File) {
            if (state.rootId.isNotEmpty() && !SKILL_ID.matches(state.rootId)) {
                throw SkillRecoveryRequiredException("Skill 依赖事务根 id 无效")
            }
            val ids = state.installedDependencies.map { it.skillId }
            if (ids.distinct().size != ids.size || ids.any { !SKILL_ID.matches(it) || it == state.rootId }) {
                throw SkillRecoveryRequiredException("Skill 依赖事务清单包含非法 id")
            }
            val owners = state.ownerSnapshots
            if (
                owners.map { it.ownerId }.distinct().size != owners.size ||
                owners.any { snapshot ->
                    !SKILL_ID.matches(snapshot.ownerId) ||
                        snapshot.dependencyIds.any { !SKILL_ID.matches(it) || it == snapshot.ownerId } ||
                        (!snapshot.ownerExisted && snapshot.dependencyIds.isNotEmpty())
                }
            ) {
                throw SkillRecoveryRequiredException("Skill 依赖事务 owner 快照包含非法内容")
            }
            state.installedDependencies.forEach { dependency ->
                val snapshot = dependency.registrySnapshot
                if (snapshot.skillId != dependency.skillId || snapshot.entryExisted) {
                    throw SkillRecoveryRequiredException("Skill 依赖事务包含已存在的依赖")
                }
            }
            if (state.committedDependencyIds.any { id -> ids.none { it == id } }) {
                throw SkillRecoveryRequiredException("Skill 依赖事务提交清单包含未知依赖")
            }
        }

        private fun writeAtomically(operation: File, state: State) {
            val value = JSONObject()
                .put("version", VERSION)
                .put("rootId", state.rootId)
                .put("rootCommitted", state.rootCommitted)
                .put("committedDependencies", JSONArray(state.committedDependencyIds.sorted()))
                .put(
                    "owners",
                    JSONArray().apply {
                        state.ownerSnapshots.sortedBy { it.ownerId }.forEach { snapshot ->
                            put(
                                JSONObject()
                                    .put("ownerId", snapshot.ownerId)
                                    .put("ownerExisted", snapshot.ownerExisted)
                                    .put("dependencyIds", JSONArray(snapshot.dependencyIds.sorted())),
                            )
                        }
                    },
                )
                .put(
                    "installedDependencies",
                    JSONArray().apply {
                        state.installedDependencies.forEach { dependency ->
                            val registry = dependency.registrySnapshot
                            put(
                                JSONObject()
                                    .put("id", dependency.skillId)
                                    .put(
                                        "registry",
                                        JSONObject()
                                            .put("entryExisted", registry.entryExisted)
                                            .put("enabled", registry.enabled)
                                            .put("source", registry.source)
                                            .put("installState", registry.installState),
                                    ),
                            )
                        }
                    },
                )
            val journal = File(operation, JOURNAL_FILE_NAME)
            val temporary = File(operation, ".${JOURNAL_FILE_NAME}-${UUID.randomUUID()}")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(value.toString().toByteArray(Charsets.UTF_8))
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

        private const val VERSION = 1
        private const val MAX_JOURNAL_BYTES = 256L * 1024L
        private val SKILL_ID = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        private val TRANSACTION_ID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )

        internal fun transactionIdForDependency(skillId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(("nexus-skill-dependency:" + skillId).toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) +
                "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32)
        }
    }
}
