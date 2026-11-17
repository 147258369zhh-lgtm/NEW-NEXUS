package io.github.mangi.eta.ui.screens.capability

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.packages.*
import io.github.mangi.eta.agent.control.NexusSettingsControlPlane
import io.github.mangi.eta.ui.components.*
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun PackageCenterScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val store = remember(context) { CapabilityPackageStore(context) }
    val service = remember(context) { PackageApplicationService(context) }
    var rows by remember { mutableStateOf<List<CapabilityPackage>>(emptyList()) }
    var catalog by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val installedListState = rememberLazyListState()
    val bundledListState = rememberLazyListState()
    var filter by remember { mutableStateOf("all") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<PackageImportPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var uiModule by remember { mutableStateOf<PackageUiModule?>(null) }
    uiModule?.let { module -> PackageUiScreen(module, onBack = { uiModule = null }); return }

    suspend fun refresh() {
        val values = withContext(Dispatchers.IO) { store.ensureBuiltinTime(); store.list() to store.builtinCatalog() }
        rows = values.first; catalog = values.second; loaded = true
    }
    fun perform(action: suspend () -> Unit) {
        scope.launch {
            busy = true; error = null
            try { action(); refresh() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message?.take(180) ?: "操作未完成" }
            finally { busy = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform { preview = withContext(Dispatchers.IO) { store.stage(uri) } }
    }
    LaunchedEffect(store) { try { refresh() } catch (failure: Exception) { loaded = false; error = failure.message?.take(180) } }
    BackHandler { if (!busy && preview == null && selectedId == null) onBack() }

    MiuixScaffoldPage(title = "插件与沙盒包", onBack = { if (!busy) onBack() },
        listState = if (tab == 0) installedListState else bundledListState,
        aboveContent = {
            Column {
                NexusSettingsTabs(
                    tabs = listOf(stringResource(R.string.settings_tab_installed), stringResource(R.string.settings_tab_bundled)),
                    selectedTab = tab,
                    onTabSelected = { tab = it },
                )
                if (busy) Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) { InfiniteProgressIndicator(size = 22.dp) }
                error?.let { Text(it, color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            }
        }, actions = {
        IconButton(enabled = !busy, onClick = { importer.launch(arrayOf("*/*")) }) {
            Icon(Icons.Rounded.FileUpload, contentDescription = "导入扩展包")
        }
    }) {
        if (tab == 0) {
            item {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("all" to "全部", "plugin" to "插件", "sandbox" to "沙盒包").forEach { (id, title) ->
                        EtaChoiceChip(title, selected = filter == id, onClick = { filter = id })
                    }
                }
            }
            rows.filter { filter == "all" || it.kind == filter }.forEach { record ->
                item(key = record.id) {
                    Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).etaGlassSurface()) {
                        DetailListRow(title = record.title, subtitle = "${record.version} · ${record.tools.size} 项工具",
                            icon = if (record.kind == "sandbox") Icons.Rounded.Inventory2 else Icons.Rounded.Extension,
                            tone = if (record.enabled) EtaIconTone.TOOL else EtaIconTone.DATA,
                            value = if (record.enabled) "已启用" else "已停用", onClick = { selectedId = record.id })
                    }
                }
            }
            if (loaded && !busy && error == null && rows.none { filter == "all" || it.kind == filter }) item { Text("暂无扩展包", modifier = Modifier.padding(24.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        } else {
            catalog.forEach { (file, title) ->
                val installed = rows.any { it.source.endsWith(":$file") || (file == "time.js" && it.id == "time") }
                item(key = file) {
                    Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).etaGlassSurface()) {
                        DetailListRow(title = title, icon = Icons.Rounded.Extension, tone = EtaIconTone.TOOL,
                            value = if (installed) "已安装" else "", onClick = {
                                if (!busy) perform { preview = withContext(Dispatchers.IO) { store.stageBuiltin(file) } }
                            })
                    }
                }
            }
        }
    }

    preview?.let { item ->
        WindowDialog(show = true, title = if (item.replacing) "更新扩展包" else "安装扩展包", onDismissRequest = {
            if (!busy) { preview = null; scope.launch(Dispatchers.IO) { store.cancel(item) } }
        }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PackageIdentity(item.content)
                    if (item.content.description.isNotBlank()) Text(item.content.description, style = MiuixTheme.textStyles.body2)
                    Text("${item.content.tools.size} 项工具", style = MiuixTheme.textStyles.body2)
                    item.content.tools.take(12).forEach { tool -> Text(tool.name, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    PackageCompatibility(item.content)
                }
                error?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error) }
                if (busy) InfiniteProgressIndicator(size = 22.dp)
                MiuixDialogActions(confirmText = "安装", confirmEnabled = !busy, onCancel = {
                    if (!busy) { preview = null; scope.launch(Dispatchers.IO) { store.cancel(item) } }
                }, onConfirm = {
                    perform { withContext(Dispatchers.IO) { service.installReviewed(item) }; preview = null }
                })
            }
        }
    }
    rows.firstOrNull { it.id == selectedId }?.let { record ->
        PackageDetails(record, busy, service, error, onDismiss = { selectedId = null }, onInspect = {
            perform { withContext(Dispatchers.IO) { service.inspectReviewed(record) } }
        }, onSave = { enabled, tools, memory, timeout, extensions ->
            perform {
                withContext(Dispatchers.IO) {
                    NexusSettingsControlPlane.get(context).applyJson("settings.packages.configure", JSONObject()
                        .put("package_id", record.id).put("digest", record.digest).put("enabled", enabled)
                        .put("allowed_tools", JSONArray(tools.sorted())).put("memory_mb", memory).put("timeout_seconds", timeout)
                        .put("enabled_extensions", JSONArray(extensions.sorted())),
                        SettingsActorKind.LOCAL_USER, "package_center")
                }
                selectedId = null
            }
        }, onDelete = { perform { withContext(Dispatchers.IO) { service.deleteReviewed(record) }; selectedId = null } },
            onRollback = { perform { withContext(Dispatchers.IO) { service.rollbackReviewed(record) }; selectedId = null } },
            onOpenUi = { module -> selectedId = null; uiModule = module })
    }
}

