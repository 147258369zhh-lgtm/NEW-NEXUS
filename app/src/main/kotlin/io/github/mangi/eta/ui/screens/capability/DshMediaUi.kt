package io.github.mangi.eta.ui.screens.capability

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.agent.connector.SavedConnectorConfig
import io.github.mangi.eta.agent.dsh.*
import io.github.mangi.eta.ui.components.MiuixDialogActions
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private data class DshMicPrompt(val host: SavedConnectorConfig, val plugin: DshPlugin, val answer: CompletableDeferred<Boolean>)

@Composable
internal fun rememberDshVoicePermission(repository: DshHostRepository): suspend (SavedConnectorConfig, DshPlugin) -> Boolean {
    val context = LocalContext.current.applicationContext
    var prompt by remember { mutableStateOf<DshMicPrompt?>(null) }
    var permission by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val pending by rememberUpdatedState(permission)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> pending?.complete(allowed); permission = null }
    val activePrompt by rememberUpdatedState(prompt)
    DisposableEffect(Unit) { onDispose { activePrompt?.answer?.cancel(); pending?.cancel() } }
    prompt?.let { request -> WindowDialog(show = true, title = "允许「${request.plugin.title}」听你开口？", onDismissRequest = { request.answer.complete(false); prompt = null }) {
        Text("仅检测开口和停顿，不上传录音或文字。", style = MiuixTheme.textStyles.body2)
        MiuixDialogActions(confirmText = "允许", onCancel = { request.answer.complete(false); prompt = null }, onConfirm = {
            try { repository.setVoiceAllowed(request.host, request.plugin.pkg, true); request.answer.complete(true) }
            catch (_: Exception) { request.answer.complete(false) }
            prompt = null
        })
    } }
    return remember(repository, ask) { { host, plugin ->
        try {
            val approved = if (repository.voiceAllowed(host, plugin.pkg)) true else {
                check(prompt == null)
                val response = CompletableDeferred<Boolean>(); prompt = DshMicPrompt(host, plugin, response)
                try { response.await() } finally { if (prompt?.answer === response) prompt = null }
            }
            if (!approved) false else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) true
            else {
                check(permission == null)
                val response = CompletableDeferred<Boolean>(); permission = response
                try { ask.launch(Manifest.permission.RECORD_AUDIO) }
                catch (failure: Exception) { if (permission === response) permission = null; throw failure }
                // A cancelled caller does not transfer a still-open system dialog to a later request.
                try { response.await() } catch (cancelled: CancellationException) { response.cancel(); throw cancelled }
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
    } }
}

/** App-owned windows, never a system-wide overlay. The newest visible surface draws the controls. */
@Composable
internal fun DshMediaSurface(surfaceKey: String, mediaController: DshMediaController? = null) {
    val context = LocalContext.current.applicationContext
    val controller = remember(context, mediaController) { mediaController ?: DshMediaController.get(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val token = remember(surfaceKey) { Any() }
    DisposableEffect(controller, token, lifecycle) {
        controller.surface(token, surfaceKey, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, _ -> controller.surface(token, surfaceKey, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.removeSurface(token) }
    }
    val state by controller.state.collectAsState()
    val top by controller.topSurface.collectAsState()
    val topOffset = with(LocalDensity.current) { 80.dp.roundToPx() }
    val bottomOffset = with(LocalDensity.current) { 112.dp.roundToPx() }
    if (top === token && state.surface == surfaceKey) {
        Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, topOffset), properties = PopupProperties(focusable = false, clippingEnabled = true)) {
            Row(Modifier.fillMaxWidth(.92f).widthIn(max = 420.dp).background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (state.listening || state.waitingPermission) Icons.Rounded.Mic else Icons.Rounded.VolumeUp, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(state.author, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.status ?: when { state.waitingPermission -> "等待麦克风授权"; state.listening -> "正在听你开口"; state.audio == "loading" -> "加载音频"; state.audio == "paused" -> "已暂停"; state.audio != null -> "正在播放"; else -> "字幕" },
                        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
                }
                if (state.audio != null && state.audio != "loading") IconButton(onClick = controller::pauseOrResume, modifier = Modifier.size(36.dp)) {
                    Icon(if (state.audio == "paused") Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (state.audio == "paused") "继续播放" else "暂停播放", Modifier.size(18.dp))
                }
                IconButton(onClick = { controller.stop() }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Stop, "停止插件聆听和播放", Modifier.size(18.dp)) }
            }
        }
        if (state.overlay) Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -bottomOffset), properties = PopupProperties(focusable = false, clippingEnabled = true)) {
            Column(Modifier.fillMaxWidth(.92f).widthIn(max = 420.dp).background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.Extension, state.author, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(state.caption, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = controller::hideOverlay, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "关闭插件字幕", Modifier.size(16.dp)) }
                }
                state.progress?.let { progress -> androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                    drawRect(androidx.compose.ui.graphics.Color(0xFF209665), size = androidx.compose.ui.geometry.Size(size.width * progress.toFloat(), size.height))
                } }
            }
        }
    }
}

@Composable
internal fun DshPanelMicro(session: DshUiSession, plugin: DshPlugin, contribution: DshContribution, remote: String) {
    val stream by session.stream.collectAsState()
    val channel = contribution.raw.optString("micro")
    var model by remember(session, plugin.pkg, contribution.id, channel, remote) { mutableStateOf<org.json.JSONObject?>(null) }
    LaunchedEffect(stream, channel, remote) {
        val active = stream
        if (active != null && "micro" in active.features && "micro" in plugin.capabilities && plugin.contributions.any { it.surface == "micro" && it.id == channel }) {
            try {
                val sub = active.subscribeMicro(plugin.pkg, channel, org.json.JSONObject().apply { if (remote.isNotBlank()) put("sessionId", remote) })
                try { active.microFeeds.collect { rows -> model = rows[sub]?.takeIf { it.active }?.data as? org.json.JSONObject } }
                finally { model = null; active.unsubscribeMicro(sub) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { model = null }
        }
    }
    model?.let { data ->
        if (data.has("text")) Text(DshConversationFeeds.singleLine((data.opt("text") as? String).orEmpty(), 240), style = MiuixTheme.textStyles.body2,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (data.has("progress") && !data.isNull("progress")) Text("${(data.optDouble("progress") * 100).toInt()}%", style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
