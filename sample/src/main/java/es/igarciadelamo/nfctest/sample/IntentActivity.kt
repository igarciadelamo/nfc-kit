package es.igarciadelamo.nfctest.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives tags delivered as Intents (foreground, implicit and cold-start).
 *
 * singleTop + explicit intent with FLAG_ACTIVITY_SINGLE_TOP means a second tag
 * arrives in onNewIntent, exactly like the platform's NFC dispatch. A cold
 * start arrives in onCreate.
 */
class IntentActivity : Activity() {

    private lateinit var content: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdInstances.incrementAndGet()
        liveInstances.incrementAndGet()
        content = TextView(this).apply {
            id = R.id.received_info
            setPadding(32, 32 + statusBarHeight(), 32, 32)
        }
        setContentView(content)
        handleIntent(intent, "onCreate")
    }

    override fun onDestroy() {
        liveInstances.decrementAndGet()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, "onNewIntent")
    }

    private fun handleIntent(intent: Intent?, path: String) {
        if (intent == null || intent.action?.startsWith(NFC_ACTION_PREFIX) != true) {
            // Ignore the launcher intent; this screen only cares about NFC dispatch.
            content.text = "Waiting for a tag..."
            return
        }
        val delivery = ReceivedTags.fromIntent(
            SOURCE,
            intent,
            Thread.currentThread().name,
            path,
        )
        Log.i(
            TAG,
            "created=${createdInstances.get()} live=${liveInstances.get()} path=$path " +
                "action=${delivery.action} data=${delivery.data} type=${delivery.type} " +
                "uid=${delivery.uid} records=${delivery.records.size}",
        )
        content.text = "path=$path action=${delivery.action} " +
            "records=${delivery.records.size} uid=${delivery.uid}"
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private companion object {
        const val TAG = "NfcSample"
        const val SOURCE = "intent"
        const val NFC_ACTION_PREFIX = "android.nfc.action."
        val createdInstances = AtomicInteger(0)
        val liveInstances = AtomicInteger(0)
    }
}
