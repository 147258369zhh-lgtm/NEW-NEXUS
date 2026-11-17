package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import io.github.mangi.eta.ui.components.NexusSettingsSmallTitle as SmallTitle
import io.github.mangi.eta.ui.components.NexusSettingsDropdownPreference as DropdownPreference

@Composable
internal fun XiaomiMiMoConnectionSection(store: SpeechSettingsStore, synthesis: Boolean) {
    Column {
        SmallTitle(stringResource(R.string.speech_group_connection))
        Card(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            SpeechFieldColumn {
                SpeechField("API Key", if (synthesis) store.credentials.xiaomiTts else store.credentials.xiaomiAsr,
                    secret = true, onChange = { store.credential(if (synthesis) SpeechCredentialField.XIAOMI_TTS
                        else SpeechCredentialField.XIAOMI_ASR, it.trim()) })
            }
            if (!synthesis) {
                val config = store.settings.xiaomiAsr
                val languages = listOf("auto", "zh", "en")
                DropdownPreference(
                    title = stringResource(R.string.speech_xiaomi_language),
                    items = listOf(stringResource(R.string.speech_xiaomi_language_auto),
                        stringResource(R.string.speech_xiaomi_language_zh), stringResource(R.string.speech_xiaomi_language_en)),
                    selectedIndex = languages.indexOf(config.language).coerceAtLeast(0),
                    onSelectedIndexChange = { index -> languages.getOrNull(index)?.let {
                        store.edit(store.settings.copy(xiaomiAsr = config.copy(language = it)))
                    } },
                )
            }
        }
        SpeechNote(stringResource(R.string.speech_xiaomi_connection_note))
    }
}

@Composable
internal fun BaiduConnectionSection(store: SpeechSettingsStore, synthesis: Boolean) {
    val config = if (synthesis) store.settings.baiduTts else store.settings.baiduAsr
    Column {
        SmallTitle(stringResource(R.string.speech_group_connection))
        Card(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            if (!synthesis) {
                val models = listOf(1537, 80001).plus(config.devPid).distinct()
                DropdownPreference(
                    title = stringResource(R.string.speech_baidu_model),
                    items = models.map { when (it) {
                        1537 -> stringResource(R.string.speech_baidu_model_standard)
                        80001 -> stringResource(R.string.speech_baidu_model_pro)
                        else -> it.toString()
                    } }, selectedIndex = models.indexOf(config.devPid),
                    onSelectedIndexChange = { index -> models.getOrNull(index)?.let {
                        store.edit(store.settings.copy(baiduAsr = config.copy(devPid = it)))
                    } },
                )
            }
            SpeechFieldColumn {
                SpeechField("API Key", if (synthesis) store.credentials.baiduTtsApiKey else store.credentials.baiduAsrApiKey,
                    secret = true, onChange = { store.credential(if (synthesis) SpeechCredentialField.BAIDU_TTS_API_KEY
                        else SpeechCredentialField.BAIDU_ASR_API_KEY, it.trim()) })
                SpeechField("Secret Key", if (synthesis) store.credentials.baiduTtsSecretKey else store.credentials.baiduAsrSecretKey,
                    secret = true, onChange = { store.credential(if (synthesis) SpeechCredentialField.BAIDU_TTS_SECRET_KEY
                        else SpeechCredentialField.BAIDU_ASR_SECRET_KEY, it.trim()) })
            }
        }
        SpeechNote(stringResource(R.string.speech_baidu_connection_note))
    }
}

@Composable
internal fun IflytekConnectionSection(store: SpeechSettingsStore, synthesis: Boolean) {
    val config = if (synthesis) store.settings.iflytekTts else store.settings.iflytekAsr
    Column {
        SmallTitle(stringResource(R.string.speech_group_connection))
        Card(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            SpeechFieldColumn {
                SpeechField("App ID", config.appId, onChange = { value -> store.edit(
                    if (synthesis) store.settings.copy(iflytekTts = config.copy(appId = value.trim()))
                    else store.settings.copy(iflytekAsr = config.copy(appId = value.trim()))) })
                SpeechField("API Key", if (synthesis) store.credentials.iflytekTtsApiKey else store.credentials.iflytekAsrApiKey,
                    secret = true, onChange = { store.credential(if (synthesis) SpeechCredentialField.IFLYTEK_TTS_API_KEY
                        else SpeechCredentialField.IFLYTEK_ASR_API_KEY, it.trim()) })
                SpeechField("API Secret", if (synthesis) store.credentials.iflytekTtsApiSecret else store.credentials.iflytekAsrApiSecret,
                    secret = true, onChange = { store.credential(if (synthesis) SpeechCredentialField.IFLYTEK_TTS_API_SECRET
                        else SpeechCredentialField.IFLYTEK_ASR_API_SECRET, it.trim()) })
                if (!synthesis) {
                    SpeechField(stringResource(R.string.speech_iflytek_language), config.language,
                        onChange = { store.edit(store.settings.copy(iflytekAsr = config.copy(language = it.trim()))) })
                    SpeechField(stringResource(R.string.speech_iflytek_accent), config.accent,
                        onChange = { store.edit(store.settings.copy(iflytekAsr = config.copy(accent = it.trim()))) })
                }
            }
        }
        SpeechNote(stringResource(R.string.speech_iflytek_connection_note))
    }
}

@Composable
internal fun TencentConnectionSection(store: SpeechSettingsStore, synthesis: Boolean) {
    val config = if (synthesis) store.settings.tencentTts else store.settings.tencentAsr
    Column {
        SmallTitle(stringResource(R.string.speech_group_connection))
        Card(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            SpeechFieldColumn {
                SpeechField("SecretId", if (synthesis) store.credentials.tencentTtsSecretId else store.credentials.tencentAsrSecretId,
                    secret = true, onChange = { store.credential(if (synthesis) SpeechCredentialField.TENCENT_TTS_SECRET_ID
                        else SpeechCredentialField.TENCENT_ASR_SECRET_ID, it.trim()) })
                SpeechField("SecretKey", if (synthesis) store.credentials.tencentTtsSecretKey else store.credentials.tencentAsrSecretKey,
                    secret = true, onChange = { store.credential(if (synthesis) SpeechCredentialField.TENCENT_TTS_SECRET_KEY
                        else SpeechCredentialField.TENCENT_ASR_SECRET_KEY, it.trim()) })
                SpeechField(stringResource(R.string.speech_tencent_region), config.region,
                    onChange = { value -> store.edit(if (synthesis) store.settings.copy(tencentTts = config.copy(region = value.trim()))
                        else store.settings.copy(tencentAsr = config.copy(region = value.trim()))) })
                if (!synthesis) SpeechField(stringResource(R.string.speech_tencent_engine), config.engine,
                    onChange = { store.edit(store.settings.copy(tencentAsr = config.copy(engine = it.trim()))) })
            }
        }
        SpeechNote(stringResource(R.string.speech_tencent_connection_note))
    }
}
