package io.github.mangi.eta.agent.voice

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.*
import android.provider.Settings
import com.k2fsa.sherpa.onnx.*
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream

internal enum class WakeListeningPhase { STOPPED, STARTING, LISTENING, PAUSED, STOPPING, FAILED }
internal data class WakeListeningState(val phase: WakeListeningPhase = WakeListeningPhase.STOPPED, val message: String = "", val keyword: String? = null)

internal object BackgroundWakeWordRuntime {
    private val mutableState = MutableStateFlow(WakeListeningState())
    val state: StateFlow<WakeListeningState> = mutableState.asStateFlow()
    @Volatile internal var service: BackgroundWakeWordService? = null
    internal fun publish(value: WakeListeningState) { mutableState.value = value }
    fun pauseCapture() { service?.pauseCapture() }
    fun memoryPressure() { service?.pauseForMemory() }
    suspend fun yieldMicrophone() {
        val current = service ?: return
        current.pauseCapture()
        withTimeout(1_500) { current.awaitCaptureStopped() }
    }
    fun start(context: Context) {
        val app = context.applicationContext
        require(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "请先授予麦克风权限" }
        require(Settings.canDrawOverlays(app)) { "请先允许助手悬浮窗" }
        val config = WakeWordStore(app).read()
        val record = requireNotNull(LocalModelRepository.record(config.modelId)) { "请选择已安装的唤醒模型" }
        require("keyword" in record.outputModalities && config.words.isNotEmpty()) { "请选择专用唤醒模型并设置唤醒词" }
        LocalSpeechNativeCrashGuard.confirmRetry(record, LocalSpeechKind.ASR, "sherpa-wake@2.0.1")
        app.startForegroundService(Intent(app, BackgroundWakeWordService::class.java).setAction(BackgroundWakeWordService.START))
    }
    fun stop(context: Context) { context.applicationContext.stopService(Intent(context, BackgroundWakeWordService::class.java)) }
    suspend fun stopModel(context: Context, modelId: String) {
        val current = service
        if (current != null && WakeWordStore(context).read().modelId == modelId) {
            current.pauseCapture()
            withTimeout(5_000) { current.awaitCaptureStopped() }
            stop(context)
        }
    }
}

internal interface WakeWordEngine : AutoCloseable { fun accept(frame: ByteArray): String? }

internal class SherpaWakeWordEngine(context: Context, record: LocalModelRecord, words: List<String>) : WakeWordEngine {
    private val spec = requireNotNull(SpeechModelPackage.inspect(LocalModelRepository.artifactFile(context, record))) { "唤醒模型包不完整" }
    private val spotter: KeywordSpotter
    private val stream: OnlineStream
    private val keywordFile: java.io.File
    init {
        require(spec.architecture == "keyword-spotter") { "请选择专用唤醒模型" }
        val keywords = WakeKeywordCompiler.compile(words, java.io.File(spec.path("tokens")))
        keywordFile = java.io.File.createTempFile("nexus-wake-", ".txt", context.cacheDir)
        try {
        keywordFile.writeText(keywords, Charsets.UTF_8)
        spotter = KeywordSpotter(config = KeywordSpotterConfig(modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(spec.path("encoder"), spec.path("decoder"), spec.path("joiner")),
            tokens = spec.path("tokens"), provider = "cpu", modelType = "zipformer2", numThreads = record.runtimeSettings.threads.coerceIn(1, 2)), keywordsFile = keywordFile.absolutePath))
        try { stream = spotter.createStream() } catch (failure: Throwable) { spotter.release(); throw failure }
        } catch (failure: Throwable) { keywordFile.delete(); throw failure }
    }
    override fun accept(frame: ByteArray): String? {
        stream.acceptWaveform(pcm16ToFloat(frame), SpeechRecorder.RATE)
        var count = 0
        while (spotter.isReady(stream)) {
            check(++count <= 1024) { "唤醒解码超过单帧上限" }; spotter.decode(stream)
            val keyword = spotter.getResult(stream).keyword.trim()
            if (keyword.isNotBlank()) { spotter.reset(stream); return keyword }
        }
        return null
    }
    override fun close() { try { stream.release() } finally { try { spotter.release() } finally { keywordFile.delete() } } }
}

