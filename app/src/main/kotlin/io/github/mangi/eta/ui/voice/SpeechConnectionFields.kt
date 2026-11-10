package io.github.mangi.eta.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.LocalSpeechKind
import io.github.mangi.eta.agent.voice.LocalSpeechRuntime
import io.github.mangi.eta.agent.voice.LocalSpeechRuntimePhase
import io.github.mangi.eta.agent.voice.localSpeechKind
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.DoubaoSpeechConfig
import io.github.mangi.eta.data.model.QwenSpeechConfig
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.data.model.SpeechRegion
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.LocalModelFormat
import io.github.mangi.eta.data.repository.LocalModelRepository
import io.github.mangi.eta.data.repository.LocalRuntimeBackendPolicy
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SpeechConnectionSection(
    store: SpeechSettingsStore,
    synthesis: Boolean,
    onOpenLocalModels: () -> Unit = {},
) {
    if (!synthesis) {
        when (store.settings.asr) {
            AsrProvider.LOCAL -> LocalSpeechModelSection(
                store = store,
                kind = LocalSpeechKind.ASR,
                onOpenLocalModels = onOpenLocalModels,
            )
            else -> QwenDoubaoConnectionSection(store, synthesis = false)
        }
        return
    }
    when (store.settings.tts) {
        TtsProvider.NONE -> Unit
        TtsProvider.QWEN, TtsProvider.DOUBAO -> QwenDoubaoConnectionSection(store, synthesis = true)
        TtsProvider.GEMINI -> GeminiConnectionSection(store)
        TtsProvider.MINIMAX -> MiniMaxConnectionSection(store)
        TtsProvider.LOCAL -> LocalSpeechModelSection(
            store = store,
            kind = LocalSpeechKind.TTS,
            onOpenLocalModels = onOpenLocalModels,
        )
    }
}

@Composable
private fun LocalSpeechModelSection(
    store: SpeechSettingsStore,
    kind: LocalSpeechKind,
    onOpenLocalModels: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    LocalModelRepository.init(context)
    val records by LocalModelRepository.models.collectAsState()
    val runtimeStates by LocalSpeechRuntime.states.collectAsState()
    val available = records.filter { record ->
        record.format == LocalModelFormat.LITERT_LM && localSpeechKind(record) == kind
    }
    val selectedId = if (kind == LocalSpeechKind.TTS) {
        store.settings.localTtsModelId
    } else {
        store.settings.localAsrModelId
    }
    val selectedIndex = available.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val selectedRecord = available.firstOrNull { it.id == selectedId }
    val runtime = runtimeStates[kind]

    LaunchedEffect(kind, available.map { it.id }, selectedId) {
        if (available.isNotEmpty() && available.none { it.id == selectedId }) {
            val first = available.first().id
            store.edit(
                if (kind == LocalSpeechKind.TTS) {
                    store.settings.copy(localTtsModelId = first)
                } else {
                    store.settings.copy(localAsrModelId = first)
                },
            )
        }
    }

    Column {
        SmallTitle(stringResource(R.string.speech_group_connection))
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            if (available.isEmpty()) {
                BasicComponent(
                    title = stringResource(R.string.speech_local_model_empty),
                    summary = stringResource(R.string.speech_local_manage_models),
                )
            } else {
                OverlayDropdownPreference(
                    title = stringResource(R.string.speech_local_model),
                    items = available.map { it.displayName },
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = { index ->
                        available.getOrNull(index)?.let { record ->
                            store.edit(
                                if (kind == LocalSpeechKind.TTS) {
                                    store.settings.copy(localTtsModelId = record.id)
                                } else {
                                    store.settings.copy(localAsrModelId = record.id)
                                },
                            )
                        }
                    },
                )
                selectedRecord?.let { record ->
                    val devices = listOf(
                        LocalRuntimeBackendPolicy.AUTO,
                        LocalRuntimeBackendPolicy.CPU,
                        LocalRuntimeBackendPolicy.GPU,
                        LocalRuntimeBackendPolicy.NPU,
                    )
                    OverlayDropdownPreference(
                        title = "计算设备",
                        summary = when {
                            runtime?.recordId != record.id -> null
                            runtime.phase == LocalSpeechRuntimePhase.READY ->
                                "已就绪 · ${runtime.activeBackend.ifBlank { record.runtimeSettings.backend.name }}"
                            runtime.phase == LocalSpeechRuntimePhase.STARTING -> "正在启动"
                            runtime.phase == LocalSpeechRuntimePhase.FAILED -> "启动失败"
                            else -> null
                        },
                        items = devices.map { if (it == LocalRuntimeBackendPolicy.AUTO) "自动选择" else it.name },
                        selectedIndex = devices.indexOf(record.runtimeSettings.backend).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            devices.getOrNull(index)?.let { backend ->
                                scope.launch {
                                    LocalModelRepository.updateRuntimeSettings(record.id) { it.copy(backend = backend) }
                                    LocalSpeechRuntime.release(record.id)
                                }
                            }
                        },
                    )
                    runtime?.message?.takeIf(String::isNotBlank)?.let { message ->
                        BasicComponent(title = message)
                    }
                }
            }
            ArrowPreference(
                title = stringResource(R.string.speech_local_manage_models),
                summary = if (available.isEmpty()) {
                    stringResource(R.string.speech_local_model_empty)
                } else {
                    stringResource(R.string.speech_local_model_count, available.size)
                },
                onClick = onOpenLocalModels,
            )
        }
    }
}