@Composable
private fun PackageIdentity(record: CapabilityPackage) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EtaIconTile(if (record.kind == "sandbox") Icons.Rounded.Inventory2 else Icons.Rounded.Extension, tone = EtaIconTone.TOOL)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(record.title, style = MiuixTheme.textStyles.body1)
            Text(record.version, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun PackageCompatibility(record: CapabilityPackage) {
    if (record.issues.isNotEmpty()) Text("部分宿主接口待适配", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
}

private val toolGroups = listOf(
    "设备读取" to setOf("device_status", "get_current_context", "get_device_environment"),
    "文件读取" to setOf("read_file", "list_directory", "stat_file", "glob_files", "grep_files", "read_image"),
    "文件写入" to setOf("write_file", "edit_file", "make_directory", "copy_file", "move_file"),
    "删除文件" to setOf("delete_file"),
    "网络检索" to setOf("fetch_url", "web_search"),
    "外部请求" to setOf("connector_call"),
    "网页操作" to setOf("browser_use"),
    "屏幕操作" to setOf("observe_screen", "tap", "swipe", "scroll", "type_text", "launch_app"),
    "终端执行" to setOf("terminal", "terminal_job", "terminal_daemon"),
    "自动任务" to setOf("schedule_list", "schedule_create", "schedule_pause", "automation_manage"),
    "模型调用" to setOf("subagent"),
)

@Composable
private fun PackageDetails(record: CapabilityPackage, busy: Boolean, service: PackageApplicationService, externalError: String?, onDismiss: () -> Unit, onInspect: () -> Unit,
    onSave: (Boolean, Set<String>, Int, Int, Set<String>) -> Unit, onDelete: () -> Unit, onRollback: () -> Unit,
    onOpenUi: (PackageUiModule) -> Unit) {
    val context = LocalContext.current
    var modules by remember(record.id, record.registrations, record.enabled) { mutableStateOf<List<PackageUiModule>>(emptyList()) }
    var widgets by remember(record.id, record.registrations, record.enabled) { mutableStateOf<List<PackageDesktopWidget>>(emptyList()) }
    var aiProviders by remember(record.id, record.registrations, record.enabled, record.enabledExtensions) { mutableStateOf<List<PackageAiDeclaration>>(emptyList()) }
    var providerNotice by remember { mutableStateOf<String?>(null) }
    var connectedProviders by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(record.id, record.registrations, record.enabled) {
        modules = withContext(Dispatchers.IO) { PackageUiService(context).modules(record.id).filter { it.messageMenuId == null } }
        widgets = withContext(Dispatchers.IO) { PackageDesktopWidgetService(context).available(record.id) }
    }
    LaunchedEffect(record.id, record.registrations, record.enabled, record.enabledExtensions) {
        aiProviders = withContext(Dispatchers.IO) { PackageAiRegistry(context).declarations().filter { it.record.id == record.id } }
        connectedProviders = withContext(Dispatchers.IO) { val registry = PackageAiRegistry(context)
            aiProviders.filter(registry::isConnected).mapTo(mutableSetOf()) { it.providerId } }
    }
    var tab by rememberSaveable(record.id) { mutableIntStateOf(0) }
    val overviewScrollState = rememberScrollState()
    val permissionsListState = rememberLazyListState()
    val runtimeScrollState = rememberScrollState()
    val configurationScrollState = rememberScrollState()
    val extensionsScrollState = rememberScrollState()
    val detailScrollState = when (tab) {
        2 -> runtimeScrollState
        3 -> configurationScrollState
        4 -> extensionsScrollState
        else -> overviewScrollState
    }
    var action by remember(record.id) { mutableStateOf<String?>(null) }
    var enabled by remember(record.id, record.digest) { mutableStateOf(record.enabled) }
    var tools by remember(record.id, record.digest) { mutableStateOf(record.allowedTools) }
    var extensions by remember(record.id, record.digest, record.registrations) { mutableStateOf(record.enabledExtensions) }
    var memory by remember(record.id, record.digest) { mutableStateOf(record.memoryMb.toString()) }
    var timeout by remember(record.id, record.digest) { mutableStateOf(record.timeoutSeconds.toString()) }
    val scope = rememberCoroutineScope()
    var environmentNames by remember(record.id) { mutableStateOf<List<String>>(emptyList()) }
    var variableName by remember(record.id) { mutableStateOf("") }
    var variableValue by remember(record.id) { mutableStateOf("") }
    var environmentBusy by remember(record.id) { mutableStateOf(false) }
    var environmentError by remember(record.id) { mutableStateOf<String?>(null) }
    var runTool by remember(record.id) { mutableStateOf<PackageTool?>(null) }
    var notifications by remember(record.id) { mutableStateOf<List<PackageEventReceipt>>(emptyList()) }
    var notification by remember(record.id) { mutableStateOf<PackageEventReceipt?>(null) }
    var notificationError by remember(record.id) { mutableStateOf<String?>(null) }
    var inputs by remember(record.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var resultText by remember(record.id) { mutableStateOf<String?>(null) }
    var runController by remember(record.id) { mutableStateOf<io.github.mangi.eta.agent.runtime.AgentRunController?>(null) }
    val approval by io.github.mangi.eta.agent.control.EtaAppControlConfirmation.pending.collectAsState()
    DisposableEffect(record.id) { onDispose { runController?.cancel() } }
    LaunchedEffect(record.id) {
        runCatching { withContext(Dispatchers.IO) { service.environmentNames(record) } }.onSuccess { environmentNames = it }
            .onFailure { environmentError = "配置无法读取，请重新填写" }
    }
    fun saveVariable(name: String, value: String?) {
        scope.launch {
            environmentBusy = true; environmentError = null
            try {
                environmentNames = withContext(Dispatchers.IO) { service.saveEnvironmentReviewed(record, name, value); service.environmentNames(record) }
                variableValue = ""; variableName = ""
            } catch (failure: Exception) { environmentError = failure.message?.take(120) ?: "配置未保存" }
            finally { environmentBusy = false }
        }
    }
    val minimumMemory = if (record.wasmModuleCount > 0) 16 else 8
    val valid = (memory.toIntOrNull() ?: 0) in minimumMemory..256 && (timeout.toIntOrNull() ?: 0) in 1..120
    suspend fun readNotifications() {
        try { notifications = withContext(Dispatchers.IO) { service.notificationHistory(record) }; notificationError = null }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { notificationError = "事件记录暂不可用" }
    }
    LaunchedEffect(record.id, tab) { if (tab == 0 || tab == 2) readNotifications() }
    fun dismiss() {
        if (runController != null) { runController?.cancel(); return }
        if (!busy && !environmentBusy) { if (action != null) action = null else onDismiss() }
    }
    BackHandler { dismiss() }
    WindowDialog(show = approval == null, title = when (action) { "delete" -> "卸载扩展包"; "rollback" -> "回退版本"; "notification" -> "通知记录"; else -> record.title }, onDismissRequest = ::dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            externalError?.let { Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error) }
            if (busy || environmentBusy) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfiniteProgressIndicator(size = 18.dp)
                    Text("正在保存扩展包设置…", style = MiuixTheme.textStyles.footnote1)
                }
            }
            listOfNotNull(environmentError, notificationError).distinct().forEach {
                Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
            }
            providerNotice?.let { Text(it, style = MiuixTheme.textStyles.footnote1) }
            if (action == "run") {
                val tool = requireNotNull(runTool)
                val properties = remember(tool.inputSchema) { JSONObject(tool.inputSchema).optJSONObject("properties") ?: JSONObject() }
                val keys = remember(properties) { properties.keys().asSequence().toList() }
                Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tool.description.ifBlank { tool.name }, style = MiuixTheme.textStyles.body2)
                    if (resultText != null) androidx.compose.foundation.text.selection.SelectionContainer { Text(resultText!!, style = MiuixTheme.textStyles.body2) }
                    else keys.forEach { key ->
                        val parameter = properties.getJSONObject(key)
                        val value = inputs[key] ?: parameter.opt("default")?.toString().orEmpty()
                        val title = parameter.optString("title").ifBlank { parameterTitle(key) }
                        val options = parameter.optJSONArray("enum")
                        if (options != null) {
                            Text(title, style = MiuixTheme.textStyles.body2)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (i in 0 until options.length()) {
                                    val option = options.getString(i)
                                    EtaChoiceChip(option, selected = value == option, enabled = runController == null, onClick = { inputs = inputs + (key to option) })
                                }
                            }
                        } else if (parameter.optString("type") == "boolean") SwitchPreference(title = title, checked = value == "true",
                            enabled = runController == null, onCheckedChange = { inputs = inputs + (key to it.toString()) })
                        else TextField(value = value, onValueChange = { inputs = inputs + (key to it) }, label = title,
                            keyboardOptions = KeyboardOptions(keyboardType = if (parameter.optString("type") in setOf("integer", "number")) KeyboardType.Number else KeyboardType.Text),
                            singleLine = parameter.optString("type") !in setOf("object", "array"), modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (runController != null) {
                        InfiniteProgressIndicator()
                        IconButton(onClick = { runController?.cancel() }) { Icon(Icons.Rounded.Stop, contentDescription = "停止扩展包操作") }
                    } else {
                        TextButton(text = "返回", onClick = { action = null; resultText = null })
                        if (resultText == null) IconButton(enabled = !busy, onClick = {
                            try {
                                val arguments = packageArguments(tool, inputs)
                                val controller = io.github.mangi.eta.agent.runtime.AgentRunController()
                                runController = controller
                                scope.launch {
                                    try {
                                        val result = kotlinx.coroutines.runInterruptible(Dispatchers.IO) { service.runActionReviewed(record, tool, arguments, controller) }
                                        resultText = if (result.ok) {
                                            val payload = JSONObject(result.content)
                                            (payload.opt("value") ?: payload).let { if (it is JSONObject) it.toString(2) else it.toString() }.take(16000)
                                        } else result.error ?: "操作未完成"
                                    } catch (failure: Exception) { resultText = failure.message?.take(180) ?: "操作未完成" }
                                    finally { runController = null }
                                }
                            } catch (failure: Exception) { resultText = failure.message ?: "请检查输入" }
                        }) { Icon(Icons.Rounded.PlayArrow, contentDescription = "运行扩展工具") }
                    }
                }
            } else if (action == "notification") {
                notification?.let { event ->
                    DetailListRow(packageEventTitle(event.name), icon = Icons.Rounded.History, value = event.status.label, onClick = null)
                    Text(event.runId, style = MiuixTheme.textStyles.body2)
                    if (event.status == PackageEventStatus.UNKNOWN) {
                        Text("该回调的结果尚未确认。归档只移除待核对记录，不会重新执行或停止原任务。", style = MiuixTheme.textStyles.body2)
                        MiuixDialogActions(confirmText = "归档记录", onCancel = { action = null }, onConfirm = {
                            scope.launch {
                                try { withContext(Dispatchers.IO) { service.archiveNotificationReviewed(record, event.runId) }; readNotifications(); action = null }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (failure: Exception) { resultText = failure.message?.take(120) }
                            }
                        })
                    } else TextButton("返回", onClick = { action = null })
                    resultText?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2) }
                }
            } else if (action != null) {
                Text(if (action == "delete") "卸载 ${record.title}？" else "回退 ${record.title} 到上一版本？", style = MiuixTheme.textStyles.body1)
                MiuixDialogActions(confirmText = if (action == "delete") "卸载" else "回退", destructive = action == "delete", confirmEnabled = !busy,
                    onCancel = { action = null }, onConfirm = { if (action == "delete") onDelete() else onRollback() })
            } else {
                PackageIdentity(record)
                NexusSettingsTabs(
                    tabs = listOf(
                        stringResource(R.string.settings_tab_overview),
                        stringResource(R.string.settings_tab_tools_permissions),
                        stringResource(R.string.settings_tab_runtime_events),
                        stringResource(R.string.settings_tab_configuration),
                        stringResource(R.string.settings_tab_extensions),
                    ),
                    selectedTab = tab,
                    onTabSelected = { tab = it },
                )
                val eventIssues = notifications.count { it.status in setOf(PackageEventStatus.FAILED, PackageEventStatus.UNKNOWN) }
                val activeEvents = notifications.count { it.status in setOf(PackageEventStatus.PENDING, PackageEventStatus.RUNNING) }
                if (eventIssues > 0 || activeEvents > 0) {
                    TextButton("事件记录 · $activeEvents 项进行中 · $eventIssues 项需处理", onClick = { tab = 2 })
                }
                if (tab == 1) {
                    val availableTools = remember(record.tools) {
                        record.tools.filter { it.callbackKey?.substringBefore('/') !in PackageExtensionKinds.automatic + PackageExtensionKinds.ui }
                    }
                    LazyColumn(Modifier.weight(1f, false), state = permissionsListState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "tools_heading") { NexusSettingsSectionTitle("扩展工具") }
                        items(availableTools.size, key = { index -> "tool:$index" }) { index ->
                            val tool = availableTools[index]
                            DetailListRow(title = tool.description.ifBlank { tool.name }, icon = Icons.Rounded.PlayArrow, tone = EtaIconTone.TOOL,
                                enabled = record.enabled && !busy, onClick = { runTool = tool; inputs = emptyMap(); resultText = null; action = "run" })
                        }
                        item(key = "permissions_heading") { NexusSettingsSectionTitle("宿主工具权限") }
                        toolGroups.forEach { (title, names) ->
                            item(key = "permission:$title") {
                                SwitchPreference(title = title, checked = tools.containsAll(names), enabled = !busy,
                                    onCheckedChange = { checked -> tools = if (checked) tools + names else tools - names })
                            }
                        }
                    }
                } else Column(Modifier.weight(1f, false).verticalScroll(detailScrollState), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (tab) {
                        0 -> {
                            SwitchPreference(title = "启用", checked = enabled, enabled = !busy, onCheckedChange = { enabled = it })
                            if (record.description.isNotBlank()) Text(record.description, style = MiuixTheme.textStyles.body2)
                            PackageCompatibility(record)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                if (record.mainEntry != null) IconButton(enabled = !busy, onClick = onInspect) { Icon(Icons.Rounded.Sync, contentDescription = "同步扩展接口") }
                                if (record.previous != null) IconButton(enabled = !busy, onClick = { action = "rollback" }) { Icon(Icons.Rounded.History, contentDescription = "回退版本") }
                                IconButton(enabled = !busy, onClick = { action = "delete" }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "卸载扩展包") }
                            }
                        }
                        2 -> {
                            TextField(value = memory, onValueChange = { memory = it.filter(Char::isDigit).take(3) }, label = "内存上限（MiB）",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                            TextField(value = timeout, onValueChange = { timeout = it.filter(Char::isDigit).take(3) }, label = "执行时限（秒）",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("事件记录", modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
                                IconButton(onClick = { scope.launch { PackageApplicationEvents.refresh(); readNotifications() } }) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新事件记录")
                                }
                            }
                            notifications.forEach { event -> DetailListRow(packageEventTitle(event.name), value = event.status.label,
                                icon = Icons.Rounded.History, tone = EtaIconTone.DATA, onClick = { notification = event; resultText = null; action = "notification" }) }
                        }
                        3 -> {
                            record.environmentNames.filterNot(environmentNames::contains).forEach { name ->
                                DetailListRow(title = name, icon = Icons.Rounded.Key, tone = EtaIconTone.DATA,
                                    value = "未配置", onClick = { variableName = name; variableValue = "" })
                            }
                            environmentNames.forEach { name ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(name, style = MiuixTheme.textStyles.body2)
                                        Text("已配置", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.primary)
                                    }
                                    IconButton(enabled = !busy && !environmentBusy, onClick = { saveVariable(name, null) }) {
                                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除 $name")
                                    }
                                }
                            }
                            TextField(value = variableName, onValueChange = { variableName = it.take(128) }, label = "名称", singleLine = true, modifier = Modifier.fillMaxWidth())
                            TextField(value = variableValue, onValueChange = { variableValue = it.take(4096) }, label = "值",
                                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                IconButton(enabled = !busy && !environmentBusy && variableName.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) && variableValue.isNotEmpty(),
                                    onClick = { saveVariable(variableName, variableValue) }) { Icon(Icons.Rounded.Check, contentDescription = "保存配置") }
                            }
                        }
                        else -> {
                            if (modules.isNotEmpty() || widgets.isNotEmpty()) NexusSettingsSectionTitle("界面与桌面组件")
                            modules.forEach { module -> DetailListRow(module.title, icon = Icons.Rounded.Dashboard, tone = EtaIconTone.TOOL,
                                enabled = record.enabled && !busy, onClick = { onOpenUi(module) }) }
                            widgets.forEach { widget -> DetailListRow(widget.title, icon = Icons.Rounded.Widgets, tone = EtaIconTone.TOOL,
                                value = "添加到桌面", enabled = !busy, onClick = {
                                    scope.launch {
                                        try {
                                            if (!withContext(Dispatchers.IO) { PackageDesktopWidgetService(context).pin(widget) })
                                                providerNotice = "请在系统桌面的组件列表中添加 NEXA"
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                        catch (_: Exception) { providerNotice = "组件暂不可用，请重新打开" }
                                    }
                                }) }
                            if (aiProviders.isNotEmpty()) NexusSettingsSectionTitle("模型服务")
                            aiProviders.forEach { provider -> DetailListRow(provider.title, icon = Icons.Rounded.Hub, tone = EtaIconTone.MODEL,
                                value = if (provider.providerId in connectedProviders) "已接入" else "接入模型", enabled = !busy && !environmentBusy, onClick = {
                                    environmentBusy = true
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { PackageAiRegistry(context).connect(provider) }
                                            connectedProviders = connectedProviders + provider.providerId
                                            providerNotice = null
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                        catch (failure: Exception) { providerNotice = failure.message?.take(160) ?: "插件模型接入失败" }
                                        finally { environmentBusy = false }
                                    }
                                }) }
                            NexusSettingsSectionTitle("扩展接口")
                            val declared = PackageExtensionKinds.declared(record)
                            if (declared.isEmpty()) Text("暂无可用扩展", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            PackageExtensionKinds.labels.filterKeys(declared::contains).forEach { (kind, title) ->
                                SwitchPreference(title = title, checked = kind in extensions, enabled = !busy,
                                    onCheckedChange = { checked -> extensions = if (checked) extensions + kind else extensions - kind })
                            }
                        }
                    }
                }
                MiuixDialogActions(confirmText = "保存", confirmEnabled = !busy && !environmentBusy && valid, onCancel = ::dismiss,
                    onConfirm = { onSave(enabled, tools, memory.toInt(), timeout.toInt(), extensions) })
            }
        }
    }
}

