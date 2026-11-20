package dev.nfctest

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps the currently resumed Activity so the dispatcher can hand an Intent to
 * the app's own foreground Activity (Android 10+ forbids background launches).
 */
class ResumedActivityTracker(
    private val onDestroyed: (Activity) -> Unit = {},
) : Application.ActivityLifecycleCallbacks {

    private val current = AtomicReference<Activity?>(null)

    val resumed: Activity?
        get() = current.get()?.takeIf { !it.isDestroyed }

    override fun onActivityResumed(activity: Activity) {
        current.set(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        current.compareAndSet(activity, null)
    }

    override fun onActivityDestroyed(activity: Activity) {
        current.compareAndSet(activity, null)
        onDestroyed(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
