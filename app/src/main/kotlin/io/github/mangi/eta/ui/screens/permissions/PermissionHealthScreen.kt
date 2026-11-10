package io.github.mangi.eta.ui.screens.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PermissionHealthScreen(
    state: PermissionHealthUiState,
    onAction: (PermissionHealthAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    MiuixScaffoldPage(
        title = stringResource(R.string.ui_permission_health_3048bb),
        onBack = { onAction(PermissionHealthAction.NavigateBack) },
        modifier = modifier,
    ) {
        state.runtime?.let { runtime ->
            item(key = "runtime") {
                SmallTitle(stringResource(R.string.compat_environment_title))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
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
        item(key = "card") {
            SmallTitle(stringResource(R.string.ui_permissions_and_status_35f368))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
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
        if (optionalItems.isNotEmpty()) {
            item(key = "optional") {
                SmallTitle(stringResource(R.string.compat_optional_title))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
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
