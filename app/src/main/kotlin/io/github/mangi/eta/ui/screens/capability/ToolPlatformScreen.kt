package io.github.mangi.eta.ui.screens.capability

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.capability.*
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.NexusSettingsGroup
import io.github.mangi.eta.ui.components.NexusSettingsTabs
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ToolPlatformScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<ToolPlatformSnapshot?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        try { snapshot = ToolPlatformService(context).snapshot() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val loaded = snapshot
    val tools = remember(loaded, query) {
        loaded?.tools.orEmpty().filter { query.isBlank() || it.descriptor.let { d ->
            "${d.id} ${d.shortDescription}".contains(query.trim(), ignoreCase = true) } }
    }
    val packages = remember(loaded, query) { loaded?.linuxPackages.orEmpty().filter {
        query.isBlank() || "${it.title} ${it.id} ${it.commands.joinToString(" ")}".contains(query.trim(), ignoreCase = true)
    } }
    MiuixScaffoldPage(title = "模型工具平台", onBack = onBack, aboveContent = {
        NexusSettingsTabs(listOf("内置工具", "Linux 能力"), tab, { tab = it; expanded = null })
    }) {
        item("search") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = query, onValueChange = { query = it.take(100) }, label = "搜索工具或用途",
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(if (tab == 0) "按需发现与激活 · 调用时复核当前会话权限" else
                    "通过终端调用 · environment=linux · 安装状态不等同运行健康检查",
                    style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (loaded == null) {
            item("loading") { NexusSettingsGroup { Text(if (failed) "读取失败，请返回后重试" else "正在读取本机能力",
                Modifier.padding(16.dp)) } }
        } else if (tab == 0) {
            items(tools, key = { it.descriptor.id }) { row ->
                val d = row.descriptor
                NexusSettingsGroup {
                    Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == d.id) null else d.id }.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(d.shortDescription.substringBefore('。').take(48), style = MiuixTheme.textStyles.body2,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${d.id} · ${platformCondition(row.unavailable)}", style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (expanded == d.id) SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(d.modelDescription, style = MiuixTheme.textStyles.footnote1)
                            Text("效果：${platformEffect(d.effectClass)} · 恢复：${platformReplay(d.replayPolicy.name)}",
                                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        } }
                    }
                }
            }
            if (tools.isEmpty()) item("empty-tools") { Text("没有匹配工具", Modifier.padding(16.dp)) }
        } else {
            item("linux-context") { NexusSettingsGroup { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(loaded.linuxName, style = MiuixTheme.textStyles.body2)
                Text(if (loaded.linuxReady) "基础系统已安装" else "基础系统未安装", style = MiuixTheme.textStyles.footnote1)
                Text("terminal · terminal_job · terminal_daemon", style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            } } }
            items(packages, key = { it.id }) { row -> NexusSettingsGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${row.title} · ${if (row.installed) "已安装" else "待安装"}", style = MiuixTheme.textStyles.body2)
                    if (row.commands.isNotEmpty()) Text(row.commands.joinToString(" · "), style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            } }
            if (packages.isEmpty()) item("empty-linux") { Text("没有匹配能力包", Modifier.padding(16.dp)) }
        }
    }
}

private fun platformReplay(value: String): String = when (value) {
    "AUTOMATIC" -> "可重新读取"
    "VERIFY_THEN_RESUME" -> "核对结果后续接"
    "REQUIRE_USER" -> "需要用户确认"
    "NEVER" -> "不自动重放"
    else -> "按运行状态判断"
}

private fun platformCondition(code: String?): String = when (code) {
    null -> "系统条件具备，运行时复核"
    "ROOT_REQUIRED" -> "需要 Root"
    "LSPOSED_REQUIRED" -> "需要系统框架"
    "ACCESSIBILITY_UNAVAILABLE" -> "需要无障碍服务"
    "DEVICE_UNSUPPORTED" -> "设备不支持"
    "CALENDAR_PERMISSION_REQUIRED" -> "需要日历权限"
    "NOTIFICATION_ACCESS_REQUIRED" -> "需要通知权限"
    "APP_USAGE_ACCESS_REQUIRED" -> "需要使用情况权限"
    "LOCATION_PERMISSION_REQUIRED" -> "需要位置权限"
    else -> "运行时检查配置与权限"
}

private fun platformEffect(effect: EffectClass): String = when (effect) {
    EffectClass.READ -> "读取"
    EffectClass.REVERSIBLE_WRITE -> "可撤销写入"
    EffectClass.IDEMPOTENT_WRITE -> "幂等写入"
    EffectClass.NON_IDEMPOTENT_WRITE -> "不可重复提交"
    EffectClass.EXTERNAL_SIDE_EFFECT -> "外部操作"
    EffectClass.GUI_ACTION -> "界面操作"
    EffectClass.DESTRUCTIVE -> "破坏性操作"
    EffectClass.HIGH_RISK -> "需核对授权"
}
