package io.github.mangi.eta.ui.screens.capability

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.packages.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.*
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PackageUiScreen(initial: PackageUiModule, onBack: () -> Unit, subject: ConversationSubject? = null,
    service: PackageUiService? = null, initialState: JSONObject = initial.params) {
    val context = LocalContext.current.applicationContext
    val api = remember(context, service) { service ?: PackageUiService(context) }
    val scope = rememberCoroutineScope()
    var module by remember(initial) { mutableStateOf(initial) }
    var document by remember(initial) { mutableStateOf<PackageUiDocument?>(null) }
    var preview by remember(initial) { mutableStateOf<PackageUiDocument?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var controller by remember { mutableStateOf<AgentRunController?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dirtyFields by remember { mutableStateOf<Set<String>>(emptySet()) }
    var revision by remember { mutableLongStateOf(0) }
    val history = remember { mutableListOf<Pair<PackageUiModule, PackageUiDocument>>() }
    val images = remember(module.record.id, module.record.digest) { DshPanelImages(scope) { path -> api.image(module, path) } }
    DisposableEffect(images) { onDispose { images.close() } }
    fun back() {
        if (busy) { controller?.cancel(); return }
        if (history.isEmpty()) onBack() else { val previous = history.removeAt(history.lastIndex); module = previous.first; document = previous.second; error = null }
    }
    fun perform(action: String? = null, payload: Any? = null) {
        if (busy) return
        val currentModule = module; val current = document
        val owner = AgentRunController(); controller = owner; busy = true; error = null; preview = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (action == null || current == null) api.render(currentModule, state = initialState, controller = owner, subject = subject)
                    else api.action(currentModule, current, action, payload, owner, subject, onFrame = { frame ->
                        scope.launch { if (controller === owner && busy && module == currentModule) preview = frame }
                    })
                }
                owner.throwIfCancelled()
                document = result
                preview = null
                revision++
                for (effect in result.effects) when (effect.optString("type")) {
                    "notice" -> notice = effect.optString("text").take(500)
                    "navigate" -> {
                        check(action != null) { "插件不能在打开页面时自动跳转" }
                        val route = effect.getString("route")
                        val target = withContext(Dispatchers.IO) { api.modules(currentModule.record.id).singleOrNull { it.route == route } }
                            ?: error("插件目标页面不存在")
                        check(history.size < 8) { "插件页面层级过深" }
                        val nextState = JSONObject(target.params.toString())
                        effect.optJSONObject("arguments")?.let { args -> args.keys().forEach { nextState.put(it, args.get(it)) } }
                        val next = withContext(Dispatchers.IO) { api.render(target, state = nextState, controller = owner, subject = subject) }
                        history += currentModule to result; module = target; document = next
                    }
                }
            } catch (cancelled: CancellationException) { error = "插件操作已取消，未自动重发"; throw cancelled }
            catch (failure: Exception) { error = failure.message?.take(180) ?: "插件界面暂不可用" }
            finally { preview = null; busy = false; controller = null; owner.cancel() }
        }
    }
    DisposableEffect(initial) { onDispose { controller?.cancel() } }
    LaunchedEffect(initial) { perform() }
    BackHandler(onBack = ::back)
    MiuixScaffoldPage(module.title, onBack = ::back) {
        item { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(module.record.title, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (busy) InfiniteProgressIndicator()
            notice?.let { Text(it, style = MiuixTheme.textStyles.body2) }
            error?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2) }
            (preview ?: document)?.let { CompositionLocalProvider(LocalPackageImages provides images) { key(module.record.id, module.id) { PackageComposeNode(it.tree, enabled = !busy, action = ::perform, close = ::back,
                commandsEnabled = !busy && dirtyFields.isEmpty(), editing = { field, dirty -> dirtyFields = if (dirty) dirtyFields + field else dirtyFields - field }, revision = revision) } }
            }
            if (!busy && document == null && error != null) TextButton("重试", onClick = { perform() }, textStyle = MiuixTheme.textStyles.body2, minHeight = 36.dp)
            if (!busy && document != null && error != null) TextButton("重新读取", onClick = { perform() }, textStyle = MiuixTheme.textStyles.body2, minHeight = 36.dp)
        } }
    }
}
