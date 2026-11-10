package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 可恢复错误和任务等待共享的文字/操作布局；不截断说明，不使用固定高度。 */
@Composable
internal fun EtaStateMessage(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    centered: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    val tokens = LocalEtaDesignTokens.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(tokens.stateItemSpacing),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(title, style = MiuixTheme.textStyles.body1, fontWeight = tokens.titleWeight,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            color = MiuixTheme.colorScheme.onSurface)
        detail?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MiuixTheme.textStyles.footnote1,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        actions()
    }
}
