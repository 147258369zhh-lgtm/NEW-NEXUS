package io.github.mangi.eta.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase
import io.github.mangi.eta.agent.accessibility.textResource
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 延续聊天的留白和 Miuix 字体，状态保留可读；只有活动等待显示停止操作。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AccessibilityWaitCard(
    message: SystemNoticeMessageUi,
    onOpenPermissions: (() -> Unit)?,
    onStopWaiting: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tokens = LocalEtaDesignTokens.current
    var settingsFailed by remember(message.id) { mutableStateOf(false) }
    val phase = AccessibilityWaitPhase.fromWireValue(message.detail.orEmpty()) ?: AccessibilityWaitPhase.CANCELLED
    val displayedPhase = if (!message.running && phase == AccessibilityWaitPhase.WAITING) AccessibilityWaitPhase.CANCELLED else phase
    EtaStateMessage(
        title = stringResource(R.string.accessibility_wait_title),
        detail = stringResource(displayedPhase.textResource()),
        modifier = modifier.fillMaxWidth().padding(horizontal = tokens.chatHorizontalInset, vertical = 6.dp)
            .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(tokens.cornerMedium))
            .border(0.5.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(tokens.cornerMedium))
            .padding(tokens.cardContentPadding),
    ) {
        if (settingsFailed) {
            Text(stringResource(R.string.accessibility_wait_settings_failed), style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error)
        }
        if (message.running || displayedPhase == AccessibilityWaitPhase.TIMED_OUT) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    text = stringResource(if (onOpenPermissions != null) R.string.accessibility_wait_permissions else R.string.accessibility_wait_settings),
                    onClick = {
                        settingsFailed = runCatching {
                            onOpenPermissions?.invoke() ?: context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.isFailure
                    },
                    modifier = Modifier.defaultMinSize(minHeight = tokens.minimumTouchTarget),
                )
                if (message.running && onStopWaiting != null) {
                    TextButton(text = stringResource(R.string.accessibility_wait_stop), onClick = onStopWaiting,
                        modifier = Modifier.defaultMinSize(minHeight = tokens.minimumTouchTarget))
                }
            }
        }
    }
}
