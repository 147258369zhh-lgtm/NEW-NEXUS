package io.github.mangi.eta.fork.learning

import android.content.Context
import org.json.JSONObject

/** Persistent lock for learning-setting mutations. Lease timestamps survive process death. */
internal object LearningSettingLock {
    private const val PREFS = "eta_learning_setting_lock"
    private const val LOCKED = "locked"
    private const val LEASE_UNTIL = "lease_until"
    private const val MAX_LEASE_MS = 60 * 60 * 1000L

    data class State(val locked: Boolean, val leaseUntil: Long?, val reason: String)

    @Synchronized
    fun state(context: Context, now: Long = System.currentTimeMillis()): State {
        val prefs = prefs(context)
        val until = prefs.getLong(LEASE_UNTIL, 0L)
        if (until > 0 && until <= now) {
            prefs.edit().putLong(LEASE_UNTIL, 0L).putString("reason", "temporary unlock expired").apply()
            audit(context, "lease_expired", "temporary unlock expired", now)
        }
        val activeUntil = prefs.getLong(LEASE_UNTIL, 0L).takeIf { it > now }
        return State(prefs.getBoolean(LOCKED, false) && activeUntil == null, activeUntil, prefs.getString("reason", "").orEmpty())
    }

    fun lock(context: Context, reason: String, now: Long = System.currentTimeMillis()) {
        val why = reason.trim().take(240).ifBlank { "user locked learning settings" }
        prefs(context).edit().putBoolean(LOCKED, true).putLong(LEASE_UNTIL, 0L).putString("reason", why).apply()
        audit(context, "locked", why, now)
    }

    fun temporaryUnlock(context: Context, durationMs: Long, reason: String, now: Long = System.currentTimeMillis()): State {
        require(durationMs in 1..MAX_LEASE_MS) { "unlock duration must be between 1 ms and 1 hour" }
        val why = reason.trim().take(240).ifBlank { "temporary learning settings change" }
        val until = now + durationMs
        prefs(context).edit().putBoolean(LOCKED, true).putLong(LEASE_UNTIL, until).putString("reason", why).apply()
        audit(context, "temporary_unlock", "$why (until=$until)", now)
        return State(false, until, why)
    }

    fun auditTrail(context: Context): List<String> = prefs(context).getString("audit", "[]").let { raw ->
        runCatching { org.json.JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    }

    fun request(context: Context, setting: String, value: Boolean, reason: String = "locked setting change requested", now: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        val rows = runCatching { org.json.JSONArray(p.getString("requests", "[]")) }.getOrElse { org.json.JSONArray() }
        rows.put(JSONObject().put("at", now).put("setting", setting).put("value", value).put("reason", reason.take(240)))
        while (rows.length() > 100) rows.remove(0)
        p.edit().putString("requests", rows.toString()).apply()
        audit(context, "request_only", "$setting=$value: ${reason.take(240)}", now)
    }

    fun requests(context: Context): List<String> = prefs(context).getString("requests", "[]").let { raw ->
        runCatching { org.json.JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    }

    private fun audit(context: Context, action: String, reason: String, now: Long) {
        val p = prefs(context)
        val rows = runCatching { org.json.JSONArray(p.getString("audit", "[]")) }.getOrElse { org.json.JSONArray() }
        rows.put(JSONObject().put("at", now).put("action", action).put("reason", reason))
        while (rows.length() > 100) rows.remove(0)
        p.edit().putString("audit", rows.toString()).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
