package io.github.mangi.eta.ui.components

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Share
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.zhh_lgtm.nexus.kernel.api.ApprovalPart
import io.github.zhh_lgtm.nexus.kernel.api.ArtifactId
import io.github.zhh_lgtm.nexus.kernel.api.AudioPart
import io.github.zhh_lgtm.nexus.kernel.api.CodePart
import io.github.zhh_lgtm.nexus.kernel.api.ContentPart
import io.github.zhh_lgtm.nexus.kernel.api.FilePart
import io.github.zhh_lgtm.nexus.kernel.api.ImagePart
import io.github.zhh_lgtm.nexus.kernel.api.MarkdownPart
import io.github.zhh_lgtm.nexus.kernel.api.MathPart
import io.github.zhh_lgtm.nexus.kernel.api.PlanCardPart
import io.github.zhh_lgtm.nexus.kernel.api.TextPart
import io.github.zhh_lgtm.nexus.kernel.api.ToolTracePart
import io.github.zhh_lgtm.nexus.kernel.api.UserInputPart
import io.github.zhh_lgtm.nexus.kernel.api.VideoPart
import androidx.media3.ui.compose.PlayerSurface
import io.github.mangi.eta.ui.markdown.MarkdownImage
import io.github.mangi.eta.ui.markdown.MarkdownImageBlock
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.messageBodyTextStyle
import io.github.mangi.eta.ui.markdown.NexaCodePartContent
import io.github.mangi.eta.data.repository.NexusArtifactAccess
import io.github.mangi.eta.data.repository.NexusArtifactMedia
import io.github.mangi.eta.ui.screens.artwork.NexaWebArtworkActivity
import io.github.mangi.eta.ui.screens.artwork.NexaWebArtworkStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Resolves a kernel artifact to a URI readable by the current Host. */
internal fun interface NexusArtifactUriResolver {
    fun resolve(artifactId: ArtifactId): String?
}

