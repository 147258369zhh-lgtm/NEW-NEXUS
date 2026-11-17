package io.github.mangi.eta.ui.screens.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import io.github.mangi.eta.ui.components.NexusSettingsTabs
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.color
import io.github.mangi.eta.ui.components.label
import io.github.mangi.eta.ui.model.PermissionHealthAction
import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionHealthUiState
import io.github.mangi.eta.data.model.AgentPermissionMode
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import top.yukonga.miuix.kmp.basic.Icon
import io.github.mangi.eta.ui.components.NexusSettingsSmallTitle as SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import io.github.mangi.eta.ui.components.NexusSettingsArrowPreference as ArrowPreference
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PermissionHealthScreen(
    state: PermissionHealthUiState,
    onAction: (PermissionHealthAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var category by rememberSaveable { mutableIntStateOf(0) }
    val permissionsScroll = rememberLazyListState()
    val approvalScroll = rememberLazyListState()
    val environmentScroll = rememberLazyListState()
    MiuixScaffoldPage(
        listState = listOf(permissionsScroll, approvalScroll, environmentScroll)[category],
        aboveContent = { NexusSettingsTabs(listOf(
            stringResource(R.string.settings_tab_permissions), stringResource(R.string.settings_tab_approval),
            stringResource(R.string.compat_environment_title),
        ), category, { category = it }) },
        title = stringResource(R.string.ui_permission_health_3048bb),
        onBack = { onAction(PermissionHealthAction.NavigateBack) },
        modifier = modifier,
    ) {
        if (category == 1) item(key = "agent_approval") {
            SmallTitle("Agent 权限审批")
            Card(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                Column {
                    AgentPermissionMode.entries.forEach { mode ->
                        PermissionModeRow(
                            mode = mode,
                            selected = state.agentPermissionMode == mode,
                            onClick = { onAction(PermissionHealthAction.SetAgentPermissionMode(mode)) },
                        )
                    }
                }
            }
            ApprovalLearningPanel()
        }
        if (category == 2) state.runtime?.let { runtime ->
            item(key = "runtime") {
                SmallTitle(stringResource(R.string.compat_environment_title))
                Card(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(text = runtime.androidVersion, style = MiuixTheme.textStyles.headline1)
                        Text(text = runtime.device, style = MiuixTheme.textStyles.body2)
                        Text(
                            text = runtime.mode,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                        Text(
                            text = runtime.systemBuild,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    }
                }
            }
        }
        if (category == 0) item(key = "card") {
            SmallTitle(stringResource(R.string.ui_permissions_and_status_35f368))
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
            ) {
                state.items.filter { it.requiredForCore }.forEach { item ->
                    PermissionItemRow(
                        item = item,
                        onActionClick = { onAction(PermissionHealthAction.OpenItemAction(item.id)) },
                    )
                }
            }
        }
        val optionalItems = state.items.filterNot { it.requiredForCore }
        if (category == 0 && optionalItems.isNotEmpty()) {
            item(key = "optional") {
                SmallTitle(stringResource(R.string.compat_optional_title))
                Card(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                    optionalItems.forEach { item ->
                        PermissionItemRow(
                            item = item,
                            onActionClick = { onAction(PermissionHealthAction.OpenItemAction(item.id)) },
                        )
                    }
                    state.rootAccessibilityRecovery?.let { recovery ->
                        SwitchPreference(
                            title = stringResource(R.string.recovery_title),
                            summary = recovery.summary,
                            checked = recovery.enabled,
                            enabled = recovery.canToggle,
                            onCheckedChange = { onAction(PermissionHealthAction.SetRootAccessibilityRecovery(it)) },
                        )
                        if (recovery.canRetry) {
                            ArrowPreference(
                                title = stringResource(R.string.recovery_retry),
                                summary = stringResource(R.string.recovery_retry_summary),
                                onClick = { onAction(PermissionHealthAction.RetryRootAccessibilityRecovery) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionModeRow(
    mode: AgentPermissionMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val icon = when (mode) {
        AgentPermissionMode.FULL_ACCESS -> Icons.Rounded.VerifiedUser
        AgentPermissionMode.SMART_APPROVAL -> Icons.Rounded.AutoAwesome
        AgentPermissionMode.MANUAL_APPROVAL -> Icons.Rounded.PanTool
    }
    val title = when (mode) {
        AgentPermissionMode.FULL_ACCESS -> "完全访问"
        AgentPermissionMode.SMART_APPROVAL -> "智能审批"
        AgentPermissionMode.MANUAL_APPROVAL -> "手动审批"
    }
    val summary = when (mode) {
        AgentPermissionMode.FULL_ACCESS -> "在具体授权任务内自动执行；仍受系统、Root和账号真实权限约束"
        AgentPermissionMode.SMART_APPROVAL -> "范围内自动执行；必要时独立审查，审查不可用或不确定时交给你"
        AgentPermissionMode.MANUAL_APPROVAL -> "范围内自动执行；需要新增权限或风险确认时交给你"
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MiuixTheme.textStyles.body1)
            Text(summary, style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Icon(
            imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = if (selected) "当前" else null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun PermissionItemRow(
    item: PermissionHealthItemUi,
    onActionClick: () -> Unit,
) {
    ArrowPreference(
        title = item.title,
        summary = item.summary.takeIf { it.isNotBlank() },
        endActions = {
            Text(
                text = item.status.label(),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = item.status.color(),
            )
        },
        onClick = onActionClick,
    )
}
