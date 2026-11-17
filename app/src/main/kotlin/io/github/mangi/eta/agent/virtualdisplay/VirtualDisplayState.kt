package io.github.mangi.eta.agent.virtualdisplay

import android.view.Display
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/** 虚拟屏生命周期。底层显示不存在或桥进程退出后，由控制器进入恢复流程。 */
internal enum class VirtualDisplayLifecycle {
    IDLE,
    STARTING,
    ACTIVE,
    RECOVERING,
    FAILED,
    RELEASING,
}

/** 当前虚拟屏由哪条系统路径承载，用于诊断和 ROM 兼容降级。 */
internal enum class VirtualDisplayBackend {
    NONE,
    IN_PROCESS,
    ROOT_BRIDGE,
}

/** Flags accepted by display creation, not proof that the OS honored focus behavior. */
internal enum class VirtualDisplayFlagProfile(val wireValue: String) {
    TRUSTED_INTERACTIVE("trusted_interactive"),
    INTERACTIVE_COMPAT("interactive_compat"),
    CAPTURE_COMPAT("capture_compat"),
    UNKNOWN("unknown"),
}

/** Live accessibility observation of application-window focus on one display session. */
internal enum class VirtualDisplayFocusObservation(val wireValue: String) {
    UNKNOWN("unknown"),
    FOCUSED_APPLICATION_WINDOW("focused_application_window"),
    NO_FOCUSED_APPLICATION_WINDOW("no_focused_application_window"),
    UNAVAILABLE("unavailable"),
}

/** Exact capability for one active display session; it cannot survive ownership or session transfer. */
@Immutable
internal data class VirtualDisplayLease(
    val ownerRunId: String,
    val sessionId: String,
    val displayId: Int,
    val ownershipGeneration: Long = 0L,
)

/**
 * 虚拟屏画布内的微距触控视觉反馈。
 * 坐标全部归一化到 [0f, 1f]，与画布当前渲染尺寸解耦，在微缩镜与画廊视窗中均可精确绘制。
 */
@Immutable
internal sealed interface VirtualTouchEffect {
    val sequence: Long
    val createdAtMs: Long

    @Immutable
    data class Tap(
        override val sequence: Long,
        override val createdAtMs: Long,
        val normalizedX: Float,
        val normalizedY: Float,
        val longPress: Boolean = false,
    ) : VirtualTouchEffect

    @Immutable
    data class Swipe(
        override val sequence: Long,
        override val createdAtMs: Long,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Int = 420,
    ) : VirtualTouchEffect
}

/**
 * 虚拟屏实时状态快照，供顶部胶囊微缩镜、画廊装裱视窗与破壁全屏过渡共享订阅。
 */
@Immutable
internal data class VirtualDisplaySnapshot(
    val active: Boolean = false,
    val displayId: Int = Display.DEFAULT_DISPLAY,
    val width: Int = 1080,
    val height: Int = 2400,
    val appName: String = "",
    val packageName: String = "",
    /** 用户是否已在画廊视窗内开启手动触控接管。 */
    val userControlling: Boolean = false,
    val manualInputGeneration: Long = 0,
    /** 是否正在执行从虚拟屏到物理主屏的破壁全屏放大动画。 */
    val materializingToMain: Boolean = false,
    /** 最新采集的画面帧。 */
    val frame: ImageBitmap? = null,
    val frameSequence: Long = 0L,
    /** 最近一次在虚拟屏内发生的手势光效。 */
    val touchEffect: VirtualTouchEffect? = null,
    /** 与 [displayId] 同生命周期的不透明会话标识，供诊断和恢复关联。 */
    val sessionId: String = "",
    /** Run that currently owns GUI access to this single shared virtual display; blank means reusable. */
    val ownerRunId: String = "",
    /** Completed Run whose preview remains available until reclaimed or idle cleanup. */
    val previewOwnerRunId: String = "",
    val ownershipGeneration: Long = 0L,
    val lifecycle: VirtualDisplayLifecycle = VirtualDisplayLifecycle.IDLE,
    val backend: VirtualDisplayBackend = VirtualDisplayBackend.NONE,
    val flagProfile: VirtualDisplayFlagProfile = VirtualDisplayFlagProfile.UNKNOWN,
    /** Window focus is sampled from AccessibilityWindows; display flags never set this value. */
    val focusObservation: VirtualDisplayFocusObservation = VirtualDisplayFocusObservation.UNKNOWN,
    val focusObservedAtElapsedMs: Long = 0L,
    val recoveryAttempt: Int = 0,
    val failureReason: String? = null,
    val startedAtElapsedMs: Long = 0L,
    val lastFrameAtElapsedMs: Long = 0L,
) {
    /** A scoped observation that an application window reports isFocused=true. */
    val hasObservedApplicationWindowFocus: Boolean
        get() = focusObservation == VirtualDisplayFocusObservation.FOCUSED_APPLICATION_WINDOW

    fun hasFreshFrame(nowElapsedMs: Long, staleAfterMs: Long = FRAME_STALE_AFTER_MS): Boolean {
        if (startedAtElapsedMs < 0L || nowElapsedMs < startedAtElapsedMs) return false
        val frameAt = lastFrameAtElapsedMs.takeIf { it > 0L } ?: startedAtElapsedMs
        if (nowElapsedMs < frameAt) return false
        return nowElapsedMs - frameAt <= staleAfterMs
    }

    val lease: VirtualDisplayLease?
        get() = takeIf {
            active && lifecycle == VirtualDisplayLifecycle.ACTIVE &&
                ownerRunId.isNotBlank() && sessionId.isNotBlank()
        }?.let { VirtualDisplayLease(ownerRunId, sessionId, displayId, ownershipGeneration) }

    val aspectRatio: Float
        get() = if (width > 0 && height > 0) {
            (width.toFloat() / height.toFloat()).coerceIn(0.38f, 0.75f)
        } else {
            9f / 19.5f
        }

    companion object {
        const val FRAME_STALE_AFTER_MS = 10_000L
        val Inactive = VirtualDisplaySnapshot()
    }
}
