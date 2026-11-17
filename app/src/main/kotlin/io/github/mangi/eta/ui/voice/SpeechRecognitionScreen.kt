package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.github.mangi.eta.agent.voice.VoiceprintProfile
import io.github.mangi.eta.agent.voice.VoiceprintEnrollmentController
import io.github.mangi.eta.agent.voice.BackgroundWakeWordRuntime
import io.github.mangi.eta.agent.voice.WakeListeningPhase
import io.github.mangi.eta.data.repository.LocalModelRepository
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.NexaSegmentedTabs
import io.github.mangi.eta.ui.components.StatusError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import io.github.mangi.eta.ui.components.NexusSettingsSmallTitle as SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import io.github.mangi.eta.ui.components.NexusSettingsArrowPreference as ArrowPreference
import io.github.mangi.eta.ui.components.NexusSettingsDropdownPreference as OverlayDropdownPreference
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val serviceListState = rememberLazyListState()
    val wakeListState = rememberLazyListState()
    val voiceprintListState = rememberLazyListState()
    val wakeWordSections = rememberWakeWordSections(context, onOpenLocalModels)
    val recognitionState by store.recognition.state.collectAsState()
    val showRecognitionStatus = recognitionState.active || recognitionState.error != null ||
        recognitionState.preview.isNotBlank() || store.recognitionTestResult != null

    MiuixScaffoldPage(
        title = stringResource(R.string.speech_recognition_title),
        onBack = onBack,
        modifier = Modifier.imePadding(),
        listState = when (tab) {
            0 -> serviceListState
            1 -> wakeListState
            else -> voiceprintListState
        },
        aboveContent = {
            Column {
                NexaSegmentedTabs(
                    tabs = listOf(
                        stringResource(R.string.speech_group_service),
                        stringResource(R.string.speech_wake_word_title),
                        stringResource(R.string.speech_voiceprint_profile),
                    ),
                    selectedTabIndex = tab,
                    onTabSelected = { tab = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                wakeWordSections.status?.let { status ->
                    ArrowPreference(
                        title = stringResource(R.string.speech_voiceprint_profile),
                        summary = status,
                        onClick = { tab = wakeWordSections.statusTab },
                    )
                }
                if (showRecognitionStatus) SpeechRecognitionTestSection(store, compact = true)
            }
        },
    ) {
        if (!store.loaded) {
            item(key = "loading") { SpeechLoadingState(store.message) }
            return@MiuixScaffoldPage
        }
        if (tab == 0) item(key = "recognition_enabled") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.speech_recognition_enabled),
                    checked = settings.recognitionEnabled,
                    onCheckedChange = { store.edit(settings.copy(recognitionEnabled = it)) },
                )
            }
        }
        if (settings.recognitionEnabled) {
            if (tab == 1 || tab == 2) item(key = "wake_words:$tab") { wakeWordSections.content(tab == 2) }
            if (tab == 0) item(key = "service") {
                Column {
                    SmallTitle(stringResource(R.string.speech_group_service))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
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
                                AsrProvider.AZURE -> R.string.speech_azure_asr_note
                                AsrProvider.BAIDU -> R.string.speech_baidu_asr_note
                                AsrProvider.IFLYTEK -> R.string.speech_iflytek_asr_note
                                AsrProvider.TENCENT -> R.string.speech_tencent_asr_note
                                AsrProvider.XIAOMI -> R.string.speech_xiaomi_asr_note
                                AsrProvider.DEEPSEEK -> R.string.speech_deepseek_asr_note
                            },
                        ),
                    )
                }
            }
            if (tab == 0 && !showRecognitionStatus) item(key = "test") {
                SpeechRecognitionTestSection(store)
            }
            if (tab == 0) item(key = "connection") {
                SpeechConnectionSection(store, synthesis = false, onOpenLocalModels = onOpenLocalModels)
            }
            if (tab == 0 && settings.asr in setOf(AsrProvider.QWEN_REALTIME, AsrProvider.QWEN_FLASH, AsrProvider.QWEN_FILE)) {
                item(key = "language") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
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
            if (tab == 0 && settings.asr == AsrProvider.QWEN_FILE) {
                item(key = "oss") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.speech_oss_title),
                            summary = settings.oss.bucket.ifBlank { stringResource(R.string.speech_oss_configure) },
                            onClick = onOpenOss,
                        )
                    }
                }
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
private fun rememberWakeWordSections(context: android.content.Context, onOpenLocalModels: () -> Unit): WakeWordSections {
    val scope = rememberCoroutineScope()
    val store = remember(context) { WakeWordStore(context) }
    val voiceprintStore = remember(context) { VoiceprintProfileStore(context) }
    var config by remember { mutableStateOf(store.read()) }
    val records by rememberLocalModelRecords()
    val wakeModels = records.filter { "keyword" in it.outputModalities }
    val speakerModels = records.filter { "speaker_embedding" in it.outputModalities }
    val listening by BackgroundWakeWordRuntime.state.collectAsState()
    LaunchedEffect(records.map { it.id }) {
        val next = config.copy(modelId = config.modelId.ifBlank { wakeModels.firstOrNull()?.id.orEmpty() },
            speakerModelId = config.speakerModelId.ifBlank { speakerModels.firstOrNull()?.id.orEmpty() })
        if (next != config) { config = next; store.write(next) }
    }
    var wakeError by remember { mutableStateOf<String?>(null) }
    val requestWake = rememberSpeechPermission {
        val next = config.copy(enabled = true).sanitized()
        store.write(next); config = next
        runCatching { BackgroundWakeWordRuntime.start(context) }.onSuccess { wakeError = null }.onFailure {
            wakeError = it.message; config = config.copy(enabled = false); store.write(config)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                config = store.read()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var profile by remember { mutableStateOf<VoiceprintProfile?>(null) }
    var profileLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(voiceprintStore) {
        profile = voiceprintStore.access { profile() }
        profileLoaded = true
    }
    val enrollment = remember(context, scope) {
        VoiceprintEnrollmentController(context, scope) { enrolled ->
            profile = enrolled
            config = config.copy(voiceprintEnabled = true)
            store.write(config)
        }
    }
    val enrollmentState by enrollment.state.collectAsState()
    DisposableEffect(enrollment) { onDispose { enrollment.cancel() } }
    val requestEnrollment = rememberSpeechPermission { enrollment.start() }
    var words by remember(config.words) { mutableStateOf(config.words.joinToString("、")) }
    return WakeWordSections(
        status = enrollmentState.error
            ?: enrollmentState.progress.takeIf { enrollmentState.active && it.isNotBlank() }
            ?: wakeError,
        statusTab = if (enrollmentState.active || enrollmentState.error != null) 2 else 1,
        content = { voiceprint ->
    Column {
        SmallTitle(stringResource(if (voiceprint) R.string.speech_voiceprint_profile else R.string.speech_wake_word_title))
        Card(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            if (!voiceprint) {
            SwitchPreference(
                title = stringResource(R.string.speech_wake_word_enabled),
                summary = wakeError ?: listening.message.takeIf(String::isNotBlank),
                checked = config.enabled,
                onCheckedChange = {
                    if (it) requestWake() else {
                        config = config.copy(enabled = false); store.write(config); BackgroundWakeWordRuntime.stop(context)
                    }
                },
            )
            if (wakeModels.isNotEmpty()) OverlayDropdownPreference(title = "唤醒模型", items = wakeModels.map { it.displayName },
                selectedIndex = wakeModels.indexOfFirst { it.id == config.modelId }.coerceAtLeast(0),
                onSelectedIndexChange = { index -> wakeModels.getOrNull(index)?.let {
                    config = config.copy(modelId = it.id); store.write(config)
                } })
            else ArrowPreference(title = "唤醒模型", summary = "未安装", onClick = onOpenLocalModels)
            if (config.enabled) ArrowPreference(title = when (listening.phase) {
                WakeListeningPhase.LISTENING -> "正在聆听"
                WakeListeningPhase.STARTING -> "正在启动"
                WakeListeningPhase.PAUSED -> "已让位"
                WakeListeningPhase.STOPPING -> "正在停止"
                else -> "开始聆听"
            }, summary = null, enabled = listening.phase !in setOf(WakeListeningPhase.LISTENING, WakeListeningPhase.STARTING, WakeListeningPhase.STOPPING), onClick = requestWake)
            }
            if (voiceprint) {
            if (speakerModels.isNotEmpty()) OverlayDropdownPreference(title = "声纹模型", items = speakerModels.map { it.displayName },
                selectedIndex = speakerModels.indexOfFirst { it.id == config.speakerModelId }.coerceAtLeast(0),
                onSelectedIndexChange = { index -> speakerModels.getOrNull(index)?.let {
                    config = config.copy(speakerModelId = it.id, voiceprintEnabled = false); store.write(config)
                } })
            else ArrowPreference(title = "声纹模型", summary = "未安装", onClick = onOpenLocalModels)
            ArrowPreference(
                title = stringResource(R.string.speech_voiceprint_profile),
                summary = enrollmentState.error
                    ?: enrollmentState.progress.takeIf(String::isNotBlank)
                    ?: if (profile != null && profile?.modelIdentity != speakerModels.firstOrNull { it.id == config.speakerModelId }?.let { "sherpa-speaker-v1:${it.sha256}" }) {
                        "模型已变更，请重新录入"
                    } else if (profile == null) {
                        stringResource(R.string.speech_voiceprint_profile_missing)
                    } else {
                        stringResource(R.string.speech_voiceprint_profile_ready_simple)
                    },
                enabled = profileLoaded && !enrollmentState.active,
                onClick = requestEnrollment,
            )
            SwitchPreference(
                title = stringResource(R.string.speech_voiceprint_enabled),
                summary = stringResource(R.string.speech_voiceprint_summary),
                checked = config.voiceprintEnabled,
                enabled = profileLoaded && !enrollmentState.active,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        config = config.copy(voiceprintEnabled = false)
                        store.write(config)
                    } else if (profile != null && profile?.modelIdentity == speakerModels.firstOrNull { it.id == config.speakerModelId }?.let { "sherpa-speaker-v1:${it.sha256}" }) {
                        config = config.copy(voiceprintEnabled = true)
                        store.write(config)
                    } else {
                        requestEnrollment()
                    }
                },
            )
            if (profile != null) {
                OverlayDropdownPreference(title = "声纹匹配阈值", items = listOf(.60f, .65f, .70f, .72f, .75f, .80f, .85f, .90f).map { "${(it * 100).toInt()}%" },
                    selectedIndex = listOf(.60f, .65f, .70f, .72f, .75f, .80f, .85f, .90f).indexOf(config.voiceprintThreshold).takeIf { it >= 0 } ?: 3,
                    onSelectedIndexChange = { index -> listOf(.60f, .65f, .70f, .72f, .75f, .80f, .85f, .90f).getOrNull(index)?.let {
                        config = config.copy(voiceprintThreshold = it); store.write(config)
                    } })
                ArrowPreference(
                    title = stringResource(R.string.speech_voiceprint_delete),
                    summary = null,
                    enabled = profileLoaded && !enrollmentState.active,
                    onClick = {
                        profileLoaded = false
                        scope.launch {
                            try {
                                voiceprintStore.access { clear() }
                                profile = null
                                config = config.copy(voiceprintEnabled = false)
                                store.write(config)
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                wakeError = error.message
                            } finally {
                                profileLoaded = true
                            }
                        }
                    },
                )
            }
            }
            if (!voiceprint) SpeechFieldColumn {
                SpeechField(
                    label = stringResource(R.string.speech_wake_word_field),
                    value = words,
                    onChange = { value ->
                        words = value.take(600)
                        val next = config.copy(words = words.split('、', ',', ';', '\n'))
                        config = next
                        store.write(next)
                    },
                )
            }
        }
    }
    })
}

private class WakeWordSections(
    val status: String?,
    val statusTab: Int,
    val content: @Composable (Boolean) -> Unit,
)

/** 使用当前草稿调用正式识别链路；进行中点击行完成，右侧可取消。 */
@Composable
private fun SpeechRecognitionTestSection(store: SpeechSettingsStore, compact: Boolean = false) {
    val state by store.recognition.state.collectAsState()
    val request = rememberSpeechPermission { store.startRecognitionTest() }
    val summary = state.error
        ?: state.preview.take(200).ifBlank { state.progress }.ifBlank { store.recognitionTestResult.orEmpty() }.ifBlank { null }

    Column {
        if (!compact) SmallTitle(stringResource(R.string.speech_test_recognition))
        Card(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
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
        if (!compact) SpeechNote(stringResource(R.string.speech_test_note))
    }
}
