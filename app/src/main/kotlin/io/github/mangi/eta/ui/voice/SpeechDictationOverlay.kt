package io.github.mangi.eta.ui.voice

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.mangi.eta.agent.voice.EtaSpeechPhase
import io.github.mangi.eta.agent.voice.NexaVoiceOrb
import io.github.mangi.eta.agent.voice.SpeechInputController
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A transparent, nonmodal presence anchored above the real editor; no duplicate transcript. */
@Composable
internal fun SpeechDictationOverlay(
    controller: SpeechInputController,
    visible: Boolean,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    onFinish: () -> Unit,
) {
    if (!visible) return
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val request = rememberSpeechPermission {
        io.github.mangi.eta.agent.dsh.DshMediaController.stopForInput(context)
        onStart()
        controller.start()
    }
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxWidth()) {
        Popup(
            alignment = Alignment.BottomCenter,
            offset = IntOffset(0, -gap),
            properties = PopupProperties(focusable = false),
        ) {
            Box(Modifier.width(280.dp).height(154.dp)) {
                DictationWaveform(
                    active = state.active,
                    level = { controller.state.value.level },
                    samples = { controller.state.value.waveform },
                    modifier = Modifier.matchParentSize(),
                )
                    // Pause finishes this ASR segment and preserves the orb. Resuming opens a
                    // new segment, so no idle microphone or remote stream is kept alive.
                    DictationOrbAction(
                        if (state.active) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.active) "暂停听写" else "继续听写",
                        enabled = !state.active || state.phase == EtaSpeechPhase.LISTENING,
                        onClick = { if (state.active) controller.finish() else request() },
                        modifier = Modifier.align(Alignment.Center),
                        tint = Color.White,
                    )
                DictationOrbAction(Icons.Rounded.Check, "完成听写", onClick = onFinish,
                    modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun DictationOrbAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.primary,
) {
    IconButton(
        onClick = onClick, enabled = enabled, minWidth = 48.dp, minHeight = 48.dp,
        backgroundColor = Color.Transparent,
        modifier = modifier.clip(CircleShape),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp),
            tint = tint.copy(alpha = if (enabled) 0.95f else 0.35f))
    }
}
