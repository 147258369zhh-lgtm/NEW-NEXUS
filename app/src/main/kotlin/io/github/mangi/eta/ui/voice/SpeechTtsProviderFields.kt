package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.GeminiTtsModel
import io.github.mangi.eta.data.model.MiniMaxTtsModel
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import io.github.mangi.eta.ui.components.NexusSettingsSmallTitle as SmallTitle
import io.github.mangi.eta.ui.components.NexusSettingsDropdownPreference as OverlayDropdownPreference

@Composable
internal fun GeminiConnectionSection(store: SpeechSettingsStore) {
    val config = store.settings.geminiTts
    SpeechApiKeyConnectionSection(
        baseUrl = config.baseUrl,
        apiKey = store.credentials.geminiTts,
        onBaseUrl = { store.edit(store.settings.copy(geminiTts = config.copy(baseUrl = it))) },
        onApiKey = { store.credential(SpeechCredentialField.GEMINI_TTS, it) },
        note = stringResource(R.string.speech_gemini_connection_note),
    ) {
        OverlayDropdownPreference(
            title = stringResource(R.string.speech_tts_model),
            items = listOf(
                stringResource(R.string.speech_gemini_flash),
                stringResource(R.string.speech_gemini_flash_lite),
            ),
            selectedIndex = config.model.ordinal,
            onSelectedIndexChange = { index ->
                GeminiTtsModel.entries.getOrNull(index)?.let {
                    store.edit(store.settings.copy(geminiTts = config.copy(model = it)))
                }
            },
        )
    }
}

@Composable
internal fun MiniMaxConnectionSection(store: SpeechSettingsStore) {
    val config = store.settings.minimaxTts
    SpeechApiKeyConnectionSection(
        baseUrl = config.baseUrl,
        apiKey = store.credentials.minimaxTts,
        onBaseUrl = { store.edit(store.settings.copy(minimaxTts = config.copy(baseUrl = it))) },
        onApiKey = { store.credential(SpeechCredentialField.MINIMAX_TTS, it) },
        note = stringResource(R.string.speech_minimax_connection_note),
    ) {
        OverlayDropdownPreference(
            title = stringResource(R.string.speech_tts_model),
            items = listOf(
                stringResource(R.string.speech_minimax_turbo),
                stringResource(R.string.speech_minimax_hd),
            ),
            selectedIndex = config.model.ordinal,
            onSelectedIndexChange = { index ->
                MiniMaxTtsModel.entries.getOrNull(index)?.let {
                    store.edit(store.settings.copy(minimaxTts = config.copy(model = it)))
                }
            },
        )
    }
}

@Composable
private fun SpeechApiKeyConnectionSection(
    baseUrl: String,
    apiKey: String,
    onBaseUrl: (String) -> Unit,
    onApiKey: (String) -> Unit,
    note: String,
    model: @Composable () -> Unit,
) {
    Column {
        SmallTitle(stringResource(R.string.speech_group_connection))
        Card(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        ) {
            model()
            SpeechFieldColumn {
                SpeechField("API Key", apiKey, secret = true, onChange = { onApiKey(it.trim()) })
                SpeechField(stringResource(R.string.speech_base_url), baseUrl, onChange = { onBaseUrl(it.trim()) })
            }
        }
        SpeechNote(note)
    }
}

/** 只呈现该服务实际支持的发声参数。 */
@Composable
internal fun SpeechDeliveryFields(store: SpeechSettingsStore) {
    when (store.settings.tts) {
        TtsProvider.GEMINI -> {
            val config = store.settings.geminiTts
            SpeechFieldColumn {
                SpeechField(
                    label = stringResource(R.string.speech_gemini_style),
                    value = config.style,
                    singleLine = false,
                    onChange = { store.edit(store.settings.copy(geminiTts = config.copy(style = it))) },
                )
            }
        }
        TtsProvider.MINIMAX -> {
            val config = store.settings.minimaxTts
            val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                .plus(config.speed).distinct().sorted()
            OverlayDropdownPreference(
                title = stringResource(R.string.speech_speed),
                items = speeds.map { "$it×" },
                selectedIndex = speeds.indexOf(config.speed),
                onSelectedIndexChange = { index ->
                    speeds.getOrNull(index)?.let {
                        store.edit(store.settings.copy(minimaxTts = config.copy(speed = it)))
                    }
                },
            )
        }
        TtsProvider.BAIDU -> {
            val config = store.settings.baiduTts
            DomesticIntDeliveryField(stringResource(R.string.speech_speed), config.speed, 0..15) {
                store.edit(store.settings.copy(baiduTts = config.copy(speed = it)))
            }
            DomesticIntDeliveryField(stringResource(R.string.speech_domestic_pitch), config.pitch, 0..15) {
                store.edit(store.settings.copy(baiduTts = config.copy(pitch = it)))
            }
            DomesticIntDeliveryField(stringResource(R.string.speech_domestic_volume), config.volume, 0..9) {
                store.edit(store.settings.copy(baiduTts = config.copy(volume = it)))
            }
        }
        TtsProvider.IFLYTEK -> {
            val config = store.settings.iflytekTts
            val speeds = (0..100 step 10).toList().plus(config.speed).distinct().sorted()
            DomesticNumberDeliveryField(stringResource(R.string.speech_speed), speeds.map(Int::toString),
                speeds.indexOf(config.speed)) { index -> speeds.getOrNull(index)?.let {
                store.edit(store.settings.copy(iflytekTts = config.copy(speed = it)))
            } }
        }
        TtsProvider.TENCENT -> {
            val config = store.settings.tencentTts
            val speeds = listOf(-2f, -1f, 0f, 1f, 2f, 3f, 4f, 5f, 6f).plus(config.speed).distinct().sorted()
            DomesticNumberDeliveryField(stringResource(R.string.speech_speed), speeds.map(Float::toString),
                speeds.indexOf(config.speed)) { index -> speeds.getOrNull(index)?.let {
                store.edit(store.settings.copy(tencentTts = config.copy(speed = it)))
            } }
            val volumes = (-10..10).map(Int::toFloat).plus(config.volume).distinct().sorted()
            DomesticNumberDeliveryField(stringResource(R.string.speech_domestic_volume), volumes.map(Float::toString),
                volumes.indexOf(config.volume)) { index -> volumes.getOrNull(index)?.let {
                store.edit(store.settings.copy(tencentTts = config.copy(volume = it)))
            } }
        }
        TtsProvider.NONE, TtsProvider.QWEN, TtsProvider.DOUBAO, TtsProvider.LOCAL, TtsProvider.AZURE, TtsProvider.XIAOMI, TtsProvider.DEEPSEEK -> Unit
    }
}

@Composable
private fun DomesticIntDeliveryField(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val values = range.toList().plus(value).distinct().sorted()
    DomesticNumberDeliveryField(title, values.map(Int::toString), values.indexOf(value)) { index ->
        values.getOrNull(index)?.let(onChange)
    }
}

@Composable
private fun DomesticNumberDeliveryField(title: String, values: List<String>, selected: Int, onChange: (Int) -> Unit) {
    OverlayDropdownPreference(title = title, items = values, selectedIndex = selected, onSelectedIndexChange = onChange)
}
