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
    val lifecycle: VirtualDisplayLifecycle = VirtualDisplayLifecycle.IDLE,
    val backend: VirtualDisplayBackend = VirtualDisplayBackend.NONE,
    val recoveryAttempt: Int = 0,
    val failureReason: String? = null,
    val startedAtElapsedMs: Long = 0L,
    val lastFrameAtElapsedMs: Long = 0L,
) {
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) {
            (width.toFloat() / height.toFloat()).coerceIn(0.38f, 0.75f)
        } else {
            9f / 19.5f
        }

    companion object {
        val Inactive = VirtualDisplaySnapshot()
    }
}
