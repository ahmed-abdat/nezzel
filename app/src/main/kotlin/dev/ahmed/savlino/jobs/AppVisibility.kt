package dev.ahmed.savlino.jobs

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Counts every app window, including the share sheet, without relying on the Home composition. */
internal class AppVisibility(private val opened: () -> Unit) : Application.ActivityLifecycleCallbacks {
    private var started = 0
    @Volatile var foreground = false
        private set
    override fun onActivityStarted(activity: Activity) {
        started++
        val alreadyOpen = foreground
        foreground = true
        if (!alreadyOpen) opened()
    }
    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        foreground = started > 0 || activity.isChangingConfigurations
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
