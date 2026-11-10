@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.mangi.eta.ui.screens.localmodel

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import io.github.mangi.eta.agent.voice.LocalSpeechRuntime
import io.github.mangi.eta.agent.voice.LocalSpeechRuntimePhase
import io.github.mangi.eta.agent.voice.LocalSpeechRuntimeSnapshot
import io.github.mangi.eta.agent.voice.localSpeechKind
import io.github.mangi.eta.agent.model.LiteRtLmRuntimeManager
import io.github.mangi.eta.agent.model.LlamaCppRuntimeManager
import io.github.mangi.eta.agent.model.MnnRuntimeManager
import io.github.mangi.eta.agent.model.NativeLaunchKind
import io.github.mangi.eta.agent.model.NpuModelTargeting
import io.github.mangi.eta.agent.model.QualcommNpuRuntimeManager
import io.github.mangi.eta.agent.model.QualcommNpuRuntimeState
import io.github.mangi.eta.data.model.ProviderEndpoint
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.LocalDevicePerformanceSnapshot
import io.github.mangi.eta.data.repository.LocalModelDownloadStage
import io.github.mangi.eta.data.repository.LocalModelFormat
import io.github.mangi.eta.data.repository.LocalModelPerformanceMonitor
import io.github.mangi.eta.data.repository.LocalModelPreset
import io.github.mangi.eta.data.repository.LocalModelRecord
import io.github.mangi.eta.data.repository.LocalModelRepository
import io.github.mangi.eta.data.repository.LocalModelRuntimeSettings
import io.github.mangi.eta.data.repository.LocalModelRuntimeSnapshot
import io.github.mangi.eta.data.repository.LocalModelRuntimeState
import io.github.mangi.eta.data.repository.LocalRuntimeBackendPolicy
import io.github.mangi.eta.data.repository.LocalRuntimePowerPolicy
import io.github.mangi.eta.data.repository.LocalRuntimeThermalPolicy
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.DialogChoicePreference
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.EtaMotion
import io.github.mangi.eta.ui.components.EtaUiMetrics
import io.github.mangi.eta.ui.components.etaRippleIndication
import io.github.mangi.eta.ui.pages.providers.ProviderSection
import io.github.mangi.eta.ui.pages.providers.TagChip
import io.github.mangi.eta.ui.pages.providers.TagChipTone
import java.text.DecimalFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private enum class LocalModelNoticeTone { INFO, SUCCESS, ERROR }

private enum class LocalModelTypeFilter { TEXT, TTS, ASR, VISION, EMBEDDING }

private const val TAB_OVERVIEW = 0
private const val TAB_MODELS = 1
private const val TAB_RUNTIME = 2

