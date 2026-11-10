package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zhh_lgtm.nexus.application.api.WorkflowSnapshot
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Compact, collapsible projection of the current plan or goal above the composer. */
@Composable
internal fun NexusWorkflowStatusCard(
    snapshot: WorkflowSnapshot,
    modifier: Modifier = Modifier,
) {
    val plan = snapshot.plan
    val goal = snapshot.goal
    if (plan == null && goal == null) return
    var expanded by rememberSaveable(snapshot.scopeId) { mutableStateOf(false) }
    val title = when {
        goal != null && plan != null -> "目标 · ${goal.state.name} · 计划 r${plan.revision}"
        goal != null -> "目标 · ${goal.state.name}"
        plan != null -> "计划 r${plan.revision}"
        else -> ""
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f),
                RoundedCornerShape(16.dp),
            )
            .border(
                0.5.dp,
                MiuixTheme.colorScheme.primary.copy(alpha = 0.22f),
                RoundedCornerShape(16.dp),
            )
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                title,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "收起" else "展开",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        goal?.objective?.takeIf(String::isNotBlank)?.let { objective ->
            Text(
                objective,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                plan?.steps.orEmpty().forEachIndexed { index, step ->
                    Text(
                        "${index + 1}. ${step.title}${step.verification.takeIf { it.isNotBlank() }?.let { " · 验证：$it" }.orEmpty()}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                goal?.budget?.let { budget ->
                    Text(
                        "预算：${budget.softRounds ?: "—"} 轮 / ${budget.softToolCalls ?: "—"} 次工具 / ${budget.softDurationMs?.div(60_000) ?: "—"} 分钟",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}
