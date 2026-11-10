package fuck.andes.ui.screens.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.ui.components.MiuixScaffoldPage
import fuck.andes.ui.components.PrefDivider
import fuck.andes.ui.model.AgentSkillsAction
import fuck.andes.ui.model.AgentSkillsUiState
import fuck.andes.ui.model.SkillItemUi
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val CardHorizontalPadding = 12.dp
private val CardBottomPadding = 12.dp

@Composable
fun AgentSkillsScreen(
    state: AgentSkillsUiState,
    onAction: (AgentSkillsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    MiuixScaffoldPage(
        title = "技能",
        onBack = { onAction(AgentSkillsAction.NavigateBack) },
        modifier = modifier,
    ) {
        val installed = state.skills.filter { it.installed }
        val builtinInstalled = installed.filter { it.source == "builtin" }
        val userInstalled = installed.filter { it.source != "builtin" }
        val removed = state.skills.filter { !it.installed }

        if (builtinInstalled.isNotEmpty()) {
            item(key = "builtin-title") { SmallTitle("内置技能") }
            item(key = "builtin-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    builtinInstalled.forEachIndexed { index, skill ->
                        SkillSwitchRow(
                            skill = skill,
                            onToggle = { enabled ->
                                onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                            },
                        )
                        if (index < builtinInstalled.lastIndex) PrefDivider()
                    }
                }
            }
        }

        if (userInstalled.isNotEmpty()) {
            item(key = "user-title") { SmallTitle("用户技能") }
            item(key = "user-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    userInstalled.forEachIndexed { index, skill ->
                        SkillSwitchRow(
                            skill = skill,
                            onToggle = { enabled ->
                                onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                            },
                        )
                        if (index < userInstalled.lastIndex) PrefDivider()
                    }
                }
            }
        }

        if (removed.isNotEmpty()) {
            item(key = "removed-title") { SmallTitle("已移除") }
            item(key = "removed-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    removed.forEachIndexed { index, skill ->
                        BasicComponent(
                            title = skill.name,
                            summary = "点击重新安装",
                            startAction = { SkillIcon(skill) },
                            onClick = {
                                onAction(AgentSkillsAction.ReinstallBuiltin(skill.id))
                            },
                        )
                        if (index < removed.lastIndex) PrefDivider()
                    }
                }
            }
        }

        if (state.skills.isEmpty() && !state.isLoading) {
            item(key = "empty") { SmallTitle("暂无已安装技能") }
        }
    }
}

@Composable
private fun SkillSwitchRow(
    skill: SkillItemUi,
    onToggle: (Boolean) -> Unit,
) {
    val truncatedSummary = remember(skill.description) {
        val desc = skill.description.ifBlank { "无描述" }
        if (desc.length > 80) desc.take(80) + "..." else desc
    }
    BasicComponent(
        title = skill.name,
        summary = truncatedSummary,
        startAction = { SkillIcon(skill) },
        endActions = {
            Switch(
                checked = skill.enabled,
                onCheckedChange = onToggle,
            )
        },
    )
}

@Composable
private fun SkillIcon(skill: SkillItemUi) {
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(36.dp)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconForSkill(skill.id)),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

private fun iconForSkill(skillId: String): Int = when (skillId) {
    "self-improving-agent" -> LucideR.drawable.lucide_ic_refresh_cw
    "skill-creator" -> LucideR.drawable.lucide_ic_pencil_ruler
    else -> LucideR.drawable.lucide_ic_puzzle
}
