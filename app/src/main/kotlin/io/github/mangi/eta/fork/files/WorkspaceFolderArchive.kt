package io.github.mangi.eta.fork.files

import io.github.mangi.eta.agent.terminal.FileToolBackend
import io.github.mangi.eta.agent.terminal.FileToolKind
import java.io.File
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class WorkspaceArchiveLimits(
    val maxEntries: Int = 10_000,
    val maxFileBytes: Long = 128L * 1024 * 1024,
    val maxTotalBytes: Long = 256L * 1024 * 1024,
) {
    init { require(maxEntries > 0 && maxFileBytes >= 0 && maxTotalBytes >= 0) }
}

/** Creates a complete ZIP or fails. Caller stages it before touching a SAF destination. */
internal class WorkspaceFolderArchive(
    workspace: File,
    private val backend: FileToolBackend,
    private val limits: WorkspaceArchiveLimits = WorkspaceArchiveLimits(),
) {
    private val root = workspace.canonicalFile.toPath()

    fun write(directory: String, output: OutputStream, checkCancelled: () -> Unit = {}) {
        fun check() {
            checkCancelled()
            if (Thread.currentThread().isInterrupted) throw CancellationException("Workspace export cancelled")
            backend.checkActive()
        }
        fun confined(path: String): String {
            val resolved = File(backend.resolve(path)).canonicalFile.toPath()
            require(resolved.startsWith(root)) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
            require(resolved.toString() == File(path).absolutePath) { "WORKSPACE_LINK_UNSUPPORTED" }
            return resolved.toString()
        }

        val source = confined(directory)
        require(backend.stat(source).kind == FileToolKind.DIRECTORY) { "WORKSPACE_NOT_DIRECTORY" }
        val parent = requireNotNull(File(source).parentFile) { "WORKSPACE_PATH_OUTSIDE_ROOT" }.toPath()
        val queue = ArrayDeque<String>().apply { add(source) }
        val seen = hashSetOf<String>()
        val directoryRevisions = mutableMapOf<String, String>()
        var totalBytes = 0L
        var entries = 0
        ZipOutputStream(output, Charsets.UTF_8).use { zip ->
            while (queue.isNotEmpty()) {
                check()
                val path = confined(queue.removeFirst())
                require(seen.add(path)) { "WORKSPACE_FILE_CHANGED" }
                val stat = backend.stat(path)
                require(stat.kind in setOf(FileToolKind.FILE, FileToolKind.DIRECTORY)) { "WORKSPACE_LINK_UNSUPPORTED" }
                check(++entries <= limits.maxEntries) { "WORKSPACE_ARCHIVE_LIMIT" }
                val name = parent.relativize(File(path).toPath()).toString().replace(File.separatorChar, '/')
                require(name.split('/').none { it.isEmpty() || it == "." || it == ".." || '\\' in it || '\u0000' in it }) {
                    "WORKSPACE_NAME_INVALID"
                }
                zip.putNextEntry(ZipEntry(if (stat.kind == FileToolKind.DIRECTORY) "$name/" else name))
                if (stat.kind == FileToolKind.FILE) {
                    check(stat.sizeBytes in 0..limits.maxFileBytes && stat.sizeBytes <= limits.maxTotalBytes - totalBytes) {
                        "WORKSPACE_ARCHIVE_LIMIT"
                    }
                    var offset = 0L
                    while (offset < stat.sizeBytes) {
                        check()
                        confined(path)
                        val count = minOf(16 * 1024L, stat.sizeBytes - offset).toInt()
                        val bytes = backend.readBytes(path, offset, count)
                        check(bytes.isNotEmpty() && bytes.size <= count) { "WORKSPACE_FILE_CHANGED" }
                        zip.write(bytes)
                        offset += bytes.size
                    }
                    totalBytes += offset
                    check(backend.stat(path).let { it.kind == FileToolKind.FILE && it.revision == stat.revision }) {
                        "WORKSPACE_FILE_CHANGED"
                    }
                } else {
                    directoryRevisions[path] = stat.revision
                    var offset = 0
                    var revision: String? = null
                    do {
                        check()
                        confined(path)
                        val page = backend.list(path, offset, 200, revision ?: stat.revision)
                        revision = page.revision
                        page.entries.forEach { entry ->
                            check()
                            require(File(entry.path).absoluteFile.toPath().normalize().parent == File(path).toPath()) {
                                "WORKSPACE_PATH_OUTSIDE_ROOT"
                            }
                            require(entry.kind in setOf(FileToolKind.FILE, FileToolKind.DIRECTORY)) { "WORKSPACE_LINK_UNSUPPORTED" }
                            check(entries + queue.size < limits.maxEntries) { "WORKSPACE_ARCHIVE_LIMIT" }
                            queue.add(confined(entry.path))
                        }
                        if (!page.hasMore) break
                        check(page.nextOffset > offset) { "WORKSPACE_INVALID_PAGE" }
                        offset = page.nextOffset
                    } while (true)
                }
                zip.closeEntry()
            }
            directoryRevisions.forEach { (path, revision) ->
                check()
                confined(path)
                check(backend.stat(path).let { it.kind == FileToolKind.DIRECTORY && it.revision == revision }) {
                    "WORKSPACE_FILE_CHANGED"
                }
            }
        }
    }
}
