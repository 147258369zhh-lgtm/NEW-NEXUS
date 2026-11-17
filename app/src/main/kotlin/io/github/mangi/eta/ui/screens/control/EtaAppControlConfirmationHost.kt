package io.github.mangi.eta.ui.screens.control

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.ui.components.MiuixDialogActions
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun EtaAppControlConfirmationHost() {
    val pending by EtaAppControlConfirmation.pending.collectAsState()
    val pendingCount by EtaAppControlConfirmation.pendingCount.collectAsState()
    val request = pending ?: return
    WindowDialog(
        show = true,
        title = "审批敏感操作",
        cornerRadius = DialogDefaults.CornerRadius,
        onDismissRequest = { EtaAppControlConfirmation.cancel(request.id) },
    ) {
        val scheme = MiuixTheme.colorScheme
        val detailsScroll = remember(request.id) { ScrollState(0) }
        Column(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(detailsScroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (request.parameters.any { it.first == "effect" && it.second in setOf("HIGH_RISK", "DESTRUCTIVE") }) {
                        Icons.Rounded.WarningAmber
                    } else Icons.Rounded.GppMaybe,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = scheme.error,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(request.title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = request.parameters.firstOrNull { it.first == "effect" }?.second?.approvalRiskLabel()
                            ?: "需要你明确确认",
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.error,
                    )
                    if (pendingCount > 1) {
                        Text("当前第 1 项，共 $pendingCount 项待审批", style = MiuixTheme.textStyles.footnote2,
                            color = scheme.onSurfaceVariantSummary)
                    }
                }
            }
            if (request.summary.isNotBlank()) Text(request.summary, style = MiuixTheme.textStyles.body2)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surfaceContainer, RoundedCornerShape(14.dp))
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
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
                                text = value.approvalValue(label).ifBlank { "空" },
                                style = MiuixTheme.textStyles.footnote2,
                                modifier = Modifier.weight(1.35f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Text(
                text = "确认后只执行这一次操作；项目文件、外部连接或费用状态发生变化的操作不会被自动重放。",
                style = MiuixTheme.textStyles.footnote2,
                color = scheme.onSurfaceVariantSummary,
            )
            }
            MiuixDialogActions(
                confirmText = "确认执行",
                cancelText = "拒绝本次",
                destructive = true,
                onCancel = { EtaAppControlConfirmation.reject(request.id) },
                onConfirm = { EtaAppControlConfirmation.approve(request.id) },
            )
        }
    }
}

private fun String.confirmationFieldLabel(): String = when (this) {
    "tool" -> "工具"
    "host_id" -> "执行环境"
    "effect" -> "风险类型"
    "approval_mode" -> "审批模式"
    "expires_at" -> "授权到期"
    "run_id" -> "运行记录"
    "parameters" -> "操作参数"
    "command" -> "命令"
    "path" -> "文件路径"
    "url" -> "网址"
    "uri" -> "目标地址"
    "package" -> "应用"
    "app" -> "应用"
    "action" -> "操作"
    "target" -> "目标"
    "environment" -> "环境"
    "identity" -> "执行身份"
    "amount" -> "金额"
    "currency" -> "币种"
    "enabled" -> "启用"
    "providerId" -> "提供商"
    "modelId" -> "模型"
    "name" -> "名称"
    "rootDisplayName" -> "文件夹"
    "source" -> "来源"
    else -> this
}

private fun String.approvalRiskLabel(): String = when (this) {
    "HIGH_RISK" -> "高风险操作，必须由你确认"
    "DESTRUCTIVE" -> "删除或不可逆操作，必须由你确认"
    "EXTERNAL_SIDE_EFFECT" -> "会改变外部服务或产生费用"
    "NON_IDEMPOTENT_WRITE" -> "会修改不可安全重试的数据"
    else -> "本次运行需要人工授权"
}

private fun String.approvalValue(label: String): String = when (label) {
    "approval_mode" -> when (this) {
        "full_access" -> "完全访问"
        "smart_approval" -> "智能审批"
        "manual_approval" -> "手动审批"
        else -> this
    }
    "effect" -> approvalRiskLabel()
    else -> this
}
