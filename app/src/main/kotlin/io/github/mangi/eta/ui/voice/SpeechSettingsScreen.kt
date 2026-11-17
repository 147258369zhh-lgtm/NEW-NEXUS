package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.model.supportsLiveRecognition
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.NexaCompactAction
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import io.github.mangi.eta.ui.components.NexusSettingsSmallTitle as SmallTitle
import io.github.mangi.eta.ui.components.NexusSettingsDropdownPreference as DropdownPreference
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Common controls save independently; credentials and transport details keep their own drafts. */
@Composable
internal fun SpeechSettingsScreen(onBack: () -> Unit, onNavigate: (AppRoute) -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
    val saveMutex = remember { Mutex() }
    var failure by remember { mutableStateOf<String?>(null) }
    val update: ((SpeechSettings) -> SpeechSettings) -> Unit = { transform ->
        scope.launch {
            try {
                saveMutex.withLock { SpeechSettingsRepository.save(transform(SpeechSettingsRepository.settings())) }
                failure = null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failure = "语音设置保存失败，请重试"
            }
        }
    }
    MiuixScaffoldPage(title = stringResource(R.string.speech_settings_title), onBack = onBack) {
        val current = settings
        if (current == null) {
            item(key = "loading") { SpeechLoadingState(failure = null) }
            return@MiuixScaffoldPage
        }
        item(key = "recognition") {
            SmallTitle(stringResource(R.string.speech_recognition_title))
            Card(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                SwitchPreference(title = stringResource(R.string.speech_recognition_enabled),
                    checked = current.recognitionEnabled,
                    onCheckedChange = { value -> update { it.copy(recognitionEnabled = value) } })
                DropdownPreference(title = stringResource(R.string.speech_service_and_model),
                    items = AsrProvider.entries.map { it.label() }, selectedIndex = current.asr.ordinal,
                    onSelectedIndexChange = { index -> AsrProvider.entries.getOrNull(index)?.let { provider ->
                        update { it.copy(asr = provider) }
                    } })
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End) {
                    NexaCompactAction("识别服务配置", onClick = { onNavigate(AppRoute.SpeechRecognition) })
                }
            }
            if (current.asr == AsrProvider.AZURE) SpeechNote(stringResource(R.string.speech_azure_asr_note))
            when (current.asr) {
                AsrProvider.BAIDU -> SpeechNote(stringResource(R.string.speech_baidu_asr_note))
                AsrProvider.IFLYTEK -> SpeechNote(stringResource(R.string.speech_iflytek_asr_note))
                AsrProvider.TENCENT -> SpeechNote(stringResource(R.string.speech_tencent_asr_note))
                AsrProvider.XIAOMI -> SpeechNote(stringResource(R.string.speech_xiaomi_asr_note))
                AsrProvider.DEEPSEEK -> SpeechNote(stringResource(R.string.speech_deepseek_asr_note))
                else -> Unit
            }
        }
        item(key = "input") {
            SmallTitle(stringResource(R.string.speech_group_result))
            Card(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                run {
                    val options = SpeechSettings.AUTO_SEND_SILENCE_OPTIONS
                    DropdownPreference(title = stringResource(R.string.speech_auto_send_title),
                        items = options.map { millis -> if (millis == 0) stringResource(R.string.speech_auto_send_off)
                            else stringResource(R.string.speech_auto_send_seconds, (millis / 1000f).toString().removeSuffix(".0")) },
                        selectedIndex = options.indexOf(current.autoSendSilenceMs).takeIf { it >= 0 }
                            ?: options.indexOf(SpeechSettings.DEFAULT_AUTO_SEND_SILENCE_MS),
                        summary = if (current.asr.supportsLiveRecognition()) null else "当前服务录音结束后识别，需手动完成或松手发送",
                        enabled = current.recognitionEnabled && current.asr.supportsLiveRecognition(),
                        onSelectedIndexChange = { index -> options.getOrNull(index)?.let { value ->
                            update { it.copy(autoSendSilenceMs = value) }
                        } })
                }
                SwitchPreference(title = stringResource(R.string.speech_refine_title),
                    summary = stringResource(R.string.speech_refine_summary), checked = current.refineTranscript,
                    enabled = current.recognitionEnabled,
                    onCheckedChange = { value -> update { it.copy(refineTranscript = value) } })
            }
        }
        item(key = "synthesis") {
            SmallTitle(stringResource(R.string.speech_synthesis_title))
            Card(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                DropdownPreference(title = stringResource(R.string.speech_tts_service),
                    items = TtsProvider.entries.map { it.label() }, selectedIndex = current.tts.ordinal,
                    onSelectedIndexChange = { index -> TtsProvider.entries.getOrNull(index)?.let { provider ->
                        update { it.copy(tts = provider) }
                    } })
                SwitchPreference(title = "聊天回答自动朗读", checked = current.chatAutoSpeak,
                    enabled = current.tts != TtsProvider.NONE,
                    onCheckedChange = { value -> update { it.copy(chatAutoSpeak = value) } })
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End) {
                    NexaCompactAction("音色与播报配置", onClick = { onNavigate(AppRoute.SpeechSynthesis) })
                }
            }
        }
        item(key = "conversation") {
            SmallTitle(stringResource(R.string.speech_group_assistant))
            Card(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                SwitchPreference(title = stringResource(R.string.speech_auto_speak),
                    checked = current.autoSpeak,
                    enabled = current.tts != TtsProvider.NONE,
                    onCheckedChange = { value -> update { it.copy(autoSpeak = value) } })
                SwitchPreference(title = stringResource(R.string.speech_continuous_conversation_title),
                    checked = current.continuousConversation, enabled = current.recognitionEnabled,
                    onCheckedChange = { value -> update { it.copy(continuousConversation = value) } })
                SwitchPreference(title = "双工对话", checked = current.duplexConversation,
                    summary = if (current.asr.supportsLiveRecognition()) null else "当前服务录音结束后识别，不支持实时双工",
                    enabled = current.recognitionEnabled && current.asr.supportsLiveRecognition(),
                    onCheckedChange = { value -> update { it.copy(duplexConversation = value) } })
            }
        }
        failure?.let { message -> item(key = "failure") { SpeechNote(message) } }
    }
}
