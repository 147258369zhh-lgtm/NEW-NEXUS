package io.github.mangi.eta.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.zhh_lgtm.nexus.application.api.ArtifactRecord
import io.github.zhh_lgtm.nexus.application.api.ArtifactStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

internal class NexusArtifactAccess(context: Context) {
    private val appContext = context.applicationContext
    private val ledger = NexusArtifactLedger(appContext)

    fun readableUri(id: String): String? = runCatching {
        val record = ledger.find(id) ?: return null
        if (record.status != ArtifactStatus.VERIFIED) return null
        if (record.location == "content") record.path else localFile(id).toURI().toString()
    }.getOrNull()

    private fun localFile(id: String): File {
        val record = ledger.find(id) ?: error("ARTIFACT_NOT_FOUND")
        return localFile(record)
    }

    private fun localFile(record: ArtifactRecord): File {
        check(record.status == ArtifactStatus.VERIFIED) { "ARTIFACT_NOT_VERIFIED" }
        val file = when (record.location) {
            "workspace" -> {
                val root = TerminalPrivateStorage.workspace(appContext.filesDir).canonicalFile
                File(root, record.path).canonicalFile.also { check(it.toPath().startsWith(root.toPath())) { "ARTIFACT_OUTSIDE_WORKSPACE" } }
            }
            "device" -> File(record.path).canonicalFile
            else -> error("ARTIFACT_NOT_LOCAL")
        }
        check(file.isFile && file.canRead()) { "ARTIFACT_NOT_READABLE" }
        return file
    }

    fun record(id: String): ArtifactRecord? = runCatching { ledger.find(id) }.getOrNull()

