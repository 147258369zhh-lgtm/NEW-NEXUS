package io.github.mangi.eta.agent.accessibility

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

internal enum class AccessibilityConnectionStatus { CONNECTED, DISCONNECTED, DISABLED, UNKNOWN }
internal enum class AccessibilityDisconnectReason { UNBOUND, DESTROYED }

internal data class AccessibilityConnectionHistory(
    val connected: Boolean = false,
    val lastConnectedAtMs: Long? = null,
    val lastDisconnectedAtMs: Long? = null,
    val disconnectCount: Int = 0,
    val lastDisconnectReason: AccessibilityDisconnectReason? = null,
)

internal data class AccessibilityHealthSnapshot(
    val status: AccessibilityConnectionStatus,
    val history: AccessibilityConnectionHistory,
)

/** 系统授权与实际连接是两种状态。读取与订阅均不申请 Root、不写系统设置、不轮询。 */
internal object AgentAccessibilityHealth {
    private val mutableHistory = MutableStateFlow(AccessibilityConnectionHistory())
    val history = mutableHistory.asStateFlow()

    @Synchronized
    fun onConnected() {
        mutableHistory.value = mutableHistory.value.copy(
            connected = true,
            lastConnectedAtMs = SystemClock.elapsedRealtime(),
        )
        AndroidAgentLogger.info("Agent accessibility state=connected disconnect_count=${history.value.disconnectCount}")
    }

    @Synchronized
    fun onDisconnected(reason: AccessibilityDisconnectReason) {
        val previous = mutableHistory.value
        if (!previous.connected) return
        mutableHistory.value = previous.copy(
            connected = false,
            lastDisconnectedAtMs = SystemClock.elapsedRealtime(),
            disconnectCount = previous.disconnectCount + 1,
            lastDisconnectReason = reason,
        )
        AndroidAgentLogger.warn("Agent accessibility state=disconnected callback=$reason disconnect_count=${history.value.disconnectCount}")
    }

    fun snapshot(context: Context): AccessibilityHealthSnapshot = AccessibilityHealthSnapshot(
        status = classify(AgentAccessibilityService.isAvailable(), configured(context)),
        history = history.value,
    )

    fun configured(context: Context): Boolean? = try {
        val resolver = context.contentResolver
        val enabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        enabled && containsService(
            Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            ComponentName(context, AgentAccessibilityService::class.java),
        )
    } catch (_: RuntimeException) {
        null
    }

    internal fun containsService(services: String?, expected: ComponentName): Boolean =
        services.orEmpty().split(':').any { ComponentName.unflattenFromString(it.trim()) == expected }

    internal fun classify(connected: Boolean, configured: Boolean?): AccessibilityConnectionStatus = when {
        connected -> AccessibilityConnectionStatus.CONNECTED
        configured == true -> AccessibilityConnectionStatus.DISCONNECTED
        configured == false -> AccessibilityConnectionStatus.DISABLED
        else -> AccessibilityConnectionStatus.UNKNOWN
    }

    fun observeChanges(context: Context) = callbackFlow {
        val resolver = context.applicationContext.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        // 某些 ROM 拒绝订阅时仍保留生命周期和页面 ON_RESUME 刷新。
        try {
            listOf(Settings.Secure.ACCESSIBILITY_ENABLED, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .forEach { resolver.registerContentObserver(Settings.Secure.getUriFor(it), false, observer) }
        } catch (_: RuntimeException) {
            runCatching { resolver.unregisterContentObserver(observer) }
        }
        val historyJob = launch { history.collect { trySend(Unit) } }
        trySend(Unit)
        awaitClose {
            historyJob.cancel()
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }.conflate()
}
