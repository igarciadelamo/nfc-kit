package dev.nfctest

import android.app.Application
import android.content.Context
import android.util.Log
import dev.nfctest.api.NfcReaders
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide debug runtime, installed by [NfcTestInitProvider] before any
 * component of the host app uses NfcReaders.
 */
object NfcTestRuntime {

    const val LOG_TAG = "NfcTest"

    private val installed = AtomicBoolean(false)

    @Volatile
    lateinit var tracker: ResumedActivityTracker
        private set

    @Volatile
    lateinit var reader: InjectableNfcReader
        private set

    @Volatile
    lateinit var dispatcher: TagDispatcher
        private set

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return

        val application = context.applicationContext as Application
        val injectable = InjectableNfcReader()
        reader = injectable
        tracker = ResumedActivityTracker(onDestroyed = injectable::onActivityDestroyed)
        dispatcher = TagDispatcher(application, tracker, injectable)
        application.registerActivityLifecycleCallbacks(tracker)
        NfcReaders.factory = { injectable }
        Log.i(LOG_TAG, "nfc-test debug runtime installed")
    }
}
