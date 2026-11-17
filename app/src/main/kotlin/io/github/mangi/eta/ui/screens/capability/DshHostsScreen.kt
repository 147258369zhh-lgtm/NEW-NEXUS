package io.github.mangi.eta.ui.screens.capability

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.agent.connector.SavedConnectorConfig
import io.github.mangi.eta.agent.dsh.*
import io.github.mangi.eta.ui.components.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private data class DshSelectedPanel(val plugin: DshPlugin, val item: DshContribution, val view: DshView)
private data class DshPendingTap(val plugin: DshPlugin, val item: DshContribution, val action: String?, val value: JSONObject?, val tappedAt: Long = System.nanoTime(), val userGesture: Boolean = true)

@Composable
internal fun DshHostsScreen(onBack: () -> Unit, insertDraft: suspend (String) -> Boolean = { false }, command: (String, String) -> Boolean = { _, _ -> false },
    openPage: (String) -> Boolean = { false }, draft: suspend () -> String? = { null },
    actionSurfaces: Set<String> = emptySet(), message: String? = null, subjectKey: String? = null, messageId: String? = null,
    initialHost: String? = null, initialPanel: Pair<String, String>? = null) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val repository = remember(context) { DshHostRepository(context) }
    var hosts by remember { mutableStateOf<List<SavedConnectorConfig>>(emptyList()) }
    var selected by remember { mutableStateOf<SavedConnectorConfig?>(null) }
    var session by remember { mutableStateOf<DshUiSession?>(null) }
    var lease by remember { mutableStateOf<DshSharedSessions.Lease?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var catalog by remember { mutableStateOf<DshCatalog?>(null) }
    var panel by remember { mutableStateOf<DshSelectedPanel?>(null) }
    var panelGeneration by remember { mutableLongStateOf(0) }
    var panelEnded by remember { mutableStateOf(false) }
    var remoteSession by remember { mutableStateOf("") }
    var editingBinding by remember { mutableStateOf(false) }
    var bindingInput by remember { mutableStateOf("") }
    var turnInput by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<SavedConnectorConfig?>(null) }
    var consent by remember { mutableStateOf<DshPendingTap?>(null) }
    var chosenContext by remember { mutableStateOf<Set<String>>(emptySet()) }
    val allowedContext = remember { mutableMapOf<String, Set<String>>() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val registry = LocalActivityResultRegistryOwner.current?.activityResultRegistry
    val picker = remember(registry, lifecycle) { registry?.let { DshAndroidFilePicker(it, lifecycle) } }
    val fileReader = remember(context) { DshPickedFileReader(context) }
    val voicePermission = rememberDshVoicePermission(repository)
    val media = remember(context) { DshMediaController.get(context) }
    val mediaSurface = subjectKey ?: "dsh-manager"
    DshMediaSurface(mediaSurface)
    val permissionRevision by DshHostRepository.scopeChanges.collectAsState()
    val allowedVoices by produceState<Set<String>>(emptySet(), selected?.id, selected?.credentialRevision, permissionRevision) {
        value = selected?.let { host -> withContext(Dispatchers.IO) { runCatching { repository.voicesAllowed(host) }.getOrDefault(emptySet()) } }.orEmpty()
    }

    suspend fun refreshHosts() { hosts = withContext(Dispatchers.IO) { repository.hosts() } }
    fun closeHost() { operation?.cancel(); lease?.close(); lease = null; session = null; selected = null; catalog = null; panel = null }
    fun back() { when { pending != null -> operation?.cancel(); panel != null -> { panel = null; panelGeneration++ }; selected != null -> closeHost(); else -> onBack() } }
    fun perform(block: suspend () -> Unit) {
        scope.launch { busy = true; notice = null
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = failure.message?.take(180) ?: "DSH 操作未完成" }
            finally { busy = false }
        }
    }
    suspend fun openPanel(plugin: DshPlugin, item: DshContribution) {
        val current = session ?: return
        val remote = remoteSession
        val view = current.client.view(plugin.pkg, item.id, if (remote.isBlank()) JSONObject() else JSONObject().put("sessionId", remote))
        if (session === current && remoteSession == remote) { panel = DshSelectedPanel(plugin, item, view); panelEnded = false; panelGeneration++ }
    }
    suspend fun applyEffects(plugin: DshPlugin, effects: DshEffects) {
        val dispatchCommand = command
        DshEffectRouter.apply(effects, object : DshEffectTarget {
            override suspend fun capability(effect: JSONObject, origin: DshEffects) {
                val current = requireNotNull(session)
                media.apply(mediaSurface, subjectKey, current, plugin, JSONObject().apply { if (remoteSession.isNotBlank()) put("sessionId", remoteSession) },
                    effect, origin, voicePermission = { voicePermission(current.host, plugin) })
            }
            override fun discardCapability(effect: JSONObject, origin: DshEffects) { session?.let { media.discard(it, plugin, effect, origin) } }
            override suspend fun selectFiles(effect: JSONObject, origin: DshEffects): DshEffects {
                val current = requireNotNull(session)
                val generation = panelGeneration
                return current.selectFiles(plugin, effect, origin,
                    select = { images, maximum -> requireNotNull(picker) { "此页面无法打开文件选择器" }.pick(DshPickRequest(images, maximum)) },
                    read = fileReader::read,
                    stillCurrent = { session === current && panelGeneration == generation && catalog?.plugins?.any {
                        it.pkg == plugin.pkg && it.version == plugin.version && it.state == "ok" && "files" in it.capabilities &&
                            it.contributions.map { entry -> entry.id to entry.raw.toString() } == plugin.contributions.map { entry -> entry.id to entry.raw.toString() }
                    } == true })
            }
            override fun notice(text: String) { notice = text }
            override fun copy(text: String) { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(plugin.title, text)) }
            override suspend fun insert(text: String) = insertDraft(text)
            override fun command(name: String, args: String) = dispatchCommand(name, args)
            override fun link(url: String) { link = url }
            override suspend fun open(page: String): Boolean {
                if (!page.startsWith("panel:")) return openPage(page)
                val target = plugin.contributions.firstOrNull { it.surface == "panels" && it.id == page.removePrefix("panel:") && it.ready } ?: return false
                openPanel(plugin, target); return true
            }
            override suspend fun refresh(id: String) { panel?.takeIf { it.plugin.pkg == plugin.pkg && (id.isBlank() || it.item.id == id) }?.let { openPanel(it.plugin, it.item) } }
            override fun patch(id: String, data: JSONObject) { panel?.takeIf { it.plugin.pkg == plugin.pkg && (id.isBlank() || it.item.id == id) }?.let {
                panel = it.copy(view = it.view.copy(data = DshProtocol.mergePatch(it.view.data, data)))
            } }
        })
    }
    suspend fun selectHost(host: SavedConnectorConfig) {
        val acquired = DshSharedSessions.acquireAsync(context, repository, host)
        try {
            val loaded = acquired.session.refreshCatalog()
            val remote = if (subjectKey == null) "" else withContext(Dispatchers.IO) { repository.conversationScope(host, subjectKey).optString("sessionId") }
            lease?.close(); lease = acquired; session = acquired.session; selected = host; catalog = loaded; remoteSession = remote
        } catch (failure: Exception) { acquired.close(); throw failure }
    }
    suspend fun invoke(tap: DshPendingTap): Boolean {
        if (pending != null) return false
        val current = session ?: return false
        operation = currentCoroutineContext().job
        val generation = panelGeneration
        pending = tap.action ?: tap.item.id; notice = null
        return try {
            val shared = JSONObject()
            val permission = allowedContext["${current.host.id}:${tap.plugin.pkg}"].orEmpty()
            if ("clipboard" in permission) context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let {
                if (it.toByteArray().size <= 16 * 1024) shared.put("clipboard", it)
            }
            if ("draft" in permission) draft()?.let { if (it.toByteArray().size <= 16 * 1024) shared.put("draft", it) }
            if ("message" in permission && message != null && message.toByteArray().size <= 16 * 1024) shared.put("message", message)
            val result = current.invokeFromTap(tap.plugin, tap.item,
                scope = if (remoteSession.isBlank()) JSONObject() else JSONObject().put("sessionId", remoteSession),
                sharedContext = shared, action = tap.action, value = tap.value, tappedAtNanos = tap.tappedAt, userGesture = tap.userGesture)
            check(session === current && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { "页面已离开，本次效果已作废" }
            check(generation == panelGeneration) { "面板已更新，本次旧操作效果已作废" }
            check(catalog?.plugins?.any { plugin -> plugin.pkg == tap.plugin.pkg && plugin.state == "ok" && plugin.contributions.any {
                it.id == tap.item.id && it.ready && it.raw.toString() == tap.item.raw.toString()
            } } == true) { "插件已更新或停用，本次效果已作废" }
            applyEffects(tap.plugin, result)
            true
        } catch (cancelled: CancellationException) { notice = "插件操作已取消，未自动重发"; throw cancelled }
        catch (failure: Exception) { notice = failure.message?.take(180) ?: "插件未完成操作"; false }
        finally { pending = null; operation = null }
    }
    fun requestTap(tap: DshPendingTap) {
        if (pending != null || busy) return
        val key = "${selected?.id}:${tap.plugin.pkg}"
        val wants = tap.plugin.wants intersect when (tap.item.surface) {
            "composer.actions", "composer.tools" -> setOf("draft", "clipboard")
            "message.actions" -> if (message != null) setOf("message") else emptySet()
            else -> emptySet()
        }
        if (tap.item.effects == null && wants.isNotEmpty() && key !in allowedContext) { chosenContext = wants; consent = tap }
        else scope.launch { invoke(tap) }
    }

    BackHandler { back() }
    LaunchedEffect(Unit) { refreshHosts()
        initialHost?.let { hostId -> hosts.firstOrNull { it.id == hostId && it.enabled }?.let { host ->
            perform { selectHost(host)
                initialPanel?.let { (pkg, id) -> catalog?.plugins?.firstOrNull { it.pkg == pkg }?.let { plugin ->
                    plugin.contributions.firstOrNull { it.surface == "panels" && it.id == id && it.ready }?.let { openPanel(plugin, it) }
                } }
            }
        } }
    }
    DisposableEffect(lease) { val current = lease; onDispose { current?.close() } }
    val activeOperation by rememberUpdatedState(operation)
    DisposableEffect(session, lifecycle) {
        val current = session
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && current?.fileHold?.value != true) activeOperation?.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(lease, lifecycle) { lease?.let { current ->
        try { lifecycle.currentStateFlow.collect { current.visible(it.isAtLeast(Lifecycle.State.STARTED)) } }
        finally { current.visible(false) }
    } }
    LaunchedEffect(session) { session?.notices?.collect { event ->
        if (event.kind in setOf("catalog-updated", "reconnected")) {
            val host = selected
            if (host != null) runCatching { requireNotNull(session).catalog.value ?: requireNotNull(session).refreshCatalog() }.onSuccess { loaded ->
                val old = catalog
                loaded.plugins.forEach { plugin ->
                    if (old?.plugins?.firstOrNull { it.pkg == plugin.pkg }?.let { it.version != plugin.version || it.wants != plugin.wants } == true)
                        allowedContext.remove("${host.id}:${plugin.pkg}")
                }
                catalog = loaded
            }
            panel?.let { previous ->
                val plugin = catalog?.plugins?.firstOrNull { it.pkg == previous.plugin.pkg && it.state == "ok" }
                val item = plugin?.contributions?.firstOrNull { it.id == previous.item.id && it.surface == "panels" && it.ready }
                if (plugin == null || item == null) { panel = null; panelGeneration++; notice = "插件面板已停用或移除" }
                else runCatching { openPanel(plugin, item) }
            }
        } else if (event.kind == "stale") panel?.let { runCatching { openPanel(it.plugin, it.item) } }
        else if (event.kind == "disconnected") notice = "实时连接已断开，正在重连"
        else if (event.kind == "auth-required") notice = "主机授权未恢复，请更新启动令牌后重连"
        else if (event.kind == "auth-forbidden") notice = "主机拒绝了连接，请检查主机地址与访问设置"
    } }
    val activeStream = session?.stream?.collectAsState()?.value
    val fileProgress = session?.fileProgress?.collectAsState()?.value
    val subscribedPanel = panel
    DshPanelSubscription(activeStream, subscribedPanel?.view,
        if (remoteSession.isBlank()) JSONObject() else JSONObject().put("sessionId", remoteSession), panelGeneration,
        onData = { feed -> panel?.takeIf { subscribedPanel != null && it.item.id == subscribedPanel.item.id && it.plugin.pkg == subscribedPanel.plugin.pkg }?.let {
            panel = it.copy(view = it.view.copy(data = feed.data, revision = feed.revision))
        } },
        onEnded = { panelEnded = true; notice = "面板实时内容已结束，请刷新主机状态" })

    MiuixScaffoldPage(title = panel?.view?.title ?: selected?.name ?: "DSH 主机", onBack = ::back, actions = {
        if (pending != null) IconButton(onClick = { operation?.cancel() }) { Icon(Icons.Rounded.Stop, contentDescription = "停止插件操作") }
        if (selected == null) IconButton(enabled = !busy, onClick = { editId = null; name = ""; address = ""; token = ""; editing = true }) {
            Icon(Icons.Rounded.Add, contentDescription = "添加 DSH 主机")
        } else {
          if (subjectKey != null) IconButton(enabled = !busy && pending == null, onClick = { perform {
              bindingInput = remoteSession
              turnInput = if (messageId == null) "" else withContext(Dispatchers.IO) { repository.messageTurns(requireNotNull(selected), subjectKey)[DshHostRepository.localMessageKey(messageId)].orEmpty() }
              editingBinding = true
          } }) {
            Icon(Icons.Rounded.Link, contentDescription = if (remoteSession.isBlank()) "关联远端会话" else "已关联远端会话",
                tint = if (remoteSession.isBlank()) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.primary)
          }
          IconButton(enabled = !busy && pending == null, onClick = { perform {
            val host = requireNotNull(selected)
            catalog = requireNotNull(session).refreshCatalog(force = true)
            panel?.let { openPanel(it.plugin, it.item) }
        } }) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新 DSH 主机") }
        }
    }) {
        notice?.let { text -> item { Text(text, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) } }
        fileProgress?.let { text -> item { Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfiniteProgressIndicator(); Text(text, style = MiuixTheme.textStyles.body2)
        } } }
        if (busy) item { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { InfiniteProgressIndicator() } }
        val activePanel = panel
        if (activePanel != null) item(key = "dsh-panel") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DshViewRenderer(activePanel.view.view, activePanel.view.data, pending,
                    onAction = { action -> invoke(DshPendingTap(activePanel.plugin, activePanel.item, action.id, action.value, action.tappedAtNanos, action.userGesture)) },
                    connected = !busy && !panelEnded && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && (activeStream != null || activePanel.view.view.optInt("v", 1) == 1),
                    generation = panelGeneration,
                    imageScope = "${selected?.id}:${selected?.credentialRevision}:${activePanel.plugin.pkg}:${activePanel.item.id}:$remoteSession",
                    image = { path -> requireNotNull(session).client.asset(activePanel.plugin.pkg, path) })
                session?.let { DshPanelMicro(it, activePanel.plugin, activePanel.item, remoteSession) }
            }
        } else if (selected == null) {
            hosts.forEach { host -> item(key = host.id) {
                Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).etaGlassSurface()) {
                    DetailListRow(host.name, icon = Icons.Rounded.Computer, tone = EtaIconTone.TOOL,
                        value = if (host.enabled) "已启用" else "已停用", enabled = !busy && host.enabled, onClick = { perform {
                            selectHost(host)
                        } })
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                        IconButton(enabled = !busy, onClick = { editId = host.id; name = host.name; address = host.endpoint; token = ""; editing = true }) { Icon(Icons.Rounded.Settings, contentDescription = "编辑 ${host.name}") }
                        IconButton(enabled = !busy, onClick = { perform { withContext(Dispatchers.IO) { repository.setEnabledFromUi(host, !host.enabled) }; refreshHosts() } }) {
                            Icon(if (host.enabled) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (host.enabled) "停用主机" else "启用主机")
                        }
                        IconButton(enabled = !busy, onClick = { deleting = host }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除 ${host.name}") }
                    }
                }
            } }
            if (hosts.isEmpty()) item { Text("尚未添加 DSH 主机", style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(24.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        } else {
            item { Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Circle, contentDescription = null, modifier = Modifier.size(8.dp), tint = if (activeStream != null) androidx.compose.ui.graphics.Color(0xFF209665) else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(if (activeStream != null) "实时连接" else "正在连接", style = MiuixTheme.textStyles.body2)
            } }
            catalog?.plugins?.forEach { plugin -> item(key = plugin.pkg) {
                Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).etaGlassSurface()) {
                    DetailListRow(plugin.title, icon = Icons.Rounded.Extension, tone = EtaIconTone.TOOL, value = when (plugin.state) {
                        "ok" -> plugin.version; "needs-app" -> "插件要求更高应用版本（当前 ${io.github.mangi.eta.BuildConfig.VERSION_NAME.substringBefore('-')}）"; "unsupported" -> "协议暂不支持"; "invalid" -> "插件声明有误"; else -> "当前不可用"
                    })
                    if ("voice" in plugin.capabilities) SwitchPreference(title = "听你开口", checked = plugin.pkg in allowedVoices,
                        onCheckedChange = { allowed -> perform { withContext(Dispatchers.IO) { repository.setVoiceAllowed(requireNotNull(selected), plugin.pkg, allowed) } } })
                    plugin.contributions.filter { it.surface == "panels" || it.surface in actionSurfaces }.forEach { contribution ->
                        DetailListRow(contribution.title, icon = if (contribution.surface == "panels") Icons.Rounded.Dashboard else Icons.Rounded.PlayArrow,
                            tone = EtaIconTone.TOOL, enabled = contribution.ready && !busy && pending == null && activeStream?.features.orEmpty().containsAll(DshProtocol.requiredFeatures(contribution)),
                            onClick = { val target = contribution.panel?.let { id -> plugin.contributions.firstOrNull { it.surface == "panels" && it.id == id && it.ready } }
                                if (contribution.surface == "panels") perform { openPanel(plugin, contribution) }
                                else if (target != null && contribution.effects == null) perform { openPanel(plugin, target) }
                                else requestTap(DshPendingTap(plugin, contribution, null, null)) })
                    }
                }
            } }
        }
    }

    if (editing) WindowDialog(show = true, title = if (editId == null) "添加主机" else "编辑主机", onDismissRequest = { if (!busy) editing = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(value = name, onValueChange = { name = it.take(80) }, label = "名称", singleLine = true, modifier = Modifier.fillMaxWidth())
            TextField(value = address, onValueChange = { address = it.take(8192) }, label = "主机地址或启动链接", singleLine = true, modifier = Modifier.fillMaxWidth())
            TextField(value = token, onValueChange = { token = it.take(4096) }, label = "启动令牌", visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            notice?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2) }
            MiuixDialogActions(confirmText = "保存", confirmEnabled = !busy && name.isNotBlank() && address.isNotBlank(), onCancel = { if (!busy) editing = false }, onConfirm = {
                perform { withContext(Dispatchers.IO) { repository.saveFromUi(editId, name, address, token) }; token = ""; editing = false; refreshHosts() }
            })
        }
    }
    if (editingBinding) WindowDialog(show = true, title = "关联远端会话", onDismissRequest = { if (!busy) editingBinding = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(value = bindingInput, onValueChange = { bindingInput = it.take(256) }, label = "DSH 会话编号", singleLine = true, modifier = Modifier.fillMaxWidth())
            if (messageId != null) TextField(value = turnInput, onValueChange = { turnInput = it.take(64) }, label = "DSH 回合编号", singleLine = true, modifier = Modifier.fillMaxWidth())
            notice?.let { Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error) }
            MiuixDialogActions(confirmText = "保存", confirmEnabled = !busy, onCancel = { editingBinding = false }, onConfirm = { perform {
                val host = requireNotNull(selected); val subject = requireNotNull(subjectKey)
                withContext(Dispatchers.IO) { repository.bindConversation(host, subject, bindingInput, messageId, turnInput) }
                operation?.cancel(); panel = null; panelGeneration++; remoteSession = bindingInput.trim(); editingBinding = false
                notice = if (remoteSession.isBlank()) "已解除关联" else "已关联远端会话"
            } })
        }
    }
    deleting?.let { host -> WindowDialog(show = true, title = "删除主机", onDismissRequest = { if (!busy) deleting = null }) {
        Text(host.name, style = MiuixTheme.textStyles.body1)
        MiuixDialogActions(confirmText = "删除", destructive = true, confirmEnabled = !busy, onCancel = { deleting = null }, onConfirm = {
            perform { withContext(Dispatchers.IO) { repository.deleteFromUi(host) }; deleting = null; refreshHosts() }
        })
    } }
    consent?.let { tap -> WindowDialog(show = true, title = "共享给 ${tap.plugin.title}", onDismissRequest = { consent = null }) {
        Column {
            (tap.plugin.wants intersect if (tap.item.surface == "message.actions") setOf("message") else setOf("clipboard", "draft")).forEach { name -> SwitchPreference(title = when (name) { "clipboard" -> "剪贴板"; "message" -> "当前回复"; else -> "当前草稿" },
                checked = name in chosenContext, onCheckedChange = { chosenContext = if (it) chosenContext + name else chosenContext - name }) }
            MiuixDialogActions(confirmText = "继续", onCancel = { consent = null }, onConfirm = {
                allowedContext["${selected?.id}:${tap.plugin.pkg}"] = chosenContext
                consent = null; scope.launch { invoke(tap) }
            })
        }
    } }
    link?.let { url -> WindowDialog(show = true, title = "打开外部链接", onDismissRequest = { link = null }) {
        Text(Uri.parse(url).host.orEmpty(), style = MiuixTheme.textStyles.body1)
        MiuixDialogActions(confirmText = "打开", onCancel = { link = null }, onConfirm = {
            link = null
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { notice = "无法打开链接" }
        })
    } }
}
