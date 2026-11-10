package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.EtaVoiceInteractionService
import io.github.mangi.eta.agent.voice.validateSpeechSettings
import io.github.mangi.eta.agent.voice.WakeWordStore
import io.github.mangi.eta.agent.voice.VoiceprintProfileStore
import io.github.mangi.eta.agent.voice.VoiceprintEnrollmentController
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SpeechRecognitionScreen(
    onBack: () -> Unit,
    onOpenOss: () -> Unit,
    onOpenLocalModels: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SpeechSettingsStore(context, scope) }
    SpeechLifecycle { store.stop() }
    val settings = store.settings

    MiuixScaffoldPage(
        title = stringResource(R.string.speech_recognition_title),
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        if (!store.loaded) {
            item(key = "loading") { SpeechLoadingState(store.message) }
            return@MiuixScaffoldPage
        }
        item(key = "recognition_enabled") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.speech_recognition_enabled),
                    checked = settings.recognitionEnabled,
                    onCheckedChange = { store.edit(settings.copy(recognitionEnabled = it)) },
                )
            }
        }
        if (settings.recognitionEnabled) {
            item(key = "wake_words") { WakeWordSection(context) }
            item(key = "service") {
                Column {
                    SmallTitle(stringResource(R.string.speech_group_service))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.speech_service_and_model),
                            items = AsrProvider.entries.map { it.label() },
                            selectedIndex = settings.asr.ordinal,
                            onSelectedIndexChange = { index ->
                                AsrProvider.entries.getOrNull(index)?.let { store.edit(settings.copy(asr = it)) }
                            },
                        )
                    }
                    SpeechNote(
                        stringResource(
                            when (settings.asr) {
                                AsrProvider.QWEN_REALTIME, AsrProvider.DOUBAO -> R.string.speech_asr_note_streaming
                                AsrProvider.QWEN_FLASH -> R.string.speech_asr_note_flash
                                AsrProvider.QWEN_FILE -> R.string.speech_asr_note_file
                                AsrProvider.LOCAL -> R.string.speech_asr_note_local
                            },
                        ),
                    )
                }
            }
            item(key = "connection") {
                SpeechConnectionSection(store, synthesis = false, onOpenLocalModels = onOpenLocalModels)
            }
            if (settings.asr != AsrProvider.DOUBAO && settings.asr != AsrProvider.LOCAL) {
                item(key = "language") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        SpeechFieldColumn {
                            SpeechField(
                                label = stringResource(R.string.speech_language_hint),
                                value = settings.language,
                                onChange = { store.edit(settings.copy(language = it.trim())) },
                            )
                        }
                    }
                }
            }
            item(key = "result") {
                SmallTitle(stringResource(R.string.speech_group_result))
                SpeechResultSection(store)
            }
            if (settings.asr == AsrProvider.QWEN_FILE) {
                item(key = "oss") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.speech_oss_title),
                            summary = settings.oss.bucket.ifBlank { stringResource(R.string.speech_oss_configure) },
                            onClick = onOpenOss,
                        )
                    }
                }
            }
            item(key = "test") {
                SpeechRecognitionTestSection(store)
            }
        } else {
            item(key = "recognition_disabled_note") {
                SpeechNote(stringResource(R.string.speech_recognition_disabled_note))
            }
        }
        item(key = "save") {
            SpeechSaveBlock(store) {
                validateSpeechSettings(store.settings, store.credentials, synthesis = false)
            }
        }
    }
}

