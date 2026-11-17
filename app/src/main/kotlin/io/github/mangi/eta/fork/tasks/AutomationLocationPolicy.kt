package io.github.mangi.eta.fork.tasks

import android.location.Location
import android.os.SystemClock

internal object AutomationLocationPolicy {
    private const val MAX_AGE_MILLIS = 120_000L

    fun isRecent(location: Location, now: Long = System.currentTimeMillis(),
        elapsedNanos: Long = SystemClock.elapsedRealtimeNanos()): Boolean {
        val age = if (location.elapsedRealtimeNanos > 0) {
            (elapsedNanos - location.elapsedRealtimeNanos).takeIf { it >= 0 }?.div(1_000_000) ?: return false
        } else now - location.time
        return age in 0..MAX_AGE_MILLIS && location.hasAccuracy() &&
            location.accuracy.isFinite() && location.accuracy > 0 &&
            location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
            location.longitude.isFinite() && location.longitude in -180.0..180.0
    }

    // Keep the previous side while the accuracy circle overlaps the region boundary.
    fun isInside(location: Location, area: LocationAutomationConfig): Boolean? {
        if (!isRecent(location)) return null
        val distance = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, area.latitude, area.longitude, distance)
        return when {
            distance[0] + location.accuracy <= area.radiusMeters -> true
            distance[0] - location.accuracy > area.radiusMeters -> false
            else -> null
        }
    }
}
