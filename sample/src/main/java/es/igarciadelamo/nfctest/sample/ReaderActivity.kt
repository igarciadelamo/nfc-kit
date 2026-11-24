package es.igarciadelamo.nfctest.sample

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import es.igarciadelamo.nfcreader.NfcReader
import es.igarciadelamo.nfcreader.NfcReaders
import es.igarciadelamo.nfcreader.TagCallback

/**
 * Reads tags through [NfcReaders], i.e. through whatever reader the debug module
 * installed. Shows a colourful modal for every delivery.
 */
class ReaderActivity : Activity() {

    private lateinit var state: TextView
    private lateinit var details: TextView
    private lateinit var modal: TagModal

    private val reader: NfcReader get() = NfcReaders.get()

    private val callback = TagCallback { tag ->
        // Runs on the reader thread (binder-like), never on the main thread.
        val thread = Thread.currentThread().name
        val delivery = ReceivedTags.fromTag(SOURCE, tag, thread)
        ReceivedTags.addTagDelivery(delivery)
        Log.i(TAG, "reader callback on thread=$thread uid=${delivery.uid}")
        runOnUiThread {
            render(delivery)
            modal.show(delivery)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sample)
        findViewById<TextView>(R.id.header_title).setText(R.string.reader_title)
        findViewById<TextView>(R.id.header_subtitle).setText(R.string.reader_subtitle)
        state = findViewById(R.id.state)
        details = findViewById(R.id.received_info)
        modal = TagModal(findViewById(R.id.tag_modal))
        render(ReceivedTags.last)
    }

    override fun onResume() {
        super.onResume()
        val result = reader.enable(this, callback)
        Log.i(TAG, "enable -> $result")
        render(ReceivedTags.last)
    }

    override fun onPause() {
        reader.disable(this)
        Log.i(TAG, "disable")
        super.onPause()
    }

    private fun render(delivery: ReceivedTags.Delivery?) {
        if (delivery == null) {
            state.setText(R.string.state_waiting)
            state.setTextColor(getColor(R.color.nfc_text_muted))
            details.setText(R.string.delivery_hint)
            return
        }
        state.setText(R.string.state_received)
        state.setTextColor(getColor(R.color.nfc_success_dark))
        details.text = format(delivery)
    }

    private companion object {
        const val TAG = "NfcTest"
        const val SOURCE = "reader"
    }
}
