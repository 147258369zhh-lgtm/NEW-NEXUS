package io.github.mangi.eta.agent.packages

import android.app.Activity
import android.app.Application
import android.os.Bundle
import org.json.JSONObject

internal class PackageAppLifecycle(private val app: Application) : Application.ActivityLifecycleCallbacks {
    private val started = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Activity, Boolean>())
    fun install() {
        app.registerActivityLifecycleCallbacks(this)
        emit("application_on_create")
    }
    private fun emit(name: String, activity: Activity? = null) = PackageApplicationEvents.emit(app, "AppLifecycleHook", name,
        JSONObject().put("extras", JSONObject().apply { activity?.let { put("activityName", it.javaClass.name) } }))
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = emit("activity_on_create", activity)
    override fun onActivityStarted(activity: Activity) {
        if (started.add(activity) && started.size == 1) emit("application_on_foreground")
        emit("activity_on_start", activity)
    }
    override fun onActivityResumed(activity: Activity) = emit("activity_on_resume", activity)
    override fun onActivityPaused(activity: Activity) = emit("activity_on_pause", activity)
    override fun onActivityStopped(activity: Activity) {
        emit("activity_on_stop", activity)
        if (started.remove(activity) && started.isEmpty()) emit("application_on_background")
    }
    override fun onActivityDestroyed(activity: Activity) { started.remove(activity); emit("activity_on_destroy", activity) }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
