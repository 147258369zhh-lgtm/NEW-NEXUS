package io.github.mangi.eta.ui.screens.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.RunInputPhase
import io.github.mangi.eta.agent.runtime.RunInputRequest
import io.github.mangi.eta.agent.runtime.RunUserInput
import io.github.mangi.eta.ui.components.NexusArtifactUriResolver
import io.github.mangi.eta.ui.components.NexusContentPartRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun RunUserInputHost(
    onRecoveredAnswer: suspend (RunInputRequest, String) -> Boolean = { _, _ -> false },
    conversationId: String? = null,
    meetingRoomId: String? = null,
) {
    val context = LocalContext.current
    val pending by RunUserInput.pending.collectAsState()
    val approval by EtaAppControlConfirmation.pending.collectAsState()
    val request = pending.firstOrNull { candidate ->
        if (meetingRoomId != null) candidate.targetId == meetingRoomId && candidate.source in setOf(
            io.github.zhh_lgtm.nexus.kernel.api.RunSource.MEETING_HOST,
            io.github.zhh_lgtm.nexus.kernel.api.RunSource.MEETING_PARTICIPANT)
        else conversationId != null && candidate.conversationId == conversationId && candidate.source !in setOf(
            io.github.zhh_lgtm.nexus.kernel.api.RunSource.MEETING_HOST,
            io.github.zhh_lgtm.nexus.kernel.api.RunSource.MEETING_PARTICIPANT)
    } ?: return
    val scope = rememberCoroutineScope()
    var busy by remember(request.part.requestId) { mutableStateOf(false) }
    var error by remember(request.part.requestId) { mutableStateOf<String?>(null) }
    var dismissed by remember(request.part.requestId) { mutableStateOf(false) }
    fun resolve(answer: String? = null, declined: Boolean = false) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val accepted = if (request.recovered) {
                    val resumed = if (answer == null) true else onRecoveredAnswer(request, answer)
                    resumed && withContext(Dispatchers.IO) {
                        RunUserInput.finishRecovered(
                            context.applicationContext,
                            request.part.requestId,
                            if (declined) RunInputPhase.DECLINED else if (answer == null) RunInputPhase.CANCELLED else RunInputPhase.ANSWERED,
                        )
                    }
                } else withContext(Dispatchers.IO) {
                    if (declined) RunUserInput.decline(request.part.requestId)
                    else if (answer == null) RunUserInput.cancel(request.part.requestId)
                    else RunUserInput.answer(request.part.requestId, answer)
                }
                if (!accepted) error = "请求已结束或回答无效"
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { error = "回答未提交，请核对任务状态"
            } finally { busy = false }
        }
    }
    WindowDialog(show = approval == null && !dismissed, title = "需要你的补充", onDismissRequest = { dismissed = true }) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (request.recovered) {
                Text(
                    text = stringResource(R.string.run_input_recovered_notice),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            key(request.part.requestId) {
                if (request.part.questions.isNotEmpty()) io.github.mangi.eta.ui.components.StructuredUserInputCard(
                    request.part, onSubmit = { resolve(it) }, enabled = !busy)
                else NexusContentPartRenderer(request.part, resolveArtifact = NexusArtifactUriResolver { null },
                    onUserInput = { _, answer -> resolve(answer) }, actionsEnabled = !busy)
            }
            error?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error) }
            TextButton(text = "取消", onClick = { resolve() }, enabled = !busy)
            TextButton(text = "不提供", onClick = { resolve(declined = true) }, enabled = !busy)
        }
    }
}
