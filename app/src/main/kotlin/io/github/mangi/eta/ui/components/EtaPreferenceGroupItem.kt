package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 长列表保持逐项组合，仅对分组首尾绘制 Miuix Card 标准圆角，避免将大量动态行塞入单个 Card。 */
@Composable
internal fun EtaPreferenceGroupItem(
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val settings = LocalNexusSettingsVisuals.current
    val glass = LocalEtaDesignTokens.current.skin.isGlass
    val radius = CardDefaults.CornerRadius
    val surfaceColor = if (settings) settingsCardColor() else if (glass) colors.secondaryVariant.copy(alpha = 0.88f) else colors.surfaceContainer
    val surface = if (isFirst || isLast) {
        Modifier.squircleSurface(
            color = surfaceColor,
            topStart = if (isFirst) radius else 0.dp,
            topEnd = if (isFirst) radius else 0.dp,
            bottomStart = if (isLast) radius else 0.dp,
            bottomEnd = if (isLast) radius else 0.dp,
        )
    } else {
        Modifier.background(surfaceColor)
    }
    CompositionLocalProvider(LocalContentColor provides colors.onSurfaceContainer) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = if (settings) 16.dp else 12.dp)
                .padding(bottom = if (isLast) { if (settings) 8.dp else 12.dp } else 0.dp)
                .then(surface)
                .drawBehind {
                    if (settings && !isFirst) drawLine(colors.outline,
                        Offset(48.dp.toPx(), 0f), Offset(size.width - 16.dp.toPx(), 0f), 0.5.dp.toPx())
                },
        ) {
            content()
        }
    }
}
