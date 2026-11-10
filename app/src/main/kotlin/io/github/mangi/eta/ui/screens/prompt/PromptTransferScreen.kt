package io.github.mangi.eta.ui.screens.prompt

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.prompt.PromptInjectionConfig
import io.github.mangi.eta.agent.prompt.PromptInjectionStore
import io.github.mangi.eta.agent.prompt.PromptImportGateway
import io.github.mangi.eta.agent.prompt.PromptImportFormat
import io.github.mangi.eta.agent.prompt.PromptImportNoticeKind
import io.github.mangi.eta.agent.prompt.PromptImportPreview
import io.github.mangi.eta.agent.prompt.PromptImportResult
import io.github.mangi.eta.agent.prompt.PromptTemplate
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.etaGlassSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
internal fun PromptTransferPanel(
    store: PromptInjectionUiStore,
    onOpenExportPicker: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<PromptImportPreview?>(null) }
    val fallbackName = stringResource(R.string.prompt_injection_untitled_template)
    val importFailed = stringResource(R.string.prompt_injection_import_failed)
    val exportFailed = stringResource(R.string.prompt_injection_export_failed)
    val exportSuccess = stringResource(R.string.prompt_injection_export_success)

    fun report(message: String, error: Boolean = false) {
        status = message
        statusError = error
    }

    val exportJson = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    val raw = withContext(Dispatchers.IO) {
                        io.github.mangi.eta.agent.prompt.PromptImportCodec.exportNative(store.config)
                    }
                    writeText(context, uri, raw)
                }
                report(if (result.isSuccess) exportSuccess else exportFailed, result.isFailure)
            }
        }
    }
    val importJson = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                inspectPromptFile(context, uri).let { result ->
                    when (result) {
                        is PromptImportResult.Success -> pendingImport = result.preview
                        is PromptImportResult.Failure -> report(result.message.ifBlank { importFailed }, true)
                    }
                }
            }
        }
    }
    val importMarkdown = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                when (val result = inspectPromptFile(context, uri)) {
                    is PromptImportResult.Success -> pendingImport = result.preview
                    is PromptImportResult.Failure -> report(result.message.ifBlank { importFailed }, true)
                }
            }
        }
    }
    val importText = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                when (val result = inspectPromptFile(context, uri)) {
                    is PromptImportResult.Success -> pendingImport = result.preview
                    is PromptImportResult.Failure -> report(result.message.ifBlank { importFailed }, true)
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val colors = MiuixTheme.colorScheme
        Card(
            modifier = Modifier.etaGlassSurface(accent = colors.primary, cornerRadius = 18.dp),
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
                    text = stringResource(R.string.prompt_injection_transfer_title),
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.prompt_injection_transfer_note),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        status?.let { message -> PromptInlineStatus(text = message, isError = statusError) }
        SmallTitle(stringResource(R.string.prompt_injection_native_json))
        Card(Modifier.etaGlassSurface(accent = colors.secondary, cornerRadius = 16.dp)) {
            ArrowPreference(
                title = stringResource(R.string.prompt_injection_export_json),
                summary = stringResource(R.string.prompt_injection_export_json_summary),
                onClick = {
                    exportJson.launch("eta-prompt-injection-" + fileStamp() + ".json")
                },
            )
            ArrowPreference(
                title = stringResource(R.string.prompt_injection_import_json),
                summary = stringResource(R.string.prompt_injection_import_json_summary),
                onClick = { importJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            )
        }
        SmallTitle(stringResource(R.string.prompt_injection_text_formats))
        Card(Modifier.etaGlassSurface(accent = colors.secondary, cornerRadius = 16.dp)) {
            ArrowPreference(
                title = stringResource(R.string.prompt_injection_import_md),
                summary = stringResource(R.string.prompt_injection_import_md_summary),
                onClick = { importMarkdown.launch(arrayOf("text/markdown", "text/plain", "text/*")) },
            )
            ArrowPreference(
                title = stringResource(R.string.prompt_injection_import_txt),
                summary = stringResource(R.string.prompt_injection_import_txt_summary),
                onClick = { importText.launch(arrayOf("text/plain", "text/*")) },
            )
            ArrowPreference(
                title = stringResource(R.string.prompt_injection_export_template),
                summary = stringResource(R.string.prompt_injection_export_template_summary),
                onClick = onOpenExportPicker,
            )
        }
        Text(
            text = stringResource(R.string.prompt_injection_template_export_note),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    pendingImport?.let { preview ->
        val nativeRestore = preview.format == PromptImportFormat.ETA_NATIVE
        val incoming = PromptInjectionConfig(
            templates = preview.templates,
            bindings = if (nativeRestore) preview.bindings else emptyList(),
        )
        val summary = if (nativeRestore) {
            stringResource(
                R.string.prompt_injection_import_native_confirm,
                store.config.templates.size,
                store.config.bindings.size,
                incoming.templates.size,
                incoming.bindings.size,
            )
        } else {
            stringResource(R.string.prompt_injection_import_external_confirm, incoming.templates.size)
        }
        WindowDialog(
            show = true,
            title = stringResource(R.string.prompt_injection_import_confirm_title),
            summary = summary,
            onDismissRequest = { pendingImport = null },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = formatLabel(preview.format),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = preview.name.ifBlank { fallbackName },
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                )
                if (preview.description.isNotBlank()) {
                    Text(
                        text = preview.description,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.prompt_injection_import_preview_counts,
                        preview.templates.size,
                        preview.blockCount,
                        preview.characterCount,
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = preview.templates.joinToString("\n") { template ->
                        template.name.ifBlank { fallbackName }
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = stringResource(
                        R.string.prompt_injection_import_source,
                        preview.sourceBytes,
                        preview.sourceSha256,
                    ),
                    style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = stringResource(
                        if (nativeRestore) R.string.prompt_injection_import_native_warning
                        else R.string.prompt_injection_import_external_warning,
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (nativeRestore) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (preview.notices.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.prompt_injection_import_notices),
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium,
                    )
                    preview.notices.forEach { notice ->
                        Text(
                            text = "• " + notice.message,
                            style = MiuixTheme.textStyles.footnote2,
                            color = if (notice.kind == PromptImportNoticeKind.UNTRUSTED_TOOL_DECLARATION) {
                                MiuixTheme.colorScheme.error
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                        )
                    }
                }
            }
            MiuixDialogActions(
                confirmText = stringResource(
                    if (nativeRestore) R.string.prompt_injection_restore_action
                    else R.string.prompt_injection_import_action,
                ),
                onCancel = { pendingImport = null },
                onConfirm = {
                    pendingImport = null
                    scope.launch {
                        val added = if (nativeRestore) {
                            if (store.replaceConfig(incoming)) incoming.templates.size else null
                        } else {
                            importTemplates(store, preview, fallbackName)
                        }
                        report(
                            if (added != null) {
                                resources.getString(R.string.prompt_injection_import_success, added)
                            } else {
                                importFailed
                            },
                            error = added == null,
                        )
                    }
                },
            )
        }
    }
}

@Composable
internal fun PromptExportPickerScreen(
    store: PromptInjectionUiStore,
    onBack: () -> Unit,
    onExported: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<PromptTemplate, String>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    val exportSuccess = stringResource(R.string.prompt_injection_export_success)
    val exportFailed = stringResource(R.string.prompt_injection_export_failed)
    val templates = store.config.templates.sortedBy { it.name.lowercase() }

    val markdownExport = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val target = pending
        pending = null
        if (uri != null && target != null) {
            scope.launch {
                val result = runCatching {
                    writeText(context, uri, PromptInjectionTextCodec.exportMarkdown(target.first))
                }
                if (result.isSuccess) {
                    store.report(exportSuccess)
                    onExported()
                } else {
                    status = exportFailed
                    statusError = true
                }
            }
        }
    }
    val textExport = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val target = pending
        pending = null
        if (uri != null && target != null) {
            scope.launch {
                val result = runCatching {
                    writeText(context, uri, PromptInjectionTextCodec.exportText(target.first))
                }
                if (result.isSuccess) {
                    store.report(exportSuccess)
                    onExported()
                } else {
                    status = exportFailed
                    statusError = true
                }
            }
        }
    }

    BackHandler { onBack() }
    MiuixScaffoldPage(
        title = stringResource(R.string.prompt_injection_export_template),
        onBack = onBack,
    ) {
        item(key = "export_picker_note") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.prompt_injection_export_picker_title),
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.prompt_injection_export_picker_note),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        status?.let { message ->
            item(key = "export_picker_status") {
                PromptInlineStatus(
                    text = message,
                    isError = statusError,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        if (templates.isEmpty()) {
            item(key = "export_picker_empty") {
                PromptEmptyState(
                    title = stringResource(R.string.prompt_injection_empty_title),
                    summary = stringResource(R.string.prompt_injection_empty_summary),
                    icon = Icons.Rounded.Description,
                )
            }
        }
        templates.forEach { template ->
            item(key = "export_picker_${template.id}") {
                Card(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = template.name.ifBlank {
                                stringResource(R.string.prompt_injection_untitled_template)
                            },
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        PromptMetaRow(
                            items = listOf(
                                stringResource(R.string.prompt_injection_block_count, template.blocks.size),
                                stringResource(R.string.prompt_injection_char_count, promptCount(template.characterCount)),
                            ),
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                text = stringResource(R.string.prompt_injection_export_md),
                                onClick = {
                                    pending = template to "markdown"
                                    markdownExport.launch(safeFileName(template.name) + ".md")
                                },
                            )
                            TextButton(
                                text = stringResource(R.string.prompt_injection_export_txt),
                                onClick = {
                                    pending = template to "text"
                                    textExport.launch(safeFileName(template.name) + ".txt")
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun inspectPromptFile(context: Context, uri: Uri): PromptImportResult =
    withContext(Dispatchers.IO) {
        try {
            PromptImportGateway.inspectUri(context.contentResolver, uri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            PromptImportResult.Failure(
                code = io.github.mangi.eta.agent.prompt.PromptImportErrorCode.READ_FAILED,
                message = failure.message.orEmpty(),
            )
        }
    }

/**
 * Appends imported text templates to the current library. Each imported template gets a fresh id
 * and a name that cannot collide with an existing template.
 *
 * Returns the number of added templates, or null when nothing could be saved.
 */
private suspend fun importTemplates(
    store: PromptInjectionUiStore,
    preview: PromptImportPreview,
    fallbackName: String,
): Int? {
    if (preview.templates.isEmpty()) return null
    val current = store.config.templates
    if (current.size + preview.templates.size > PromptInjectionStore.MAX_TEMPLATES) return null
    val usedNames = current.mapTo(hashSetOf(), PromptTemplate::name)
    val now = System.currentTimeMillis()
    val imported = preview.templates.map { template ->
        val name = uniqueImportedName(template.name, usedNames, fallbackName)
        usedNames += name
        template.copy(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAt = now,
            updatedAt = now,
            source = template.source.ifBlank { "import" },
        )
    }
    val saved = store.replaceConfig(store.config.copy(templates = current + imported))
    return if (saved) imported.size else null
}

private fun uniqueImportedName(
    name: String,
    used: Set<String>,
    fallbackName: String,
): String {
    val base = name.trim()
        .ifBlank { fallbackName }
        .take(PromptInjectionStore.MAX_TEMPLATE_NAME_CHARS)
    if (base !in used) return base
    var index = 2
    while (true) {
        val suffix = " ($index)"
        val stem = base.take((PromptInjectionStore.MAX_TEMPLATE_NAME_CHARS - suffix.length).coerceAtLeast(1))
        val candidate = stem + suffix
        if (candidate !in used) return candidate
        index += 1
    }
}

@Composable
private fun formatLabel(format: PromptImportFormat): String = stringResource(
    when (format) {
        PromptImportFormat.ETA_NATIVE -> R.string.prompt_injection_format_native
        PromptImportFormat.PRO_PRESET -> R.string.prompt_injection_format_pro
        PromptImportFormat.SILLYTAVERN_PRESET -> R.string.prompt_injection_format_sillytavern
        PromptImportFormat.GENERIC_JSON -> R.string.prompt_injection_format_json
        PromptImportFormat.MARKDOWN -> R.string.prompt_injection_format_markdown
        PromptImportFormat.PLAIN_TEXT -> R.string.prompt_injection_format_text
        PromptImportFormat.CHARACTER_CARD_PNG -> R.string.prompt_injection_format_character_card
    },
)

private suspend fun writeText(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    val output = context.contentResolver.openOutputStream(uri, "wt")
        ?: throw IllegalStateException("Cannot open output")
    output.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(text) }
}

private fun fileStamp(): String =
    SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

private fun safeFileName(name: String): String = name.trim()
    .ifBlank { "prompt-template" }
    .replace(Regex("[\\\\/:*?\"<>|]"), "-")
    .take(80)
