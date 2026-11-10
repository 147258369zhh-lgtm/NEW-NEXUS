package io.github.mangi.eta.fork.tasks

import android.app.*
import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.core.NexusUiContract
import kotlinx.coroutines.*
import java.util.UUID

/** User-enabled foreground monitor, paired with WorkManager recovery when the ROM stops it. */
internal class AutomationMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networkTransports = mutableMapOf<Network, Set<Int>>()
    private fun transports(capabilities: NetworkCapabilities): Set<Int> = buildSet {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetworkCapabilities.TRANSPORT_WIFI)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val type = when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> AutomationTrigger.CHARGING
                Intent.ACTION_POWER_DISCONNECTED -> AutomationTrigger.CHARGING_STOPPED
                Intent.ACTION_USER_PRESENT -> AutomationTrigger.UNLOCKED
                Intent.ACTION_SCREEN_ON -> AutomationTrigger.SCREEN_ON
                Intent.ACTION_SCREEN_OFF -> AutomationTrigger.SCREEN_OFF
                BluetoothDevice.ACTION_ACL_CONNECTED -> AutomationTrigger.BLUETOOTH_CONNECTED
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> AutomationTrigger.BLUETOOTH_DISCONNECTED
                "android.intent.action.HEADSET_PLUG" -> if (intent.getIntExtra("state", 0) == 1) {
                    AutomationTrigger.HEADSET_CONNECTED
                } else {
                    AutomationTrigger.HEADSET_DISCONNECTED
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> if (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) {
                    AutomationTrigger.AIRPLANE_MODE_ON
                } else {
                    AutomationTrigger.AIRPLANE_MODE_OFF
                }
                else -> AutomationTrigger.STATE
            }
            AutomationTriggers.signal(context, type, UUID.randomUUID().toString())
        }
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Capabilities arrive immediately after availability; wait so Wi-Fi/cellular can be classified.
        }
        override fun onLost(network: Network) {
            val (lost, remaining) = synchronized(networkTransports) {
                val lost = networkTransports.remove(network).orEmpty()
                lost to networkTransports.values.flatten().toSet()
            }
            if (NetworkCapabilities.TRANSPORT_WIFI in lost && NetworkCapabilities.TRANSPORT_WIFI !in remaining) {
                AutomationTriggers.signal(this@AutomationMonitorService, AutomationTrigger.WIFI_DISCONNECTED, "lost:$network")
            }
            if (NetworkCapabilities.TRANSPORT_CELLULAR in lost && NetworkCapabilities.TRANSPORT_CELLULAR !in remaining) {
                AutomationTriggers.signal(this@AutomationMonitorService, AutomationTrigger.MOBILE_DISCONNECTED, "lost:$network")
            }
            if (remaining.isEmpty()) {
                AutomationTriggers.signal(this@AutomationMonitorService, AutomationTrigger.NETWORK_DISCONNECTED, "lost:$network")
            }
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
            val transports = transports(capabilities)
            if (transports.isEmpty()) return
            val (added, becameAvailable) = synchronized(networkTransports) {
                val hadNetwork = networkTransports.values.any { it.isNotEmpty() }
                val previous = networkTransports[network].orEmpty()
                networkTransports[network] = transports
                (transports - previous) to !hadNetwork
            }
            if (becameAvailable) {
                AutomationTriggers.signal(this@AutomationMonitorService, AutomationTrigger.NETWORK_CONNECTED, network.toString())
            }
            added.forEach { transport ->
                AutomationTriggers.signal(
                    this@AutomationMonitorService,
                    if (transport == NetworkCapabilities.TRANSPORT_WIFI) AutomationTrigger.WIFI_CONNECTED
                    else AutomationTrigger.MOBILE_CONNECTED,
                    network.toString(),
                )
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("automation-monitor", "自动任务监听", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1762, NexusUiContract.launchIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1763, Intent(this, AutomationMonitorService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "automation-monitor").setSmallIcon(R.drawable.ic_notification).setContentTitle("自动任务监听中")
            .setContentText("设备事件与已授权目录").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止监听", stop).build()).build()
        val hasForegroundLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val canMonitorLocation = hasForegroundLocation &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            if (canMonitorLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            startForeground(1762, notification, serviceTypes)
        } catch (_: SecurityException) {
            // Android can block a location foreground service during boot/background restore.
            startForeground(1762, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
        running.value = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction("android.intent.action.HEADSET_PLUG"); addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            if (ContextCompat.checkSelfPermission(this@AutomationMonitorService, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val connectivity = getSystemService(ConnectivityManager::class.java)
        connectivity.activeNetwork?.let { active ->
            connectivity.getNetworkCapabilities(active)?.let { capabilities ->
                transports(capabilities).takeIf { it.isNotEmpty() }?.let { networkTransports[active] = it }
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        scope.launch {
            var nextFullScanAt = 0L
            while (isActive) {
                try {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now >= nextFullScanAt) {
                        AutomationTriggers.poll(this@AutomationMonitorService)
                        nextFullScanAt = now + 60_000L
                        val dao = AgentTaskDatabase.get(this@AutomationMonitorService).agentTaskDao()
                        if (dao.executingCount() == 0 && dao.nextClaimableRunId(System.currentTimeMillis()) != null) {
                            AgentTaskWorkScheduler.checkNow(this@AutomationMonitorService)
                        }
                    } else {
                        AutomationTriggers.pollAppOpenTriggers(this@AutomationMonitorService)
                    }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The persisted trigger detail reports scan failures. */ }
                delay(10_000)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { setEnabled(this, false); stopSelf() }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        running.value = false
        scope.cancel(); unregisterReceiver(receiver)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        super.onDestroy()
    }
    companion object {
        val running = kotlinx.coroutines.flow.MutableStateFlow(false)
        fun enabled(context: Context) = context.getSharedPreferences("automation-monitor", 0).getBoolean("enabled", false)
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("automation-monitor", 0).edit().putBoolean("enabled", enabled).apply()
            if (enabled) {
                try {
                    check(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) { "请先开启通知权限" }
                    context.startForegroundService(Intent(context, AutomationMonitorService::class.java))
                } catch (failure: Throwable) {
                    context.getSharedPreferences("automation-monitor", 0).edit().putBoolean("enabled", false).apply()
                    throw failure
                }
            } else context.stopService(Intent(context, AutomationMonitorService::class.java))
        }

        /** Restores a user-enabled monitor after process death or reboot when Android permits it. */
        fun restoreIfEnabled(context: Context) {
            if (!enabled(context)) return
            runCatching {
                if (context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                    context.startForegroundService(Intent(context, AutomationMonitorService::class.java))
                }
            }
        }
    }
}