@Composable
private fun WakeWordSection(context: android.content.Context) {
    val scope = rememberCoroutineScope()
    val store = remember(context) { WakeWordStore(context) }
    val voiceprintStore = remember(context) { VoiceprintProfileStore(context) }
    var config by remember { mutableStateOf(store.read()) }
    var assistantActive by remember(context) {
        mutableStateOf(EtaVoiceInteractionService.isDefaultAssistant(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                assistantActive = EtaVoiceInteractionService.isDefaultAssistant(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var profile by remember { mutableStateOf(voiceprintStore.profile()) }
    val enrollment = remember(context, scope) {
        VoiceprintEnrollmentController(context, scope) { enrolled ->
            profile = enrolled
            config = config.copy(voiceprintEnabled = true)
            store.write(config)
        }
    }
    val enrollmentState by enrollment.state.collectAsState()
    val requestEnrollment = rememberSpeechPermission { enrollment.start() }
    var words by remember(config.words) { mutableStateOf(config.words.joinToString("、")) }
    Column {
        SmallTitle(stringResource(R.string.speech_wake_word_title))
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            SwitchPreference(
                title = stringResource(R.string.speech_wake_word_enabled),
                summary = stringResource(R.string.speech_wake_word_summary),
                checked = config.enabled,
                onCheckedChange = {
                    config = config.copy(enabled = it)
                    store.write(config)
                },
            )
            ArrowPreference(
                title = stringResource(R.string.speech_voiceprint_profile),
                summary = enrollmentState.error
                    ?: enrollmentState.progress.takeIf(String::isNotBlank)
                    ?: if (profile == null) {
                        stringResource(R.string.speech_voiceprint_profile_missing)
                    } else {
                        stringResource(R.string.speech_voiceprint_profile_ready_simple)
                    },
                enabled = !enrollmentState.active,
                onClick = requestEnrollment,
            )
            SwitchPreference(
                title = stringResource(R.string.speech_voiceprint_enabled),
                summary = stringResource(R.string.speech_voiceprint_summary),
                checked = config.voiceprintEnabled,
                enabled = !enrollmentState.active,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        config = config.copy(voiceprintEnabled = false)
                        store.write(config)
                    } else if (profile != null) {
                        config = config.copy(voiceprintEnabled = true)
                        store.write(config)
                    } else {
                        requestEnrollment()
                    }
                },
            )
            if (profile != null) {
                ArrowPreference(
                    title = stringResource(R.string.speech_voiceprint_delete),
                    summary = null,
                    enabled = !enrollmentState.active,
                    onClick = {
                        voiceprintStore.clear()
                        profile = null
                        config = config.copy(voiceprintEnabled = false)
                        store.write(config)
                    },
                )
            }
            SpeechFieldColumn {
                SpeechField(
                    label = stringResource(R.string.speech_wake_word_field),
                    value = words,
                    onChange = { value ->
                        words = value
                        val next = config.copy(words = value.split('、', ',', ';', '\n'))
                        config = next
                        store.write(next)
                    },
                )
            }
        }
        if (config.enabled && !assistantActive) {
            SpeechNote("当前 Nexus 未设为系统默认助手，唤醒词不会生效。请先在“设置 → 系统助手接管”中完成注册。")
        }
        SpeechNote(stringResource(R.string.speech_wake_word_note))
    }
}

/** 短音频与文件转写只在录音结束后识别，没有停顿判定，因此只对流式识别提供自动发送。 */
@Composable
private fun SpeechResultSection(store: SpeechSettingsStore) {
    val settings = store.settings
    val streaming = settings.asr !in setOf(AsrProvider.QWEN_FLASH, AsrProvider.QWEN_FILE)
    val options = SpeechSettings.AUTO_SEND_SILENCE_OPTIONS
    Column {
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            if (streaming) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.speech_auto_send_title),
                    items = options.map { millis ->
                        if (millis == 0) {
                            stringResource(R.string.speech_auto_send_off)
                        } else {
                            stringResource(R.string.speech_auto_send_seconds, (millis / 1_000f).toString().removeSuffix(".0"))
                        }
                    },
                    selectedIndex = options.indexOf(settings.autoSendSilenceMs)
                        .takeIf { it >= 0 }
                        ?: options.indexOf(SpeechSettings.DEFAULT_AUTO_SEND_SILENCE_MS),
                    onSelectedIndexChange = { index ->
                        options.getOrNull(index)?.let { store.edit(settings.copy(autoSendSilenceMs = it)) }
                    },
                )
            }
            SwitchPreference(
                title = stringResource(R.string.speech_refine_title),
                summary = stringResource(R.string.speech_refine_summary),
                checked = settings.refineTranscript,
                onCheckedChange = { store.edit(settings.copy(refineTranscript = it)) },
            )
            SwitchPreference(
                title = stringResource(R.string.speech_continuous_conversation_title),
                summary = stringResource(R.string.speech_continuous_conversation_summary),
                checked = settings.continuousConversation,
                onCheckedChange = { store.edit(settings.copy(continuousConversation = it)) },
            )
        }
        if (streaming) SpeechNote(stringResource(R.string.speech_auto_send_note))
    }
}

/** 使用当前草稿调用正式识别链路；进行中点击行完成，右侧可取消。 */
@Composable
private fun SpeechRecognitionTestSection(store: SpeechSettingsStore) {
    val state by store.recognition.state.collectAsState()
    val request = rememberSpeechPermission { store.recognition.start(store.settings, store.credentials) }
    val summary = state.error
        ?: state.preview.take(200).ifBlank { state.progress }.ifBlank { null }

    Column {
        SmallTitle(stringResource(R.string.speech_test_recognition))
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            BasicComponent(
                title = stringResource(
                    if (state.active) R.string.speech_test_finish else R.string.speech_test_recognition,
                ),
                summary = summary,
                summaryColor = BasicComponentDefaults.summaryColor(
                    color = if (state.error != null) StatusError else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                ),
                enabled = !store.saving,
                onClick = { if (state.active) store.recognition.finish() else request() },
                endActions = if (state.active) {
                    {
                        TextButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = { store.recognition.cancel() },
                            minWidth = 0.dp,
                            minHeight = 32.dp,
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                } else null,
            )
        }
        SpeechNote(stringResource(R.string.speech_test_note))
    }
}
