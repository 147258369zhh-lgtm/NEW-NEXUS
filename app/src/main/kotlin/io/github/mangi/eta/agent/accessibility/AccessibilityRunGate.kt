package io.github.mangi.eta.agent.accessibility

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.GuiControlCoordinator
import io.github.mangi.eta.agent.runtime.GuiInterventionTimeoutException
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import java.util.concurrent.locks.ReentrantLock

internal enum class AccessibilityWaitPhase(val wireValue: String) {
    WAITING("waiting"), RESTORED("restored"), TIMED_OUT("timed_out"), CANCELLED("cancelled");

    companion object {
        fun fromWireValue(value: String) = entries.firstOrNull { it.wireValue == value }
    }
}

/** 单次 run 共享等待预算；等待在 GUI action 外，取消能唤醒条件等待和 Root helper。 */
internal class AccessibilityRunGate(
    private val controller: AgentRunController,
    private val gui: GuiControlCoordinator.RunControl,
    private val serviceAvailable: () -> Boolean,
    private val requestRecovery: () -> Unit,
    private val onEvent: (AgentEvent.AccessibilityWaiting) -> Unit,
    private val totalWaitMs: Long = 300_000L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val pollMs: Long = 250L,
    private val minimumRecoveryBudgetMs: Long = 22_000L,
) {
    private val lock = ReentrantLock()
    private var spentMs = 0L
    private var outage = 0

    fun prepare(toolName: String): ToolExecutionDecision {
        if (!AgentToolRequirements.requiresAccessibility(toolName)) return ToolExecutionDecision.Allow
        controller.throwIfCancelled()
        val thread = Thread.currentThread()
        val binding = controller.register(thread::interrupt)
        var locked = false
        var startedAt: Long? = null
        var waiting = false
        try {
            lock.lockInterruptibly()
            locked = true
            if (controller.isCancelled) throw AgentRunCancelledException()
            if (serviceAvailable()) return ToolExecutionDecision.Allow
            val remaining = (totalWaitMs - spentMs).coerceAtLeast(0L)
            if (remaining == 0L) return timeoutDecision()
            outage++
            val outageStartedAt = nowMs()
            startedAt = outageStartedAt
            return gui.withAccessibilityIntervention(remaining) {
                waiting = true
                onEvent(event(AccessibilityWaitPhase.WAITING))
                // 每次断线最多请求一次；后端自己执行用户设置、Root 能力和退避检查。
                if (remaining - (nowMs() - outageStartedAt) >= minimumRecoveryBudgetMs) {
                    try { requestRecovery() } catch (failure: RuntimeException) {
                        if (failure is AgentRunCancelledException) throw failure
                        // 恢复后端异常仍允许用户手动恢复，不转为无限自动重试。
                    }
                }
                while (!serviceAvailable()) {
                    if (controller.isCancelled) throw AgentRunCancelledException()
                    val timeLeft = remaining - (nowMs() - outageStartedAt)
                    if (timeLeft <= 0L) {
                        onEvent(event(AccessibilityWaitPhase.TIMED_OUT))
                        return@withAccessibilityIntervention timeoutDecision()
                    }
                    Thread.sleep(minOf(pollMs, timeLeft))
                }
                if (controller.isCancelled) throw AgentRunCancelledException()
                onEvent(event(AccessibilityWaitPhase.RESTORED))
                if (AgentToolRequirements.requiresFreshObservation(toolName) || toolName == "open_system_panel") {
                    ToolExecutionDecision.Reject(
                        "ACCESSIBILITY_RESTORED_REOBSERVE",
                        "无障碍已恢复；此前屏幕观察已失效，本次操作未执行。请先 observe_screen，再决定下一步，勿重放旧坐标或输入。",
                    )
                } else ToolExecutionDecision.Allow
            }
        } catch (_: GuiInterventionTimeoutException) {
            onEvent(event(AccessibilityWaitPhase.TIMED_OUT))
            return timeoutDecision()
        } catch (cancelled: AgentRunCancelledException) {
            if (waiting) onEvent(event(AccessibilityWaitPhase.CANCELLED))
            throw cancelled
        } catch (_: InterruptedException) {
            if (waiting) onEvent(event(AccessibilityWaitPhase.CANCELLED))
            Thread.currentThread().interrupt()
            throw AgentRunCancelledException()
        } finally {
            if (locked) {
                startedAt?.let { spentMs = (spentMs + (nowMs() - it).coerceAtLeast(0L)).coerceAtMost(totalWaitMs) }
                lock.unlock()
            }
            binding.close()
        }
    }

    private fun event(phase: AccessibilityWaitPhase) = AgentEvent.AccessibilityWaiting(outage, phase)

    private fun timeoutDecision() = ToolExecutionDecision.Reject(
        "ACCESSIBILITY_WAIT_TIMEOUT",
        "本任务的无障碍恢复等待已用尽；本次屏幕操作未执行。请在权限健康页恢复服务后再继续，勿重复调用等待。",
    )
}
