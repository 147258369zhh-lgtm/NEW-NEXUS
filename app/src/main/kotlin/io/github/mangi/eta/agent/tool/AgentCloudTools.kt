package io.github.mangi.eta.agent.tool

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.mangi.eta.agent.cloud.CloudApiException
import io.github.mangi.eta.agent.cloud.CloudDocumentTools
import io.github.mangi.eta.agent.cloud.CloudInputFile
import io.github.mangi.eta.agent.cloud.CloudMediaTools
import io.github.mangi.eta.agent.cloud.CloudToolContext
import io.github.mangi.eta.agent.cloud.CloudToolAvailability
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentArtifactReference
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.data.repository.NexusArtifactAccess
import io.github.mangi.eta.data.repository.NexusArtifactLedger
import io.github.mangi.eta.data.repository.NexusArtifactMedia
import io.github.mangi.eta.data.repository.RunArtifactCapture
import io.github.mangi.eta.data.repository.SubjectFilesRepository
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Bridges cloud calls to the same trusted subject, file admission and output ledger as local tools. */
internal object AgentCloudTools {
    fun execute(context: Context, call: AgentModelClient.ToolCall, args: JSONObject,
        subjectType: String?, subjectId: String?, projectWorkspace: String?): AgentModelClient.ToolResult =
        runBlocking(Dispatchers.IO + RuntimeInvocationScope.coroutineContext()) {
            val controller = RuntimeInvocationScope.controller ?: throw CloudApiException("run_context_required")
            val identity = controller.executionIdentity ?: throw CloudApiException("run_identity_required")
            controller.throwIfStopped()
            val config = io.github.mangi.eta.data.repository.CloudToolsSettingsRepository.resolveRuntimeConfig(context)
            val operation = args.optString("operation")
            CloudToolAvailability.unavailableCode(call.name, operation, config)?.let { code ->
                return@runBlocking failed(code, when (code) {
                    "cloud_tools_disabled" -> "请在系统工具中配置并开启云端工具"
                    "cloud_operation_disabled" -> "此云端能力已停用"
                    "cloud_credentials_missing" -> "请先保存此云端能力的服务商凭据"
                    "cloud_configuration_invalid" -> "请检查此云端能力的服务地址与路由配置"
                    else -> "不支持的云端操作"
                })
            }
            val definition = io.github.mangi.eta.data.model.CloudCapabilities.definitions.firstOrNull { it.operation == operation }
                ?: return@runBlocking failed("unknown_cloud_operation", "不支持的云端操作")
            val workspace = TerminalPrivateStorage.workspace(context.filesDir).canonicalFile
            val project = projectWorkspace?.let { File(it).canonicalFile }
            val ledger = NexusArtifactLedger(context)
            // Only already admitted files belonging to this subject or explicit project inputs.
            val needsInput = operation in setOf("ocr", "file_upload", "vision", "baidu_ocr", "document_parse",
                "table_recognize", "image_edit", "video_generate", "video_understand", "speech_transcribe") &&
                listOf("file", "image", "path", "paths", "image_path", "image_paths", "video_path", "audio_path")
                    .any { args.has(it) }
            val admitted = if (!needsInput) emptyList() else if (subjectType in setOf("conversation", "meeting") && !subjectId.isNullOrBlank()) {
                SubjectFilesRepository.files(context, checkNotNull(subjectType), subjectId).filterNot { it.directory }.map { it.artifact }
            } else ledger.list(10_000).filter { it.runId == identity.runId.value }
            val access = NexusArtifactAccess(context)
            fun resolve(reference: String): CloudInputFile {
                require(reference.isNotBlank() && reference.length <= 4096 && '\u0000' !in reference) { "文件引用无效" }
                val record = admitted.firstOrNull { it.id == reference || it.path == reference } ?:
                    admitted.firstOrNull { access.readableUri(it.id) == reference }
                val source = record?.let { access.readableUri(it.id) ?: throw CloudApiException("cloud_input_unreadable") } ?: reference
                val uri = Uri.parse(source)
                if (uri.scheme == "content") {
                    check(record != null) { "云端上传只接受当前会话已导入的内容引用" }
                    val mime = record.mediaType ?: context.contentResolver.getType(uri) ?: "application/octet-stream"
                    return CloudInputFile(record.label, mime, record.sizeBytes) {
                        context.contentResolver.openInputStream(uri) ?: throw CloudApiException("cloud_input_unreadable")
                    }
                }
                require(uri.scheme == null || uri.scheme == "file") { "请导入本地文件后再上传，不能使用远程或私有引用" }
                val candidate = if (uri.scheme == "file") File(checkNotNull(uri.path)) else File(source).let {
                    if (it.isAbsolute) it else File(project ?: workspace, source)
                }
                val file = candidate.canonicalFile
                val runOutputs = File(workspace, "artifacts/${hash(identity.runId.value).take(24)}").canonicalFile
                val allowed = record != null || file.toPath().startsWith(runOutputs.toPath()) ||
                    project?.let { file.toPath().startsWith(it.toPath()) } == true
                require(allowed && file.isFile && file.canRead()) { "文件未导入当前会话/项目，或已不可读" }
                val label = record?.label ?: file.name
                val mime = NexusArtifactMedia.mimeType(record?.mediaType, label, file.path)
                return CloudInputFile(label, mime, file.length()) { file.inputStream() }
            }
            val artifacts = mutableListOf<AgentArtifactReference>()
            val folder = File(workspace, "artifacts/${hash(identity.runId.value).take(24)}/cloud").canonicalFile
            val cloudContext = CloudToolContext(context.applicationContext, controller, ::resolve, { name, mime, bytes ->
                controller.throwIfStopped()
                require(bytes.size.toLong() <= 100L * 1024 * 1024) { "云端成果超过100MiB" }
                check(folder.toPath().startsWith(workspace.toPath()) && (folder.isDirectory || folder.mkdirs()))
                val safeName = name.substringAfterLast('/').substringAfterLast('\\')
                    .replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").trim('.', ' ').take(120).ifBlank { "cloud-output" }
                val output = File(folder, "${UUID.randomUUID()}-$safeName")
                val temporary = File(folder, ".${UUID.randomUUID()}.partial")
                try {
                    FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
                    controller.throwIfStopped()
                    check(temporary.renameTo(output)) { "云端成果写入失败" }
                    val result = JSONObject().put("ok", true).put("path", output.path).put("label", safeName)
                        .put("bytes", output.length()).put("mime_type", mime)
                        .put("content_uri", FileProvider.getUriForFile(context, "${context.packageName}.files", output).toString())
                    val registered = JSONObject(RunArtifactCapture.fileResult(context, call.copy(name = "write_file"),
                        result.toString(), subjectType, subjectId))
                    val id = registered.optString("artifact_id").takeIf(String::isNotBlank)
                        ?: throw CloudApiException("cloud_output_registration_failed", outcomeUnknown = true)
                    artifacts += AgentArtifactReference(id, safeName, registered.optString("mime_type", mime))
                    registered
                } finally { temporary.delete() }
            }, scopeKey = "${subjectType ?: "run"}:${subjectId ?: identity.runId.value}:project:${identity.projectId.orEmpty()}")
            try {
                val result = if (call.name == "cloud_media") CloudMediaTools.executeConfigured(cloudContext, args, config)
                    else CloudDocumentTools.executeConfigured(cloudContext, args, config)
                // A recovered media result may refer to outputs created by a previous run.
                fun collect(value: Any?) {
                    when (value) {
                        is JSONObject -> {
                            val id = value.optString("artifact_id").takeIf(String::isNotBlank)
                            val record = id?.let(ledger::find)
                            if (record != null && ((record.subjectType == subjectType && record.subjectId == subjectId) || record.runId == identity.runId.value)) {
                                artifacts += AgentArtifactReference(record.id, record.label,
                                    record.mediaType ?: "application/octet-stream")
                            }
                            value.keys().forEach { collect(value.opt(it)) }
                        }
                        is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i))
                    }
                }
                collect(result)
                AgentModelClient.ToolResult(result.put("capability", definition.label).toString(), sensitive = true,
                    artifacts = artifacts.distinctBy { it.id })
            } catch (failure: CloudApiException) {
                AgentModelClient.ToolResult(failure.toJson().toString(), sensitive = true)
            }
        }

    private fun failed(code: String, message: String) = AgentModelClient.ToolResult(
        JSONObject().put("ok", false).put("code", code).put("message", message).toString(), sensitive = true)

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
