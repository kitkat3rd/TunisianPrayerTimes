package com.tunisianprayertimes.mosque.trace

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ViewTreeObserver
import com.tunisianprayertimes.MainActivity
import com.tunisianprayertimes.adhan.AdhanSettingsActivity
import com.tunisianprayertimes.mosque.MosqueSettingsActivity
import com.tunisianprayertimes.mosque.SilenceWalkActivity

/** Only ordinary foreground screens can launch the diagnostic location service. */
internal class DebugWalkAutoStart : Application.ActivityLifecycleCallbacks {
    companion object {
        fun install(app: Application) = app.registerActivityLifecycleCallbacks(DebugWalkAutoStart())
    }

    private val focusListeners = mutableMapOf<Activity, ViewTreeObserver.OnWindowFocusChangeListener>()

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity && activity !is MosqueSettingsActivity &&
            activity !is SilenceWalkActivity && activity !is AdhanSettingsActivity) return
        if (activity.isFinishing || activity.isDestroyed) return
        if (activity !in focusListeners) {
            // A runtime permission dialog can return focus without a second onResume.
            val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                if (focused && activity in focusListeners && !activity.isFinishing && !activity.isDestroyed) {
                    SilenceWalkRecorderService.startAutomatically(activity)
                }
            }
            activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            focusListeners[activity] = listener
        }
        SilenceWalkRecorderService.startAutomatically(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        val listener = focusListeners.remove(activity) ?: return
        val observer = activity.window.decorView.viewTreeObserver
        if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
    }

    override fun onActivityDestroyed(activity: Activity) = onActivityPaused(activity)
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
