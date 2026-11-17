package io.github.mangi.eta.ui.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.EtaSpeechPhase
import io.github.mangi.eta.agent.voice.ReadAloudPlaybackService
import io.github.mangi.eta.agent.voice.SpeechInputController
import io.github.mangi.eta.agent.voice.SpeechPlaybackController
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.components.AnswerActionSize
import io.github.mangi.eta.ui.components.ChatInputActionIconSize
import io.github.mangi.eta.ui.components.ChatInputActionSize
import io.github.mangi.eta.ui.components.StatusError
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val ACTION_SPEECH_SETTINGS = "io.github.mangi.eta.OPEN_SPEECH_SETTINGS"

internal fun openSpeechSettings(context: Context) {
    context.startActivity(
        Intent(context, MainActivity::class.java).setAction(ACTION_SPEECH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    )
}

/** 页面不可见或销毁时终止音频任务。 */
@Composable
internal fun SpeechLifecycle(onStop: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val stop by rememberUpdatedState(onStop)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
}

@Composable
internal fun rememberSpeechInput(onPreview: (String) -> Unit = {}, onResult: (String) -> Unit): SpeechInputController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val result by rememberUpdatedState(onResult)
    val preview by rememberUpdatedState(onPreview)
    val controller = remember(context, scope) {
        SpeechInputController(context, scope, onPreview = { preview(it) }, onResult = { result(it) })
    }
    SpeechLifecycle { controller.cancel() }
    return controller
}

@Composable
internal fun rememberSpeechPermission(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val deniedMessage = stringResource(R.string.speech_mic_permission)
    val action by rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) action() else Toast.makeText(context, deniedMessage, Toast.LENGTH_SHORT).show()
    }
    return {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** 输入栏听写入口，沿用输入栏统一的操作尺寸；录音中点击完成识别并插入文字。 */
@Composable
internal fun SpeechDictationButton(
    controller: SpeechInputController,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onStart: () -> Unit = {},
    onFinish: () -> Unit = controller::finish,
) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val request = rememberSpeechPermission { if (enabled) { onStart(); controller.start() } }
    IconButton(
        onClick = { if (state.active) onFinish() else { io.github.mangi.eta.agent.dsh.DshMediaController.stopForInput(context); request() } },
        enabled = enabled && state.phase != EtaSpeechPhase.RECOGNIZING,
        minWidth = ChatInputActionSize,
        minHeight = ChatInputActionSize,
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (state.active) Icons.Rounded.Check else Icons.Rounded.Mic,
            contentDescription = stringResource(
                if (state.active) R.string.speech_dictation_finish else R.string.speech_dictation,
            ),
            modifier = Modifier.size(ChatInputActionIconSize),
            tint = if (state.active) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurface
            },
        )
    }
}

/** 输入栏上方的听写状态条：录音中显示电平与完成/取消，错误给出设置或关闭出口。 */
@Composable
internal fun SpeechInputFeedback(controller: SpeechInputController, showActiveOverlay: Boolean = true) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    if (state.active && showActiveOverlay) {
        Dialog(
            onDismissRequest = controller::cancel,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            VoiceInputOverlay(
                preview = state.preview.ifBlank { state.progress },
                level = { controller.state.value.level },
                onCancel = controller::cancel,
                onFinish = controller::finish,
            )
        }
        return
    }
    AnimatedVisibility(
        visible = state.error != null,
        enter = fadeIn(tween(160)) + expandVertically(tween(160)),
        exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .heightIn(min = 38.dp)
                .squircleSurface(
                    color = MiuixTheme.colorScheme.surfaceContainer,
                    cornerRadius = 19.dp,
                )
                .squircleBorder(
                    width = 0.5.dp,
                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                    cornerRadius = 19.dp,
                )
                .padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.error != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(StatusError),
                )
            }
            Text(
                text = state.error ?: state.preview.ifBlank { state.progress },
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = if (state.error != null) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.configureAvailable) {
                SpeechFeedbackAction(
                    text = stringResource(R.string.speech_open_settings),
                    primary = true,
                    onClick = { openSpeechSettings(context) },
                )
            }
            IconButton(onClick = controller::cancel, minWidth = 28.dp, minHeight = 28.dp) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    modifier = Modifier.size(15.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun VoiceInputOverlay(
    preview: String,
    level: () -> Float,
    onCancel: () -> Unit,
    onFinish: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val accent = colors.primary
    val pulse = rememberInfiniteTransition(label = "voice_pulse")
    val phase by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "voice_phase",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.42f)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 28.dp)
                .padding(bottom = 120.dp)
                .widthIn(max = 330.dp)
                .dropShadow(
                    shape = RoundedCornerShape(26.dp),
                    shadow = Shadow(radius = 24.dp, color = accent, alpha = 0.20f),
                )
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            colors.surfaceContainerHigh.copy(alpha = 0.96f),
                            colors.secondaryContainer.copy(alpha = 0.90f),
                            colors.surfaceContainer.copy(alpha = 0.96f),
                        )
                    )
                )
                .squircleBorder(
                    0.7.dp,
                    accent.copy(alpha = 0.34f),
                    26.dp,
                )
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(accent, CircleShape),
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = "实时语音",
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "正在聆听",
                    color = accent,
                    fontSize = 12.sp,
                )
            }
            VoiceInputOrb(
                level = level,
                phase = phase,
                accent = accent,
                modifier = Modifier.size(126.dp),
            )
            Text(
                text = preview.ifBlank { "请开始说话…" },
                color = colors.onSurface.copy(alpha = if (preview.isBlank()) 0.58f else 0.9f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VoiceOverlayAction(
                    label = stringResource(R.string.action_cancel),
                    icon = Icons.Rounded.Close,
                    onClick = onCancel,
                )
                VoiceOverlayAction(
                    label = stringResource(R.string.action_done),
                    icon = Icons.Rounded.Check,
                    primary = true,
                    onClick = onFinish,
                )
            }
        }
    }
}

