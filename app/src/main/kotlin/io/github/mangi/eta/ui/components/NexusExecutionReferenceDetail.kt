package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.repository.EffectReviewDecision
import io.github.mangi.eta.data.repository.NexusExecutionDetail
import io.github.mangi.eta.data.repository.NexusExecutionDetails
import io.github.zhh_lgtm.nexus.kernel.api.RunState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.DateFormat
import java.util.Date

@Composable
internal fun NexusExecutionReferenceDetail(itemId: String, modifier: Modifier = Modifier, refreshKey: Any? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember(itemId) { mutableStateOf(false) }
    var revision by remember(itemId) { mutableIntStateOf(0) }
    var reviewBusy by remember(itemId) { mutableStateOf(false) }
    var reviewError by remember(itemId) { mutableStateOf<String?>(null) }
    val detail by produceState<NexusExecutionDetail?>(null, itemId, refreshKey, revision) {
        loaded = false
        value = try { NexusExecutionDetails(context).read(itemId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        loaded = true
    }
    fun resolve(decision: EffectReviewDecision) {
        if (reviewBusy) return
        scope.launch {
            reviewBusy = true
            reviewError = null
            try {
                if (NexusExecutionDetails(context).resolveEffectReview(itemId, decision)) {
                    revision++
                } else {
                    reviewError = "此记录已不可核对"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reviewError = "核对记录未保存，请稍后重试"
            } finally {
                reviewBusy = false
            }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val record = detail
        if (record == null) {
            Text(if (loaded) "此运行记录暂不可读取" else "读取运行记录…", style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        } else {
            val state = when (record.state) {
                RunState.SUCCEEDED -> "已完成"
                RunState.FAILED -> "失败"
                RunState.CANCELLED -> "已取消"
                RunState.INTERRUPTED -> "已中断"
                RunState.WAITING_AUTH -> "等待确认"
                RunState.WAITING_USER -> "等待补充"
                RunState.WAITING_EVENT -> "等待事件"
                RunState.PAUSED -> "已暂停"
                RunState.VERIFYING -> "待核对"
                RunState.RUNNING, RunState.ADMITTED, RunState.CREATED -> "进行中"
                else -> "结果未确认"
            }
            val time = remember(record.startedAt) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.startedAt)) }
            Text("$state · $time${record.completedAt?.let { " · ${((it - record.startedAt).coerceAtLeast(0)) / 1000.0} 秒" }.orEmpty()}",
                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (record.revisionKind == "plan") {
                Text("执行计划${record.revision?.let { " · r$it" }.orEmpty()}", style = MiuixTheme.textStyles.body2)
                record.steps.forEachIndexed { index, step ->
                    Column(Modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${index + 1}. ${step.title}", style = MiuixTheme.textStyles.body2)
                        step.description.takeIf(String::isNotBlank)?.let { Text(it, style = MiuixTheme.textStyles.footnote1) }
                        step.verification.takeIf(String::isNotBlank)?.let { Text("验证：$it", style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    }
                }
            }
            record.text?.takeIf(String::isNotBlank)?.let { SelectionContainer { Text(it, style = MiuixTheme.textStyles.body2) } }
            record.answer?.let { Text("回答：$it", style = MiuixTheme.textStyles.body2) }
            if (record.contentUnavailable) Text("版本正文暂不可读取", style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (record.effectReview == null) {
                record.errorCode?.let { Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error) }
            }
            record.effectReview?.let { review ->
                if (review.pending) {
                    Text("副作用待核对（${review.effectClass.reviewLabel()}）：外部操作可能已经发生，"
                        + "本次结果无法确认。禁止自动重放，请先在本机核对实际状态。",
                        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton("已核对", onClick = { resolve(EffectReviewDecision.ACKNOWLEDGED) },
                            enabled = !reviewBusy, colors = ButtonDefaults.textButtonColorsPrimary(), minHeight = 34.dp,
                            textStyle = MiuixTheme.textStyles.footnote2)
                        TextButton("重新发起", onClick = { resolve(EffectReviewDecision.RERUN_REQUESTED) },
                            enabled = !reviewBusy, colors = ButtonDefaults.textButtonColorsPrimary(), minHeight = 34.dp,
                            textStyle = MiuixTheme.textStyles.footnote2)
                        TextButton("取消", onClick = { resolve(EffectReviewDecision.CANCELLED) },
                            enabled = !reviewBusy, colors = ButtonDefaults.textButtonColorsPrimary(), minHeight = 34.dp,
                            textStyle = MiuixTheme.textStyles.footnote2)
                    }
                } else {
                    Text("核对已记录：${review.decision?.reviewLabel()}。未自动执行或重放原操作。",
                        style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                reviewError?.let { Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error) }
            }
        }
    }
}

private fun io.github.zhh_lgtm.nexus.kernel.api.EffectClass.reviewLabel(): String = when (this) {
    io.github.zhh_lgtm.nexus.kernel.api.EffectClass.READ -> "只读操作"
    io.github.zhh_lgtm.nexus.kernel.api.EffectClass.REVERSIBLE_WRITE -> "写入操作"
    io.github.zhh_lgtm.nexus.kernel.api.EffectClass.IDEMPOTENT_WRITE -> "幂等写入"
    io.github.zhh_lgtm.nexus.kernel.api.EffectClass.NON_IDEMPOTENT_WRITE -> "不可重复写入"
    io.github.zhh_lgtm.nexus.kernel.api.EffectClass.DESTRUCTIVE -> "破坏性操作"
    io.github.zhh_lgtm.nexus.kernel.api.EffectClass.EXTERNAL_SIDE_EFFECT -> "外部副作用"
    io.github.zhh_lgtm.nexus.kernel.api.EffectClass.GUI_ACTION -> "界面操作"
    io.github.zhh_lgtm.nexus.kernel.api.EffectClass.HIGH_RISK -> "高风险操作"
}

private fun EffectReviewDecision.reviewLabel(): String = when (this) {
    EffectReviewDecision.ACKNOWLEDGED -> "已核对，保留未知结果记录"
    EffectReviewDecision.RERUN_REQUESTED -> "已记录重新发起请求，请回到会话重新发送"
    EffectReviewDecision.CANCELLED -> "已取消未确认操作"
}
