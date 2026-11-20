package dev.nfctest.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView

/**
 * Receives tags delivered as Intents (foreground and implicit modes).
 *
 * singleTop + explicit intent with FLAG_ACTIVITY_SINGLE_TOP means a second tag
 * arrives in onNewIntent, exactly like the platform's NFC dispatch.
 */
class IntentActivity : Activity() {

    private lateinit var content: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = TextView(this).apply { setPadding(32, 32, 32, 32) }
        setContentView(content)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action?.startsWith(NFC_ACTION_PREFIX) != true) {
            // Ignore the launcher intent; this screen only cares about NFC dispatch.
            render()
            return
        }
        val delivery = ReceivedTags.fromIntent(SOURCE, intent, Thread.currentThread().name)
        Log.i(
            TAG,
            "intent action=${delivery.action} data=${delivery.data} type=${delivery.type} " +
                "uid=${delivery.uid} records=${delivery.records}",
        )
        render()
    }

    private fun render() {
        val last = ReceivedTags.last
        content.text = buildString {
            appendLine("NFC Test Sample — intent")
            appendLine()
            if (last == null) {
                appendLine("Waiting for a tag...")
            } else {
                appendLine(format(last))
            }
        }
    }

    private companion object {
        const val TAG = "NfcTest"
        const val SOURCE = "intent"
        const val NFC_ACTION_PREFIX = "android.nfc.action."
    }
}
