package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mangi.eta.data.repository.*
import io.github.mangi.eta.ui.components.*
import io.github.mangi.eta.ui.pages.providers.TagChip
import io.github.mangi.eta.ui.pages.providers.TagChipTone
import java.io.InputStream
import java.io.OutputStream
import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun DataBackupScreen(
    context: Context,
    onBack: () -> Unit,
    onExport: suspend (OutputStream, String?, (EtaBackupProgress) -> Unit, EtaBackupOptions) -> EtaBackupSummary,
    onImport: suspend (InputStream, String?, (String) -> Unit) -> EtaBackupSummary,
    model: BackupViewModel = viewModel(),
) {
    val app = context.applicationContext
    val vm = model
    var options by vm.optionsState
    var auto by remember { mutableStateOf(AutoBackupStore.read(app)) }
    var autoStatus by remember { mutableStateOf(AutoBackupStore.status(app)) }
    var exportDialog by vm.exportDialogState
    var importUri by vm.importUriState
    var importDialog by vm.importDialogState
    var autoPasswordDialog by remember { mutableStateOf(false) }
    var autoEncrypted by remember { mutableStateOf(AutoBackupPassword.configured(app)) }
    var restartRequested by remember { mutableStateOf(false) }
    var preview by vm.previewState
    var password by vm.passwordState
    var destinationPassword by vm.destinationPasswordState
    var selectedOptions by vm.selectedOptionsState
    val busy = vm.operation != null
    val pending = vm.pendingRestart
    val listState = rememberLazyListState()
    BackHandler(enabled = busy) { vm.status = "正在处理备份，可先取消操作再返回" }
    LaunchedEffect(vm.operation) {
        vm.pendingRestart = withContext(Dispatchers.IO) { EtaFullBackupArchive.hasPendingRestore(app) }
        if (vm.pendingRestart && vm.operation == null) vm.status = "备份已暂存，请重启完成恢复"
        if (vm.status == null && vm.operation == null) vm.status = withContext(Dispatchers.IO) { EtaFullBackupArchive.restoreResult(app) }
    }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(2000); autoStatus = AutoBackupStore.status(app) }
    }
    LaunchedEffect(pending) {
        if (pending) listState.scrollToItem(0)
    }
    fun saveAuto(value: AutoBackupSettings) {
        try { AutoBackupStore.save(app, value); AutoBackupScheduler.sync(app); auto = value }
        catch (failure: Exception) { vm.status = failure.message ?: "设置保存失败" }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val secret = destinationPassword
        destinationPassword = null
        if (uri != null) vm.start(BackupOperation.EXPORT) { progress ->
            val summary = EtaBackupDocumentExport.export(app, uri, secret, progress) { output, pass, update -> onExport(output, pass, update, selectedOptions) }
            "导出成功 · ${summary.conversationCount} 个会话 · ${summary.providerCount} 个服务商"
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { importUri = uri; password = ""; preview = null; importDialog = true }
    }
    val directoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            saveAuto(auto.copy(tree = uri.toString()))
        } catch (failure: Exception) { vm.status = "目录授权失败，请重新选择可写目录" }
    }
    MiuixScaffoldPage(title = "迁移、备份与恢复", onBack = { if (!busy) onBack() else vm.status = "正在处理备份，可先取消操作再返回" }, listState = listState, overlay = {
        if (autoPasswordDialog) WindowDialog(show = true, title = "自动备份密码", summary = "密码加密保存在本机。换机恢复时需要自行输入此密码；留空则取消加密。", onDismissRequest = { autoPasswordDialog = false; password = "" }) {
            PasswordInput(password, { password = it })
            MiuixDialogActions(confirmText = "保存", cancelText = "取消", onCancel = { autoPasswordDialog = false; password = "" }, onConfirm = {
                try { AutoBackupPassword.save(app, password); autoEncrypted = password.isNotEmpty(); autoPasswordDialog = false; password = "" }
                catch (_: Exception) { vm.status = "密码保存失败，请重试" }
            })
        }
        if (exportDialog) WindowDialog(show = true, title = "导出数据", onDismissRequest = { exportDialog = false; password = "" }) {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
                BackupOptionsPanel(options, { options = it })
                PasswordInput(password, { password = it })
            }
            MiuixDialogActions(confirmText = "选择位置", cancelText = "取消", confirmEnabled = options.contents.isNotEmpty(), onCancel = { exportDialog = false; password = "" }, onConfirm = {
                selectedOptions = options.validated()
                destinationPassword = password.takeIf { it.isNotEmpty() }
                val suffix = if (destinationPassword != null && options.format == EtaBackupFormat.JSON) ".eta-backup" else options.format.suffix
                password = ""; exportDialog = false
                exportLauncher.launch("Nexus-backup-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}$suffix")
            })
        }
        if (importDialog) WindowDialog(show = true, title = if (preview == null) "校验备份" else "确认恢复", onDismissRequest = { importDialog = false; password = ""; preview = null }) {
            val inspected = preview
            if (inspected == null) {
                Text("选择的文件会先校验，校验完成后确认恢复。", style = MiuixTheme.textStyles.footnote1)
                PasswordInput(password, { password = it })
            } else Text("包含：${inspected.includedContents.joinToString("、") { it.title }}\n${inspected.conversationCount} 个会话 · ${inspected.messageCount} 条消息\n${inspected.providerCount} 个服务商 · ${inspected.characterCount} 个角色\n\n" +
                if (inspected.fullArchive) "完整迁移会替换应用内的数据和配置。恢复前会保留安全快照，完成后需要重启。" else "只替换备份中包含的数据类别；其他类别保留。JSON 不包含会议室、文件及全部应用设置。",
                style = MiuixTheme.textStyles.body2)
            MiuixDialogActions(confirmText = if (inspected == null) "开始校验" else "恢复数据", cancelText = "取消", destructive = inspected != null,
                onCancel = { importDialog = false; password = ""; preview = null }, onConfirm = {
                    val uri = importUri ?: return@MiuixDialogActions
                    importDialog = false
                    if (inspected == null) vm.start(BackupOperation.INSPECT) { progress ->
                        preview = EtaBackupDocumentImport.read(app, uri, progress) {
                            EtaFullBackupArchive.inspect(
                                context = app,
                                input = it,
                                password = password.takeIf { value -> value.isNotEmpty() },
                                onPhase = { phase -> progress(EtaBackupProgress(phase)) },
                            )
                        }
                        importDialog = true
                        "备份校验通过，请确认恢复内容"
                    } else {
                        val secret = password.takeIf { it.isNotEmpty() }; password = ""; preview = null
                        vm.start(BackupOperation.IMPORT) { update ->
                            val summary = EtaBackupDocumentImport.read(app, uri, update) { input -> onImport(input, secret) { update(EtaBackupProgress(it)) } }
                            vm.pendingRestart = summary.restartRequired
                            if (summary.restartRequired) "备份已校验并暂存，请重启完成恢复" else "恢复完成 · ${summary.conversationCount} 个会话 · ${summary.providerCount} 个服务商"
                        }
                    }
                })
        }
    }) {
        if (pending) item(key = "restart") {
            SmallTitle("恢复待完成", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).padding(bottom = EtaUiMetrics.groupGap).etaGlassSurface(accent = MiuixTheme.colorScheme.secondary)) {
                ArrowPreference(title = "重启完成恢复", summary = "恢复数据已暂存", startAction = { PreferenceIcon(Icons.Rounded.RestartAlt, EtaIconTone.DATA) }, onClick = {
                    if (!restartRequested) {
                        restartRequested = true
                        try {
                            context.startActivity(
                                Intent(context, BackupRestartActivity::class.java)
                                    .putExtra("previous_pid", Process.myPid())
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (failure: Exception) {
                            restartRequested = false
                            vm.status = failure.message ?: "无法启动恢复重启，请手动关闭并重新打开 Nexus"
                        }
                    }
                })
            }
        }
        item(key = "backup_overview") {
            BackupOverviewCard(auto = auto, autoStatus = autoStatus, autoEncrypted = autoEncrypted)
        }
        item(key = "migration_wizard") {
            SmallTitle("从旧 Eta 迁移", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            MigrationWizardCard(
                enabled = !busy && !pending,
                onSelectBackup = { importLauncher.launch(arrayOf("*/*")) },
            )
        }
        item(key = "actions") {
            SmallTitle("备份与恢复", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).padding(bottom = EtaUiMetrics.groupGap),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BackupActionTile(
                    title = "导出数据",
                    caption = if (isExportOperation(vm.operation)) "正在导出" else "选择内容与格式",
                    icon = Icons.Rounded.Upload,
                    tone = EtaIconTone.DATA,
                    enabled = !busy && !pending,
                    busy = isExportOperation(vm.operation),
                    onClick = { password = ""; exportDialog = true },
                    modifier = Modifier.weight(1f),
                )
                BackupActionTile(
                    title = "导入或迁移",
                    caption = if (isImportOperation(vm.operation)) "正在校验或恢复" else "Eta 备份也可导入",
                    icon = Icons.Rounded.Download,
                    tone = EtaIconTone.SYSTEM,
                    enabled = !busy && !pending,
                    busy = isImportOperation(vm.operation),
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        vm.progress?.let { progress -> item(key = "progress") {
            SmallTitle(if (isExportOperation(vm.operation)) "导出进度" else "恢复进度", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).padding(bottom = EtaUiMetrics.groupGap).etaGlassSurface(accent = MiuixTheme.colorScheme.primary)) {
                val size = if (progress.bytes > 0) android.text.format.Formatter.formatShortFileSize(app, progress.bytes) +
                    (progress.totalBytes?.let { " / ${android.text.format.Formatter.formatShortFileSize(app, it)}" } ?: "") else null
                val summary = listOfNotNull(
                    progress.files.takeIf { it > 0 }?.let { "已处理 $it 项" },
                    progress.records.takeIf { it > 0 }?.let { "已写入 ${NumberFormat.getIntegerInstance().format(it)} 条记录" },
                    size,
                ).joinToString(" · ").ifBlank { backupPhaseDetail(progress.phase) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfiniteProgressIndicator(size = 22.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(backupPhaseLabel(progress.phase), style = MiuixTheme.textStyles.body1, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(summary, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    TextButton(text = "取消", onClick = vm::cancel)
                }
            }
        } }
        vm.status?.let { status -> item(key = "status") {
            BackupStatusBanner(status)
        } }
        item(key = "automatic") {
            SmallTitle("自动备份", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            BackupGroup("备份计划", Icons.Rounded.Schedule, EtaIconTone.AUTOMATION, if (auto.enabled) "已启用" else "未启用") {
                SwitchPreference(title = "定期备份", checked = auto.enabled, enabled = !busy && !pending && auto.tree.isNotBlank(), onCheckedChange = { saveAuto(auto.copy(enabled = it)) })
                ArrowPreference(title = "备份目录", summary = backupDirectoryLabel(auto.tree),
                    enabled = !busy && !pending, onClick = { directoryLauncher.launch(auto.tree.takeIf { it.isNotBlank() }?.let(Uri::parse)) })
                val dayOptions = (1..30).map { "每 $it 天" }
                DialogChoicePreference(title = "备份间隔", value = dayOptions[auto.days - 1], options = dayOptions, selectedIndex = auto.days - 1, enabled = !busy && !pending, onSelectedIndexChange = { saveAuto(auto.copy(days = it + 1)) })
                val hourOptions = (0..23).map { "%02d:00".format(it) }
                DialogChoicePreference(title = "执行时间", value = hourOptions[auto.hour], options = hourOptions, selectedIndex = auto.hour, enabled = !busy && !pending, onSelectedIndexChange = { saveAuto(auto.copy(hour = it)) })
                ArrowPreference(title = "立即备份一次", summary = autoStatus.takeIf { it.isNotBlank() }, enabled = !busy && !pending && auto.tree.isNotBlank(), onClick = { AutoBackupScheduler.enqueue(app); autoStatus = "已安排备份" })
            }
            BackupGroup("保留与安全", Icons.Rounded.Shield, EtaIconTone.SYSTEM, if (autoEncrypted) "已加密" else "未加密") {
                val keepOptions = (1..30).map { "最近 $it 份" }
                DialogChoicePreference(title = "保留份数", value = keepOptions[auto.keep - 1], options = keepOptions, selectedIndex = auto.keep - 1, enabled = !busy && !pending, onSelectedIndexChange = { saveAuto(auto.copy(keep = it + 1)) })
                SwitchPreference(title = "仅充电时备份", checked = auto.chargingOnly, enabled = !busy && !pending, onCheckedChange = { saveAuto(auto.copy(chargingOnly = it)) })
                ArrowPreference(title = "备份加密", summary = if (autoEncrypted) "已设置密码" else "未设置密码", enabled = !busy && !pending, onClick = { password = ""; autoPasswordDialog = true })
            }
            BackupGroup("备份内容", Icons.Rounded.Inventory2, EtaIconTone.DATA, auto.options.format.title) {
                BackupOptionsPanel(auto.options, { saveAuto(auto.copy(options = it)) }, enabled = !busy && !pending)
            }
        }
    }
}

@Composable
private fun BackupActionTile(
    title: String,
    caption: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tone: EtaIconTone,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier.heightIn(min = 112.dp).etaGlassSurface(accent = tone.accent, cornerRadius = 18.dp)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainer, MiuixTheme.colorScheme.onSurface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EtaIconTile(icon, tone = tone, size = 38.dp, glyphSize = 20.dp, enabled = enabled)
                Spacer(Modifier.weight(1f))
                if (busy) InfiniteProgressIndicator(size = 18.dp)
                else Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(18.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MiuixTheme.textStyles.body1, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
                Text(caption, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BackupGroup(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tone: EtaIconTone,
    status: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .etaGlassSurface(accent = tone.accent, cornerRadius = 18.dp),
        colors = CardDefaults.defaultColors(colors.surfaceContainer, colors.onSurface),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EtaIconTile(icon, tone = tone, size = 34.dp, glyphSize = 18.dp)
                Text(title, Modifier.weight(1f), style = MiuixTheme.textStyles.body1, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                EtaStatusPill(status)
            }
            HorizontalDivider(color = colors.dividerLine.copy(alpha = .5f))
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
private fun MigrationWizardCard(
    enabled: Boolean,
    onSelectBackup: () -> Unit,
) {
    Card(
        Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .padding(bottom = EtaUiMetrics.groupGap)
            .etaGlassSurface(accent = MiuixTheme.colorScheme.secondary),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                "安全迁移向导",
                style = MiuixTheme.textStyles.body1,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Text(
                "旧 Eta 与 Nexus 使用独立私有目录。请先在旧 Eta 中导出加密完整备份，再从这里选择文件；Nexus 不会读取另一应用的私有数据库。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            listOf(
                "1  在旧 Eta 导出加密完整备份",
                "2  选择文件并校验清单、密码和磁盘空间",
                "3  预览内容，确认后暂存恢复并重启 Nexus",
            ).forEach { step ->
                Text(
                    step,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            TextButton(
                text = "选择 Eta 备份并校验",
                enabled = enabled,
                onClick = onSelectBackup,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun BackupOverviewCard(auto: AutoBackupSettings, autoStatus: String, autoEncrypted: Boolean) {
    val configured = auto.tree.isNotBlank()
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).etaGlassSurface(accent = MiuixTheme.colorScheme.primary)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EtaIconTile(Icons.Rounded.Backup, tone = EtaIconTone.DATA, size = 42.dp, glyphSize = 22.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("备份中心", style = MiuixTheme.textStyles.title3, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(
                        if (configured) "每 ${auto.days} 天 · ${auto.hour.toString().padStart(2, '0')}:00" else "选择目录后可启用自动备份",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                EtaStatusPill(
                    text = if (auto.enabled && configured) "已启用" else if (configured) "已配置" else "未配置",
                    tone = if (auto.enabled && configured) EtaStatusTone.Positive else EtaStatusTone.Neutral,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagChip(backupDirectoryLabel(auto.tree), if (configured) TagChipTone.Emphasized else TagChipTone.Normal)
                if (autoEncrypted) TagChip("已加密")
                if (auto.chargingOnly) TagChip("仅充电")
            }
            if (autoStatus.isNotBlank()) {
                Text(autoStatus, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun BackupStatusBanner(status: String) {
    val error = status.contains("失败") || status.contains("错误") || status.contains("无法")
    val positive = status.contains("成功") || status.contains("完成")
    val tone = when {
        error -> EtaStatusTone.Danger
        positive -> EtaStatusTone.Positive
        else -> EtaStatusTone.Neutral
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EtaStatusPill(text = if (error) "未完成" else if (positive) "已完成" else "处理中", tone = tone)
        Text(status, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
    }
}

internal fun backupDirectoryLabel(tree: String): String {
    if (tree.isBlank()) return "选择可写文件夹后启用自动备份"
    val documentId = runCatching { Uri.parse(tree).lastPathSegment.orEmpty() }.getOrDefault("")
    return documentId
        .substringAfter(':', documentId)
        .trim('/')
        .split('/')
        .filter(String::isNotBlank)
        .joinToString(" / ")
        .ifBlank { "已选择备份文件夹" }
}

internal fun isExportOperation(operation: BackupOperation?): Boolean = operation == BackupOperation.EXPORT

internal fun isImportOperation(operation: BackupOperation?): Boolean =
    operation == BackupOperation.IMPORT || operation == BackupOperation.INSPECT

@Composable private fun PasswordInput(value: String, change: (String) -> Unit) {
    TextField(value = value, onValueChange = change, label = "密码（可选）", singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
}

@Composable internal fun BackupOptionsPanel(options: EtaBackupOptions, change: (EtaBackupOptions) -> Unit, enabled: Boolean = true) {
    val formatOptions = EtaBackupFormat.entries.map { it.title }
    DialogChoicePreference(title = "备份格式", value = formatOptions[options.format.ordinal], options = formatOptions, selectedIndex = options.format.ordinal, enabled = enabled,
        onSelectedIndexChange = { change(options.copy(format = EtaBackupFormat.entries[it])) })
    if (options.format == EtaBackupFormat.FULL) {
        SwitchPreference(
            title = "包含全部应用文件",
            summary = if (options.includeAppFiles) {
                "包含项目文件、技能、工作区和 Linux 环境；文件很多时导出会明显变慢"
            } else {
                "仅备份会话、配置、记忆与数据库；恢复时不会删除现有文件"
            },
            checked = options.includeAppFiles,
            enabled = enabled,
            onCheckedChange = { change(options.copy(includeAppFiles = it)) },
        )
        val compressionOptions = EtaBackupCompression.entries.map { it.title }
        DialogChoicePreference(
            title = "打包方式",
            value = options.compression.title,
            options = compressionOptions,
            selectedIndex = options.compression.ordinal,
            enabled = enabled,
            onSelectedIndexChange = { change(options.copy(compression = EtaBackupCompression.entries[it])) },
        )
    } else EtaBackupContent.entries.forEach { content ->
        SwitchPreference(title = content.title, summary = content.detail, checked = content in options.contents, enabled = enabled && (options.contents.size > 1 || content !in options.contents),
            onCheckedChange = { change(options.copy(contents = if (it) options.contents + content else options.contents - content)) })
    }
}

internal fun backupPhaseDetail(phase: String): String = when (phase) {
    "checking_runtime" -> "正在确认没有运行中的任务，避免产生不一致备份"
    "checkpoint_databases" -> "正在把数据库临时状态合并到主文件"
    "prepare_export", "stage_export" -> "正在准备会话、记忆与配置"
    "export_conversations" -> "正在写入会话列表"
    "export_messages" -> "正在写入消息与分支记录"
    "export_checkpoints" -> "正在写入上下文检查点"
    "export_roleplay" -> "正在写入角色与消息来源"
    "logical_snapshot", "validate_logical_data" -> "正在生成可跨版本恢复的会话与记忆数据"
    "private_data" -> "正在扫描应用文件；文件数量较多时可能需要几分钟"
    "portable_secrets" -> "正在打包加密后的登录与连接凭据"
    "manifest", "verify_manifest", "verify_file" -> "正在计算校验值，确保文件完整"
    "validate_database" -> "正在检查数据库版本与完整性"
    "copy_document" -> "本地打包完成，正在写入所选位置"
    "copy_export" -> "正在写入兼容备份数据"
    "export_complete" -> "备份数据已经生成，正在完成文件写入"
    "extract", "read_document" -> "正在把备份读取到临时空间"
    "safety_snapshot" -> "正在保存恢复前的安全快照"
    "stage_restore" -> "正在暂存已校验的恢复数据"
    else -> "操作仍在进行；可取消后重新选择范围"
}

internal fun backupPhaseLabel(phase: String): String = when (phase) {
    "checking_runtime" -> "检查运行中的任务"
    "checkpoint_databases" -> "保存数据库状态"
    "prepare_export", "stage_export" -> "准备导出数据"
    "export_conversations" -> "打包会话"
    "export_messages" -> "打包消息"
    "export_checkpoints" -> "打包上下文"
    "export_roleplay" -> "打包角色数据"
    "logical_snapshot", "validate_logical_data" -> "校验会话与记忆"
    "private_data" -> "打包应用文件"
    "portable_secrets" -> "整理迁移凭据"
    "manifest", "verify_manifest", "verify_file" -> "校验备份文件"
    "validate_database" -> "检查数据库"
    "copy_document" -> "写入目标文件"
    "copy_export" -> "写入兼容数据"
    "export_complete" -> "完成导出"
    "extract" -> "读取并解包备份"
    "read_document" -> "读取所选文件"
    "safety_snapshot" -> "保存恢复前的安全快照"
    "stage_restore" -> "暂存恢复数据"
    "inspect_complete" -> "校验完成"
    else -> when {
        phase.startsWith("read_") -> "读取备份记录"
        phase.startsWith("restore_") || phase.startsWith("clear_") || phase.startsWith("activate_") -> "恢复数据与配置"
        phase.startsWith("validate_") -> "校验数据关联"
        else -> "正在处理备份"
    }
}