private fun packageEventTitle(name: String) = when (name) {
    "application_on_create" -> "应用启动"
    "application_on_foreground" -> "进入前台"
    "application_on_background" -> "进入后台"
    "activity_on_create" -> "页面创建"
    "activity_on_start" -> "页面打开"
    "activity_on_resume" -> "页面恢复"
    "activity_on_pause" -> "页面暂停"
    "activity_on_stop" -> "页面关闭"
    "activity_on_destroy" -> "页面释放"
    "application_on_trim_memory", "application_on_low_memory" -> "内存状态变化"
    "chat_message_saved" -> "消息保存"
    else -> name.take(96)
}

private fun parameterTitle(key: String) = mapOf("query" to "关键词", "url" to "网址", "path" to "路径", "message" to "内容",
    "content" to "内容", "doi" to "DOI", "author" to "作者", "title" to "标题", "text" to "文字", "max_results" to "结果数量",
    "rows" to "结果数量", "eventPayload" to "输入内容")[key] ?: key

private fun packageArguments(tool: PackageTool, values: Map<String, String>): JSONObject {
    val schema = JSONObject(tool.inputSchema)
    val properties = schema.optJSONObject("properties") ?: JSONObject()
    val required = schema.optJSONArray("required")?.let { names ->
        (0 until names.length()).mapTo(hashSetOf()) { names.getString(it) }
    }.orEmpty()
    val result = JSONObject()
    properties.keys().forEach { key ->
        val field = properties.getJSONObject(key)
        val raw = values[key]
        if (raw.isNullOrEmpty()) {
            val defaultValue = field.opt("default")
            if (defaultValue != null && defaultValue != JSONObject.NULL) result.put(key, defaultValue)
            else if (key in required && field.optString("type") == "boolean") result.put(key, false)
            return@forEach
        }
        result.put(key, when (field.optString("type", "string")) {
            "integer" -> raw.toLong()
            "number" -> raw.toDouble().also { require(it.isFinite()) }
            "boolean" -> raw.toBooleanStrict()
            "array" -> JSONArray(raw)
            "object" -> JSONObject(raw)
            else -> raw
        })
    }
    io.github.mangi.eta.agent.control.EtaControlSchema.validate(schema, result)
    return result
}
