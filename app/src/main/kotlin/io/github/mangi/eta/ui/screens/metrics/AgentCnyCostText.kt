package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.NexusSettingsArrowPreference as ArrowPreference
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.math.BigDecimal
import java.math.RoundingMode

internal fun cnyAmountText(amount: BigDecimal): String = when {
    amount.signum() > 0 && amount < BigDecimal("0.01") -> "＜¥0.01"
    else -> "¥" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
}

@Composable
internal fun CnyMetricCostText(models: List<AgentMetricModelEntity>): String? {
    val context = LocalContext.current.applicationContext
    val revision by AgentCnyExchangeStore.revision.collectAsState()
    var estimate by remember { mutableStateOf<Pair<List<AgentMetricModelEntity>, AgentCnyEstimate>?>(null) }
    LaunchedEffect(context) { AgentCnyExchangeStore.refresh(context) }
    LaunchedEffect(models, revision) {
        estimate = models to withContext(Dispatchers.IO) {
            runCatching {
                models.groupBy { it.recordId }.forEach { (id, scoped) ->
                    AgentCnyExchangeStore.capture(context, id, scoped.mapNotNull { it.price?.currency })
                }
                AgentCnyExchangeStore.estimate(context, models)
            }.getOrElse {
                AgentCnyEstimate(null, false, 0, models.size, emptyList())
            }
        }
    }
    // Never retain another selected subject's money while its conversion is loading.
    val loaded = estimate ?: return null
    if (loaded.first != models || models.isEmpty()) return null
    val result = loaded.second
    return result.amount?.let { cnyAmountText(it) + if (result.complete) "" else " · 部分" }
}

@Composable
internal fun AgentCnyRateControls() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val revision by AgentCnyExchangeStore.revision.collectAsState()
    val rate = remember(revision) { AgentCnyExchangeStore.current(context, "USD") }
    var automatic by remember(revision) { mutableStateOf(AgentCnyExchangeStore.automatic(context)) }
    var edit by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var editCurrency by remember { mutableStateOf("USD") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    SwitchPreference(title = "自动更新人民币参考汇率", summary = "每天按需检查，失败保留缓存；不改历史已冻结换算",
        checked = automatic, onCheckedChange = { value -> scope.launch {
            try { AgentCnyExchangeStore.setAutomatic(context, value); automatic = value }
            catch (_: Exception) { status = "汇率设置未保存，请重试" }
        } })
    ArrowPreference(title = "美元兑人民币", summary = rate?.let {
        "1 USD = ${it.multiplier.setScale(4, RoundingMode.HALF_UP)} CNY · ${it.date} · ${it.source}"
    } ?: "未设置参考汇率", onClick = {
        editCurrency = "USD"
        draft = rate?.multiplier?.setScale(6, RoundingMode.HALF_UP)?.toPlainString().orEmpty(); error = null; edit = true
    })
    ArrowPreference(title = "其他币种兑人民币", summary = "自动参考不包含的币种，可手动设置换算汇率", onClick = {
        editCurrency = "EUR"; draft = ""; error = null; edit = true
    })
    ArrowPreference(title = "更新参考汇率", summary = status ?: "参考数据：Frankfurter；费用统一换算为人民币估算",
        enabled = !busy, onClick = { scope.launch {
            busy = true
            try { status = AgentCnyExchangeStore.refresh(context, force = true) } finally { busy = false }
        } })
    WindowDialog(show = edit, title = "人民币换算汇率", summary = "输入1单位外币对应的人民币金额；仅影响尚未冻结的换算",
        onDismissRequest = { if (!busy) edit = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(value = editCurrency, onValueChange = { editCurrency = it.uppercase(java.util.Locale.ROOT).filter(Char::isLetter).take(3) },
                singleLine = true, label = "外币代码，例如 USD、EUR")
            TextField(value = draft, onValueChange = { draft = it.take(32) }, singleLine = true,
                label = "1 $editCurrency = 多少 CNY", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            error?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error) }
            TextButton("恢复自动参考", enabled = !busy, onClick = { scope.launch {
                busy = true
                try { AgentCnyExchangeStore.setManual(context, editCurrency, null); edit = false }
                catch (_: Exception) { error = "汇率未保存，请重试" } finally { busy = false }
            } })
            MiuixDialogActions(confirmText = "保存", cancelText = "取消", confirmEnabled = !busy, cancelEnabled = !busy,
                onCancel = { edit = false }, onConfirm = { scope.launch {
                    busy = true
                    try { AgentCnyExchangeStore.setManual(context, editCurrency, draft); edit = false }
                    catch (_: Exception) { error = "请输入有效的正数汇率" } finally { busy = false }
                } })
        }
    }
}
