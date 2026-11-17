package io.github.mangi.eta.agent.memory

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks whether any Nexus activity is visible. Idle learning is a background nicety, so it yields
 * the moment the user opens the app and only resumes after the quiet window passes again.
 */
internal object AppForegroundState {
    private val resumed = AtomicInteger(0)

    val isVisible: Boolean get() = resumed.get() > 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed.incrementAndGet()
                // A foreground visit is user activity: restart the idle quiet window.
                runCatching { MemoryAutomationSettingsStore.touch(activity.applicationContext) }
            }

            override fun onActivityPaused(activity: Activity) {
                while (true) {
                    val current = resumed.get()
                    if (current <= 0 || resumed.compareAndSet(current, current - 1)) return
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Test hook; production state comes from the activity lifecycle. */
    internal fun setVisibleForTesting(value: Boolean) {
        resumed.set(if (value) 1 else 0)
    }
}

