package io.github.mangi.eta.data.repository

import android.content.Context
import android.webkit.MimeTypeMap
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.mangi.eta.agent.runtime.AgentArtifactReference
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.zhh_lgtm.nexus.application.api.ArtifactRecord
import io.github.zhh_lgtm.nexus.application.api.ArtifactStatus
import java.io.File
import java.security.MessageDigest
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import org.json.JSONObject

/** Reads the native file backend's receipt, never paths claimed in assistant prose. */
internal object RunArtifactCapture {
    internal data class TerminalScanRoot(
        val environment: String,
        val directory: File,
        val deliveryBasePath: String? = null,
    )

    internal data class TerminalWorkspaceSnapshot(
        internal val entries: Map<Path, Pair<Long, Long>>,
        internal val incomplete: Boolean = false,
    )
    private data class TerminalScan(val files: Map<Path, Pair<Path, File>>, val incomplete: Boolean)

    /**
     * Adds a bounded, read-only receipt of files changed by one foreground terminal command.
     * This never registers or publishes an artifact; the Agent must still call deliver_file.
     */
    fun terminalResult(
        toolName: String,
        args: JSONObject,
        raw: String,
        startedAt: Long,
        roots: Collection<TerminalScanRoot>,
        snapshot: TerminalWorkspaceSnapshot? = null,
    ): String {
        val action = args.optString("action", "exec").lowercase()
        val shouldScan = when (toolName) {
            "terminal" -> action == "exec"
            "terminal_job" -> action in setOf("start", "read", "wait")
            "terminal_daemon" -> action in setOf("start", "logs")
            else -> false
        }
        if (!shouldScan) return raw
        val result = runCatching { JSONObject(raw) }.getOrNull()?.takeIf { it.optBoolean("ok") } ?: return raw
        val jobStartedAt = result.optLong("started_at", 0L).takeIf { it > 0L }
        val since = ((jobStartedAt ?: startedAt) - 1_500L).coerceAtLeast(0L)
        val scan = scanTerminalFiles(roots) { file, value, attrs ->
            if (snapshot != null) snapshot.entries[file] != (value.length() to value.lastModified())
            else attrs.lastModifiedTime().toMillis() >= since
        }
        val allFiles = terminalDescriptors(scan.files, roots)
        val files = allFiles.take(24)
        val incomplete = scan.incomplete || snapshot?.incomplete == true
        if (files.isEmpty() && !incomplete) return raw
        return result
            .put("generated_file_count", allFiles.size)
            .put("generated_files_truncated", allFiles.size > files.size)
            .put("generated_files_scan_incomplete", incomplete)
            .put("generated_files_notice", "候选来自共享目录，可能包含其他会话文件；使用 list_directory 分页核对，交付须调用 deliver_file。扫描不完整时可能遗漏文件。")
            .put("artifact_registration", "requires_deliver_file")
            .put("generated_files", org.json.JSONArray().apply { files.forEach(::put) })
            .toString()
    }

    internal fun recentTerminalFiles(
        roots: Collection<TerminalScanRoot>,
        since: Long,
        limit: Int = 24,
    ): List<JSONObject> {
        if (limit <= 0) return emptyList()
        val found = scanTerminalFiles(roots) { _, _, attrs -> attrs.lastModifiedTime().toMillis() >= since }
        return terminalDescriptors(found.files, roots).take(limit)
    }

    internal fun snapshotTerminalRoots(roots: Collection<TerminalScanRoot>): TerminalWorkspaceSnapshot {
        val found = scanTerminalFiles(roots) { _, _, _ -> true }
        return TerminalWorkspaceSnapshot(found.files.mapValues { (_, value) ->
            value.second.length() to value.second.lastModified()
        }, found.incomplete)
    }

    internal fun changedTerminalFiles(
        roots: Collection<TerminalScanRoot>,
        snapshot: TerminalWorkspaceSnapshot,
        limit: Int = 24,
    ): List<JSONObject> {
        val found = scanTerminalFiles(roots) { file, value, _ ->
            snapshot.entries[file] != (value.length() to value.lastModified())
        }
        return terminalDescriptors(found.files, roots).take(limit)
    }