@Composable
private fun VoiceInputOrb(
    level: () -> Float,
    phase: Float,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val highlight = MiuixTheme.colorScheme.onPrimary
    Canvas(modifier = modifier) {
        val liveLevel = sqrt(level().coerceIn(0f, 1f))
        val radius = size.minDimension / 2f
        for (index in 0..3) {
            val progress = (phase + index * 0.25f) % 1f
            drawCircle(
                color = accent.copy(alpha = (1f - progress) * 0.20f),
                radius = radius * (0.42f + progress * 0.54f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4.dp.toPx()),
            )
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    highlight.copy(alpha = 0.95f),
                    accent.copy(alpha = 0.58f),
                    Color.Transparent,
                ),
                center = center,
                radius = radius * (0.60f + liveLevel * 0.22f),
            ),
            radius = radius * (0.54f + liveLevel * 0.18f),
        )
        val barCount = 9
        val barWidth = 3.2.dp.toPx()
        val gap = 4.2.dp.toPx()
        val totalWidth = barCount * barWidth + (barCount - 1) * gap
        val startX = center.x - totalWidth / 2f
        repeat(barCount) { index ->
            val wave = (sin((phase * PI * 2f) + index * 0.68f).toFloat() + 1f) / 2f
            val barHeight = 8.dp.toPx() + (24.dp.toPx() * (0.32f + liveLevel * 0.72f) * wave)
            drawRoundRect(
                color = highlight.copy(alpha = 0.90f),
                topLeft = Offset(startX + index * (barWidth + gap), center.y - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
private fun VoiceOverlayAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(
                if (primary) colors.primary.copy(alpha = 0.22f)
                else colors.surfaceContainerHigh.copy(alpha = 0.72f)
            )
            .squircleBorder(
                0.5.dp,
                if (primary) colors.primary.copy(alpha = 0.52f)
                else colors.outline.copy(alpha = 0.24f),
                18.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (primary) colors.primary else colors.onSurface.copy(alpha = 0.76f),
        )
        Text(
            text = label,
            color = if (primary) colors.primary else colors.onSurface.copy(alpha = 0.78f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 录音电平波形。录音端约 5 次/秒上报 RMS，这里逐帧做包络插值：上升快、回落慢，
 * 说话时跳动跟手，停顿时平滑收回。电平与包络只在绘制阶段读取，帧循环不触发重组。
 */
@Composable
private fun SpeechLevelBars(level: () -> Float) {
    val color = MiuixTheme.colorScheme.primary
    val envelope = remember { FloatArray(LEVEL_BAR_WEIGHTS.size) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame = it }
    }
    Canvas(modifier = Modifier.size(width = 22.dp, height = 16.dp)) {
        frame
        // RMS 与听感近似对数关系，开方后小声说话也能看到明显起伏。
        val input = sqrt(level().coerceIn(0f, 1f))
        val barWidth = 2.5.dp.toPx()
        val gap = (size.width - barWidth * envelope.size) / (envelope.size - 1)
        val minHeight = barWidth
        envelope.indices.forEach { index ->
            val jitter = 1f + (Random.nextFloat() * 2f - 1f) * LEVEL_BAR_JITTER
            val target = (input * LEVEL_BAR_WEIGHTS[index] * jitter).coerceIn(0f, 1f)
            val current = envelope[index]
            envelope[index] = current + (target - current) * if (target > current) LEVEL_ATTACK else LEVEL_RELEASE
            val height = minHeight + (size.height - minHeight) * envelope[index]
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

private val LEVEL_BAR_WEIGHTS = floatArrayOf(0.5f, 0.8f, 1f, 0.75f, 0.55f)
private const val LEVEL_ATTACK = 0.4f
private const val LEVEL_RELEASE = 0.15f
private const val LEVEL_BAR_JITTER = 0.04f

@Composable
private fun SpeechFeedbackAction(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val tint = if (primary) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val background = if (primary) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = tint,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

internal val LocalSpeechPlayback = staticCompositionLocalOf<SpeechPlaybackController?> { null }
internal val LocalSpeechPlaybackEnabled = compositionLocalOf { false }

/**
 * 聊天页自动朗读：一轮回答在本页流式生成并正常结束后，朗读这一轮的最终回答。
 * 只认在本页看到过流式生成的回答，切换会话、打开历史或停止、失败的回合都不会补读。
 */
@Composable
internal fun SpeechAutoReadAloud(messages: List<AgentChatMessageUi>, isStreaming: Boolean) {
    val controller = LocalSpeechPlayback.current ?: return
    val settings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
    val enabled = settings?.let { it.chatAutoSpeak && it.tts != TtsProvider.NONE } == true
    var watchedId by remember { mutableStateOf<String?>(null) }
    val currentMessages by rememberUpdatedState(messages)
    LaunchedEffect(messages) {
        messages.lastOrNull { it is AgentMessageUi && it.isStreaming }?.let { watchedId = it.id }
        // 被观察的回答不在当前列表里，说明已经切到别的会话。
        if (watchedId != null && messages.none { it.id == watchedId }) watchedId = null
    }
    LaunchedEffect(isStreaming) {
        if (isStreaming) return@LaunchedEffect
        val watched = watchedId ?: return@LaunchedEffect
        watchedId = null
        if (!enabled) return@LaunchedEffect
        val list = currentMessages
        if (list.none { it.id == watched }) return@LaunchedEffect
        val answerIndex = list.indexOfLast { it is AgentMessageUi }
        val answer = list.getOrNull(answerIndex) as? AgentMessageUi ?: return@LaunchedEffect
        val interrupted = list.drop(answerIndex + 1).any {
            it is SystemNoticeMessageUi && it.code in setOf(SystemNoticeCode.Stopped, SystemNoticeCode.RuntimeFailed)
        }
        if (!interrupted && answer.content.isNotBlank()) controller.speak(answer.id, answer.content)
    }
    // 关闭自动朗读视同“别再读了”，正在进行的朗读一并停止。
    var wasEnabled by remember { mutableStateOf(enabled) }
    LaunchedEffect(enabled) {
        if (wasEnabled && !enabled) controller.stop()
        wasEnabled = enabled
    }
}

/**
 * 朗读控制器随聊天页存在；未配置播报服务时朗读入口整体隐藏。
 * 退到后台或锁屏时继续朗读（出声期间由前台播放服务保活），离开聊天页才停止。
 */
@Composable
internal fun SpeechPlaybackHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember {
        val appContext = context.applicationContext
        lateinit var created: SpeechPlaybackController
        var foreground: java.io.Closeable? = null
        created = SpeechPlaybackController(
            context = context,
            scope = scope,
            beforePlayback = { foreground = ReadAloudPlaybackService.start(appContext) { created.stop() } },
            afterPlayback = { foreground?.close(); foreground = null },
            interruptOnScreenOff = false,
        )
        created
    }
    val settings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
    DisposableEffect(controller) { onDispose { controller.stop() } }
    SpeechPlaybackErrors(controller)
    CompositionLocalProvider(
        LocalSpeechPlayback provides controller,
        LocalSpeechPlaybackEnabled provides (settings != null && settings?.tts != TtsProvider.NONE),
        content = content,
    )
}

@Composable
internal fun SpeechPlaybackErrors(controller: SpeechPlaybackController) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    LaunchedEffect(state.error) {
        state.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
}

/** 朗读入口与相邻的消息操作共享同一套尺寸与弱化配色，播放中转为停止。 */
@Composable
internal fun SpeechReadAloudButton(messageId: String, text: String) {
    val controller = LocalSpeechPlayback.current ?: return
    if (!LocalSpeechPlaybackEnabled.current) return
    val state by controller.state.collectAsState()
    val active = state.messageId == messageId
    IconButton(
        onClick = { if (active) controller.stop() else controller.speak(messageId, text) },
        minWidth = AnswerActionSize,
        minHeight = AnswerActionSize,
    ) {
        Icon(
            imageVector = if (active) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = stringResource(
                if (active) R.string.speech_read_stop else R.string.speech_read_aloud,
            ),
            modifier = Modifier.size(if (active) 14.dp else 16.dp),
            tint = if (active) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f)
            },
        )
    }
}
