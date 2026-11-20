package dev.nfctest

import android.app.Activity
import android.nfc.Tag
import android.util.Log
import dev.nfctest.api.EnableResult
import dev.nfctest.api.NfcReader
import dev.nfctest.api.RealNfcReader
import dev.nfctest.api.TagCallback
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Decorator around [RealNfcReader].
 *
 * It always reports [EnableResult.ENABLED] so a debug build keeps working on
 * devices without an NFC adapter, logs what the real reader actually said, and
 * remembers the callback so [deliver] can inject fake tags.
 *
 * Injected callbacks run on a dedicated background thread, mirroring the binder
 * thread the platform uses for reader-mode callbacks.
 */
class InjectableNfcReader(
    private val real: NfcReader = RealNfcReader(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, BINDER_THREAD)
    },
) : NfcReader {

    private data class Registration(val activity: Activity, val callback: TagCallback)

    @Volatile
    private var registration: Registration? = null

    /** True while an Activity has registered a callback and is still alive. */
    val isActive: Boolean
        get() = registration?.let { !it.activity.isDestroyed } ?: false

    override fun enable(activity: Activity, callback: TagCallback, flags: Int): EnableResult {
        val realResult = try {
            real.enable(activity, callback, flags)
        } catch (t: Throwable) {
            Log.w(TAG, "real reader enable failed: ${t.javaClass.name}: ${t.message}", t)
            EnableResult.NO_ADAPTER
        }
        registration = Registration(activity, callback)
        Log.i(TAG, "reader enabled (real=$realResult, injection=ENABLED)")
        return EnableResult.ENABLED
    }

    override fun disable(activity: Activity) {
        val cleared = registration?.takeIf { it.activity === activity }
        if (cleared != null) registration = null
        try {
            real.disable(activity)
        } catch (t: Throwable) {
            Log.w(TAG, "real reader disable failed: ${t.javaClass.name}: ${t.message}", t)
        }
        Log.i(TAG, "reader disabled (was registered=${cleared != null})")
    }

    /** Fires the registered callback on the binder-like thread. */
    fun deliver(tag: Tag): Boolean {
        val current = registration ?: return false
        if (current.activity.isDestroyed) {
            registration = null
            return false
        }
        executor.execute { current.callback.onTagDiscovered(tag) }
        return true
    }

    fun onActivityDestroyed(activity: Activity) {
        registration?.takeIf { it.activity === activity }?.let { registration = null }
    }

    private companion object {
        const val TAG = NfcTestRuntime.LOG_TAG
        const val BINDER_THREAD = "NfcTest-binder"
    }
}
