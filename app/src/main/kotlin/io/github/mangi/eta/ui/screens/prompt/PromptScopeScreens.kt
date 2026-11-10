package io.github.mangi.eta.ui.screens.prompt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.prompt.PromptScopeType
import io.github.mangi.eta.agent.prompt.PromptTarget
import io.github.mangi.eta.agent.prompt.PromptTemplate
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.etaGlassSurface
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun PromptScopeTargetsScreen(
    type: PromptScopeType,
    store: PromptInjectionUiStore,
    onBack: () -> Unit,
    onPick: (PromptTarget) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable(type) { mutableStateOf("") }
    val allTargets = store.targets(type)
    val targets = allTargets.filter { target ->
        query.isBlank() || target.title.contains(query, ignoreCase = true) ||
            target.detail.contains(query, ignoreCase = true)
    }
    val boundCount = allTargets.count { store.bindingFor(it.target) != null }
    val unbindSuccess = stringResource(R.string.prompt_injection_unbind_success)
    val unbindFailed = stringResource(R.string.prompt_injection_unbind_failed)

    BackHandler { onBack() }
    MiuixScaffoldPage(
        title = stringResource(type.titleRes()),
        onBack = onBack,
    ) {
        item(key = "scope_targets_summary") {
            val colors = MiuixTheme.colorScheme
            Card(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    .etaGlassSurface(accent = colors.primary, cornerRadius = 18.dp),
                colors = CardDefaults.defaultColors(
                    color = colors.secondaryContainer.copy(alpha = 0.28f),
                    contentColor = colors.onSurface,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.prompt_injection_scope_count, allTargets.size, boundCount),
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.prompt_injection_targets_note),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        if (allTargets.size > 6) {
            item(key = "scope_targets_search") {
                TextField(
                    value = query,
                    onValueChange = { query = it.take(120) },
                    label = stringResource(R.string.prompt_injection_search_targets),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        if (targets.isEmpty()) {
            item(key = "scope_targets_empty") {
                PromptEmptyState(
                    title = stringResource(R.string.prompt_injection_no_targets),
                    summary = stringResource(R.string.prompt_injection_no_targets_summary),
                    icon = type.icon(),
                )
            }
        } else {
            item(key = "scope_targets_header") {
                SmallTitle(stringResource(R.string.prompt_injection_choose_target))
            }
            targets.forEach { target ->
                item(key = "scope_target_${target.target.key}") {
                    val binding = store.bindingFor(target.target)
                    val template = store.template(binding?.templateId)
                    val colors = MiuixTheme.colorScheme
                    Card(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            .etaGlassSurface(accent = if (binding != null) colors.primary else colors.secondary, cornerRadius = 16.dp),
                        colors = CardDefaults.defaultColors(
                            color = if (binding != null) {
                                colors.secondaryContainer.copy(alpha = 0.38f)
                            } else {
                                colors.surfaceContainer.copy(alpha = 0.76f)
                            },
                            contentColor = colors.onSurface,
                        ),
                        onClick = { onPick(target.target) },
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                PromptIconBadge(type.icon())
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        text = target.title.ifBlank { target.target.primaryId },
                                        style = MiuixTheme.textStyles.body1,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    target.detail.takeIf(String::isNotBlank)?.let { detail ->
                                        Text(
                                            text = detail,
                                            style = MiuixTheme.textStyles.body2,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp).padding(top = 2.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                if (binding != null) {
                                    PromptPill(
                                        text = stringResource(
                                            R.string.prompt_injection_bound_to,
                                            template?.name ?: stringResource(R.string.prompt_injection_missing_template),
                                        ),
                                        tone = PromptPillTone.Accent,
                                        expanded = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    if (!target.enabled) {
                                        PromptPill(
                                            text = stringResource(R.string.prompt_injection_target_disabled),
                                            tone = PromptPillTone.Warning,
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TextButton(
                                            text = stringResource(R.string.prompt_injection_unbind),
                                            onClick = {
                                                scope.launch {
                                                    store.report(
                                                        if (store.unbind(target.target)) unbindSuccess else unbindFailed,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                } else {
                                    PromptPill(stringResource(R.string.prompt_injection_unbound))
                                    if (!target.enabled) {
                                        PromptPill(
                                            text = stringResource(R.string.prompt_injection_target_disabled),
                                            tone = PromptPillTone.Warning,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PromptScopePickerScreen(
    target: PromptTarget,
    store: PromptInjectionUiStore,
    onBack: () -> Unit,
    onBound: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentBinding = store.bindingFor(target)
    val currentTemplate = store.template(currentBinding?.templateId)
    val targetLabel = promptTargetLabel(store, target, target.primaryId)
    var pendingReplacement by remember { mutableStateOf<Pair<PromptTemplate, PromptTemplate>?>(null) }
    val templates = store.config.templates.sortedWith(
        compareByDescending<PromptTemplate> { it.enabled }.thenBy { it.name.lowercase() },
    )
    val bindSuccess = stringResource(R.string.prompt_injection_bind_success)
    val bindFailed = stringResource(R.string.prompt_injection_bind_failed)
    val unbindSuccess = stringResource(R.string.prompt_injection_unbind_success)
    val unbindFailed = stringResource(R.string.prompt_injection_unbind_failed)
    val bindNow: (PromptTemplate) -> Unit = { template ->
        scope.launch {
            val succeeded = store.bind(template.id, target)
            store.report(if (succeeded) bindSuccess else bindFailed)
            if (succeeded) onBound()
        }
    }

    BackHandler { onBack() }
    MiuixScaffoldPage(
        title = stringResource(R.string.prompt_injection_choose_template),
        onBack = onBack,
    ) {
        item(key = "picker_target") {
            val colors = MiuixTheme.colorScheme
            Card(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    .etaGlassSurface(accent = colors.primary, cornerRadius = 18.dp),
                colors = CardDefaults.defaultColors(
                    color = colors.secondaryContainer.copy(alpha = 0.26f),
                    contentColor = colors.onSurface,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PromptKeyValueRow(
                        label = stringResource(R.string.prompt_injection_scope),
                        value = stringResource(target.type.titleRes()),
                    )
                    PromptKeyValueRow(
                        label = stringResource(R.string.prompt_injection_target),
                        value = targetLabel,
                    )
                    if (currentTemplate != null) {
                        PromptKeyValueRow(
                            label = stringResource(R.string.prompt_injection_current_binding),
                            value = currentTemplate.name,
                        )
                    } else {
                        PromptKeyValueRow(
                            label = stringResource(R.string.prompt_injection_current_binding),
                            value = stringResource(R.string.prompt_injection_unbound),
                        )
                    }
                    Text(
                        text = stringResource(R.string.prompt_injection_picker_note),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        if (currentBinding != null) {
            item(key = "picker_unbind") {
                TextButton(
                    text = stringResource(R.string.prompt_injection_unbind),
                    onClick = {
                        scope.launch {
                            val succeeded = store.unbind(target)
                            store.report(if (succeeded) unbindSuccess else unbindFailed)
                            if (succeeded) onBound()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        if (templates.isEmpty()) {
            item(key = "picker_empty") {
                PromptEmptyState(
                    title = stringResource(R.string.prompt_injection_no_templates),
                    summary = stringResource(R.string.prompt_injection_no_templates_summary),
                    icon = Icons.Rounded.Search,
                )
            }
        } else {
            item(key = "picker_templates_header") {
                SmallTitle(stringResource(R.string.prompt_injection_choose_template))
            }
            templates.forEach { template ->
                item(key = "picker_template_${template.id}") {
                    val selected = currentBinding?.templateId == template.id
                    val colors = MiuixTheme.colorScheme
                    Card(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            .etaGlassSurface(accent = colors.primary, cornerRadius = 16.dp),
                        colors = CardDefaults.defaultColors(
                            color = when {
                                selected -> colors.secondaryContainer.copy(alpha = 0.48f)
                                template.enabled -> colors.surfaceContainer.copy(alpha = 0.82f)
                                else -> colors.surfaceContainer.copy(alpha = 0.62f)
                            },
                            contentColor = colors.onSurface,
                        ),
                        onClick = {
                            when {
                                selected -> Unit
                                currentTemplate != null -> pendingReplacement = currentTemplate to template
                                else -> bindNow(template)
                            }
                        },
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                PromptIconBadge(
                                    icon = Icons.Rounded.CheckCircle,
                                    tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        text = template.name.ifBlank {
                                            stringResource(R.string.prompt_injection_untitled_template)
                                        },
                                        style = MiuixTheme.textStyles.body1,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    template.description.takeIf(String::isNotBlank)?.let { description ->
                                        Text(
                                            text = description,
                                            style = MiuixTheme.textStyles.body2,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (selected) {
                                    PromptPill(
                                        text = stringResource(R.string.prompt_injection_current),
                                        tone = PromptPillTone.Accent,
                                    )
                                }
                            }
                            PromptMetaRow(
                                items = buildList {
                                    add(stringResource(R.string.prompt_injection_block_count, template.blocks.size))
                                    add(stringResource(R.string.prompt_injection_char_count, promptCount(template.characterCount)))
                                    if (!template.enabled) {
                                        add(stringResource(R.string.prompt_injection_disabled))
                                    }
                                },
                            )
                            if (!template.enabled) {
                                Text(
                                    text = stringResource(R.string.prompt_injection_template_disabled_hint),
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingReplacement?.let { (previous, next) ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.prompt_injection_replace_title),
            summary = stringResource(
                R.string.prompt_injection_replace_body,
                targetLabel,
                previous.name,
                next.name,
            ),
            onDismissRequest = { pendingReplacement = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.prompt_injection_replace_confirm),
                onCancel = { pendingReplacement = null },
                onConfirm = {
                    pendingReplacement = null
                    bindNow(next)
                },
            )
        }
    }
}
