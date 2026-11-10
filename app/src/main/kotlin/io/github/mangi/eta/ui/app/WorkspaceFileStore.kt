package io.github.mangi.eta.ui.app

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.fork.files.WorkspaceSearch
import io.github.mangi.eta.fork.files.WorkspaceSearchResult
import io.github.mangi.eta.fork.files.WorkspaceFolderArchive
import io.github.mangi.eta.agent.terminal.FileToolBackend
import io.github.mangi.eta.agent.terminal.FileToolKind
import io.github.mangi.eta.agent.terminal.LocalFileToolBackend
import io.github.mangi.eta.agent.terminal.ShellFileToolBackend
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.agent.terminal.runOneShotShell
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal data class WorkspaceEntry(val path: String, val name: String, val directory: Boolean, val size: Long)

internal data class WorkspacePreview(
    val text: String,
    val sizeBytes: Long,
    val truncated: Boolean,
    val binary: Boolean,
)

internal class WorkspaceFileStore(
    context: Context,
    private val backendFor: (File) -> FileToolBackend = ::workspaceBackend,
) {
    private val appContext = context.applicationContext
    private val artifactStore = WorkspaceArtifactStore(appContext)
    private val root: File
        get() {
            val workspace = TerminalPrivateStorage.workspace(appContext.filesDir)
            if (!workspace.exists() && !workspace.mkdirs()) {
                throw IOException("WORKSPACE_DIRECTORY_UNAVAILABLE")
            }
            if (!workspace.isDirectory) throw IOException("WORKSPACE_DIRECTORY_UNAVAILABLE")
            return workspace.canonicalFile
        }

    suspend fun list(path: String): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        val backend = backendFor(root)
        val directory = resolve(backend, path)
        val entries = mutableListOf<WorkspaceEntry>()
        var offset = 0
        var revision: String? = null
        do {
            ensureActive()
            val page = backend.list(directory, offset, 200, revision)
            revision = page.revision
            page.entries.forEach { entry ->
                val target = if (entry.kind == FileToolKind.SYMLINK) backend.stat(backend.resolve(entry.path)) else entry
                if (File(target.path).toPath().startsWith(root.toPath()) && target.kind in setOf(FileToolKind.FILE, FileToolKind.DIRECTORY)) {
                    entries += WorkspaceEntry(File(entry.path).relativeTo(root).invariantSeparatorsPath, entry.name,
                        target.kind == FileToolKind.DIRECTORY, target.sizeBytes)
                }
            }
            if (!page.hasMore) break
            check(page.nextOffset > offset) { "WORKSPACE_INVALID_PAGE" }
            offset = page.nextOffset
        } while (true)
        entries.sortedWith(compareByDescending<WorkspaceEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    suspend fun importFile(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        when (val result = AgentFileReferenceGateway.importDocumentUri(appContext, uri)) {
            is AgentFileReferenceGateway.Resolution.Success -> {
                val workspacePath = runCatching {
                    File(result.reference.absolutePath).canonicalFile.toPath()
                        .also { require(it.startsWith(root.toPath())) }
                        .let { root.toPath().relativize(it).toString().replace(File.separatorChar, '/') }
                }.getOrNull()
                workspacePath?.let {
                    artifactStore.record("manual-import", it, result.reference.displayName, status = WorkspaceArtifact.STATUS_VERIFIED)
                }
                true
            }
            is AgentFileReferenceGateway.Resolution.Failure -> false
        }
    }

    suspend fun search(path: String, query: String, includeContents: Boolean): WorkspaceSearchResult {
        val caller = currentCoroutineContext()
        return runInterruptible(Dispatchers.IO) {
            val workspace = root
            val backend = backendFor(workspace)
            val directory = resolve(backend, path)
            WorkspaceSearch(workspace, backend).search(directory, query, includeContents) { caller.ensureActive() }
        }
    }

    suspend fun exportFile(path: String, destination: Uri) = withContext(Dispatchers.IO) {
        val backend = backendFor(root)
        val source = resolve(backend, path)
        val stat = backend.stat(source)
        check(stat.kind == FileToolKind.FILE) { "WORKSPACE_NOT_FILE" }
        val output = appContext.contentResolver.openOutputStream(destination, "wt")
            ?: throw java.io.IOException("EXPORT_UNAVAILABLE")
        output.use {
            val file = File(source)
            if (file.canRead()) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            } else {
                var offset = 0L
                while (offset < stat.sizeBytes) {
                    currentCoroutineContext().ensureActive()
                    val bytes = backend.readBytes(source, offset, 16 * 1024)
                    check(bytes.isNotEmpty()) { "WORKSPACE_FILE_CHANGED" }
                    output.write(bytes)
                    offset += bytes.size
                }
            }
        }
    }

    suspend fun exportFolder(path: String, destination: Uri) {
        val caller = currentCoroutineContext()
        runInterruptible(Dispatchers.IO) {
            val workspace = root
            val backend = backendFor(workspace)
            val source = resolve(backend, path)
            val temporary = File.createTempFile("workspace-export-", ".zip", appContext.cacheDir)
            try {
                // Failures and cancellation during traversal leave the selected document untouched.
                temporary.outputStream().use { output ->
                    WorkspaceFolderArchive(workspace, backend).write(source, output) { caller.ensureActive() }
                }
                caller.ensureActive()
                val output = appContext.contentResolver.openOutputStream(destination, "wt")
                    ?: throw IOException("EXPORT_UNAVAILABLE")
                output.use {
                    temporary.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            caller.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            } finally {
                temporary.delete()
            }
        }
    }

    suspend fun preview(path: String, maxBytes: Int = PREVIEW_LIMIT): WorkspacePreview = withContext(Dispatchers.IO) {
        require(maxBytes in 1..PREVIEW_LIMIT)
        val backend = backendFor(root)
        val source = resolve(backend, path)
        val stat = backend.stat(source)
        check(stat.kind == FileToolKind.FILE) { "WORKSPACE_NOT_FILE" }
        val bytes = java.io.ByteArrayOutputStream(minOf(stat.sizeBytes, maxBytes.toLong()).toInt())
        var offset = 0L
        while (offset < stat.sizeBytes && bytes.size() < maxBytes) {
            currentCoroutineContext().ensureActive()
            val chunk = backend.readBytes(source, offset, minOf(16 * 1024, maxBytes - bytes.size()))
            check(chunk.isNotEmpty()) { "WORKSPACE_FILE_CHANGED" }
            bytes.write(chunk)
            offset += chunk.size
        }
        val content = bytes.toByteArray()
        val binary = content.any { it == 0.toByte() } || content.count { byte ->
            val value = byte.toInt() and 0xff
            value < 0x20 && value !in setOf(0x09, 0x0a, 0x0d)
        } > (content.size / 10).coerceAtLeast(1)
        val text = if (binary) "" else try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            ""
        }
        WorkspacePreview(
            text = text,
            sizeBytes = stat.sizeBytes,
            truncated = offset < stat.sizeBytes,
            binary = binary || (text.isEmpty() && content.isNotEmpty()),
        )
    }

    suspend fun createDirectory(parentPath: String, name: String) = withContext(Dispatchers.IO) {
        val backend = backendFor(root)
        val parent = resolve(backend, parentPath)
        requireDirectory(backend, parent)
        val safeName = validateName(name)
        backend.createDirectory(absoluteUnder(parent, safeName))
        Unit
    }

    suspend fun rename(path: String, newName: String) = withContext(Dispatchers.IO) {
        val backend = backendFor(root)
        val source = resolve(backend, path)
        requireNotRoot(source)
        val safeName = validateName(newName)
        backend.move(source, absoluteUnder(File(source).parent ?: root.path, safeName))
        Unit
    }

    suspend fun copyInto(path: String, destinationDirectory: String) = withContext(Dispatchers.IO) {
        val backend = backendFor(root)
        val source = resolve(backend, path)
        requireNotRoot(source)
        val destination = resolve(backend, destinationDirectory)
        requireDirectory(backend, destination)
        backend.copy(source, absoluteUnder(destination, File(source).name))
        Unit
    }

    suspend fun moveInto(path: String, destinationDirectory: String) = withContext(Dispatchers.IO) {
        val backend = backendFor(root)
        val source = resolve(backend, path)
        requireNotRoot(source)
        val destination = resolve(backend, destinationDirectory)
        requireDirectory(backend, destination)
        backend.move(source, absoluteUnder(destination, File(source).name))
        Unit
    }

    suspend fun delete(path: String): Unit = withContext(Dispatchers.IO) {
        val backend = backendFor(root)
        val source = resolve(backend, path)
        requireNotRoot(source)
        val stat = backend.stat(source)
        backend.delete(source, recursive = stat.kind == FileToolKind.DIRECTORY)
    }

    suspend fun prepareShare(path: String): Uri = withContext(Dispatchers.IO) {
        val backend = backendFor(root)
        val source = resolve(backend, path)
        val stat = backend.stat(source)
        check(stat.kind == FileToolKind.FILE) { "WORKSPACE_NOT_FILE" }
        val shareDirectory = File(appContext.cacheDir, "workspace-share").apply {
            if (!mkdirs() && !isDirectory) throw IOException("WORKSPACE_SHARE_UNAVAILABLE")
        }
        shareDirectory.listFiles()
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_SHARE_CACHE_FILES - 1)
            ?.forEach { it.deleteRecursively() }
        val safeName = File(source).name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }
        val target = File(shareDirectory, "${UUID.randomUUID()}-$safeName")
        target.outputStream().use { output -> copyBackendBytes(backend, source, stat, output) }
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", target)
    }

    fun mimeType(path: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(path).extension.lowercase())
            ?: "application/octet-stream"

    private fun resolve(backend: FileToolBackend, path: String): String {
        val portable = path.replace('\\', '/').trim()
        require(!portable.startsWith('/') && !portable.matches(Regex("^[A-Za-z]:/.*"))) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
        require(portable.split('/').none { it == ".." || it == "." || '\u0000' in it }) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
        return File(backend.resolve(portable)).canonicalFile.path.also {
            require(File(it).toPath().startsWith(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
        }
    }

    private fun validateName(name: String): String {
        val normalized = name.trim()
        require(normalized.isNotEmpty() && normalized != "." && normalized != "..") { "WORKSPACE_NAME_INVALID" }
        require(normalized.length <= MAX_NAME_LENGTH) { "WORKSPACE_NAME_TOO_LONG" }
        require('/' !in normalized && '\\' !in normalized && '\u0000' !in normalized) { "WORKSPACE_NAME_INVALID" }
        require(normalized.none(Char::isISOControl)) { "WORKSPACE_NAME_INVALID" }
        return normalized
    }

    private fun absoluteUnder(parent: String, name: String): String {
        val candidate = File(parent, name).canonicalFile
        require(candidate.toPath().startsWith(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
        return candidate.path
    }

    private fun requireDirectory(backend: FileToolBackend, path: String) {
        check(backend.stat(path).kind == FileToolKind.DIRECTORY) { "WORKSPACE_NOT_DIRECTORY" }
    }

    private fun requireNotRoot(path: String) {
        require(File(path).canonicalFile != root) { "WORKSPACE_ROOT_PROTECTED" }
    }

    private suspend fun copyBackendBytes(
        backend: FileToolBackend,
        source: String,
        stat: io.github.mangi.eta.agent.terminal.FileToolStat,
        output: java.io.OutputStream,
    ) {
        val file = File(source)
        if (file.canRead()) {
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            return
        }
        var offset = 0L
        while (offset < stat.sizeBytes) {
            currentCoroutineContext().ensureActive()
            val bytes = backend.readBytes(source, offset, 16 * 1024)
            check(bytes.isNotEmpty()) { "WORKSPACE_FILE_CHANGED" }
            output.write(bytes)
            offset += bytes.size
        }
    }

    private companion object {
        const val PREVIEW_LIMIT = 256 * 1024
        const val MAX_NAME_LENGTH = 255
        const val MAX_SHARE_CACHE_FILES = 12
    }
}

private fun workspaceBackend(root: File): FileToolBackend {
    if (!RootAccess.isGranted) return LocalFileToolBackend(root)
    val supervisor = ShellProcessSupervisor()
    return ShellFileToolBackend("android", "root", root.path, root.path) { command, input, limit ->
        runOneShotShell(supervisor, "root", command, timeoutSeconds = 20, stdin = input, maxOutputBytes = limit)
    }
}