/** Explicit microphone foreground session; never starts microphone capture from a boot receiver. */
class BackgroundWakeWordService : Service() {
    internal var engineFactory: (Context, LocalModelRecord, List<String>) -> WakeWordEngine = ::SherpaWakeWordEngine
    internal var captureFactory: () -> SpeechAudioCapture = { SpeechRecorder(backgroundWake = true) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: Job? = null
    @Volatile private var recorder: SpeechAudioCapture? = null
    private var observation: Job? = null
    private var settingsObservation: Job? = null
    private var modelObservation: Job? = null
    private var reconfiguration: Job? = null
    private var cooldownUntil = 0L
    private var resourcePaused = false
    private var lock: PowerManager.WakeLock? = null
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        reconfiguration?.cancel()
        reconfiguration = scope.launch { delay(150); pauseCapture(); runner?.join(); reconcile() }
    }
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) reconcile()
        }
    }
    override fun onCreate() {
        super.onCreate(); BackgroundWakeWordRuntime.service = this
        LocalModelRepository.init(this)
        getSharedPreferences("eta_wake_words", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(settingsListener)
        registerReceiver(screen, IntentFilter(Intent.ACTION_USER_PRESENT), RECEIVER_NOT_EXPORTED)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") WakeWordStore(this).let { it.write(it.read().copy(enabled = false)) }
        if (intent?.action != START || !WakeWordStore(this).read().enabled) { stopSelf(); return START_NOT_STICKY }
        try {
            promote()
            resourcePaused = false
            if (observation == null) observation = scope.launch { SpeechAudioLease.occupied.collect { reconcile() } }
            if (settingsObservation == null) settingsObservation = scope.launch {
                SpeechSettingsRepository.settingsFlow().collect { if (!it.recognitionEnabled) { pauseCapture(); stopSelf() } }
            }
            if (modelObservation == null) modelObservation = scope.launch {
                LocalModelRepository.models.map { models ->
                    models.firstOrNull { it.id == WakeWordStore(this@BackgroundWakeWordService).read().modelId }?.let { it.sha256 to it.runtimeSettings.threads }
                }.distinctUntilChanged().drop(1).collect { pauseCapture(); runner?.join(); reconcile() }
            }
            reconcile()
        } catch (failure: Exception) { fail(failure) }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    internal fun pauseCapture() {
        recorder?.cancel()
        runner?.cancel()
        lock?.let { if (it.isHeld) it.release() }; lock = null
    }
    internal suspend fun awaitCaptureStopped() { runner?.join() }
    internal fun pauseForMemory() {
        resourcePaused = true; pauseCapture()
        BackgroundWakeWordRuntime.publish(WakeListeningState(WakeListeningPhase.PAUSED, "内存不足，已暂停唤醒"))
    }
    private fun reconcile() {
        val config = WakeWordStore(this).read()
        if (!config.enabled) { stopSelf(); return }
        if (resourcePaused) return
        if (SpeechAudioLease.occupied.value || SystemClock.elapsedRealtime() < cooldownUntil) {
            pauseCapture(); BackgroundWakeWordRuntime.publish(WakeListeningState(WakeListeningPhase.PAUSED, "语音会话使用中")); return
        }
        if (runner?.isActive == true) return
        val old = runner
        runner = scope.launch {
            old?.join()
            if (SpeechAudioLease.occupied.value) return@launch
            try {
                val record = LocalModelRepository.record(config.modelId)
                    ?: throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请选择已安装的唤醒模型")
                BackgroundWakeWordRuntime.publish(WakeListeningState(WakeListeningPhase.STARTING))
                withContext(Dispatchers.Default) {
                    check(!LocalSpeechNativeCrashGuard.hasNativeFailure(record, LocalSpeechKind.ASR, "sherpa-wake@2.0.1")) { "唤醒模型上次发生原生崩溃，请点击开始聆听重试" }
                    val attempt = LocalSpeechNativeCrashGuard.beginAttempt(record, LocalSpeechKind.ASR, LocalRuntimeBackendPolicy.CPU,
                        "background-kws", "sherpa-wake@2.0.1")
                    try { engineFactory(this@BackgroundWakeWordService, record, config.words).use { engine ->
                        while (isActive) {
                            if (SpeechAudioLease.occupied.value) throw CancellationException("Wake microphone yielded")
                            capture(engine, config)
                        }
                    } } finally { attempt.completed() }
                }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Throwable) { fail(failure) }
        }
    }
    private suspend fun capture(engine: WakeWordEngine, config: WakeWordConfig) = coroutineScope {
        if (SpeechAudioLease.occupied.value) throw CancellationException("Wake microphone yielded")
        val manager = getSystemService(AudioManager::class.java)
        if (manager.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)) {
            BackgroundWakeWordRuntime.publish(WakeListeningState(WakeListeningPhase.PAUSED, "通话中")); delay(1_000); return@coroutineScope
        }
        val audio = captureFactory(); recorder = audio
        val preroll = SpeechPrerollBuffer(SpeechRecorder.RATE * 2 * 3)
        val recording = launch { audio.record({ BackgroundWakeWordRuntime.publish(WakeListeningState(WakeListeningPhase.LISTENING)) }, {}) }
        try {
            lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nexus:WakeWord").apply {
                setReferenceCounted(false); acquire(90_000)
            }
            for (frame in audio.frames) {
                currentCoroutineContext().ensureActive(); preroll.add(frame)
                val word = engine.accept(frame) ?: continue
                audio.finish()
                val accepted = if (config.voiceprintEnabled) {
                    val pcm = preroll.drain().fold(ByteArrayOutputStream()) { out, part -> out.apply { write(part) } }.toByteArray()
                    val features = LocalSpeakerModel.extract(this@BackgroundWakeWordService, pcm)
                    VoiceprintProfileStore(this@BackgroundWakeWordService).verify(features?.bytes, config.voiceprintThreshold, features?.modelIdentity.orEmpty()) == VoiceprintVerification.VERIFIED
                } else true
                if (accepted) withContext(Dispatchers.Main.immediate) {
                    cooldownUntil = SystemClock.elapsedRealtime() + 2_500
                    BackgroundWakeWordRuntime.publish(WakeListeningState(WakeListeningPhase.PAUSED, "唤醒已识别", word))
                    EtaAssistantOverlayService.showFromWake(this@BackgroundWakeWordService)
                }
                if (accepted) { delay(2_600); return@coroutineScope }
                break
            }
        } finally {
            audio.cancel(); recording.cancelAndJoin()
            if (recorder === audio) recorder = null
            lock?.let { if (it.isHeld) it.release() }; lock = null
        }
    }
    private fun promote() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "语音唤醒", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, BackgroundWakeWordService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Nexus 语音唤醒").setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
        startForeground(4211, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
    private fun fail(failure: Throwable) {
        pauseCapture()
        BackgroundWakeWordRuntime.publish(WakeListeningState(WakeListeningPhase.FAILED, failure.message?.take(180) ?: "唤醒未启动"))
        stopSelf()
    }
    override fun onDestroy() {
        pauseCapture(); observation?.cancel(); settingsObservation?.cancel(); modelObservation?.cancel(); scope.cancel()
        getSharedPreferences("eta_wake_words", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(settingsListener)
        unregisterReceiver(screen); stopForeground(STOP_FOREGROUND_REMOVE)
        if (BackgroundWakeWordRuntime.service === this) BackgroundWakeWordRuntime.service = null
        if (BackgroundWakeWordRuntime.state.value.phase != WakeListeningPhase.FAILED) {
            BackgroundWakeWordRuntime.publish(WakeListeningState(WakeListeningPhase.STOPPING))
            val ending = runner
            if (ending == null) BackgroundWakeWordRuntime.publish(WakeListeningState())
            else ending.invokeOnCompletion {
                if (BackgroundWakeWordRuntime.service == null && BackgroundWakeWordRuntime.state.value.phase == WakeListeningPhase.STOPPING)
                    BackgroundWakeWordRuntime.publish(WakeListeningState())
            }
        }
        super.onDestroy()
    }
    companion object { const val START = "io.github.zhh_lgtm.nexus.START_WAKE_WORD"; private const val CHANNEL = "nexus_wake_word" }
}
