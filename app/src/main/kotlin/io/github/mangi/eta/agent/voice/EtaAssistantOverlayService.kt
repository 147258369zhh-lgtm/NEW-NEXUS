package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.voice.openSpeechSettings
import io.github.mangi.eta.ui.voice.SpeechPlaybackErrors
import io.github.mangi.eta.ui.voice.LocalSpeechPlayback
import io.github.mangi.eta.ui.voice.LocalSpeechPlaybackEnabled
import android.os.PowerManager
import android.Manifest
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.core.NexusUiContract
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.app.AgentRunEventCoalescer
import io.github.mangi.eta.ui.app.AgentRunMessageProjector
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * Eta 数字助理的用户界面窗口。
 *
 * 系统助理会话承接电源键入口并采集上下文；这里固定使用全屏 TYPE_APPLICATION_OVERLAY，
 * 让输入法、动画和厂商助手式浮窗拥有同一个窗口生命周期。
 */
internal class EtaAssistantOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancellationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NexusAssistantRuntimeCancel")
    }
    private val runtimeClient = AgentRuntimeClient(this, AndroidAgentLogger)
    private val runMessageProjector = AgentRunMessageProjector()
    private val eventCoalescer = AgentRunEventCoalescer()
    private var deltaFlushJob: Job? = null
    private var speechLevel by mutableFloatStateOf(0f)
    private var speechState by mutableStateOf(EtaSpeechState())
    /**
     * 识别侧与播报侧分别维护自己的状态，再由 [publishSpeechState] 合成一份界面状态：
     * 朗读中麦克风是闭合的，两边的字段不会互相覆盖，也不会把“正在朗读”误显示成“正在聆听”。
     */
    private var asrSpeech by mutableStateOf(EtaSpeechState())
    private var playbackSpeaking by mutableStateOf(false)
    private var bargeInArmed by mutableStateOf(false)
    private var speechSettings by mutableStateOf<SpeechSettings?>(null)
    private var speechHeld = false
    private var pendingHoldToTalk = false
    private var continuousVoiceConversation = false
    private var awaitingVoiceFollowUp = false
    private val speechForeground by lazy { AssistantSpeechForeground(this) }
    private val wakeWordStore by lazy { WakeWordStore(this) }
    private val voiceprintStore by lazy { VoiceprintProfileStore(this) }
    private var speechSpeakerFeatures: ByteArray? = null
    private val playback by lazy {
        SpeechPlaybackController(this, scope, beforePlayback = speechForeground::playback, afterPlayback = speechForeground::playbackStopped)
    }
    private val voiceSession by lazy {
        LocalVoiceSessionCoordinator(
            scope = scope,
            stopSpeaking = { fadeMs -> playback.stopSmoothly(fadeMs) },
            startListening = { startSpeechInternal() },
            monitorFactory = { SpeechBargeInMonitor(this) },
            onMonitorLevel = { level -> speechLevel = level },
            onMonitoringChanged = { active ->
                if (active) speechForeground.monitoring() else speechForeground.monitoringStopped()
            },
        )
    }
    private val speechInput by lazy {
        SpeechInputController(
            context = this,
            scope = scope,
            automaticEndpoint = true,
            captureSpeakerFeatures = true,
            onSpeakerFeatures = { features -> speechSpeakerFeatures = features },
            beforeCapture = speechForeground::recording,
            afterCapture = speechForeground::recordingStopped,
            onResult = { text ->
                updateSpeechState(EtaSpeechState())
                if (!WakeWordMatcher.matches(text, wakeWordStore.read())) {
                    updateSpeechState(EtaSpeechState(message = getString(R.string.speech_wake_word_not_detected)))
                    scope.launch(Dispatchers.Main.immediate) {
                        delay(650L)
                        if (activeRunId == null && windowView != null) startSpeech()
                    }
                } else if (wakeWordStore.read().voiceprintEnabled) {
                    val wakeConfig = wakeWordStore.read()
                    val verification = voiceprintStore.verify(
                        featureVector = speechSpeakerFeatures,
                        threshold = wakeConfig.voiceprintThreshold,
                    )
                    speechSpeakerFeatures = null
                    updateSpeechState(EtaSpeechState(message = getString(
                        when (verification) {
                            VoiceprintVerification.VERIFIED -> R.string.speech_voiceprint_verified
                            VoiceprintVerification.REJECTED -> R.string.speech_voiceprint_rejected
                            VoiceprintVerification.NO_PROFILE -> R.string.speech_voiceprint_profile_required
                            VoiceprintVerification.DISABLED -> R.string.speech_voiceprint_unavailable
                            VoiceprintVerification.UNAVAILABLE -> R.string.speech_voiceprint_unavailable
                        },
                    )))
                    if (verification == VoiceprintVerification.VERIFIED) submitPromptInternal(text, fromSpeech = true)
                } else {
                    speechSpeakerFeatures = null
                    submitPromptInternal(text, fromSpeech = true)
                }
            },
        )
    }
    private val conversationKey = "eta_assistant_${UUID.randomUUID()}"
    private var conversationHistory = emptyList<AgentModelClient.ConversationMessage>()

    private var windowManager: WindowManager? = null
    private var windowView: ComposeView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var detachingWindowView: View? = null
    private val windowDetachCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var backInvokedDispatcher: OnBackInvokedDispatcher? = null
    private var backInvokedCallback: OnBackInvokedCallback? = null
    private var runJob: Job? = null
    private var dismissalJob: Job? = null
    private var activeRunId: String? = null
    private var entryGeneration = 0L
    private var presentedEntryGeneration = -1L
    private var entryInputPending = false
    private var entryEntranceComplete by mutableStateOf(false)
    private var entryContentReady = false
    private var entryScreenContext: EtaAssistantScreenContext? = null
    private var hiddenForForegroundOperation = false
    private var handoffInProgress by mutableStateOf(false)
    private var handoffExitRequested by mutableStateOf(false)
    private var inputText by mutableStateOf("")
    private var inputFocusRequestKey by mutableIntStateOf(-1)
    private var uiState by mutableStateOf(EtaVoiceUiState())

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        activeService = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        scope.launch(Dispatchers.Main.immediate) {
            SpeechSettingsRepository.settingsFlow().collect { settings ->
                val wasEnabled = speechSettings?.recognitionEnabled == true
                speechSettings = settings
                if (wasEnabled && !settings.recognitionEnabled) {
                    cancelSpeech()
                    if (windowView != null && activeRunId == null) entryInputPending = true
                }
                presentEntryInput()
            }
        }
        scope.launch(Dispatchers.Main.immediate) {
            speechInput.state.collect { state ->
                speechLevel = state.level
                if (state.preview.isNotBlank()) inputText = state.preview
                updateSpeechState(EtaSpeechState(
                    phase = state.phase,
                    // 连接、聆听、识别中的阶段播报由语音条目与波形承担，反馈条只保留错误提示，
                    // 避免与语音界面重复叙事。
                    message = state.error,
                    configureAvailable = state.configureAvailable,
                    feedbackIsError = state.error != null,
                    holdToTalk = speechHeld && state.active,
                ))
                // 识别会话的生命周期就是协调器的“正在听”，开始与结束都在这里对齐。
                if (state.active) voiceSession.onListeningStarted() else voiceSession.onListeningEnded()
            }
        }
        scope.launch(Dispatchers.Main.immediate) {
            playback.state.collect { state ->
                // 合成一旦开始就接受打断：等首包时用户也可能改主意。
                if (state.messageId == null) {
                    if (playbackSpeaking) {
                        playbackSpeaking = false
                        publishSpeechState()
                    }
                    voiceSession.onSpeakingEnded()
                } else {
                    if (!playbackSpeaking) {
                        playbackSpeaking = true
                        publishSpeechState()
                    }
                    voiceSession.onSpeakingStarted()
                }
                if (awaitingVoiceFollowUp && !state.loading && state.messageId == null && state.error == null) {
                    awaitingVoiceFollowUp = false
                    scheduleVoiceFollowUp()
                }
            }
        }
        scope.launch(Dispatchers.Main.immediate) {
            voiceSession.state.collect { session ->
                if (session.bargeInArmed != bargeInArmed) {
                    bargeInArmed = session.bargeInArmed
                    if (playbackSpeaking) publishSpeechState()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AssistantSpeechForeground.ACTION_STOP -> {
                voiceSession.release()
                speechInput.cancel()
                playback.stop()
            }
            ACTION_SHOW -> showEntry(intent.getStringExtra(EXTRA_SCREEN_CONTEXT_ID))
            ACTION_HANDOFF_READY -> finishHandoff()
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 内存吃紧时按 [LocalVoiceResourcePolicy] 的顺序回收本地语音模型：先播报、再识别，
     * 文本主模型不参与；正在使用的方向当场不释放，避免打断这一轮对话。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val settings = speechSettings ?: return
        val phase = voiceSession.state.value.phase
        val targets = LocalVoiceResourcePolicy.releaseTargets(
            level = level,
            speaking = phase == VoiceSessionPhase.SPEAKING || phase == VoiceSessionPhase.STOPPING,
            listening = phase == VoiceSessionPhase.LISTENING,
        )
        if (targets.isEmpty()) return
        val speakingNow = playbackSpeaking
        scope.launch(Dispatchers.Default) {
            if (LocalSpeechKind.TTS in targets && speakingNow) {
                withContext(Dispatchers.Main.immediate) {
                    playback.stop()
                    voiceSession.onSpeakingEnded()
                }
            }
            targets.forEach { kind ->
                val id = when (kind) {
                    LocalSpeechKind.TTS -> settings.localTtsModelId
                    LocalSpeechKind.ASR -> settings.localAsrModelId
                }
                if (id.isNotBlank()) runCatching { LocalSpeechRuntime.release(id) }
            }
            AndroidAgentLogger.info(
                "Nexus local speech released for memory: kinds=${targets.joinToString(",")}",
            )
        }
    }

    override fun onDestroy() {
        entryGeneration++
        EtaAssistantScreenContexts.release(entryScreenContext?.id)
        entryScreenContext = null
        cancelCurrentRun()
        removeWindow()
        speechForeground.stop()
        scope.cancel()
        cancellationExecutor.shutdown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    private fun showEntry(screenContextId: String?) {
        if (!Settings.canDrawOverlays(this)) {
            EtaAssistantScreenContexts.release(screenContextId)
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_permission_missing") {
                "Nexus assistant overlay permission is missing"
            }
            stopSelf()
            return
        }
        dismissalJob?.cancel()
        dismissalJob = null
        cancelCurrentRun()
        if (entryScreenContext?.id != screenContextId) {
            EtaAssistantScreenContexts.release(entryScreenContext?.id)
            entryScreenContext = EtaAssistantScreenContexts.find(screenContextId)
        }
        removeWindow()
        val generation = ++entryGeneration
        presentedEntryGeneration = -1L
        entryInputPending = true
        entryEntranceComplete = false
        entryContentReady = false
        inputFocusRequestKey = -1
        inputText = ""
        uiState = EtaVoiceUiState()
        hiddenForForegroundOperation = false
        handoffInProgress = false
        handoffExitRequested = false
        presentEntry(generation)
    }

    private fun presentEntry(generation: Long) {
        if (generation != entryGeneration || presentedEntryGeneration == generation) return
        presentedEntryGeneration = generation
        showWindow()
        if (windowView == null) {
            stopSelf()
            return
        }
        presentEntryInput()
    }

    private fun presentEntryInput() {
        if (!entryInputPending || windowView == null || activeRunId != null) return
        val settings = speechSettings ?: return
        val canRecord = settings.recognitionEnabled &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // 文字模式先完成唤醒光效和输入栏显现，最后才交接焦点并请求键盘。
        if (!canRecord && (!entryEntranceComplete || !entryContentReady)) return
        entryInputPending = false
        if (canRecord) {
            startSpeech()
        } else {
            if (settings.recognitionEnabled) {
                updateSpeechState(EtaSpeechState(errorRes = R.string.voice_audio_permission))
            }
            showKeyboard()
        }
    }

    private fun updateSpeechState(value: EtaSpeechState) {
        asrSpeech = value
        publishSpeechState()
    }

    private fun publishSpeechState() {
        speechState = if (playbackSpeaking) {
            asrSpeech.copy(
                phase = EtaSpeechPhase.IDLE,
                speaking = true,
                bargeInArmed = bargeInArmed,
                canonical = voiceSession.state.value.canonical,
            )
        } else {
            asrSpeech.copy(canonical = voiceSession.state.value.canonical)
        }
    }

    private fun startSpeech(holdToTalk: Boolean = false) {
        if (activeRunId != null || speechSettings?.recognitionEnabled != true) return
        pendingHoldToTalk = holdToTalk
        when (voiceSession.state.value.phase) {
            VoiceSessionPhase.SPEAKING -> {
                // 朗读中点麦克风：与语音打断走同一条收尾链，淡出后再开麦。
                voiceSession.requestListening()
                return
            }
            VoiceSessionPhase.LISTENING, VoiceSessionPhase.STOPPING -> return
            else -> Unit
        }
        startSpeechInternal(holdToTalk)
    }

    private fun startSpeechInternal(holdToTalk: Boolean = pendingHoldToTalk) {
        if (activeRunId != null || speechSettings?.recognitionEnabled != true) return
        if (speechInput.state.value.active) return
        entryInputPending = false
        awaitingVoiceFollowUp = false
        if (playbackSpeaking) playback.stop()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                dismissAndStop()
            } catch (_: RuntimeException) {
                updateSpeechState(EtaSpeechState(errorRes = R.string.voice_audio_permission))
            }
            return
        }
        inputFocusRequestKey = -1
        inputText = ""
        updateSoftInput(visible = false)
        windowView?.let { view ->
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
        speechLevel = 0f
        speechHeld = holdToTalk
        updateSpeechState(EtaSpeechState(phase = EtaSpeechPhase.STARTING, holdToTalk = holdToTalk))
        voiceSession.onListeningStarted()
        speechInput.start(holdToTalk = holdToTalk)
    }

    private fun scheduleVoiceFollowUp() {
        scope.launch(Dispatchers.Main.immediate) {
            delay(320L)
            if (continuousVoiceConversation &&
                activeRunId == null &&
                windowView?.isShown == true &&
                !hiddenForForegroundOperation &&
                !speechInput.state.value.active &&
                !playbackSpeaking &&
                speechSettings?.recognitionEnabled == true &&
                getSystemService(PowerManager::class.java).isInteractive &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                startSpeech()
            }
        }
    }

    private fun cancelSpeech() {
        voiceSession.stopSpeakingNow()
        speechInput.cancel()
        speechHeld = false
        continuousVoiceConversation = false
        awaitingVoiceFollowUp = false
        updateSpeechState(EtaSpeechState())
        voiceSession.onListeningEnded()
    }

    private fun switchToKeyboard() {
        voiceSession.stopSpeakingNow()
        speechInput.cancel()
        continuousVoiceConversation = false
        awaitingVoiceFollowUp = false
        updateSpeechState(EtaSpeechState())
        voiceSession.onListeningEnded()
        showKeyboard()
    }

    private fun showWindow() {
        if (windowView != null) return
        val generation = entryGeneration
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = createComposeView {
            val appearance by AppearanceSettingsRepository.settingsFlow()
                .collectAsState(initial = AppearanceSettings())
            AgentAppTheme(
                appearance = appearance,
                applyInterfaceScale = false,
            ) {
                // ColorOS 在 Overlay 窗口切换期间可能短暂使用软件画布；RuntimeShader
                // 无法在该画布绘制，因此浮窗统一使用 Miuix 的圆角回退路径。
                CompositionLocalProvider(LocalSquircleEnabled provides false,
                    LocalSpeechPlayback provides playback,
                    LocalSpeechPlaybackEnabled provides (speechSettings?.let { it.tts != TtsProvider.NONE } == true)) {
                    SpeechPlaybackErrors(playback)
                    EtaVoicePanel(
                        state = uiState,
                        speech = speechState,
                        recognitionEnabled = speechSettings?.recognitionEnabled,
                        entranceComplete = entryEntranceComplete,
                        onEntranceFinished = {
                            if (generation == entryGeneration) {
                                entryEntranceComplete = true
                                presentEntryInput()
                            }
                        },
                        onContentReady = {
                            if (generation == entryGeneration) {
                                entryContentReady = true
                                presentEntryInput()
                            }
                        },
                        onInputFocus = {
                            entryInputPending = false
                            if (!speechState.active && speechState.hasFeedback) {
                                updateSpeechState(EtaSpeechState())
                            }
                        },
                        speechLevel = { speechLevel },
                        onMicrophone = ::startSpeech,
                        onCancelSpeech = ::cancelSpeech,
                        onFinishSpeech = { speechInput.finish() },
                        onStopSpeaking = { voiceSession.stopSpeakingNow() },
                        onOpenSpeechSettings = {
                            openSpeechSettings(this)
                            dismissAndStop()
                        },
                        onKeyboard = ::switchToKeyboard,
                        input = inputText,
                        inputFocusRequestKey = inputFocusRequestKey,
                        onInputChange = {
                            inputText = it
                            if (!speechState.active && speechState.hasFeedback) {
                                updateSpeechState(EtaSpeechState())
                            }
                        },
                        onSuggestionClick = ::submitPrompt,
                        onSubmit = ::submitInput,
                        onStop = ::stopCurrentRun,
                        onClose = ::dismissAndStop,
                        canOpenConversation = activeRunId == null &&
                            uiState.messages.any { message ->
                                message is AgentMessageUi && message.content.isNotBlank()
                            },
                        handoffRunning = handoffInProgress,
                        exitRequested = handoffExitRequested,
                        onOpenConversation = ::openConversation,
                    )
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            dimAmount = 0f
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            title = "NexusAssistantOverlay"
        }
        runCatching { wm.addView(view, params) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_add_failed") {
                "Nexus assistant overlay addView failed: type=${throwable.javaClass.simpleName}"
            }
            return
        }
        windowManager = wm
        windowView = view
        windowParams = params
        registerSystemBackCallback(view)
        view.requestFocus()
    }

    private fun createComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusableInTouchMode = true
            setViewTreeLifecycleOwner(this@EtaAssistantOverlayService)
            setViewTreeSavedStateRegistryOwner(this@EtaAssistantOverlayService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent(content)
        }

    private fun registerSystemBackCallback(view: View) {
        unregisterSystemBackCallback()
        val dispatcher = view.findOnBackInvokedDispatcher()
        if (dispatcher == null) {
            AndroidAgentLogger.warn("Nexus assistant overlay back dispatcher unavailable")
            return
        }
        val callback = OnBackInvokedCallback(::dismissAndStop)
        dispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        backInvokedDispatcher = dispatcher
        backInvokedCallback = callback
    }

    private fun unregisterSystemBackCallback() {
        val dispatcher = backInvokedDispatcher
        val callback = backInvokedCallback
        backInvokedDispatcher = null
        backInvokedCallback = null
        if (dispatcher != null && callback != null) {
            dispatcher.unregisterOnBackInvokedCallback(callback)
        }
    }

    private fun showKeyboard(status: EtaVoiceStatus = EtaVoiceStatus.InputRequest) {
        entryInputPending = false
        uiState = uiState.copy(
            phase = EtaVoicePhase.READY,
            status = status,
        )
        updateSoftInput(visible = true)
        inputFocusRequestKey++
    }

    private fun submitInput() {
        val prompt = inputText.trim()
        if (prompt.isBlank() || activeRunId != null) return
        submitPrompt(prompt)
    }

    private fun submitPrompt(prompt: String) = submitPromptInternal(prompt, fromSpeech = false)

    private fun submitPromptInternal(prompt: String, fromSpeech: Boolean) {
        val normalized = prompt.trim()
        if (normalized.isBlank() || activeRunId != null) return
        continuousVoiceConversation = fromSpeech && speechSettings?.continuousConversation == true
        awaitingVoiceFollowUp = false
        entryInputPending = false
        inputFocusRequestKey = -1
        speechInput.cancel()
        updateSpeechState(EtaSpeechState())
        // 打字提交同样结束朗读，只是不需要接着开麦。
        if (voiceSession.state.value.phase == VoiceSessionPhase.SPEAKING) {
            voiceSession.stopSpeakingNow()
        } else {
            playback.stop()
        }
        voiceSession.onThinking()
        val capture = entryScreenContext
        inputText = ""
        activeRunId = UUID.randomUUID().toString()
        val runId = activeRunId ?: return
        uiState = uiState.copy(
            phase = EtaVoicePhase.PROCESSING,
            status = EtaVoiceStatus.Reasoning,
            messages = uiState.messages + UserMessageUi(
                id = "user-$runId",
                content = normalized,
            ),
        )
        updateSoftInput(visible = false)
        runJob = scope.launch {
            val speechConfig = if (fromSpeech) SpeechSettingsRepository.settings() else null
            val screen = capture?.snapshot() ?: EtaAssistantScreenContext.Snapshot()
            val runImages = listOfNotNull(screen.image)
            val config = AgentModelClient.loadConfig()
            val payload = AgentExternalArchivePayload(
                userText = normalized,
                conversationKey = conversationKey,
                title = normalized.take(40),
            )
            val result = runtimeClient.run(
                request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = normalized,
                    assistantScreenContext = screen.text,
                    config = config.copy(supportsSearchSuggestions = true),
                    images = runImages,
                    history = conversationHistory,
                    handoff = AgentRuntimeWire.EntryHandoff(
                        id = "$conversationKey:$runId",
                        source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                        payload = payload.toJson(),
                        dismissEntrySurfaceOnForegroundOperation = true,
                    ),
                ),
                onEvent = { event -> handleRuntimeEvent(runId, event) },
            )
            val shouldStopAfterResult = withContext(Dispatchers.Main.immediate) {
                if (activeRunId != runId) return@withContext false
                flushPendingDelta(runId)
                activeRunId = null
                runJob = null
                if (result.contextSnapshot != null) {
                    conversationHistory = result.contextSnapshot.messages
                } else if (result.ok) {
                    conversationHistory = conversationHistory +
                        AgentModelClient.buildUserHistoryMessage(normalized, runImages) + result.transcript
                }
                if (result.ok) {
                    uiState = uiState.copy(
                        phase = EtaVoicePhase.READY,
                        status = EtaVoiceStatus.Completed,
                        messages = finishRunMessages(runId, result),
                    )
                } else {
                    uiState = uiState.copy(
                        phase = EtaVoicePhase.ERROR,
                        status = EtaVoiceStatus.Failed(result.error),
                        messages = finishRunMessages(runId, result),
                    )
                }
                if (!hiddenForForegroundOperation && windowView?.isShown == true && result.ok &&
                    getSystemService(PowerManager::class.java).isInteractive &&
                    speechConfig?.autoSpeak == true && speechConfig.tts != TtsProvider.NONE) {
                    val messageId = uiState.messages.filterIsInstance<AgentMessageUi>().lastOrNull()?.id ?: runId
                    playback.speak(messageId, result.content, settings = speechConfig)
                    awaitingVoiceFollowUp = continuousVoiceConversation
                } else if (continuousVoiceConversation && result.ok && !hiddenForForegroundOperation &&
                    getSystemService(PowerManager::class.java).isInteractive
                ) {
                    awaitingVoiceFollowUp = true
                    scheduleVoiceFollowUp()
                } else if (!result.ok && fromSpeech) {
                    continuousVoiceConversation = false
                }
                if (!hiddenForForegroundOperation) {
                    updateSoftInput(visible = false)
                }
                // A foreground tool temporarily removes the overlay so the target app can
                // receive input. Keep the assistant service alive and restore its panel after
                // the tool result, allowing a voice conversation to continue without returning
                // to Eta manually.
                val restoreAfterForeground = hiddenForForegroundOperation
                if (restoreAfterForeground) {
                    resumeAfterForegroundOperation()
                }
                false
            }
            runtimeClient.ackResult(runId)
            if (shouldStopAfterResult) {
                withContext(Dispatchers.Main.immediate) {
                    if (activeRunId == null) {
                        removeWindow()
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun resumeAfterForegroundOperation() {
        hiddenForForegroundOperation = false
        entryInputPending = true
        entryEntranceComplete = true
        entryContentReady = true
        presentedEntryGeneration = -1L
        entryGeneration++
        scope.launch(Dispatchers.Main.immediate) {
            delay(220L)
            if (activeRunId == null && windowView == null) {
                showWindow()
                presentEntryInput()
            }
        }
    }

    private fun handleRuntimeEvent(runId: String, event: AgentEvent) {
        scope.launch(Dispatchers.Main.immediate) {
            if (activeRunId != runId) return@launch
            if (AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event)) {
                hideForForegroundOperation()
            }
            if (event is AgentEvent.AssistantBlockDelta) {
                if (event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL || event.delta.isEmpty()) return@launch
                eventCoalescer.append(runId, event)?.let { ready ->
                    uiState = projectRuntimeEvent(runId, ready, uiState)
                }
                if (deltaFlushJob?.isActive != true) {
                    deltaFlushJob = scope.launch(Dispatchers.Main.immediate) {
                        delay(50)
                        deltaFlushJob = null
                        if (activeRunId == runId) flushPendingDelta(runId)
                    }
                }
            } else {
                flushPendingDelta(runId)
                uiState = projectRuntimeEvent(runId, event, uiState)
            }
        }
    }

    private fun flushPendingDelta(runId: String) {
        deltaFlushJob?.cancel()
        deltaFlushJob = null
        eventCoalescer.flush(runId)?.let { event ->
            uiState = projectRuntimeEvent(runId, event, uiState)
        }
    }

    private fun projectRuntimeEvent(
        runId: String,
        event: AgentEvent,
        state: EtaVoiceUiState,
    ): EtaVoiceUiState {
        var messages = runMessageProjector.applyEvent(runId, event, state.messages)
        var status = state.status
        var phase = state.phase
        when (event) {
            is AgentEvent.UserSupplementReceived -> {
                val id = "user-$runId-supplement-${event.index}"
                if (messages.none { it.id == id }) {
                    messages = messages + UserMessageUi(id = id, content = event.text)
                }
            }
            is AgentEvent.ToolStarted -> status = EtaVoiceStatus.RunningTool(event.name)
            is AgentEvent.HostedToolStarted -> status = EtaVoiceStatus.RunningTool(event.name)
            is AgentEvent.RunFailed -> {
                phase = EtaVoicePhase.ERROR
                status = EtaVoiceStatus.Failed(event.reason)
            }
            is AgentEvent.ContextCompaction,
            is AgentEvent.ModelRetryScheduled,
            is AgentEvent.ProviderRequestStarted,
            -> status = EtaVoiceStatus.Reasoning
            else -> Unit
        }
        return state.copy(messages = messages, phase = phase, status = status)
    }

    private fun finishRunMessages(
        runId: String,
        result: AgentRuntimeWire.RunResult,
    ): List<AgentChatMessageUi> {
        var messages = runMessageProjector.finalizeText(
            runId,
            runMessageProjector.finalizeThinking(runId, uiState.messages),
        )
        if (!result.ok) {
            messages = runMessageProjector.failRunningTools(
                result.error ?: SYNTHETIC_RUNTIME_FAILED,
                messages,
            )
        }
        val notice = when {
            result.ok && result.content.isBlank() -> SystemNoticeCode.EmptyResult
            !result.ok && result.error == LEGACY_STOPPED_ERROR -> SystemNoticeCode.Stopped
            !result.ok -> SystemNoticeCode.RuntimeFailed
            else -> null
        }
        messages = AgentRunMessageProjector.applyResult(
            runId, messages, result.content, notice,
            detail = result.error.takeIf { notice == SystemNoticeCode.RuntimeFailed },
        )
        runMessageProjector.clearRun(runId)
        return messages
    }

    private fun stopCurrentRun() {
        val runId = activeRunId
        if (runId != null) {
            flushPendingDelta(runId)
            activeRunId = null
            requestRuntimeCancellation(runId)
            runJob?.cancel()
            runJob = null
            uiState = uiState.copy(
                phase = EtaVoicePhase.READY,
                status = EtaVoiceStatus.Stopped,
                messages = runMessageProjector.failRunningTools(
                    SYNTHETIC_STOPPED,
                    runMessageProjector.finalizeText(
                        runId,
                        runMessageProjector.finalizeThinking(runId, uiState.messages),
                    ),
                ),
            )
            runMessageProjector.clearRun(runId)
            updateSoftInput(visible = true)
            inputFocusRequestKey++
        } else {
            dismissAndStop()
        }
    }

    private fun cancelCurrentRun() {
        voiceSession.release()
        playback.stop()
        speechInput.cancel()
        updateSpeechState(EtaSpeechState())
        val runId = activeRunId ?: return
        flushPendingDelta(runId)
        activeRunId = null
        requestRuntimeCancellation(runId)
        runJob?.cancel()
        runJob = null
    }

    private fun requestRuntimeCancellation(runId: String) {
        runCatching {
            cancellationExecutor.execute { runtimeClient.cancelRun(runId) }
        }
    }

    private fun updateSoftInput(visible: Boolean) {
        val wm = windowManager ?: return
        val view = windowView ?: return
        val params = windowParams ?: return
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
            if (visible) {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            }
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun hideForForegroundOperation(onComplete: ((Boolean) -> Unit)? = null) {
        hiddenForForegroundOperation = true
        EtaVoiceInteractionSession.requestHideForForegroundOperation(this)
        removeWindow(onComplete)
    }

    private fun removeWindow(onComplete: ((Boolean) -> Unit)? = null) {
        entryInputPending = false
        voiceSession.release()
        playback.stop()
        speechInput.cancel()
        speechForeground.stop()
        updateSpeechState(EtaSpeechState())
        unregisterSystemBackCallback()
        detachingWindowView?.let { detachingView ->
            onComplete?.let(windowDetachCallbacks::add)
            if (!detachingView.isAttachedToWindow) {
                finishWindowDetach(success = true)
            }
            return
        }

        val view = windowView
        val wm = windowManager
        if (view == null || wm == null || !view.isAttachedToWindow) {
            windowView = null
            windowParams = null
            windowManager = null
            onComplete?.invoke(true)
            return
        }

        onComplete?.let(windowDetachCallbacks::add)
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                finishWindowDetach(success = true)
            }
        }
        detachingWindowView = view
        view.addOnAttachStateChangeListener(attachListener)

        val removed = runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
            wm.removeView(view)
            view.disposeComposition()
            true
        }.getOrElse { throwable ->
            view.removeOnAttachStateChangeListener(attachListener)
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_remove_failed") {
                "Nexus assistant overlay removeView failed: type=${throwable.javaClass.simpleName}"
            }
            false
        }
        if (!removed) {
            finishWindowDetach(success = false)
            return
        }

        windowView = null
        windowParams = null
        windowManager = null
        if (!view.isAttachedToWindow) {
            view.removeOnAttachStateChangeListener(attachListener)
            finishWindowDetach(success = true)
        }
    }

    private fun finishWindowDetach(success: Boolean) {
        detachingWindowView = null
        val callbacks = windowDetachCallbacks.toList()
        windowDetachCallbacks.clear()
        callbacks.forEach { callback -> callback(success) }
    }

    private fun dismissAndStop() {
        if (dismissalJob?.isActive == true) return
        entryGeneration++
        EtaAssistantScreenContexts.release(entryScreenContext?.id)
        entryScreenContext = null
        cancelCurrentRun()
        handoffExitRequested = true
        dismissalJob = scope.launch(Dispatchers.Main.immediate) {
            delay(HANDOFF_EXIT_DURATION_MS)
            removeWindow()
            stopSelf()
        }
    }

    private fun openConversation() {
        if (handoffInProgress || activeRunId != null || uiState.messages.isEmpty()) return
        handoffInProgress = true
        AndroidAgentLogger.info("Nexus assistant handoff requested")
        updateSoftInput(visible = false)
        val intent = NexusUiContract.launchIntent(this)
            .setAction(ACTION_OPEN_CONVERSATION)
            .putExtra(EXTRA_CONVERSATION_KEY, conversationKey)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        val creatorOptions = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                pendingIntentCreatorBackgroundActivityStartMode =
                    if (Build.VERSION.SDK_INT >= 36) {
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                    } else {
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    }
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            HANDOFF_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptions.toBundle(),
        )
        val senderOptions = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                pendingIntentBackgroundActivityStartMode =
                    if (Build.VERSION.SDK_INT >= 36) {
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                    } else {
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    }
            }
        }
        runCatching { pendingIntent.send(this, 0, null, null, null, null, senderOptions.toBundle()) }
            .onFailure {
                handoffInProgress = false
                AndroidAgentLogger.warn("Nexus assistant handoff activity launch failed")
                return
            }
        scope.launch(Dispatchers.Main.immediate) {
            delay(HANDOFF_TIMEOUT_MS)
            if (handoffInProgress) {
                AndroidAgentLogger.warn("Nexus assistant handoff timed out waiting for chat")
                handoffInProgress = false
            }
        }
    }

    private fun finishHandoff() {
        if (!handoffInProgress) return
        if (handoffExitRequested) return
        AndroidAgentLogger.info("Nexus assistant handoff chat ready")
        handoffExitRequested = true
        scope.launch(Dispatchers.Main.immediate) {
            delay(HANDOFF_EXIT_DURATION_MS)
            handoffInProgress = false
            removeWindow()
            stopSelf()
        }
    }

    internal companion object {
        const val ACTION_SHOW = "io.github.mangi.eta.agent.voice.SHOW"
        const val ACTION_OPEN_CONVERSATION = "io.github.mangi.eta.agent.voice.OPEN_CONVERSATION"
        const val EXTRA_CONVERSATION_KEY = "io.github.mangi.eta.agent.voice.extra.CONVERSATION_KEY"
        private const val EXTRA_SCREEN_CONTEXT_ID = "assistant_screen_context_id"
        private const val ACTION_HANDOFF_READY = "io.github.mangi.eta.agent.voice.HANDOFF_READY"
        private const val HANDOFF_TIMEOUT_MS = 5_000L
        private const val HANDOFF_EXIT_DURATION_MS = 220L
        private const val HANDOFF_REQUEST_CODE = 0x455441
        private const val LEGACY_STOPPED_ERROR = "已停止"
        private const val SYNTHETIC_STOPPED = "eta_status:stopped"
        private const val SYNTHETIC_RUNTIME_FAILED = "eta_status:runtime_failed"
        private const val FOREGROUND_DISMISS_TIMEOUT_MS = 2_000L
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var activeService: EtaAssistantOverlayService? = null

        /**
         * Eta 自己拥有入口浮层，直接关闭并等待具体 View detach；不能按包名猜测，
         * 因为入口、Runtime 与结果浮层都属于同一个包。
         */
        fun dismissForForegroundOperation(context: Context): Boolean {
            val service = activeService
            if (service == null) {
                EtaVoiceInteractionSession.requestHideForForegroundOperation(context)
                return true
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.hideForForegroundOperation()
                return service.windowView == null && service.detachingWindowView == null
            }

            val completed = CountDownLatch(1)
            val dismissed = AtomicBoolean(false)
            mainHandler.post {
                val current = activeService
                if (current == null) {
                    EtaVoiceInteractionSession.requestHideForForegroundOperation(context)
                    dismissed.set(true)
                    completed.countDown()
                } else if (current !== service) {
                    completed.countDown()
                } else {
                    current.hideForForegroundOperation { success ->
                        dismissed.set(success)
                        completed.countDown()
                    }
                }
            }
            return try {
                completed.await(FOREGROUND_DISMISS_TIMEOUT_MS, TimeUnit.MILLISECONDS) &&
                    dismissed.get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        fun show(context: Context, screenContextId: String?) {
            context.applicationContext.startService(
                Intent(context.applicationContext, EtaAssistantOverlayService::class.java)
                    .setAction(ACTION_SHOW)
                    .putExtra(EXTRA_SCREEN_CONTEXT_ID, screenContextId),
            )
        }

        fun dismiss(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, EtaAssistantOverlayService::class.java),
            )
        }

        fun notifyHandoffReady(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, EtaAssistantOverlayService::class.java)
                    .setAction(ACTION_HANDOFF_READY),
            )
        }
    }
}
