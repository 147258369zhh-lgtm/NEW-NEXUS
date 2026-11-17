package io.github.mangi.eta.ui.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import io.github.mangi.eta.agent.accessibility.AccessibilityConnectionHistory
import io.github.mangi.eta.agent.accessibility.AccessibilityConnectionStatus
import io.github.mangi.eta.agent.accessibility.AccessibilityHealthSnapshot
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionClient
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityHealth
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.voice.EtaVoiceInteractionService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class SettingsRuntimeSnapshot(
    val capabilities: AgentToolCapabilities = AgentToolCapabilities(
        rootAvailable = RootAccess.state.value.isGranted,
        rootState = RootAccess.state.value,
    ),
    val overlayGranted: Boolean = false,
    val assistantActive: Boolean = false,
    val accessibilityHealth: AccessibilityHealthSnapshot = AccessibilityHealthSnapshot(
        status = AccessibilityConnectionStatus.UNKNOWN,
        history = AccessibilityConnectionHistory(),
    ),
    val accessibilityProtectionEnabled: Boolean = false,
)

/**
 * Settings pages and capability surfaces share a process-wide snapshot. System and permission
 * reads are performed off the main thread and serialized so multiple visible readers do not issue
 * duplicate binder checks while a settings transition is rendering.
 */
internal object SettingsRuntimeSnapshotStore {
    private val mutableState = MutableStateFlow(SettingsRuntimeSnapshot())
    val state = mutableState.asStateFlow()
    private val refreshMutex = Mutex()
    private val requestedCapture = AtomicLong()
    private var completedCapture = 0L
    @Volatile private var lastCapturedAtMs = 0L

    fun prewarm(context: Context, scope: CoroutineScope) {
        scope.launch { refresh(context.applicationContext) }
    }

    suspend fun refresh(context: Context, maxAgeMillis: Long = 0L) {
        val ticket = requestedCapture.incrementAndGet()
        refreshMutex.withLock {
            if (completedCapture >= ticket) return@withLock
            val now = SystemClock.elapsedRealtime()
            if (maxAgeMillis > 0L && lastCapturedAtMs != 0L && now - lastCapturedAtMs in 0..maxAgeMillis) {
                return@withLock
            }
            val appContext = context.applicationContext
            do {
                val through = requestedCapture.get()
                val fresh = withContext(Dispatchers.IO) {
                    SettingsRuntimeSnapshot(
                        capabilities = AgentToolCapabilities.capture(appContext),
                        overlayGranted = Settings.canDrawOverlays(appContext),
                        assistantActive = EtaVoiceInteractionService.isDefaultAssistant(appContext),
                        accessibilityHealth = AgentAccessibilityHealth.snapshot(appContext),
                        accessibilityProtectionEnabled = AccessibilityProtectionClient.isEnabled(appContext),
                    )
                }
                mutableState.value = fresh
                lastCapturedAtMs = SystemClock.elapsedRealtime()
                completedCapture = through
                // Requests queued during capture need one trailing sample (the permission could have
                // changed mid-capture), rather than one full Binder scan per waiting settings page.
            } while (requestedCapture.get() > completedCapture)
        }
    }
}
