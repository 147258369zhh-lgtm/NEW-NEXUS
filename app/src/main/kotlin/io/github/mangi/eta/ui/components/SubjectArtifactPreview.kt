package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.repository.NexusArtifactAccess
import io.github.mangi.eta.data.repository.NexusArtifactMedia
import io.github.mangi.eta.ui.screens.artwork.NexaWebArtworkActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.window.WindowDialog

/** Subject files share the same bounded viewers as message attachments. */
@Composable
internal fun SubjectArtifactPreview(artifactId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var source by remember(artifactId) { mutableStateOf<NexaAttachmentSource?>(null) }
    var error by remember(artifactId) { mutableStateOf<String?>(null) }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(artifactId) {
        try {
            source = withContext(Dispatchers.IO) {
                val access = NexusArtifactAccess(context)
                val record = access.record(artifactId) ?: error("文件记录已不存在")
                val uri = access.readableUri(artifactId) ?: error("文件已不可读取")
                val mime = NexusArtifactMedia.mimeType(record.mediaType, record.label, record.path)
                NexaAttachmentSource(uri, record.label, mime, record.sizeBytes, artifactId,
                    validate = { NexusArtifactAccess(context).readableUri(artifactId) == uri })
            }
            val loaded = source
            if (loaded != null && NexusArtifactMedia.kind(loaded.mime, loaded.label) == NexusArtifactMedia.Kind.HTML) {
                NexaWebArtworkActivity.openArtifact(context, artifactId)
                dismiss()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.localizedMessage ?: "文件暂不可预览" }
    }
    val loaded = source
    when {
        error != null || loaded == null -> WindowDialog(show = true, title = loaded?.label ?: "文件预览", onDismissRequest = onDismiss) {
            Text(error ?: "正在读取文件…", Modifier.padding(16.dp))
            NexaCompactAction("关闭", onClick = onDismiss)
        }
        NexusArtifactMedia.kind(loaded.mime, loaded.label) == NexusArtifactMedia.Kind.IMAGE ->
            NexusImagePreview(loaded.uri, loaded.label, show = true, onDismiss = onDismiss, artifactId = artifactId)
        NexusArtifactMedia.kind(loaded.mime, loaded.label) == NexusArtifactMedia.Kind.HTML -> Unit
        NexusArtifactMedia.kind(loaded.mime, loaded.label) in setOf(NexusArtifactMedia.Kind.AUDIO, NexusArtifactMedia.Kind.VIDEO) ->
            WindowDialog(show = true, title = loaded.label, onDismissRequest = onDismiss) {
                Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                    NexaAttachmentContent(loaded, Modifier.fillMaxWidth())
                    NexaCompactAction("关闭", onClick = onDismiss)
                }
            }
        else -> NexaBoundedFilePreview(loaded, onDismiss)
    }
}