internal val LocalServicePresets = listOf(
    "Ollama" to "http://127.0.0.1:11434/v1",
    "LM Studio" to "http://127.0.0.1:1234/v1",
    "llama.cpp" to "http://127.0.0.1:8080/v1",
)
@Composable
internal fun LocalModelsScreen(
    onBack: () -> Unit,
    onOpenSpeechRecognition: () -> Unit = {},
    onOpenSpeechSynthesis: () -> Unit = {},
    onOpenLocalService: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    LocalModelRepository.init(context)
    val models by LocalModelRepository.models.collectAsState()
    val speechSettings by SpeechSettingsRepository.settingsFlow().collectAsState(initial = SpeechSettings())
    val download by LocalModelRepository.download.collectAsState()
    val runtime by LocalModelRepository.runtime.collectAsState()
    val speechRuntime by LocalSpeechRuntime.states.collectAsState()
    var tab by rememberSaveable { mutableStateOf(TAB_OVERVIEW) }
    var downloadUrl by rememberSaveable { mutableStateOf(LocalModelRepository.defaultDownloadUrl(context)) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeTone by remember { mutableStateOf(LocalModelNoticeTone.INFO) }
    var noticeOffersRuntimeSettings by remember { mutableStateOf(false) }
    var modelTypeFilterIndex by rememberSaveable { mutableStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var importingMnnFolder by remember { mutableStateOf(false) }
    var bindingRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    var bindingSaving by rememberSaveable { mutableStateOf(false) }
    var bindingError by remember { mutableStateOf<String?>(null) }
    var deletingRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    var verifyingId by remember { mutableStateOf<String?>(null) }
    var benchmarkingId by remember { mutableStateOf<String?>(null) }
    var attachingId by remember { mutableStateOf<String?>(null) }
    var runtimeBusy by remember { mutableStateOf(false) }
    var selectedRuntimeId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceExpanded by rememberSaveable { mutableStateOf(false) }
    var startAfterRecommendationDownload by rememberSaveable { mutableStateOf(false) }
    var performanceSnapshot by remember {
        mutableStateOf(LocalModelPerformanceMonitor.capture(context))
    }

    LocalDevicePerformanceEffects(context = context) {
        performanceSnapshot = it
    }

    fun noticeOf(
        message: String,
        tone: LocalModelNoticeTone,
        offersRuntimeSettings: Boolean = false,
    ) {
        notice = message
        noticeTone = tone
        noticeOffersRuntimeSettings = offersRuntimeSettings
    }

    fun startDownload(preset: LocalModelPreset, sourceUrl: String) {
        if (download.stage in setOf(
                LocalModelDownloadStage.QUEUED,
                LocalModelDownloadStage.DOWNLOADING,
                LocalModelDownloadStage.VERIFYING,
            )
        ) {
            return
        }
        scope.launch {
            try {
                val title = preset.displayName.takeIf(String::isNotBlank) ?: when (preset.id) {
                    LocalModelPreset.Qwen3DynamicInt4.id -> context.getString(R.string.local_models_qwen3_title)
                    LocalModelPreset.Qwen3OnePointSevenB.id -> context.getString(R.string.local_models_qwen17_title)
                    LocalModelPreset.Qwen3FourB.id -> context.getString(R.string.local_models_qwen4_title)
                    LocalModelPreset.Gemma3OneBInt4.id -> context.getString(R.string.local_models_gemma3_title)
                    LocalModelPreset.Gemma4E2B.id -> context.getString(R.string.local_models_gemma4_title)
                    LocalModelPreset.Qwen25Small.id -> context.getString(R.string.local_models_qwen_title)
                    LocalModelPreset.Qwen25OnePointFiveB.id -> context.getString(R.string.local_models_qwen15_title)
                    LocalModelPreset.Qwen25ThreeB.id -> context.getString(R.string.local_models_qwen25_3b_title)
                    else -> sourceUrl.substringAfterLast('/').substringBefore('?').ifBlank { "local-model" }
                }
                LocalModelRepository.startDownload(context, title, sourceUrl)
                noticeOf(context.getString(R.string.local_models_download_active, title), LocalModelNoticeTone.INFO)
            } catch (failure: Exception) {
                startAfterRecommendationDownload = false
                noticeOf(
                    context.getString(
                        R.string.local_models_download_failed,
                        failure.message ?: failure.javaClass.simpleName,
                    ),
                    LocalModelNoticeTone.ERROR,
                )
            }
        }
    }

    fun saveRuntimeSettings(
        record: LocalModelRecord,
        transform: (LocalModelRuntimeSettings) -> LocalModelRuntimeSettings,
    ) {
        scope.launch {
            try {
                LocalModelRepository.updateRuntimeSettings(record.id, transform)
                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
            } catch (failure: Exception) {
                noticeOf(
                    context.getString(
                        R.string.local_models_runtime_settings_failed,
                        failure.message ?: failure.javaClass.simpleName,
                    ),
                    LocalModelNoticeTone.ERROR,
                )
            }
        }
    }

    fun stopRecord(record: LocalModelRecord) {
        if (runtimeBusy) return
        runtimeBusy = true
        scope.launch {
            try {
                when (localModelType(record)) {
                    LocalModelTypeFilter.TTS, LocalModelTypeFilter.ASR -> LocalSpeechRuntime.stop(record.id)
                    else -> when (record.format) {
                        LocalModelFormat.LITERT_LM -> LiteRtLmRuntimeManager.stop(record.id)
                        LocalModelFormat.GGUF -> LlamaCppRuntimeManager.stop(record.id)
                        LocalModelFormat.MNN -> MnnRuntimeManager.stop(record.id)
                        else -> Unit
                    }
                }
                noticeOf(context.getString(R.string.local_models_runtime_stopped), LocalModelNoticeTone.INFO)
            } catch (failure: Exception) {
                noticeOf(
                    context.getString(
                        R.string.local_models_runtime_stop_failed,
                        failure.message ?: failure.javaClass.simpleName,
                    ),
                    LocalModelNoticeTone.ERROR,
                )
            } finally {
                runtimeBusy = false
            }
        }
    }

    fun attachExternally(record: LocalModelRecord, onFinished: (String, Boolean) -> Unit) {
        if (attachingId != null) return
        attachingId = record.id
        scope.launch {
            try {
                val label = withContext(Dispatchers.IO) {
                    discoverAndBindLocalService(record, record.runtimeSettings.contextWindow)
                }
                val message = context.getString(R.string.local_models_attach_success, label)
                noticeOf(message, LocalModelNoticeTone.SUCCESS)
                onFinished(message, true)
                LocalModelRepository.refresh()
            } catch (failure: Exception) {
                val message = context.getString(
                    R.string.local_models_attach_not_found,
                    failure.message ?: failure.javaClass.simpleName,
                )
                noticeOf(message, LocalModelNoticeTone.ERROR)
                onFinished(message, false)
            } finally {
                attachingId = null
            }
        }
    }

    fun bindSpeechRecord(record: LocalModelRecord, speech: Boolean) {
        if (record.format != LocalModelFormat.LITERT_LM) {
            noticeOf("当前本地语音适配器仅支持 LiteRT-LM 语音包", LocalModelNoticeTone.ERROR)
            return
        }
        runtimeBusy = true
        selectedRuntimeId = record.id
        scope.launch {
            try {
                val current = SpeechSettingsRepository.settings()
                val next = if (speech) {
                    current.copy(tts = TtsProvider.LOCAL, localTtsModelId = record.id)
                } else {
                    current.copy(asr = AsrProvider.LOCAL, localAsrModelId = record.id)
                }
                SpeechSettingsRepository.save(next)
                LocalSpeechRuntime.warmUp(next, localSpeechKind(record) ?: error("不是本地语音模型"))
                val snapshot = LocalSpeechRuntime.states.value[localSpeechKind(record)]
                val device = snapshot?.activeBackend?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
                noticeOf(
                    if (speech) "已启动 ${record.displayName}$device"
                    else "已启动 ${record.displayName}$device",
                    LocalModelNoticeTone.SUCCESS,
                )
            } catch (failure: Exception) {
                noticeOf(
                    "语音模型绑定失败：${failure.message ?: failure.javaClass.simpleName}",
                    LocalModelNoticeTone.ERROR,
                )
            } finally {
                runtimeBusy = false
            }
        }
    }

    fun startRecord(record: LocalModelRecord, automatic: Boolean = false) {
        if (runtimeBusy) return
        when (localModelType(record)) {
            LocalModelTypeFilter.TTS -> {
                bindSpeechRecord(record, speech = true)
                return
            }
            LocalModelTypeFilter.ASR -> {
                bindSpeechRecord(record, speech = false)
                return
            }
            else -> Unit
        }
        if (record.format !in setOf(LocalModelFormat.LITERT_LM, LocalModelFormat.GGUF, LocalModelFormat.MNN)) {
            attachExternally(record) { message, success ->
                if (!success) {
                    bindingError = message
                    bindingRecordId = record.id
                }
            }
            return
        }
        runtimeBusy = true
        selectedRuntimeId = record.id
        scope.launch {
            try {
                // Re-register on every start. A generated provider may have been removed from
                // model settings; this also repairs a stale binding without touching selection.
                LocalModelRepository.activateBuiltInRuntime(record.id)
                when (record.format) {
                    LocalModelFormat.LITERT_LM -> LiteRtLmRuntimeManager.warmUp(
                        record.id,
                        if (automatic) NativeLaunchKind.AUTOMATIC else NativeLaunchKind.USER,
                    )
                    LocalModelFormat.GGUF -> LlamaCppRuntimeManager.warmUp(
                        record.id,
                        if (automatic) NativeLaunchKind.AUTOMATIC else NativeLaunchKind.USER,
                    )
                    LocalModelFormat.MNN -> MnnRuntimeManager.warmUp(
                        record.id,
                        if (automatic) NativeLaunchKind.AUTOMATIC else NativeLaunchKind.USER,
                    )
                    else -> Unit
                }
                LocalModelRepository.selectBuiltInRuntime(record.id)
                noticeOf(
                    context.getString(R.string.local_models_runtime_started, record.displayName),
                    LocalModelNoticeTone.SUCCESS,
                )
                LocalModelRepository.refresh()
            } catch (failure: Exception) {
                noticeOf(
                    context.getString(
                        R.string.local_models_runtime_start_failed,
                        record.displayName,
                        failure.message ?: failure.javaClass.simpleName,
                    ),
                    LocalModelNoticeTone.ERROR,
                    offersRuntimeSettings = true,
                )
            } finally {
                runtimeBusy = false
            }
        }
    }

    /** Registers a built-in artifact in the model picker without loading it or changing the
     * selected chat model. Used when a download or import only asks to attach automatically. */
    fun registerRecord(record: LocalModelRecord) {
        if (!record.format.supportsBuiltInRuntime() ||
            localModelType(record) !in setOf(LocalModelTypeFilter.TEXT, LocalModelTypeFilter.VISION)
        ) return
        selectedRuntimeId = record.id
        scope.launch {
            try {
                LocalModelRepository.activateBuiltInRuntime(record.id)
                noticeOf(
                    context.getString(R.string.local_models_runtime_registered_only, record.displayName),
                    LocalModelNoticeTone.SUCCESS,
                )
                LocalModelRepository.refresh()
            } catch (failure: Exception) {
                noticeOf(
                    context.getString(
                        R.string.local_models_runtime_register_failed,
                        record.displayName,
                        failure.message ?: failure.javaClass.simpleName,
                    ),
                    LocalModelNoticeTone.ERROR,
                    offersRuntimeSettings = true,
                )
            }
        }
    }

    /** Runs after an import finishes so every import entry point ends in the same visible state. */
    suspend fun finishImport(record: LocalModelRecord) {
        LocalModelRepository.refresh()
        selectedRuntimeId = record.id
        when (localModelLaunchDecision(
            startAfterDownload = false,
            format = record.format,
            settings = record.runtimeSettings,
            supportsChat = localModelType(record) in setOf(LocalModelTypeFilter.TEXT, LocalModelTypeFilter.VISION),
        )) {
            LocalModelLaunchDecision.START -> startRecord(record, automatic = true)
            LocalModelLaunchDecision.REGISTER_ONLY -> registerRecord(record)
            LocalModelLaunchDecision.NONE -> noticeOf(
                context.getString(R.string.local_models_import_success, record.displayName),
                LocalModelNoticeTone.SUCCESS,
            )
        }
    }

    fun benchmark(record: LocalModelRecord) {
        if (record.boundProviderId == null || record.boundModelSelectionId == null) {
            bindingRecordId = record.id
            bindingError = context.getString(R.string.local_models_benchmark_bind_first)
            return
        }
        if (benchmarkingId != null) return
        benchmarkingId = record.id
        scope.launch {
            try {
                val result = LocalModelPerformanceMonitor.benchmark(record)
                LocalModelRepository.recordBenchmark(record.id, result)
                noticeOf(context.getString(R.string.local_models_benchmark_success), LocalModelNoticeTone.SUCCESS)
            } catch (failure: Exception) {
                noticeOf(
                    context.getString(
                        R.string.local_models_benchmark_failed,
                        failure.message ?: failure.javaClass.simpleName,
                    ),
                    LocalModelNoticeTone.ERROR,
                )
            } finally {
                benchmarkingId = null
            }
        }
    }

    LaunchedEffect(Unit) { LocalModelRepository.refresh() }
    LaunchedEffect(models, runtime.recordId) {
        val current = selectedRuntimeId
        if (current == null || models.none { it.id == current }) {
            selectedRuntimeId = runtime.recordId?.takeIf { id -> models.any { it.id == id } }
                ?: models.firstOrNull { it.sourceUrl == LocalModelPreset.Qwen3DynamicInt4.url }?.id
                ?: models.firstOrNull {
                    it.format in setOf(LocalModelFormat.LITERT_LM, LocalModelFormat.GGUF, LocalModelFormat.MNN)
                }?.id
                ?: models.firstOrNull()?.id
        }
    }
    LaunchedEffect(download.stage) {
        when (download.stage) {
            LocalModelDownloadStage.COMPLETED -> {
                LocalModelRepository.refresh()
                val completed = LocalModelRepository.models.value.firstOrNull { it.id == download.id }
                if (completed == null) {
                    noticeOf(context.getString(R.string.local_models_download_complete), LocalModelNoticeTone.SUCCESS)
                } else {
                    selectedRuntimeId = completed.id
                    when (localModelLaunchDecision(
                        startAfterDownload = startAfterRecommendationDownload,
                        format = completed.format,
                        settings = completed.runtimeSettings,
                        supportsChat = localModelType(completed) in setOf(
                            LocalModelTypeFilter.TEXT,
                            LocalModelTypeFilter.VISION,
                        ),
                    )) {
                        LocalModelLaunchDecision.START -> startRecord(completed, automatic = true)
                        LocalModelLaunchDecision.REGISTER_ONLY -> registerRecord(completed)
                        LocalModelLaunchDecision.NONE -> noticeOf(
                            context.getString(R.string.local_models_download_complete),
                            LocalModelNoticeTone.SUCCESS,
                        )
                    }
                }
                startAfterRecommendationDownload = false
            }
            LocalModelDownloadStage.FAILED -> noticeOf(
                context.getString(R.string.local_models_download_failed, download.message.orEmpty()),
                LocalModelNoticeTone.ERROR,
            ).also { startAfterRecommendationDownload = false }
            LocalModelDownloadStage.CANCELLED -> noticeOf(
                context.getString(R.string.local_models_download_cancelled),
                LocalModelNoticeTone.INFO,
            ).also { startAfterRecommendationDownload = false }
            LocalModelDownloadStage.QUEUED,
            LocalModelDownloadStage.DOWNLOADING -> Unit
            LocalModelDownloadStage.VERIFYING -> noticeOf(
                context.getString(R.string.local_models_download_verifying),
                LocalModelNoticeTone.INFO,
            )
            null -> Unit
        }
    }
    val bindingRecord = bindingRecordId?.let { id -> models.firstOrNull { it.id == id } }
    if (bindingRecord != null) {
        LocalModelBindingScreen(
            record = bindingRecord,
            snapshot = performanceSnapshot,
            saving = bindingSaving,
            autoAttaching = attachingId == bindingRecord.id,
            serverError = bindingError,
            onBack = {
                if (!bindingSaving) {
                    bindingRecordId = null
                    bindingError = null
                }
            },
            onAutoAttach = {
                bindingError = null
                attachExternally(bindingRecord) { message, success ->
                    if (success) {
                        bindingRecordId = null
                    } else {
                        bindingError = message
                    }
                }
            },
            onConfirm = { endpoint, serverModelId, contextWindow, tools, vision ->
                if (!bindingSaving) {
                    bindingSaving = true
                    bindingError = null
                    scope.launch {
                        try {
                            LocalModelRepository.bindToProvider(
                                recordId = bindingRecord.id,
                                endpoint = endpoint,
                                serverModelId = serverModelId,
                                contextWindow = contextWindow,
                                supportsTools = tools,
                                supportsVision = vision,
                            )
                            bindingRecordId = null
                            noticeOf(context.getString(R.string.local_models_bind_success), LocalModelNoticeTone.SUCCESS)
                            LocalModelRepository.refresh()
                        } catch (failure: Exception) {
                            bindingError = failure.message ?: context.getString(R.string.local_models_bind_failed)
                        } finally {
                            bindingSaving = false
                        }
                    }
                }
            },
        )
        return
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || importing || importingMnnFolder) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            try {
                val record = withContext(Dispatchers.IO) { LocalModelRepository.importUri(context, uri) }
                finishImport(record)
            } catch (failure: Exception) {
                noticeOf(
                    context.getString(
                        R.string.local_models_import_failed,
                        failure.message ?: failure.javaClass.simpleName,
                    ),
                    LocalModelNoticeTone.ERROR,
                )
            } finally {
                importing = false
            }
        }
    }

    // MNN LLMs are directory packages, so they need the tree picker instead of OpenDocument.
    val mnnFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null || importingMnnFolder || importing) return@rememberLauncherForActivityResult
        importingMnnFolder = true
        scope.launch {
            try {
                val record = withContext(Dispatchers.IO) {
                    LocalModelRepository.importMnnTreeUri(context, uri)
                }
                finishImport(record)
            } catch (failure: Exception) {
                noticeOf(
                    context.getString(
                        R.string.local_models_import_failed,
                        failure.message ?: failure.javaClass.simpleName,
                    ),
                    LocalModelNoticeTone.ERROR,
                )
            } finally {
                importingMnnFolder = false
            }
        }
    }

    val activeDownload = download.stage in setOf(
        LocalModelDownloadStage.QUEUED,
        LocalModelDownloadStage.DOWNLOADING,
        LocalModelDownloadStage.VERIFYING,
    )
    val activeDownloadPresetId = if (activeDownload) {
        LocalModelRepository.presetFor(download.sourceUrl.orEmpty())?.id
    } else {
        null
    }
    // A catalog row is a preset, while the installed catalog stores content-addressed records.
    // Resolve both through the same immutable URL/hash/file identity used by the downloader.
    val installedPresetRecords = models.mapNotNull { record ->
        LocalModelRepository.presetForRecord(record)?.let { preset -> preset.id to record }
    }.toMap()
    val runtimeRecord = models.firstOrNull { it.id == selectedRuntimeId }
    val runtimeRunning = runtime.state in setOf(
        LocalModelRuntimeState.STARTING,
        LocalModelRuntimeState.READY,
        LocalModelRuntimeState.GENERATING,
    )
    val parameterModels = models.filter {
        localModelType(it) in setOf(LocalModelTypeFilter.TEXT, LocalModelTypeFilter.VISION)
    }
    val runtimeParameterRecord = parameterModels.firstOrNull { it.id == selectedRuntimeId }
        ?: parameterModels.firstOrNull { it.id == runtime.recordId }
    val overviewRecord = runtimeRecord
        ?: models.firstOrNull { candidate ->
            val kind = localSpeechKind(candidate) ?: return@firstOrNull false
            speechRuntime[kind]?.recordId == candidate.id &&
                speechRuntime[kind]?.phase in setOf(
                    LocalSpeechRuntimePhase.STARTING,
                    LocalSpeechRuntimePhase.READY,
                    LocalSpeechRuntimePhase.FAILED,
                )
        }
        ?: models.firstOrNull { it.id == runtime.recordId }
        ?: models.firstOrNull { it.sourceUrl == LocalModelPreset.Qwen3DynamicInt4.url }
        ?: models.firstOrNull {
            localModelType(it) in setOf(LocalModelTypeFilter.TEXT, LocalModelTypeFilter.VISION)
        }
        ?: models.firstOrNull {
            it.format in setOf(LocalModelFormat.LITERT_LM, LocalModelFormat.GGUF, LocalModelFormat.MNN)
        }
        ?: models.firstOrNull()

    MiuixScaffoldPage(
        title = stringResource(R.string.local_models_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = { scope.launch { LocalModelRepository.refresh() } }) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.local_models_refresh))
            }
        },
    ) {
        item(key = "local_model_tabs") {
            TabRow(
                tabs = listOf(
                    stringResource(R.string.local_models_tab_overview),
                    stringResource(R.string.local_models_tab_models),
                    stringResource(R.string.local_models_tab_runtime),
                ),
                selectedTabIndex = tab,
                onTabSelected = { tab = it },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        notice?.let { message ->
            item(key = "local_model_notice") {
                LocalModelNotice(
                    message = message,
                    tone = noticeTone,
                    actionLabel = if (noticeOffersRuntimeSettings) {
                        stringResource(R.string.local_models_adjust_runtime)
                    } else {
                        null
                    },
                    onAction = if (noticeOffersRuntimeSettings) {
                        { tab = TAB_RUNTIME }
                    } else {
                        null
                    },
                )
            }
        }
        when (tab) {
            TAB_OVERVIEW -> {
                item(key = "local_model_launch") {
                    LocalModelLaunchCard(
                        models = models,
                        record = overviewRecord,
                        runtime = runtime,
                        speechRuntime = overviewRecord?.let { localSpeechKind(it) }?.let(speechRuntime::get),
                        snapshot = performanceSnapshot,
                        downloadActive = activeDownload,
                        busy = runtimeBusy,
                        onSelectModel = { selectedRuntimeId = it.id },
                        onBackend = { target, backend ->
                            saveRuntimeSettings(target) { it.copy(backend = backend) }
                        },
                        onStart = ::startRecord,
                        onStop = ::stopRecord,
                        onCancelDownload = { LocalModelRepository.cancelDownload(context) },
                        onDownloadAndStart = {
                            if (!activeDownload) {
                                startAfterRecommendationDownload = true
                                startDownload(LocalModelPreset.Qwen3DynamicInt4, LocalModelPreset.Qwen3DynamicInt4.url)
                            }
                        },
                    )
                }
                item(key = "local_model_capabilities") {
                    LocalCapabilityExpansionCard(
                        models = models,
                        speech = speechSettings,
                        speechRuntime = speechRuntime,
                    )
                }
            }
            TAB_MODELS -> {
                val modelTypeFilter = LocalModelTypeFilter.entries[modelTypeFilterIndex]
                val visibleModels = models.filter { localModelMatchesType(it, modelTypeFilter) }
                item(key = "model_library_intro") {
                    LocalModelLibraryIntro(
                        selectedType = modelTypeFilter,
                        onSelectType = { modelTypeFilterIndex = it.ordinal },
                    )
                }
                item(key = "model_library_catalog") {
                    LocalPremiumModelsCard(
                        downloadInProgress = activeDownload,
                        activeDownloadPresetId = activeDownloadPresetId,
                        installedRecords = installedPresetRecords,
                        busy = runtimeBusy,
                        typeFilter = modelTypeFilter,
                        presets = LocalModelPreset.all,
                        onDownload = { preset -> startDownload(preset, preset.url) },
                        onStart = ::startRecord,
                    )
                }
                if (download.stage != null) {
                    item(key = "local_model_download_progress") {
                        LocalModelDownloadCard(
                            download = download,
                            onCancel = { LocalModelRepository.cancelDownload(context) },
                            onDismiss = { LocalModelRepository.clearFinishedDownload(context) },
                            onRetry = {
                                download.sourceUrl?.let { sourceUrl ->
                                    val preset = LocalModelRepository.presetFor(sourceUrl) ?: LocalModelPreset.Qwen3DynamicInt4.copy(
                                        id = "custom-download",
                                        url = sourceUrl,
                                        expectedSizeBytes = null,
                                        expectedSha256 = null,
                                        license = "by-source",
                                        inputModalities = listOf("text"),
                                        outputModalities = listOf("text"),
                                    )
                                    startDownload(preset, sourceUrl)
                                }
                            },
                        )
                    }
                }
                item(key = "installed_header") {
                    SmallTitle(stringResource(R.string.local_models_models_installed_title) + " · " + visibleModels.size)
                }
                if (visibleModels.isEmpty()) {
                    item(key = "local_models_empty") {
                        Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            BasicComponent(
                                title = stringResource(R.string.local_models_empty),
                                summary = stringResource(R.string.local_models_empty_summary),
                                startAction = { PreferenceIcon(Icons.Rounded.Storage) },
                            )
                        }
                    }
                }
                visibleModels.forEach { record ->
                    item(key = record.id) {
                        LocalModelRecordCard(
                            record = record,
                            running = runtime.recordId == record.id && runtimeRunning,
                            speechBound = when (localModelType(record)) {
                                LocalModelTypeFilter.TTS -> speechSettings.localTtsModelId == record.id
                                LocalModelTypeFilter.ASR -> speechSettings.localAsrModelId == record.id
                                else -> false
                            },
                            speechRuntime = when (localModelType(record)) {
                                LocalModelTypeFilter.TTS -> speechRuntime[io.github.mangi.eta.agent.voice.LocalSpeechKind.TTS]
                                LocalModelTypeFilter.ASR -> speechRuntime[io.github.mangi.eta.agent.voice.LocalSpeechKind.ASR]
                                else -> null
                            },
                            busy = runtimeBusy && selectedRuntimeId == record.id,
                            onStart = { startRecord(record) },
                            onStop = { stopRecord(record) },
                        )
                    }
                }
                item(key = "local_model_add_models") {
                    LocalAddModelsSection(
                        importing = importing,
                        importingMnnFolder = importingMnnFolder,
                        onImport = { picker.launch(arrayOf("*/*")) },
                        onImportMnnFolder = { mnnFolderPicker.launch(null) },
                    )
                }
                item(key = "source_custom") {
                    LocalCustomSourceCard(
                        expanded = sourceExpanded,
                        onToggle = { sourceExpanded = !sourceExpanded },
                        downloadUrl = downloadUrl,
                        onUrlChange = { downloadUrl = it.take(4_000) },
                        activeDownload = activeDownload,
                        onRestoreRecommended = {
                            downloadUrl = LocalModelPreset.Qwen3DynamicInt4.url
                            LocalModelRepository.setDefaultDownloadUrl(context, downloadUrl)
                        },
                        onSaveAddress = {
                            runCatching { LocalModelRepository.validateDownloadUrl(downloadUrl) }
                                .onSuccess { normalized ->
                                    downloadUrl = normalized
                                    LocalModelRepository.setDefaultDownloadUrl(context, normalized)
                                    noticeOf(context.getString(R.string.local_models_address_saved), LocalModelNoticeTone.SUCCESS)
                                }
                                .onFailure { failure ->
                                    noticeOf(
                                        failure.message ?: context.getString(R.string.local_models_address_invalid),
                                        LocalModelNoticeTone.ERROR,
                                    )
                                }
                        },
                        onDownload = {
                            val preset = LocalModelRepository.presetFor(downloadUrl) ?: LocalModelPreset.Qwen3DynamicInt4.copy(
                                id = "custom-download",
                                url = downloadUrl,
                                expectedSizeBytes = null,
                                expectedSha256 = null,
                                license = "by-source",
                                inputModalities = listOf("text"),
                                outputModalities = listOf("text"),
                            )
                            startDownload(preset, downloadUrl)
                        },
                    )
                }
            }
            TAB_RUNTIME -> {
                item(key = "advanced_runtime_header") {
                    SmallTitle(stringResource(R.string.local_models_section_runtime))
                }
                item(key = "runtime_model") {
                    LocalRuntimeModelSelectorCard(
                        models = parameterModels,
                        record = runtimeParameterRecord,
                        onSelect = { selectedRuntimeId = it.id },
                    )
                }
                runtimeParameterRecord?.let { record ->
                    item(key = "runtime_parameters") {
                        LocalRuntimeParametersCard(
                            record = record,
                            onSettings = { transform -> saveRuntimeSettings(record, transform) },
                        )
                    }
                    item(key = "runtime_policy") {
                        LocalRuntimePolicyCard(
                            record = record,
                            snapshot = performanceSnapshot,
                            onSettings = { transform -> saveRuntimeSettings(record, transform) },
                        )
                    }
                }
            }
        }
        item(key = "local_model_bottom_space") { Spacer(Modifier.height(16.dp)) }
    }

    deletingRecordId?.let { id -> models.firstOrNull { it.id == id } }?.let { record ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.local_models_delete_title),
            summary = stringResource(R.string.local_models_delete_body, record.displayName),
            onDismissRequest = { deletingRecordId = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.local_models_delete),
                destructive = true,
                onCancel = { deletingRecordId = null },
                onConfirm = {
                    deletingRecordId = null
                    scope.launch {
                        try {
                            when (record.format) {
                                LocalModelFormat.LITERT_LM -> LiteRtLmRuntimeManager.stop(record.id)
                                LocalModelFormat.GGUF -> LlamaCppRuntimeManager.stop(record.id)
                                LocalModelFormat.MNN -> MnnRuntimeManager.stop(record.id)
                                else -> Unit
                            }
                            LocalSpeechRuntime.release(record.id)
                            val speech = SpeechSettingsRepository.settings()
                            if (speech.localTtsModelId == record.id || speech.localAsrModelId == record.id) {
                                val removingAsr = speech.localAsrModelId == record.id
                                SpeechSettingsRepository.save(
                                    speech.copy(
                                        tts = if (speech.localTtsModelId == record.id) TtsProvider.NONE else speech.tts,
                                        recognitionEnabled = if (removingAsr) false else speech.recognitionEnabled,
                                        localTtsModelId = if (speech.localTtsModelId == record.id) "" else speech.localTtsModelId,
                                        localAsrModelId = if (removingAsr) "" else speech.localAsrModelId,
                                    ),
                                )
                            }
                            withContext(Dispatchers.IO) { LocalModelRepository.delete(context, record.id) }
                            noticeOf(context.getString(R.string.local_models_delete_success), LocalModelNoticeTone.SUCCESS)
                        } catch (failure: Exception) {
                            noticeOf(
                                failure.message ?: context.getString(R.string.local_models_delete_failed),
                                LocalModelNoticeTone.ERROR,
                            )
                        }
                    }
                },
            )
        }
    }
}
internal suspend fun discoverAndBindLocalService(
    record: LocalModelRecord,
    contextWindow: Int,
): String {
    var lastFailure: Exception? = null
    for ((label, endpoint) in LocalServicePresets) {
        try {
            val discovery = LocalModelPerformanceMonitor.discoverModels(endpoint)
            if (discovery.modelIds.isEmpty()) continue
            val serverModelId = chooseLocalServerModelId(discovery.modelIds, record.displayName)
            LocalModelRepository.bindToProvider(
                recordId = record.id,
                endpoint = endpoint,
                serverModelId = serverModelId,
                contextWindow = contextWindow,
                supportsTools = record.supportsTools,
                supportsVision = record.supportsVision,
            )
            return "$label · $serverModelId"
        } catch (failure: Exception) {
            lastFailure = failure
        }
    }
    throw lastFailure ?: IllegalStateException("local service unavailable")
}

