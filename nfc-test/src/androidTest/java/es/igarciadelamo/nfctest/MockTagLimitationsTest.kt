package es.igarciadelamo.nfctest

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.tech.Ndef
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Documents how the mock Tag fails when an app tries to talk to the NFC service.
 *
 * Every failure is recorded; the test itself never fails because the goal is to
 * know which exception type a future wrapper must translate.
 */
@RunWith(AndroidJUnit4::class)
class MockTagLimitationsTest {

    @Test
    fun recordMockTagLimitations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = JSONObject()
        report.put("sdkInt", Build.VERSION.SDK_INT)
        report.put("strategy", TagStrategy.PARCEL.name)

        val results = JSONArray()
        try {
            val message = NdefMessage(
                NdefRecord.createUri("https://example.com"),
                NdefRecord.createTextRecord("en", "hello"),
            )
            val tag = TagFactory.create(
                byteArrayOf(0x01, 0x02, 0x03, 0x04),
                message,
                strategy = TagStrategy.PARCEL,
            )
            val ndef = Ndef.get(tag)
            if (ndef == null) {
                report.put("fatal", "Ndef.get(tag) returned null")
            } else {
                results.put(attempt("connect") { ndef.connect() })
                results.put(valueOf("isConnected") { ndef.isConnected })
                results.put(attempt("readNdefMessage") { ndef.ndefMessage })
                results.put(attempt("writeNdefMessage") {
                    ndef.writeNdefMessage(NdefMessage(NdefRecord.createUri("https://example.com")))
                })
                results.put(attempt("close") { ndef.close() })
            }
        } catch (t: Throwable) {
            report.put("fatal", "${t.javaClass.name}: ${t.message}")
        }

        report.put("results", results)

        val file = File(context.filesDir, "limits-${Build.VERSION.SDK_INT}.json")
        try {
            file.writeText(report.toString(2))
            report.put("file", file.absolutePath)
        } catch (t: Throwable) {
            report.put("fileError", "${t.javaClass.name}: ${t.message}")
        }

        val text = report.toString(2)
        Log.i(TAG, text)
        println("$TAG $text")
    }

    private fun attempt(method: String, block: () -> Unit): JSONObject {
        val entry = JSONObject().put("method", method)
        try {
            block()
            entry.put("outcome", "returned normally")
        } catch (t: Throwable) {
            entry.put("outcome", "threw")
            entry.put("exception", t.javaClass.name)
            entry.put("message", t.message ?: "")
        }
        Log.i(TAG, "$method -> $entry")
        return entry
    }

    private fun valueOf(method: String, block: () -> Any?): JSONObject {
        val entry = JSONObject().put("method", method)
        try {
            entry.put("outcome", "returned")
            entry.put("value", block().toString())
        } catch (t: Throwable) {
            entry.put("outcome", "threw")
            entry.put("exception", t.javaClass.name)
            entry.put("message", t.message ?: "")
        }
        Log.i(TAG, "$method -> $entry")
        return entry
    }

    private companion object {
        const val TAG = "NfcTestLimits"
    }
}
