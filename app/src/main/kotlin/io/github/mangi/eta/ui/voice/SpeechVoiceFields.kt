package io.github.mangi.eta.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.ui.components.StatusError
import top.yukonga.miuix.kmp.basic.Text
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import io.github.mangi.eta.ui.components.NexusSettingsSmallTitle as SmallTitle
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference

@Composable
internal fun rememberSpeechVoiceContent(
    store: SpeechSettingsStore,
    onOpenLocalModels: () -> Unit,
): @Composable () -> Unit {
    return when (store.settings.tts) {
        TtsProvider.NONE -> { {} }
        TtsProvider.LOCAL -> rememberLocalSpeechVoiceContent(store, onOpenLocalModels)
        else -> rememberCloudSpeechVoiceContent(store)
    }
}

/** 云端播报保留自定义音色 ID；本地播报使用模型目录，避免发送不存在的声线。 */
@Composable
private fun rememberCloudSpeechVoiceContent(store: SpeechSettingsStore): @Composable () -> Unit {
    val settings = store.settings
    val voice = when (settings.tts) {
        TtsProvider.QWEN -> settings.qwenVoice
        TtsProvider.DOUBAO -> settings.doubaoVoice
        TtsProvider.GEMINI -> settings.geminiTts.voice
        TtsProvider.MINIMAX -> settings.minimaxTts.voice
        TtsProvider.AZURE -> settings.azureTts.voice
        TtsProvider.BAIDU -> settings.baiduTts.voice.toString()
        TtsProvider.IFLYTEK -> settings.iflytekTts.voice
        TtsProvider.TENCENT -> settings.tencentTts.voice.toString()
        TtsProvider.XIAOMI -> settings.xiaomiTts.voice
        TtsProvider.DEEPSEEK -> settings.deepSeekTts.voice
        TtsProvider.LOCAL, TtsProvider.NONE -> return {}
    }
    val presets = SpeechVoicePresets.forProvider(settings.tts)
    val presetIndex = presets.indexOfFirst { it.id == voice }
    var editingCustom by rememberSaveable(settings.tts) { mutableStateOf(false) }
    var numericDraft by rememberSaveable(settings.tts) { mutableStateOf<String?>(null) }
    val numericVoice = settings.tts == TtsProvider.BAIDU || settings.tts == TtsProvider.TENCENT
    val displayedVoice = if (numericVoice) numericDraft ?: voice else voice
    val numericValue = displayedVoice.trim().toIntOrNull()
    val numericError = numericVoice && (numericValue == null || numericValue < 0 ||
        (settings.tts == TtsProvider.TENCENT && numericValue == 0))
    val custom = editingCustom || presetIndex < 0
    val allowCustom = settings.tts !in setOf(TtsProvider.XIAOMI, TtsProvider.DEEPSEEK)
    val change: (String) -> Unit = {
        store.edit(
            when (settings.tts) {
                TtsProvider.QWEN -> settings.copy(qwenVoice = it)
                TtsProvider.DOUBAO -> settings.copy(doubaoVoice = it)
                TtsProvider.GEMINI -> settings.copy(geminiTts = settings.geminiTts.copy(voice = it))
                TtsProvider.MINIMAX -> settings.copy(minimaxTts = settings.minimaxTts.copy(voice = it))
                TtsProvider.AZURE -> settings.copy(azureTts = settings.azureTts.copy(voice = it))
                TtsProvider.BAIDU -> settings.copy(baiduTts = settings.baiduTts.copy(voice = it.toIntOrNull() ?: -1))
                TtsProvider.IFLYTEK -> settings.copy(iflytekTts = settings.iflytekTts.copy(voice = it))
                TtsProvider.TENCENT -> settings.copy(tencentTts = settings.tencentTts.copy(voice = it.toIntOrNull() ?: -1))
                TtsProvider.XIAOMI -> settings.copy(xiaomiTts = settings.xiaomiTts.copy(voice = it))
                TtsProvider.DEEPSEEK -> settings.copy(deepSeekTts = settings.deepSeekTts.copy(voice = it))
                TtsProvider.LOCAL, TtsProvider.NONE -> settings
            },
        )
    }

    return {
    Column {
        SmallTitle(stringResource(R.string.speech_group_voice))
        Card(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        ) {
            OverlaySpinnerPreference(
                title = stringResource(R.string.speech_voice),
                items = presets.map { preset ->
                    DropdownItem(text = stringResource(preset.name), summary = stringResource(preset.summary))
                } + if (allowCustom) listOf(DropdownItem(text = stringResource(R.string.speech_voice_custom)))
                else if (presetIndex < 0) listOf(DropdownItem(text = voice,
                    summary = stringResource(if (settings.tts == TtsProvider.DEEPSEEK) R.string.speech_deepseek_voice_invalid
                        else R.string.speech_xiaomi_voice_invalid))) else emptyList(),
                selectedIndex = if (custom) presets.size else presetIndex,
                onSelectedIndexChange = { index ->
                    val preset = presets.getOrNull(index)
                    editingCustom = preset == null
                    if (preset != null) {
                        numericDraft = null
                        change(preset.id)
                    } else if (!custom) {
                        if (numericVoice) numericDraft = ""
                        change("")
                    }
                },
            )
            AnimatedVisibility(visible = custom && allowCustom) {
                SpeechFieldColumn {
                    SpeechField(
                        label = stringResource(R.string.speech_voice_id),
                        value = displayedVoice,
                        onChange = { value ->
                            if (numericVoice) numericDraft = value
                            change(value.trim())
                        },
                    )
                    if (numericError) Text(stringResource(R.string.speech_domestic_numeric_voice_error), color = StatusError)
                }
            }
            SpeechDeliveryFields(store)
        }
        SpeechNote(
            stringResource(
                when (settings.tts) {
                    TtsProvider.QWEN -> R.string.speech_qwen_voice_note
                    TtsProvider.DOUBAO -> R.string.speech_doubao_voice_note
                    TtsProvider.GEMINI -> R.string.speech_gemini_voice_note
                    TtsProvider.MINIMAX -> R.string.speech_minimax_voice_note
                    TtsProvider.AZURE -> R.string.speech_azure_voice_note
                    TtsProvider.BAIDU -> R.string.speech_baidu_voice_note
                    TtsProvider.IFLYTEK -> R.string.speech_iflytek_voice_note
                    TtsProvider.TENCENT -> R.string.speech_tencent_voice_note
                    TtsProvider.XIAOMI -> R.string.speech_xiaomi_voice_note
                    TtsProvider.DEEPSEEK -> R.string.speech_deepseek_voice_note
                    TtsProvider.LOCAL, TtsProvider.NONE -> error("Not a cloud synthesis provider")
                },
            ),
        )
    }
    }
}