internal fun chooseLocalServerModelId(modelIds: List<String>, displayName: String): String {
    if (modelIds.isEmpty()) return ""
    val tokens = displayName.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 }
    val scores = modelIds.associateWith { id ->
        val lower = id.lowercase()
        tokens.count { token -> lower.contains(token) }
    }
    val best = scores.maxByOrNull { it.value }
    return if (best != null && best.value > 0) best.key else modelIds.first()
}

@Composable
private fun LocalModelLaunchCard(
    models: List<LocalModelRecord>,
    record: LocalModelRecord?,
    runtime: LocalModelRuntimeSnapshot,
    speechRuntime: LocalSpeechRuntimeSnapshot?,
    snapshot: LocalDevicePerformanceSnapshot,
    downloadActive: Boolean,
    busy: Boolean,
    onSelectModel: (LocalModelRecord) -> Unit,
    onBackend: (LocalModelRecord, LocalRuntimeBackendPolicy) -> Unit,
    onStart: (LocalModelRecord) -> Unit,
    onStop: (LocalModelRecord) -> Unit,
    onCancelDownload: () -> Unit,
    onDownloadAndStart: () -> Unit,
) {
    val total = models.sumOf(LocalModelRecord::sizeBytes)
    val speechKind = record?.let(::localSpeechKind)
    val speechSnapshot = speechRuntime?.takeIf { record != null && it.recordId == record.id }
    val speechState = when (speechSnapshot?.phase) {
        LocalSpeechRuntimePhase.STARTING -> LocalModelRuntimeState.STARTING
        LocalSpeechRuntimePhase.READY -> LocalModelRuntimeState.READY
        LocalSpeechRuntimePhase.FAILED -> LocalModelRuntimeState.FAILED
        else -> LocalModelRuntimeState.STOPPED
    }
    val visibleRuntimeState = if (speechKind != null) speechState else runtime.state
    val active = if (speechKind != null) {
        speechSnapshot?.recordId == record?.id && speechSnapshot?.phase in setOf(
            LocalSpeechRuntimePhase.STARTING,
            LocalSpeechRuntimePhase.READY,
        )
    } else {
        record != null && runtime.recordId == record.id && runtime.state in setOf(
            LocalModelRuntimeState.STARTING,
            LocalModelRuntimeState.READY,
            LocalModelRuntimeState.GENERATING,
        )
    }
    val npuTarget = record?.let { NpuModelTargeting.targetOf(it.fileName) }
    val npuModel = record?.format == LocalModelFormat.LITERT_LM && npuTarget != null
    val npuCompatible = npuModel && NpuModelTargeting.matches(npuTarget, Build.SOC_MODEL)
    val backendOptions = when {
        record == null -> emptyList()
        speechKind != null -> listOf(
            LocalRuntimeBackendPolicy.AUTO,
            LocalRuntimeBackendPolicy.CPU,
            LocalRuntimeBackendPolicy.GPU,
            LocalRuntimeBackendPolicy.NPU,
        )
        npuCompatible -> listOf(LocalRuntimeBackendPolicy.NPU)
        else -> listOf(
            LocalRuntimeBackendPolicy.AUTO,
            LocalRuntimeBackendPolicy.GPU,
            LocalRuntimeBackendPolicy.CPU,
        )
    }
    val automaticLabel = stringResource(R.string.local_models_runtime_backend_auto)
    val cpuLabel = stringResource(R.string.local_models_runtime_backend_cpu)
    val gpuLabel = stringResource(R.string.local_models_runtime_backend_gpu)
    val npuLabel = stringResource(R.string.local_models_runtime_backend_npu)
    val backendLabels = backendOptions.map { backend ->
        when (backend) {
            LocalRuntimeBackendPolicy.AUTO -> automaticLabel
            LocalRuntimeBackendPolicy.CPU -> cpuLabel
            LocalRuntimeBackendPolicy.GPU -> gpuLabel
            LocalRuntimeBackendPolicy.NPU -> npuLabel
        }
    }
    val limited = snapshot.thermalStatus >= 2 ||
        snapshot.powerSaveMode ||
        snapshot.availableRamBytes < 2L * 1024 * 1024 * 1024

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EtaIconTile(
                    icon = Icons.Rounded.Memory,
                    tone = EtaIconTone.MODEL,
                    size = 44.dp,
                    glyphSize = 23.dp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stringResource(R.string.local_models_overview),
                        style = MiuixTheme.textStyles.headline2,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                LocalRuntimeStatusBadge(
                    state = visibleRuntimeState,
                    running = active,
                    hasModel = record != null,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .squircleSurface(MiuixTheme.colorScheme.surfaceContainerHigh, 14.dp)
                    .padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LocalLaunchMetric(
                    value = models.size.toString(),
                    label = stringResource(R.string.local_models_metric_installed),
                    icon = Icons.Rounded.Storage,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(width = 1.dp, height = 34.dp).background(MiuixTheme.colorScheme.outline.copy(alpha = .10f)))
                LocalLaunchMetric(
                    value = formatLocalModelBytes(total),
                    label = stringResource(R.string.local_models_metric_storage),
                    icon = Icons.Rounded.Download,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(width = 1.dp, height = 34.dp).background(MiuixTheme.colorScheme.outline.copy(alpha = .10f)))
                LocalLaunchMetric(
                    value = formatLocalModelBytes(snapshot.availableRamBytes),
                    label = stringResource(R.string.local_models_metric_available_memory),
                    icon = Icons.Rounded.Speed,
                    modifier = Modifier.weight(1f),
                )
            }

            if (models.isNotEmpty()) {
                DialogChoicePreference(
                    title = stringResource(R.string.local_models_launch_model),
                    value = record?.displayName ?: stringResource(R.string.local_models_primary_recommendation),
                    options = models.map(LocalModelRecord::displayName),
                    selectedIndex = models.indexOfFirst { it.id == record?.id }.coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        models.getOrNull(index)?.let(onSelectModel)
                    },
                )
            }

            if (record != null && backendOptions.isNotEmpty()) {
                DialogChoicePreference(
                    title = stringResource(R.string.local_models_launch_backend),
                    value = backendLabels.getOrElse(backendOptions.indexOf(record.runtimeSettings.backend).coerceAtLeast(0)) { automaticLabel },
                    options = backendLabels,
                    selectedIndex = backendOptions.indexOf(record.runtimeSettings.backend).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        backendOptions.getOrNull(index)?.let { backend -> onBackend(record, backend) }
                    },
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth().squircleSurface(
                    MiuixTheme.colorScheme.surfaceContainerHigh,
                    18.dp,
                ).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PreferenceIcon(if (active) Icons.Rounded.PlayArrow else Icons.Rounded.Memory)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = record?.displayName ?: stringResource(R.string.local_models_primary_recommendation),
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = record?.let {
                                stringResource(
                                    R.string.local_models_primary_installed_summary,
                                    it.format.name.replace('_', '-'),
                                    formatLocalModelBytes(it.sizeBytes),
                                )
                            } ?: stringResource(R.string.local_models_primary_missing_summary),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2,
                        )
                    }
                    if (downloadActive) {
                        LocalCompactAction(
                            icon = Icons.Rounded.Stop,
                            description = stringResource(R.string.local_models_cancel_download),
                            enabled = !busy,
                            danger = true,
                            onClick = onCancelDownload,
                        )
                    } else if (active) {
                        LocalCompactAction(
                            icon = Icons.Rounded.Stop,
                            description = if (busy) stringResource(R.string.local_models_runtime_stopping)
                            else stringResource(R.string.local_models_runtime_stop),
                            enabled = !busy,
                            danger = true,
                            onClick = { onStop(requireNotNull(record)) },
                        )
                    } else {
                        LocalCompactAction(
                            icon = if (record == null) Icons.Rounded.CloudDownload else Icons.Rounded.PlayArrow,
                            description = when {
                                busy -> stringResource(R.string.local_models_runtime_starting)
                                record == null -> stringResource(R.string.local_models_download_and_start)
                                else -> stringResource(R.string.local_models_start_now)
                            },
                            enabled = !busy,
                            prominent = true,
                            onClick = {
                                if (record != null) onStart(record) else onDownloadAndStart()
                            },
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TagChip(snapshot.socLabel)
                TagChip(stringResource(R.string.local_models_android, snapshot.androidSdk))
                TagChip(
                    stringResource(
                        if (limited) R.string.local_models_performance_limited
                        else R.string.local_models_performance_ready,
                    ),
                    if (limited) TagChipTone.Normal else TagChipTone.Emphasized,
                )
            }
            val runtimeMessage = if (speechKind != null) speechSnapshot?.message else runtime.message
            if (visibleRuntimeState == LocalModelRuntimeState.FAILED && !runtimeMessage.isNullOrBlank()) {
                Text(
                    text = runtimeMessage,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun LocalLaunchMetric(
    value: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = MiuixTheme.colorScheme.primary)
        Text(
            value,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 15.sp, lineHeight = 20.sp),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LocalRuntimeStatusBadge(
    state: LocalModelRuntimeState,
    running: Boolean,
    hasModel: Boolean,
) {
    val color = when {
        running -> StatusSuccess
        state == LocalModelRuntimeState.FAILED -> MiuixTheme.colorScheme.error
        hasModel -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val label = when {
        state == LocalModelRuntimeState.STARTING -> stringResource(R.string.local_models_runtime_state_starting)
        state == LocalModelRuntimeState.GENERATING -> stringResource(R.string.local_models_runtime_state_generating)
        state == LocalModelRuntimeState.FAILED -> stringResource(R.string.local_models_runtime_state_failed)
        running -> stringResource(R.string.local_models_runtime_state_ready)
        hasModel -> stringResource(R.string.local_models_runtime_state_ready)
        else -> stringResource(R.string.local_models_runtime_state_not_installed)
    }
    Row(
        modifier = Modifier
            .squircleSurface(color.copy(alpha = .10f), 9.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp),
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

@Composable
private fun LocalCompactAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    prominent: Boolean = false,
    danger: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) .96f else 1f,
        animationSpec = tween(EtaMotion.press),
        label = "local-model-action-press",
    )
    val tint = when {
        danger -> MiuixTheme.colorScheme.error
        prominent -> MiuixTheme.colorScheme.onPrimary
        else -> MiuixTheme.colorScheme.primary
    }
    val background = when {
        prominent -> MiuixTheme.colorScheme.primary
        danger -> MiuixTheme.colorScheme.error.copy(alpha = .10f)
        else -> MiuixTheme.colorScheme.secondaryContainer
    }
    Row(
        modifier = Modifier
            .height(EtaUiMetrics.statusMinHeight + 8.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .squircleSurface(background, 10.dp)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = etaRippleIndication(),
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            icon,
            description,
            tint = tint.copy(alpha = if (enabled) 1f else .45f),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = description,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp),
            fontWeight = FontWeight.Medium,
            color = tint.copy(alpha = if (enabled) 1f else .45f),
            maxLines = 1,
        )
    }
}

@Composable
private fun LocalModelLibraryIntro(
    selectedType: LocalModelTypeFilter,
    onSelectType: (LocalModelTypeFilter) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(38.dp).squircleSurface(
                        MiuixTheme.colorScheme.primary.copy(alpha = 0.1f),
                        12.dp,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Storage,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stringResource(R.string.local_models_models_library_title),
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                LocalModelTypeFilter.entries.forEach { filter ->
                    EtaChoiceChip(
                        text = localModelTypeLabel(filter),
                        selected = filter == selectedType,
                        onClick = { onSelectType(filter) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalCapabilityExpansionCard(
    models: List<LocalModelRecord>,
    speech: SpeechSettings,
    speechRuntime: Map<io.github.mangi.eta.agent.voice.LocalSpeechKind, LocalSpeechRuntimeSnapshot>,
) {
    val asr = models.firstOrNull { it.id == speech.localAsrModelId }
    val tts = models.firstOrNull { it.id == speech.localTtsModelId }
    val asrReady = speech.asr == AsrProvider.LOCAL && asr != null
    val ttsReady = speech.tts == TtsProvider.LOCAL && tts != null
    val visionReady = models.any { localModelType(it) == LocalModelTypeFilter.VISION }
    val asrRuntime = speechRuntime[io.github.mangi.eta.agent.voice.LocalSpeechKind.ASR]
    val ttsRuntime = speechRuntime[io.github.mangi.eta.agent.voice.LocalSpeechKind.TTS]
    ProviderSection(title = stringResource(R.string.local_models_capability_expansion_title)) {
        BasicComponent(
            title = stringResource(R.string.local_models_capability_asr_title),
            summary = asr?.displayName?.let { name ->
                if (asrRuntime?.phase == LocalSpeechRuntimePhase.READY) "$name · ${asrRuntime.activeBackend}" else name
            },
            startAction = { PreferenceIcon(Icons.Rounded.Mic) },
            endActions = {
                TagChip(
                    if (asrReady) stringResource(R.string.local_models_capability_connected)
                    else stringResource(R.string.local_models_capability_not_connected),
                    if (asrReady) TagChipTone.Emphasized else TagChipTone.Normal,
                )
            },
        )
        BasicComponent(
            title = stringResource(R.string.local_models_capability_tts_title),
            summary = tts?.displayName?.let { name ->
                if (ttsRuntime?.phase == LocalSpeechRuntimePhase.READY) "$name · ${ttsRuntime.activeBackend}" else name
            },
            startAction = { PreferenceIcon(Icons.Rounded.GraphicEq) },
            endActions = {
                TagChip(
                    if (ttsReady) stringResource(R.string.local_models_capability_connected)
                    else stringResource(R.string.local_models_capability_not_connected),
                    if (ttsReady) TagChipTone.Emphasized else TagChipTone.Normal,
                )
            },
        )
        BasicComponent(
            title = stringResource(R.string.local_models_capability_vision_title),
            startAction = { PreferenceIcon(Icons.Rounded.Verified) },
            endActions = {
                TagChip(
                    if (visionReady) stringResource(R.string.local_models_capability_available)
                    else stringResource(R.string.local_models_capability_not_connected),
                    if (visionReady) TagChipTone.Emphasized else TagChipTone.Normal,
                )
            },
        )
    }
}

@Composable
private fun LocalModelNotice(
    message: String,
    tone: LocalModelNoticeTone,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val (icon, tint) = when (tone) {
        LocalModelNoticeTone.INFO -> Icons.Rounded.Security to MiuixTheme.colorScheme.primary
        LocalModelNoticeTone.SUCCESS -> Icons.Rounded.CheckCircle to MiuixTheme.colorScheme.primary
        LocalModelNoticeTone.ERROR -> Icons.Rounded.WarningAmber to MiuixTheme.colorScheme.error
    }
    Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(icon, null, tint = tint)
                Text(
                    message,
                    style = MiuixTheme.textStyles.body2,
                    color = if (tone == LocalModelNoticeTone.ERROR) tint else MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            if (actionLabel != null && onAction != null) {
                TextButton(
                    text = actionLabel,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onAction,
                )
            }
        }
    }
}
@Composable
private fun LocalModelDownloadCard(
    download: io.github.mangi.eta.data.repository.LocalModelDownloadState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val active = download.stage in setOf(
        LocalModelDownloadStage.QUEUED,
        LocalModelDownloadStage.DOWNLOADING,
        LocalModelDownloadStage.VERIFYING,
    )
    Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    when (download.stage) {
                        LocalModelDownloadStage.COMPLETED -> Icons.Rounded.CheckCircle
                        LocalModelDownloadStage.FAILED -> Icons.Rounded.WarningAmber
                        else -> Icons.Rounded.Download
                    },
                    null,
                    tint = when (download.stage) {
                        LocalModelDownloadStage.FAILED -> MiuixTheme.colorScheme.error
                        else -> MiuixTheme.colorScheme.primary
                    },
                )
                Text(
                    text = downloadTitle(download),
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    InfiniteProgressIndicator(size = 18.dp)
                    LocalCompactAction(
                        icon = Icons.Rounded.Stop,
                        description = stringResource(R.string.local_models_cancel_download),
                        danger = true,
                        onClick = onCancel,
                    )
                } else {
                    if (download.sourceUrl != null &&
                        download.stage in setOf(LocalModelDownloadStage.FAILED, LocalModelDownloadStage.CANCELLED)
                    ) {
                        LocalCompactAction(
                            icon = Icons.Rounded.Refresh,
                            description = stringResource(R.string.local_models_retry_download),
                            onClick = onRetry,
                        )
                    }
                    LocalCompactAction(
                        icon = Icons.Rounded.Close,
                        description = stringResource(R.string.local_models_clear_download),
                        onClick = onDismiss,
                    )
                }
            }
            val progress = download.progress
            if (active && download.stage != LocalModelDownloadStage.VERIFYING && progress != null) {
                Box(
                    Modifier.fillMaxWidth().height(5.dp).squircleSurface(
                        color = MiuixTheme.colorScheme.secondaryContainer,
                        cornerRadius = 3.dp,
                    ),
                ) {
                    Box(
                        Modifier.fillMaxWidth(progress).height(5.dp).squircleSurface(
                            color = MiuixTheme.colorScheme.primary,
                            cornerRadius = 3.dp,
                        ),
                    )
                }
            }
            if (download.bytesDownloaded > 0 ||
                download.message != null ||
                download.stage == LocalModelDownloadStage.VERIFYING
            ) {
                Text(
                    text = when (download.stage) {
                        LocalModelDownloadStage.VERIFYING -> stringResource(R.string.local_models_download_verifying)
                        LocalModelDownloadStage.FAILED,
                        LocalModelDownloadStage.CANCELLED -> download.message.orEmpty()
                        else -> stringResource(
                            R.string.local_models_download_progress,
                            formatLocalModelBytes(download.bytesDownloaded),
                            download.totalBytes?.let(::formatLocalModelBytes) ?: "—",
                        )
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (download.stage == LocalModelDownloadStage.FAILED) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
        }
    }
}

@Composable
private fun LocalModelRecordCard(
    record: LocalModelRecord,
    running: Boolean,
    speechBound: Boolean,
    speechRuntime: LocalSpeechRuntimeSnapshot?,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val builtIn = record.format in setOf(LocalModelFormat.LITERT_LM, LocalModelFormat.GGUF, LocalModelFormat.MNN)
    val modelType = localModelType(record)
    val chatCapable = modelType == LocalModelTypeFilter.TEXT || modelType == LocalModelTypeFilter.VISION
    val speechRunning = speechRuntime?.recordId == record.id &&
        speechRuntime.phase in setOf(LocalSpeechRuntimePhase.READY, LocalSpeechRuntimePhase.STARTING)
    val active = running || speechRunning
    val typeIcon = when (modelType) {
        LocalModelTypeFilter.VISION -> Icons.Rounded.Image
        LocalModelTypeFilter.TTS -> Icons.Rounded.GraphicEq
        LocalModelTypeFilter.ASR -> Icons.Rounded.Mic
        LocalModelTypeFilter.EMBEDDING -> Icons.Rounded.Hub
        LocalModelTypeFilter.TEXT -> Icons.Rounded.Memory
    }
    Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(36.dp).squircleSurface(
                        MiuixTheme.colorScheme.secondaryContainer,
                        11.dp,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(typeIcon, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        record.displayName,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            R.string.local_models_format,
                            record.format.name.replace('_', '-'),
                            formatLocalModelBytes(record.sizeBytes),
                        ),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    LocalRuntimeSignal(
                        if (active) LocalModelRuntimeState.READY else LocalModelRuntimeState.STOPPED,
                    )
                    Text(
                        stringResource(
                            when {
                                running -> R.string.local_models_runtime_running
                                speechRunning -> R.string.local_models_runtime_running
                                speechBound -> R.string.local_models_speech_bound
                                else -> R.string.local_models_badge_installed
                            },
                        ),
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (active) StatusSuccess else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TagChip(localModelTypeLabel(modelType), TagChipTone.Emphasized)
                if (chatCapable && record.contextWindow > 0) {
                    TagChip(stringResource(R.string.local_models_context_chip, formatCompactLocalContext(record.contextWindow)))
                }
                if (record.lastVerifiedAt != null) {
                    TagChip(stringResource(R.string.local_models_verified), TagChipTone.Emphasized)
                }
            }
            if (record.lastBenchmark != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TagChip(
                        stringResource(R.string.local_models_benchmark_latency, record.lastBenchmark.latencyMs),
                        TagChipTone.Emphasized,
                    )
                    record.lastBenchmark.tokensPerSecond?.let { speed ->
                        TagChip(stringResource(R.string.local_models_benchmark_throughput, speed))
                    }
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LocalCompactAction(
                    icon = when {
                        running || speechRunning -> Icons.Rounded.Stop
                        chatCapable && builtIn -> Icons.Rounded.PlayArrow
                        chatCapable -> Icons.Rounded.Dns
                        else -> Icons.Rounded.Settings
                    },
                    description = when {
                        busy -> stringResource(R.string.local_models_runtime_starting)
                        running || speechRunning -> stringResource(R.string.local_models_runtime_stop)
                        speechBound && modelType == LocalModelTypeFilter.TTS -> "已设为播报模型"
                        speechBound && modelType == LocalModelTypeFilter.ASR -> "已设为识别模型"
                        chatCapable && builtIn -> stringResource(R.string.local_models_start_now)
                        chatCapable -> stringResource(R.string.local_models_start_and_attach)
                        modelType == LocalModelTypeFilter.TTS -> "设为播报模型"
                        modelType == LocalModelTypeFilter.ASR -> "设为识别模型"
                        else -> localModelTypeLabel(modelType)
                    },
                    enabled = !busy,
                    prominent = !running && !speechRunning && !speechBound,
                    danger = running || speechRunning,
                    onClick = when {
                        running || speechRunning -> onStop
                        modelType == LocalModelTypeFilter.TTS || modelType == LocalModelTypeFilter.ASR -> onStart
                        chatCapable && builtIn -> onStart
                        else -> onStart
                    },
                )
            }
        }
    }
}

@Composable
private fun LocalAddModelsSection(
    importing: Boolean,
    importingMnnFolder: Boolean,
    onImport: () -> Unit,
    onImportMnnFolder: () -> Unit,
) {
    ProviderSection(title = stringResource(R.string.local_models_models_add_title)) {
        ArrowPreference(
            title = "导入模型文件",
            summary = if (importing) stringResource(R.string.local_models_importing) else "LiteRT-LM · GGUF · MNN",
            enabled = !importing,
            startAction = {
                if (importing) InfiniteProgressIndicator(size = 22.dp)
                else PreferenceIcon(Icons.Rounded.FileOpen)
            },
            onClick = onImport,
        )
        ArrowPreference(
            title = "导入 MNN 目录",
            summary = if (importingMnnFolder) stringResource(R.string.local_models_importing_mnn) else "MNN 模型包",
            enabled = !importingMnnFolder,
            startAction = {
                if (importingMnnFolder) InfiniteProgressIndicator(size = 22.dp)
                else PreferenceIcon(Icons.Rounded.Folder)
            },
            onClick = onImportMnnFolder,
        )
    }
}

@Composable
private fun LocalPremiumModelsCard(
    downloadInProgress: Boolean,
    activeDownloadPresetId: String?,
    installedRecords: Map<String, LocalModelRecord>,
    busy: Boolean,
    typeFilter: LocalModelTypeFilter,
    presets: List<LocalModelPreset>,
    onDownload: (LocalModelPreset) -> Unit,
    onStart: (LocalModelRecord) -> Unit,
) {
    ProviderSection(
        title = if (typeFilter == LocalModelTypeFilter.TEXT) {
            stringResource(R.string.local_models_catalog_recommended)
        } else {
            localModelTypeLabel(typeFilter)
        },
    ) {
        val visiblePresets = presets.filter { localPresetMatchesType(it, typeFilter) }
        if (typeFilter == LocalModelTypeFilter.TEXT) {
            LocalCatalogTier.entries.forEach { tier ->
                val tierPresets = visiblePresets.filter { localCatalogTier(it) == tier }
                if (tierPresets.isNotEmpty()) {
                    Text(
                        text = localCatalogTierLabel(tier),
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp),
                    )
                    tierPresets.forEach { preset ->
                        LocalCatalogModelRow(
                            preset = preset,
                            downloadInProgress = downloadInProgress,
                            activeDownloadPresetId = activeDownloadPresetId,
                            installedRecord = installedRecords[preset.id],
                            busy = busy,
                            onDownload = { onDownload(preset) },
                            onStart = onStart,
                        )
                    }
                }
            }
        } else {
            visiblePresets.forEach { preset ->
                LocalCatalogModelRow(
                    preset = preset,
                    downloadInProgress = downloadInProgress,
                    activeDownloadPresetId = activeDownloadPresetId,
                    installedRecord = installedRecords[preset.id],
                    busy = busy,
                    onDownload = { onDownload(preset) },
                    onStart = onStart,
                )
            }
        }
        if (visiblePresets.isEmpty()) {
            BasicComponent(
                title = stringResource(R.string.local_models_empty),
            )
        }
    }
}

private enum class LocalCatalogTier { LIGHT, BALANCED, HIGH, LARGE }

@Composable
private fun LocalCatalogModelRow(
    preset: LocalModelPreset,
    downloadInProgress: Boolean,
    activeDownloadPresetId: String?,
    installedRecord: LocalModelRecord?,
    busy: Boolean,
    onDownload: () -> Unit,
    onStart: (LocalModelRecord) -> Unit,
) {
    val format = LocalModelFormat.fromFileName(preset.url)?.name?.replace('_', '-') ?: "MODEL"
    val size = preset.expectedSizeBytes?.let(::formatLocalModelBytes) ?: "—"
    val type = localPresetType(preset)
    val downloading = activeDownloadPresetId == preset.id
    val action = if (installedRecord != null) onStart else { _: LocalModelRecord -> onDownload() }
    val enabled = !downloading && !busy && (installedRecord != null || !downloadInProgress)
    val icon = when (type) {
        LocalModelTypeFilter.VISION -> Icons.Rounded.Image
        LocalModelTypeFilter.TTS -> Icons.Rounded.GraphicEq
        LocalModelTypeFilter.ASR -> Icons.Rounded.Mic
        LocalModelTypeFilter.EMBEDDING -> Icons.Rounded.Hub
        LocalModelTypeFilter.TEXT -> Icons.Rounded.Memory
    }
    BasicComponent(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) {
            installedRecord?.let(onStart) ?: onDownload()
        },
        startAction = {
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(36.dp)
                    .squircleSurface(MiuixTheme.colorScheme.secondaryContainer, 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
            }
        },
        endActions = {
            when {
                downloading -> Box(
                    modifier = Modifier.size(34.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator(size = 18.dp)
                }
                installedRecord != null -> LocalCompactAction(
                    icon = Icons.Rounded.PlayArrow,
                    description = stringResource(R.string.local_models_runtime_start),
                    enabled = enabled,
                    onClick = { action(installedRecord) },
                )
                else -> LocalCompactAction(
                    icon = Icons.Rounded.CloudDownload,
                    description = stringResource(R.string.local_models_download),
                    enabled = enabled,
                    onClick = onDownload,
                )
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = localCatalogTitle(preset),
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TagChip(
                if (installedRecord != null) stringResource(R.string.local_models_badge_installed)
                else localModelTypeLabel(type),
                TagChipTone.Emphasized,
            )
        }
        Text(
            text = "$size · $format",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun localModelTypeLabel(type: LocalModelTypeFilter): String = stringResource(
    when (type) {
        LocalModelTypeFilter.TEXT -> R.string.local_models_filter_text
        LocalModelTypeFilter.TTS -> R.string.local_models_filter_tts
        LocalModelTypeFilter.ASR -> R.string.local_models_filter_asr
        LocalModelTypeFilter.VISION -> R.string.local_models_filter_vision
        LocalModelTypeFilter.EMBEDDING -> R.string.local_models_filter_embedding
    },
)

@Composable
private fun localCatalogTierLabel(tier: LocalCatalogTier): String = stringResource(
    when (tier) {
        LocalCatalogTier.LIGHT -> R.string.local_models_catalog_tier_light
        LocalCatalogTier.BALANCED -> R.string.local_models_catalog_tier_balanced
        LocalCatalogTier.HIGH -> R.string.local_models_catalog_tier_high
        LocalCatalogTier.LARGE -> R.string.local_models_catalog_tier_large
    },
)

@Composable
private fun localCatalogQuality(tier: LocalCatalogTier): String = stringResource(
    when (tier) {
        LocalCatalogTier.LIGHT -> R.string.local_models_quality_light
        LocalCatalogTier.BALANCED -> R.string.local_models_quality_balanced
        LocalCatalogTier.HIGH -> R.string.local_models_quality_high
        LocalCatalogTier.LARGE -> R.string.local_models_quality_large
    },
)

private fun localPresetType(preset: LocalModelPreset): LocalModelTypeFilter = when {
    "audio" in preset.outputModalities -> LocalModelTypeFilter.TTS
    "audio" in preset.inputModalities -> LocalModelTypeFilter.ASR
    "embedding" in preset.outputModalities -> LocalModelTypeFilter.EMBEDDING
    "image" in preset.inputModalities -> LocalModelTypeFilter.VISION
    else -> LocalModelTypeFilter.TEXT
}

private fun localPresetMatchesType(
    preset: LocalModelPreset,
    type: LocalModelTypeFilter,
): Boolean {
    val input = preset.inputModalities.map(String::lowercase).toSet()
    val output = preset.outputModalities.map(String::lowercase).toSet()
    return when (type) {
        LocalModelTypeFilter.TEXT -> "text" in input && "text" in output
        LocalModelTypeFilter.TTS -> "text" in input && "audio" in output
        LocalModelTypeFilter.ASR -> "audio" in input && "text" in output
        LocalModelTypeFilter.VISION -> "image" in input
        LocalModelTypeFilter.EMBEDDING -> "embedding" in output
    }
}

private fun localModelType(record: LocalModelRecord): LocalModelTypeFilter =
    when {
        "audio" in record.outputModalities -> LocalModelTypeFilter.TTS
        "audio" in record.inputModalities -> LocalModelTypeFilter.ASR
        "embedding" in record.outputModalities -> LocalModelTypeFilter.EMBEDDING
        "image" in record.inputModalities -> LocalModelTypeFilter.VISION
        else -> LocalModelTypeFilter.TEXT
    }

@Composable
private fun localCatalogTitle(preset: LocalModelPreset): String = preset.displayName.takeIf(String::isNotBlank) ?: when (preset.id) {
    LocalModelPreset.Qwen3DynamicInt4.id -> stringResource(R.string.local_models_qwen3_title)
    LocalModelPreset.Qwen3OnePointSevenB.id -> stringResource(R.string.local_models_qwen17_title)
    LocalModelPreset.Qwen3FourB.id -> stringResource(R.string.local_models_qwen4_title)
    LocalModelPreset.Gemma3OneBInt4.id -> stringResource(R.string.local_models_gemma3_title)
    LocalModelPreset.Gemma4E2B.id -> stringResource(R.string.local_models_gemma4_title)
    LocalModelPreset.Qwen25Small.id -> stringResource(R.string.local_models_qwen_title)
    LocalModelPreset.Qwen25OnePointFiveB.id -> stringResource(R.string.local_models_qwen15_title)
    LocalModelPreset.Qwen25ThreeB.id -> stringResource(R.string.local_models_qwen25_3b_title)
    else -> preset.id
}

private fun localCatalogTier(preset: LocalModelPreset): LocalCatalogTier = when (preset.id) {
    LocalModelPreset.Qwen3DynamicInt4.id,
    LocalModelPreset.Qwen25Small.id,
    -> LocalCatalogTier.LIGHT

    LocalModelPreset.Qwen3OnePointSevenB.id,
    LocalModelPreset.Qwen25OnePointFiveB.id,
    LocalModelPreset.Gemma3OneBInt4.id,
    -> LocalCatalogTier.BALANCED

    LocalModelPreset.Qwen25ThreeB.id,
    LocalModelPreset.Gemma4E2B.id,
    -> LocalCatalogTier.HIGH

    LocalModelPreset.Qwen3FourB.id -> LocalCatalogTier.LARGE
    else -> when {
        (preset.expectedSizeBytes ?: 0L) < 800L * 1024 * 1024 -> LocalCatalogTier.LIGHT
        (preset.expectedSizeBytes ?: 0L) < 1_500L * 1024 * 1024 -> LocalCatalogTier.BALANCED
        (preset.expectedSizeBytes ?: 0L) < 2_500L * 1024 * 1024 -> LocalCatalogTier.HIGH
        else -> LocalCatalogTier.LARGE
    }
}

private fun localModelMatchesType(
    record: LocalModelRecord,
    type: LocalModelTypeFilter,
): Boolean {
    val input = record.inputModalities.map(String::lowercase).toSet()
    val output = record.outputModalities.map(String::lowercase).toSet()
    return when (type) {
        LocalModelTypeFilter.TEXT -> "text" in input && "text" in output
        LocalModelTypeFilter.TTS -> "text" in input && "audio" in output
        LocalModelTypeFilter.ASR -> "audio" in input && "text" in output
        LocalModelTypeFilter.VISION -> "image" in input
        LocalModelTypeFilter.EMBEDDING -> "embedding" in output
    }
}

private fun localModelCapabilityStatus(record: LocalModelRecord): Int = when {
    localModelMatchesType(record, LocalModelTypeFilter.TEXT) &&
        record.runtime in setOf(
            io.github.mangi.eta.data.repository.LocalModelRuntime.BUILT_IN_LITERT_LM,
            io.github.mangi.eta.data.repository.LocalModelRuntime.BUILT_IN_LLAMA_CPP,
            io.github.mangi.eta.data.repository.LocalModelRuntime.BUILT_IN_MNN,
        ) -> R.string.local_models_status_supported

    localModelMatchesType(record, LocalModelTypeFilter.TEXT) -> R.string.local_models_status_experimental
    else -> R.string.local_models_status_reserved
}

@Composable
private fun LocalRuntimeModelSelectorCard(
    models: List<LocalModelRecord>,
    record: LocalModelRecord?,
    onSelect: (LocalModelRecord) -> Unit,
) {
    ProviderSection(title = null) {
        if (models.isEmpty()) {
            BasicComponent(
                title = stringResource(R.string.local_models_runtime_no_models_hint),
                startAction = { PreferenceIcon(Icons.Rounded.Memory) },
            )
        } else {
            OverlayDropdownPreference(
                title = stringResource(R.string.local_models_runtime_model),
                items = models.map(LocalModelRecord::displayName),
                selectedIndex = models.indexOfFirst { it.id == record?.id }.coerceAtLeast(0),
                onSelectedIndexChange = { index -> models.getOrNull(index)?.let(onSelect) },
            )
        }
    }
}

@Composable
private fun LocalRuntimeParametersCard(
    record: LocalModelRecord,
    onSettings: (((LocalModelRuntimeSettings) -> LocalModelRuntimeSettings)) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = record.runtimeSettings
    val npuTarget = NpuModelTargeting.targetOf(record.fileName)
    val npuModel = record.format == LocalModelFormat.LITERT_LM && npuTarget != null
    val npuCompatible = npuModel && NpuModelTargeting.matches(npuTarget, Build.SOC_MODEL)
    val embeddedContext = NpuModelTargeting.historyBudget(record.fileName)
    val npuState by QualcommNpuRuntimeManager.state.collectAsState()
    var npuError by remember(record.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(record.id) {
        QualcommNpuRuntimeManager.initialize(context)
    }
    val threadChoices = (1..8).toList()
    val contextChoices = if (npuModel) {
        listOf(embeddedContext)
    } else {
        listOf(2_048, 4_096, 8_192, 16_384, 32_768, 65_536, 131_072)
            .let { choices -> (choices + settings.contextWindow).distinct().sorted() }
    }
    val outputChoices = listOf(256, 512, 1_024, 2_048, 4_096, 8_192)
        .let { choices -> (choices + settings.maxOutputTokens).distinct().sorted() }
    val temperatureChoices = listOf(0f, 0.2f, 0.4f, 0.6f, 0.7f, 0.8f, 1.0f, 1.2f, 1.5f)
        .let { choices -> (choices + settings.temperature).distinct().sorted() }
    val topKChoices = listOf(20, 40, 64, 96, 128)
        .let { choices -> (choices + settings.topK).distinct().sorted() }
    val topPChoices = listOf(0.7f, 0.8f, 0.9f, 0.95f, 1f)
        .let { choices -> (choices + settings.topP).distinct().sorted() }
    Column(Modifier.fillMaxWidth()) {
        ProviderSection(title = stringResource(R.string.local_models_runtime_compute_group)) {
            if (npuModel && !npuCompatible) {
                Text(
                    stringResource(R.string.local_models_npu_runtime_incompatible, npuTarget.orEmpty().uppercase()),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (npuModel) {
                BasicComponent(
                    title = stringResource(R.string.local_models_npu_runtime_title),
                    summary = when (val state = npuState) {
                        is QualcommNpuRuntimeState.Installed -> stringResource(
                            R.string.local_models_npu_runtime_installed,
                            state.arch,
                        )
                        is QualcommNpuRuntimeState.Unsupported -> state.reason
                        QualcommNpuRuntimeState.NotInstalled -> stringResource(R.string.local_models_npu_runtime_missing)
                        QualcommNpuRuntimeState.Downloading -> stringResource(R.string.local_models_npu_runtime_downloading)
                        is QualcommNpuRuntimeState.Verifying -> stringResource(R.string.local_models_npu_runtime_verifying)
                        is QualcommNpuRuntimeState.NeedsRepair -> state.reason
                        is QualcommNpuRuntimeState.Failed -> state.reason
                    },
                    startAction = { PreferenceIcon(Icons.Rounded.Memory) },
                    endActions = {
                        val busy = npuState is QualcommNpuRuntimeState.Downloading ||
                            npuState is QualcommNpuRuntimeState.Verifying
                        LocalCompactAction(
                            icon = if (npuState is QualcommNpuRuntimeState.Installed) {
                                Icons.Rounded.Refresh
                            } else {
                                Icons.Rounded.CloudDownload
                            },
                            description = stringResource(
                                if (npuState is QualcommNpuRuntimeState.Installed) {
                                    R.string.local_models_npu_runtime_check
                                } else {
                                    R.string.local_models_npu_runtime_install
                                },
                            ),
                            enabled = !busy && npuState !is QualcommNpuRuntimeState.Unsupported,
                            prominent = npuState !is QualcommNpuRuntimeState.Installed,
                            onClick = {
                                scope.launch {
                                    npuError = runCatching {
                                        QualcommNpuRuntimeManager.ensureInstalled(context)
                                    }.exceptionOrNull()?.message
                                }
                            },
                        )
                    },
                )
                npuError?.takeIf(String::isNotBlank)?.let { message ->
                    Text(
                        message,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
            OverlayDropdownPreference(
                title = stringResource(R.string.local_models_runtime_threads),
                items = threadChoices.map { it.toString() },
                selectedIndex = threadChoices.indexOf(settings.threads).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    threadChoices.getOrNull(index)?.let { threads ->
                        onSettings { it.copy(threads = threads) }
                    }
                },
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.local_models_runtime_context),
                items = contextChoices.map(::formatCompactLocalContext),
                selectedIndex = contextChoices.indexOf(
                    if (npuModel) embeddedContext else settings.contextWindow,
                ).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    contextChoices.getOrNull(index)?.let { context -> if (!npuModel) {
                        onSettings { it.copy(contextWindow = context) }
                    } }
                },
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.local_models_runtime_max_output),
                items = outputChoices.map { it.toString() },
                selectedIndex = outputChoices.indexOf(settings.maxOutputTokens).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    outputChoices.getOrNull(index)?.let { output ->
                        onSettings { it.copy(maxOutputTokens = output) }
                    }
                },
            )
        }
        ProviderSection(title = stringResource(R.string.local_models_runtime_sampling_group)) {
            OverlayDropdownPreference(
                title = stringResource(R.string.local_models_runtime_temperature),
                summary = stringResource(R.string.local_models_runtime_temperature_summary),
                items = temperatureChoices.map { it.toString() },
                selectedIndex = temperatureChoices.indexOf(settings.temperature).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    temperatureChoices.getOrNull(index)?.let { temperature ->
                        onSettings { it.copy(temperature = temperature) }
                    }
                },
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.local_models_runtime_top_p),
                summary = stringResource(R.string.local_models_runtime_top_p_summary),
                items = topPChoices.map { it.toString() },
                selectedIndex = topPChoices.indexOf(settings.topP).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    topPChoices.getOrNull(index)?.let { topP ->
                        onSettings { it.copy(topP = topP) }
                    }
                },
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.local_models_runtime_top_k),
                summary = stringResource(R.string.local_models_runtime_top_k_summary),
                items = topKChoices.map { it.toString() },
                selectedIndex = topKChoices.indexOf(settings.topK).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    topKChoices.getOrNull(index)?.let { topK ->
                        onSettings { it.copy(topK = topK) }
                    }
                },
            )
        }
        ProviderSection(title = stringResource(R.string.local_models_runtime_capabilities_group)) {
            SwitchPreference(
                title = stringResource(R.string.local_models_runtime_thinking),
                summary = stringResource(R.string.local_models_runtime_thinking_summary),
                checked = settings.thinkingEnabled,
                onCheckedChange = { enabled ->
                    onSettings { it.copy(thinkingEnabled = enabled) }
                },
            )
            if (record.supportsVision) {
                SwitchPreference(
                    title = stringResource(R.string.local_models_runtime_vision),
                    summary = stringResource(R.string.local_models_runtime_vision_summary),
                    checked = settings.imageInputEnabled,
                    onCheckedChange = { enabled ->
                        onSettings { it.copy(imageInputEnabled = enabled) }
                    },
                )
            }
        }
    }
}

@Composable
private fun LocalRuntimePolicyCard(
    record: LocalModelRecord,
    snapshot: LocalDevicePerformanceSnapshot,
    onSettings: (((LocalModelRuntimeSettings) -> LocalModelRuntimeSettings)) -> Unit,
) {
    val settings = record.runtimeSettings
    val builtIn = record.format in setOf(LocalModelFormat.LITERT_LM, LocalModelFormat.GGUF, LocalModelFormat.MNN)
    val idleChoices = listOf(5, 10, 15, 30, 60, 120)
        .let { choices -> (choices + settings.idleUnloadMinutes).distinct().sorted() }
    ProviderSection(title = stringResource(R.string.local_models_runtime_policy)) {
            if (builtIn) {
                SwitchPreference(
                    title = stringResource(R.string.local_models_runtime_auto_connect),
                    checked = settings.autoConnect,
                    onCheckedChange = { enabled ->
                        onSettings { it.copy(autoConnect = enabled) }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.local_models_runtime_auto_start),
                    checked = settings.autoStart,
                    onCheckedChange = { enabled ->
                        onSettings { it.copy(autoStart = enabled) }
                    },
                )
            }
            OverlayDropdownPreference(
                title = stringResource(R.string.local_models_runtime_idle),
                items = idleChoices.map { minutes ->
                    stringResource(R.string.local_models_runtime_idle_value, minutes)
                },
                selectedIndex = idleChoices.indexOf(settings.idleUnloadMinutes).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    idleChoices.getOrNull(index)?.let { minutes ->
                        onSettings { it.copy(idleUnloadMinutes = minutes) }
                    }
                },
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.local_models_runtime_power_policy),
                items = listOf(
                    stringResource(R.string.local_models_runtime_power_follow),
                    stringResource(R.string.local_models_runtime_power_pause),
                ),
                selectedIndex = when (settings.powerPolicy) {
                    LocalRuntimePowerPolicy.FOLLOW_SYSTEM -> 0
                    LocalRuntimePowerPolicy.PAUSE_IN_BATTERY_SAVER -> 1
                },
                onSelectedIndexChange = { index ->
                    onSettings {
                        it.copy(
                            powerPolicy = if (index == 1) {
                                LocalRuntimePowerPolicy.PAUSE_IN_BATTERY_SAVER
                            } else {
                                LocalRuntimePowerPolicy.FOLLOW_SYSTEM
                            },
                        )
                    }
                },
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.local_models_runtime_thermal_policy),
                items = listOf(
                    stringResource(R.string.local_models_runtime_thermal_balanced),
                    stringResource(R.string.local_models_runtime_thermal_stability),
                    stringResource(R.string.local_models_runtime_thermal_pause),
                ),
                selectedIndex = when (settings.thermalPolicy) {
                    LocalRuntimeThermalPolicy.BALANCED -> 0
                    LocalRuntimeThermalPolicy.STABILITY_FIRST -> 1
                    LocalRuntimeThermalPolicy.PAUSE_WHEN_SEVERE -> 2
                },
                onSelectedIndexChange = { index ->
                    val policy = when (index) {
                        1 -> LocalRuntimeThermalPolicy.STABILITY_FIRST
                        2 -> LocalRuntimeThermalPolicy.PAUSE_WHEN_SEVERE
                        else -> LocalRuntimeThermalPolicy.BALANCED
                    }
                    onSettings { it.copy(thermalPolicy = policy) }
                },
            )
            if (snapshot.powerSaveMode) {
                Text(
                    stringResource(R.string.local_models_warning_power_saving),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (snapshot.thermalStatus >= 2) {
                Text(
                    stringResource(R.string.local_models_warning_thermal),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
    }
}
@Composable
private fun LocalCustomSourceCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    downloadUrl: String,
    onUrlChange: (String) -> Unit,
    activeDownload: Boolean,
    onRestoreRecommended: () -> Unit,
    onSaveAddress: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth()) {
            ArrowPreference(
                title = stringResource(R.string.local_models_section_source),
                summary = if (expanded) stringResource(R.string.local_models_section_source_hint)
                else downloadUrl,
                startAction = { PreferenceIcon(Icons.Rounded.Link) },
                onClick = onToggle,
            )
            if (expanded) {
                Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    TextField(
                        value = downloadUrl,
                        onValueChange = onUrlChange,
                        label = stringResource(R.string.local_models_download_address),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        stringResource(R.string.local_models_download_address_summary),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            text = stringResource(R.string.local_models_use_recommended),
                            onClick = onRestoreRecommended,
                        )
                        TextButton(
                            text = stringResource(R.string.local_models_save_address),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = onSaveAddress,
                        )
                    }
                    ArrowPreference(
                        title = stringResource(R.string.local_models_download),
                        summary = stringResource(R.string.local_models_download_note),
                        enabled = !activeDownload,
                        startAction = { PreferenceIcon(Icons.Rounded.CloudDownload) },
                        onClick = onDownload,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalExternalServiceCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenLocalService: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth()) {
            ArrowPreference(
                title = stringResource(R.string.local_models_sources_service_title),
                summary = stringResource(R.string.local_models_sources_service_summary),
                startAction = { PreferenceIcon(Icons.Rounded.Dns) },
                onClick = onToggle,
            )
            if (expanded) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    Text(
                        stringResource(R.string.local_models_sources_service_detail),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    TextButton(
                        text = stringResource(R.string.local_models_sources_open_service),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        onClick = onOpenLocalService,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalModelBindingScreen(
    record: LocalModelRecord,
    snapshot: LocalDevicePerformanceSnapshot,
    saving: Boolean,
    autoAttaching: Boolean,
    serverError: String?,
    onBack: () -> Unit,
    onAutoAttach: () -> Unit,
    onConfirm: (endpoint: String, serverModelId: String, contextWindow: Int, tools: Boolean, vision: Boolean) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val performance = LocalModelPerformanceMonitor.recommend(snapshot, record)
    var endpoint by rememberSaveable(record.id) { mutableStateOf(record.serviceEndpoint) }
    var serverModelId by rememberSaveable(record.id) { mutableStateOf(record.serverModelId) }
    var contextWindow by rememberSaveable(record.id) {
        mutableStateOf(
            if (record.boundModelSelectionId != null) record.contextWindow.toString()
            else performance.contextWindow.toString(),
        )
    }
    var supportsTools by rememberSaveable(record.id) { mutableStateOf(record.supportsTools) }
    var supportsVision by rememberSaveable(record.id) { mutableStateOf(record.supportsVision) }
    var formError by rememberSaveable(record.id) { mutableStateOf<String?>(null) }
    var probing by rememberSaveable(record.id) { mutableStateOf(false) }
    var probeMessage by rememberSaveable(record.id) { mutableStateOf<String?>(null) }
    var probeFailed by rememberSaveable(record.id) { mutableStateOf(false) }
    var discoveredModelIds by remember(record.id) { mutableStateOf(emptyList<String>()) }
    var localNetworkAccess by rememberSaveable(record.id) {
        mutableStateOf(LocalNetworkPermission.accessState(context))
    }
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        localNetworkAccess = LocalNetworkPermission.accessState(context)
        probeFailed = !it
        probeMessage = if (it) null else context.getString(R.string.local_models_local_network_required)
    }
    MiuixScaffoldPage(
        title = stringResource(R.string.local_models_bind_title),
        onBack = onBack,
    ) {
        item(key = "bind_summary") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        record.displayName,
                        style = MiuixTheme.textStyles.headline2,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.local_models_bind_summary),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TagChip(snapshot.socLabel, TagChipTone.Emphasized)
                        TagChip(
                            stringResource(
                                R.string.local_models_device_recommendation_value,
                                formatCompactLocalContext(performance.contextWindow),
                                performance.threads,
                            ),
                        )
                    }
                    performance.warnings.forEach { warning ->
                        Text(
                            performanceWarningText(warning),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                    TextButton(
                        text = if (autoAttaching) stringResource(R.string.local_models_attaching)
                        else stringResource(R.string.local_models_bind_auto),
                        enabled = !autoAttaching && !saving,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onAutoAttach,
                    )
                    TextButton(
                        text = stringResource(R.string.local_models_apply_device_profile),
                        onClick = { contextWindow = performance.contextWindow.toString() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item(key = "service_fields") {
            ProviderSection(title = stringResource(R.string.local_models_bind_service_section)) {
                Text(
                    stringResource(R.string.local_models_service_presets),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    LocalServicePresets.forEach { (label, value) ->
                        TextButton(
                            text = label,
                            onClick = {
                                endpoint = value
                                formError = null
                            },
                        )
                    }
                }
                TextField(
                    value = endpoint,
                    onValueChange = {
                        endpoint = it.take(2_000)
                        formError = null
                    },
                    label = stringResource(R.string.local_models_service_endpoint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    stringResource(R.string.local_models_service_endpoint_hint),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                if (endpointRequiresLocalNetworkPermission(endpoint) &&
                    localNetworkAccess == LocalNetworkPermission.AccessState.DENIED
                ) {
                    Text(
                        stringResource(R.string.local_models_local_network_required),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    TextButton(
                        text = stringResource(R.string.local_models_grant_local_network),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        onClick = {
                            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                        },
                    )
                }
                TextButton(
                    text = if (probing) stringResource(R.string.local_models_probe_probing)
                    else stringResource(R.string.local_models_probe_action),
                    enabled = !probing && (
                        !endpointRequiresLocalNetworkPermission(endpoint) ||
                            localNetworkAccess != LocalNetworkPermission.AccessState.DENIED
                        ),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    onClick = {
                        val normalized = runCatching {
                            LocalModelRepository.validateServiceEndpoint(endpoint)
                        }.getOrElse { failure ->
                            probeFailed = true
                            probeMessage = failure.message
                            return@TextButton
                        }
                        endpoint = normalized
                        probing = true
                        probeFailed = false
                        probeMessage = null
                        scope.launch {
                            try {
                                val discovery = LocalModelPerformanceMonitor.discoverModels(normalized)
                                discoveredModelIds = discovery.modelIds
                                if (discovery.modelIds.isNotEmpty()) {
                                    serverModelId = chooseLocalServerModelId(discovery.modelIds, record.displayName)
                                    probeMessage = context.getString(
                                        R.string.local_models_probe_success,
                                        discovery.modelIds.size,
                                        discovery.latencyMs,
                                    )
                                } else {
                                    probeFailed = true
                                    probeMessage = context.getString(R.string.local_models_probe_empty)
                                }
                            } catch (failure: Exception) {
                                discoveredModelIds = emptyList()
                                probeFailed = true
                                probeMessage = context.getString(
                                    R.string.local_models_probe_failed,
                                    failure.message ?: failure.javaClass.simpleName,
                                )
                            } finally {
                                probing = false
                            }
                        }
                    },
                )
                probeMessage?.let { message ->
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (probeFailed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                if (discoveredModelIds.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        discoveredModelIds.forEach { modelId ->
                            TextButton(
                                text = if (serverModelId == modelId) "✓ $modelId" else modelId,
                                onClick = {
                                    serverModelId = modelId
                                    formError = null
                                },
                            )
                        }
                    }
                }
                TextField(
                    value = serverModelId,
                    onValueChange = {
                        serverModelId = it.take(240)
                        formError = null
                    },
                    label = stringResource(R.string.local_models_server_model_id),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    singleLine = true,
                )
                TextField(
                    value = contextWindow,
                    onValueChange = {
                        contextWindow = it.filter(Char::isDigit).take(7)
                        formError = null
                    },
                    label = stringResource(R.string.local_models_context_window),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    singleLine = true,
                )
            }
        }
        item(key = "capabilities") {
            ProviderSection(title = stringResource(R.string.local_models_capabilities_title)) {
                SwitchPreference(
                    title = stringResource(R.string.local_models_tools),
                    summary = stringResource(R.string.local_models_tools_summary),
                    checked = supportsTools,
                    onCheckedChange = { supportsTools = it },
                )
                SwitchPreference(
                    title = stringResource(R.string.local_models_vision),
                    summary = stringResource(R.string.local_models_vision_summary),
                    checked = supportsVision,
                    onCheckedChange = { supportsVision = it },
                )
            }
        }
        item(key = "bind_error") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                (formError ?: serverError)?.let { error ->
                    Text(
                        text = error,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }
        item(key = "bind_action") {
            TextButton(
                text = if (saving) stringResource(R.string.local_models_bind_saving)
                else stringResource(R.string.local_models_bind_action_short),
                enabled = !saving,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                onClick = {
                    val length = contextWindow.toIntOrNull()
                    formError = runCatching {
                        LocalModelRepository.validateServiceEndpoint(endpoint)
                        require(serverModelId.isNotBlank()) {
                            context.getString(R.string.local_models_model_id_required)
                        }
                        require(serverModelId.length <= 240) {
                            context.getString(R.string.local_models_model_id_too_long)
                        }
                        require(length != null && length in 1_024..1_048_576) {
                            context.getString(R.string.local_models_context_invalid)
                        }
                    }.exceptionOrNull()?.message
                    if (formError == null) {
                        onConfirm(endpoint, serverModelId, requireNotNull(length), supportsTools, supportsVision)
                    }
                },
            )
        }
        item(key = "bind_bottom_space") { Spacer(Modifier.height(18.dp)) }
    }
}
@Composable
private fun LocalDevicePerformanceEffects(
    context: Context,
    onChanged: (LocalDevicePerformanceSnapshot) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner) {
        fun refresh() {
            runCatching { LocalModelPerformanceMonitor.capture(context) }
                .onSuccess(onChanged)
        }

        val power = context.getSystemService(PowerManager::class.java)
        val thermalListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager.OnThermalStatusChangedListener { refresh() }.also { listener ->
                power.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
            }
        } else {
            null
        }
        val powerSaveReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refresh()
            }
        }
        ContextCompat.registerReceiver(
            context,
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            runCatching { context.unregisterReceiver(powerSaveReceiver) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                thermalListener?.let(power::removeThermalStatusListener)
            }
        }
    }
}

private fun runtimeStateLabel(state: LocalModelRuntimeState): Int = when (state) {
    LocalModelRuntimeState.STOPPED -> R.string.local_models_runtime_state_stopped
    LocalModelRuntimeState.STARTING -> R.string.local_models_runtime_state_starting
    LocalModelRuntimeState.READY -> R.string.local_models_runtime_state_ready
    LocalModelRuntimeState.GENERATING -> R.string.local_models_runtime_state_generating
    LocalModelRuntimeState.FAILED -> R.string.local_models_runtime_state_failed
}

@Composable
private fun performanceWarningText(warning: io.github.mangi.eta.data.repository.LocalPerformanceWarning): String = when (warning) {
    io.github.mangi.eta.data.repository.LocalPerformanceWarning.LOW_AVAILABLE_MEMORY -> {
        stringResource(R.string.local_models_warning_memory)
    }
    io.github.mangi.eta.data.repository.LocalPerformanceWarning.LOW_STORAGE -> {
        stringResource(R.string.local_models_warning_storage)
    }
    io.github.mangi.eta.data.repository.LocalPerformanceWarning.THERMAL_PRESSURE -> {
        stringResource(R.string.local_models_warning_thermal)
    }
    io.github.mangi.eta.data.repository.LocalPerformanceWarning.POWER_SAVING -> {
        stringResource(R.string.local_models_warning_power_saving)
    }
}

@Composable
private fun LocalRuntimeSignal(state: LocalModelRuntimeState) {
    val color = when (state) {
        LocalModelRuntimeState.READY,
        LocalModelRuntimeState.GENERATING,
        -> StatusSuccess

        LocalModelRuntimeState.STARTING -> StatusSuccess.copy(alpha = 0.55f)
        LocalModelRuntimeState.FAILED -> MiuixTheme.colorScheme.error
        LocalModelRuntimeState.STOPPED -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(color.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
    }
}

@Composable
private fun downloadTitle(state: io.github.mangi.eta.data.repository.LocalModelDownloadState): String = when (state.stage) {
    LocalModelDownloadStage.VERIFYING -> stringResource(R.string.local_models_download_verifying)
    LocalModelDownloadStage.COMPLETED -> stringResource(R.string.local_models_download_complete)
    LocalModelDownloadStage.FAILED -> stringResource(R.string.local_models_download_failed, state.message.orEmpty())
    LocalModelDownloadStage.CANCELLED -> stringResource(R.string.local_models_download_cancelled)
    else -> stringResource(R.string.local_models_download_active, state.displayName)
}

internal fun endpointRequiresLocalNetworkPermission(endpoint: String): Boolean {
    if (!ProviderEndpoint.isLocalOrPrivate(endpoint)) return false
    val host = runCatching { java.net.URI(endpoint.trim()).host }
        .getOrNull()
        ?.removeSurrounding("[", "]")
        ?.lowercase()
        ?: return false
    return host != "localhost" &&
        host != "::1" &&
        !host.startsWith("127.")
}

internal fun formatLocalModelBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return DecimalFormat(if (value >= 100 || unit == 0) "0" else "0.0").format(value) + " " + units[unit]
}

internal fun formatCompactLocalContext(tokens: Int): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
    tokens >= 1_000 -> "${tokens / 1_000}K"
    else -> tokens.toString()
}

/** Artifact formats served by an in-process runtime, so they can be added to the picker. */
internal fun LocalModelFormat.supportsBuiltInRuntime(): Boolean =
    this == LocalModelFormat.LITERT_LM ||
        this == LocalModelFormat.GGUF ||
        this == LocalModelFormat.MNN

internal enum class LocalModelLaunchDecision {
    NONE,
    REGISTER_ONLY,
    START,
}

/**
 * Decides what a finished download or import should do with a local artifact. Loading the model
 * requires either an explicit "download and start" action or the user's auto-connect and
 * auto-start settings; attaching without starting only adds the model to the picker.
 */
internal fun localModelLaunchDecision(
    startAfterDownload: Boolean,
    format: LocalModelFormat?,
    settings: LocalModelRuntimeSettings?,
    supportsChat: Boolean = true,
): LocalModelLaunchDecision {
    if (format == null || !format.supportsBuiltInRuntime() || settings == null || !supportsChat) {
        return LocalModelLaunchDecision.NONE
    }
    if (startAfterDownload) return LocalModelLaunchDecision.START
    if (!settings.autoConnect) return LocalModelLaunchDecision.NONE
    return if (settings.autoStart) {
        LocalModelLaunchDecision.START
    } else {
        LocalModelLaunchDecision.REGISTER_ONLY
    }
}
