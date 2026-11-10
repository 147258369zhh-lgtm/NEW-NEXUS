package io.github.mangi.eta.ui.screens.prompt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DeleteOutline
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
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.prompt.PromptBlock
import io.github.mangi.eta.agent.prompt.PromptBlockPosition
import io.github.mangi.eta.agent.prompt.PromptInjectionStore
import io.github.mangi.eta.agent.prompt.PromptIssueLevel
import io.github.mangi.eta.agent.prompt.PromptAnalysisReport
import io.github.mangi.eta.agent.prompt.PromptTemplate
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.etaGlassSurface
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import org.json.JSONArray
import java.util.UUID

@Composable
internal fun PromptTemplateEditorScreen(
    template: PromptTemplate,
    store: PromptInjectionUiStore,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val baselineBlocks = remember(template.id, template.blocks) {
        template.blocks.sortedWith(PromptTemplate.BLOCK_ORDER)
    }
    var name by rememberSaveable(template.id) { mutableStateOf(template.name) }
    var description by rememberSaveable(template.id) { mutableStateOf(template.description) }
    var enabled by rememberSaveable(template.id) { mutableStateOf(template.enabled) }
    var blocksJson by rememberSaveable(template.id) { mutableStateOf(encodeBlocks(baselineBlocks)) }
    val blocks = remember(blocksJson) { decodeBlocks(blocksJson) }
    var error by rememberSaveable(template.id) { mutableStateOf<String?>(null) }
    var saving by remember(template.id) { mutableStateOf(false) }
    var showDiscard by rememberSaveable(template.id) { mutableStateOf(false) }
    val setBlocks: (List<PromptBlock>) -> Unit = { next -> blocksJson = encodeBlocks(next) }

    val dirty = name != template.name || description != template.description ||
        enabled != template.enabled || blocks != baselineBlocks
    val analysis: PromptAnalysisReport = remember(name, description, enabled, blocksJson) {
        store.analyzeTemplate(
            template.copy(
                name = name.trim(),
                description = description.trim(),
                enabled = enabled,
                blocks = blocks.mapIndexed { index, block -> block.copy(order = index * 10) },
            ),
        )
    }
    val nameRequired = stringResource(R.string.prompt_injection_name_required)
    val saveFailed = stringResource(R.string.prompt_injection_save_failed)
    val saveSuccess = stringResource(R.string.prompt_injection_save_success)
    val requestBack: () -> Unit = {
        if (dirty && !saving) showDiscard = true else if (!saving) onBack()
    }
    val save: () -> Unit = {
        if (!saving) {
            if (name.isBlank()) {
                error = nameRequired
            } else {
                error = null
                saving = true
                val normalized = blocks.mapIndexed { index, block ->
                    block.copy(
                        title = block.title.trim().take(PromptInjectionStore.MAX_BLOCK_TITLE_CHARS),
                        content = block.content.trim().take(PromptInjectionStore.MAX_BLOCK_CHARS),
                        order = index * 10,
                    )
                }
                val next = template.copy(
                    name = name.trim().take(PromptInjectionStore.MAX_TEMPLATE_NAME_CHARS),
                    description = description.trim().take(PromptInjectionStore.MAX_DESCRIPTION_CHARS),
                    enabled = enabled,
                    blocks = normalized,
                )
                scope.launch {
                    val succeeded = store.saveTemplate(next)
                    saving = false
                    if (succeeded) {
                        store.report(saveSuccess)
                        onSaved()
                    } else {
                        error = store.validationError ?: saveFailed
                    }
                }
            }
        }
    }

    BackHandler { requestBack() }
    MiuixScaffoldPage(
        title = if (template.name.isBlank()) {
            stringResource(R.string.prompt_injection_new)
        } else {
            stringResource(R.string.prompt_injection_edit_template)
        },
        onBack = requestBack,
        actions = {
            TextButton(
                text = if (saving) stringResource(R.string.prompt_injection_saving) else stringResource(R.string.prompt_injection_save),
                enabled = !saving && name.isNotBlank(),
                onClick = save,
            )
        },
    ) {
        item(key = "editor_meta") {
            Column {
                SmallTitle(stringResource(R.string.prompt_injection_template_info))
                val colors = MiuixTheme.colorScheme
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        .etaGlassSurface(accent = colors.primary, cornerRadius = 18.dp),
                    colors = CardDefaults.defaultColors(
                        color = colors.secondaryContainer.copy(alpha = 0.22f),
                        contentColor = colors.onSurface,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextField(
                            value = name,
                            onValueChange = {
                                name = it.take(PromptInjectionStore.MAX_TEMPLATE_NAME_CHARS)
                                error = null
                            },
                            label = stringResource(R.string.prompt_injection_name),
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = description,
                            onValueChange = { description = it.take(PromptInjectionStore.MAX_DESCRIPTION_CHARS) },
                            label = stringResource(R.string.prompt_injection_description),
                            useLabelAsPlaceholder = true,
                            minLines = 2,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SwitchPreference(
                            title = stringResource(R.string.prompt_injection_template_enabled),
                            summary = stringResource(R.string.prompt_injection_template_enabled_summary),
                            checked = enabled,
                            onCheckedChange = { enabled = it },
                        )
                        Text(
                            text = stringResource(
                                R.string.prompt_injection_editor_stats,
                                blocks.size,
                                promptCount(blocks.filter(PromptBlock::enabled).sumOf { it.content.length }),
                            ),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        PromptEditorAnalysis(analysis)
                        error?.let { message ->
                            PromptInlineStatus(text = message, isError = true)
                        }
                    }
                }
            }
        }
        item(key = "editor_blocks_header") {
            Column {
                SmallTitle(stringResource(R.string.prompt_injection_blocks))
                PromptSectionNote(stringResource(R.string.prompt_injection_blocks_note))
            }
        }
        blocks.forEachIndexed { index, block ->
            item(key = "editor_block_${block.id}") {
                PromptBlockEditor(
                    index = index,
                    block = block,
                    canMoveUp = index > 0,
                    canMoveDown = index < blocks.lastIndex,
                    onChange = { updated ->
                        setBlocks(blocks.toMutableList().also { it[index] = updated })
                    },
                    onMoveUp = {
                        if (index > 0) {
                            setBlocks(blocks.toMutableList().also {
                                val previous = it[index - 1]
                                it[index - 1] = it[index]
                                it[index] = previous
                            })
                        }
                    },
                    onMoveDown = {
                        if (index < blocks.lastIndex) {
                            setBlocks(blocks.toMutableList().also {
                                val next = it[index + 1]
                                it[index + 1] = it[index]
                                it[index] = next
                            })
                        }
                    },
                    onDelete = { setBlocks(blocks.filterNot { it.id == block.id }) },
                )
            }
        }
        item(key = "editor_add_block") {
            TextButton(
                text = stringResource(R.string.prompt_injection_add_block),
                onClick = {
                    setBlocks(
                        blocks + PromptBlock(
                            id = UUID.randomUUID().toString(),
                            title = "",
                            content = "",
                            order = blocks.size * 10,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        item(key = "editor_bottom_save") {
            TextButton(
                text = if (saving) stringResource(R.string.prompt_injection_saving) else stringResource(R.string.prompt_injection_save),
                enabled = !saving && name.isNotBlank(),
                onClick = save,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }

    if (showDiscard) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.prompt_injection_discard_title),
            summary = stringResource(R.string.prompt_injection_discard_body),
            onDismissRequest = { showDiscard = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.prompt_injection_discard),
                destructive = true,
                onCancel = { showDiscard = false },
                onConfirm = {
                    showDiscard = false
                    onBack()
                },
            )
        }
    }
}

@Composable
private fun PromptEditorAnalysis(report: PromptAnalysisReport) {
    val visible = (report.errors + report.warnings + report.issues.filter { it.level == PromptIssueLevel.INFO })
        .distinctBy { it.code to it.message }
        .take(4)
    if (visible.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        visible.forEach { issue ->
            val color = when (issue.level) {
                PromptIssueLevel.ERROR -> MiuixTheme.colorScheme.error
                PromptIssueLevel.WARNING -> MiuixTheme.colorScheme.primary
                PromptIssueLevel.INFO -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
            Text(
                text = issue.message,
                style = MiuixTheme.textStyles.footnote2,
                color = color,
            )
        }
    }
}

private fun encodeBlocks(blocks: List<PromptBlock>): String =
    JSONArray(blocks.map(PromptBlock::toJson)).toString()

private fun decodeBlocks(raw: String): List<PromptBlock> = runCatching {
    val array = JSONArray(raw)
    (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let(PromptBlock::fromJson)
    }
}.getOrDefault(emptyList())

@Composable
private fun PromptBlockEditor(
    index: Int,
    block: PromptBlock,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (PromptBlock) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            .etaGlassSurface(accent = colors.primary, cornerRadius = 16.dp),
        colors = CardDefaults.defaultColors(
            color = if (block.enabled) {
                colors.secondaryContainer.copy(alpha = 0.34f)
            } else {
                colors.surfaceContainer.copy(alpha = 0.66f)
            },
            contentColor = colors.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PromptPill(
                    text = stringResource(R.string.prompt_injection_block_number, index + 1),
                    tone = if (block.enabled) PromptPillTone.Accent else PromptPillTone.Neutral,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (block.enabled) {
                        stringResource(R.string.prompt_injection_enabled)
                    } else {
                        stringResource(R.string.prompt_injection_disabled)
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = block.enabled,
                    onCheckedChange = { onChange(block.copy(enabled = it)) },
                )
            }
            TextField(
                value = block.title,
                onValueChange = { onChange(block.copy(title = it.take(PromptInjectionStore.MAX_BLOCK_TITLE_CHARS))) },
                label = stringResource(R.string.prompt_injection_block_title),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.prompt_injection_position),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EtaChoiceChip(
                        text = stringResource(R.string.prompt_injection_position_prefix),
                        selected = block.position == PromptBlockPosition.SYSTEM_PREFIX,
                        onClick = { onChange(block.copy(position = PromptBlockPosition.SYSTEM_PREFIX)) },
                    )
                    EtaChoiceChip(
                        text = stringResource(R.string.prompt_injection_position_post),
                        selected = block.position == PromptBlockPosition.POST_HISTORY,
                        onClick = { onChange(block.copy(position = PromptBlockPosition.POST_HISTORY)) },
                    )
                }
            }
            TextField(
                value = block.content,
                onValueChange = { onChange(block.copy(content = it.take(PromptInjectionStore.MAX_BLOCK_CHARS))) },
                label = stringResource(R.string.prompt_injection_block_content),
                useLabelAsPlaceholder = true,
                minLines = 4,
                maxLines = 14,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.prompt_injection_char_count, promptCount(block.content.length)),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.prompt_injection_move_up))
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.prompt_injection_move_down))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.prompt_injection_remove_block),
                        tint = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
