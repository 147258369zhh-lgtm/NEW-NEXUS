package io.github.mangi.eta.data.repository

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
internal data class EtaBackupLinkManifest(val path: String, val target: String)

@Serializable
internal data class EtaBackupDirectoryManifest(val path: String, val mode: Int? = null)

/** Never resolve or descend through a symlink: Linux rootfs trees contain both cycles and guest-absolute links. */
internal object EtaBackupFileTree {
    suspend fun walk(
        dataDir: File,
        roots: List<String>,
        excluded: Set<String>,
        maxEntries: Int = 200_000,
        visit: suspend (File, BasicFileAttributes) -> Unit,
    ) {
        var count = 0
        suspend fun visitPath(path: Path, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (depth > 256 || ++count > maxEntries) throw EtaBackupException("备份文件过多或目录层级过深，请清理后重试")
            val relative = dataDir.toPath().relativize(path).toString().replace(File.separatorChar, '/')
            if (excluded.any { relative == it || relative.startsWith("$it/") }) return
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isDirectory && !attributes.isRegularFile && !attributes.isSymbolicLink) {
                throw EtaBackupException("备份包含不支持的特殊文件：$relative")
            }
            visit(path.toFile(), attributes)
            if (attributes.isDirectory) {
                Files.newDirectoryStream(path).use { children ->
                    for (child in children) visitPath(child, depth + 1)
                }
            }
        }
        for (root in roots) {
            val path = File(dataDir, root).toPath()
            if (Files.exists(path, NOFOLLOW_LINKS)) visitPath(path, 0)
        }
    }

    fun mode(path: Path): Int? = try {
        val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
        PosixFilePermission.entries.fold(0) { bits, permission ->
            if (permission in permissions) bits or (1 shl (8 - permission.ordinal)) else bits
        }
    } catch (_: UnsupportedOperationException) { null }

    fun setMode(path: Path, mode: Int?) {
        if (mode == null) return
        require(mode in 0..511) { "备份文件权限无效" }
        Files.setPosixFilePermissions(path, PosixFilePermission.entries.filterTo(mutableSetOf()) {
            mode and (1 shl (8 - it.ordinal)) != 0
        })
    }

    /** Kotlin File.deleteRecursively follows directory links; restore cleanup must not. */
    fun delete(directory: File) {
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) return
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
