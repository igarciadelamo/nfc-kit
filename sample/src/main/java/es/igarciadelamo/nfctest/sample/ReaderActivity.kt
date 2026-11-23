package es.igarciadelamo.nfctest.sample

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import es.igarciadelamo.nfctest.api.NfcReader
import es.igarciadelamo.nfctest.api.NfcReaders
import es.igarciadelamo.nfctest.api.TagCallback

/**
 * Reads tags through [NfcReaders], i.e. through whatever reader the debug module
 * installed. The UI is intentionally minimal.
 */
class ReaderActivity : Activity() {

    private lateinit var content: TextView
    private val reader: NfcReader get() = NfcReaders.get()

    private val callback = TagCallback { tag ->
        // Runs on the reader thread (binder-like), never on the main thread.
        val thread = Thread.currentThread().name
        val delivery = ReceivedTags.fromTag(SOURCE, tag, thread)
        ReceivedTags.addTagDelivery(delivery)
        Log.i(TAG, "reader callback on thread=$thread uid=${delivery.uid}")
        runOnUiThread { render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = TextView(this).apply { setPadding(32, 32 + statusBarHeight(), 32, 32) }
        setContentView(content)
        render()
    }

    override fun onResume() {
        super.onResume()
        val result = reader.enable(this, callback)
        Log.i(TAG, "enable -> $result")
        render()
    }

    override fun onPause() {
        reader.disable(this)
        Log.i(TAG, "disable")
        super.onPause()
    }

    private fun render() {
        val last = ReceivedTags.last
        content.text = buildString {
            appendLine("NFC Test Sample — reader mode")
            appendLine()
            if (last == null) {
                appendLine("Waiting for a tag...")
            } else {
                appendLine(format(last))
            }
        }
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private companion object {
        const val TAG = "NfcTest"
        const val SOURCE = "reader"
    }
}
