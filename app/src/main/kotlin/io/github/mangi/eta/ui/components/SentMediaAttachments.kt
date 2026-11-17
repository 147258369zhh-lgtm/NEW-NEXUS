package io.github.mangi.eta.ui.components

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.ui.compose.PlayerSurface
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.data.repository.NexusArtifactMedia
import io.github.mangi.eta.ui.markdown.MarkdownImage
import io.github.mangi.eta.ui.markdown.MarkdownImageBlock
import io.github.mangi.eta.ui.screens.artwork.NexaWebArtworkActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

private data class SentMediaAttachment(
    val reference: AgentFileReference,
    val file: File,
    val mime: String,
    val kind: NexusArtifactMedia.Kind,
) {
    val uri: String get() = Uri.fromFile(file).toString()
}

/** Shared by sent chat and meeting messages. File labels remain the fallback for unavailable files. */
@Composable
internal fun SentMediaAttachments(references: List<AgentFileReference>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val media by produceState<List<SentMediaAttachment>>(emptyList(), references) {
        value = withContext(Dispatchers.IO) {
            references.distinctBy { it.absolutePath }.mapNotNull { reference ->
                currentCoroutineContext().ensureActive()
                inspectSentMedia(context, reference)
            }
        }
    }
    val mediaPaths = media.mapTo(hashSetOf()) { it.reference.absolutePath }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        media.forEach { attachment -> key(attachment.reference.absolutePath) {
            when (attachment.kind) {
                NexusArtifactMedia.Kind.HTML -> SentArtworkAttachment(attachment)
                else -> NexaAttachmentContent(NexaAttachmentSource(attachment.uri, attachment.reference.displayName, attachment.mime, attachment.file.length(),
                    validate = { readableSentFile(context, attachment.reference) == attachment.file }))
            }
        } }
        val otherReferences = references.filterNot { it.absolutePath in mediaPaths }
        if (otherReferences.isNotEmpty()) SentFileReferenceFlow(otherReferences)
    }
}

/** No privileged backend is used. Canonical paths prevent a workspace link reaching app secrets. */
private fun readableSentFile(context: Context, reference: AgentFileReference): File? = runCatching {
    if (reference.kind != AgentFileReferenceKind.File || !reference.absolutePath.startsWith('/') ||
        reference.absolutePath.any(Char::isISOControl)) return null
    val file = File(reference.absolutePath).canonicalFile
    val workspace = TerminalPrivateStorage.workspace(context.filesDir).canonicalFile
    val privateRoots = (listOf(context.dataDir, context.createDeviceProtectedStorageContext().dataDir) +
        context.getExternalFilesDirs(null).mapNotNull { it?.parentFile } +
        context.externalCacheDirs.mapNotNull { it?.parentFile }).map { it.canonicalFile }
    val externalRoots = listOf(Environment.getExternalStorageDirectory().canonicalFile, File("/storage").canonicalFile)
    if (!sentMediaPathAllowed(file, workspace, privateRoots, externalRoots)) return null
    file.takeIf { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) && it.canRead() }
}.getOrNull()

internal fun sentMediaPathAllowed(file: File, workspace: File, privateRoots: List<File>, externalRoots: List<File>): Boolean {
    val path = file.toPath().normalize()
    val workspacePath = workspace.toPath().normalize()
    if (path.startsWith(workspacePath) && path != workspacePath) return true
    if (privateRoots.any { path.startsWith(it.toPath().normalize()) }) return false
    return externalRoots.any { path.startsWith(it.toPath().normalize()) && path != it.toPath().normalize() }
}

private fun inspectSentMedia(context: Context, reference: AgentFileReference): SentMediaAttachment? = runCatching {
    val file = readableSentFile(context, reference) ?: return null
    val header = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(4096) }
    val mime = NexusArtifactMedia.detectedMimeType(null, reference.displayName, file.path, header)
    val kind = NexusArtifactMedia.kind(mime, reference.displayName, file.path)
    SentMediaAttachment(reference, file, mime, kind)
}.getOrNull()


@Composable
private fun SentArtworkAttachment(attachment: SentMediaAttachment) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember(attachment.uri) { mutableStateOf(false) }
    val source = NexaAttachmentSource(attachment.uri, attachment.reference.displayName, attachment.mime, attachment.file.length(),
        validate = { readableSentFile(context, attachment.reference) == attachment.file })
    Column(Modifier.fillMaxWidth()) {
        NexaCompactAction(text = if (opening) "正在打开" else "预览作品", enabled = !opening, onClick = {
            scope.launch {
                opening = true
                try {
                    val text = withContext(Dispatchers.IO) {
                        source.input(context).use { input ->
                            val bytes = input.readNBytes(2 * 1024 * 1024 + 1)
                            require(bytes.size <= 2 * 1024 * 1024)
                            bytes.toString(Charsets.UTF_8)
                        }
                    }
                    NexaWebArtworkActivity.openSource(context, text, "html")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { Toast.makeText(context, "作品暂不可预览，可保存或外部打开", Toast.LENGTH_SHORT).show() }
                finally { opening = false }
            }
        })
        NexaFileCard(source)
    }
}
