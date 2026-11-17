package io.github.mangi.eta.ui.screens.capability

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.ui.unit.dp
import androidx.activity.compose.LocalActivityResultRegistryOwner
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.mangi.eta.agent.dsh.*
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.data.repository.ConversationDraftRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class DshConversationActions(val composer: () -> Unit, val message: (String, String) -> Unit, val tap: (DshNativeTarget) -> Unit)
internal val LocalDshConversationActions = staticCompositionLocalOf<DshConversationActions?> { null }
private data class DshEntryRequest(val message: String? = null, val messageId: String? = null, val hostId: String? = null, val panel: Pair<String, String>? = null)
private data class DshConsent(val target: DshNativeTarget, val wants: Set<String>, val tappedAt: Long)

/** The local subject is only an insertion target; it is never sent as a foreign DSH session ID. */
@Composable
internal fun DshConversationEntry(subject: ConversationSubject, sessionDetails: Boolean = false,
    onCommand: (String, String) -> Boolean = { _, _ -> false }, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val repository = remember(context) { DshHostRepository(context) }
    val drafts = remember(context) { ConversationDraftRepository.get(context) }
    val feeds = remember(subject.key, context) { DshConversationFeeds(context, subject.key, repository) }
    val scope = rememberCoroutineScope()
    val currentCommand by rememberUpdatedState(onCommand)
    var available by remember(subject.key) { mutableStateOf(false) }
    val nativeAvailable by remember(feeds) { feeds.snapshot.map { it.available }.distinctUntilChanged() }.collectAsState(false)
    var request by remember(subject.key) { mutableStateOf<DshEntryRequest?>(null) }
    var mounted by remember(subject.key) { mutableStateOf(true) }
    var notice by remember(subject.key) { mutableStateOf<String?>(null) }
    var running by remember(subject.key) { mutableStateOf(false) }
    var operation by remember(subject.key) { mutableStateOf<Job?>(null) }
    var activeSession by remember(subject.key) { mutableStateOf<DshUiSession?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    var consent by remember { mutableStateOf<DshConsent?>(null) }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    val shared = remember(subject.key) { mutableMapOf<String, Set<String>>() }
    val registry = LocalActivityResultRegistryOwner.current?.activityResultRegistry
    val picker = remember(registry, lifecycle) { registry?.let { DshAndroidFilePicker(it, lifecycle) } }
    val reader = remember(context) { DshPickedFileReader(context) }
    val media = remember(context) { DshMediaController.get(context) }
    val voicePermission = rememberDshVoicePermission(repository)
    DshMediaSurface(subject.key)
    fun permissionKey(target: DshNativeTarget) = "${target.host.id}:${target.host.credentialRevision}:${target.pkg}:${target.version}"
    fun closePanel() { request = null; feeds.reload() }
    fun openPanel(target: DshNativeTarget, id: String) { request = DshEntryRequest(hostId = target.host.id, panel = target.pkg to id) }

    suspend fun execute(target: DshNativeTarget, tappedAt: Long) {
        var lease: DshSharedSessions.Lease? = null
        try {
            lease = DshSharedSessions.acquireAsync(context, repository, target.host)
            val session = lease.session; activeSession = session
            val plugin = session.catalog.value?.plugins?.firstOrNull { it.pkg == target.pkg && it.state == "ok" && it.version == target.version }
                ?: error("插件已更新，请刷新后重试")
            val item = plugin.contributions.firstOrNull { it.id == target.id && it.surface == target.surface && it.raw.toString() == target.declaration && it.ready }
                ?: error("插件操作已变化，请重新选择")
            if (item.panel != null && item.effects == null) { openPanel(target, item.panel); return }
            val wants = if (item.surface in setOf("composer.actions", "composer.tools")) plugin.wants intersect setOf("clipboard", "draft") else emptySet()
            val key = permissionKey(target)
            if (item.effects == null && wants.isNotEmpty() && key !in shared) { chosen = wants; consent = DshConsent(target, wants, tappedAt); return }
            val remote = withContext(Dispatchers.IO) { repository.conversationScope(target.host, subject.key) }
            val remoteKey = remote.toString()
            suspend fun stillCurrent(): Boolean = mounted && repository.isCurrent(target.host) &&
                withContext(Dispatchers.IO) { repository.conversationScope(target.host, subject.key).toString() == remoteKey } &&
                session.catalog.value?.plugins?.any { current -> current.pkg == plugin.pkg && current.version == plugin.version && current.state == "ok" &&
                    current.contributions.any { it.id == item.id && it.raw.toString() == item.raw.toString() } } == true
            val allowed = shared[key].orEmpty(); val values = JSONObject()
            if ("draft" in allowed) (drafts.activeDraft(subject)?.text ?: drafts.load(drafts.open(subject))?.text)?.let { if (it.toByteArray().size <= 16 * 1024) values.put("draft", it) }
            if ("clipboard" in allowed) context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let {
                if (it.toByteArray().size <= 16 * 1024) values.put("clipboard", it)
            }
            val result = session.invokeFromTap(plugin, item, remote, values, tappedAtNanos = tappedAt)
            check(stillCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { "会话或插件已变化，本次效果已作废" }
            DshEffectRouter.apply(result, object : DshEffectTarget {
                override suspend fun capability(effect: JSONObject, origin: DshEffects) {
                    media.apply(subject.key, subject.key, session, plugin, remote, effect, origin, voicePermission = { voicePermission(target.host, plugin) })
                }
                override fun discardCapability(effect: JSONObject, origin: DshEffects) { media.discard(session, plugin, effect, origin) }
                override suspend fun selectFiles(effect: JSONObject, origin: DshEffects): DshEffects = session.selectFiles(plugin, effect, origin,
                    select = { images, maximum -> requireNotNull(picker).pick(DshPickRequest(images, maximum)) }, read = reader::read,
                    stillCurrent = { mounted && repository.isCurrent(target.host) && repository.conversationScope(target.host, subject.key).toString() == remoteKey && session.catalog.value?.plugins?.any { it.pkg == plugin.pkg && it.version == plugin.version && it.state == "ok" } == true })
                override fun notice(text: String) { notice = text }
                override fun copy(text: String) { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(plugin.title, text)) }
                override suspend fun insert(text: String) = drafts.insertExternal(subject, text) { mounted }
                override fun command(name: String, args: String) = mounted && currentCommand(name, args)
                override fun link(url: String) { link = url }
                override suspend fun open(page: String): Boolean {
                    val id = page.removePrefix("panel:")
                    if (!page.startsWith("panel:") || plugin.contributions.none { it.surface == "panels" && it.id == id && it.ready }) return false
                    openPanel(target, id); return true
                }
                override suspend fun refresh(id: String) { session.refreshCatalog(force = true); feeds.reload() }
                override fun patch(id: String, data: JSONObject) = Unit
            })
        } catch (cancelled: CancellationException) { notice = "插件操作已取消，未自动重发"; throw cancelled }
        catch (failure: Exception) { notice = failure.message?.take(120) ?: "插件操作未完成" }
        finally { lease?.close(); activeSession = null }
    }
    fun tap(target: DshNativeTarget, clickedAt: Long = System.nanoTime()) {
        if (running) return
        running = true; notice = null
        operation = scope.launch { try { execute(target, clickedAt) } finally { running = false; operation = null } }
    }
    DisposableEffect(subject.key) { mounted = true; onDispose { mounted = false } }
    val ownedOperation by rememberUpdatedState(operation)
    val ownedSession by rememberUpdatedState(activeSession)
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && ownedSession?.fileHold?.value != true) ownedOperation?.cancel() }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(notice) { if (notice != null) { delay(4000); notice = null } }
    LaunchedEffect(subject.key, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            available = withContext(Dispatchers.IO) { repository.hosts().any { host -> host.enabled && (!sessionDetails ||
                repository.cached(host)?.plugins?.any { plugin -> plugin.state == "ok" && plugin.contributions.any { it.ready && it.surface in DshProtocol.supportedSurfaces } } == true) } }
            if (available && !sessionDetails && subject.id.isNotBlank()) {
                try { feeds.maintain() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notice = "插件实时内容暂不可用" }
            } else awaitCancellation()
        }
    }
    val actions = if ((if (sessionDetails) available else nativeAvailable) && subject.id.isNotBlank()) DshConversationActions(
        composer = { request = DshEntryRequest() }, message = { id, text -> request = DshEntryRequest(text, id) }, tap = { tap(it) }) else null
    CompositionLocalProvider(LocalDshConversationActions provides actions, LocalDshFeeds provides feeds.takeUnless { sessionDetails },
        LocalDshInteraction provides DshInteractionUi(notice, running) { operation?.cancel() }) {
        if (sessionDetails) content() else PackageConversationEntry(subject) { content() }
    }
    request?.let { entry ->
        Dialog(onDismissRequest = ::closePanel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
                DshHostsScreen(onBack = ::closePanel,
                    subjectKey = subject.key,
                    messageId = entry.messageId, initialHost = entry.hostId, initialPanel = entry.panel, command = { name, args -> mounted && currentCommand(name, args) },
                    actionSurfaces = if (sessionDetails) setOf("session.actions") else if (entry.message == null) setOf("composer.actions", "composer.tools") else setOf("message.actions"),
                    message = entry.message,
                    draft = {
                        if (!mounted) null else drafts.activeDraft(subject)?.text ?: drafts.load(drafts.open(subject))?.text
                    },
                    insertDraft = { text -> drafts.insertExternal(subject, text) { mounted } })
            }
        }
    }
    consent?.let { selected -> WindowDialog(show = true, title = "共享给插件", onDismissRequest = { consent = null }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            selected.wants.forEach { key -> SwitchPreference(title = if (key == "draft") "当前草稿" else "剪贴板", checked = key in chosen,
                onCheckedChange = { chosen = if (it) chosen + key else chosen - key }) }
            MiuixDialogActions(confirmText = "继续", onCancel = { consent = null }, onConfirm = {
                shared[permissionKey(selected.target)] = chosen; consent = null
                if (System.nanoTime() - selected.tappedAt <= 3_000_000_000L) tap(selected.target, selected.tappedAt)
                else notice = "共享选择已保存，请再次点击操作"
            })
        }
    } }
    link?.let { url -> WindowDialog(show = true, title = "打开外部链接", onDismissRequest = { link = null }) {
        Text(Uri.parse(url).host.orEmpty(), style = MiuixTheme.textStyles.body2)
        MiuixDialogActions(confirmText = "打开", onCancel = { link = null }, onConfirm = {
            link = null; runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { notice = "无法打开链接" }
        })
    } }
}

@Composable
internal fun DshSessionActionsEntry(subject: ConversationSubject, onCommand: (String, String) -> Boolean = { _, _ -> false }) {
    DshConversationEntry(subject, sessionDetails = true, onCommand = onCommand) {
        LocalDshConversationActions.current?.let { actions ->
            io.github.mangi.eta.ui.components.DetailListRow("会话插件", icon = androidx.compose.material.icons.Icons.Rounded.Extension,
                tone = io.github.mangi.eta.ui.components.EtaIconTone.TOOL, onClick = actions.composer)
        }
    }
}
