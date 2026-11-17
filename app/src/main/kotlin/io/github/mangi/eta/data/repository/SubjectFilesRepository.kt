package io.github.mangi.eta.data.repository

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.fork.meeting.MeetingRepository
import io.github.mangi.eta.fork.projects.ProjectRepository
import io.github.zhh_lgtm.nexus.application.api.ArtifactRecord
import io.github.zhh_lgtm.nexus.application.api.ArtifactStatus
import java.io.File
import java.security.MessageDigest
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal data class SubjectInsightFile(
    val artifact: ArtifactRecord,
    val input: Boolean,
    val source: String,
    val readable: Boolean = false,
    val availability: String = "不可读",
    val directory: Boolean = false,
)

/** Files are admitted through explicit imports/attachments or the verified artifact ledger. */
internal object SubjectFilesRepository {
    fun observeFiles(context: Context, type: String, id: String): Flow<List<SubjectInsightFile>> {
        require(type in setOf("conversation", "meeting") && id.isNotBlank())
        val changes = WorkspaceArtifactStore(context).changes()
        // Watch user attachments independently of the artifact ledger. A new input does not
        // otherwise write to that ledger until the file panel is opened or refreshed.
        val inputs = if (type == "meeting") {
            MeetingRepository.snapshot(context, id).map { it?.messageAttachments }.distinctUntilChanged()
        } else EtaDatabase.get(context).conversationDao().userMessageChanges(id)
        return combine(changes, inputs) { _, _ -> Unit }.map { files(context, type, id) }
    }

    suspend fun importDocuments(context: Context, type: String, id: String, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        require(type in setOf("conversation", "meeting") && id.isNotBlank())
        var imported = 0
        uris.forEach { uri ->
            currentCoroutineContext().ensureActive()
            when (val result = AgentFileReferenceGateway.importDocumentUri(context, uri)) {
                is AgentFileReferenceGateway.Resolution.Success -> {
                    persistReferences(context, type, id, listOf(result.reference))
                    imported++
                }
                is AgentFileReferenceGateway.Resolution.Failure -> error("无法导入所选文件；已成功导入 $imported 个文件")
            }
        }
        imported
    }

    suspend fun persistReferences(context: Context, type: String, id: String, references: List<AgentFileReference>) = withContext(Dispatchers.IO) {
        require(type in setOf("conversation", "meeting") && id.isNotBlank())
        references.filter { it.kind == AgentFileReferenceKind.File }.forEach { reference ->
            check(admit(context, type, id, reference.absolutePath, reference.displayName) != null) { "无法读取所选文件" }
        }
    }

