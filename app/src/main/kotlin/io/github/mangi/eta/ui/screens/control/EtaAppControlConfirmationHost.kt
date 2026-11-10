package io.github.mangi.eta.ui.screens.control

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.ui.components.MiuixDialogActions
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun EtaAppControlConfirmationHost() {
    val pending by EtaAppControlConfirmation.pending.collectAsState()
    val request = pending ?: return
    WindowDialog(
        show = true,
        title = "确认操作",
        summary = request.summary,
        cornerRadius = DialogDefaults.CornerRadius,
        onDismissRequest = { EtaAppControlConfirmation.cancel(request.id) },
    ) {
        val scheme = MiuixTheme.colorScheme
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surfaceContainer, RoundedCornerShape(14.dp))
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = request.title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                )
                if (request.parameters.isEmpty()) {
                    Text(
                        text = "此操作会修改软件状态。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSurfaceVariantSummary,
                    )
                } else {
                    request.parameters.forEach { (label, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = label.confirmationFieldLabel(),
                                style = MiuixTheme.textStyles.footnote2,
                                color = scheme.onSurfaceVariantSummary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = value.ifBlank { "空" },
                                style = MiuixTheme.textStyles.footnote2,
                                modifier = Modifier.weight(1.35f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            MiuixDialogActions(
                confirmText = "确认执行",
                destructive = true,
                onCancel = { EtaAppControlConfirmation.cancel(request.id) },
                onConfirm = { EtaAppControlConfirmation.approve(request.id) },
            )
        }
    }
}

private fun String.confirmationFieldLabel(): String = when (this) {
    "enabled" -> "启用"
    "providerId" -> "提供商"
    "modelId" -> "模型"
    "name" -> "名称"
    "rootDisplayName" -> "文件夹"
    "source" -> "来源"
    else -> this
}
