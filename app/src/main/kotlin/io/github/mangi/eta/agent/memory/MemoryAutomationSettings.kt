package io.github.mangi.eta.agent.memory

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@kotlinx.serialization.Serializable
internal data class MemoryAutomationSettings(
    val extractMemory: Boolean = true,
    val idleEnabled: Boolean = true,
    val chargingOnly: Boolean = true,
    val screenOffOnly: Boolean = true,
    val unmeteredOnly: Boolean = true,
    val intervalMinutes: Int = 60,
    val quietMinutes: Int = 10,
    val dailyLimit: Int = 6,
    val batchSize: Int = 2,
    val retentionDays: Int = 30,
    val capturePreferences: Boolean = true,
    val captureDecisions: Boolean = true,
    val captureProcedures: Boolean = true,
)

internal object MemoryAutomationSettingsStore {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("eta_memory_automation", Context.MODE_PRIVATE)
    fun read(context: Context): MemoryAutomationSettings = prefs(context).let { p ->
        MemoryAutomationSettings(p.getBoolean("extract", true), p.getBoolean("idle", true),
            p.getBoolean("charging", true), p.getBoolean("screen_off", true), p.getBoolean("wifi", true),
            p.getInt("interval", 60).coerceIn(15, 1440), p.getInt("quiet", 10).coerceIn(1, 120),
            p.getInt("daily", 6).coerceIn(1, 50), p.getInt("batch", 2).coerceIn(1, 10),
            p.getInt("retention", 30).coerceIn(1, 365), p.getBoolean("preferences", true),
            p.getBoolean("decisions", true), p.getBoolean("procedures", true))
    }
    fun save(context: Context, s: MemoryAutomationSettings) {
        require(s.intervalMinutes in 15..1440 && s.quietMinutes in 1..120 && s.dailyLimit in 1..50 &&
            s.batchSize in 1..10 && s.retentionDays in 1..365) { "MEMORY_POLICY_RANGE_INVALID" }
        check(prefs(context).edit().putBoolean("extract", s.extractMemory).putBoolean("idle", s.idleEnabled)
            .putBoolean("charging", s.chargingOnly).putBoolean("screen_off", s.screenOffOnly)
            .putBoolean("wifi", s.unmeteredOnly).putInt("interval", s.intervalMinutes)
            .putInt("quiet", s.quietMinutes).putInt("daily", s.dailyLimit).putInt("batch", s.batchSize)
            .putInt("retention", s.retentionDays).putBoolean("preferences", s.capturePreferences)
            .putBoolean("decisions", s.captureDecisions).putBoolean("procedures", s.captureProcedures).commit()) { "MEMORY_POLICY_SAVE_FAILED" }
        MemoryMaintenanceScheduler.sync(context)
        if (s.idleEnabled) io.github.mangi.eta.agent.skill.SkillEvolution.enqueue(context)
    }
    fun observe(context: Context) = callbackFlow {
        val p = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key !in setOf("status", "last_activity")) trySend(read(context))
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        trySend(read(context))
        awaitClose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
    fun touch(context: Context) { prefs(context).edit().putLong("last_activity", System.currentTimeMillis()).apply() }
    fun lastActivity(context: Context): Long = prefs(context).getLong("last_activity", 0)
    fun status(context: Context): String = prefs(context).getString("status", "尚未整理").orEmpty()
    fun status(context: Context, message: String) { prefs(context).edit().putString("status", message).apply() }
    fun observeStatus(context: Context) = callbackFlow {
        val p = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "status" || key == null) trySend(status(context))
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        trySend(status(context))
        awaitClose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
}
