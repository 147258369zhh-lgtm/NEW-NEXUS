package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

/** One host and one 16dp outline for attachment, nested choices and session controls. */
@Composable
internal fun ComposerPopup(
    show: Boolean,
    anchorTopPx: Int,
    maxHeight: Dp,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val base = MiuixTheme.colorScheme
    val light = base.background.luminance() > 0.5f
    val colors = base.copy(surfaceContainer = if (light) Color(0xFFEEF0F5) else Color(0xFF292B32))
    val provider = remember(anchorTopPx) { InputPopupPositionProvider(anchorTopPx) }
    MiuixTheme(colors = colors, textStyles = MiuixTheme.textStyles) {
        WindowListPopup(
            show = show && anchorTopPx > 0,
            popupPositionProvider = provider,
            alignment = PopupPositionProvider.Align.TopStart,
            enableWindowDim = false,
            onDismissRequest = onDismiss,
            maxHeight = maxHeight,
            minWidth = ChatInputPopupWidth,
        ) {
            // The library itself paints/clips this same 16dp squircle; no rectangular
            // second background or differently rounded underlay can show through its corners.
            Box(Modifier.width(ChatInputPopupWidth).squircleBorder(
                0.5.dp, if (light) Color(0xFFCDD2DD).copy(alpha = 0.55f)
                else Color.White.copy(alpha = 0.10f), 16.dp)) { content() }
        }
    }
}

@Composable
internal fun composerPopupItemColor(): Color =
    if (MiuixTheme.colorScheme.background.luminance() > 0.5f) Color.White
    else Color(0xFF34363F)
