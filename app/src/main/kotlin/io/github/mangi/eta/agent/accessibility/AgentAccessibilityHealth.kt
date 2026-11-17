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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

internal enum class AccessibilityConnectionStatus { CONNECTED, DISCONNECTED, DISABLED, UNKNOWN }
internal enum class AccessibilityDisconnectReason { UNBOUND, DESTROYED, PROCESS_EXIT_UNREPORTED }

internal data class AccessibilityConnectionHistory(
    val connected: Boolean = false,
    val lastConnectedAtMs: Long? = null,
    val lastDisconnectedAtMs: Long? = null,
    /** Boot in which the elapsed timestamp pair was recorded; null when identity is unavailable. */
    val connectionBootCount: Int? = null,
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
    private const val PREFS = "eta_accessibility_connection_health"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_DISCONNECT_COUNT = "disconnect_count"
    private const val KEY_DISCONNECT_REASON = "disconnect_reason"
    private const val KEY_CONNECTION_BOOT_COUNT = "connection_boot_count"
    private var initialized = false

    @Synchronized
    fun onConnected(context: Context) {
        initialize(context)
        mutableHistory.value = mutableHistory.value.copy(
            connected = true,
            lastConnectedAtMs = SystemClock.elapsedRealtime(),
            connectionBootCount = bootCount(context),
        )
        persist(context, mutableHistory.value)
        AndroidAgentLogger.info("Agent accessibility state=connected disconnect_count=${history.value.disconnectCount}")
    }

    @Synchronized
    fun onDisconnected(context: Context, reason: AccessibilityDisconnectReason) {
        initialize(context)
        val previous = mutableHistory.value
        if (!previous.connected) return
        val updated = previous.copy(
            connected = false,
            lastDisconnectedAtMs = SystemClock.elapsedRealtime(),
            connectionBootCount = previous.connectionBootCount ?: bootCount(context),
            disconnectCount = previous.disconnectCount + 1,
            lastDisconnectReason = reason,
        )
        mutableHistory.value = updated
        persist(context, updated)
        AndroidAgentLogger.warn("Agent accessibility state=disconnected callback=$reason disconnect_count=${history.value.disconnectCount}")
    }

    @Synchronized
    private fun initialize(context: Context) {
        if (initialized) return
        val prefs = preferences(context)
        val wasConnected = prefs.getBoolean(KEY_CONNECTED, false)
        val count = prefs.getInt(KEY_DISCONNECT_COUNT, 0).coerceAtLeast(0)
        val previousReason = prefs.getString(KEY_DISCONNECT_REASON, null)
            ?.let { runCatching { AccessibilityDisconnectReason.valueOf(it) }.getOrNull() }
        val previousBootCount = if (prefs.contains(KEY_CONNECTION_BOOT_COUNT)) {
            prefs.getInt(KEY_CONNECTION_BOOT_COUNT, 0)
        } else {
            null
        }
        val currentBootCount = bootCount(context)
        // Elapsed timestamps do not survive reboot/process restart. Carry boot identity through so a
        // stable window can only be evaluated when the history belongs to this same boot.
        val recovered = recoverPreviousProcessState(
            wasConnected,
            count,
            previousReason,
            SystemClock.elapsedRealtime(),
            currentBootCount.takeIf { previousBootCount == null || previousBootCount == currentBootCount },
        )
        mutableHistory.value = recovered
        if (wasConnected) persist(context, recovered)
        initialized = true
    }

    @Synchronized
    fun snapshot(context: Context): AccessibilityHealthSnapshot {
        initialize(context)
        return AccessibilityHealthSnapshot(
            status = classify(AgentAccessibilityService.isAvailable(), configured(context)),
            history = history.value,
        )
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun persist(context: Context, value: AccessibilityConnectionHistory) {
        preferences(context).edit()
            .putBoolean(KEY_CONNECTED, value.connected)
            .putInt(KEY_DISCONNECT_COUNT, value.disconnectCount.coerceAtLeast(0))
            .apply {
                value.lastDisconnectReason?.let { putString(KEY_DISCONNECT_REASON, it.name) }
                    ?: remove(KEY_DISCONNECT_REASON)
                value.connectionBootCount?.let { putInt(KEY_CONNECTION_BOOT_COUNT, it) }
                    ?: remove(KEY_CONNECTION_BOOT_COUNT)
            }
            .commit()
    }

    internal fun recoverPreviousProcessState(
        wasConnected: Boolean,
        disconnectCount: Int,
        previousReason: AccessibilityDisconnectReason?,
        nowElapsedMs: Long,
        bootCount: Int? = null,
    ): AccessibilityConnectionHistory = if (wasConnected) {
        AccessibilityConnectionHistory(
            connected = false,
            lastDisconnectedAtMs = nowElapsedMs,
            connectionBootCount = bootCount,
            disconnectCount = disconnectCount.coerceAtLeast(0) + 1,
            lastDisconnectReason = AccessibilityDisconnectReason.PROCESS_EXIT_UNREPORTED,
        )
    } else {
        AccessibilityConnectionHistory(
            disconnectCount = disconnectCount.coerceAtLeast(0),
            lastDisconnectReason = previousReason,
            connectionBootCount = bootCount,
        )
    }

    private fun bootCount(context: Context): Int? = runCatching {
        Settings.Global.getInt(context.contentResolver, "boot_count").takeIf { it >= 0 }
    }.getOrNull()

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
    }.conflate().flowOn(Dispatchers.IO)
}