    suspend fun files(context: Context, type: String, id: String): List<SubjectInsightFile> = withContext(Dispatchers.IO) {
        require(type in setOf("conversation", "meeting") && id.isNotBlank())
        val directories = mutableListOf<SubjectInsightFile>()
        suspend fun collectReferences(references: List<AgentFileReference>) {
            references.filter { it.kind == AgentFileReferenceKind.File }.forEach { reference ->
                // Historical attachments may no longer be readable; keep the rest visible.
                try { admit(context, type, id, reference.absolutePath, reference.displayName) }
                catch (_: IOException) { }
                catch (_: SecurityException) { }
                catch (_: IllegalArgumentException) { }
            }
            references.filter { it.kind == AgentFileReferenceKind.Directory }.forEach { reference ->
                val readable = runCatching { File(reference.absolutePath).let { it.isDirectory && it.canRead() } }.getOrDefault(false)
                directories += SubjectInsightFile(
                    ArtifactRecord(id = "directory-${digest("$type:$id:${reference.absolutePath}")}", runId = "subject-directory",
                        path = reference.absolutePath, label = reference.displayName, kind = "directory",
                        status = ArtifactStatus.PENDING, createdAt = 0L, subjectType = type, subjectId = id, location = "device"),
                    input = true, source = "输入栏目录引用", readable = false,
                    availability = if (readable) "目录引用 · 不展开、预览或导出目录内容" else "目录引用 · 已失效或当前权限不可读",
                    directory = true,
                )
            }
        }
        if (type == "meeting") {
            MeetingRepository.snapshot(context, id).first()?.messageAttachments?.values?.forEach { attachments ->
                collectReferences(attachments.files.map { it.reference })
                attachments.images.forEachIndexed { index, image -> admitImageSafely(context, type, id, image.dataUrl.ifBlank { image.uri }, index) }
            }
        } else {
            // Subject-specific bounded pages avoid loading unrelated conversations or huge history at once.
            val dao = EtaDatabase.get(context).conversationDao()
            var offset = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val messages = dao.messageRowsPage(id, 100, offset)
                messages.filter { it.type == "user" }.forEach { rawMessage ->
                    val message = dao.restoreMessage(rawMessage)
                    collectReferences(AgentFileReferencePromptCodec.parse(message.content).references)
                    val images = runCatching { JSONArray(message.imagesJson) }.getOrDefault(JSONArray())
                    for (index in 0 until images.length()) admitImageSafely(context, type, id, images.optString(index), index)
                }
                if (messages.size < 100) break
                offset += messages.size
            }
        }
        val ledger = NexusArtifactLedger(context)
        val projectId = ProjectRepository.bindingForSubject(context, type, id)?.projectId
        val subjectFiles = ledger.forSubject(type, id)
        // Only project-level explicit inputs are shared; another subject's outputs never leak here.
        val sharedInputs = projectId?.let { ledger.forProject(it) }.orEmpty().filter {
            it.subjectId == null && isInput(it)
        }
        (subjectFiles.map { SubjectInsightFile(it, isInput(it), if (isInput(it)) "${if (type == "meeting") "当前会议室" else "当前会话"}导入 / 输入栏附件" else "${if (type == "meeting") "当前会议室" else "当前会话"}输出文件记录") } +
            sharedInputs.map { SubjectInsightFile(it, true, "关联项目导入文件") })
            .map { entry -> val state = readability(context, entry.artifact); entry.copy(readable = state.first, availability = state.second) }
            .plus(directories)
            .distinctBy { Triple(it.input, it.artifact.location, it.artifact.path) }
    }

    private fun readability(context: Context, record: ArtifactRecord): Pair<Boolean, String> {
        if (record.status != ArtifactStatus.VERIFIED) return false to if (record.status == ArtifactStatus.FAILED) "验证失败 · 保留文件记录" else "尚未验证 · 保留文件记录"
        val readable = runCatching {
            when (record.location) {
                "content" -> context.contentResolver.openInputStream(Uri.parse(record.path))?.use { it.read(); true } ?: false
                "workspace" -> {
                    val root = io.github.mangi.eta.agent.terminal.TerminalPrivateStorage.workspace(context.filesDir).canonicalFile
                    val file = File(root, record.path).canonicalFile
                    file.toPath().startsWith(root.toPath()) && file.isFile && file.canRead()
                }
                "device" -> File(record.path).let { it.isFile && it.canRead() }
                else -> false
            }
        }.getOrDefault(false)
        return readable to if (readable) "可读 · 已验证记录" else "已失效或当前权限不可读 · 保留文件记录"
    }
    private fun isInput(record: ArtifactRecord) = record.kind == "input" || record.runId == "manual-import" || record.runId == "subject-import"

    private fun admit(context: Context, type: String, id: String, path: String, name: String): String? {
        val uri = Uri.parse(path)
        val actualPath = if (uri.scheme == "file") uri.path ?: return null else path
        val workspace = io.github.mangi.eta.agent.terminal.TerminalPrivateStorage.workspace(context.filesDir).canonicalFile
        val local = if (uri.scheme == "content") null else File(actualPath).canonicalFile
        val location = when {
            uri.scheme == "content" -> "content"
            local != null && local.toPath().startsWith(workspace.toPath()) -> "workspace"
            else -> "device"
        }
        val storedPath = if (location == "workspace") local!!.relativeTo(workspace).invariantSeparatorsPath else actualPath
        val size = if (local != null) {
            if (!local.isFile || !local.canRead()) return null
            local.length()
        } else null
        val key = "input-${digest("$type:$id:$location:$storedPath")}" 
        val store = WorkspaceArtifactStore(context)
        if (store.find(key) != null) return key
        val header = if (local != null) local.inputStream().use { it.readNBytes(512) } else {
            context.contentResolver.openInputStream(uri)?.use { it.readNBytes(512) } ?: return null
        }
        val mime = NexusArtifactMedia.detectedMimeType(if (location == "content") context.contentResolver.getType(uri) else null, name, actualPath, header)
        store.record(runId = "subject-import", path = storedPath, label = name, kind = "input",
            status = WorkspaceArtifact.STATUS_VERIFIED, id = key, subjectType = type, subjectId = id,
            location = location, mediaType = mime, sizeBytes = size)
        return key
    }
    private fun admitImage(context: Context, type: String, id: String, reference: String, index: Int) {
        if (reference.isBlank()) return
        val path = if (reference.startsWith("data:image/", true)) {
            val encoded = reference.substringAfter("base64,", "")
            if (encoded.isBlank() || encoded.length > 48 * 1024 * 1024) return
            val directory = File(context.filesDir, "subject-input-images")
            check(directory.isDirectory || directory.mkdirs()) { "无法保存输入图片" }
            val target = AtomicFile(File(directory, digest(reference) + ".image"))
            if (!target.baseFile.isFile) {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                require(bytes.isNotEmpty() && bytes.size <= 32 * 1024 * 1024)
                val output = target.startWrite()
                try { output.write(bytes); target.finishWrite(output) } catch (failure: Throwable) { target.failWrite(output); throw failure }
            }
            target.baseFile.path
        } else reference
        admit(context, type, id, path, "输入图片 ${index + 1}")
    }

    private fun admitImageSafely(context: Context, type: String, id: String, reference: String, index: Int) {
        try {
            admitImage(context, type, id, reference, index)
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}




