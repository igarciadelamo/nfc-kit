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

    private lateinit var state: TextView
    private lateinit var details: TextView
    private lateinit var modal: TagModal

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdInstances.incrementAndGet()
        liveInstances.incrementAndGet()
        setContentView(R.layout.activity_sample)
        findViewById<TextView>(R.id.header_title).setText(R.string.intent_title)
        findViewById<TextView>(R.id.header_subtitle).setText(R.string.intent_subtitle)
        state = findViewById(R.id.state)
        details = findViewById(R.id.received_info)
        modal = TagModal(findViewById(R.id.tag_modal))
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
            state.setText(R.string.state_waiting)
            state.setTextColor(getColor(R.color.nfc_text_muted))
            details.setText(R.string.delivery_hint)
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
        state.setText(R.string.state_received)
        state.setTextColor(getColor(R.color.nfc_success_dark))
        details.text = "path=$path action=${delivery.action} " +
            "records=${delivery.records.size} uid=${delivery.uid}"
        modal.show(delivery)
    }

    private companion object {
        const val TAG = "NfcSample"
        const val SOURCE = "intent"
        const val NFC_ACTION_PREFIX = "android.nfc.action."
        val createdInstances = AtomicInteger(0)
        val liveInstances = AtomicInteger(0)
    }
}
