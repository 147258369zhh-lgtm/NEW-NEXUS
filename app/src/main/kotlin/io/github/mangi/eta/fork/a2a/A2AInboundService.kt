package io.github.mangi.eta.fork.a2a

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.mangi.eta.R
import io.github.mangi.eta.core.NexusUiContract

/** Foreground owner for the private inbound A2A listener. */
internal class A2AInboundService : Service() {
    private var transport: A2ATransport? = null
    private lateinit var configStore: A2AInboundConfigStore
    private lateinit var accessStore: A2AAccessPolicyStore
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthJob: Job? = null
    private var retryJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var consecutiveProbeFailures = 0
    private var retryAttempt = 0

    override fun onCreate() {
        super.onCreate()
        A2ARuntimeBootstrap.ensure(this)
        configStore = A2AInboundConfigStore(this)
        accessStore = A2AAccessPolicyStore(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nexus:A2AInbound")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "A2A incoming endpoint", NotificationManager.IMPORTANCE_LOW),
        )
        startForegroundCompat("Starting private A2A endpoint")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            configStore.setEnabled(false)
            stopListener()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART) {
            retryJob?.cancel()
            stopEngine()
            startListenerIfNeeded()
            return START_STICKY
        }
        if (!configStore.snapshot().enabled) {
            configStore.setLastError(null)
            stopSelf()
            return START_NOT_STICKY
        }
        startListenerIfNeeded()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopListener()
        serviceScope.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // This foreground endpoint is intentionally independent from the task/Activity lifecycle.
        super.onTaskRemoved(rootIntent)
    }

    @Synchronized
    private fun startListenerIfNeeded() {
        if (transport != null) {
            startHealthMonitor()
            return
        }
        val snapshot = configStore.snapshot()
        if (!snapshot.enabled) {
            stopSelf()
            return
        }
        if (snapshot.token.isBlank()) {
            configStore.setLastError("Unable to create the inbound bearer token")
            stopSelf()
            return
        }
        var createdTransport: A2ATransport? = null
        runCatching {
            A2ATransportFactory.create(
                context = this,
                snapshot = snapshot,
                tokenProvider = { configStore.loadToken().orEmpty() },
                externalEndpointProvider = { configStore.snapshot().advertisedEndpointUrl },
                accessStore = accessStore,
            ).also { created ->
                createdTransport = created
                created.start()
                this.transport = created
            }
            configStore.setLastError(null)
            configStore.setProbeState(true, transport?.currentAddress())
            retryAttempt = 0
            consecutiveProbeFailures = 0
            startHealthMonitor()
            updateNotification(
                "Listening via ${snapshot.transportMode} on " +
                    (transport?.currentAddress() ?: "port ${snapshot.port}"),
            )
        }.onFailure { failure ->
            runCatching { createdTransport?.stop() }
            transport = null
            consecutiveProbeFailures = 0
            configStore.setLastError(failure.message ?: failure.javaClass.simpleName)
            configStore.setProbeState(false, null)
            updateNotification("A2A endpoint failed to start")
            scheduleRestart("start-failed")
        }
    }

    private fun stopListener() {
        retryJob?.cancel()
        retryJob = null
        stopEngine()
        configStore.setProbeState(false, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopEngine() {
        healthJob?.cancel()
        healthJob = null
        runCatching { transport?.stop() }
        transport = null
    }

    private fun startHealthMonitor() {
        if (healthJob?.isActive == true) return
        healthJob = serviceScope.launch {
            while (isActive && configStore.snapshot().enabled) {
                delay(HEALTH_PROBE_INTERVAL_MS)
                val snapshot = configStore.snapshot()
                val healthy = withContext(Dispatchers.IO) {
                    runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("127.0.0.1", snapshot.port), HEALTH_PROBE_TIMEOUT_MS)
                        }
                        true
                    }.getOrDefault(false)
                }
                configStore.setProbeState(healthy, transport?.currentAddress())
                if (healthy) {
                    consecutiveProbeFailures = 0
                    updateNotification("Listening on ${transport?.currentAddress() ?: "port ${snapshot.port}"}")
                } else {
                    consecutiveProbeFailures += 1
                    updateNotification("Health probe failed ($consecutiveProbeFailures/2)")
                    if (consecutiveProbeFailures >= MAX_PROBE_FAILURES) {
                        scheduleRestart("health-probe-failed")
                        return@launch
                    }
                }
            }
        }
    }

    private fun scheduleRestart(reason: String) {
        configStore.setLastError("A2A service restart scheduled: $reason")
        if (retryJob?.isActive == true) return
        stopEngine()
        consecutiveProbeFailures = 0
        configStore.recordRestart()
        retryJob = serviceScope.launch {
            val attempt = ++retryAttempt
            val waitMs = (1_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(30_000L)
            delay(waitMs)
            if (configStore.snapshot().enabled) startListenerIfNeeded()
        }
    }

    private fun startForegroundCompat(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification(text))
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            NexusUiContract.launchIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, A2AInboundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Nexus A2A endpoint")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "eta_a2a_inbound"
        private const val NOTIFICATION_ID = 1113
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_A2A_INBOUND"
        private const val ACTION_RESTART = "io.github.mangi.eta.action.RESTART_A2A_INBOUND"
        private const val HEALTH_PROBE_INTERVAL_MS = 15_000L
        private const val HEALTH_PROBE_TIMEOUT_MS = 1_000
        private const val MAX_PROBE_FAILURES = 2

        fun start(context: Context) {
            context.applicationContext.startForegroundService(Intent(context, A2AInboundService::class.java))
        }

        fun stop(context: Context) {
            context.applicationContext.startService(
                Intent(context, A2AInboundService::class.java).setAction(ACTION_STOP),
            )
        }

        fun restart(context: Context) {
            context.applicationContext.startForegroundService(
                Intent(context, A2AInboundService::class.java).setAction(ACTION_RESTART),
            )
        }
    }
}
