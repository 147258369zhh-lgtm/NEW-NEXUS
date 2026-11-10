package io.github.mangi.eta.agent.terminal

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal class LocalFileToolBackend(
    workspace: File,
    home: File = workspace,
    override val environment: String = "android",
    override val identity: String = "user",
    private val isCancelled: () -> Boolean = { false },
) : FileToolBackend {
    private val workspace = workspace.canonicalFile
    private val home = home.canonicalFile

    override fun resolve(path: String): String {
        checkActive()
        if (path.indexOf('\u0000') >= 0) throw FileToolException("INVALID_PATH", "路径包含无效字符")
        val candidate = when {
            path.isEmpty() -> workspace
            path == "~" -> home
            path.startsWith("~/") -> File(home, path.substring(2))
            File(path).isAbsolute -> File(path)
            else -> File(workspace, path)
        }.canonicalFile
        return candidate.path
    }

    override fun stat(path: String): FileToolStat = metadata(File(resolve(path)).toPath())

    override fun readBytes(path: String, offset: Long, limit: Int): ByteArray {
        require(offset >= 0 && limit in 1..MAX_READ_BYTES)
        val file = File(resolve(path))
        if (metadata(file.toPath()).kind != FileToolKind.FILE) {
            throw FileToolException("NOT_REGULAR_FILE", "目标不是普通文件")
        }
        return RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            val bytes = ByteArray(minOf(limit.toLong(), (input.length() - offset).coerceAtLeast(0)).toInt())
            var count = 0
            while (count < bytes.size) {
                checkInterrupted()
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            if (count == bytes.size) bytes else bytes.copyOf(count)
        }
    }

    override fun list(path: String, offset: Int, limit: Int, expectedRevision: String?): FileToolDirectoryPage {
        require(offset >= 0 && limit in 1..MAX_DIRECTORY_PAGE)
        if (offset > MAX_DIRECTORY_POSITION) throw FileToolException("DIRECTORY_LIMIT", "目录枚举位置超过当前限制")
        val directory = File(resolve(path)).toPath()
        val before = metadata(directory)
        if (before.kind != FileToolKind.DIRECTORY) throw FileToolException("NOT_DIRECTORY", "目标不是目录")
        if (expectedRevision != null && before.revision != expectedRevision) {
            throw FileToolException("STALE_CURSOR", "目录已变化，请从头重新列出或搜索")
        }
        val entries = ArrayList<FileToolStat>(limit)
        var position = 0
        var hasMore = false
        Files.newDirectoryStream(directory).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                checkInterrupted()
                val entry = iterator.next()
                if (position < offset) {
                    position++
                    continue
                }
                if (entries.size == limit) {
                    hasMore = true
                    break
                }
                entries += metadata(entry)
                position++
            }
        }
        val after = metadata(directory)
        if (after.revision != before.revision) throw FileToolException("STALE_CURSOR", "枚举期间目录已变化，请重试")
        return FileToolDirectoryPage(entries, position, hasMore, before.revision)
    }

    override fun write(
        path: String,
        bytes: ByteArray,
        append: Boolean,
        expectedRevision: String?,
        expectedSha256: String?,
    ): FileToolWriteResult = synchronized(writeLock) {
        checkActive()
        if (bytes.size > MAX_WRITE_BYTES) throw FileToolException("CONTENT_TOO_LARGE", "单次写入内容超过大小限制")
        val target = File(resolve(path)).toPath()
        val existing = try { metadata(target) } catch (_: NoSuchFileException) { null }
        if (existing != null && existing.kind != FileToolKind.FILE) {
            throw FileToolException("NOT_REGULAR_FILE", "目标不是普通文件")
        }
        verifyExpected(target, existing, expectedRevision, expectedSha256)
        Files.createDirectories(target.parent)
        // 创建父目录后再次解析，防止已有符号链接把写入引向允许根之外。
        if (File(resolve(path)).toPath() != target) throw FileToolException("FILE_CHANGED", "写入目标已变化")
        if (append) {
            checkActive()
            FileOutputStream(target.toFile(), true).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            return@synchronized FileToolWriteResult(metadata(target), atomic = false)
        }
        val temporary = Files.createTempFile(target.parent, ".eta-write-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (existing != null) {
                try {
                    val permissions = try {
                        Files.getPosixFilePermissions(target)
                    } catch (_: UnsupportedOperationException) {
                        null
                    }
                    // 共享存储可能合成固定权限；相同权限无需再发起无意义的 chmod。
                    if (permissions != null && Files.getPosixFilePermissions(temporary) != permissions) {
                        Files.setPosixFilePermissions(temporary, permissions)
                        if (Files.getPosixFilePermissions(temporary) != permissions) {
                            throw FileToolException("FILE_PERMISSION_PRESERVE_FAILED", "文件系统未应用原文件权限，本次未写入")
                        }
                    }
                } catch (_: UnsupportedOperationException) {
                    throw FileToolException("FILE_PERMISSION_PRESERVE_FAILED", "文件系统不支持保留原文件权限，本次未写入")
                } catch (_: IOException) {
                    throw FileToolException("FILE_PERMISSION_PRESERVE_FAILED", "无法保留原文件权限，本次未写入")
                } catch (_: SecurityException) {
                    throw FileToolException("FILE_PERMISSION_PRESERVE_FAILED", "保留原文件权限未获授权，本次未写入")
                }
            }
            val current = try { metadata(target) } catch (_: NoSuchFileException) { null }
            verifyExpected(target, current, expectedRevision, expectedSha256)
            checkActive()
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                throw FileToolException("ATOMIC_WRITE_UNSUPPORTED", "此文件系统不支持原子替换，本次未写入")
            }
            FileToolWriteResult(metadata(target), atomic = true)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun createDirectory(path: String): FileToolStat {
        checkActive()
        val target = File(resolve(path)).toPath()
        try {
            Files.createDirectory(target)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            throw FileToolException("DESTINATION_EXISTS", "目标已存在")
        }
        return metadata(target)
    }

    override fun copy(source: String, destination: String): FileToolStat = synchronized(writeLock) {
        checkActive()
        val from = File(resolve(source)).toPath()
        val to = File(resolve(destination)).toPath()
        if (from == to) throw FileToolException("INVALID_DESTINATION", "源和目标不能相同")
        val sourceStat = metadata(from)
        if (sourceStat.kind == FileToolKind.SYMLINK) {
            throw FileToolException("SYMLINK_UNSUPPORTED", "为避免越过工作区边界，不复制符号链接")
        }
        if (sourceStat.kind == FileToolKind.DIRECTORY && to.startsWith(from)) {
            throw FileToolException("INVALID_DESTINATION", "不能把文件夹复制到自身内部")
        }
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            throw FileToolException("DESTINATION_EXISTS", "目标位置已有同名文件或文件夹")
        }
        var entries = 0L
        var bytes = 0L
        Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkActive()
                entries++
                if (entries > MAX_COPY_ENTRIES) throw FileToolException("COPY_LIMIT", "复制的文件数量超过限制")
                val target = to.resolve(from.relativize(dir))
                Files.createDirectory(target)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkActive()
                entries++
                bytes += attrs.size()
                if (entries > MAX_COPY_ENTRIES || bytes > MAX_COPY_BYTES) {
                    throw FileToolException("COPY_LIMIT", "复制的文件数量或总大小超过限制")
                }
                if (attrs.isSymbolicLink) {
                    throw FileToolException("SYMLINK_UNSUPPORTED", "为避免越过工作区边界，不复制符号链接")
                }
                if (!attrs.isRegularFile) throw FileToolException("UNSUPPORTED_FILE_TYPE", "不支持复制特殊文件")
                Files.copy(file, to.resolve(from.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }
        })
        metadata(to)
    }

    override fun move(source: String, destination: String): FileToolStat = synchronized(writeLock) {
        checkActive()
        val from = File(resolve(source)).toPath()
        val to = File(resolve(destination)).toPath()
        if (from == to) throw FileToolException("INVALID_DESTINATION", "源和目标不能相同")
        metadata(from)
        if (to.startsWith(from)) throw FileToolException("INVALID_DESTINATION", "不能把文件夹移动到自身内部")
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            throw FileToolException("DESTINATION_EXISTS", "目标位置已有同名文件或文件夹")
        }
        Files.move(from, to)
        metadata(to)
    }

    override fun delete(path: String, recursive: Boolean) {
        checkActive()
        val target = File(resolve(path)).toPath()
        val stat = metadata(target)
        if (stat.kind != FileToolKind.DIRECTORY || !recursive) {
            Files.delete(target)
            return
        }
        Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkActive()
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                checkActive()
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun verifyExpected(path: Path, current: FileToolStat?, revision: String?, sha256: String?) {
        if (revision != null && current?.revision != revision) throw FileToolException("FILE_CHANGED", "文件已变化，本次未写入")
        if (sha256 != null) {
            if (current == null || current.sizeBytes > MAX_WRITE_BYTES) throw FileToolException("FILE_CHANGED", "文件已变化，本次未写入")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(16_384)
                while (true) {
                    checkInterrupted()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_WRITE_BYTES) throw FileToolException("FILE_CHANGED", "文件已变化，本次未写入")
                    digest.update(buffer, 0, count)
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } != sha256) {
                throw FileToolException("FILE_CHANGED", "文件内容已变化，本次未写入")
            }
        }
    }

    private fun metadata(path: Path): FileToolStat {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val kind = when {
            attributes.isSymbolicLink -> FileToolKind.SYMLINK
            attributes.isRegularFile -> FileToolKind.FILE
            attributes.isDirectory -> FileToolKind.DIRECTORY
            else -> FileToolKind.OTHER
        }
        return FileToolStat(
            path = path.toAbsolutePath().toString(),
            name = path.fileName?.toString().orEmpty(),
            kind = kind,
            sizeBytes = attributes.size(),
            revision = "${attributes.lastModifiedTime()}:${attributes.size()}:${attributes.fileKey()}",
        )
    }

    override fun checkActive() {
        if (isCancelled() || Thread.currentThread().isInterrupted) throw InterruptedException("文件操作已取消")
    }

    private fun checkInterrupted() = checkActive()

    private companion object {
        val writeLock = Any()
        const val MAX_READ_BYTES = 16_384
        const val MAX_WRITE_BYTES = 512 * 1024
        const val MAX_DIRECTORY_PAGE = 200
        const val MAX_DIRECTORY_POSITION = 1_000_000
        const val MAX_COPY_ENTRIES = 20_000L
        const val MAX_COPY_BYTES = 2L * 1024 * 1024 * 1024
    }
}