/**
 * One adapter for every kernel ContentPart. Chat, meetings, DSH and remote Hosts can share this
 * renderer while retaining their own surrounding layout and action policy.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun NexusContentPartRenderer(
    part: ContentPart,
    resolveArtifact: NexusArtifactUriResolver,
    onOpenFile: (ArtifactId) -> Unit = {},
    onOpenToolTrace: ((String) -> Unit)? = null,
    onOpenPlan: ((String) -> Unit)? = null,
    onUserInput: (requestId: String, answer: String) -> Unit = { _, _ -> },
    onApproval: (approvalId: String, approved: Boolean) -> Unit = { _, _ -> },
    onOpenImage: ((String, String) -> Unit)? = null,
    actionsEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val artifactId = when (part) { is ImagePart -> part.artifactId; is AudioPart -> part.artifactId; is VideoPart -> part.artifactId; is FilePart -> part.artifactId; else -> null }
    val providedUri = artifactId?.let(resolveArtifact::resolve)
    val resolution = if (artifactId != null) {
        val resolved by produceState(true to providedUri, artifactId, providedUri) {
            value = false to (providedUri ?: withContext(Dispatchers.IO) { NexusArtifactAccess(context).readableUri(artifactId.value) })
        }
        resolved
    } else false to null
    val renderUri = providedUri ?: resolution.second
    when (part) {
        is TextPart -> NexusPartText(part.text, markdown = false, modifier = modifier)
        is MarkdownPart -> NexusPartText(part.markdown, markdown = true, modifier = modifier)
        is MathPart -> NexusPartText("$$\n${part.latex}\n$$", markdown = true, modifier = modifier)
        is CodePart -> {
            val language = part.language?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9_+.-]{1,32}")) }.orEmpty()
            NexaCodePartContent(part.code, language.takeIf(String::isNotBlank), modifier)
        }
        is ImagePart -> {
            val uri = renderUri
            if (uri != null) NexaAttachmentContent(rememberArtifactSource(uri, part.artifactId.value, part.mimeType, "图片 ${part.artifactId.value}"), modifier,
                onOpenImage = onOpenImage?.let { handler -> { handler(uri, "图片 ${part.artifactId.value}") } })
            else NexusArtifactUnavailable("图片", modifier, resolution.first)
        }
        is AudioPart -> {
            val uri = renderUri
            if (uri != null) NexusAudioBlock(uri, part.artifactId.value, part.mimeType, part.durationMs, modifier)
            else NexusArtifactUnavailable("音频", modifier, resolution.first)
        }
        is VideoPart -> {
            val uri = renderUri
            if (uri != null) NexusVideoBlock(uri, part.artifactId.value, part.mimeType, part.durationMs, part.width, part.height, modifier)
            else NexusArtifactUnavailable("视频", modifier, resolution.first)
        }
        is FilePart -> NexusFilePartBlock(part, NexusArtifactUriResolver { id -> if (id == artifactId) renderUri else resolveArtifact.resolve(id) }, onOpenFile, onOpenImage, modifier)
        is ToolTracePart -> {
            var expanded by remember(part.partId) { mutableStateOf(false) }
            Column(modifier) {
                NexusActionCard("运行轨迹", "", if (expanded) "收起" else "查看", {
                    if (onOpenToolTrace != null) onOpenToolTrace(part.itemId.value) else expanded = !expanded
                }, Modifier)
                if (expanded) NexusExecutionReferenceDetail(part.itemId.value, Modifier.padding(top = 8.dp))
            }
        }
        is PlanCardPart -> {
            var expanded by remember(part.partId) { mutableStateOf(false) }
            Column(modifier) {
                NexusActionCard("执行计划", "", if (expanded) "收起" else "展开", {
                    if (onOpenPlan != null) onOpenPlan(part.planId.value) else expanded = !expanded
                }, Modifier)
                if (expanded) {
                    val executionItemId = part.executionItemId
                    if (executionItemId != null) NexusExecutionReferenceDetail(executionItemId.value, Modifier.padding(top = 8.dp))
                    else Text("此历史计划未关联版本记录", style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        is UserInputPart -> NexusUserInputBlock(part, onUserInput, modifier, actionsEnabled)
        is ApprovalPart -> NexusApprovalBlock(part, onApproval, modifier, actionsEnabled)
    }
}

private data class ArtifactMediaInfo(val kind: NexusArtifactMedia.Kind, val mime: String)

/** Old histories can contain media as FilePart. Resolve metadata without rewriting that history. */
@Composable
private fun NexusFilePartBlock(part: FilePart, resolver: NexusArtifactUriResolver, onOpenFile: (ArtifactId) -> Unit,
    onOpenImage: ((String, String) -> Unit)?, modifier: Modifier) {
    val context = LocalContext.current
    val info by produceState<ArtifactMediaInfo?>(null, part.artifactId, part.displayName) {
        value = withContext(Dispatchers.IO) {
            val access = NexusArtifactAccess(context)
            val record = access.record(part.artifactId.value)
            val header = runCatching {
                val uri = access.readableUri(part.artifactId.value)?.let(Uri::parse) ?: return@runCatching ByteArray(0)
                val input = if (uri.scheme == "file") java.io.File(requireNotNull(uri.path)).inputStream() else context.contentResolver.openInputStream(uri)
                input?.use { it.readNBytes(4096) } ?: ByteArray(0)
            }.getOrDefault(ByteArray(0))
            val mime = NexusArtifactMedia.detectedMimeType(record?.mediaType, record?.label ?: part.displayName, record?.path, header)
            ArtifactMediaInfo(NexusArtifactMedia.kind(mime, part.displayName), mime)
        }
    }
    val type = info
    val uri = resolver.resolve(part.artifactId)
    when {
        type?.kind == NexusArtifactMedia.Kind.HTML -> Column(modifier) {
            NexusWebArtifactBlock(part.artifactId.value, part.displayName, Modifier)
            if (uri != null) NexaFileCard(rememberArtifactSource(uri, part.artifactId.value, type.mime, part.displayName))
        }
        type != null && uri != null -> NexaAttachmentContent(rememberArtifactSource(uri, part.artifactId.value, type.mime, part.displayName), modifier,
            onOpenImage = onOpenImage?.let { handler -> { handler(uri, part.displayName) } })
        type == null -> NexusArtifactUnavailable("文件", modifier, loading = true)
        else -> NexusActionCard(part.displayName, "文件暂不可读取", "打开", { onOpenFile(part.artifactId) }, modifier)
    }
}

@Composable
private fun NexusWebArtifactBlock(artifactId: String, label: String, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember(artifactId) { mutableStateOf(false) }
    var refresh by remember(artifactId) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val thumbnail by produceState<String?>(null, artifactId, refresh) {
        value = withContext(Dispatchers.IO) { NexaWebArtworkStore.thumbnail(context, artifactId)?.let { "${it.toURI()}?v=${it.lastModified()}" } }
    }
    val open = {
        if (!opening) scope.launch {
            opening = true
            try { NexaWebArtworkActivity.openArtifact(context, artifactId) }
            catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; Toast.makeText(context, "作品暂不可预览：${failure.message.orEmpty()}", Toast.LENGTH_SHORT).show() }
            finally { opening = false }
        }
        Unit
    }
    NexusSurface(modifier) {
        thumbnail?.let { MarkdownImageBlock(MarkdownImage(0, it, label), Modifier.fillMaxWidth(), open) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MiuixTheme.textStyles.body1)
                Text("交互作品", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            NexaCompactAction(text = if (opening) "正在打开" else "预览", enabled = !opening, onClick = open)
        }
    }
}

