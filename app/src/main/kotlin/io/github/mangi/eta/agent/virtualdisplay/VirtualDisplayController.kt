package io.github.mangi.eta.agent.virtualdisplay

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceToolContract
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 虚拟屏会话与生命周期中枢。
 *
 * 画面通过常驻 [ImageReader] 接收：
 * - 收起态（仅胶囊微缩取景器可见）按 ~6fps 节流更新，兼顾真实动感与低功耗；
 * - 展开态（画廊装裱视窗 / 用户手动接管）提升至 ~30fps 实时更新；
 * - Agent 调用 observe_screen(include_screenshot=true) 时直接提取最新原尺寸 Bitmap，无需等待无障碍截图冷却。
 */
/** Owns one virtual-display resource bundle. Each live display must have its own instance. */
internal class VirtualDisplaySessionController(
    internal val registryKey: String = UUID.randomUUID().toString(),
) {

    private val HEALTH_CHECK_INTERVAL_MS = 3_000L
    private val RECOVERY_WINDOW_MS = 60_000L
    private val MAX_RECOVERY_ATTEMPTS = 1

    private val lock = Any()
    @Volatile private var disposed = false
    private val mutableSnapshots = MutableStateFlow(VirtualDisplaySnapshot.Inactive)
    val snapshots: StateFlow<VirtualDisplaySnapshot> = mutableSnapshots.asStateFlow()

    private val frameSequenceGenerator = AtomicLong(0L)
    private val touchSequenceGenerator = AtomicLong(0L)
    private val manualGeneration = AtomicLong(0L)
    private val sessionSequence = AtomicLong(0L)
    private val releaseGeneration = AtomicLong(0L)
    private val healthScheduleGeneration = AtomicLong(0L)
    private val manualInputExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { action -> Thread(action, "eta-vd-manual-input").apply { isDaemon = true } }
    private val healthExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "eta-vd-health").apply { isDaemon = true }
    }

    @Volatile
    private var highFrequencyPreview: Boolean = false


    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var inProcessVirtualDisplay: VirtualDisplay? = null
    private var rootBridgeProcess: Process? = null
    private var rootSurfaceToken: String? = null
    private var processSupervisor: ShellProcessSupervisor? = null

    private var activeDisplayId: Int = Display.DEFAULT_DISPLAY
    private var displayWidth: Int = 1080
    private var displayHeight: Int = 2400
    private var displayDensityDpi: Int = 420
    private var latestFullBitmap: Bitmap? = null
    private var lastUiFrameAtElapsedMs: Long = 0L
    private val mutedPackages = linkedSetOf<String>()
    private var controllerContext: Context? = null
    private var rootAvailability: () -> Boolean = { RootAccess.isGranted }
    private var currentSessionId: String = ""
    private var currentBackend: VirtualDisplayBackend = VirtualDisplayBackend.NONE
    private var currentFlagProfile = VirtualDisplayFlagProfile.UNKNOWN
    private var healthCheck: ScheduledFuture<*>? = null
    private var recoveryAttempt: Int = 0
    private var lastRecoveryAtElapsedMs: Long = Long.MIN_VALUE
    private var recoveryInFlight: Boolean = false
    private var lastLaunch: LaunchSnapshot? = null
    private var unownedSinceElapsedMs: Long = 0L

    private sealed interface LaunchSnapshot {
        val packageName: String
        val appName: String
        val intent: Intent

        data class App(
            override val packageName: String,
            override val appName: String,
            override val intent: Intent,
        ) : LaunchSnapshot

        data class Uri(
            override val packageName: String,
            override val appName: String,
            override val intent: Intent,
        ) : LaunchSnapshot
    }

    private data class InProcessDisplayCreation(
        val display: VirtualDisplay,
        val flagProfile: VirtualDisplayFlagProfile,
    )

    private data class RootDisplayCreation(
        val displayId: Int,
        val flagProfile: VirtualDisplayFlagProfile,
    )

    fun isBackendAvailable(rootAvailable: Boolean, lsposedAvailable: Boolean): Boolean =
        rootAvailable || lsposedAvailable

    fun setStageExpanded(expanded: Boolean) {
        highFrequencyPreview = expanded
    }

    fun currentLease(): VirtualDisplayLease? = synchronized(lock) { mutableSnapshots.value.lease }

    fun validate(lease: VirtualDisplayLease?): Boolean = synchronized(lock) {
        lease != null && mutableSnapshots.value.lease == lease
    }

    /** Accept focus evidence only for the exact active display lease and its current session. */
    internal fun recordFocusObservation(
        lease: VirtualDisplayLease?,
        observation: VirtualDisplayFocusObservation,
        observedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean = synchronized(lock) {
        val current = mutableSnapshots.value
        if (
            lease == null || current.lease != lease ||
            observation == VirtualDisplayFocusObservation.UNKNOWN || observedAtElapsedMs <= 0L
        ) return false
        mutableSnapshots.value = current.copy(
            focusObservation = observation,
            focusObservedAtElapsedMs = observedAtElapsedMs,
        )
        true
    }

    /** Fence an external display launch and its snapshot update against session/owner replacement. */
    internal fun dispatchIfSessionCurrent(
        expected: VirtualDisplaySnapshot,
        dispatch: () -> Unit,
    ): Boolean = synchronized(lock) {
        val current = mutableSnapshots.value
        if (
            !current.active || current.lifecycle != VirtualDisplayLifecycle.ACTIVE ||
            current.sessionId != expected.sessionId || current.displayId != expected.displayId ||
            current.ownerRunId != expected.ownerRunId ||
            (expected.lease != null && current.lease != expected.lease)
        ) return false
        dispatch()
        true
    }

    /** Run one bounded platform dispatch while lease transfer is fenced. */
    internal fun dispatchIfLeaseCurrent(lease: VirtualDisplayLease?, dispatch: () -> Unit): Boolean =
        synchronized(lock) {
            if (lease == null || mutableSnapshots.value.lease != lease) return false
            dispatch()
            true
        }

    /** Creation must still own the allocation after teardown or a competing owner has run. */
    internal fun reservationStamp(): Pair<Long, Long> = synchronized(lock) {
        releaseGeneration.get() to mutableSnapshots.value.ownershipGeneration
    }

    internal fun <T> withRunReservation(ownerRunId: String, expected: Pair<Long, Long>, action: () -> T): T? = synchronized(lock) {
        if (disposed || reservationStamp() != expected ||
            (ownerRunId.isNotBlank() && mutableSnapshots.value.ownerRunId != ownerRunId)) return null
        action()
    }

    /**
     * A caller is allowed when it presents the exact active lease. The legacy owner-string form is
     * accepted only when no lease was captured, so an old lease can never act on a newer session.
     */
    private fun ownedByCallerLocked(lease: VirtualDisplayLease?, ownerRunId: String): Boolean {
        val current = mutableSnapshots.value
        if (lease != null) return current.lease == lease
        if (ownerRunId.isBlank()) return current.ownerRunId.isBlank()
        return current.ownerRunId.isBlank() || current.ownerRunId == ownerRunId
    }

    fun setUserControlling(controlling: Boolean, lease: VirtualDisplayLease? = null) {
        synchronized(lock) {
            val current = mutableSnapshots.value
            if (!validate(lease)) return
            if (
                !current.active ||
                current.lifecycle != VirtualDisplayLifecycle.ACTIVE ||
                current.userControlling == controlling
            ) return
            mutableSnapshots.value = current.copy(userControlling = controlling, manualInputGeneration = manualGeneration.incrementAndGet())
            if (controlling) highFrequencyPreview = true
        }
    }

    fun currentDisplayId(): Int = synchronized(lock) {
        activeDisplayId.takeIf { CanvasRoutingPolicy.isVirtualDisplay(it) } ?: Display.DEFAULT_DISPLAY
    }

    /** Claim the existing single display for one Run; the empty owner state is reusable. */
    fun claimRunOwnership(runId: String): Boolean = synchronized(lock) {
        if (runId.isBlank() || disposed) return false
        val current = mutableSnapshots.value
        val owner = current.ownerRunId
        if (owner.isNotBlank() && owner != runId) return false
        if (owner.isBlank()) mutableSnapshots.value = current.copy(ownerRunId = runId, previewOwnerRunId = "", ownershipGeneration = current.ownershipGeneration + 1L)
        unownedSinceElapsedMs = 0L
        true
    }

    /** Return the exact active lease to its current owner, or claim a reusable single display. */
    fun claimLease(ownerRunId: String): VirtualDisplayLease? = synchronized(lock) {
        if (ownerRunId.isBlank()) return null
        val current = mutableSnapshots.value
        if (!current.active || current.lifecycle != VirtualDisplayLifecycle.ACTIVE) return null
        if (current.ownerRunId.isNotBlank() && current.ownerRunId != ownerRunId) return null
        if (current.ownerRunId.isBlank()) mutableSnapshots.value = current.copy(ownerRunId = ownerRunId, previewOwnerRunId = "", ownershipGeneration = current.ownershipGeneration + 1L)
        mutableSnapshots.value.lease
    }

    /**
     * Release exactly one lease. A late closer holding an old (owner, session, display) triple must not
     * drop the ownership of a newer session of the same Run.
     */
    fun releaseLease(lease: VirtualDisplayLease?): Boolean {
        if (lease == null) return false
        return synchronized(lock) {
            val current = mutableSnapshots.value
            if (current.lease != lease) return false
            mutableSnapshots.value = current.copy(ownerRunId = "", previewOwnerRunId = current.ownerRunId,
                userControlling = false, ownershipGeneration = current.ownershipGeneration + 1L)
            unownedSinceElapsedMs = SystemClock.elapsedRealtime()
            true
        }
    }

    /**
     * 确保受信任虚拟屏已就绪并返回其 displayId；不可用时返回 null，调用方必须明确报告失败。
     */
    fun ensureVirtualDisplay(
        context: Context,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        ownerRunId: String = "",
        expectedLease: VirtualDisplayLease? = null,
    ): Int? {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (disposed) return null
            // A stale lease must never trigger session creation; the caller has to reacquire first.
            if (expectedLease != null && !validate(expectedLease)) return null
            controllerContext = appContext
            rootAvailability = rootAvailable
            if (isCurrentDisplayHealthyLocked(appContext)) {
                if (ownerRunId.isNotBlank() && !claimRunOwnership(ownerRunId)) return null
                if (ownerRunId.isBlank() && mutableSnapshots.value.ownerRunId.isNotBlank()) return null
                return activeDisplayId
            }
            if (ownerRunId.isNotBlank() && !claimRunOwnership(ownerRunId)) return null
            if (ownerRunId.isBlank() && mutableSnapshots.value.ownerRunId.isNotBlank()) return null
        }
        val (width, height, densityDpi) = resolvePhysicalDisplayMetrics(appContext)
        synchronized(lock) {
            if (disposed) return null
            if (expectedLease != null && !validate(expectedLease)) return null
            if (isCurrentDisplayHealthyLocked(appContext)) {
                if (ownerRunId.isNotBlank() && !claimRunOwnership(ownerRunId)) return null
                if (ownerRunId.isBlank() && mutableSnapshots.value.ownerRunId.isNotBlank()) return null
                return activeDisplayId
            }
            releaseResourcesLocked(
                appContext,
                rootAvailable,
                restoreAudio = !recoveryInFlight,
            )
            currentSessionId = "vd-$registryKey-${sessionSequence.incrementAndGet()}"
            currentBackend = VirtualDisplayBackend.NONE
            currentFlagProfile = VirtualDisplayFlagProfile.UNKNOWN
            mutableSnapshots.value = VirtualDisplaySnapshot(
                width = width,
                height = height,
                sessionId = currentSessionId,
                ownerRunId = ownerRunId,
                lifecycle = VirtualDisplayLifecycle.STARTING,
                recoveryAttempt = recoveryAttempt,
            )

            var workerToClean: HandlerThread? = null
            val frameResources = runCatching {
                val worker = HandlerThread("eta-virtual-display-frame").apply { start() }
                workerToClean = worker
                val workerHandler = Handler(worker.looper)
                val frameReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
                frameReader.setOnImageAvailableListener(::onFrameAvailable, workerHandler)
                Triple(worker, workerHandler, frameReader)
            }.getOrElse { throwable ->
                workerToClean?.let { worker -> runCatching { worker.quitSafely() } }
                val failedSession = currentSessionId
                releaseResourcesLocked(
                    appContext,
                    rootAvailable,
                    restoreAudio = !recoveryInFlight,
                )
                mutableSnapshots.value = VirtualDisplaySnapshot(
                    width = width,
                    height = height,
                    sessionId = failedSession,
                    ownerRunId = ownerRunId,
                    lifecycle = VirtualDisplayLifecycle.FAILED,
                    recoveryAttempt = recoveryAttempt,
                    failureReason = "FRAME_SOURCE_CREATE_FAILED",
                )
                AndroidAgentLogger.error(
                    "Virtual display frame source creation failed",
                    throwable,
                )
                return null
            }
            val thread = frameResources.first
            val handler = frameResources.second
            val reader = frameResources.third

            frameThread = thread
            frameHandler = handler
            imageReader = reader
            displayWidth = width
            displayHeight = height
            displayDensityDpi = densityDpi

            val createdInProcess = tryCreateInProcessVirtualDisplay(
                appContext = appContext,
                surface = reader.surface,
                width = width,
                height = height,
                densityDpi = densityDpi,
            )
            if (createdInProcess != null) {
                val id = createdInProcess.display.display?.displayId ?: Display.INVALID_DISPLAY
                if (CanvasRoutingPolicy.isVirtualDisplay(id)) {
                    inProcessVirtualDisplay = createdInProcess.display
                    activeDisplayId = id
                    currentBackend = VirtualDisplayBackend.IN_PROCESS
                    currentFlagProfile = createdInProcess.flagProfile
                    publishActiveLocked()
                    scheduleHealthCheckLocked(appContext, rootAvailable)
                    AndroidAgentLogger.info("Virtual display created in-process: displayId=$id size=${width}x$height")
                    return id
                }
                runCatching { createdInProcess.display.release() }
            }

            if (rootAvailable()) {
                val rootDisplay = tryCreateRootBridgedVirtualDisplay(
                    appContext = appContext,
                    surface = reader.surface,
                    width = width,
                    height = height,
                    densityDpi = densityDpi,
                    rootAvailable = rootAvailable,
                )
                if (rootDisplay != null && CanvasRoutingPolicy.isVirtualDisplay(rootDisplay.displayId)) {
                    activeDisplayId = rootDisplay.displayId
                    currentBackend = VirtualDisplayBackend.ROOT_BRIDGE
                    currentFlagProfile = rootDisplay.flagProfile
                    publishActiveLocked()
                    scheduleHealthCheckLocked(appContext, rootAvailable)
                    AndroidAgentLogger.info("Virtual display created via root bridge: displayId=${rootDisplay.displayId} profile=${rootDisplay.flagProfile.wireValue} size=${width}x$height")
                    return rootDisplay.displayId
                }
            }

            val failedSession = currentSessionId
            releaseResourcesLocked(
                appContext,
                rootAvailable,
                restoreAudio = !recoveryInFlight,
            )
            mutableSnapshots.value = VirtualDisplaySnapshot(
                width = width,
                height = height,
                sessionId = failedSession,
                ownerRunId = ownerRunId,
                lifecycle = VirtualDisplayLifecycle.FAILED,
                recoveryAttempt = recoveryAttempt,
                failureReason = "VIRTUAL_DISPLAY_CREATE_FAILED",
            )
            AndroidAgentLogger.warn("Virtual display creation failed after compatibility fallbacks")
            return null
        }
    }

    /**
     * 在虚拟屏上启动指定应用，并对其施加后台音频焦点静默保护。
     */
    fun launchAppOnVirtualDisplay(
        context: Context,
        packageName: String,
        appName: String,
        launchIntent: Intent,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        ownerRunId: String = "",
        lease: VirtualDisplayLease? = null,
    ): Int? {
        val expectedLease = lease
        val displayId = if (expectedLease != null) {
            if (ownerRunId.isNotBlank() && ownerRunId != expectedLease.ownerRunId) return null
            if (!validate(expectedLease)) return null
            ensureVirtualDisplay(
                context,
                rootAvailable,
                ownerRunId = expectedLease.ownerRunId,
                expectedLease = expectedLease,
            )
        } else {
            ensureVirtualDisplay(context, rootAvailable, ownerRunId)
        } ?: return null
        val appContext = context.applicationContext
        val launchSession = synchronized(lock) {
            mutableSnapshots.value.takeIf { current ->
                current.active && current.lifecycle == VirtualDisplayLifecycle.ACTIVE &&
                    current.displayId == displayId &&
                    (ownerRunId.isBlank() || current.ownerRunId == ownerRunId)
            }
        } ?: return null
        var launched = false
        val sessionCurrent = dispatchIfSessionCurrent(launchSession) {
            mutePackageAudioFocusIfPossible(appContext, packageName, rootAvailable)
            val startedInProcess = runCatching {
                val intent = Intent(launchIntent).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                appContext.startActivity(intent, options.toBundle())
                true
            }.getOrElse { throwable ->
                AndroidAgentLogger.debug {
                    "Virtual display startActivity in-process fallback: type=${throwable.safeLogType()}"
                }
                false
            }
            val rootStarted = if (!startedInProcess && rootAvailable()) {
                val component = launchIntent.component?.flattenToShortString()
                    ?: return@dispatchIfSessionCurrent
                val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
                try {
                    exec.execute(
                        "am start --user ${DeviceToolContract.appUserId(appContext)} --display $displayId -n ${DeviceToolContract.quote(component)}",
                        timeoutMillis = 6_000L,
                    ).exitCode == 0
                } finally {
                    exec.close()
                }
            } else {
                false
            }
            if (!startedInProcess && !rootStarted) return@dispatchIfSessionCurrent

            val current = mutableSnapshots.value
            mutableSnapshots.value = current.copy(
                appName = appName,
                packageName = packageName,
                materializingToMain = false,
                failureReason = null,
            )
            lastLaunch = LaunchSnapshot.App(
                packageName = packageName,
                appName = appName,
                intent = Intent(launchIntent),
            )
            if (!recoveryInFlight) recoveryAttempt = 0
            launched = true
        }
        return displayId.takeIf { sessionCurrent && launched }
    }

    /**
     * 在虚拟屏上打开外部 URI。
     */
    fun openUriOnVirtualDisplay(
        context: Context,
        intent: Intent,
        targetLabel: String,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        ownerRunId: String = "",
        lease: VirtualDisplayLease? = null,
    ): Int? {
        val expectedLease = lease
        val displayId = if (expectedLease != null) {
            if (ownerRunId.isNotBlank() && ownerRunId != expectedLease.ownerRunId) return null
            if (!validate(expectedLease)) return null
            ensureVirtualDisplay(
                context,
                rootAvailable,
                ownerRunId = expectedLease.ownerRunId,
                expectedLease = expectedLease,
            )
        } else {
            ensureVirtualDisplay(context, rootAvailable, ownerRunId)
        } ?: return null
        val appContext = context.applicationContext
        val launchSession = synchronized(lock) {
            mutableSnapshots.value.takeIf { current ->
                current.active && current.lifecycle == VirtualDisplayLifecycle.ACTIVE &&
                    current.displayId == displayId &&
                    (ownerRunId.isBlank() || current.ownerRunId == ownerRunId)
            }
        } ?: return null
        var launched = false
        val sessionCurrent = dispatchIfSessionCurrent(launchSession) {
            val started = runCatching {
                val launch = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                appContext.startActivity(launch, options.toBundle())
                true
            }.getOrDefault(false)
            val rootStarted = if (!started && rootAvailable()) {
                val uriString = intent.dataString ?: return@dispatchIfSessionCurrent
                val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
                try {
                    exec.execute(
                        "am start --user ${DeviceToolContract.appUserId(appContext)} --display $displayId -a android.intent.action.VIEW -d ${DeviceToolContract.quote(uriString)}",
                        timeoutMillis = 6_000L,
                    ).exitCode == 0
                } finally {
                    exec.close()
                }
            } else {
                false
            }
            if (!started && !rootStarted) return@dispatchIfSessionCurrent

            val current = mutableSnapshots.value
            val resolvedPackage = intent.resolveActivity(appContext.packageManager)?.packageName.orEmpty()
            mutableSnapshots.value = current.copy(
                appName = targetLabel.ifBlank { current.appName },
                packageName = resolvedPackage.ifBlank { current.packageName },
                materializingToMain = false,
                failureReason = null,
            )
            lastLaunch = LaunchSnapshot.Uri(
                packageName = resolvedPackage,
                appName = targetLabel.ifBlank { uriDisplayName(intent) },
                intent = Intent(intent),
            )
            if (!recoveryInFlight) recoveryAttempt = 0
            launched = true
        }
        return displayId.takeIf { sessionCurrent && launched }
    }

    /**
     * 将虚拟屏中正在运行的应用无缝破壁移交到物理主屏（DEFAULT_DISPLAY）。
     * 先触发 UI 层的 260ms 全屏放大贴合过渡，同时在底层原位迁移 RootTask。
     */
    fun handoffToMainDisplay(
        context: Context,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        ownerRunId: String = "",
        lease: VirtualDisplayLease? = null,
    ): Boolean {
        val snapshot = synchronized(lock) {
            val before = mutableSnapshots.value
            if (!before.active || !CanvasRoutingPolicy.isVirtualDisplay(before.displayId)) {
                return false
            }
            if (!ownedByCallerLocked(lease, ownerRunId)) return false
            val authorized = if (before.ownerRunId.isBlank() && ownerRunId.isNotBlank()) {
                before.copy(ownerRunId = ownerRunId)
            } else {
                before
            }
            val marked = authorized.copy(
                userControlling = false,
                materializingToMain = true,
            )
            mutableSnapshots.value = marked
            highFrequencyPreview = true
            marked
        }

        val appContext = context.applicationContext
        var moved = false
        val current = try { dispatchIfSessionCurrent(snapshot) {
            moved = moveVirtualTaskToMainDisplay(
                context = appContext,
                displayId = snapshot.displayId,
                packageName = snapshot.packageName,
                rootAvailable = rootAvailable,
            )
            if (moved) restoreMutedPackages(appContext, rootAvailable)
            else mutableSnapshots.value = mutableSnapshots.value.copy(materializingToMain = false)
        } } catch (failure: Exception) {
            synchronized(lock) {
                val currentSnapshot = mutableSnapshots.value
                if (currentSnapshot.sessionId == snapshot.sessionId) {
                    mutableSnapshots.value = currentSnapshot.copy(materializingToMain = false)
                }
            }
            AndroidAgentLogger.warn("Virtual display handoff failed: ${failure.javaClass.simpleName}")
            false
        }
        if (!current || !moved) return false

        // 留出 260ms 让 VirtualDisplayMaterializeOverlay 完成从画板到物理全屏的弹簧贴合动画
        try {
            Thread.sleep(260L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        release(appContext, rootAvailable, lease = snapshot.lease)
        return moved
    }

    /**
     * 提取虚拟屏最新一帧的完整 Bitmap 副本（供 observe_screen 使用，调用方负责 recycle）。
     */
    fun captureScreenshotBitmap(waitUpToMs: Long = 450L, expectedDisplayId: Int? = null,
        expectedSessionId: String? = null, ownerRunId: String = "",
        lease: VirtualDisplayLease? = null): Bitmap? {
        val deadline = SystemClock.elapsedRealtime() + waitUpToMs.coerceAtLeast(0L)
        val session = expectedSessionId ?: mutableSnapshots.value.sessionId
        while (true) {
            synchronized(lock) {
                val current = mutableSnapshots.value
                if (!current.active || current.lifecycle != VirtualDisplayLifecycle.ACTIVE || current.sessionId != session ||
                    expectedDisplayId != null && current.displayId != expectedDisplayId) return null
                if (!ownedByCallerLocked(lease, ownerRunId)) return null
                if (!current.hasFreshFrame(SystemClock.elapsedRealtime())) return null
                if (current.ownerRunId.isBlank() && ownerRunId.isNotBlank()) {
                    mutableSnapshots.value = current.copy(ownerRunId = ownerRunId)
                }
                val bmp = latestFullBitmap
                if (bmp != null && !bmp.isRecycled) {
                    return runCatching { bmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                }
                if (!CanvasRoutingPolicy.isVirtualDisplay(activeDisplayId)) return null
            }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            try {
                Thread.sleep(40L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    fun emitTapEffect(x: Int, y: Int, longPress: Boolean = false) {
        synchronized(lock) {
            val current = mutableSnapshots.value
            if (!current.active || current.width <= 0 || current.height <= 0) return
            val nx = (x.toFloat() / current.width).coerceIn(0f, 1f)
            val ny = (y.toFloat() / current.height).coerceIn(0f, 1f)
            val effect = VirtualTouchEffect.Tap(
                sequence = touchSequenceGenerator.incrementAndGet(),
                createdAtMs = SystemClock.uptimeMillis(),
                normalizedX = nx,
                normalizedY = ny,
                longPress = longPress,
            )
            mutableSnapshots.value = current.copy(touchEffect = effect)
        }
    }

    fun emitSwipeEffect(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 420) {
        synchronized(lock) {
            val current = mutableSnapshots.value
            if (!current.active || current.width <= 0 || current.height <= 0) return
            val effect = VirtualTouchEffect.Swipe(
                sequence = touchSequenceGenerator.incrementAndGet(),
                createdAtMs = SystemClock.uptimeMillis(),
                startX = (x1.toFloat() / current.width).coerceIn(0f, 1f),
                startY = (y1.toFloat() / current.height).coerceIn(0f, 1f),
                endX = (x2.toFloat() / current.width).coerceIn(0f, 1f),
                endY = (y2.toFloat() / current.height).coerceIn(0f, 1f),
                durationMs = durationMs.coerceIn(160, 1_500),
            )
            mutableSnapshots.value = current.copy(touchEffect = effect)
        }
    }

    /**
     * 用户在画廊视窗画布上直接触摸时，将归一化坐标注入虚拟屏。
     */
    fun dispatchUserTapOnCanvas(
        normalizedX: Float,
        normalizedY: Float,
        longPress: Boolean = false,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        ownerRunId: String = "",
        lease: VirtualDisplayLease? = null,
        withControl: ((() -> Unit) -> Unit) = { it() },
    ) {
        val snapshot = mutableSnapshots.value
        if (!snapshot.active || !snapshot.userControlling || !CanvasRoutingPolicy.isVirtualDisplay(snapshot.displayId) || !normalizedX.isFinite() || !normalizedY.isFinite()) return
        val allowed = synchronized(lock) { ownedByCallerLocked(lease, ownerRunId) }
        if (!allowed) return
        val x = (normalizedX.coerceIn(0f, 1f) * (snapshot.width - 1)).roundToInt()
        val y = (normalizedY.coerceIn(0f, 1f) * (snapshot.height - 1)).roundToInt()
        emitTapEffect(x, y, longPress = longPress)
        manualInputExecutor.execute { withControl {
            if (!isSameManualSession(snapshot, mutableSnapshots.value)) return@withControl
            val duration = if (longPress) 650L else 55L
            val gesture = AgentAccessibilityService.current()
                ?.gestureTap(
                    x.toFloat(), y.toFloat(), durationMs = duration,
                    displayId = snapshot.displayId, expectedLease = lease,
                )
            if (gesture?.code == "ACTION_OUTCOME_UNKNOWN" || gesture?.code == "DISPLAY_LEASE_EXPIRED") return@withControl
            if (gesture?.ok != true && isSameManualSession(snapshot, mutableSnapshots.value) &&
                lease != null && rootAvailable()
            ) {
                val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
                try {
                    val cmd = if (longPress) {
                        "input -d ${snapshot.displayId} swipe $x $y $x $y 650"
                    } else {
                        "input -d ${snapshot.displayId} tap $x $y"
                    }
                    dispatchIfLeaseCurrent(lease) { exec.execute(cmd, timeoutMillis = 5_000L) }
                } finally {
                    exec.close()
                }
            }
        } }
    }

    fun dispatchUserSwipeOnCanvas(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Int = 320,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        ownerRunId: String = "",
        lease: VirtualDisplayLease? = null,
        withControl: ((() -> Unit) -> Unit) = { it() },
    ) {
        val snapshot = mutableSnapshots.value
        if (!snapshot.active || !snapshot.userControlling || !CanvasRoutingPolicy.isVirtualDisplay(snapshot.displayId) || listOf(startX, startY, endX, endY).any { !it.isFinite() }) return
        val allowed = synchronized(lock) { ownedByCallerLocked(lease, ownerRunId) }
        if (!allowed) return
        val x1 = (startX.coerceIn(0f, 1f) * (snapshot.width - 1)).roundToInt()
        val y1 = (startY.coerceIn(0f, 1f) * (snapshot.height - 1)).roundToInt()
        val x2 = (endX.coerceIn(0f, 1f) * (snapshot.width - 1)).roundToInt()
        val y2 = (endY.coerceIn(0f, 1f) * (snapshot.height - 1)).roundToInt()
        val safeDuration = durationMs.coerceIn(140, 1_000)
        emitSwipeEffect(x1, y1, x2, y2, safeDuration)
        manualInputExecutor.execute { withControl {
            if (!isSameManualSession(snapshot, mutableSnapshots.value)) return@withControl
            val gesture = AgentAccessibilityService.current()
                ?.gestureSwipe(
                    x1.toFloat(),
                    y1.toFloat(),
                    x2.toFloat(),
                    y2.toFloat(),
                    durationMs = safeDuration.toLong(),
                    displayId = snapshot.displayId,
                    expectedLease = lease,
                )
            if (gesture?.code == "ACTION_OUTCOME_UNKNOWN" || gesture?.code == "DISPLAY_LEASE_EXPIRED") return@withControl
            if (gesture?.ok != true && isSameManualSession(snapshot, mutableSnapshots.value) &&
                lease != null && rootAvailable()
            ) {
                val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
                try {
                    dispatchIfLeaseCurrent(lease) {
                        exec.execute(
                            "input -d ${snapshot.displayId} swipe $x1 $y1 $x2 $y2 $safeDuration",
                            timeoutMillis = 5_000L,
                        )
                    }
                } finally {
                    exec.close()
                }
            }
        } }
    }

    fun release(
        context: Context? = null,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        ownerRunId: String = "",
        lease: VirtualDisplayLease? = null,
    ) {
        synchronized(lock) {
            val current = mutableSnapshots.value
            if (lease != null) {
                if (current.lease != lease) return
            } else if (!ownedByCallerLocked(null, ownerRunId)) return
            releaseGeneration.incrementAndGet()
            if (!hasResourcesLocked() && mutableSnapshots.value.lifecycle == VirtualDisplayLifecycle.IDLE) {
                recoveryInFlight = false
                recoveryAttempt = 0
                lastLaunch = null
                return
            }
            if (current.active || current.lifecycle != VirtualDisplayLifecycle.IDLE) {
                mutableSnapshots.value = current.copy(
                    active = false,
                    lifecycle = VirtualDisplayLifecycle.RELEASING,
                    userControlling = false,
                    frame = null,
                    touchEffect = null,
                )
            }
            releaseResourcesLocked(
                context = context,
                rootAvailable = rootAvailable,
                restoreAudio = true,
            )
            recoveryInFlight = false
            recoveryAttempt = 0
            lastLaunch = null
            currentSessionId = ""
            currentBackend = VirtualDisplayBackend.NONE
            currentFlagProfile = VirtualDisplayFlagProfile.UNKNOWN
            controllerContext = null
            mutableSnapshots.value = VirtualDisplaySnapshot.Inactive
        }
    }

    internal fun shutdownExecutors() {
        synchronized(lock) {
            disposed = true
            healthExecutor.shutdownNow()
            manualInputExecutor.shutdownNow()
        }
    }

    internal fun releaseCurrent(context: Context?, rootAvailable: () -> Boolean = { RootAccess.isGranted }) =
        synchronized(lock) {
            release(context, rootAvailable, ownerRunId = mutableSnapshots.value.ownerRunId)
        }

    internal fun releaseForOwner(ownerRunId: String, context: Context?, expectedReleaseGeneration: Long? = null): Boolean = synchronized(lock) {
        if (expectedReleaseGeneration != null && releaseGeneration.get() != expectedReleaseGeneration) return false
        val current = mutableSnapshots.value
        if (ownerRunId.isBlank() || (current.ownerRunId != ownerRunId &&
                (current.ownerRunId.isNotBlank() || current.previewOwnerRunId != ownerRunId))) return false
        releaseCurrent(context)
        true
    }

    internal fun releaseIfIdle(nowElapsedMs: Long, idleAfterMs: Long): Boolean = synchronized(lock) {
        val current = mutableSnapshots.value
        if (!current.active || current.ownerRunId.isNotBlank() || current.userControlling ||
            current.materializingToMain || unownedSinceElapsedMs <= 0L ||
            nowElapsedMs - unownedSinceElapsedMs < idleAfterMs) return false
        releaseCurrent(controllerContext)
        true
    }

    private fun isSameManualSession(before: VirtualDisplaySnapshot, current: VirtualDisplaySnapshot) =
        current.active && current.lifecycle == VirtualDisplayLifecycle.ACTIVE && current.userControlling &&
            current.displayId == before.displayId && current.sessionId == before.sessionId &&
            current.ownerRunId == before.ownerRunId &&
            current.manualInputGeneration == before.manualInputGeneration

    private fun releaseResourcesLocked(
        context: Context?,
        rootAvailable: () -> Boolean,
        restoreAudio: Boolean,
    ) {
        highFrequencyPreview = false
        activeDisplayId = Display.DEFAULT_DISPLAY
        // A queued health task may already be waiting for this lock. Invalidate it before its
        // display and image resources are torn down, even though cancel(false) cannot stop it.
        healthScheduleGeneration.incrementAndGet()
        healthCheck?.cancel(false)
        healthCheck = null

        if (restoreAudio && mutedPackages.isNotEmpty()) restoreMutedPackages(context ?: controllerContext, rootAvailable)

        inProcessVirtualDisplay?.let { vd ->
            runCatching { vd.release() }
        }
        inProcessVirtualDisplay = null

        val bridge = rootBridgeProcess
        val supervisor = processSupervisor
        rootBridgeProcess = null
        processSupervisor = null
        rootSurfaceToken?.let(VirtualDisplaySurfaceRegistry::unregister)
        rootSurfaceToken = null
        if (bridge != null) {
            thread(name = "eta-vd-bridge-cleanup", isDaemon = true) {
                runCatching {
                    bridge.outputStream.write("release\n".toByteArray(Charsets.UTF_8))
                    bridge.outputStream.flush()
                    bridge.outputStream.close()
                }
                if (supervisor != null) {
                    supervisor.beginClosing()
                    if (bridge.isAlive) supervisor.terminateAndReap(bridge) else supervisor.reapProcess(bridge)
                    supervisor.unregisterProcess(bridge)
                } else {
                    runCatching { bridge.destroyForcibly() }
                }
            }
        }

        imageReader?.let { reader ->
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
        }
        imageReader = null

        frameThread?.let { thread ->
            runCatching { thread.quitSafely() }
        }
        frameThread = null
        frameHandler = null

        latestFullBitmap?.let { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
        }
        latestFullBitmap = null
        lastUiFrameAtElapsedMs = 0L
    }

    private fun hasResourcesLocked(): Boolean =
        imageReader != null || inProcessVirtualDisplay != null || rootBridgeProcess != null || frameThread != null

    private fun publishActiveLocked() {
        val current = mutableSnapshots.value
        val now = SystemClock.elapsedRealtime()
        mutableSnapshots.value = current.copy(
            active = true,
            displayId = activeDisplayId,
            width = displayWidth,
            height = displayHeight,
            sessionId = currentSessionId,
            lifecycle = VirtualDisplayLifecycle.ACTIVE,
            backend = currentBackend,
            flagProfile = currentFlagProfile,
            focusObservation = VirtualDisplayFocusObservation.UNKNOWN,
            focusObservedAtElapsedMs = 0L,
            recoveryAttempt = recoveryAttempt,
            failureReason = null,
            startedAtElapsedMs = now,
        )
    }

    private fun isCurrentDisplayHealthyLocked(context: Context): Boolean {
        if (imageReader == null || !CanvasRoutingPolicy.isVirtualDisplay(activeDisplayId)) return false
        if (currentBackend == VirtualDisplayBackend.ROOT_BRIDGE && rootBridgeProcess?.isAlive != true) {
            return false
        }
        val displayManager = context.getSystemService(DisplayManager::class.java) ?: return false
        return displayManager.getDisplay(activeDisplayId) != null
    }

    private fun scheduleHealthCheckLocked(
        context: Context,
        rootAvailable: () -> Boolean,
    ) {
        healthCheck?.cancel(false)
        val probe = VirtualDisplayHealthProbe(
            sessionId = currentSessionId,
            displayId = activeDisplayId,
            scheduleGeneration = healthScheduleGeneration.incrementAndGet(),
        )
        healthCheck = healthExecutor.scheduleWithFixedDelay(
            {
                val failure = synchronized(lock) {
                    val snapshot = mutableSnapshots.value
                    when {
                        !isHealthProbeCurrentLocked(probe, snapshot) -> null
                        !isCurrentDisplayHealthyLocked(context) -> "DISPLAY_OR_BRIDGE_LOST"
                        !snapshot.hasFreshFrame(SystemClock.elapsedRealtime()) -> {
                            if (snapshot.failureReason != "FRAME_STREAM_STALLED") {
                                mutableSnapshots.value = snapshot.copy(failureReason = "FRAME_STREAM_STALLED")
                            }
                            null
                        }
                        else -> {
                            if (snapshot.failureReason == "FRAME_STREAM_STALLED") {
                                mutableSnapshots.value = snapshot.copy(failureReason = null)
                            }
                            null
                        }
                    }
                }
                if (failure != null) requestRecovery(context, rootAvailable, failure, probe)
            },
            HEALTH_CHECK_INTERVAL_MS,
            HEALTH_CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun requestRecovery(
        context: Context,
        rootAvailable: () -> Boolean,
        failureReason: String,
        probe: VirtualDisplayHealthProbe,
    ) {
        val recoveryPlan = synchronized(lock) {
            if (!isHealthProbeCurrentLocked(probe, mutableSnapshots.value)) return
            if (recoveryInFlight) return
            val current = mutableSnapshots.value
            val ownerLease = current.lease
            val now = SystemClock.elapsedRealtime()
            if (now - lastRecoveryAtElapsedMs < RECOVERY_WINDOW_MS && recoveryAttempt >= MAX_RECOVERY_ATTEMPTS) {
                failLocked(context, rootAvailable, current.copy(active = false), "RECOVERY_EXHAUSTED")
                return
            }
            recoveryInFlight = true
            lastRecoveryAtElapsedMs = now
            recoveryAttempt += 1
            mutableSnapshots.value = current.copy(
                active = false,
                lifecycle = VirtualDisplayLifecycle.RECOVERING,
                recoveryAttempt = recoveryAttempt,
                failureReason = failureReason,
                userControlling = false,
                touchEffect = null,
            )
            releaseResourcesLocked(context, rootAvailable, restoreAudio = false)
            Triple(lastLaunch, releaseGeneration.get(), ownerLease)
        }
        AndroidAgentLogger.warn("Virtual display recovery started: reason=$failureReason")
        val launch = recoveryPlan.first
        val generation = recoveryPlan.second
        val ownerLease = recoveryPlan.third
        val recoveredDisplayId = synchronized(lock) {
            if (releaseGeneration.get() != generation) {
                recoveryInFlight = false
                return
            }
            ensureVirtualDisplay(
                context,
                rootAvailable,
                ownerRunId = ownerLease?.ownerRunId.orEmpty(),
            )
        }
        val relaunched = synchronized(lock) {
            if (releaseGeneration.get() != generation || disposed ||
                mutableSnapshots.value.ownerRunId != ownerLease?.ownerRunId.orEmpty()) return
            if (recoveredDisplayId != null && launch != null) when (launch) {
                is LaunchSnapshot.App -> launchAppOnVirtualDisplay(
                    context = context,
                    packageName = launch.packageName,
                    appName = launch.appName,
                    launchIntent = launch.intent,
                    rootAvailable = rootAvailable,
                    ownerRunId = ownerLease?.ownerRunId.orEmpty(),
                ) != null
                is LaunchSnapshot.Uri -> openUriOnVirtualDisplay(
                    context = context,
                    intent = launch.intent,
                    targetLabel = launch.appName,
                    rootAvailable = rootAvailable,
                    ownerRunId = ownerLease?.ownerRunId.orEmpty(),
                ) != null
            } else recoveredDisplayId != null
        }
        synchronized(lock) {
            recoveryInFlight = false
            if (releaseGeneration.get() != generation) return
            if (!relaunched) {
                failLocked(
                    context,
                    rootAvailable,
                    mutableSnapshots.value.copy(active = false),
                    "RESTORE_FAILED",
                )
            } else {
                AndroidAgentLogger.info("Virtual display recovery completed: displayId=$recoveredDisplayId")
            }
        }
    }

    /** A queued scheduler task is valid only for the exact active display session that scheduled it. */
    internal fun isHealthProbeCurrentLocked(
        probe: VirtualDisplayHealthProbe,
        snapshot: VirtualDisplaySnapshot,
    ): Boolean =
        !recoveryInFlight &&
            probe.scheduleGeneration == healthScheduleGeneration.get() &&
            snapshot.active &&
            snapshot.lifecycle == VirtualDisplayLifecycle.ACTIVE &&
            snapshot.sessionId == probe.sessionId &&
            snapshot.displayId == probe.displayId

    private fun failLocked(
        context: Context,
        rootAvailable: () -> Boolean,
        failedSnapshot: VirtualDisplaySnapshot,
        reason: String,
    ) {
        releaseResourcesLocked(context, rootAvailable, restoreAudio = true)
        currentBackend = VirtualDisplayBackend.NONE
        currentFlagProfile = VirtualDisplayFlagProfile.UNKNOWN
        lastLaunch = null
        mutableSnapshots.value = failedSnapshot.copy(
            active = false,
            displayId = failedSnapshot.displayId,
            lifecycle = VirtualDisplayLifecycle.FAILED,
            backend = VirtualDisplayBackend.NONE,
            flagProfile = VirtualDisplayFlagProfile.UNKNOWN,
            focusObservation = VirtualDisplayFocusObservation.UNKNOWN,
            focusObservedAtElapsedMs = 0L,
            userControlling = false,
            materializingToMain = false,
            frame = null,
            touchEffect = null,
            recoveryAttempt = recoveryAttempt,
            failureReason = reason,
        )
        AndroidAgentLogger.warn("Virtual display stopped: reason=$reason")
    }

    private fun onFrameAvailable(reader: ImageReader) {
        synchronized(lock) { if (imageReader !== reader) return }
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return

        try {
            val now = SystemClock.elapsedRealtime()
            val minIntervalMs = if (highFrequencyPreview) 30L else 160L
            val shouldUpdateUi = now - lastUiFrameAtElapsedMs >= minIntervalMs || latestFullBitmap == null
            if (!shouldUpdateUi) return

            val bitmap = decodeImageToBitmap(image) ?: return
            var oldBitmap: Bitmap? = null
            synchronized(lock) {
                if (imageReader !== reader || !CanvasRoutingPolicy.isVirtualDisplay(activeDisplayId)) {
                    bitmap.recycle()
                    return
                }
                oldBitmap = latestFullBitmap
                latestFullBitmap = bitmap
                if (shouldUpdateUi) {
                    lastUiFrameAtElapsedMs = now
                    val previewBitmap = createUiPreviewBitmap(bitmap)
                    val current = mutableSnapshots.value
                    if (current.active) {
                        mutableSnapshots.value = current.copy(
                            frame = previewBitmap.asImageBitmap(),
                            frameSequence = frameSequenceGenerator.incrementAndGet(),
                            lastFrameAtElapsedMs = now,
                            failureReason = current.failureReason.takeUnless { it == "FRAME_STREAM_STALLED" },
                        )
                    }
                }
            }
            oldBitmap?.let { old ->
                if (!old.isRecycled) old.recycle()
            }
        } catch (_: Throwable) {
            // 单帧解码异常不影响后续帧流
        } finally {
            runCatching { image.close() }
        }
    }

    private fun decodeImageToBitmap(image: Image): Bitmap? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer ?: return null
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        if (paddedWidth < width) return null

        val rawBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        rawBitmap.copyPixelsFromBuffer(buffer)
        return if (paddedWidth == width) {
            rawBitmap
        } else {
            val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
            rawBitmap.recycle()
            cropped
        }
    }

    private fun createUiPreviewBitmap(source: Bitmap): Bitmap {
        val maxTargetHeight = if (highFrequencyPreview) 1080 else 480
        if (source.height <= maxTargetHeight) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val ratio = maxTargetHeight.toFloat() / source.height.toFloat()
        val targetW = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val targetH = maxTargetHeight
        return source.scale(targetW, targetH)
    }

    @android.annotation.SuppressLint("WrongConstant")
    private fun tryCreateInProcessVirtualDisplay(
        appContext: Context,
        surface: android.view.Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): InProcessDisplayCreation? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/hardware/display/") }
        }
        val dm = appContext.getSystemService(DisplayManager::class.java) ?: return null
        for (flags in CanvasRoutingPolicy.FLAG_CANDIDATES) {
            val display = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val config = VirtualDisplayConfig.Builder(
                        CanvasRoutingPolicy.VIRTUAL_DISPLAY_NAME,
                        width,
                        height,
                        densityDpi,
                    )
                        .setSurface(surface)
                        .setFlags(flags)
                        .build()
                    dm.createVirtualDisplay(config)
                } else {
                    dm.createVirtualDisplay(
                        CanvasRoutingPolicy.VIRTUAL_DISPLAY_NAME,
                        width,
                        height,
                        densityDpi,
                        surface,
                        flags,
                    )
                }
            }.getOrNull()
            val displayId = display?.display?.displayId ?: Display.INVALID_DISPLAY
            if (display != null && CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                if (flags != CanvasRoutingPolicy.TRUSTED_INTERACTIVE_FLAGS) {
                    AndroidAgentLogger.warn("Virtual display in-process compatibility flags=$flags")
                }
                return InProcessDisplayCreation(display, CanvasRoutingPolicy.flagProfile(flags))
            }
            display?.let { runCatching { it.release() } }
        }
        return null
    }

    private fun tryCreateRootBridgedVirtualDisplay(
        appContext: Context,
        surface: android.view.Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        rootAvailable: () -> Boolean,
    ): RootDisplayCreation? {
        val token = VirtualDisplaySurfaceRegistry.register(surface)
        val supervisor = ShellProcessSupervisor(rootAvailable = rootAvailable)
        val apkPath = appContext.applicationInfo.sourceDir
        val cmd = "CLASSPATH=${DeviceToolContract.quote(apkPath)} app_process /system/bin " +
            "io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayCommandMain"
        val process = supervisor.startShellProcess("root", cmd, mergeStderr = true)
        if (process == null) {
            VirtualDisplaySurfaceRegistry.unregister(token)
            return null
        }

        val readyLatch = CountDownLatch(1)
        var resolvedDisplay: RootDisplayCreation? = null
        val payload = JSONObject()
            .put("action", "hold_display")
            .put("user_id", DeviceToolContract.appUserId(appContext))
            .put("surface_token", token)
            .put("width", width)
            .put("height", height)
            .put("density_dpi", densityDpi)
            .toString() + "\n"

        thread(name = "eta-vd-bridge-stdout", isDaemon = true) {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val ready = VirtualDisplayCommandMain.parseDisplayReadyLine(line)
                        if (ready != null) {
                            resolvedDisplay = RootDisplayCreation(
                                ready.displayId,
                                CanvasRoutingPolicy.flagProfile(ready.flags),
                            )
                            readyLatch.countDown()
                        } else if (line.startsWith(VirtualDisplayCommandMain.RESULT_PREFIX)) {
                            readyLatch.countDown()
                        }
                    }
                }
            }
            readyLatch.countDown()
        }

        val written = runCatching {
            process.outputStream.write(payload.toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
            true
        }.getOrDefault(false)

        if (!written) {
            VirtualDisplaySurfaceRegistry.unregister(token)
            supervisor.beginClosing()
            supervisor.terminateAndReap(process)
            supervisor.unregisterProcess(process)
            return null
        }

        val awaited = runCatching { readyLatch.await(4, TimeUnit.SECONDS) }.getOrDefault(false)
        val displayCreation = resolvedDisplay
        if (!awaited || displayCreation == null || !CanvasRoutingPolicy.isVirtualDisplay(displayCreation.displayId)) {
            VirtualDisplaySurfaceRegistry.unregister(token)
            supervisor.beginClosing()
            if (process.isAlive) supervisor.terminateAndReap(process) else supervisor.reapProcess(process)
            supervisor.unregisterProcess(process)
            return null
        }

        rootBridgeProcess = process
        processSupervisor = supervisor
        rootSurfaceToken = token
        return displayCreation
    }

    private fun moveVirtualTaskToMainDisplay(
        context: Context,
        displayId: Int,
        packageName: String,
        rootAvailable: () -> Boolean,
    ): Boolean {
        // 1. 尝试进程内通过 system_server Hook 放行的 moveRootTaskToDisplay 迁移
        val movedInProcess = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityTaskManager", "Landroid/app/IActivityTaskManager")
            }
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val service = atmClass.getMethod("getService").invoke(null) ?: return@runCatching false
            val getTasks = service.javaClass.methods.firstOrNull { it.name == "getTasks" && it.parameterCount >= 1 }
            val tasks = when (getTasks?.parameterCount) {
                1 -> getTasks.invoke(service, 40)
                3 -> getTasks.invoke(service, 40, false, false)
                4 -> getTasks.invoke(service, 40, false, false, Display.INVALID_DISPLAY)
                else -> null
            } as? List<*> ?: emptyList<Any?>()
            var targetTaskId = -1
            for (item in tasks) {
                val info = item as? android.app.TaskInfo ?: continue
                val taskDisplay = runCatching {
                    android.app.TaskInfo::class.java.getField("displayId").getInt(info)
                }.getOrDefault(Display.INVALID_DISPLAY)
                val topPkg = info.topActivity?.packageName ?: info.baseActivity?.packageName
                if (taskDisplay == displayId && (packageName.isEmpty() || topPkg == packageName)) {
                    targetTaskId = info.taskId
                    break
                }
            }
            if (targetTaskId > 0) {
                val moveMethod = service.javaClass.getMethod(
                    "moveRootTaskToDisplay",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                moveMethod.invoke(service, targetTaskId, Display.DEFAULT_DISPLAY)
                true
            } else {
                false
            }
        }.getOrDefault(false)

        if (movedInProcess) return true

        // 2. Root 特权桥调用 moveRootTaskToDisplay
        if (rootAvailable()) {
            val apkPath = context.applicationInfo.sourceDir
            val cmd = "CLASSPATH=${DeviceToolContract.quote(apkPath)} app_process /system/bin " +
                "io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayCommandMain"
            val req = JSONObject()
                .put("action", "move_to_main")
                .put("display_id", displayId)
                .put("package_name", packageName)
                .toString() + "\n"
            val supervisor = ShellProcessSupervisor(rootAvailable = rootAvailable)
            val proc = supervisor.startShellProcess("root", cmd, mergeStderr = true)
            if (proc != null) {
                var outcomeUnknown = false
                val outputReader = Executors.newSingleThreadExecutor { action ->
                    Thread(action, "eta-vd-handoff-output").apply { isDaemon = true }
                }
                val output = outputReader.submit<String> {
                    proc.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(2_048)
                        val result = StringBuilder()
                        while (result.length < 32_768) {
                            val count = reader.read(buffer, 0, minOf(buffer.size, 32_768 - result.length))
                            if (count < 0) break
                            result.append(buffer, 0, count)
                        }
                        result.toString()
                    }
                }
                val movedByRoot = try {
                    proc.outputStream.write(req.toByteArray(Charsets.UTF_8))
                    proc.outputStream.flush()
                    proc.outputStream.close()
                    if (!proc.waitFor(4, TimeUnit.SECONDS)) throw java.util.concurrent.TimeoutException()
                    val out = output.get(250L, TimeUnit.MILLISECONDS)
                    out.contains("\"ok\":true")
                } catch (_: Throwable) {
                    outcomeUnknown = true
                    false
                } finally {
                    supervisor.beginClosing()
                    if (proc.isAlive) supervisor.terminateAndReap(proc) else supervisor.reapProcess(proc)
                    supervisor.unregisterProcess(proc)
                    output.cancel(true)
                    outputReader.shutdownNow()
                }
                if (movedByRoot) return true
                if (outcomeUnknown) return false
            }
        }

        // 3. 兜底：以 SINGLE_TOP + NEW_TASK 在 DEFAULT_DISPLAY 唤起该应用
        if (packageName.isNotBlank()) {
            return runCatching {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    ?: return@runCatching false
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                context.startActivity(intent, options.toBundle())
                true
            }.getOrDefault(false)
        }
        return false
    }

    private fun mutePackageAudioFocusIfPossible(
        context: Context,
        packageName: String,
        rootAvailable: () -> Boolean,
    ) {
        if (!rootAvailable() || !DeviceToolContract.validPackageName(packageName)) return
        val added = synchronized(lock) { mutedPackages.add(packageName) }
        if (!added) return
        VirtualDisplayAudioFocusRegistry.acquire(packageName) { muted ->
            applyPackageAudioFocus(context, packageName, rootAvailable, muted)
        }
    }

    private fun restoreMutedPackages(
        context: Context?,
        rootAvailable: () -> Boolean,
    ) {
        val toRestore = synchronized(lock) {
            val copy = mutedPackages.toList()
            mutedPackages.clear()
            copy
        }
        if (toRestore.isEmpty()) return
        val appContext = context?.applicationContext ?: controllerContext
        for (pkg in toRestore) {
            VirtualDisplayAudioFocusRegistry.release(pkg) { muted ->
                if (appContext != null) applyPackageAudioFocus(appContext, pkg, rootAvailable, muted)
            }
        }
    }

    private fun applyPackageAudioFocus(
        context: Context,
        packageName: String,
        rootAvailable: () -> Boolean,
        muted: Boolean,
    ) {
        if (!rootAvailable() || !DeviceToolContract.validPackageName(packageName)) return
        val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
        try {
            val action = if (muted) "ignore" else "allow"
            exec.execute(
                "cmd appops set --user ${DeviceToolContract.appUserId(context)} ${DeviceToolContract.quote(packageName)} TAKE_AUDIO_FOCUS $action",
                timeoutMillis = 3_000L,
            )
        } catch (_: Throwable) {
            // Some ROMs do not expose this app-op.
        } finally {
            exec.close()
        }
    }

    private fun resolvePhysicalDisplayMetrics(context: Context): Triple<Int, Int, Int> {
        val dm = context.resources.displayMetrics
        var width = dm.widthPixels.coerceAtLeast(720)
        var height = dm.heightPixels.coerceAtLeast(1280)
        val density = dm.densityDpi.coerceIn(160, 640)
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val point = Point()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealSize(point)
            if (point.x > 0 && point.y > 0) {
                width = point.x
                height = point.y
            }
        }
        val sourcePixels = width.toLong() * height.toLong()
        val scale = if (sourcePixels > MAX_FRAME_PIXELS) {
            sqrt(MAX_FRAME_PIXELS.toDouble() / sourcePixels.toDouble()).toFloat()
        } else {
            1f
        }
        val safeWidth = (width * scale).roundToInt().coerceAtLeast(720)
        val safeHeight = (height * scale).roundToInt().coerceAtLeast(1280)
        val safeDensity = (density * scale).roundToInt().coerceIn(160, 640)
        return Triple(safeWidth, safeHeight, safeDensity)
    }

    private fun uriDisplayName(intent: Intent): String =
        intent.data?.host?.takeIf { it.isNotBlank() }
            ?: intent.data?.scheme?.takeIf { it.isNotBlank() }
            ?: intent.`package`.orEmpty()

    private val MAX_FRAME_PIXELS = 1080L * 2400L
}

