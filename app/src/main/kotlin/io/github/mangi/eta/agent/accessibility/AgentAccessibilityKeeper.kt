package io.github.mangi.eta.agent.accessibility

import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 在 GUI 工具执行前确认 Eta 无障碍服务已经真实连接。
 *
 * 优先使用用户开启的独立 Root 恢复；已有系统保护后端保留兼容。
 * 读取与普通操作不会申请 Root，恢复失败时不执行屏幕动作。
 */
object AgentAccessibilityKeeper {
    internal fun ensureEnabledForGuiOperation(context: Context): AccessibilityEnableResult {
        val startedAt = SystemClock.elapsedRealtime()
        val result = if (!AgentAccessibilityService.isAvailable() && RootAccessibilityRecovery.canRecover(context) &&
            !(EtaApp.serviceInstance != null && AccessibilityProtectionClient.isEnabled(context))
        ) RootAccessibilityRecovery.recoverBlocking(context) else ensureAvailable(
            serviceAvailable = AgentAccessibilityService::isAvailable,
            protectionEnabled = { AccessibilityProtectionClient.isEnabled(context) },
            requestRecovery = {
                AccessibilityProtectionClient.requestRecoveryBlocking(context) ==
                    AccessibilityProtectionClient.ControlStatus.APPLIED
            },
            awaitServiceBinding = ::awaitServiceBinding,
            protectionAvailable = { EtaApp.serviceInstance != null },
            serviceConfigured = { AgentAccessibilityHealth.configured(context) == true },
        )
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        if (result.available) {
            AndroidAgentLogger.info(
                "Agent accessibility action=ensure_for_gui outcome=completed " +
                    "recoveryRequested=${result.recoveryRequested} " +
                    "elapsed_ms=$elapsedMs"
            )
        } else {
            AndroidAgentLogger.warn(
                "Agent accessibility action=ensure_for_gui outcome=failed " +
                    "code=${result.code} recoveryRequested=${result.recoveryRequested} " +
                    "elapsed_ms=$elapsedMs"
            )
        }
        return result
    }

    internal fun ensureAvailable(
        serviceAvailable: () -> Boolean,
        protectionEnabled: () -> Boolean,
        requestRecovery: () -> Boolean,
        awaitServiceBinding: () -> Boolean,
        protectionAvailable: () -> Boolean = { true },
        serviceConfigured: () -> Boolean = { false },
    ): AccessibilityEnableResult {
        if (serviceAvailable()) {
            return AccessibilityEnableResult.available(recoveryRequested = false)
        }
        if (!protectionAvailable() || !protectionEnabled()) {
            val disconnected = serviceConfigured()
            return AccessibilityEnableResult.failure(
                code = if (disconnected) "ACCESSIBILITY_DISCONNECTED" else "ACCESSIBILITY_UNAVAILABLE",
                message = if (disconnected) {
                    "Nexus 无障碍已启用但服务未连接；请在系统无障碍设置中重新开启，本次 GUI 操作未执行"
                } else {
                    "Nexus 无障碍服务未连接；请在系统设置中开启 Nexus 无障碍服务"
                },
                recoveryRequested = false,
            )
        }
        if (!requestRecovery()) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_PROTECTION_UNAVAILABLE",
                message = "无障碍保护后端不可用；本次 GUI 操作未执行",
                recoveryRequested = true,
            )
        }
        if (!awaitServiceBinding()) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_REPAIR_TIMEOUT",
                message = "Nexus 无障碍服务未在恢复时限内连接；本次 GUI 操作未执行",
                recoveryRequested = true,
            )
        }
        return AccessibilityEnableResult.available(recoveryRequested = true)
    }

    private fun awaitServiceBinding(): Boolean {
        repeat(SERVICE_BIND_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) return false
            if (AgentAccessibilityService.isAvailable()) return true
            SystemClock.sleep(SERVICE_BIND_POLL_MS)
        }
        return AgentAccessibilityService.isAvailable()
    }

    private const val SERVICE_BIND_ATTEMPTS = 60
    private const val SERVICE_BIND_POLL_MS = 100L
}

internal data class AccessibilityEnableResult(
    val available: Boolean,
    val code: String = "",
    val message: String = "",
    val recoveryRequested: Boolean,
) {
    companion object {
        fun available(
            recoveryRequested: Boolean,
        ): AccessibilityEnableResult = AccessibilityEnableResult(
            available = true,
            recoveryRequested = recoveryRequested,
        )

        fun failure(
            code: String,
            message: String,
            recoveryRequested: Boolean,
        ): AccessibilityEnableResult = AccessibilityEnableResult(
            available = false,
            code = code,
            message = message,
            recoveryRequested = recoveryRequested,
        )
    }
}
