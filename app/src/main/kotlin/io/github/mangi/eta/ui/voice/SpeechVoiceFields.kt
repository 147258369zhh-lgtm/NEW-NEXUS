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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference

@Composable
internal fun SpeechVoiceSection(
    store: SpeechSettingsStore,
    onOpenLocalModels: () -> Unit,
) {
    when (store.settings.tts) {
        TtsProvider.NONE -> Unit
        TtsProvider.LOCAL -> LocalSpeechVoiceSection(store, onOpenLocalModels)
        else -> CloudSpeechVoiceSection(store)
    }
}

/** 云端播报保留自定义音色 ID；本地播报使用模型目录，避免发送不存在的声线。 */
@Composable
private fun CloudSpeechVoiceSection(store: SpeechSettingsStore) {
    val settings = store.settings
    val voice = when (settings.tts) {
        TtsProvider.QWEN -> settings.qwenVoice
        TtsProvider.DOUBAO -> settings.doubaoVoice
        TtsProvider.GEMINI -> settings.geminiTts.voice
        TtsProvider.MINIMAX -> settings.minimaxTts.voice
        TtsProvider.LOCAL, TtsProvider.NONE -> return
    }
    val presets = SpeechVoicePresets.forProvider(settings.tts)
    val presetIndex = presets.indexOfFirst { it.id == voice }
    var editingCustom by rememberSaveable(settings.tts) { mutableStateOf(false) }
    val custom = editingCustom || presetIndex < 0
    val change: (String) -> Unit = {
        store.edit(
            when (settings.tts) {
                TtsProvider.QWEN -> settings.copy(qwenVoice = it)
                TtsProvider.DOUBAO -> settings.copy(doubaoVoice = it)
                TtsProvider.GEMINI -> settings.copy(geminiTts = settings.geminiTts.copy(voice = it))
                TtsProvider.MINIMAX -> settings.copy(minimaxTts = settings.minimaxTts.copy(voice = it))
                TtsProvider.LOCAL, TtsProvider.NONE -> settings
            },
        )
    }

    Column {
        SmallTitle(stringResource(R.string.speech_group_voice))
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            OverlaySpinnerPreference(
                title = stringResource(R.string.speech_voice),
                items = presets.map { preset ->
                    DropdownItem(text = stringResource(preset.name), summary = stringResource(preset.summary))
                } + DropdownItem(text = stringResource(R.string.speech_voice_custom)),
                selectedIndex = if (custom) presets.size else presetIndex,
                onSelectedIndexChange = { index ->
                    val preset = presets.getOrNull(index)
                    editingCustom = preset == null
                    if (preset != null) change(preset.id) else if (!custom) change("")
                },
            )
            AnimatedVisibility(visible = custom) {
                SpeechFieldColumn {
                    SpeechField(
                        label = stringResource(R.string.speech_voice_id),
                        value = voice,
                        onChange = { change(it.trim()) },
                    )
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
                    TtsProvider.LOCAL, TtsProvider.NONE -> error("Not a cloud synthesis provider")
                },
            ),
        )
    }
}
