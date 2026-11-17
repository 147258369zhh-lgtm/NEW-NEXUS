package io.github.mangi.eta.fork.projects

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.zhh_lgtm.nexus.application.api.WorkspaceBinding
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** SAF originals are read only until a distinct, locally approved publish operation. */
internal class ProjectWorkspace private constructor(
    private val context: Context,
    val binding: WorkspaceBinding,
    private val stateDirectory: File,
    private val tree: SafProjectTree,
    private val projectAvailable: () -> Boolean,
    private val project: ProjectEntity,
) {
    private val files = File(binding.hostPath).canonicalFile
    private val manifest = File(stateDirectory, "binding.json")
    fun ensurePrepared() {
        if (!manifest.isFile) prepare(context, project, binding.runId, tree, projectAvailable)
        ensureActive()
    }

    fun status(offset: Int = 0, limit: Int = 50): JSONObject = prepared {
        require(offset >= 0 && limit in 1..100)
        ensureActive()
        val changes = changes()
        JSONObject().put("ok", true).put("project_id", binding.projectId).put("binding_id", binding.id)
            .put("workspace", binding.hostPath).put("linux_workspace", binding.guestPath).put("source_read_only", true)
            .put("changes", JSONArray(changes.drop(offset).take(limit))).put("change_fingerprint", fingerprint(changes))
            .put("total_changes", changes.size).put("next_offset", minOf(offset.toLong() + limit, changes.size.toLong()))
            .put("has_more", offset.toLong() + limit < changes.size)
            .put("publish_history", JSONArray(stateDirectory.listFiles().orEmpty().filter { it.name.startsWith("publish-") && it.extension == "json" }
                .sortedByDescending(File::lastModified).take(4).map { journal ->
                    val record = JSONObject(journal.readText())
                    JSONObject().put("id", journal.nameWithoutExtension).put("phase", record.getString("phase"))
                        .put("published_count", record.optJSONArray("committed")?.length() ?: 0)
                        .put("pending", record.optJSONObject("pending") ?: JSONObject.NULL)
                        .put("retained_backups", record.optJSONArray("retained") ?: JSONArray())
                }))
    }

    fun publish(paths: List<String>, expectedFingerprint: String, operationId: String): JSONObject = prepared {
        ensureActive(); tree.ensureAccess(true)
        require(paths.size in 1..32 && paths.distinct().size == paths.size) { "每次发布请选择 1–32 个文件" }
        paths.forEach(::validateRelative)
        val journal = File(stateDirectory, "publish-${hash(operationId)}.json")
        val requestFingerprint = hash(JSONArray(paths).toString() + ":" + expectedFingerprint)
        if (journal.isFile) {
            val previous = JSONObject(journal.readText())
            require(previous.optString("request_fingerprint") == requestFingerprint) { "相同发布标识不能更换文件或变更指纹" }
            return@prepared previous.optJSONObject("result") ?: JSONObject().put("ok", false).put("code", "PROJECT_PUBLISH_UNCONFIRMED")
                .put("message", "上次发布未取得完整回执，请先核对项目原文件，不要自动重发")
        }
        val changes = changes()
        require(expectedFingerprint == fingerprint(changes)) { "工作副本已变化，请重新检查后发布" }
        val byPath = changes.associateBy { it.getString("path") }
        paths.forEach { path ->
            val change = byPath[path] ?: error("所选文件没有变化：$path")
            require(change.getString("kind") != "deleted") { "发布不删除原文件；请保留或单独管理删除项" }
        }
        val before = readManifest()
        val baseline = before.getJSONObject("files")
        val resolved = paths.associateWith { path ->
            val parent = findDirectory(path.substringBeforeLast('/', ""), create = false)
            val current = parent?.let { tree.children(it).singleOrNull { node -> node.name == path.substringAfterLast('/') } }
            val original = baseline.optJSONObject(path)
            if (original == null) require(current == null) { "项目中新出现同名文件，请先合并：$path" }
            else {
                require(current != null && !current.directory && current.id == original.getString("document_id")) { "原项目文件已移动或替换：$path" }
                require(documentHash(current.id) == original.getString("sha256")) { "原项目文件已被其他程序修改：$path" }
                require(current.flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0) { "文档服务不支持保留原件后替换，请使用分享导出：$path" }
            }
            current
        }
        val state = JSONObject().put("phase", "PUBLISHING").put("paths", JSONArray(paths)).put("committed", JSONArray())
            .put("request_fingerprint", requestFingerprint)
        writeAtomic(journal, state)
        val committed = JSONArray()
        val retained = JSONArray()
        fun forgetBackup(path: String) {
            val remaining = (0 until retained.length()).map { retained.getJSONObject(it) }.filterNot { it.getString("path") == path }
            while (retained.length() > 0) retained.remove(0)
            remaining.forEach { retained.put(it) }
        }
        try {
            paths.forEach { path ->
                ensureActive()
                val source = checkedFile(path)
                require(source.isFile && hashFile(source) == byPath.getValue(path).getString("sha256")) { "工作文件已变化：$path" }
                val parent = requireNotNull(findDirectory(path.substringBeforeLast('/', ""), create = true))
                val name = path.substringAfterLast('/')
                val tempName = ".nexus-${UUID.randomUUID()}.part"
                val temporary = tree.create(parent, tempName, false)
                var oldBackup: ProjectDocument? = null
                var published = false
                try {
                    require(temporary.flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0) { "文档服务不支持发布重命名" }
                    tree.write(temporary.id).use { output -> source.inputStream().use { input -> copy(input, output) } }
                    require(documentHash(temporary.id) == byPath.getValue(path).getString("sha256")) { "发布副本写入校验失败" }
                    val original = resolved[path]
                    val current = tree.children(parent).singleOrNull { it.name == name }
                    if (original == null) require(current == null) { "项目目标已变化：$path" }
                    else {
                        require(current?.id == original.id && documentHash(original.id) == baseline.getJSONObject(path).getString("sha256")) { "项目文件在发布前已变化：$path" }
                        val backupName = ".nexus-${UUID.randomUUID()}.backup"
                        state.put("pending", JSONObject().put("path", path).put("original_document_id", original.id)
                            .put("temporary_document_id", temporary.id).put("backup_name", backupName))
                        writeAtomic(journal, state)
                        oldBackup = tree.rename(original.id, backupName)
                        retained.put(JSONObject().put("path", path).put("document_id", oldBackup!!.id).put("backup_name", oldBackup!!.name))
                        state.put("retained", retained); writeAtomic(journal, state)
                    }
                    val output = tree.rename(temporary.id, name)
                    published = true
                    require(documentHash(output.id) == byPath.getValue(path).getString("sha256")) { "发布后校验失败：$path" }
                    baseline.put(path, JSONObject().put("document_id", output.id).put("sha256", byPath.getValue(path).getString("sha256")))
                    writeAtomic(manifest, before)
                    committed.put(path)
                    state.remove("pending")
                    state.put("committed", committed); writeAtomic(journal, state)
                    if (oldBackup != null) {
                        runCatching { tree.delete(oldBackup!!.id) }.onSuccess {
                            forgetBackup(path)
                        }
                    }
                } catch (failure: Exception) {
                    val backup = oldBackup
                    if (!published && backup != null) {
                        // Cancellation cannot prevent best-effort restoration of the original name.
                        runCatching { restore { tree.rename(backup.id, name) } }.onSuccess { restored ->
                            baseline.getJSONObject(path).put("document_id", restored.id)
                            writeAtomic(manifest, before)
                            forgetBackup(path)
                            state.remove("pending")
                        }
                    }
                    throw failure
                } finally {
                    if (!published) runCatching { restore { tree.delete(temporary.id) } }
                }
            }
            val result = JSONObject().put("ok", true).put("published", committed).put("retained_backups", retained)
            writeAtomic(journal, state.put("phase", "COMPLETED").put("result", result))
            result
        } catch (failure: Exception) {
            val result = JSONObject().put("ok", false).put("code", "PROJECT_PUBLISH_INCOMPLETE").put("published", committed)
                .put("retained_backups", retained).put("message", "发布未完整完成；已验证的文件与原件备份位置已保留，请先核对，不要自动重发")
                .put("error_type", failure.javaClass.simpleName)
            runCatching { writeAtomic(journal, state.put("phase", "INCOMPLETE").put("result", result)) }
            result
        }
    }

    private fun changes(): List<JSONObject> {
        val baseline = readManifest().getJSONObject("files")
        val actual = linkedMapOf<String, String>()
        var bytes = 0L
        files.walkTopDown().onEnter { directory ->
            ensureActive(); require(!Files.isSymbolicLink(directory.toPath())) { "工作区不能包含目录链接" }; true
        }.filter(File::isFile).forEach { file ->
            ensureActive()
            require(!Files.isSymbolicLink(file.toPath()) && actual.size < MAX_FILES) { "工作区文件数量超限或包含链接" }
            bytes += file.length(); require(bytes <= MAX_BYTES && file.length() <= MAX_FILE_BYTES) { "工作区文件超过发布大小限制" }
            actual[file.relativeTo(files).invariantSeparatorsPath.also(::validateRelative)] = hashFile(file)
        }
        return (actual.keys + baseline.keys().asSequence().toSet()).sorted().mapNotNull { path ->
            val old = baseline.optJSONObject(path)?.optString("sha256")
            val current = actual[path]
            if (old == current) null else JSONObject().put("path", path).put("kind", if (old == null) "added" else if (current == null) "deleted" else "modified")
                .put("sha256", current ?: JSONObject.NULL).put("source_sha256", old ?: JSONObject.NULL)
        }
    }
    private fun findDirectory(path: String, create: Boolean): String? {
        var parent = tree.rootId
        if (path.isEmpty()) return parent
        path.split('/').forEach { name ->
            val child = tree.children(parent).singleOrNull { it.name == name }
            parent = if (child == null) { if (!create) return null; tree.create(parent, name, true).id }
                else child.also { require(it.directory) { "原项目父路径不是目录" } }.id
        }
        return parent
    }
    private fun checkedFile(path: String): File {
        validateRelative(path)
        val file = File(files, path)
        require(file.canonicalFile.toPath().startsWith(files.toPath()) && !Files.isSymbolicLink(file.toPath())) { "文件超出项目工作副本" }
        return file
    }
    private fun documentHash(id: String): String = tree.read(id).use { input -> digest(input) }
    private fun ensureActive() { RuntimeInvocationScope.controller?.throwIfCancelled(); check(projectAvailable()) { "项目已归档、移除或目录绑定已变化" }; tree.ensureAccess() }
    private fun readManifest() = JSONObject(manifest.readText()).also { require(it.getString("project_id") == binding.projectId && it.getString("source") == binding.sourceReference) }
    private fun fingerprint(changes: List<JSONObject>) = hash(binding.id + ":" + JSONArray(changes).toString())
    private fun <T> prepared(action: () -> T): T { ensurePrepared(); return locked(action) }
    private fun <T> locked(action: () -> T): T = synchronized(LOCK) {
        FileChannel.open(File(stateDirectory, ".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { it.lock().use { action() } }
    }

    companion object {
        private val LOCK = Any()
        const val MAX_FILES = 2048
        const val MAX_BYTES = 64L * 1024 * 1024
        const val MAX_FILE_BYTES = 16L * 1024 * 1024
        fun open(context: Context, project: ProjectEntity, runId: String,
            tree: SafProjectTree = SafProjectTree(context, Uri.parse(project.rootIdentityKey)),
            projectAvailable: () -> Boolean = { true }): ProjectWorkspace {
            require(project.archivedAt == null && project.source == "SAF") { "项目不是可用的授权目录" }
            val parent = File(TerminalPrivateStorage.workspace(context.filesDir), "projects/${hash(project.id)}/${hash(runId)}").canonicalFile
            val binding = WorkspaceBinding("workspace-${hash(project.id + ":" + runId)}", project.id, runId,
                File(parent, "files").absolutePath, sourceReference = project.rootIdentityKey)
            return ProjectWorkspace(context.applicationContext, binding, parent, tree, projectAvailable, project)
        }
        fun prepare(context: Context, project: ProjectEntity, runId: String,
            tree: SafProjectTree = SafProjectTree(context, Uri.parse(project.rootIdentityKey)),
            projectAvailable: () -> Boolean = { true }): ProjectWorkspace {
            require(project.archivedAt == null && project.source == "SAF") { "项目不是可用的授权目录" }
            tree.ensureAccess()
            val owner = open(context, project, runId, tree, projectAvailable)
            val parent = owner.stateDirectory
            synchronized(LOCK) {
                if (File(parent, "binding.json").isFile) { owner.readManifest(); return owner }
                check(!parent.exists()) { "项目工作副本未完成，请先保留并核对已有文件" }
                check(parent.parentFile!!.mkdirs() || parent.parentFile!!.isDirectory)
                val temporary = File(parent.parentFile, ".preparing-${UUID.randomUUID()}").apply { check(mkdir()) }
                try {
                    val files = File(temporary, "files").apply { check(mkdir()) }
                    val baseline = JSONObject(); val ids = hashSetOf<String>(); var total = 0L; var count = 0
                    fun copyDirectory(id: String, relative: String, depth: Int) {
                        require(depth <= 16 && ids.add(id)) { "项目目录层级过深或存在循环" }
                        val children = tree.children(id)
                        require(children.map { it.name.lowercase() }.distinct().size == children.size) { "项目目录存在冲突的文件名" }
                        children.forEach { node ->
                            RuntimeInvocationScope.controller?.throwIfCancelled()
                            require(++count <= MAX_FILES) { "项目超过 2048 个条目" }
                            val path = if (relative.isEmpty()) node.name else "$relative/${node.name}"
                            validateRelative(path)
                            val destination = File(files, path)
                            if (node.directory) { check(destination.mkdir()); copyDirectory(node.id, path, depth + 1) }
                            else {
                                require(ids.add(node.id)) { "项目目录重复引用相同文档" }
                                require(node.size == null || node.size in 0..MAX_FILE_BYTES) { "项目单文件超过 16 MiB" }
                                tree.read(node.id).use { input -> destination.outputStream().use { output -> copy(input, output) } }
                                total += destination.length(); require(total <= MAX_BYTES) { "项目总大小超过 64 MiB，请缩小项目目录" }
                                val after = tree.document(node.id)
                                require(node.size == after.size && node.modified == after.modified && node.name == after.name) { "项目文件在读取时发生变化" }
                                baseline.put(path, JSONObject().put("document_id", node.id).put("sha256", hashFile(destination)))
                            }
                        }
                    }
                    copyDirectory(tree.rootId, "", 0)
                    check(projectAvailable()) { "项目已不可用" }
                    writeAtomic(File(temporary, "binding.json"), JSONObject().put("project_id", project.id).put("run_id", runId)
                        .put("source", project.rootIdentityKey).put("files", baseline))
                    Files.move(temporary.toPath(), parent.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } finally { temporary.deleteRecursively() }
            }
            return owner
        }
        private fun validateRelative(path: String) {
            require(path.isNotBlank() && path.length <= 2048 && '\\' !in path && ':' !in path && !path.startsWith('/') && path.split('/').size <= 17)
            path.split('/').forEach(SafProjectTree::validateName)
        }
        private fun copy(input: InputStream, output: java.io.OutputStream) {
            val buffer = ByteArray(64 * 1024); var size = 0L
            val cancellation = RuntimeInvocationScope.controller?.register { runCatching { input.close() }; runCatching { output.close() } }
            try {
                while (true) { RuntimeInvocationScope.controller?.throwIfCancelled(); val count = input.read(buffer); if (count < 0) break
                    size += count; require(size <= MAX_FILE_BYTES); output.write(buffer, 0, count) }
            } finally { cancellation?.close() }
        }
        private fun digest(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024); var size = 0L
            val cancellation = RuntimeInvocationScope.controller?.register { runCatching { input.close() } }
            try { while (true) { RuntimeInvocationScope.controller?.throwIfCancelled(); val count = input.read(buffer); if (count < 0) break
                size += count; require(size <= MAX_FILE_BYTES); digest.update(buffer, 0, count) }
            } finally { cancellation?.close() }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        private fun hashFile(file: File) = file.inputStream().use(::digest)
        private fun <T> restore(action: () -> T): T {
            val resources = io.github.mangi.eta.agent.runtime.RunResourceScope()
            return try { RuntimeInvocationScope.withResources(resources) { RuntimeInvocationScope.withController(AgentRunController(), action) } }
            finally { resources.close() }
        }
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun writeAtomic(file: File, data: JSONObject) {
            val temporary = File(file.parentFile, ".${file.name}.new")
            FileOutputStream(temporary).use { it.write(data.toString().toByteArray()); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
