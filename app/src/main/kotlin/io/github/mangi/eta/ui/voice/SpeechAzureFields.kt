package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AzureSpeechConfig
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import io.github.mangi.eta.ui.components.NexusSettingsSmallTitle as SmallTitle

@Composable
internal fun AzureConnectionSection(store: SpeechSettingsStore, synthesis: Boolean) {
    val config = if (synthesis) store.settings.azureTts else store.settings.azureAsr
    val key = if (synthesis) store.credentials.azureTts else store.credentials.azureAsr
    val change: (AzureSpeechConfig) -> Unit = {
        store.edit(if (synthesis) store.settings.copy(azureTts = it) else store.settings.copy(azureAsr = it))
    }
    Column {
        SmallTitle(stringResource(R.string.speech_group_connection))
        Card(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            SpeechFieldColumn {
                SpeechField(stringResource(R.string.speech_azure_key), key, secret = true,
                    onChange = { store.credential(if (synthesis) SpeechCredentialField.AZURE_TTS else SpeechCredentialField.AZURE_ASR, it.trim()) })
                SpeechField(stringResource(R.string.speech_azure_region), config.region,
                    onChange = { change(config.copy(region = it.trim().lowercase())) })
                SpeechField(stringResource(R.string.speech_azure_locale), config.locale,
                    onChange = { change(config.copy(locale = it.trim())) })
            }
        }
        SpeechNote(stringResource(if (synthesis) R.string.speech_azure_tts_note else R.string.speech_azure_asr_note))
    }
}
