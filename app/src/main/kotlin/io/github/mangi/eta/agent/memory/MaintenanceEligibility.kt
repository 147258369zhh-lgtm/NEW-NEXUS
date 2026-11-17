package io.github.mangi.eta.agent.memory

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import io.github.mangi.eta.agent.model.AgentModelClient

/** Shared idle policy for memory and skill consolidation; local inference has no network gate. */
internal object MaintenanceEligibility {
    private val budgetDenials = listOf(
        "RUN_BUDGET_EXCEEDED_",
        "RUN_SOFT_BUDGET_NOT_APPROVED_",
        "RUN_TOTAL_MODEL_REQUESTS_EXCEEDED",
        "MODEL_COST_",
    )

    /** A budget refusal is a yield: the source stays queued and does not spend an attempt. */
    fun isBudgetDenial(code: String?): Boolean =
        code != null && budgetDenials.any { code.startsWith(it) }

    fun reason(context: Context, settings: MemoryAutomationSettings, config: AgentModelClient.ModelConfig?,
        manual: Boolean, budgetExhausted: Boolean = false, now: Long = System.currentTimeMillis()): String? {
        if (MemoryAutomationCapture.activeRuns.get() > 0) return "等待 Agent 任务完成"
        val visible = AppForegroundState.isVisible
        if (!manual && visible) return "应用在前台"
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in setOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm?.getNetworkCapabilities(cm.activeNetwork)
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        return evaluate(settings, config?.isEmbeddedLocalTextModel() == true, manual, power?.isInteractive == true,
            charging, network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
            now - MemoryAutomationSettingsStore.lastActivity(context),
            foreground = visible, budgetExhausted = budgetExhausted,
            resourcesLimited = memory.lowMemory || power?.isPowerSaveMode == true ||
                (power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE)
    }

    internal fun evaluate(settings: MemoryAutomationSettings, embeddedLocal: Boolean, manual: Boolean, screenOn: Boolean,
        charging: Boolean, connected: Boolean, unmetered: Boolean, idleMs: Long, foreground: Boolean = false,
        budgetExhausted: Boolean = false, resourcesLimited: Boolean = false): String? {
        if (!manual) {
            if (!settings.idleEnabled) return "空闲整理已关闭"
            if (settings.chargingOnly && !charging) return "等待充电"
            if (settings.screenOffOnly && screenOn) return "等待熄屏"
            if (idleMs < settings.quietMinutes * 60_000L) return "等待空闲 ${settings.quietMinutes} 分钟"
            if (foreground) return "应用在前台"
            if (budgetExhausted) return "今日学习额度已用完"
            if (resourcesLimited) return "等待设备资源释放"
        }
        if (!embeddedLocal) {
            if (!connected) return "等待可用网络"
            if (!manual && settings.unmeteredOnly && !unmetered) return "等待非计费网络"
        }
        return null
    }
}