@Composable
private fun NexusVideoBlock(
    uri: String, artifactId: String, mimeType: String, durationMs: Long?, width: Int?, height: Int?, modifier: Modifier,
) {
    NexaAttachmentContent(rememberArtifactSource(uri, artifactId, mimeType, "视频"), modifier, durationMs, width, height)
}

@Composable
private fun NexusPartText(text: String, markdown: Boolean, modifier: Modifier) {
    if (markdown && NexaWebArtworkStore.accepts(null, text)) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var opening by remember { mutableStateOf(false) }
        NexusActionCard("网页作品", "", if (opening) "正在打开" else "预览", {
            if (!opening) scope.launch {
                opening = true
                try { NexaWebArtworkActivity.openSource(context, text, null) }
                catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; Toast.makeText(context, "作品暂不可预览", Toast.LENGTH_SHORT).show() }
                finally { opening = false }
            }
        }, modifier, compactAction = true)
    } else if (markdown) {
        SelectionContainer { StaticMarkdown(content = text, tone = MarkdownTone.Answer, modifier = modifier.fillMaxWidth()) }
    } else {
        SelectionContainer { Text(
            text = text,
            style = messageBodyTextStyle(),
            color = MiuixTheme.colorScheme.onSurface,
            modifier = modifier.fillMaxWidth(),
        ) }
    }
}

@Composable
private fun NexusAudioBlock(uri: String, artifactId: String, mimeType: String, durationMs: Long?, modifier: Modifier) {
    NexaAttachmentContent(rememberArtifactSource(uri, artifactId, mimeType, "音频"), modifier, durationMs)
}

private fun mediaFileName(kind: String, mimeType: String, artifactId: String): String {
    val extension = when (mimeType.lowercase()) {
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/mpeg" -> "mp3"
        "audio/ogg" -> "ogg"
        "audio/mp4" -> "m4a"
        "video/x-matroska" -> "mkv"
        "video/quicktime" -> "mov"
        else -> mimeType.substringAfter('/', "bin").substringBefore('+').take(10)
    }
    val stem = artifactId.filter(Char::isLetterOrDigit).takeLast(12).ifBlank { "media" }
    return "$kind-$stem.$extension"
}

@Composable
private fun NexusUserInputBlock(
    part: UserInputPart,
    onUserInput: (String, String) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    if (part.questions.isNotEmpty()) {
        NexusSurface(modifier) {
            StructuredUserInputCard(part, onSubmit = { onUserInput(part.requestId, it) }, enabled = enabled)
        }
        return
    }
    var answer by remember(part.partId) { mutableStateOf("") }
    NexusSurface(modifier) {
        Text(part.question, style = MiuixTheme.textStyles.body1)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            part.options.forEach { option ->
                TextButton(text = option, enabled = enabled, onClick = { onUserInput(part.requestId, option) })
            }
        }
        if (part.allowFreeText) {
            BasicTextField(
                value = answer,
                onValueChange = { answer = it.take(4_000) },
                enabled = enabled,
                textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                    .padding(10.dp),
            )
            TextButton(
                text = "提交",
                enabled = enabled && answer.isNotBlank(),
                onClick = { onUserInput(part.requestId, answer.trim()) },
            )
        }
    }
}

@Composable
private fun NexusApprovalBlock(
    part: ApprovalPart,
    onApproval: (String, Boolean) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    NexusSurface(modifier, danger = true) {
        Text("需要确认", style = MiuixTheme.textStyles.body1)
        Text(
            "审批 ${part.approvalId}。请进入详情核对影响范围和副作用。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(text = "拒绝", enabled = enabled, onClick = { onApproval(part.approvalId, false) })
            TextButton(text = "批准", enabled = enabled, onClick = { onApproval(part.approvalId, true) })
        }
    }
}

@Composable
private fun NexusActionCard(
    title: String,
    detail: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier,
    compactAction: Boolean = false,
) {
    NexusSurface(modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.body1)
                if (detail.isNotBlank()) Text(detail, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (compactAction) NexaCompactAction(text = action, onClick = onClick)
            else TextButton(text = action, onClick = onClick)
        }
    }
}

@Composable
private fun NexusArtifactUnavailable(kind: String, modifier: Modifier, loading: Boolean = false) {
    NexusSurface(modifier) {
        Text(if (loading) "正在加载${kind}" else "${kind}产物尚不可读取", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun NexusSurface(
    modifier: Modifier,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val outline = if (danger) MiuixTheme.colorScheme.error.copy(alpha = 0.55f)
    else MiuixTheme.colorScheme.outline.copy(alpha = 0.32f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .border(0.5.dp, outline, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
