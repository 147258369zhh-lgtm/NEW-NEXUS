package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 弹窗共用操作区：常规宽度左右排列，窄屏或大字体纵向排列，保证文字和触控空间。
 *
 * @param confirmText 确认按钮文案。
 * @param onCancel 取消回调。
 * @param onConfirm 确认回调。
 * @param modifier 根修饰符。
 * @param cancelText 取消按钮文案。
 * @param cancelEnabled 取消按钮是否可用。
 * @param confirmEnabled 确认按钮是否可用。
 * @param destructive 确认是否为破坏性操作（使用 error 配色）。
 */
@Composable
fun MiuixDialogActions(
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    cancelText: String? = null,
    cancelEnabled: Boolean = true,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
) {
    val tokens = LocalEtaDesignTokens.current
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.34f), Color.Transparent),
                    ),
                ),
        )
        Spacer(Modifier.height(8.dp))
        val cancelButton: @Composable (Modifier) -> Unit = { buttonModifier ->
            TextButton(
                text = cancelText ?: stringResource(R.string.action_cancel),
                onClick = onCancel,
                enabled = cancelEnabled,
                modifier = buttonModifier.heightIn(min = 48.dp)
                    .then(if (tokens.skin.isGlass) Modifier.etaGlassSurface(cornerRadius = 16.dp) else Modifier),
            )
        }
        val confirmButton: @Composable (Modifier) -> Unit = { buttonModifier ->
            TextButton(
                text = confirmText,
                onClick = onConfirm,
                enabled = confirmEnabled,
                modifier = buttonModifier.heightIn(min = 48.dp)
                    .then(if (tokens.skin.isGlass) Modifier.etaGlassSurface(
                        accent = if (destructive) MiuixTheme.colorScheme.error else tokens.accent,
                        cornerRadius = 16.dp,
                    ) else Modifier),
                colors = if (destructive) {
                    ButtonDefaults.textButtonColorsPrimary(
                        color = MiuixTheme.colorScheme.error,
                        textColor = MiuixTheme.colorScheme.onError,
                    )
                } else ButtonDefaults.textButtonColorsPrimary(),
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.3f) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cancelButton(Modifier.fillMaxWidth())
                    confirmButton(Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tokens.dialogActionSpacing)) {
                    cancelButton(Modifier.weight(1f))
                    confirmButton(Modifier.weight(1f))
                }
            }
        }
    }
}
