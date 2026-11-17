package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentArtifactReference
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.agent.web.WebHttpTransport
import io.github.mangi.eta.data.repository.NexusArtifactMedia
import io.github.mangi.eta.data.repository.RunArtifactCapture
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Explicit, anonymous download. Never shares browser sessions, credentials, cookies or model HTTP clients. */
internal object AgentMediaDownloadTool {
    private const val MAX_BYTES = 32L * 1024 * 1024
    private val client by lazy { OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build() }

    fun execute(context: Context, call: AgentModelClient.ToolCall, args: JSONObject,
        subjectType: String?, subjectId: String?): AgentModelClient.ToolResult {
        val controller = RuntimeInvocationScope.controller ?: error("RUN_CONTEXT_REQUIRED")
        val owner = controller.executionIdentity ?: error("RUN_CONTEXT_REQUIRED")
        var url = WebHttpTransport.validateUrl(args.getString("url"))
        val budget = args.optLong("max_bytes", 8L * 1024 * 1024)
        require(budget in 1..MAX_BYTES) { "max_bytes 必须在 1 字节到 32 MiB 之间" }
        val expectedHash = args.optString("expected_sha256").lowercase().takeIf(String::isNotBlank)
        require(expectedHash == null || expectedHash.matches(Regex("[a-f0-9]{64}"))) { "expected_sha256 无效" }
        val root = TerminalPrivateStorage.workspace(context.filesDir).canonicalFile
        val folder = File(root, "artifacts/${digest(owner.runId.value).take(24)}/downloads").canonicalFile
        check(folder.toPath().startsWith(root.toPath()) && (folder.isDirectory || folder.mkdirs())) { "DOWNLOAD_STORAGE_UNAVAILABLE" }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        for (redirect in 0..4) {
            controller.throwIfCancelled()
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "DOWNLOAD_TIMEOUT" }
            val request = client.newCall(Request.Builder().url(url).header("Accept", "*/*").get().build())
            request.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            val cancellation = controller.register(request::cancel)
            try {
                request.execute().use { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        check(redirect < 4) { "DOWNLOAD_REDIRECT_LIMIT" }
                        val next = response.header("Location")?.let(url::resolve) ?: error("DOWNLOAD_REDIRECT_INVALID")
                        check(url.scheme != "https" || next.scheme == "https") { "DOWNLOAD_INSECURE_REDIRECT" }
                        url = WebHttpTransport.validateUrl(next.toString())
                        return@use
                    }
                    check(response.isSuccessful) { "DOWNLOAD_HTTP_${response.code}" }
                    val declaredLength = response.body.contentLength()
                    check(declaredLength <= budget) { "DOWNLOAD_TOO_LARGE" }
                    val suggested = args.optString("name").ifBlank {
                        response.header("Content-Disposition")?.let { header ->
                            Regex("filename=\\\"?([^\\\";]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
                        }.orEmpty().ifBlank { url.pathSegments.lastOrNull().orEmpty() }
                    }
                    val name = suggested.substringAfterLast('/').substringAfterLast('\\')
                        .replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_").trim('.', ' ').take(120).ifBlank { "download" }
                    val file = File(folder, "${digest(call.id).take(24)}-${UUID.randomUUID()}-$name")
                    val partial = File(folder, ".${UUID.randomUUID()}.partial")
                    var saved = false
                    try {
                        val hash = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        FileOutputStream(partial).use { sink -> response.body.byteStream().use { source ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                controller.throwIfCancelled()
                                check(System.nanoTime() < deadline) { "DOWNLOAD_TIMEOUT" }
                                val read = source.read(buffer)
                                if (read < 0) break
                                count += read
                                check(count <= budget) { "DOWNLOAD_TOO_LARGE" }
                                hash.update(buffer, 0, read); sink.write(buffer, 0, read)
                            }
                            sink.fd.sync()
                        } }
                        check(count > 0 && (declaredLength < 0 || count == declaredLength)) { "DOWNLOAD_INCOMPLETE" }
                        val sha256 = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        check(expectedHash == null || expectedHash == sha256) { "DOWNLOAD_HASH_MISMATCH" }
                        controller.throwIfCancelled()
                        java.nio.file.Files.move(partial.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        val receipt = JSONObject().put("ok", true).put("path", file.absolutePath).put("label", name)
                            .put("mime_type", response.header("Content-Type") ?: "application/octet-stream").put("bytes", count)
                        val registered = JSONObject(RunArtifactCapture.fileResult(context, call.copy(name = "write_file"),
                            receipt.toString(), subjectType, subjectId))
                        val artifactId = registered.optString("artifact_id").takeIf(String::isNotBlank) ?: error("DOWNLOAD_REGISTRATION_FAILED")
                        val mime = NexusArtifactMedia.mimeType(registered.optString("mime_type"), name, file.path)
                        saved = true
                        return AgentModelClient.ToolResult(registered.put("sha256", sha256).toString(), sensitive = true,
                            artifacts = listOf(AgentArtifactReference(artifactId, name, mime)))
                    } finally {
                        partial.delete()
                        if (!saved) file.delete()
                    }
                }
            } finally { cancellation.close() }
        }
        error("DOWNLOAD_REDIRECT_LIMIT")
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
