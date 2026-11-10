package io.github.mangi.eta.ui.screens.skills

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.agent.runtime.AgentBoundaryStore
import io.github.mangi.eta.agent.runtime.AgentToolPolicy
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Tool permissions are intentionally separate from prompt templates: a prompt may shape behavior,
 * but tool access remains an explicit boundary decision.
 */
@Composable
internal fun BoundaryToolsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var policy by remember { mutableStateOf(AgentBoundaryStore.load()) }
    var status by remember { mutableStateOf<String?>(null) }
    val names = remember { AgentToolRequirements.registeredNames().sorted() }
    val saveFailed = stringResource(R.string.skills_tools_save_failed)

    BackHandler { onBack() }
    MiuixScaffoldPage(
        title = stringResource(R.string.skills_tools_permission_title),
        onBack = onBack,
    ) {
        item(key = "boundary_scope") {
            SmallTitle(stringResource(R.string.skills_tools_permission_scope))
            Text(
                text = stringResource(R.string.skills_tools_permission_note),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        status?.let { message ->
            item(key = "boundary_status") {
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        item(key = "boundary_search") {
            TextField(
                value = query,
                onValueChange = { query = it.take(100) },
                label = stringResource(R.string.skills_tools_search_tools),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        names.filter { name ->
            query.isBlank() || name.contains(query, ignoreCase = true) ||
                toolDisplayName(context.resources, name).contains(query, ignoreCase = true)
        }.forEach { name ->
            item(key = "boundary_tool_$name") {
                val blockedByGroup = !AgentToolPolicy(
                    disallowedTools = policy.disallowedTools.filter { it != name },
                ).isAllowed(name)
                SwitchPreference(
                    title = toolDisplayName(context.resources, name),
                    summary = if (blockedByGroup) {
                        stringResource(R.string.skills_tools_group_blocked, name)
                    } else {
                        name
                    },
                    checked = policy.isAllowed(name),
                    enabled = !blockedByGroup,
                    onCheckedChange = { allowed ->
                        val next = policy.copy(
                            allowedTools = policy.allowedTools?.let {
                                if (allowed) (it + name).distinct() else it
                            },
                            disallowedTools = if (allowed) {
                                policy.disallowedTools - name
                            } else {
                                (policy.disallowedTools + name).distinct()
                            },
                        )
                        if (AgentBoundaryStore.save(next)) {
                            policy = next
                            status = null
                        } else {
                            status = saveFailed
                        }
                    },
                )
            }
        }
    }
}
