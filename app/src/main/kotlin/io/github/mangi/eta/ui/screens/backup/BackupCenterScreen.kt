package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.FlowRow
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    var options by vm.optionsState
    var auto by remember { mutableStateOf(AutoBackupStore.read(app)) }
    val backupStatusFlow = remember(app) { AutoBackupStore.observeStatus(app) }
    val backupSettingsFlow = remember(app) { AutoBackupStore.observeSettings(app) }
    val autoStatusSnapshot by backupStatusFlow.collectAsStateWithLifecycle(
        initialValue = remember(app) { AutoBackupStore.status(app) to AutoBackupStore.lastSuccess(app) },
    )
    val (autoStatus, lastAutoSuccess) = autoStatusSnapshot
    val currentAutoSettings by backupSettingsFlow.collectAsStateWithLifecycle(initialValue = auto)
    LaunchedEffect(currentAutoSettings) { auto = currentAutoSettings }
    val activityFlow = remember(app) { AutoBackupScheduler.observeActivity(app) }
    val autoActivity by activityFlow.collectAsStateWithLifecycle(initialValue = AutoBackupActivity())
    var backupDirectoryGranted by remember { mutableStateOf(false) }
    var exportDialog by vm.exportDialogState
    var importUri by vm.importUriState
    var importDialog by vm.importDialogState
    var autoPasswordDialog by remember { mutableStateOf(false) }
    var autoEncrypted by remember { mutableStateOf(AutoBackupPassword.configured(app)) }
    var restartRequested by remember { mutableStateOf(false) }
    var preview by vm.previewState
    var importSha256 by vm.importSha256State
    var password by vm.passwordState
    var destinationPassword by vm.destinationPasswordState
    var selectedOptions by vm.selectedOptionsState
    var exportPasswordRequired by remember { mutableStateOf(false) }
    var checkingExportPolicy by remember { mutableStateOf(false) }
    var exportPolicyError by remember { mutableStateOf<String?>(null) }
    var autoPasswordRequired by remember { mutableStateOf(false) }
    var pendingAutoSettings by remember { mutableStateOf<AutoBackupSettings?>(null) }
    var pendingAutoExpected by remember { mutableStateOf<AutoBackupSettings?>(null) }
    var passwordExpected by remember { mutableStateOf<AutoBackupSettings?>(null) }
    var savingAuto by remember { mutableStateOf(false) }
    val localBusy = vm.operation != null || savingAuto
    val busy = localBusy || autoActivity.running
    val pending = vm.pendingRestart
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val backupListState = rememberLazyListState()
    val automaticListState = rememberLazyListState()
    LaunchedEffect(selectedTab) { if (selectedTab !in 0..1) selectedTab = 0 }
    val listState = if (selectedTab == 1) automaticListState else backupListState
    BackHandler(enabled = localBusy) { vm.status = "正在处理备份，可先取消操作再返回" }
    LaunchedEffect(vm.operation) {
        vm.pendingRestart = withContext(Dispatchers.IO) { EtaFullBackupArchive.hasPendingRestore(app) }
        if (vm.pendingRestart && vm.operation == null) vm.status = "备份已暂存，请重启完成恢复"
        if (vm.status == null && vm.operation == null) vm.status = withContext(Dispatchers.IO) { EtaFullBackupArchive.restoreResult(app) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, app, auto.tree) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch {
                backupDirectoryGranted = withContext(Dispatchers.IO) { backupDirectoryGrantValid(app, auto.tree) }
                autoEncrypted = AutoBackupPassword.configured(app)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(exportDialog, options) {
        if (!exportDialog) return@LaunchedEffect
        checkingExportPolicy = true; exportPolicyError = null
        try { exportPasswordRequired = EtaBackupOperations.requiresPassword(app, options) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { exportPolicyError = "无法检查备份凭据，请稍后重试" }
        finally { checkingExportPolicy = false }
    }
    LaunchedEffect(auto.options) {
        try { autoPasswordRequired = EtaBackupOperations.requiresPassword(app, auto.options) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { autoPasswordRequired = true }
    }
    LaunchedEffect(auto.tree) {
        backupDirectoryGranted = withContext(Dispatchers.IO) { backupDirectoryGrantValid(app, auto.tree) }
    }
    fun saveAuto(value: AutoBackupSettings, expected: AutoBackupSettings = auto) {
        if (savingAuto) return
        savingAuto = true
        scope.launch {
            try {
                if (withContext(Dispatchers.IO) { AutoBackupStore.read(app) } != expected) {
                    auto = withContext(Dispatchers.IO) { AutoBackupStore.read(app) }
                    vm.status = "自动备份设置已更改，请核对后重试"
                    return@launch
                }
                val required = EtaBackupOperations.requiresPassword(app, value.options)
                autoPasswordRequired = required
                if (value.enabled && required && !autoEncrypted) {
                    pendingAutoSettings = value; pendingAutoExpected = expected; password = ""; autoPasswordDialog = true
                } else {
                    auto = withContext(Dispatchers.IO) { AutoBackupSettingsRepository.saveFromUi(app, expected, value) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { vm.status = failure.message ?: "设置保存失败" }
            finally { savingAuto = false }
        }
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
        if (uri != null) { importUri = uri; password = ""; preview = null; importSha256 = null; importDialog = true }
    }
    val directoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            saveAuto(auto.copy(tree = uri.toString()))
        } catch (failure: Exception) { vm.status = "目录授权失败，请重新选择可写目录" }
    }
    MiuixScaffoldPage(title = "备份与恢复", onBack = { if (!localBusy) onBack() else vm.status = "正在处理备份，可先取消操作再返回" }, listState = listState,
        aboveContent = {
            Column {
                NexaSegmentedTabs(
                    tabs = listOf("手动与配置", "自动计划"),
                    selectedTabIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                if (pending) {
                    Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        ArrowPreference(
                            title = "重启完成恢复",
                            summary = "恢复数据已暂存",
                            enabled = !restartRequested && !busy,
                            startAction = { PreferenceIcon(Icons.Rounded.RestartAlt, EtaIconTone.DATA) },
                            onClick = {
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
                                        vm.status = failure.message ?: "无法启动恢复重启，请手动关闭并重新打开 NEXA"
                                    }
                                }
                            },
                        )
                    }
                }
                vm.progress?.let { progress ->
                    BackupProgressBanner(
                        context = app,
                        progress = progress,
                        onCancel = vm::cancel,
                    )
                }
                if (autoActivity.running && vm.operation == null) {
                    BackupStatusBanner("目录备份正在进行，请稍候")
                }
                vm.status?.let { BackupStatusBanner(it) }
            }
        }, overlay = {
        if (autoPasswordDialog) WindowDialog(show = true, title = "目录备份密码", summary = "密码加密保存在本机，换机恢复时需要输入。", onDismissRequest = { autoPasswordDialog = false; password = ""; pendingAutoSettings = null; pendingAutoExpected = null; passwordExpected = null }) {
            PasswordInput(password, { password = it }, required = autoPasswordRequired)
            MiuixDialogActions(confirmText = "保存", cancelText = "取消",
                confirmEnabled = password.isNotEmpty() || !autoPasswordRequired || !auto.enabled && pendingAutoSettings == null,
                onCancel = { autoPasswordDialog = false; password = ""; pendingAutoSettings = null; pendingAutoExpected = null; passwordExpected = null }, onConfirm = {
                val newPassword = password
                val pending = pendingAutoSettings
                val expected = pendingAutoExpected ?: passwordExpected ?: auto
                autoPasswordDialog = false; password = ""; pendingAutoSettings = null; pendingAutoExpected = null; passwordExpected = null
                savingAuto = true
                scope.launch {
                    try {
                        if (pending != null) auto = AutoBackupSettingsRepository.saveFromUi(app, expected, pending, newPassword)
                        else AutoBackupSettingsRepository.savePasswordFromUi(app, expected, newPassword)
                        autoEncrypted = AutoBackupPassword.configured(app)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { vm.status = failure.message ?: "密码保存失败，请重试" }
                    finally { savingAuto = false }
                }
            })
        }
        if (exportDialog) WindowDialog(show = true, title = "导出数据", onDismissRequest = { exportDialog = false; password = "" }) {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
                Text("${options.format.title} · 使用首页设置的备份内容", style = MiuixTheme.textStyles.body2)
                SmallTitle("本次导出加密")
                PasswordInput(password, { password = it }, required = exportPasswordRequired)
                exportPolicyError?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error) }
            }
            MiuixDialogActions(confirmText = "选择位置", cancelText = "取消", confirmEnabled = options.contents.isNotEmpty() && !checkingExportPolicy &&
                exportPolicyError == null && (!exportPasswordRequired || password.isNotEmpty()), onCancel = { exportDialog = false; password = "" }, onConfirm = {
                selectedOptions = options.validated()
                destinationPassword = password.takeIf { it.isNotEmpty() }
                val suffix = if (destinationPassword != null && options.format == EtaBackupFormat.JSON) ".eta-backup" else options.format.suffix
                password = ""; exportDialog = false
                exportLauncher.launch("NEXA-backup-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}$suffix")
            })
        }
        if (importDialog) WindowDialog(show = true, title = if (preview == null) "校验备份" else "确认恢复", onDismissRequest = { importDialog = false; password = ""; preview = null }) {
            val inspected = preview
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (inspected == null) {
                    Text("选择的文件会先校验，校验完成后确认恢复。", style = MiuixTheme.textStyles.footnote1)
                    PasswordInput(password, { password = it })
                } else {
                    SmallTitle("备份来源")
                    Text(
                        when (inspected.sourceApplicationId) {
                            "io.github.zhh_lgtm.nexus" -> "NEXA ${inspected.sourceVersionName.orEmpty()}"
                            "fuck.andes", "io.github.mangi.eta" -> "Eta ${inspected.sourceVersionName.orEmpty()}"
                            else -> "旧版备份"
                        },
                        style = MiuixTheme.textStyles.body2,
                    )
                    SmallTitle("恢复内容")
                    Text(inspected.includedContents.joinToString("、") { it.title }, style = MiuixTheme.textStyles.body2)
                    Text(
                        "${inspected.conversationCount} 个会话 · ${inspected.messageCount} 条消息\n${inspected.providerCount} 个服务商 · ${inspected.characterCount} 个角色",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    SmallTitle("恢复影响")
                    Text(
                        if (inspected.fullArchive) "完整恢复会替换备份范围内的数据和配置。恢复前会保留安全快照，完成后需要重启。" else "只替换备份中包含的数据类别；其他类别保留。JSON 不包含会议室、文件及全部应用设置。",
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }
            MiuixDialogActions(confirmText = if (inspected == null) "开始校验" else "恢复数据", cancelText = "取消", destructive = inspected != null,
                onCancel = { importDialog = false; password = ""; preview = null }, onConfirm = {
                    val uri = importUri ?: return@MiuixDialogActions
                    importDialog = false
                    if (inspected == null) vm.start(BackupOperation.INSPECT) { progress ->
                        preview = EtaBackupDocumentImport.read(app, uri, progress, onSnapshot = { importSha256 = it }) {
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
                        val secret = password.takeIf { it.isNotEmpty() }; val inspectedHash = importSha256
                        password = ""; preview = null
                        vm.start(BackupOperation.IMPORT) { update ->
                            checkNotNull(inspectedHash) { "预览信息已失效，请重新选择备份" }
                            val summary = EtaBackupDocumentImport.read(app, uri, update, expectedSha256 = inspectedHash) { input -> onImport(input, secret) { update(EtaBackupProgress(it)) } }
                            vm.pendingRestart = summary.restartRequired
                            if (summary.restartRequired) "备份已校验并暂存，请重启完成恢复" else "恢复完成 · ${summary.conversationCount} 个会话 · ${summary.providerCount} 个服务商"
                        }
                    }
                })
        }
    }) {
        if (selectedTab == 0) item(key = "actions") {
            SmallTitle("手动备份与恢复", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).padding(bottom = EtaUiMetrics.groupGap),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BackupActionTile(
                    title = "手动备份",
                    caption = if (isExportOperation(vm.operation)) "正在导出" else "使用下方内容设置",
                    icon = Icons.Rounded.Upload,
                    tone = EtaIconTone.DATA,
                    enabled = !busy && !pending,
                    busy = isExportOperation(vm.operation),
                    onClick = { options = auto.options; password = ""; exportDialog = true },
                    modifier = Modifier.weight(1f),
                )
                BackupActionTile(
                    title = "恢复数据",
                    caption = if (isImportOperation(vm.operation)) "正在校验或恢复" else "选择已有备份",
                    icon = Icons.Rounded.Download,
                    tone = EtaIconTone.SYSTEM,
                    enabled = !busy && !pending,
                    busy = isImportOperation(vm.operation),
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (selectedTab == 1) item(key = "backup_overview") {
            BackupOverviewCard(auto = auto, autoStatus = when {
                autoActivity.running -> "正在自动备份"
                autoActivity.immediatePending && backupStatusKind(autoStatus) == BackupStatusKind.ERROR -> autoStatus
                autoActivity.immediatePending -> "备份已安排，等待系统执行"
                else -> autoStatus
            }, autoEncrypted = autoEncrypted,
                lastSuccess = lastAutoSuccess, directoryGranted = backupDirectoryGranted)
        }
        if (selectedTab == 1) item(key = "automatic_schedule") {
            BackupGroup("备份计划", Icons.Rounded.Schedule, EtaIconTone.AUTOMATION, if (auto.enabled) "已启用" else "未启用") {
                SwitchPreference(title = "定期备份", checked = auto.enabled, enabled = !busy && !pending &&
                    (auto.enabled || backupDirectoryGranted), onCheckedChange = { saveAuto(auto.copy(enabled = it)) })
                SwitchPreference(title = "仅充电时备份", checked = auto.chargingOnly, enabled = !busy && !pending, onCheckedChange = { saveAuto(auto.copy(chargingOnly = it)) })
                SwitchPreference(title = "仅非计费网络", checked = auto.unmeteredOnly, enabled = !busy && !pending, onCheckedChange = { saveAuto(auto.copy(unmeteredOnly = it)) })
                val dayOptions = (1..30).map { "每 $it 天" }
                DialogChoicePreference(title = "备份间隔", value = dayOptions[auto.days - 1], options = dayOptions, selectedIndex = auto.days - 1, enabled = !busy && !pending, onSelectedIndexChange = { saveAuto(auto.copy(days = it + 1)) })
                val hourOptions = (0..23).map { "%02d:00".format(it) }
                DialogChoicePreference(title = "执行时间", value = hourOptions[auto.hour], options = hourOptions, selectedIndex = auto.hour, enabled = !busy && !pending, onSelectedIndexChange = { saveAuto(auto.copy(hour = it)) })
            }
        }
        if (selectedTab == 0) item(key = "shared_contents") {
            BackupGroup("通用备份内容", Icons.Rounded.Inventory2, EtaIconTone.DATA, auto.options.format.title) {
                BackupOptionsPanel(auto.options, { saveAuto(auto.copy(options = it)) }, enabled = !busy && !pending)
            }
        }
        if (selectedTab == 0) item(key = "directory_settings") {
            BackupGroup("目录备份设置", Icons.Rounded.Shield, EtaIconTone.SYSTEM, if (autoEncrypted) "已加密" else "未加密") {
                ArrowPreference(title = "备份目录", summary = if (auto.tree.isNotBlank() && !backupDirectoryGranted) "目录授权已失效，请重新选择" else backupDirectoryLabel(auto.tree),
                    enabled = !busy && !pending, onClick = { directoryLauncher.launch(auto.tree.takeIf { it.isNotBlank() }?.let(Uri::parse)) })
                val keepOptions = (1..30).map { "最近 $it 份" }
                DialogChoicePreference(title = "保留份数", value = keepOptions[auto.keep - 1], options = keepOptions, selectedIndex = auto.keep - 1, enabled = !busy && !pending, onSelectedIndexChange = { saveAuto(auto.copy(keep = it + 1)) })
                ArrowPreference(title = "目录备份加密", summary = if (autoEncrypted) "已设置本机保存的密码" else "未设置密码", enabled = !busy && !pending, onClick = { password = ""; passwordExpected = auto; autoPasswordDialog = true })
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                    NexaCompactAction(
                        text = if (autoActivity.immediatePending) "已安排备份" else "立即备份一次",
                        icon = Icons.Rounded.Backup,
                        enabled = !busy && !pending && backupDirectoryGranted && !autoActivity.immediatePending,
                        onClick = { AutoBackupScheduler.enqueue(app) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupProgressBanner(
    context: Context,
    progress: EtaBackupProgress,
    onCancel: () -> Unit,
) {
    val size = if (progress.bytes > 0) {
        android.text.format.Formatter.formatShortFileSize(context, progress.bytes) +
            (progress.totalBytes?.let { " / ${android.text.format.Formatter.formatShortFileSize(context, it)}" } ?: "")
    } else null
    val summary = listOfNotNull(
        progress.files.takeIf { it > 0 }?.let { "已处理 $it 项" },
        progress.records.takeIf { it > 0 }?.let { "已写入 ${NumberFormat.getIntegerInstance().format(it)} 条记录" },
        size,
    ).joinToString(" · ").ifBlank { backupPhaseDetail(progress.phase) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfiniteProgressIndicator(size = 22.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(backupPhaseLabel(progress.phase), style = MiuixTheme.textStyles.body1, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(summary, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            NexaCompactAction(text = "取消", onClick = onCancel)
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
        modifier.etaGlassSurface(accent = tone.accent, cornerRadius = 18.dp),
        colors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainer, MiuixTheme.colorScheme.onSurface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NexaCompactAction(text = title, icon = icon, enabled = enabled, onClick = onClick, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                if (busy) InfiniteProgressIndicator(size = 14.dp)
                Text(caption, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
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
@OptIn(ExperimentalLayoutApi::class)
private fun BackupOverviewCard(auto: AutoBackupSettings, autoStatus: String, autoEncrypted: Boolean,
    lastSuccess: Long, directoryGranted: Boolean) {
    val configured = auto.tree.isNotBlank()
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).etaGlassSurface(accent = MiuixTheme.colorScheme.primary)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EtaIconTile(Icons.Rounded.Backup, tone = EtaIconTone.DATA, size = 42.dp, glyphSize = 22.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("自动备份", style = MiuixTheme.textStyles.body1, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(
                        if (configured) "每 ${auto.days} 天 · ${auto.hour.toString().padStart(2, '0')}:00" else "选择目录后可启用自动备份",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                EtaStatusPill(
                    text = if (configured && !directoryGranted) "授权失效" else if (auto.enabled && configured) "已启用" else if (configured) "已配置" else "未配置",
                    tone = if (configured && !directoryGranted) EtaStatusTone.Danger else if (auto.enabled && configured) EtaStatusTone.Positive else EtaStatusTone.Neutral,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TagChip(backupDirectoryLabel(auto.tree), if (configured) TagChipTone.Emphasized else TagChipTone.Normal)
                if (autoEncrypted) TagChip("已加密")
                if (auto.chargingOnly) TagChip("仅充电")
                if (auto.unmeteredOnly) TagChip("非计费网络")
            }
            if (autoStatus.isNotBlank()) {
                Text(autoStatus, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (lastSuccess > 0) Text("上次成功：${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(lastSuccess))}",
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (auto.enabled && directoryGranted) {
                val now = java.time.ZonedDateTime.now()
                val next = if (AutoBackupSchedule.isOverdue(auto, lastSuccess, now)) "等待系统安排" else
                    AutoBackupSchedule.nextAttempt(auto, lastSuccess, now).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                Text("预计下次：$next（系统可能因设备条件延后）", style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

private fun backupDirectoryGrantValid(context: Context, tree: String): Boolean {
    if (tree.isBlank()) return false
    val uri = Uri.parse(tree)
    return context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
}

@Composable
private fun BackupStatusBanner(status: String) {
    val kind = backupStatusKind(status)
    val tone = when (kind) {
        BackupStatusKind.ERROR -> EtaStatusTone.Danger
        BackupStatusKind.SUCCESS -> EtaStatusTone.Positive
        BackupStatusKind.PENDING, BackupStatusKind.CANCELED -> EtaStatusTone.Neutral
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EtaStatusPill(text = when (kind) {
            BackupStatusKind.ERROR -> "未完成"
            BackupStatusKind.SUCCESS -> "已完成"
            BackupStatusKind.CANCELED -> "已取消"
            BackupStatusKind.PENDING -> "处理中"
        }, tone = tone)
        Text(status, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
    }
}

internal enum class BackupStatusKind { SUCCESS, ERROR, CANCELED, PENDING }

internal fun backupStatusKind(status: String): BackupStatusKind = when {
    status.contains("已取消") -> BackupStatusKind.CANCELED
    listOf("失败", "错误", "无法", "无效", "不通过", "未通过", "未完成", "回退", "中断", "不可用").any(status::contains) -> BackupStatusKind.ERROR
    status.contains("暂存") || status.contains("待重启") -> BackupStatusKind.PENDING
    status.contains("成功") || status.contains("完成") || status.contains("已恢复") -> BackupStatusKind.SUCCESS
    else -> BackupStatusKind.PENDING
}

internal fun isBackupStatusPositive(status: String): Boolean = backupStatusKind(status) == BackupStatusKind.SUCCESS

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

@Composable private fun PasswordInput(value: String, change: (String) -> Unit, required: Boolean = false) {
    TextField(value = value, onValueChange = change, label = if (required) "备份密码" else "密码（可选）", singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
}

@Composable internal fun BackupOptionsPanel(options: EtaBackupOptions, change: (EtaBackupOptions) -> Unit, enabled: Boolean = true) {
    val formatOptions = EtaBackupFormat.entries.map { it.title }
    DialogChoicePreference(title = "备份格式", value = formatOptions[options.format.ordinal], options = formatOptions, selectedIndex = options.format.ordinal, enabled = enabled,
        onSelectedIndexChange = { change(options.copy(format = EtaBackupFormat.entries[it])) })
    SmallTitle(if (options.format == EtaBackupFormat.FULL) "完整备份范围" else "选择数据类别")
    if (options.format == EtaBackupFormat.FULL) {
        SwitchPreference(
            title = "包含全部应用文件",
            summary = if (options.includeAppFiles) {
                "包含项目文件、技能、工作区和 Linux 环境；文件很多时导出会明显变慢"
            } else {
                "备份会话、配置、记忆、数据库及技能等配置目录；恢复时会替换这些目录，其他应用文件保留"
            },
            checked = options.includeAppFiles,
            enabled = enabled,
            onCheckedChange = { change(options.copy(includeAppFiles = it)) },
        )
        SwitchPreference(
            title = "包含独立记事本",
            summary = "包含记事和附件；关闭时恢复不会覆盖现有记事本",
            checked = options.includeNotepad,
            enabled = enabled,
            onCheckedChange = { change(options.copy(includeNotepad = it)) },
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
    "portable_secrets" -> "整理加密凭据"
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