    /** Reads only a verified text artifact, with a hard limit before allocating its body. */
    suspend fun readText(id: String, maxBytes: Int = 2 * 1024 * 1024): String = withContext(Dispatchers.IO) {
        require(maxBytes in 1..8 * 1024 * 1024)
        val record = ledger.find(id) ?: error("ARTIFACT_NOT_FOUND")
        check(record.status == ArtifactStatus.VERIFIED) { "ARTIFACT_NOT_VERIFIED" }
        val input = if (record.location == "content") appContext.contentResolver.openInputStream(Uri.parse(record.path))
            ?: error("ARTIFACT_NOT_READABLE") else localFile(id).inputStream()
        input.use { source ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                check(bytes.size().toLong() + count <= maxBytes) { "ARTIFACT_TEXT_TOO_LARGE" }
                bytes.write(buffer, 0, count)
            }
            val data = bytes.toByteArray()
            val mime = NexusArtifactMedia.detectedMimeType(record.mediaType, record.label, record.path, data.take(4096).toByteArray())
            check(mime.startsWith("text/") || mime == "application/xhtml+xml" || mime == "image/svg+xml" || mime == "application/json") { "ARTIFACT_NOT_TEXT" }
            data.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        }
    }

    /** Relative assets of a verified local HTML document, never an arbitrary private-file gateway. */
    fun localResource(id: String, relativePath: String): File? = runCatching {
        val record = ledger.find(id) ?: return null
        if (record.status != ArtifactStatus.VERIFIED || record.location == "content") return null
        val source = localFile(record)
        val mime = source.inputStream().use { NexusArtifactMedia.detectedMimeType(record.mediaType, record.label, record.path, it.readNBytes(4096)) }
        if (NexusArtifactMedia.kind(mime, record.label, record.path) != NexusArtifactMedia.Kind.HTML) return null
        val relative = relativePath.substringBefore('?').substringBefore('#')
        if (relative.isBlank() || relative.startsWith('/') || relative.startsWith('\\') ||
            ':' in relative || '\u0000' in relative || '\\' in relative) return null
        val root = source.parentFile?.canonicalFile ?: return null
        val candidate = File(root, relative).canonicalFile
        if (!candidate.toPath().startsWith(root.toPath())) return null
        // A document in the workspace root must not become a gateway to unrelated files.
        val allowed = ledger.list(10_000).filter { other ->
            other.status == ArtifactStatus.VERIFIED && other.runId == record.runId && other.location != "content" &&
                (other.subjectType == record.subjectType && other.subjectId == record.subjectId)
        }.mapNotNull { other -> runCatching { other to localFile(other) }.getOrNull() }
        allowed.firstOrNull { it.second == candidate }?.second ?: allowed.filter { (other, file) ->
            // Transport attachments have collision-safe physical names; their original labels
            // remain the names used by sibling HTML/CSS documents.
            file.parentFile == root && other.label == relative.substringAfterLast('/')
        }.singleOrNull()?.second
    }.getOrNull()

    /** Copies the verified artifact into the caller-selected SAF destination. */
    suspend fun exportTo(id: String, destination: Uri) = withContext(Dispatchers.IO) {
        val record = ledger.find(id) ?: error("ARTIFACT_NOT_FOUND")
        check(record.status == ArtifactStatus.VERIFIED) { "ARTIFACT_NOT_VERIFIED" }
        val output = appContext.contentResolver.openOutputStream(destination, "wt")
            ?: throw java.io.IOException("ARTIFACT_EXPORT_UNAVAILABLE")
        try {
            output.use { sink ->
                if (record.location == "content") {
                    val input = appContext.contentResolver.openInputStream(Uri.parse(record.path))
                        ?: throw java.io.IOException("ARTIFACT_NOT_READABLE")
                    input.use { source -> source.copyTo(sink, 64 * 1024) }
                } else {
                    localFile(id).inputStream().use { source -> source.copyTo(sink, 64 * 1024) }
                }
                sink.flush()
            }
        } catch (failure: Throwable) {
            runCatching { appContext.contentResolver.delete(destination, null, null) }
            throw failure
        }
    }

    /** Uses a stable provider URI and media type for clipboard/image sharing. */
    suspend fun shareUri(id: String): Pair<Uri, String> = withContext(Dispatchers.IO) {
        val record = ledger.find(id) ?: error("ARTIFACT_NOT_FOUND")
        val type = NexusArtifactMedia.mimeType(record.mediaType, record.label, record.path)
        prepareShare(id) to type
    }

    suspend fun prepareShare(id: String): Uri = withContext(Dispatchers.IO) {
        val record = ledger.find(id) ?: error("ARTIFACT_NOT_FOUND")
        check(record.status == ArtifactStatus.VERIFIED) { "ARTIFACT_NOT_VERIFIED" }
        if (record.location == "content") return@withContext Uri.parse(record.path)
        val source = localFile(id)
        check(source.length() <= 256L * 1024L * 1024L) { "ARTIFACT_SHARE_TOO_LARGE" }
        val directory = File(appContext.cacheDir, "workspace-share").apply { check(isDirectory || mkdirs()) }
        val expiry = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        directory.listFiles()?.filter { it.isFile && it.lastModified() < expiry }?.forEach { it.delete() }
        val name = source.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(180).ifBlank { "file" }
        val destination = File(directory, "${UUID.randomUUID()}-$name")
        try {
            source.inputStream().use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            } }
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", destination)
        } catch (failure: Throwable) { destination.delete(); throw failure }
    }

    suspend fun launch(id: String, share: Boolean = false) {
        val record = withContext(Dispatchers.IO) { ledger.find(id) } ?: error("ARTIFACT_NOT_FOUND")
        val uri = prepareShare(id)
        val type = NexusArtifactMedia.mimeType(record.mediaType, record.label, record.path)
        withContext(Dispatchers.Main) {
            if (!share && type == "application/vnd.android.package-archive" && !appContext.packageManager.canRequestPackageInstalls()) {
                appContext.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                android.widget.Toast.makeText(appContext, "允许此来源后，返回并再次打开交给系统安装器", android.widget.Toast.LENGTH_LONG).show()
                return@withContext
            }
            val intent = if (share) Intent(Intent.ACTION_SEND).setType(type)
                .putExtra(Intent.EXTRA_STREAM, uri) else Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = android.content.ClipData.newRawUri(record.label, uri)
            appContext.startActivity((if (share) Intent.createChooser(intent, null) else intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
