@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.LocalSpeechKind
import io.github.mangi.eta.agent.voice.LocalSpeechRuntime
import io.github.mangi.eta.agent.voice.LocalSpeechRuntimePhase
import io.github.mangi.eta.agent.voice.LocalSpeechRuntimeSnapshot
import io.github.mangi.eta.agent.voice.LocalSpeechVoiceInfo
import io.github.mangi.eta.data.repository.LocalModelRecord
import io.github.mangi.eta.data.repository.LocalModelRepository
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.pages.providers.TagChip
import io.github.mangi.eta.ui.pages.providers.TagChipTone
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LocalSpeechVoiceSection(
    store: SpeechSettingsStore,
    onOpenLocalModels: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    LocalModelRepository.init(context)
    val records by LocalModelRepository.models.collectAsState()
    val runtimeStates by LocalSpeechRuntime.states.collectAsState()
    val settings = store.settings
    val record = records.firstOrNull { it.id == settings.localTtsModelId }
    val family = SpeechLocalVoiceCatalog.family(record)
    val runtime = runtimeStates[LocalSpeechKind.TTS]
    var availableVoices by remember(record?.id) { mutableStateOf<List<LocalSpeechVoiceInfo>>(emptyList()) }
    val selectedVoice = if (family == SpeechLocalModelFamily.KOKORO) {
        SpeechLocalVoiceCatalog.voice(settings.localTtsVoiceId) ?: SpeechLocalVoiceCatalog.chinese.first()
    } else {
        null
    }
    val fallbackChoices = SpeechLocalVoiceCatalog.chinese.map { voice ->
        LocalSpeechVoiceInfo(
            id = voice.id,
            displayName = stringResource(voice.nameRes),
            locale = "zh-CN",
            sampleRateHz = 24_000,
            downloaded = true,
        )
    }

    LaunchedEffect(record?.id, settings.localTtsVoiceId, settings.localTtsLocale) {
        val next = SpeechLocalVoiceCatalog.normalize(store.settings, record)
        if (next != store.settings) store.edit(next)
    }
    LaunchedEffect(record?.id, runtime?.phase) {
        availableVoices = if (record != null && runtime?.phase == LocalSpeechRuntimePhase.READY) {
            runCatching { LocalSpeechRuntime.listVoices(store.settings) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }
    LaunchedEffect(settings.localTtsVoiceId, settings.localTtsRate) {
        store.playback.stop()
    }

    Column {
        SmallTitle(stringResource(R.string.speech_group_voice))
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            if (record == null) {
                BasicComponent(
                    title = stringResource(R.string.speech_local_model_empty),
                    summary = stringResource(R.string.speech_local_manage_models),
                    onClick = onOpenLocalModels,
                )
            } else {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = selectedVoice?.let { stringResource(it.nameRes) }
                                    ?: settings.localTtsVoiceId.ifBlank {
                                        stringResource(R.string.speech_local_voice_default)
                                    },
                                style = MiuixTheme.textStyles.body1,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (selectedVoice != null) {
                                    TagChip(
                                        stringResource(
                                            if (selectedVoice.gender == SpeechLocalVoiceGender.FEMALE) {
                                                R.string.speech_voice_female
                                            } else {
                                                R.string.speech_voice_male
                                            },
                                        ),
                                    )
                                    TagChip(stringResource(selectedVoice.toneRes))
                                    TagChip(stringResource(R.string.speech_local_voice_grade, selectedVoice.grade))
                                    if (selectedVoice.recommended) {
                                        TagChip(
                                            stringResource(R.string.speech_local_voice_recommended),
                                            TagChipTone.Emphasized,
                                        )
                                    }
                                } else {
                                    TagChip(stringResource(R.string.speech_local_voice_auto))
                                }
                                SpeechRuntimeTag(runtime, record)
                            }
                        }
                    }

                    if (family == SpeechLocalModelFamily.KOKORO) {
                        val choices = availableVoices.ifEmpty { fallbackChoices }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            choices.forEach { voice ->
                                EtaChoiceChip(
                                    text = voice.displayName.take(24),
                                    selected = voice.id == settings.localTtsVoiceId,
                                    onClick = {
                                        store.playback.stop()
                                        store.edit(
                                            settings.copy(
                                                localTtsVoiceId = voice.id,
                                                localTtsLocale = voice.locale.ifBlank { "zh-CN" },
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.speech_local_language),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            TagChip(
                                stringResource(R.string.speech_local_language_zh_cn),
                                TagChipTone.Emphasized,
                            )
                        }
                    } else if (availableVoices.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            availableVoices.forEach { voice ->
                                EtaChoiceChip(
                                    text = voice.displayName.take(24),
                                    selected = voice.id == settings.localTtsVoiceId,
                                    onClick = {
                                        store.playback.stop()
                                        store.edit(
                                            settings.copy(
                                                localTtsVoiceId = voice.id,
                                                localTtsLocale = voice.locale.ifBlank { settings.localTtsLocale },
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.speech_local_language),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            TagChip(stringResource(R.string.speech_local_voice_auto))
                        }
                        SpeechFieldColumn {
                            SpeechField(
                                label = stringResource(R.string.speech_voice_id),
                                value = settings.localTtsVoiceId,
                                onChange = { voiceId ->
                                    store.playback.stop()
                                    store.edit(settings.copy(localTtsVoiceId = voiceId.trim()))
                                },
                            )
                        }
                    }

                    SpeechLocalSpeedRow(store)
                }
            }
        }
    }
}

@Composable
internal fun LocalSpeechPreviewSection(store: SpeechSettingsStore) {
    val context = LocalContext.current.applicationContext
    LocalModelRepository.init(context)
    val records by LocalModelRepository.models.collectAsState()
    val record = records.firstOrNull { it.id == store.settings.localTtsModelId }
    val playback by store.playback.state.collectAsState()
    val playing = playback.messageId != null
    val sample = stringResource(R.string.speech_local_preview_sample)
    var previewText by remember { mutableStateOf(sample) }

    Column {
        SmallTitle(stringResource(R.string.speech_preview_voice))
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SpeechFieldColumn {
                    SpeechField(
                        label = stringResource(R.string.speech_preview_text),
                        value = previewText,
                        singleLine = false,
                        onChange = {
                            if (it.length <= 500) {
                                store.playback.stop()
                                previewText = it
                            }
                        },
                    )
                }
                val status = playback.error ?: when {
                    playback.loading -> stringResource(R.string.speech_preview_loading)
                    playing -> stringResource(R.string.speech_local_preview_playing)
                    else -> null
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (status != null) {
                        Text(
                            text = status,
                            style = MiuixTheme.textStyles.footnote2,
                            color = if (playback.error != null) {
                                MiuixTheme.colorScheme.error
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Box(Modifier.weight(1f))
                    }
                    TextButton(
                        text = stringResource(
                            if (playing) R.string.speech_preview_stop else R.string.speech_local_preview,
                        ),
                        enabled = record != null && !store.saving && previewText.isNotBlank(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            if (playing) {
                                store.playback.stop()
                            } else {
                                store.playback.speak(
                                    "local-preview",
                                    previewText,
                                    store.settings,
                                    store.credentials,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeechRuntimeTag(
    runtime: LocalSpeechRuntimeSnapshot?,
    record: LocalModelRecord,
) {
    val phase = runtime?.takeIf { it.recordId == record.id }?.phase
    val text = when (phase) {
        LocalSpeechRuntimePhase.READY -> stringResource(
            R.string.speech_local_voice_ready,
            runtime.activeBackend.ifBlank { record.runtimeSettings.backend.name },
        )
        LocalSpeechRuntimePhase.STARTING -> stringResource(R.string.speech_local_voice_starting)
        LocalSpeechRuntimePhase.FAILED -> stringResource(R.string.speech_local_voice_failed)
        LocalSpeechRuntimePhase.STOPPED, null -> stringResource(R.string.speech_local_voice_unloaded)
    }
    TagChip(
        text = text,
        tone = if (phase == LocalSpeechRuntimePhase.READY) TagChipTone.Emphasized else TagChipTone.Normal,
    )
}

@Composable
private fun SpeechLocalSpeedRow(
    store: SpeechSettingsStore,
) {
    val settings = store.settings
    val speeds = remember(settings.localTtsRate) {
        (listOf(0.8f, 0.9f, 0.95f, 1f, 1.05f, 1.1f, 1.2f) + settings.localTtsRate)
            .distinct()
            .sorted()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.speech_speed),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            speeds.forEach { speed ->
                EtaChoiceChip(
                    text = formatSpeechSpeed(speed),
                    selected = kotlin.math.abs(settings.localTtsRate - speed) < 0.001f,
                    onClick = {
                        if (kotlin.math.abs(settings.localTtsRate - speed) >= 0.001f) {
                            store.playback.stop()
                            store.edit(settings.copy(localTtsRate = speed))
                        }
                    },
                )
            }
        }
    }
}

private fun formatSpeechSpeed(value: Float): String {
    val text = if (value % 1f == 0f) value.toInt().toString() else value.toString()
    return "$text×"
}
