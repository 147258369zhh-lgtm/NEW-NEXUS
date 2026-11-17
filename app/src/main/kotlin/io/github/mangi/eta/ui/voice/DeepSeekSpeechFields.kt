package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.ui.components.NexusSettingsArrowPreference as ArrowPreference
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import io.github.mangi.eta.ui.components.NexusSettingsSmallTitle as SmallTitle

@Composable
internal fun DeepSeekSpeechConnectionSection(store: SpeechSettingsStore, synthesis: Boolean) {
    val config = if (synthesis) store.settings.deepSeekTts else store.settings.deepSeekAsr
    Column {
        SmallTitle(stringResource(R.string.speech_group_connection))
        Card(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            SpeechFieldColumn {
                SpeechField(
                    label = stringResource(R.string.speech_deepseek_api_key),
                    value = if (synthesis) store.credentials.deepSeekTts else store.credentials.deepSeekAsr,
                    secret = true,
                    enabled = store.credentialsAvailable && !store.saving,
                    onChange = {
                        store.credential(if (synthesis) SpeechCredentialField.DEEPSEEK_TTS else SpeechCredentialField.DEEPSEEK_ASR, it.trim())
                    },
                )
                SpeechField(
                    label = stringResource(R.string.speech_deepseek_base_url),
                    value = config.baseUrl,
                    enabled = !store.saving,
                    onChange = { value ->
                        store.edit(if (synthesis) store.settings.copy(deepSeekTts = config.copy(baseUrl = value.trim()))
                            else store.settings.copy(deepSeekAsr = config.copy(baseUrl = value.trim())))
                    },
                )
            }
            if (!store.credentialsAvailable) ArrowPreference(
                title = stringResource(R.string.speech_deepseek_retry_credentials),
                summary = stringResource(R.string.speech_deepseek_credentials_preserved),
                enabled = !store.saving,
                onClick = store::retryCredentials,
            )
        }
        SpeechNote(stringResource(R.string.speech_deepseek_connection_note))
    }
}
