package io.github.mangi.eta.ui.screens.capability

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.mangi.eta.agent.packages.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.ui.components.*
import kotlinx.coroutines.*
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference

internal data class PackageConversationActions(val composer: () -> Unit, val message: (String, String) -> Unit)
internal val LocalPackageConversationActions = staticCompositionLocalOf<PackageConversationActions?> { null }
private data class PackageMenuTarget(val message: String? = null, val content: String = "")

/** Installed package actions share the existing conversation plugin entry and Runtime approval UI. */
@Composable
internal fun PackageConversationEntry(subject: ConversationSubject, inputToggleService: PackageInputToggles? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val service = remember(context) { PackageApplicationService(context) }
    val store = remember(context) { CapabilityPackageStore(context) }
    val toggles = remember(context, inputToggleService) { inputToggleService ?: PackageInputToggles(context) }
    val dsh = LocalDshConversationActions.current
    var available by remember(subject.key) { mutableStateOf(false) }
    var target by remember(subject.key) { mutableStateOf<PackageMenuTarget?>(null) }
    var rows by remember { mutableStateOf<List<Pair<CapabilityPackage, PackageTool>>>(emptyList()) }
    var inputToggles by remember { mutableStateOf<List<PackageInputToggle>>(emptyList()) }
    var uiModules by remember { mutableStateOf<List<PackageUiModule>>(emptyList()) }
    var shownModule by remember(subject.key) { mutableStateOf<PackageUiModule?>(null) }
    var uiState by remember(subject.key) { mutableStateOf(JSONObject()) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var controller by remember { mutableStateOf<AgentRunController?>(null) }
    val approval by io.github.mangi.eta.agent.control.EtaAppControlConfirmation.pending.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(subject.key) { onDispose { controller?.cancel() } }
    LaunchedEffect(subject.key, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        available = withContext(Dispatchers.IO) { store.conversationActions("NavigationEntry").isNotEmpty() || store.conversationActions("ChatMessageMenuItem").isNotEmpty() || toggles.available() || PackageUiService(context).modules().isNotEmpty() }
        awaitCancellation()
    } }
    LaunchedEffect(target) { val selected = target ?: return@LaunchedEffect
        busy = true; result = null
        val owner = AgentRunController(); controller = owner
        try {
            rows = withContext(Dispatchers.IO) { store.conversationActions(if (selected.message == null) "NavigationEntry" else "ChatMessageMenuItem") }
            inputToggles = if (selected.message == null) withContext(Dispatchers.IO) { toggles.load(subject, owner) } else emptyList()
            uiModules = withContext(Dispatchers.IO) { PackageUiService(context).modules().filter { module ->
                if (selected.message == null) module.messageMenuId == null else module.messageMenuId != null
            } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { result = "插件菜单暂不可读取" }
        finally { busy = false; controller = null; owner.cancel() }
    }
    val actions = if (subject.id.isNotBlank() && (available || dsh != null)) PackageConversationActions(
        composer = { target = PackageMenuTarget() }, message = { id, text -> target = PackageMenuTarget(id, text) }) else null
    CompositionLocalProvider(LocalPackageConversationActions provides actions) { content() }
    shownModule?.let { module ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { shownModule = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false)) { PackageUiScreen(module, onBack = { shownModule = null }, subject = subject, initialState = uiState) }
    }
    target?.let { selected -> WindowDialog(show = approval == null, title = "插件操作", onDismissRequest = { if (controller != null) controller?.cancel() else target = null }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (result != null) SelectionContainer { Text(result!!, style = MiuixTheme.textStyles.body2) }
                else {
                    if (dsh != null) DetailListRow("主机插件", icon = Icons.Rounded.Computer, tone = EtaIconTone.TOOL, enabled = !busy, onClick = {
                        target = null
                        if (selected.message == null) dsh.composer() else dsh.message(selected.message, selected.content)
                    })
                    inputToggles.forEach { toggle ->
                        SwitchPreference(title = toggle.title, checked = toggle.checked, enabled = !busy, onCheckedChange = {
                            val owner = AgentRunController(); controller = owner; busy = true
                            scope.launch {
                                try {
                                    val updated = withContext(Dispatchers.IO) { toggles.toggle(toggle, subject, owner) }
                                    inputToggles = inputToggles.filterNot { it.packageId == toggle.packageId && it.tool == toggle.tool } + updated
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { result = "输入开关保存未完成" }
                                finally { busy = false; controller = null; owner.cancel() }
                            }
                        })
                    }
                    uiModules.forEach { module -> DetailListRow(module.title, icon = Icons.Rounded.Dashboard, tone = EtaIconTone.TOOL, enabled = !busy,
                        onClick = {
                            if (module.messageMenuId == null) {
                                uiState = JSONObject(module.params.toString()).put("chatId", subject.id)
                                target = null; shownModule = module
                            } else {
                                val owner = AgentRunController(); controller = owner; busy = true
                                scope.launch {
                                    try {
                                        val menu = rows.single { it.first.id == module.record.id && it.second.callbackKey?.split('/')?.getOrNull(1) == module.messageMenuId }
                                        val event = JSONObject().put("action", "click").put("chatId", subject.id).put("messageId", selected.message)
                                            .put("menuItemId", module.messageMenuId).put("message", JSONObject().put("id", selected.message)
                                                .put("sender", "ai").put("content", selected.content))
                                        val response = withContext(Dispatchers.IO) { service.runActionReviewed(menu.first, menu.second,
                                            JSONObject().put("eventPayload", event), owner, subject) }
                                        check(response.ok) { response.error ?: "插件消息操作未完成" }
                                        val dialog = JSONObject(response.content).getJSONObject("value").getJSONObject("dialog")
                                        uiState = dialog.getJSONObject("state")
                                        shownModule = module.copy(launchSpec = dialog.optJSONObject("moduleSpec") ?: JSONObject())
                                        target = null
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { result = failure.message?.take(180) ?: "插件消息窗口暂不可用" }
                                    finally { busy = false; controller = null; owner.cancel() }
                                }
                            }
                        }) }
                    rows.filterNot { (record, tool) -> uiModules.any { it.record.id == record.id && it.messageMenuId == tool.callbackKey?.split('/')?.getOrNull(1) } }
                        .forEach { (record, tool) -> DetailListRow(tool.description.ifBlank { record.title }, icon = Icons.Rounded.Extension,
                        tone = EtaIconTone.TOOL, enabled = !busy, onClick = {
                            val owner = AgentRunController(); controller = owner; busy = true
                            scope.launch {
                                try {
                                    val id = tool.callbackKey?.split('/')?.getOrNull(1).orEmpty()
                                    val payload = JSONObject().put("action", "click").put("chatId", subject.id).put("chatType", subject.kind.value)
                                        .put("menuItemId", id).put("entryId", id)
                                    if (selected.message != null) payload.put("messageId", selected.message).put("message", JSONObject()
                                        .put("sender", "ai").put("content", selected.content).put("id", selected.message))
                                    val response = withContext(Dispatchers.IO) { service.runActionReviewed(record, tool, JSONObject().put("eventPayload", payload), owner, subject) }
                                    result = if (!response.ok) response.error else {
                                        val body = runCatching { JSONObject(response.content).opt("value") }.getOrNull()
                                        when (body) { null, JSONObject.NULL -> "已完成"; is String -> body; else -> body.toString() }
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { result = failure.message?.take(200) ?: "插件操作未完成" }
                                finally { busy = false; controller = null }
                            }
                        }) }
                    if (!busy && rows.isEmpty() && inputToggles.isEmpty() && uiModules.isEmpty() && dsh == null) Text("暂无可用操作", style = MiuixTheme.textStyles.body2)
                }
            }
            if (busy) InfiniteProgressIndicator()
            MiuixDialogActions(confirmText = if (controller != null) "停止" else "关闭", onConfirm = { if (controller != null) controller?.cancel() else target = null },
                onCancel = { if (controller != null) controller?.cancel() else target = null })
        }
    } }
}

@Composable
internal fun ConversationPluginMessageButton(messageId: String, content: String) {
    val packageActions = LocalPackageConversationActions.current
    val dsh = LocalDshConversationActions.current
    if (packageActions != null || dsh != null) TooltipBox(text = "插件操作") {
        AnswerActionButton(Icons.Rounded.Extension, "对此回复使用插件", onClick = { if (packageActions != null) packageActions.message(messageId, content) else dsh?.message?.invoke(messageId, content) })
    }
}
