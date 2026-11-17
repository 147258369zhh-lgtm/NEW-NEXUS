package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.libxposed.service.XposedService
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootAccessState
import io.github.mangi.eta.agent.device.RootAccessStatus
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityHealth
import io.github.mangi.eta.agent.accessibility.RootAccessibilityRecovery
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal data class DeviceCapabilitiesUi(
    val root: RootAccessState,
    // 服务回调只反映 Binder 连接，不能用于判断管理器中的模块开关或 Hook 生效状态。
    val xposedConnected: Boolean,
    val tools: AgentToolCapabilities,
) {
    val accessibilityAvailable: Boolean get() = tools.accessibilityAvailable
}


@Composable
internal fun rememberDeviceCapabilities(): DeviceCapabilitiesUi {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val root by RootAccess.state.collectAsState()
    val recovery by RootAccessibilityRecovery.state.collectAsState()
    var xposedConnected by remember { mutableStateOf(EtaApp.serviceInstance != null) }
    val runtime by SettingsRuntimeSnapshotStore.state.collectAsState()
    val owner = LocalLifecycleOwner.current
    val tools = runtime.capabilities
    LaunchedEffect(root, xposedConnected, recovery) {
        SettingsRuntimeSnapshotStore.refresh(context, maxAgeMillis = 2_000L)
    }
    LaunchedEffect(context, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            AgentAccessibilityHealth.observeChanges(context).collect {
                SettingsRuntimeSnapshotStore.refresh(context)
            }
        }
    }
    DisposableEffect(owner, context) {
        val listener = object : EtaApp.ServiceStateListener {
            override fun onServiceStateChanged(service: XposedService?) {
                xposedConnected = service != null
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Activity-level resume already refreshes Root. Returning between settings
                // entries must not start another su probe and fan out capability recompositions.
                scope.launch { SettingsRuntimeSnapshotStore.refresh(context, maxAgeMillis = 2_000L) }
            }
        }
        EtaApp.addServiceStateListener(listener, notifyImmediately = true)
        owner.lifecycle.addObserver(observer)
        onDispose {
            EtaApp.removeServiceStateListener(listener)
            owner.lifecycle.removeObserver(observer)
        }
    }
    return DeviceCapabilitiesUi(root, xposedConnected, tools.copy(
        rootAvailable = root.isGranted,
        rootState = root,
        lsposedAvailable = xposedConnected,
    ))
}

internal fun RootAccessState.description(context: Context): String = context.getString(
    when {
        isChecking -> R.string.capability_root_checking
        status == RootAccessStatus.GRANTED -> R.string.capability_root_granted
        status == RootAccessStatus.UNAVAILABLE -> R.string.capability_root_unavailable
        status == RootAccessStatus.TIMED_OUT -> R.string.capability_root_timeout
        status == RootAccessStatus.DENIED -> R.string.capability_root_denied
        else -> R.string.capability_root_not_granted
    },
)
