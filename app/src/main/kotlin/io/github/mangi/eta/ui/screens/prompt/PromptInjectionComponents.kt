package io.github.mangi.eta.ui.screens.prompt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.prompt.PromptScopeType
import io.github.mangi.eta.agent.prompt.PromptTarget
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.NumberFormat
import java.util.Locale

internal enum class PromptPillTone { Neutral, Accent, Success, Warning, Danger }

@Composable
internal fun PromptPill(
    text: String,
    tone: PromptPillTone = PromptPillTone.Neutral,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
) {
    val colors = MiuixTheme.colorScheme
    val tint = when (tone) {
        PromptPillTone.Neutral -> colors.onSurfaceVariantSummary
        PromptPillTone.Accent -> colors.primary
        PromptPillTone.Success -> StatusSuccess
        PromptPillTone.Warning -> StatusWarning
        PromptPillTone.Danger -> StatusError
    }
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp),
        color = tint,
        maxLines = if (expanded) 2 else 1,
        overflow = TextOverflow.Ellipsis,
        modifier = (if (expanded) modifier else modifier.widthIn(max = 240.dp))
            .squircleSurface(color = tint.copy(alpha = 0.12f), cornerRadius = 9.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
internal fun PromptIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color? = null,
) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .size(38.dp)
            .squircleBackground(colors.surfaceContainerHigh, 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(21.dp),
            tint = tint ?: colors.primary,
        )
    }
}

@Composable
internal fun PromptInlineStatus(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val colors = MiuixTheme.colorScheme
    val tint = if (isError) colors.error else colors.onSurfaceVariantSummary
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = tint,
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(
                color = if (isError) colors.errorContainer else colors.surfaceContainerHigh,
                cornerRadius = 12.dp,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
internal fun PromptMetaRow(
    items: List<String>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item -> PromptPill(item) }
    }
}

@Composable
internal fun PromptSectionNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
internal fun PromptKeyValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.widthIn(min = 76.dp),
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun PromptEmptyState(
    title: String,
    summary: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PromptIconBadge(icon)
        Text(
            text = title,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = summary,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        action?.invoke()
    }
}

internal fun promptCount(value: Int): String =
    NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

internal fun PromptScopeType.titleRes(): Int = when (this) {
    PromptScopeType.GLOBAL -> R.string.prompt_injection_global
    PromptScopeType.MODEL -> R.string.prompt_injection_scope_model
    PromptScopeType.CONVERSATION -> R.string.prompt_injection_scope_conversation
    PromptScopeType.SUBAGENT -> R.string.prompt_injection_scope_subagent
    PromptScopeType.MEETING -> R.string.prompt_injection_scope_meeting
    PromptScopeType.MEETING_PARTICIPANT -> R.string.prompt_injection_scope_participant
    PromptScopeType.CHARACTER -> R.string.prompt_injection_scope_character
    PromptScopeType.AUTOMATION -> R.string.prompt_injection_scope_automation
}

internal fun PromptScopeType.icon(): ImageVector = when (this) {
    PromptScopeType.GLOBAL -> Icons.Rounded.Public
    PromptScopeType.MODEL -> Icons.Rounded.Memory
    PromptScopeType.CONVERSATION -> Icons.Rounded.Forum
    PromptScopeType.SUBAGENT -> Icons.Rounded.SupportAgent
    PromptScopeType.MEETING -> Icons.Rounded.Groups
    PromptScopeType.MEETING_PARTICIPANT -> Icons.Rounded.Person
    PromptScopeType.CHARACTER -> Icons.Rounded.SportsBar
    PromptScopeType.AUTOMATION -> Icons.Rounded.Schedule
}

@Composable
internal fun promptTargetLabel(
    store: PromptInjectionUiStore,
    target: PromptTarget,
    fallback: String = "",
): String = if (target.type == PromptScopeType.GLOBAL) {
    stringResource(R.string.prompt_injection_global)
} else {
    store.targetTitle(target).ifBlank { fallback }
}
