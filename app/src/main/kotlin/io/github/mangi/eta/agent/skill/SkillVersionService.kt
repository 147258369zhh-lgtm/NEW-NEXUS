package io.github.mangi.eta.agent.skill

import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class SkillVersionBackup(
    val id: String,
    val skillId: String,
    val displayName: String,
    val sourceId: String,
    val sourceName: String,
    val sourceType: String,
    val repository: String? = null,
    val ref: String? = null,
    val path: String? = null,
    val version: String? = null,
    val originalInstalledAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val archiveSha256: String,
    val sizeBytes: Long,
)

sealed interface SkillVersionRestoreOutcome {
    data class Success(val backup: SkillVersionBackup) : SkillVersionRestoreOutcome

    data class Failure(
        val message: String,
        val recoveryRequired: Boolean = false,
    ) : SkillVersionRestoreOutcome
}

internal class SkillVersionStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "capability-center/skill-versions")
    private val indexFile = File(appContext.filesDir, "capability-center/skill-versions.json")

    @Synchronized
    fun readAll(): List<SkillVersionBackup> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(indexFile.readText(Charsets.UTF_8))
            require(root.optInt("version") == VERSION)
            val values = root.optJSONArray("backups") ?: JSONArray()
            buildList {
                for (index in 0 until values.length()) {
                    values.optJSONObject(index)?.let(::parse)?.let(::add)
                }
            }.distinctBy(SkillVersionBackup::id)
                .sortedByDescending(SkillVersionBackup::createdAt)
                .take(MAX_BACKUPS)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun record(backup: SkillVersionBackup) {
        val current = readAll().filterNot { it.id == backup.id }
        write((current + backup).sortedByDescending(SkillVersionBackup::createdAt).take(MAX_BACKUPS))
    }

    @Synchronized
    fun delete(backupId: String) {
        val current = readAll()
        val target = current.firstOrNull { it.id == backupId } ?: return
        write(current.filterNot { it.id == backupId })
        runCatching { archiveFile(target).delete() }
    }

    @Synchronized
    fun removeAll(skillId: String) {
        val current = readAll()
        val removed = current.filter { it.skillId == skillId }
        if (removed.isEmpty()) return
        write(current.filterNot { it.skillId == skillId })
        removed.forEach { runCatching { archiveFile(it).delete() } }
    }

    fun archiveFile(backup: SkillVersionBackup): File = File(directory, backup.id + ".zip")

    fun newArchiveFile(): File {
        prepareDirectory()
        return File.createTempFile("eta-skill-version-", ".zip", directory)
    }

    fun publishArchive(temporary: File, backupId: String): File {
        prepareDirectory()
        val target = File(directory, backupId + ".zip")
        runCatching {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    private fun write(backups: List<SkillVersionBackup>) {
        val parent = requireNotNull(indexFile.parentFile) { "Skill 版本记录目录无效" }
        check(parent.mkdirs() || parent.isDirectory) { "无法创建 Skill 版本记录目录" }
        val root = JSONObject()
            .put("version", VERSION)
            .put("backups", JSONArray().also { values ->
                backups.forEach { values.put(encode(it)) }
            })
        val temporary = File.createTempFile("eta-skill-versions-", ".tmp", parent)
        try {
            temporary.writeText(root.toString(), Charsets.UTF_8)
            runCatching {
                Files.move(
                    temporary.toPath(),
                    indexFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun encode(value: SkillVersionBackup): JSONObject = JSONObject()
        .put("id", value.id)
        .put("skillId", value.skillId)
        .put("displayName", value.displayName)
        .put("sourceId", value.sourceId)
        .put("sourceName", value.sourceName)
        .put("sourceType", value.sourceType)
        .put("repository", value.repository ?: JSONObject.NULL)
        .put("ref", value.ref ?: JSONObject.NULL)
        .put("path", value.path ?: JSONObject.NULL)
        .put("version", value.version ?: JSONObject.NULL)
        .put("originalInstalledAt", value.originalInstalledAt)
        .put("createdAt", value.createdAt)
        .put("archiveSha256", value.archiveSha256)
        .put("sizeBytes", value.sizeBytes)

    private fun parse(value: JSONObject): SkillVersionBackup? = runCatching {
        val id = value.getString("id")
        val skillId = value.getString("skillId")
        require(BACKUP_ID.matches(id) && SKILL_ID.matches(skillId))
        val sha = value.getString("archiveSha256").lowercase()
        require(SHA256.matches(sha))
        SkillVersionBackup(
            id = id,
            skillId = skillId,
            displayName = value.optString("displayName").trim().take(120),
            sourceId = value.optString("sourceId").take(128),
            sourceName = value.optString("sourceName").take(160),
            sourceType = value.optString("sourceType").take(40),
            repository = value.optionalString("repository"),
            ref = value.optionalString("ref"),
            path = value.optionalString("path"),
            version = value.optionalString("version")?.take(64),
            originalInstalledAt = value.optLong("originalInstalledAt", 0L).coerceAtLeast(0L),
            createdAt = value.optLong("createdAt", 0L).coerceAtLeast(0L),
            archiveSha256 = sha,
            sizeBytes = value.optLong("sizeBytes", 0L).coerceAtLeast(0L),
        ).takeIf { it.displayName.isNotBlank() }
    }.getOrNull()

    private fun JSONObject.optionalString(key: String): String? =
        optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun prepareDirectory() {
        check(directory.mkdirs() || directory.isDirectory) { "无法创建 Skill 版本归档目录" }
    }

    private companion object {
        const val VERSION = 1
        const val MAX_BACKUPS = 96
        val BACKUP_ID = Regex("version-[a-f0-9-]{36}")
        val SKILL_ID = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        val SHA256 = Regex("[a-f0-9]{64}")
    }
}

internal class SkillVersionService(context: Context) {
    private val appContext = context.applicationContext
    private val index = SkillRuntime.createIndexService(appContext)
    private val installer = SkillRuntime.createPackageInstaller(appContext)
    private val provenanceStore = SkillRuntime.createProvenanceStore(appContext)
    private val store = SkillVersionStore(appContext)

    fun listAll(): List<SkillVersionBackup> = store.readAll()

    fun listForSkill(skillId: String): List<SkillVersionBackup> =
        store.readAll().filter { it.skillId == skillId }

    fun backupCurrent(skillId: String): SkillVersionBackup {
        val entry = index.listSkillsForManagement(forceRefresh = true)
            .firstOrNull { it.id == skillId && it.installed }
            ?: throw IllegalArgumentException("未找到可备份的 Skill：$skillId")
        require(entry.source == "user") { "只能备份用户安装的 Skill" }
        val skillsRoot = SkillRuntime.skillsRoot(appContext).canonicalFile
        val skillRoot = File(entry.rootPath).canonicalFile
        require(skillRoot.toPath().startsWith(skillsRoot.toPath()) && skillRoot != skillsRoot) {
            "Skill 目录不在受管理范围内"
        }
        require(!Files.isSymbolicLink(skillRoot.toPath())) { "Skill 目录不能是符号链接" }

        val backupId = "version-" + UUID.randomUUID().toString().lowercase()
        val temporary = store.newArchiveFile()
        try {
            val size = writeArchive(skillRoot, temporary)
            val sha = sha256(temporary)
            store.publishArchive(temporary, backupId)
            val provenance = entry.provenance ?: SkillProvenance(
                skillId = entry.id,
                sourceId = "local",
                sourceName = "本地安装",
                sourceType = "local",
                installedAt = 0L,
            )
            val backup = SkillVersionBackup(
                id = backupId,
                skillId = entry.id,
                displayName = entry.name.take(120),
                sourceId = provenance.sourceId.take(128),
                sourceName = provenance.sourceName.take(160),
                sourceType = provenance.sourceType.take(40),
                repository = provenance.repository,
                ref = provenance.ref,
                path = provenance.path,
                version = provenance.version,
                originalInstalledAt = provenance.installedAt,
                archiveSha256 = sha,
                sizeBytes = size,
            )
            runCatching { store.record(backup) }.getOrElse {
                runCatching { store.archiveFile(backup).delete() }
                throw it
            }
            pruneOldVersions(skillId)
            return backup
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
    }

    fun restore(backupId: String): SkillVersionRestoreOutcome {
        val backup = store.readAll().firstOrNull { it.id == backupId }
            ?: return SkillVersionRestoreOutcome.Failure("版本记录不存在")
        val archive = store.archiveFile(backup)
        if (!archive.isFile) return SkillVersionRestoreOutcome.Failure("版本归档已丢失")
        if (archive.length() > MAX_ARCHIVE_FILE_BYTES) {
            return SkillVersionRestoreOutcome.Failure("版本归档超过恢复大小限制")
        }
        // Read the verified archive before the pre-restore backup can prune the oldest version.
        val archiveBytes = runCatching { archive.readBytes() }.getOrElse {
            return SkillVersionRestoreOutcome.Failure("无法读取版本归档")
        }
        val actualSha = sha256(archiveBytes)
        if (actualSha != backup.archiveSha256) {
            return SkillVersionRestoreOutcome.Failure("版本归档校验失败，已拒绝恢复")
        }
        runCatching { backupCurrent(backup.skillId) }.getOrElse {
            return SkillVersionRestoreOutcome.Failure(
                "无法备份当前版本，已取消恢复：" + (it.message ?: "未知错误"),
                recoveryRequired = false,
            )
        }
        val result = runCatching {
            installer.installLocalZip(
                openStream = { archiveBytes.inputStream() },
                replaceUserSkill = true,
                expectedReplacementId = backup.skillId,
                expectedArchiveSha256 = actualSha,
            )
        }.getOrElse {
            return SkillVersionRestoreOutcome.Failure(it.message ?: "版本恢复失败")
        }
        return when (result) {
            is SkillInstallResult.Success -> {
                runCatching {
                    provenanceStore.record(
                        SkillProvenance(
                            skillId = backup.skillId,
                            sourceId = backup.sourceId,
                            sourceName = backup.sourceName,
                            sourceType = backup.sourceType,
                            repository = backup.repository,
                            ref = backup.ref,
                            path = backup.path,
                            version = backup.version,
                            installedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                index.listSkillsForManagement(forceRefresh = true)
                SkillVersionRestoreOutcome.Success(backup)
            }
            is SkillInstallResult.Conflict -> SkillVersionRestoreOutcome.Failure("当前 Skill 已不可替换")
            is SkillInstallResult.Failure -> SkillVersionRestoreOutcome.Failure(
                result.error.message,
                result.recoveryRequired,
            )
        }
    }

    fun delete(backupId: String) {
        store.delete(backupId)
    }

    fun removeAll(skillId: String) {
        store.removeAll(skillId)
    }

    private fun pruneOldVersions(skillId: String) {
        store.readAll()
            .filter { it.skillId == skillId }
            .sortedByDescending(SkillVersionBackup::createdAt)
            .drop(MAX_VERSIONS_PER_SKILL)
            .forEach { store.delete(it.id) }
    }

    private fun writeArchive(root: File, outputFile: File): Long {
        var fileCount = 0
        var totalBytes = 0L
        ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
            Files.walk(root.toPath()).use { paths ->
                paths.filter { path -> path != root.toPath() }
                    .sorted()
                    .forEach { path ->
                        if (Files.isSymbolicLink(path)) {
                            throw IllegalStateException("Skill 包含不允许的符号链接")
                        }
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
                        fileCount += 1
                        if (fileCount > MAX_FILES) throw IllegalStateException("Skill 文件过多，无法备份")
                        val size = Files.size(path)
                        if (size > MAX_SINGLE_FILE_BYTES) throw IllegalStateException("Skill 单文件过大，无法备份")
                        totalBytes += size
                        if (totalBytes > MAX_ARCHIVE_INPUT_BYTES) throw IllegalStateException("Skill 内容过大，无法备份")
                        val relative = root.toPath().relativize(path).toString().replace('\\', '/')
                        zip.putNextEntry(ZipEntry(relative))
                        path.toFile().inputStream().buffered().use { input -> copyBounded(input, zip) }
                        zip.closeEntry()
                    }
            }
        }
        if (fileCount == 0) throw IllegalStateException("Skill 目录为空，无法备份")
        if (outputFile.length() == 0L) throw IllegalStateException("Skill 备份归档为空")
        return outputFile.length()
    }

    private fun copyBounded(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }

    private fun sha256(file: File): String {
        return file.inputStream().buffered().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().toHex()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private companion object {
        const val MAX_VERSIONS_PER_SKILL = 3
        const val MAX_FILES = 2_048
        const val MAX_ARCHIVE_FILE_BYTES = 160L * 1024L * 1024L
        const val MAX_SINGLE_FILE_BYTES = 32L * 1024L * 1024L
        const val MAX_ARCHIVE_INPUT_BYTES = 128L * 1024L * 1024L
    }
}
