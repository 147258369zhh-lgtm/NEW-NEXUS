package io.github.mangi.eta.agent.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager

/** One application-lifetime observer; no polling loop or separate resource database. */
internal class LocalResourcePressureMonitor(context: Context, private val changed: () -> Unit) : AutoCloseable {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)
    private val thermal = PowerManager.OnThermalStatusChangedListener { changed() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) changed()
        }
    }
    private val thermalRegistered = runCatching { power?.addThermalStatusListener(app.mainExecutor, thermal); power != null }.getOrDefault(false)
    private val receiverRegistered = runCatching {
        app.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        true
    }.getOrDefault(false)

    override fun close() {
        if (thermalRegistered) runCatching { power?.removeThermalStatusListener(thermal) }
        if (receiverRegistered) runCatching { app.unregisterReceiver(receiver) }
    }
}
