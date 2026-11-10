package io.github.mangi.eta.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.data.repository.ConversationAttachmentDrafts
import io.github.mangi.eta.data.repository.ConversationAttachments
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import java.util.UUID
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Stable
internal class ConversationAttachmentEditor(
    private val context: android.content.Context,
    subject: ConversationSubject,
    private val scope: CoroutineScope,
) {
    private val store = ConversationAttachmentDrafts.get(context)
    private val lease = store.open(subject)
    var value by mutableStateOf(ConversationAttachments())
        private set
    var ready by mutableStateOf(false)
        private set
    var pending by mutableIntStateOf(0)
        private set
    var error by mutableIntStateOf(0)
        private set
    private var sequence = 0L
    private var active = true

    suspend fun restore() {
        try {
            val restored = store.load(lease)
            if (active && store.isValid(lease)) { value = restored; ready = true; error = 0 }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = R.string.attachment_draft_load_failed }
    }

    fun dispose() { active = false }

    private fun update(next: ConversationAttachments) {
        if (!active || !ready || !store.isValid(lease)) return
        value = next
        val version = ++sequence
        store.save(lease, next).invokeOnCompletion { failure -> scope.launch(Dispatchers.Main.immediate) {
            if (active && version == sequence) error = if (failure == null) 0 else R.string.attachment_draft_save_failed
        } }
    }

    fun retry() { if (!ready) scope.launch { restore() } else update(value) }
    fun removeImage(id: String) = update(value.copy(images = value.images.filterNot { it.id == id }))
    fun removeFile(id: String) = update(value.copy(files = value.files.filterNot { it.id == id }))
    fun clearSubmitted(submitted: ConversationAttachments) {
        // Picker completion or a later edit must not be cleared by the old submission receipt.
        update(value.copy(images = value.images.filterNot { image -> submitted.images.any { it.id == image.id } },
            files = value.files.filterNot { file -> submitted.files.any { it.id == file.id } }))
    }

    private fun import(resolve: suspend () -> ConversationAttachments) {
        if (!active || !ready) return
        pending++
        scope.launch {
            try {
                val addition = withContext(Dispatchers.IO) { store.materialize(resolve()) }
                if (active && store.isValid(lease)) update(ConversationAttachments(
                    (value.images + addition.images).distinctBy { it.id },
                    (value.files + addition.files).distinctBy { it.reference.absolutePath },
                ).also { require(it.images.size <= 24 && it.files.size <= 64) })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (active) error = R.string.attachment_import_failed }
            finally { pending-- }
        }
    }

    fun image(uri: String) = import {
        try {
            val image = AgentImageCodec.fromReference(context, uri, "meeting_attach") ?: error("Unreadable image")
            val preview = AgentImageCodec.previewFromReference(context, image) ?: image
            ConversationAttachments(images = listOf(PendingImageUi("img-${UUID.randomUUID()}", image.reference, preview.reference, image.mimeType)))
        } finally {
            if (Uri.parse(uri).scheme == "content") runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    fun files(uris: List<String>) = references {
        val gateway = AgentFileReferenceGateway(context, AndroidAgentLogger)
        uris.map { gateway.resolveDocumentUri(Uri.parse(it), AgentFileReferenceKind.File) }
    }
    fun folder(uri: String) = references { listOf(AgentFileReferenceGateway(context, AndroidAgentLogger)
        .resolveDocumentUri(Uri.parse(uri), AgentFileReferenceKind.Directory)) }
    fun path(path: String) = references { listOf(AgentFileReferenceGateway(AndroidAgentLogger).resolveAbsolutePath(path)) }
    private fun references(resolve: () -> List<AgentFileReferenceGateway.Resolution>) = import {
        val references = resolve()
        // A mixed selection is kept intact on failure rather than silently dropping a file.
        require(references.all { it is AgentFileReferenceGateway.Resolution.Success })
        ConversationAttachments(files = references.map { resolution ->
            PendingFileReferenceUi("file-${UUID.randomUUID()}", (resolution as AgentFileReferenceGateway.Resolution.Success).reference)
        })
    }
}

@Composable
internal fun rememberConversationAttachments(subject: ConversationSubject): ConversationAttachmentEditor {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val editor = remember(context, subject) { ConversationAttachmentEditor(context, subject, scope) }
    LaunchedEffect(editor) { editor.restore() }
    DisposableEffect(editor) { onDispose { editor.dispose() } }
    return editor
}

@Composable
internal fun ConversationAttachmentFeedback(editor: ConversationAttachmentEditor, modifier: Modifier = Modifier) {
    val text = when {
        editor.error != 0 -> editor.error
        !editor.ready -> R.string.attachment_draft_wait
        editor.pending > 0 -> R.string.attachment_importing
        else -> return
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(text), modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote2,
            color = if (editor.error != 0) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (editor.error != 0) TextButton(stringResource(R.string.conversation_draft_retry), onClick = editor::retry,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp))
    }
}
