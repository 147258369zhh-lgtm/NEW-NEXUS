package io.github.mangi.eta.ui.screens.control

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.control.NexusSettingsControlPlane
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SettingsHistoryDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val plane = remember(context) { NexusSettingsControlPlane.get(context) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val pendingApproval by EtaAppControlConfirmation.pending.collectAsState()
    val pendingInputs by io.github.mangi.eta.agent.runtime.RunUserInput.pending.collectAsState()
    val titles = remember { plane.list().associate { it.id to it.title } }
    suspend fun refresh() { entries = withContext(Dispatchers.IO) { plane.audit().filter { it.optString("operation") in setOf("execute", "undo") } } }
    LaunchedEffect(plane) {
        runCatching { refresh() }.onFailure { error = "设置记录暂不可读取" }
        loaded = true
    }
    WindowDialog(show = pendingApproval == null && pendingInputs.isEmpty(), title = "设置记录", onDismissRequest = onDismiss) {
        val scheme = MiuixTheme.colorScheme
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = scheme.error) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!loaded || entries.isEmpty()) item {
                    Text(if (loaded) "暂无设置修改" else "正在读取", Modifier.padding(vertical = 20.dp),
                        style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary)
                }
                items(entries, key = { it.getString("id") }) { entry ->
                    val id = entry.getString("id")
                    val expanded = selected == id
                    val undone = entry.optBoolean("undone")
                    val phase = entry.optString("phase")
                    val status = when {
                        undone -> "已撤销"
                        phase == "NO_CHANGE" -> "未变化"
                        phase == "PENDING" -> "待核对"
                        phase == "UNCERTAIN" -> "待核对"
                        !entry.optBoolean("success") -> "未完成"
                        else -> "已修改"
                    }
                    Column(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(8.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { selected = if (expanded) null else id },
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(titles[entry.optString("capabilityId")] ?: entry.optString("capabilityId"),
                                    style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
                                val actor = if (entry.optString("actor") == SettingsActorKind.LOCAL_USER.name) "你" else "Agent"
                                Text("$actor · ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.optLong("timestamp")))} · $status",
                                    style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary)
                            }
                            if (entry.optBoolean("rollbackSafe") && entry.optBoolean("success") && !undone) {
                                IconButton(onClick = {
                                    if (busy == null) scope.launch {
                                        busy = id; error = null
                                        try {
                                            withContext(Dispatchers.IO) { plane.rollbackJson(id, SettingsActorKind.LOCAL_USER) }
                                            refresh()
                                        } catch (failure: Exception) {
                                            error = if (failure.message == "SETTINGS_CHANGED_SINCE_AUDIT") "设置已有后续修改，无法撤销这条记录"
                                                else "撤销未完成，请核对当前设置"
                                        } finally { busy = null }
                                    }
                                }, enabled = busy == null) {
                                    Icon(Icons.AutoMirrored.Rounded.Undo, "撤销设置", Modifier.size(19.dp), tint = scheme.primary)
                                }
                            }
                            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                if (expanded) "收起" else "查看变化", Modifier.size(17.dp), tint = scheme.onSurfaceVariantSummary)
                        }
                        if (expanded) {
                            val before = entry.optJSONObject("before")
                            val after = entry.optJSONObject("after")
                            val keys = ((before?.keys()?.asSequence()?.toList() ?: emptyList()) +
                                (after?.keys()?.asSequence()?.toList() ?: emptyList())).distinct()
                            keys.filter { before?.opt(it)?.toString() != after?.opt(it)?.toString() }.forEach { key ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(fieldLabel(key), style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
                                    Text("${displayValue(before?.opt(key))} → ${displayValue(after?.opt(key))}",
                                        style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary,
                                        maxLines = 5, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun displayValue(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "未设置"
    true -> "开"
    false -> "关"
    else -> value.toString().take(1000)
}
private fun fieldLabel(key: String): String = when (key) {
    "enabled" -> "启用"
    "providerId" -> "提供商"
    "modelId" -> "模型"
    "themeMode" -> "主题"
    "skin" -> "界面"
    "fontFamily" -> "字体"
    "interfaceScale" -> "界面缩放"
    "chatAutoSpeak" -> "自动朗读"
    "recognitionEnabled" -> "语音识别"
    "localTtsVoiceId" -> "本地音色"
    else -> key
}
