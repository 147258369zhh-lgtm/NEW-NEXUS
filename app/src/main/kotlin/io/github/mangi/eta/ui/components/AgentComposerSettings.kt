package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.AgentPermissionMode
import io.github.zhh_lgtm.nexus.application.api.WorkflowMode
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class ComposerSettingsPage { MODE, APPROVAL }

@Composable
internal fun AgentComposerSettingsSheet(
    show: Boolean,
    workflowMode: WorkflowMode,
    permissionMode: AgentPermissionMode,
    enabled: Boolean,
    onWorkflowModeChange: (WorkflowMode) -> Unit,
    onPermissionModeChange: (AgentPermissionMode) -> Unit,
    onOpenFullSettings: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf(ComposerSettingsPage.MODE) }
    OverlayBottomSheet(
        show = show,
        title = "会话设置",
        onDismissRequest = onDismiss,
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .squircleSurface(
                        MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
                        18.dp,
                    )
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ComposerSettingsTab(
                    text = "运行模式",
                    selected = page == ComposerSettingsPage.MODE,
                    onClick = { page = ComposerSettingsPage.MODE },
                    modifier = Modifier.weight(1f),
                )
                ComposerSettingsTab(
                    text = "审批权限",
                    selected = page == ComposerSettingsPage.APPROVAL,
                    onClick = { page = ComposerSettingsPage.APPROVAL },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (page == ComposerSettingsPage.MODE) "本次会话如何工作" else "执行到哪一步需要你确认",
                        style = MiuixTheme.textStyles.body1,
                    )
                    Text(
                        text = if (page == ComposerSettingsPage.MODE) {
                            "模式会随下一条消息一起保存"
                        } else {
                            "按所选审批方式执行当前任务"
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                ComposerCurrentBadge(
                    text = if (page == ComposerSettingsPage.MODE) workflowMode.initial else permissionMode.initial,
                )
            }

            if (page == ComposerSettingsPage.MODE) {
                listOf(WorkflowMode.CHAT, WorkflowMode.PLAN).forEach { mode ->
                    ComposerSettingsOption(
                        initial = mode.initial,
                        title = mode.title,
                        summary = mode.summary,
                        selected = workflowMode == mode || (mode == WorkflowMode.CHAT && workflowMode == WorkflowMode.GOAL),
                        enabled = enabled,
                        onClick = { onWorkflowModeChange(mode) },
                    )
                }
                ComposerSettingsOption(initial = WorkflowMode.GOAL.initial, title = "持久目标",
                    summary = "独立保留目标、预算和恢复状态；使用普通执行方式推进",
                    selected = workflowMode == WorkflowMode.GOAL, enabled = enabled,
                    onClick = { onWorkflowModeChange(if (workflowMode == WorkflowMode.GOAL) WorkflowMode.CHAT else WorkflowMode.GOAL) })
            } else {
                AgentPermissionMode.entries.forEach { mode ->
                    ComposerSettingsOption(
                        initial = mode.initial,
                        title = mode.title,
                        summary = mode.summary,
                        selected = permissionMode == mode,
                        enabled = enabled,
                        onClick = { onPermissionModeChange(mode) },
                    )
                }
            }

            if (onOpenFullSettings != null) {
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .squircleSurface(
                            MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            16.dp,
                        )
                        .clickable(onClick = onOpenFullSettings)
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = "打开完整设置",
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "更多选项",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerSettingsTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .squircleSurface(
                if (selected) MiuixTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                14.dp,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ComposerCurrentBadge(text: String) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .squircleSurface(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f), 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ComposerSettingsOption(
    initial: String,
    title: String,
    summary: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(
                if (selected) MiuixTheme.colorScheme.tertiaryContainer
                else MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.44f),
                18.dp,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .squircleSurface(
                    if (selected) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.surface,
                    12.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = MiuixTheme.textStyles.body1,
                color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MiuixTheme.textStyles.body1)
            Text(
                text = summary,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (selected) {
            Box(
                modifier = Modifier.size(22.dp).squircleSurface(MiuixTheme.colorScheme.primary, 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "已选择",
                    modifier = Modifier.size(14.dp),
                    tint = MiuixTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

internal val WorkflowMode.initial: String
    get() = when (this) {
        WorkflowMode.CHAT -> "C"
        WorkflowMode.PLAN -> "P"
        WorkflowMode.GOAL -> "G"
    }

internal val WorkflowMode.title: String
    get() = when (this) {
        WorkflowMode.CHAT -> "普通会话"
        WorkflowMode.PLAN -> "计划"
        WorkflowMode.GOAL -> "目标"
    }

private val WorkflowMode.summary: String
    get() = when (this) {
        WorkflowMode.CHAT -> "直接交流与执行，使用当前对话节奏"
        WorkflowMode.PLAN -> "调研、澄清并形成计划，不自动实施修改"
        WorkflowMode.GOAL -> "围绕明确目标持续跟踪进度和完成条件"
    }

internal val AgentPermissionMode.initial: String
    get() = when (this) {
        AgentPermissionMode.FULL_ACCESS -> "F"
        AgentPermissionMode.SMART_APPROVAL -> "S"
        AgentPermissionMode.MANUAL_APPROVAL -> "M"
    }

private val AgentPermissionMode.title: String
    get() = when (this) {
        AgentPermissionMode.FULL_ACCESS -> "完全访问"
        AgentPermissionMode.SMART_APPROVAL -> "智能审批"
        AgentPermissionMode.MANUAL_APPROVAL -> "手动审批"
    }

private val AgentPermissionMode.summary: String
    get() = when (this) {
        AgentPermissionMode.FULL_ACCESS -> "已授权任务内自动执行，不弹应用审批确认"
        AgentPermissionMode.SMART_APPROVAL -> "独立审查必要动作，结合你的批准偏好判断"
        AgentPermissionMode.MANUAL_APPROVAL -> "已允许范围内直接执行，额外授权由你确认"
    }
