package io.github.mangi.eta.agent.skill

import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException

data class SkillPackageExport(
    val skillId: String,
    val displayName: String,
    val fileCount: Int,
    val sizeBytes: Long,
    val sha256: String,
)

/** Exports one installed Skill tree as a bounded package accepted by the local installer. */
internal class SkillPackageExportService(context: Context) {
    private val appContext = context.applicationContext
    private val index = SkillRuntime.createIndexService(appContext)
    private val skillsRoot = SkillRuntime.skillsRoot(appContext)

    fun export(
        skillId: String,
        destination: File,
        isCancelled: () -> Boolean = { false },
    ): SkillPackageExport {
        val entry = index.listSkillsForManagement(forceRefresh = true)
            .firstOrNull { it.id == skillId && it.installed }
            ?: throw IllegalArgumentException("未找到已安装的 Skill：$skillId")
        val managedRoot = skillsRoot.canonicalFile
        val sourceRoot = File(entry.rootPath).canonicalFile
        require(sourceRoot.toPath().startsWith(managedRoot.toPath()) && sourceRoot != managedRoot) {
            "Skill 目录不在受管理范围内"
        }
        return SkillPackageArchive.export(
            skillId = entry.id,
            displayName = entry.name,
            sourceRoot = sourceRoot,
            destination = destination,
            isCancelled = isCancelled,
        )
    }
}

internal object SkillPackageArchive {
    fun export(
        skillId: String,
        displayName: String,
        sourceRoot: File,
        destination: File,
        isCancelled: () -> Boolean = { false },
    ): SkillPackageExport {
        require(SKILL_ID.matches(skillId)) { "Skill id 无效" }
        require(sourceRoot.isDirectory) { "Skill 目录不存在" }
        require(!Files.isSymbolicLink(sourceRoot.toPath())) { "Skill 目录不能是符号链接" }
        val sourcePath = sourceRoot.canonicalFile.toPath()
        val parent = requireNotNull(destination.absoluteFile.parentFile) { "导出目录无效" }
        check(parent.mkdirs() || parent.isDirectory) { "无法创建导出目录" }
        val canonicalParent = parent.canonicalFile.toPath()
        require(!canonicalParent.startsWith(sourcePath)) { "不能把 Skill 导出到自身目录" }
        val temporary = File.createTempFile("eta-skill-export-", ".zip", parent)
        var fileCount = 0
        var totalBytes = 0L
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                Files.walk(sourcePath).use { paths ->
                    paths.filter { it != sourcePath }
                        .sorted()
                        .forEach { path ->
                            if (isCancelled()) throw CancellationException("Skill 导出已取消")
                            require(!Files.isSymbolicLink(path)) { "Skill 包含不允许的符号链接" }
                            val relative = sourcePath.relativize(path)
                            require(relative.nameCount <= MAX_DEPTH) { "Skill 目录层级过深" }
                            val zipName = skillId + "/" + relative.joinToString("/") { it.toString() }
                            when {
                                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> {
                                    zip.putNextEntry(ZipEntry(zipName.trimEnd('/') + "/"))
                                    zip.closeEntry()
                                }
                                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> {
                                    require(path.fileName.toString() != SKILL_FILE_NAME || relative.nameCount == 1) {
                                        "Skill 内不能包含嵌套的其他 Skill"
                                    }
                                    fileCount += 1
                                    require(fileCount <= MAX_FILES) { "Skill 文件过多，无法导出" }
                                    val size = Files.size(path)
                                    require(size <= MAX_SINGLE_FILE_BYTES) { "Skill 单文件过大，无法导出" }
                                    totalBytes += size
                                    require(totalBytes <= MAX_TOTAL_BYTES) { "Skill 内容过大，无法导出" }
                                    zip.putNextEntry(ZipEntry(zipName))
                                    path.toFile().inputStream().buffered().use { input ->
                                        copyBounded(input, zip, isCancelled)
                                    }
                                    zip.closeEntry()
                                }
                                else -> error("Skill 包含特殊文件，无法导出")
                            }
                        }
                }
            }
            require(fileCount > 0) { "Skill 目录为空，无法导出" }
            require(File(sourceRoot, SKILL_FILE_NAME).isFile) { "Skill 缺少 SKILL.md" }
            moveIntoPlace(temporary, destination)
            return SkillPackageExport(
                skillId = skillId,
                displayName = displayName.take(120),
                fileCount = fileCount,
                sizeBytes = destination.length(),
                sha256 = sha256(destination),
            )
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
    }

    private fun copyBounded(
        input: InputStream,
        output: ZipOutputStream,
        isCancelled: () -> Boolean,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            if (isCancelled()) throw CancellationException("Skill 导出已取消")
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }

    private fun moveIntoPlace(source: File, destination: File) {
        runCatching {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private const val SKILL_FILE_NAME = "SKILL.md"
    private const val MAX_FILES = 2_048
    private const val MAX_DEPTH = 16
    private const val MAX_SINGLE_FILE_BYTES = 32L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 128L * 1024L * 1024L
    private val SKILL_ID = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
}
