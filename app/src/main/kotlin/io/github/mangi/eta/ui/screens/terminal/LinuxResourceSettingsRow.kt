package io.github.mangi.eta.ui.screens.terminal

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.control.NexusSettingsControlPlane
import io.github.mangi.eta.agent.terminal.LinuxExecutionSettings
import io.github.mangi.eta.ui.components.*
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun LinuxResourceSettingsRow(context: Context) {
    val scope = rememberCoroutineScope()
    var limits by remember(context) { mutableStateOf(LinuxExecutionSettings.read(context)) }
    var visible by remember { mutableStateOf(false) }
    var cpu by remember { mutableStateOf("") }
    var memory by remember { mutableStateOf("") }
    var parallel by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).etaGlassSurface()) {
        DetailListRow(title = "资源限制", icon = Icons.Rounded.Tune, tone = EtaIconTone.TOOL,
            value = "${limits.memoryMb} MiB · ${limits.maxShells} 个命令", onClick = {
                cpu = limits.cpuSeconds.toString(); memory = limits.memoryMb.toString(); parallel = limits.maxShells.toString(); error = null; visible = true
            })
    }
    if (visible) WindowDialog(show = true, title = "资源限制", onDismissRequest = { if (!busy) visible = false }) {
        Column(Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(value = cpu, onValueChange = { cpu = it.filter(Char::isDigit).take(4) }, label = "单进程 CPU 时限（秒）",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            TextField(value = memory, onValueChange = { memory = it.filter(Char::isDigit).take(4) }, label = "单进程内存地址上限（MiB）",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            TextField(value = parallel, onValueChange = { parallel = it.filter(Char::isDigit).take(1) }, label = "每次运行的并行命令数",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2) }
            val valid = (cpu.toIntOrNull() ?: 0) in 1..3600 && (memory.toIntOrNull() ?: 0) in 256..8192 && (parallel.toIntOrNull() ?: 0) in 1..8
            MiuixDialogActions(confirmText = "保存", confirmEnabled = valid && !busy, onCancel = { if (!busy) visible = false }, onConfirm = {
                busy = true
                scope.launch {
                    try {
                        limits = withContext(Dispatchers.IO) {
                            NexusSettingsControlPlane.get(context).applyJson("settings.linux.resources", JSONObject().put("cpu_seconds", cpu.toInt())
                                .put("memory_mb", memory.toInt()).put("max_shells", parallel.toInt()), SettingsActorKind.LOCAL_USER, "linux_settings")
                            LinuxExecutionSettings.read(context)
                        }
                        visible = false
                    } catch (failure: Exception) { error = failure.message ?: "资源设置未保存" }
                    finally { busy = false }
                }
            })
        }
    }
}
