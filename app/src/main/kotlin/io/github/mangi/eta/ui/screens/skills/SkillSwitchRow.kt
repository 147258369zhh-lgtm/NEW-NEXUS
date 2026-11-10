package io.github.mangi.eta.ui.screens.skills

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.model.SkillItemUi
import java.text.SimpleDateFormat
import java.util.Date
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SkillSwitchRow(
    skill: SkillItemUi,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onExport: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onOpenVersions: (() -> Unit)? = null,
) {
    var showDetails by remember(skill.id) { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val description = skill.description.ifBlank { stringResource(R.string.skills_no_description) }
    val sourceLabel = skill.sourceName ?: when (skill.source) {
        "builtin" -> stringResource(R.string.ui_built_in_skills_1ceedf)
        else -> stringResource(R.string.ui_user_skills_748e7f)
    }
    val metadata = buildList {
        add(sourceLabel)
        skill.version?.let { add("v$it") }
        skill.installedAt?.let { timestamp ->
            add(SimpleDateFormat("yyyy年M月d日", locale).format(Date(timestamp)))
        }
    }.joinToString(" · ")
    val usageLabel = if (skill.useCount <= 0) {
        "尚未使用"
    } else {
        val lastUsed = skill.lastUsedAt?.let { timestamp ->
            SimpleDateFormat("M月d日", locale).format(Date(timestamp))
        }
        buildString {
            append("已使用 ")
            append(skill.useCount)
            append(" 次")
            if (lastUsed != null) append(" · 最近 ").append(lastUsed)
        }
    }
    val reviewLabel = listOfNotNull(skill.qualityLabel, skill.prerequisiteLabel)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
    val details = buildString {
        append(description)
        append("\n\n来源\n")
        append(sourceLabel)
        skill.version?.let { append(" · v").append(it) }
        append("\n\n使用情况\n")
        append(usageLabel)
        skill.qualityDetail?.let { append("\n").append(it) }
        if (skill.dependencies.isNotEmpty()) {
            append("\n\n依赖\n")
            append(skill.dependencies.joinToString(" · "))
        }
        skill.prerequisiteDetail?.let { append("\n").append(it) }
    }
    BasicComponent(
        enabled = enabled,
        onClick = { onToggle(!skill.enabled) },
        endActions = {
            OverlayIconDropdownMenu(
                modifier = Modifier.align(Alignment.CenterVertically),
                entry = DropdownEntry(
                    items = buildList {
                        add(DropdownItem(
                            text = stringResource(R.string.ui_view_description),
                            onClick = { showDetails = true },
                        ))
                        if (skill.versions.isNotEmpty()) {
                            add(DropdownItem(
                                text = stringResource(
                                    R.string.skills_version_records_format,
                                    skill.versions.size,
                                ),
                                onClick = { onOpenVersions?.invoke() },
                            ))
                        }
                        onExport?.let { export ->
                            add(DropdownItem(
                                text = stringResource(R.string.skills_export_package),
                                enabled = enabled,
                                onClick = { if (enabled) export() },
                            ))
                        }
                        onDelete?.let { delete ->
                            add(DropdownItem(
                                text = stringResource(R.string.ui_delete_3755f5),
                                enabled = enabled,
                                onClick = { if (enabled) delete() },
                            ))
                        }
                    },
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreHoriz,
                    contentDescription = stringResource(R.string.skills_more_named, skill.name),
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = skill.enabled,
                onCheckedChange = onToggle,
                enabled = enabled,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        },
    ) {
        Text(
            text = skill.name,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
        )
        Text(
            text = description,
            style = MiuixTheme.textStyles.body2,
            color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = metadata,
            style = MiuixTheme.textStyles.footnote2,
            color = if (enabled) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.disabledOnSecondaryVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
        Text(
            text = usageLabel,
            style = MiuixTheme.textStyles.footnote2,
            color = if (enabled) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.disabledOnSecondaryVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        reviewLabel?.let { label ->
            Row(modifier = Modifier.padding(top = 5.dp)) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = if (enabled) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (enabled) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (showDetails) {
        ItemDescriptionDialog(
            title = skill.name,
            description = details,
            onDismiss = { showDetails = false },
        )
    }
}
