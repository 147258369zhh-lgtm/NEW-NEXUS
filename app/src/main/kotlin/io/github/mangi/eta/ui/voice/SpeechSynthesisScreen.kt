package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.validateSpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import io.github.mangi.eta.ui.components.NexusSettingsSmallTitle as SmallTitle
import io.github.mangi.eta.ui.components.NexusSettingsDropdownPreference as OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SpeechSynthesisScreen(onBack: () -> Unit, onOpenLocalModels: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SpeechSettingsStore(context, scope) }
    SpeechLifecycle { store.stop() }
    val settings = store.settings
    val voiceContent = rememberSpeechVoiceContent(store, onOpenLocalModels)
    val defaultCloudText = stringResource(R.string.speech_preview_default_text)
    val defaultLocalText = stringResource(R.string.speech_local_preview_sample)
    var cloudPreviewText by rememberSaveable { mutableStateOf(defaultCloudText) }
    var localPreviewText by rememberSaveable { mutableStateOf(defaultLocalText) }

    MiuixScaffoldPage(
        title = stringResource(R.string.speech_synthesis_title),
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        if (!store.loaded) {
            item(key = "loading") { SpeechLoadingState(store.message) }
            return@MiuixScaffoldPage
        }
        item(key = "service") {
            SmallTitle(stringResource(R.string.speech_group_service))
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
            ) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.speech_tts_service),
                    items = TtsProvider.entries.map { it.label() },
                    selectedIndex = settings.tts.ordinal,
                    onSelectedIndexChange = { index ->
                        TtsProvider.entries.getOrNull(index)?.let { store.edit(settings.copy(tts = it)) }
                    },
                )
            }
        }
        if (settings.tts != TtsProvider.NONE) {
            item(key = "connection") {
                SpeechConnectionSection(store, synthesis = true, onOpenLocalModels = onOpenLocalModels)
            }
            item(key = "voice") {
                voiceContent()
            }
            item(key = "preview") {
                if (settings.tts == TtsProvider.LOCAL) {
                    LocalSpeechPreviewSection(store, localPreviewText) { localPreviewText = it }
                } else {
                    SpeechPreviewSection(store, cloudPreviewText) { cloudPreviewText = it }
                }
            }
        }
        item(key = "save") {
            SpeechSaveBlock(store) {
                if (store.settings.tts != TtsProvider.NONE) {
                    validateSpeechSettings(store.settings, store.credentials, synthesis = true)
                }
            }
        }
    }
}

/** 试听使用当前草稿；播放中再次点击停止，错误直接显示在行摘要里。 */
@Composable
private fun SpeechPreviewSection(store: SpeechSettingsStore, text: String, onTextChange: (String) -> Unit) {
    val playback by store.playback.state.collectAsState()
    val playing = playback.messageId != null

    Column {
        SmallTitle(stringResource(R.string.speech_preview_voice))
        Card(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        ) {
            SpeechFieldColumn {
                SpeechField(
                    label = stringResource(R.string.speech_preview_text),
                    value = text,
                    singleLine = false,
                    enabled = !store.saving,
                    onChange = {
                        if (it.length <= 500) {
                            store.playback.stop()
                            onTextChange(it)
                        }
                    },
                )
            }
            BasicComponent(
                title = stringResource(
                    if (playing) R.string.speech_preview_stop else R.string.speech_preview_voice,
                ),
                summary = playback.error ?: if (playback.loading) {
                    stringResource(R.string.speech_preview_loading)
                } else {
                    null
                },
                summaryColor = if (playback.error != null) {
                    BasicComponentDefaults.summaryColor(color = MiuixTheme.colorScheme.error)
                } else {
                    BasicComponentDefaults.summaryColor()
                },
                enabled = !store.saving && text.isNotBlank(),
                onClick = {
                    if (playing) {
                        store.playback.stop()
                    } else {
                        store.playback.speak(
                            "preview",
                            text,
                            store.settings,
                            store.credentials,
                        )
                    }
                },
            )
        }
        SpeechNote(stringResource(R.string.speech_preview_note))
    }
}