    private fun scanTerminalFiles(
        roots: Collection<TerminalScanRoot>,
        include: (Path, File, BasicFileAttributes) -> Boolean,
    ): TerminalScan {
        var incomplete = false
        val rootPaths = roots.mapNotNull { root ->
            runCatching { root.copy(directory = root.directory.canonicalFile) }
                .getOrNull()?.takeIf { it.directory.isDirectory }.also { if (it == null) incomplete = true }
        }.distinctBy { it.directory.toPath() }
        val found = linkedMapOf<Path, Pair<Path, File>>()
        var visited = 0
        for (root in rootPaths) {
            val rootDirectory = root.directory
            runCatching {
                Files.walkFileTree(rootDirectory.toPath(), emptySet(), MAX_SCAN_DEPTH, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (++visited > MAX_SCANNED_ENTRIES) { incomplete = true; return FileVisitResult.TERMINATE }
                        if (dir != rootDirectory.toPath() && dir.fileName?.toString() in EXCLUDED_DIRECTORIES) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (++visited > MAX_SCANNED_ENTRIES) { incomplete = true; return FileVisitResult.TERMINATE }
                        if (attrs.isDirectory) { incomplete = true; return FileVisitResult.CONTINUE }
                        val name = file.fileName?.toString().orEmpty()
                        if (!attrs.isRegularFile || Files.isSymbolicLink(file) ||
                            name.endsWith(".tmp", ignoreCase = true) || name.endsWith(".partial", ignoreCase = true)
                        ) return FileVisitResult.CONTINUE
                        if (include(file, file.toFile(), attrs)) {
                            found.putIfAbsent(file, rootDirectory.toPath() to file.toFile())
                        }
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                        incomplete = true
                        return FileVisitResult.CONTINUE
                    }
                })
            }.onFailure { incomplete = true }
        }
        return TerminalScan(found, incomplete)
    }

    private fun terminalDescriptors(
        found: Map<Path, Pair<Path, File>>,
        roots: Collection<TerminalScanRoot>,
    ): List<JSONObject> = found.values
        .distinctBy { it.second.canonicalPath }
        .sortedByDescending { (_, file) -> file.lastModified() }
        .map { (rootDirectory, file) ->
            val root = roots.first { root ->
                runCatching { root.directory.canonicalFile }.getOrNull()?.toPath() == rootDirectory
            }
            val relative = rootDirectory.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
            JSONObject()
                .put("environment", root.environment)
                .put("path", relative)
                .put("deliver_path", root.deliveryBasePath?.trimEnd('/')?.plus("/$relative") ?: relative)
                .put("size_bytes", file.length())
                .put("modified_at", file.lastModified())
        }

    fun fileResult(context: Context, call: AgentModelClient.ToolCall, raw: String, subjectType: String?, subjectId: String?,
        linuxWorkspace: Pair<String, String>? = null): String {
        if (call.name !in setOf("write_file", "edit_file", "copy_file", "move_file", "voice_synthesize")) return raw
        val request = RuntimeInvocationScope.controller?.executionIdentity ?: return raw
        val result = runCatching { JSONObject(raw) }.getOrNull()?.takeIf { it.optBoolean("ok") } ?: return raw
        val receiptPath = result.optString("path").takeIf {
            if (linuxWorkspace != null) it.startsWith('/') else File(it).isAbsolute
        } ?: return raw
        try {
            val workspaceFile = TerminalPrivateStorage.workspace(context.filesDir).canonicalFile
            val workspace = workspaceFile.toPath()
            val path = if (linuxWorkspace != null) {
                linuxWorkspaceFile(receiptPath, linuxWorkspace.first, linuxWorkspace.second, workspaceFile)?.path
                    ?: return result.put("artifact_registration", "requires_deliver_file").toString()
            } else receiptPath
            val produced = File(path).canonicalFile.toPath()
            if (!produced.toFile().isFile) {
                return if (linuxWorkspace != null) result.put("artifact_registration", "requires_deliver_file").toString() else raw
            }
            val localWorkspace = produced.startsWith(workspace)
            val relative = if (localWorkspace) workspace.relativize(produced).toString().replace(File.separatorChar, '/') else path
            val id = "artifact-" + MessageDigest.getInstance("SHA-256")
                .digest("${request.runId.value}\u0000${call.id}\u0000$path".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val now = System.currentTimeMillis()
            val artifact = NexusArtifactLedger(context).record(ArtifactRecord(id, request.runId.value, relative,
                result.optString("label").takeIf(String::isNotBlank)?.take(255) ?: File(path).name,
                result.optString("kind", "file"), ArtifactStatus.VERIFIED, now, now, request.projectId,
                subjectType.takeIf { subjectId != null }, subjectId, if (localWorkspace) "workspace" else "device",
                result.optString("mime_type").takeIf(String::isNotBlank) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(path).extension.lowercase()),
                result.optLong("size_bytes", result.optLong("bytes", -1)).takeIf { it >= 0 }))
            result.put("artifact_id", artifact.id)
            result.put("mime_type", artifact.mediaType)
            GeneratedFilesPublicationStore.read(context, artifact.id)?.let {
                result.put("visible_uri", it.uri)
                result.put("visible_path", it.relativePath)
            }
            return result.toString()
        } catch (_: Exception) {
            return result.put("artifact_registration", "unavailable").toString()
        }
    }

    internal fun linuxWorkspaceFile(path: String, guestRoot: String, hostRoot: String, privateRoot: File): File? = runCatching {
        fun components(value: String): List<String>? {
            if (!value.startsWith('/') || '\\' in value || ':' in value || '\u0000' in value) return null
            val parts = mutableListOf<String>()
            for (part in value.split('/')) when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
            return parts
        }
        val rootParts = components(guestRoot) ?: return null
        val pathParts = components(path) ?: return null
        if (rootParts.isEmpty() || pathParts.size <= rootParts.size || pathParts.take(rootParts.size) != rootParts) return null
        val host = File(hostRoot).canonicalFile.toPath()
        val target = pathParts.drop(rootParts.size).fold(host.toFile()) { parent, part -> File(parent, part) }.canonicalFile
        if (!target.toPath().startsWith(host) || !target.toPath().startsWith(privateRoot.canonicalFile.toPath())) return null
        target
    }.getOrNull()

    /** Byte attachments came from a transport response, not paths or downloads claimed by remote prose. */
    fun externalResult(context: Context, call: AgentModelClient.ToolCall, result: AgentModelClient.ToolResult,
        subjectType: String?, subjectId: String?): AgentModelClient.ToolResult {
        if (result.files.isEmpty()) return result
        val owner = RuntimeInvocationScope.controller?.executionIdentity ?: return result.copy(files = emptyList())
        val payload = runCatching { JSONObject(result.content) }.getOrNull()
            ?: JSONObject().put("ok", true).put("text", result.content)
        if (!payload.optBoolean("ok", true)) return result.copy(files = emptyList())
        val attached = org.json.JSONArray()
        val references = mutableListOf<AgentArtifactReference>()
        var total = 0L
        for ((index, item) in result.files.take(8).withIndex()) {
            total += item.bytes.size
            if (item.bytes.isEmpty() || item.bytes.size > 8 * 1024 * 1024 || total > 16 * 1024 * 1024) {
                payload.put("artifact_registration", "size_limit"); continue
            }
            try {
                val root = TerminalPrivateStorage.workspace(context.filesDir).canonicalFile
                val folder = root.resolve("artifacts/${owner.runId.value}").apply { check(isDirectory || mkdirs()) }.canonicalFile
                check(folder.toPath().startsWith(root.toPath()))
                val mediaType = NexusArtifactMedia.detectedMimeType(item.mediaType, item.name, header = item.bytes.copyOf(minOf(item.bytes.size, 512)))
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mediaType).orEmpty()
                val name = item.name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_")
                    .trim('.', ' ').take(120).ifBlank { "result-${index + 1}" + if (extension.isBlank()) ".bin" else ".$extension" }
                val digest = MessageDigest.getInstance("SHA-256").digest((call.id + "\u0000" + index).toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }.take(16)
                val file = folder.resolve("$digest-$name")
                val temporary = folder.resolve(".$digest-${java.util.UUID.randomUUID()}.tmp")
                try {
                    java.io.FileOutputStream(temporary).use { stream -> stream.write(item.bytes); stream.fd.sync() }
                    try { java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                    catch (_: java.nio.file.AtomicMoveNotSupportedException) { java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                } finally { temporary.delete() }
                val receipt = JSONObject().put("ok", true).put("path", file.absolutePath).put("label", name)
                    .put("kind", "file").put("mime_type", mediaType).put("bytes", file.length())
                val registered = JSONObject(fileResult(context, call.copy(name = "write_file", id = "${call.id}:attachment:$index"), receipt.toString(), subjectType, subjectId))
                attached.put(JSONObject().put("id", registered.getString("artifact_id")).put("name", name).put("path", file.absolutePath)
                    .put("media_type", mediaType).put("size_bytes", file.length()))
                references += AgentArtifactReference(registered.getString("artifact_id"), name, mediaType)
            } catch (_: Exception) { payload.put("artifact_registration", "unavailable") }
        }
        if (attached.length() > 0) {
            payload.put("artifacts", attached)
            payload.put("artifact_id", attached.getJSONObject(0).getString("id"))
        }
        return result.copy(content = payload.toString(), files = emptyList(), artifacts = references)
    }

    fun modelResult(context: Context, runId: String, round: Int, files: List<AgentModelClient.ToolFile>,
        subjectType: String?, subjectId: String?): List<AgentArtifactReference> = externalResult(context,
        AgentModelClient.ToolCall("model:$runId:$round", "model_output", "{}"),
        AgentModelClient.ToolResult("{\"ok\":true}", files = files), subjectType, subjectId).artifacts

    private const val MAX_SCAN_DEPTH = 8
    private const val MAX_SCANNED_ENTRIES = 4_000
    private val EXCLUDED_DIRECTORIES = setOf(
        ".git", ".gradle", ".kotlin", ".analysis", ".cxx", "build", "node_modules", "__pycache__",
    )
}