/** 识别与播报共用的服务商连接配置：地域或旧版鉴权、凭据与高级地址设置。 */
@Composable
private fun QwenDoubaoConnectionSection(store: SpeechSettingsStore, synthesis: Boolean) {
    val settings = store.settings
    val qwen = if (synthesis) settings.tts == TtsProvider.QWEN else settings.asr != AsrProvider.DOUBAO
    val keyField = when {
        qwen && synthesis -> SpeechCredentialField.QWEN_TTS
        qwen -> SpeechCredentialField.QWEN_ASR
        synthesis -> SpeechCredentialField.DOUBAO_TTS
        else -> SpeechCredentialField.DOUBAO_ASR
    }
    val secret = when (keyField) {
        SpeechCredentialField.QWEN_TTS -> store.credentials.qwenTts
        SpeechCredentialField.QWEN_ASR -> store.credentials.qwenAsr
        SpeechCredentialField.DOUBAO_TTS -> store.credentials.doubaoTts
        SpeechCredentialField.DOUBAO_ASR -> store.credentials.doubaoAsr
        else -> error("Not a provider credential")
    }
    var advanced by rememberSaveable(keyField) { mutableStateOf(false) }

    Column {
        SmallTitle(stringResource(R.string.speech_group_connection))
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        ) {
            if (qwen) {
                val config = if (synthesis) settings.qwenTts else settings.qwenAsr
                val change: (QwenSpeechConfig) -> Unit = {
                    store.edit(if (synthesis) settings.copy(qwenTts = it) else settings.copy(qwenAsr = it))
                }
                OverlayDropdownPreference(
                    title = stringResource(R.string.speech_region),
                    items = listOf(
                        stringResource(R.string.speech_region_beijing),
                        stringResource(R.string.speech_region_singapore),
                    ),
                    selectedIndex = config.region.ordinal,
                    onSelectedIndexChange = { index ->
                        SpeechRegion.entries.getOrNull(index)?.let { change(config.copy(region = it)) }
                    },
                )
                SpeechFieldColumn {
                    SpeechField(
                        label = "API Key",
                        value = secret,
                        secret = true,
                        onChange = { store.credential(keyField, it.trim()) },
                    )
                }
            } else {
                val config = if (synthesis) settings.doubaoTts else settings.doubaoAsr
                val change: (DoubaoSpeechConfig) -> Unit = {
                    store.edit(if (synthesis) settings.copy(doubaoTts = it) else settings.copy(doubaoAsr = it))
                }
                SpeechFieldColumn {
                    SpeechField(
                        label = stringResource(
                            if (config.legacyAuth) R.string.speech_access_token else R.string.speech_api_key,
                        ),
                        value = secret,
                        secret = true,
                        onChange = { store.credential(keyField, it.trim()) },
                    )
                    if (config.legacyAuth) {
                        SpeechField(
                            label = stringResource(R.string.speech_app_id),
                            value = config.appId,
                            onChange = { change(config.copy(appId = it.trim())) },
                        )
                    }
                }
            }

            val chevronRotation by animateFloatAsState(if (advanced) 180f else 0f)
            BasicComponent(
                title = stringResource(R.string.speech_advanced),
                endActions = {
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = stringResource(
                            if (advanced) R.string.speech_collapse else R.string.speech_expand,
                        ),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier.rotate(chevronRotation),
                    )
                },
                onClick = { advanced = !advanced },
            )
            AnimatedVisibility(visible = advanced) {
                Column {
                    if (qwen) {
                        val config = if (synthesis) settings.qwenTts else settings.qwenAsr
                        SpeechFieldColumn {
                            SpeechField(
                                label = stringResource(R.string.speech_qwen_base_url_hint),
                                value = config.baseUrl,
                                onChange = { value ->
                                    store.edit(
                                        if (synthesis) settings.copy(qwenTts = config.copy(baseUrl = value.trim()))
                                        else settings.copy(qwenAsr = config.copy(baseUrl = value.trim())),
                                    )
                                },
                            )
                        }
                    } else {
                        val config = if (synthesis) settings.doubaoTts else settings.doubaoAsr
                        SwitchPreference(
                            title = stringResource(R.string.speech_legacy_auth),
                            checked = config.legacyAuth,
                            onCheckedChange = { enabled ->
                                store.edit(
                                    if (synthesis) settings.copy(doubaoTts = config.copy(legacyAuth = enabled))
                                    else settings.copy(doubaoAsr = config.copy(legacyAuth = enabled)),
                                )
                            },
                        )
                        SpeechFieldColumn {
                            SpeechField(
                                label = stringResource(R.string.speech_base_url),
                                value = config.baseUrl,
                                onChange = { value ->
                                    store.edit(
                                        if (synthesis) settings.copy(doubaoTts = config.copy(baseUrl = value.trim()))
                                        else settings.copy(doubaoAsr = config.copy(baseUrl = value.trim())),
                                    )
                                },
                            )
                            SpeechField(
                                label = stringResource(R.string.speech_resource_id_hint),
                                value = config.resourceId,
                                onChange = { value ->
                                    store.edit(
                                        if (synthesis) settings.copy(doubaoTts = config.copy(resourceId = value.trim()))
                                        else settings.copy(doubaoAsr = config.copy(resourceId = value.trim())),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        if (advanced) {
            SpeechNote(
                stringResource(
                    when {
                        qwen -> R.string.speech_qwen_base_url_note
                        synthesis -> R.string.speech_doubao_tts_note
                        else -> R.string.speech_doubao_asr_note
                    },
                ),
            )
        }
    }
}