/** Prevent one display teardown from unmuting an app still used by a sibling display. */
internal object VirtualDisplayAudioFocusRegistry {
    private val lock = Any()
    private val references = mutableMapOf<String, Int>()
    private val executor = Executors.newSingleThreadExecutor { action ->
        Thread(action, "eta-vd-audio-focus").apply { isDaemon = true }
    }

    fun acquire(packageName: String, apply: (muted: Boolean) -> Unit) {
        synchronized(lock) {
            val count = references[packageName] ?: 0
            references[packageName] = count + 1
            if (count == 0) executor.execute { runCatching { apply(true) } }
        }
    }

    fun release(packageName: String, apply: (muted: Boolean) -> Unit) {
        synchronized(lock) {
            val count = references[packageName] ?: return
            if (count <= 1) {
                references.remove(packageName)
                executor.execute { runCatching { apply(false) } }
            } else {
                references[packageName] = count - 1
            }
        }
    }
}

internal data class VirtualDisplayHealthProbe(
    val sessionId: String,
    val displayId: Int,
    val scheduleGeneration: Long,
)

/** Compatibility facade that routes session-scoped work to the controller owning that lease. */
internal object VirtualDisplayController {
    private val primary = VirtualDisplaySessionController()
    private val registry = VirtualDisplaySessionRegistry()
    private val runControllers = java.util.concurrent.ConcurrentHashMap<String, VirtualDisplaySessionController>()
    private val controllers = java.util.concurrent.ConcurrentHashMap<String, VirtualDisplaySessionController>().apply {
        put(primary.registryKey, primary)
    }
    internal const val MAX_SESSIONS_PER_RUN = 3
    internal const val MAX_SESSIONS = 6
    private val allocationLock = Any()
    private val selectionLock = Any()
    private val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val snapshotJobs = java.util.concurrent.ConcurrentHashMap<VirtualDisplaySessionController, Job>()
    private val idleCleanup = Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "eta-vd-idle-cleanup").apply { isDaemon = true }
    }
    @Volatile private var selectedController: VirtualDisplaySessionController = primary
    @Volatile private var requestedOwnerRunId: String? = null
    private val mutableSnapshots = MutableStateFlow(VirtualDisplaySnapshot.Inactive)
    val snapshots: StateFlow<VirtualDisplaySnapshot> = mutableSnapshots.asStateFlow()
    internal val primaryForTest: VirtualDisplaySessionController get() = primary

    init {
        observe(primary)
        idleCleanup.scheduleWithFixedDelay({
            controllers.values.toList().forEach { controller ->
                if (controller.releaseIfIdle(SystemClock.elapsedRealtime(), 120_000L)) cleanupReleased(controller)
            }
        }, 60L, 60L, TimeUnit.SECONDS)
    }

    private fun observe(controller: VirtualDisplaySessionController) {
        snapshotJobs.computeIfAbsent(controller) {
            snapshotScope.launch {
                controller.snapshots.collect { snapshot ->
                    if (snapshot.active && snapshot.sessionId.isNotBlank()) registry.register(controller)
                    synchronized(selectionLock) {
                        if (snapshot.active && requestedOwnerRunId == snapshot.ownerRunId &&
                            runControllers[snapshot.ownerRunId] === controller) {
                            if (selectedController !== controller) selectedController.setStageExpanded(false)
                            selectedController = controller
                        }
                        if (selectedController === controller) mutableSnapshots.value = snapshot
                    }
                }
            }
        }
    }

    private fun select(controller: VirtualDisplaySessionController): VirtualDisplaySessionController {
        synchronized(selectionLock) {
            if (selectedController !== controller) selectedController.setStageExpanded(false)
            selectedController = controller
            mutableSnapshots.value = controller.snapshots.value
        }
        observe(controller)
        return controller
    }

    private fun controllerForRun(runId: String): VirtualDisplaySessionController? = synchronized(allocationLock) {
        if (runId.isBlank()) return primary
        runControllers[runId]?.let { existing ->
            if ((!existing.snapshots.value.active || existing.snapshots.value.ownerRunId.isBlank() || existing.snapshots.value.ownerRunId == runId) &&
                existing.claimRunOwnership(runId)) return existing
        }
        val primarySnapshot = primary.snapshots.value
        val reserved = runControllers.filterKeys { it != runId }.values.filter {
            it.snapshots.value.ownerRunId.isNotBlank() || it.snapshots.value.lifecycle == VirtualDisplayLifecycle.STARTING
        }.toSet()
        val controller = when {
            primary !in reserved && (!primarySnapshot.active || primarySnapshot.ownerRunId.isBlank() || primarySnapshot.ownerRunId == runId) -> primary
            else -> registry.activeSessions().lastOrNull { it !in reserved && it.snapshots.value.ownerRunId.isBlank() }
                ?: controllers.values.firstOrNull { it !in reserved && !it.snapshots.value.active && it.snapshots.value.ownerRunId.isBlank() }
                ?: if (controllers.size < MAX_SESSIONS) VirtualDisplaySessionController() else return null
        }
        check(controller.claimRunOwnership(runId)) { "Display allocation ownership changed" }
        runControllers.entries.removeIf { it.key != runId && it.value === controller }
        runControllers[runId] = controller
        controllers[controller.registryKey] = controller
        observe(controller)
        return controller
    }

    private fun additionalController(runId: String): VirtualDisplaySessionController? = synchronized(allocationLock) {
        if (runId.isBlank()) return null
        val existing = (controllers.values + registry.activeSessions()).distinct()
        if (existing.count { it.snapshots.value.ownerRunId == runId } >= MAX_SESSIONS_PER_RUN) return null
        val idle = existing.firstOrNull { it.snapshots.value.ownerRunId.isBlank() &&
            !it.snapshots.value.userControlling && !it.snapshots.value.materializingToMain &&
            it.snapshots.value.lifecycle in setOf(VirtualDisplayLifecycle.IDLE, VirtualDisplayLifecycle.ACTIVE) }
        if (idle == null && existing.size >= MAX_SESSIONS) return null
        val controller = idle ?: VirtualDisplaySessionController()
        if (!controller.claimRunOwnership(runId)) return null
        controllers[controller.registryKey] = controller
        runControllers[runId] = controller
        observe(controller)
        controller
    }

    fun additionalSessionAvailable(runId: String): Boolean = synchronized(allocationLock) {
        val existing = (controllers.values + registry.activeSessions()).distinct()
        existing.count { it.snapshots.value.ownerRunId == runId } < MAX_SESSIONS_PER_RUN &&
            (existing.size < MAX_SESSIONS || existing.any { it.snapshots.value.ownerRunId.isBlank() &&
                !it.snapshots.value.userControlling && !it.snapshots.value.materializingToMain &&
                it.snapshots.value.lifecycle in setOf(VirtualDisplayLifecycle.IDLE, VirtualDisplayLifecycle.ACTIVE) })
    }

    private fun controllerFor(lease: VirtualDisplayLease?, runId: String = ""): VirtualDisplaySessionController? =
        if (lease != null) registry.resolve(lease) ?: primary.takeIf { it.validate(lease) }
        else if (runId.isNotBlank()) controllerForRun(runId)
        else select(primary)

    private fun registerIfActive(controller: VirtualDisplaySessionController) {
        if (controller.snapshots.value.active) registry.register(controller)
    }

    fun snapshotFor(lease: VirtualDisplayLease?): VirtualDisplaySnapshot? =
        lease?.let { registry.resolve(it) ?: primary.takeIf { controller -> controller.validate(it) } }?.snapshots?.value

    fun snapshotForRun(runId: String): VirtualDisplaySnapshot? =
        runControllers[runId]?.snapshots?.value?.takeIf { it.ownerRunId == runId || it.previewOwnerRunId == runId }
            ?: registry.sessionsForOwner(runId).lastOrNull()?.snapshots?.value

    fun snapshotsForRun(ownerRunId: String): List<VirtualDisplaySnapshot> =
        registry.sessionsForOwner(ownerRunId).map { it.snapshots.value }

    fun chooseSession(ownerRunId: String, sessionId: String): VirtualDisplayLease? = synchronized(allocationLock) {
        if (ownerRunId.isBlank()) return null
        val controller = registry.resolve(sessionId)?.takeIf {
            it.snapshots.value.ownerRunId == ownerRunId ||
                (it.snapshots.value.ownerRunId.isBlank() && it.snapshots.value.previewOwnerRunId == ownerRunId)
        } ?: return null
        val lease = controller.claimLease(ownerRunId) ?: return null
        runControllers[ownerRunId] = controller
        if (requestedOwnerRunId == ownerRunId || requestedOwnerRunId == null) select(controller)
        lease
    }

    fun releaseRunLeases(ownerRunId: String): Boolean {
        var released = false
        registry.sessionsForOwner(ownerRunId).forEach { controller ->
            val lease = controller.currentLease()?.takeIf { it.ownerRunId == ownerRunId }
            if (lease != null) released = controller.releaseLease(lease) || released
        }
        return released
    }

    fun selectRun(ownerRunId: String): Boolean {
        requestedOwnerRunId = ownerRunId
        val controller = runControllers[ownerRunId]?.takeIf { it.snapshots.value.ownerRunId.isBlank() || it.snapshots.value.ownerRunId == ownerRunId }
            ?: registry.sessionsForOwner(ownerRunId).lastOrNull()
            ?: primary.takeIf { it.snapshots.value.ownerRunId == ownerRunId }
            ?: return false
        select(controller)
        return true
    }

    fun releaseOwnedByRuns(context: Context?, ownerRunIds: Set<String>) {
        val ownedControllers = (registry.activeSessions() + controllers.values).distinct()
        for (controller in ownedControllers) {
            val snapshot = controller.snapshots.value
            val owner = snapshot.ownerRunId.ifBlank { snapshot.previewOwnerRunId }
            if (owner in ownerRunIds && controller.releaseForOwner(owner, context)) cleanupReleased(controller)
        }
    }

    fun releaseAll(context: Context?): Unit = synchronized(allocationLock) {
        val allControllers = (registry.activeSessions() + controllers.values).distinct()
        for (controller in allControllers) {
            controller.releaseCurrent(context)
            if (controller !== primary) {
                snapshotJobs.remove(controller)?.cancel()
                controller.shutdownExecutors()
            }
            registry.unregisterController(controller)
        }
        runControllers.clear()
        controllers.clear()
        controllers[primary.registryKey] = primary
        select(primary)
    }

    fun isBackendAvailable(rootAvailable: Boolean, lsposedAvailable: Boolean) =
        primary.isBackendAvailable(rootAvailable, lsposedAvailable)
    fun setStageExpanded(expanded: Boolean) = selectedController.setStageExpanded(expanded)
    fun currentLease() = selectedController.currentLease()
    fun currentLease(ownerRunId: String) = snapshotForRun(ownerRunId)?.lease
        ?: primary.currentLease()?.takeIf { it.ownerRunId == ownerRunId }
    fun validate(lease: VirtualDisplayLease?) = if (lease == null) false else
        (registry.resolve(lease) ?: primary.takeIf { it.validate(lease) })?.validate(lease) == true
    internal fun recordFocusObservation(
        lease: VirtualDisplayLease?,
        observation: VirtualDisplayFocusObservation,
        observedAtElapsedMs: Long = android.os.SystemClock.elapsedRealtime(),
    ) = controllerFor(lease)?.recordFocusObservation(lease, observation, observedAtElapsedMs) ?: false
    internal fun dispatchIfSessionCurrent(expected: VirtualDisplaySnapshot, dispatch: () -> Unit) =
        controllerFor(expected.lease)?.dispatchIfSessionCurrent(expected, dispatch) ?: false
    internal fun dispatchIfLeaseCurrent(lease: VirtualDisplayLease?, dispatch: () -> Unit) =
        controllerFor(lease)?.dispatchIfLeaseCurrent(lease, dispatch) ?: false
    fun setUserControlling(controlling: Boolean, lease: VirtualDisplayLease? = null) =
        controllerFor(lease)?.setUserControlling(controlling, lease)
    fun currentDisplayId() = selectedController.currentDisplayId()
    fun claimRunOwnership(runId: String) = controllerForRun(runId)?.claimRunOwnership(runId) ?: false
    fun claimLease(ownerRunId: String, sessionId: String? = null): VirtualDisplayLease? = synchronized(allocationLock) {
        if (sessionId != null) return chooseSession(ownerRunId, sessionId)
        val controller = runControllers[ownerRunId]?.takeIf { it.snapshots.value.ownerRunId == ownerRunId }
            ?: registry.sessionsForOwner(ownerRunId).lastOrNull()
            ?: runControllers[ownerRunId]?.takeIf { it.snapshots.value.active && it.snapshots.value.ownerRunId.isBlank() }
            ?: primary.takeIf { it.snapshots.value.active &&
                (it.snapshots.value.ownerRunId.isBlank() || it.snapshots.value.ownerRunId == ownerRunId) }
            ?: registry.activeSessions().lastOrNull { it.snapshots.value.ownerRunId.isBlank() }
            ?: return null
        val lease = controller.claimLease(ownerRunId)
        if (lease != null) {
            runControllers.entries.removeIf { it.key != ownerRunId && it.value === controller }
            runControllers[ownerRunId] = controller
            registerIfActive(controller)
            if (requestedOwnerRunId == null || requestedOwnerRunId == ownerRunId) select(controller)
        }
        return lease
    }
    fun releaseLease(lease: VirtualDisplayLease?): Boolean {
        if (lease == null) return false
        val controller = registry.resolve(lease) ?: primary.takeIf { it.validate(lease) } ?: return false
        val released = controller.releaseLease(lease)
        return released
    }
    fun ensureVirtualDisplay(
        context: Context,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        ownerRunId: String = "",
        expectedLease: VirtualDisplayLease? = null,
    ): Int? {
        val (controller, reservation) = synchronized(allocationLock) {
            val controller = controllerFor(expectedLease, ownerRunId) ?: return null
            controller to controller.reservationStamp()
        }
        val displayId = controller.withRunReservation(ownerRunId, reservation) {
            controller.ensureVirtualDisplay(context, rootAvailable, ownerRunId, expectedLease)
        }
        registerIfActive(controller)
        return displayId
    }
    fun launchAppOnVirtualDisplay(
        context: Context,
        packageName: String,
        appName: String,
        launchIntent: Intent,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        ownerRunId: String = "",
        lease: VirtualDisplayLease? = null,
        newSession: Boolean = false,
    ): Int? {
        if (newSession && lease != null) return null
        val previous = runControllers[ownerRunId]
        val (controller, reservation) = synchronized(allocationLock) {
            val controller = (if (newSession) additionalController(ownerRunId) else controllerFor(lease, ownerRunId)) ?: return null
            controller to controller.reservationStamp()
        }
        return controller.withRunReservation(ownerRunId, reservation) {
            controller.launchAppOnVirtualDisplay(context, packageName, appName, launchIntent, rootAvailable, ownerRunId, lease)
        }
            .also { if (it != null) registerIfActive(controller) else if (newSession) discardFailedAdditional(context, ownerRunId, controller, previous, reservation.first) }
    }
    fun openUriOnVirtualDisplay(
        context: Context,
        intent: Intent,
        targetLabel: String,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        ownerRunId: String = "",
        lease: VirtualDisplayLease? = null,
        newSession: Boolean = false,
    ): Int? {
        if (newSession && lease != null) return null
        val previous = runControllers[ownerRunId]
        val (controller, reservation) = synchronized(allocationLock) {
            val controller = (if (newSession) additionalController(ownerRunId) else controllerFor(lease, ownerRunId)) ?: return null
            controller to controller.reservationStamp()
        }
        return controller.withRunReservation(ownerRunId, reservation) {
            controller.openUriOnVirtualDisplay(context, intent, targetLabel, rootAvailable, ownerRunId, lease)
        }
            .also { if (it != null) registerIfActive(controller) else if (newSession) discardFailedAdditional(context, ownerRunId, controller, previous, reservation.first) }
    }

    private fun discardFailedAdditional(context: Context, ownerRunId: String, controller: VirtualDisplaySessionController, previous: VirtualDisplaySessionController?, expectedReleaseGeneration: Long) {
        if (!controller.releaseForOwner(ownerRunId, context, expectedReleaseGeneration)) return
        cleanupReleased(controller)
        runControllers.remove(ownerRunId, controller)
        if (previous != null) runControllers.putIfAbsent(ownerRunId, previous)
    }
    fun handoffToMainDisplay(
        context: Context,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        ownerRunId: String = "",
        lease: VirtualDisplayLease? = null,
    ): Boolean {
        val controller = controllerFor(lease, ownerRunId) ?: return false
        return controller.handoffToMainDisplay(context, rootAvailable, ownerRunId, lease).also { cleanupReleased(controller) }
    }
    fun captureScreenshotBitmap(
        waitUpToMs: Long = 450L,
        expectedDisplayId: Int? = null,
        expectedSessionId: String? = null,
        ownerRunId: String = "",
        lease: VirtualDisplayLease? = null,
    ) = controllerFor(lease, ownerRunId)?.captureScreenshotBitmap(waitUpToMs, expectedDisplayId, expectedSessionId, ownerRunId, lease)
    fun emitTapEffect(x: Int, y: Int, longPress: Boolean = false, lease: VirtualDisplayLease? = null) =
        (controllerFor(lease) ?: if (lease == null) selectedController else null)?.emitTapEffect(x, y, longPress)
    fun emitSwipeEffect(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 420, lease: VirtualDisplayLease? = null) =
        (controllerFor(lease) ?: if (lease == null) selectedController else null)?.emitSwipeEffect(x1, y1, x2, y2, durationMs)
    fun dispatchUserTapOnCanvas(
        normalizedX: Float,
        normalizedY: Float,
        longPress: Boolean = false,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        ownerRunId: String = "",
        lease: VirtualDisplayLease? = null,
        withControl: ((() -> Unit) -> Unit) = { it() },
    ) = controllerFor(lease, ownerRunId)?.dispatchUserTapOnCanvas(normalizedX, normalizedY, longPress, rootAvailable, ownerRunId, lease, withControl) ?: false
    fun dispatchUserSwipeOnCanvas(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Int = 320,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        ownerRunId: String = "",
        lease: VirtualDisplayLease? = null,
        withControl: ((() -> Unit) -> Unit) = { it() },
    ) = controllerFor(lease, ownerRunId)?.dispatchUserSwipeOnCanvas(startX, startY, endX, endY, durationMs, rootAvailable, ownerRunId, lease, withControl) ?: false
    fun release(
        context: Context? = null,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        ownerRunId: String = "",
        lease: VirtualDisplayLease? = null,
    ) {
        val controller = controllerFor(lease, ownerRunId) ?: return
        controller.release(context, rootAvailable, ownerRunId, lease)
        cleanupReleased(controller)
    }

    private fun cleanupReleased(controller: VirtualDisplaySessionController): Unit = synchronized(allocationLock) {
        if (controller.snapshots.value.active || controller.snapshots.value.ownerRunId.isNotBlank()) return
        registry.unregisterController(controller)
        if (controller !== primary) {
            controllers.remove(controller.registryKey, controller)
            snapshotJobs.remove(controller)?.cancel()
            controller.shutdownExecutors()
            if (selectedController === controller) {
                val fallback = registry.activeSessions().lastOrNull() ?: primary
                select(fallback)
            }
        }
        runControllers.entries.removeIf { it.value === controller }
    }
    internal fun isHealthProbeCurrentLocked(
        probe: VirtualDisplayHealthProbe,
        snapshot: VirtualDisplaySnapshot,
    ) = controllerFor(snapshot.lease)?.isHealthProbeCurrentLocked(probe, snapshot) ?: false
}
