package io.github.mangi.eta.ui.components

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import io.github.mangi.eta.ui.markdown.MarkdownImage
import io.github.mangi.eta.ui.markdown.MarkdownImageBlock
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.mangi.eta.ui.markdown.MarkdownTone
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    onOpenToolTrace: (String) -> Unit = {},
    onOpenPlan: (String) -> Unit = {},
    onUserInput: (requestId: String, answer: String) -> Unit = { _, _ -> },
    onApproval: (approvalId: String, approved: Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    when (part) {
        is TextPart -> NexusPartText(part.text, markdown = false, modifier = modifier)
        is MarkdownPart -> NexusPartText(part.markdown, markdown = true, modifier = modifier)
        is MathPart -> NexusPartText("$$\n${part.latex}\n$$", markdown = true, modifier = modifier)
        is CodePart -> {
            val language = part.language?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9_+.-]{1,32}")) }.orEmpty()
            NexusPartText("```$language\n${part.code}\n```", markdown = true, modifier = modifier)
        }
        is ImagePart -> {
            val uri = resolveArtifact.resolve(part.artifactId)
            if (uri != null) {
                MarkdownImageBlock(MarkdownImage(0, uri, "图片 ${part.artifactId.value}"), modifier)
            } else NexusArtifactUnavailable("图片", modifier)
        }
        is AudioPart -> {
            val uri = resolveArtifact.resolve(part.artifactId)
            if (uri != null) NexusAudioBlock(uri, part.durationMs, modifier)
            else NexusArtifactUnavailable("音频", modifier)
        }
        is FilePart -> NexusActionCard(
            title = part.displayName,
            detail = "文件 ${part.artifactId.value}",
            action = "打开",
            onClick = { onOpenFile(part.artifactId) },
            modifier = modifier,
        )
        is ToolTracePart -> NexusActionCard(
            title = "运行轨迹",
            detail = part.itemId.value,
            action = "查看",
            onClick = { onOpenToolTrace(part.itemId.value) },
            modifier = modifier,
        )
        is PlanCardPart -> NexusActionCard(
            title = "执行计划",
            detail = "计划 ${part.planId.value}",
            action = "展开",
            onClick = { onOpenPlan(part.planId.value) },
            modifier = modifier,
        )
        is UserInputPart -> NexusUserInputBlock(part, onUserInput, modifier)
        is ApprovalPart -> NexusApprovalBlock(part, onApproval, modifier)
    }
}

@Composable
private fun NexusPartText(text: String, markdown: Boolean, modifier: Modifier) {
    if (markdown) {
        StaticMarkdown(content = text, tone = MarkdownTone.Answer, modifier = modifier.fillMaxWidth())
    } else {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NexusAudioBlock(uri: String, durationMs: Long?, modifier: Modifier) {
    val context = LocalContext.current
    var prepared by remember(uri) { mutableStateOf(false) }
    var initializing by remember(uri) { mutableStateOf(false) }
    var playing by remember(uri) { mutableStateOf(false) }
    var failure by remember(uri) { mutableStateOf<String?>(null) }
    val player = remember(uri) { MediaPlayer() }
    DisposableEffect(player) {
        onDispose {
            runCatching { player.stop() }
            player.release()
        }
    }
    NexusSurface(modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("语音", style = MiuixTheme.textStyles.body1)
                Text(
                    durationMs?.let { "${it / 1000.0}s" } ?: "音频产物",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            TextButton(
                text = when {
                    failure != null -> "不可用"
                    initializing -> "加载中"
                    !prepared -> "加载"
                    playing -> "暂停"
                    else -> "播放"
                },
                enabled = failure == null,
                onClick = {
                    if (!prepared) {
                        initializing = true
                        runCatching {
                            prepareMediaPlayer(
                                context = context,
                                player = player,
                                uri = uri,
                                onPrepared = {
                                    initializing = false
                                    prepared = true
                                    playing = true
                                    player.start()
                                },
                                onFailure = {
                                    initializing = false
                                    failure = it.message ?: "无法加载音频"
                                },
                                onCompleted = { playing = false },
                            )
                        }.onFailure {
                            initializing = false
                            failure = it.message ?: "无法加载音频"
                        }
                    } else if (playing) {
                        runCatching { player.pause() }
                        playing = false
                    } else {
                        runCatching { player.start() }
                        playing = true
                    }
                },
            )
        }
        failure?.let {
            Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
        }
    }
}

private fun prepareMediaPlayer(
    context: Context,
    player: MediaPlayer,
    uri: String,
    onPrepared: () -> Unit,
    onFailure: (Throwable) -> Unit,
    onCompleted: () -> Unit,
) {
    player.reset()
    player.setDataSource(context, Uri.parse(uri))
    player.setOnPreparedListener { onPrepared() }
    player.setOnCompletionListener {
        runCatching { it.seekTo(0) }
        onCompleted()
    }
    player.setOnErrorListener { _, what, extra ->
        onFailure(IllegalStateException("音频播放失败：$what/$extra"))
        true
    }
    player.prepareAsync()
}

@Composable
private fun NexusUserInputBlock(
    part: UserInputPart,
    onUserInput: (String, String) -> Unit,
    modifier: Modifier,
) {
    var answer by remember(part.partId) { mutableStateOf("") }
    NexusSurface(modifier) {
        Text(part.question, style = MiuixTheme.textStyles.body1)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            part.options.forEach { option ->
                TextButton(text = option, onClick = { onUserInput(part.requestId, option) })
            }
        }
        if (part.allowFreeText) {
            BasicTextField(
                value = answer,
                onValueChange = { answer = it.take(4_000) },
                textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                    .padding(10.dp),
            )
            TextButton(
                text = "提交",
                enabled = answer.isNotBlank(),
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
) {
    NexusSurface(modifier, danger = true) {
        Text("需要确认", style = MiuixTheme.textStyles.body1)
        Text(
            "审批 ${part.approvalId}。请进入详情核对影响范围和副作用。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(text = "拒绝", onClick = { onApproval(part.approvalId, false) })
            TextButton(text = "批准", onClick = { onApproval(part.approvalId, true) })
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
) {
    NexusSurface(modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.body1)
                Text(detail, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            TextButton(text = action, onClick = onClick)
        }
    }
}

@Composable
private fun NexusArtifactUnavailable(kind: String, modifier: Modifier) {
    NexusSurface(modifier) {
        Text("${kind}产物尚不可读取", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
            .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
            .border(0.5.dp, outline, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
