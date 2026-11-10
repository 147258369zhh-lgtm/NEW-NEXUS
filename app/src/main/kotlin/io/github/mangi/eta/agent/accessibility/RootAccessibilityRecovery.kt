package io.github.mangi.eta.agent.accessibility

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.edit
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceToolContract
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

internal enum class RootAccessibilityRecoveryStatus {
    OFF, READY, ROOT_REQUIRED, NOT_CONFIGURED, MANUAL_REQUIRED, RECOVERING, WAITING, EXHAUSTED, FAILED, UNCONFIRMED, SAVE_FAILED,
}

internal data class RootAccessibilityRecoveryState(
    val enabled: Boolean = false,
    val status: RootAccessibilityRecoveryStatus = RootAccessibilityRecoveryStatus.OFF,
)

internal data class AccessibilityRecoveryLease(val token: String, val createdAt: Long) {
    fun validAt(candidate: String, now: Long): Boolean =
        token == candidate && now - createdAt in 0L until 25_000L
}

/** 应用进程存活期间的可选恢复。读取状态不执行 su，不替代 Root 授权或系统无障碍授权。 */
internal object RootAccessibilityRecovery {
    private const val PREFS = "eta_root_accessibility_recovery"
    private const val ENABLED = "enabled"
    private const val PENDING = "pending_rebind"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(RootAccessibilityRecoveryState())
    val state = mutableState.asStateFlow()
    private val running = AtomicBoolean()
    private val budget = AccessibilityRecoveryBudget()
    private val executionLock = ReentrantLock()
    @Volatile private var appContext: Context? = null
    @Volatile private var lease: AccessibilityRecoveryLease? = null

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        mutableState.value = RootAccessibilityRecoveryState(preferences(app).getBoolean(ENABLED, false))
        scope.launch { RootAccess.state.collect { schedule(app) } }
        scope.launch { AgentAccessibilityHealth.observeChanges(app).collect { schedule(app) } }
    }

    fun isEnabled(context: Context) = preferences(context).getBoolean(ENABLED, false)

    fun canRecover(context: Context): Boolean = RootAccess.isGranted && isEnabled(context) &&
        AgentAccessibilityHealth.configured(context) == true && !pending(context)

    /** 保留开关允许 Root 丢失时关闭；开启不会触发 Root 弹窗或擅自开启系统服务。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        initialize(context)
        val saved = preferences(context).edit().putBoolean(ENABLED, enabled).commit()
        if (!saved) {
            mutableState.value = mutableState.value.copy(status = RootAccessibilityRecoveryStatus.SAVE_FAILED)
            return
        }
        mutableState.value = RootAccessibilityRecoveryState(enabled)
        budget.reset()
        schedule(context.applicationContext)
    }

    fun retry(context: Context) {
        initialize(context)
        budget.reset()
        schedule(context.applicationContext)
    }

    private fun schedule(context: Context) {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                delay(1_500L) // 给系统正常绑定留出时间，合并同批设置/生命周期事件。
                while (true) {
                    // ColorOS 的解锁后风控会先于 Android 无障碍绑定执行；在每个恢复周期先做
                    // 包级、可逆、幂等豁免，仍然只修改厂商已有的 skip_package 列表。
                    val exemption = runInterruptible { ensureRiskCloseExemptionBlocking(context) }
                    val result = runInterruptible { recoverBlocking(context) }
                    if (exemption == VendorRiskCloseExemptionOutcome.FAILED && result.available) {
                        update(context, RootAccessibilityRecoveryStatus.MANUAL_REQUIRED)
                        break
                    }
                    if (result.available || state.value.status !in setOf(
                            RootAccessibilityRecoveryStatus.FAILED, RootAccessibilityRecoveryStatus.WAITING,
                        )) break
                    delay(5_000L)
                }
            } finally {
                running.set(false)
            }
        }
    }

    fun recoverBlocking(context: Context): AccessibilityEnableResult {
        if (Looper.myLooper() == Looper.getMainLooper()) return failure("ACCESSIBILITY_RECOVERY_BACKGROUND_REQUIRED")
        if (Thread.currentThread().isInterrupted) return failure("ACCESSIBILITY_RECOVERY_CANCELLED")
        if (!executionLock.tryLock()) return failure("ACCESSIBILITY_RECOVERY_BUSY")
        try {
            val connected = AgentAccessibilityService.isAvailable()
            val history = AgentAccessibilityHealth.history.value
            budget.recordConnection(history.lastConnectedAtMs, history.lastDisconnectedAtMs, SystemClock.elapsedRealtime(), connected)
            if (connected) {
                update(context, if (isEnabled(context)) RootAccessibilityRecoveryStatus.READY else RootAccessibilityRecoveryStatus.OFF)
                return AccessibilityEnableResult.available(false)
            }
            if (!isEnabled(context)) { update(context, RootAccessibilityRecoveryStatus.OFF); return failure("ACCESSIBILITY_UNAVAILABLE") }
            if (!RootAccess.isGranted) { update(context, RootAccessibilityRecoveryStatus.ROOT_REQUIRED); return failure("ROOT_REQUIRED") }
            // 设置被关闭或上次中断留下不确定状态时不自动重新启用，交给系统设置恢复。
            if (pending(context)) { update(context, RootAccessibilityRecoveryStatus.UNCONFIRMED); return failure("ACCESSIBILITY_REPAIR_UNCONFIRMED") }
            if (AgentAccessibilityHealth.configured(context) != true) {
                update(context, RootAccessibilityRecoveryStatus.NOT_CONFIGURED)
                return failure("ACCESSIBILITY_UNAVAILABLE")
            }
            if (EtaApp.serviceInstance != null && AccessibilityProtectionClient.isEnabled(context)) {
                update(context, RootAccessibilityRecoveryStatus.READY)
                return failure("ACCESSIBILITY_PROTECTION_ACTIVE") // 两种恢复后端不同时写入。
            }
            val wait = budget.acquire(SystemClock.elapsedRealtime())
            if (wait == null) { update(context, RootAccessibilityRecoveryStatus.EXHAUSTED); return failure("ACCESSIBILITY_RECOVERY_EXHAUSTED") }
            if (wait > 0L) { update(context, RootAccessibilityRecoveryStatus.WAITING); return failure("ACCESSIBILITY_RECOVERY_COOLDOWN") }
            update(context, RootAccessibilityRecoveryStatus.RECOVERING)
            val activeLease = AccessibilityRecoveryLease(UUID.randomUUID().toString(), SystemClock.elapsedRealtime())
            lease = activeLease
            val result = try {
                BoundedRootCommandExecutor(AndroidAgentLogger).use { executor ->
                    executor.execute(
                        "CLASSPATH=${DeviceToolContract.quote(context.applicationInfo.sourceDir)} " +
                            "app_process /system/bin io.github.mangi.eta.agent.accessibility.AccessibilityRecoveryCommandMain",
                        timeoutMillis = 15_000L,
                        maxOutputBytes = 8_192,
                        input = JSONObject().put("version", 1).put("token", activeLease.token)
                            .put("user_id", DeviceToolContract.appUserId(context))
                            .put("action", AccessibilityRecoveryCommandMain.ACTION_REBIND).toString().toByteArray(Charsets.UTF_8),
                    )
                }
            } catch (_: Exception) {
                BoundedRootCommandExecutor.Result.failed("ACCESSIBILITY_ROOT_HELPER_UNAVAILABLE")
            } finally {
                lease = null
            }
            val outcome = parseOutcome(result)
            if (outcome != AccessibilityRebindOutcome.REQUESTED) {
                val status = when {
                    pending(context) -> RootAccessibilityRecoveryStatus.UNCONFIRMED
                    !RootAccess.isGranted -> RootAccessibilityRecoveryStatus.ROOT_REQUIRED
                    !isEnabled(context) -> RootAccessibilityRecoveryStatus.OFF
                    AgentAccessibilityHealth.configured(context) != true -> RootAccessibilityRecoveryStatus.NOT_CONFIGURED
                    outcome == AccessibilityRebindOutcome.MANUAL_REQUIRED -> RootAccessibilityRecoveryStatus.MANUAL_REQUIRED
                    else -> RootAccessibilityRecoveryStatus.FAILED
                }
                update(context, status)
                return failure(when (status) {
                    RootAccessibilityRecoveryStatus.UNCONFIRMED -> "ACCESSIBILITY_REPAIR_UNCONFIRMED"
                    RootAccessibilityRecoveryStatus.MANUAL_REQUIRED -> "ACCESSIBILITY_ROOT_RECOVERY_MANUAL_REQUIRED"
                    else -> "ACCESSIBILITY_ROOT_RECOVERY_FAILED"
                }, true)
            }
            repeat(60) {
                if (AgentAccessibilityService.isAvailable()) {
                    update(context, if (isEnabled(context)) RootAccessibilityRecoveryStatus.READY else RootAccessibilityRecoveryStatus.OFF)
                    return AccessibilityEnableResult.available(true)
                }
                if (Thread.currentThread().isInterrupted || !isEnabled(context) || !RootAccess.isGranted) {
                    update(context, if (isEnabled(context)) RootAccessibilityRecoveryStatus.ROOT_REQUIRED else RootAccessibilityRecoveryStatus.OFF)
                    return failure("ACCESSIBILITY_RECOVERY_CANCELLED", true)
                }
                SystemClock.sleep(100L)
            }
            update(context, RootAccessibilityRecoveryStatus.FAILED)
            return failure("ACCESSIBILITY_REPAIR_TIMEOUT", true)
        } finally {
            executionLock.unlock()
            // GUI 入口可能先于自动检查取得锁，失败后仍需接回有界后台重试。
            if (!running.get() && state.value.status == RootAccessibilityRecoveryStatus.FAILED) schedule(context.applicationContext)
        }
    }

    /**
     * 在用户已经开启 Eta Root 恢复的前提下，幂等登记 ColorOS 的包级风险关闭豁免。
     * 不修改无障碍总开关，也不影响其他无障碍服务；非 ColorOS 缺少该键时保持原状。
     */
    fun ensureRiskCloseExemptionBlocking(context: Context): VendorRiskCloseExemptionOutcome {
        if (Looper.myLooper() == Looper.getMainLooper()) return VendorRiskCloseExemptionOutcome.FAILED
        if (!isEnabled(context) || !RootAccess.isGranted) return VendorRiskCloseExemptionOutcome.UNSUPPORTED
        if (Thread.currentThread().isInterrupted) return VendorRiskCloseExemptionOutcome.FAILED
        if (!executionLock.tryLock()) return VendorRiskCloseExemptionOutcome.FAILED
        try {
            val activeLease = AccessibilityRecoveryLease(UUID.randomUUID().toString(), SystemClock.elapsedRealtime())
            lease = activeLease
            val result = try {
                BoundedRootCommandExecutor(AndroidAgentLogger).use { executor ->
                    executor.execute(
                        "CLASSPATH=${DeviceToolContract.quote(context.applicationInfo.sourceDir)} " +
                            "app_process /system/bin io.github.mangi.eta.agent.accessibility.AccessibilityRecoveryCommandMain",
                        timeoutMillis = 15_000L,
                        maxOutputBytes = 8_192,
                        input = JSONObject().put("version", 1).put("token", activeLease.token)
                            .put("user_id", DeviceToolContract.appUserId(context))
                            .put("action", AccessibilityRecoveryCommandMain.ACTION_EXEMPT).toString().toByteArray(Charsets.UTF_8),
                    )
                }
            } catch (_: Exception) {
                BoundedRootCommandExecutor.Result.failed("ACCESSIBILITY_ROOT_EXEMPTION_UNAVAILABLE")
            } finally {
                lease = null
            }
            val outcome = parseRiskExemptionOutcome(result)
            when (outcome) {
                VendorRiskCloseExemptionOutcome.APPLIED,
                VendorRiskCloseExemptionOutcome.ALREADY_EXEMPT,
                VendorRiskCloseExemptionOutcome.UNSUPPORTED,
                null -> update(
                    context,
                    if (AgentAccessibilityService.isAvailable()) RootAccessibilityRecoveryStatus.READY
                    else RootAccessibilityRecoveryStatus.MANUAL_REQUIRED,
                )
                VendorRiskCloseExemptionOutcome.FAILED -> update(context, RootAccessibilityRecoveryStatus.MANUAL_REQUIRED)
            }
            return outcome ?: VendorRiskCloseExemptionOutcome.FAILED
        } finally {
            executionLock.unlock()
        }
    }

    /** 仅 HealthProvider 在 UID0 + 协议检查后调用。token 只走 stdin，不写日志或磁盘。 */
    fun control(context: Context, token: String, phase: String): android.os.Bundle {
        val active = lease
        val valid = active?.validAt(token, SystemClock.elapsedRealtime()) == true
        if (!valid) return android.os.Bundle.EMPTY
        // 关闭恢复只停止后续尝试；当前事务若刚临时移除了 Eta，仍允许还原，不将服务遗留关闭。
        val allowed = RootAccess.isGranted && if (phase == "restore") pending(context) else isEnabled(context)
        if (phase == "begin" && allowed && !savePending(context, true)) {
            return android.os.Bundle.EMPTY
        }
        if (phase == "complete" && !savePending(context, false)) return android.os.Bundle.EMPTY
        return android.os.Bundle().apply {
            putBoolean("allowed", allowed)
            putBoolean("completed", phase == "complete")
            putInt("user_id", DeviceToolContract.appUserId(context))
            putString("component", ComponentName(context, AgentAccessibilityService::class.java).flattenToString())
        }
    }

    fun clearPendingAfterUserConnection(context: Context) {
        if (AgentAccessibilityService.isAvailable()) preferences(context).edit { putBoolean(PENDING, false) }
    }

    private fun savePending(context: Context, value: Boolean): Boolean {
        // Root 修改前必须确认 journal 落盘；完成时也必须确认清除成功，不能异步 apply。
        val prefs = preferences(context)
        val saved = prefs.edit().putBoolean(PENDING, value).commit()
        // commit 失败仍会更新内存；保持阻断标记，不能让下次恢复把失败误认为已清理。
        if (!saved && !value) prefs.edit { putBoolean(PENDING, true) }
        return saved
    }

    private fun pending(context: Context) = preferences(context).getBoolean(PENDING, false)
    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun update(context: Context, status: RootAccessibilityRecoveryStatus) {
        mutableState.value = RootAccessibilityRecoveryState(isEnabled(context), status)
        AndroidAgentLogger.info("Agent accessibility root_recovery state=$status")
    }

    internal fun parseOutcome(result: BoundedRootCommandExecutor.Result): AccessibilityRebindOutcome? {
        if (!result.ok || result.truncated) return null
        return runCatching {
            val line = result.stdout.lineSequence().single { it.startsWith(AccessibilityRecoveryCommandMain.MARKER) }
            val payload = JSONObject(line.removePrefix(AccessibilityRecoveryCommandMain.MARKER))
            require(payload.getInt("version") == 1)
            if (payload.optString("exemption") == VendorRiskCloseExemptionOutcome.FAILED.name) {
                return AccessibilityRebindOutcome.MANUAL_REQUIRED
            }
            AccessibilityRebindOutcome.valueOf(payload.getString("outcome"))
        }.getOrNull()
    }

    internal fun parseRiskExemptionOutcome(result: BoundedRootCommandExecutor.Result): VendorRiskCloseExemptionOutcome? {
        if (!result.ok || result.truncated) return null
        return runCatching {
            val line = result.stdout.lineSequence().single { it.startsWith(AccessibilityRecoveryCommandMain.MARKER) }
            val payload = JSONObject(line.removePrefix(AccessibilityRecoveryCommandMain.MARKER))
            require(payload.getInt("version") == 1)
            VendorRiskCloseExemptionOutcome.valueOf(payload.getString("exemption"))
        }.getOrNull()
    }

    private fun failure(code: String, requested: Boolean = false) = AccessibilityEnableResult.failure(
        code, "无障碍恢复未完成（$code）；请检查权限健康页或在系统设置中重新开启 Nexus。本次屏幕操作未执行。", requested,
    )
}
