package es.igarciadelamo.nfctest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Base64

/**
 * Entry point used by `adb shell am broadcast -a es.igarciadelamo.nfctest.TAP`.
 *
 * Debug-only component, protected by android.permission.DUMP in the manifest.
 */
class NfcTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val result = handle(context, intent)
        setResult(result.code, result.message, null)

        val line = "request mode=${intent.getStringExtra(EXTRA_MODE) ?: "auto"} " +
            "-> result=${result.code} data=\"${result.message}\""
        when {
            result.code == TagDispatcher.CODE_DELIVERED -> Log.i(NfcTestRuntime.LOG_TAG, line)
            result.code == TagDispatcher.CODE_UNEXPECTED ->
                Log.e(NfcTestRuntime.LOG_TAG, line, result.cause)
            else -> Log.w(NfcTestRuntime.LOG_TAG, line)
        }
    }

    private fun handle(context: Context, intent: Intent): Outcome {
        NfcTestRuntime.install(context)

        val mode = DeliveryMode.from(intent.getStringExtra(EXTRA_MODE))
            ?: return Outcome(
                TagDispatcher.CODE_UNEXPECTED,
                "unknown mode \"${intent.getStringExtra(EXTRA_MODE)}\" " +
                    "(expected auto, reader, foreground or implicit)",
            )

        val json = when {
            intent.hasExtra(EXTRA_TAG) -> intent.getStringExtra(EXTRA_TAG)
            intent.hasExtra(EXTRA_TAG_B64) -> decodeBase64(intent.getStringExtra(EXTRA_TAG_B64))
            else -> null
        } ?: return Outcome(
            TagDispatcher.CODE_INVALID_TAG,
            "missing tag extra (tag or tag_b64)",
        )

        val spec = try {
            TagSpecParser.parse(json)
        } catch (e: TagSpecException) {
            return Outcome(TagDispatcher.CODE_INVALID_TAG, e.message.orEmpty(), e)
        }

        val result = try {
            NfcTestRuntime.dispatcher.dispatch(spec, mode)
        } catch (t: Throwable) {
            return Outcome(
                TagDispatcher.CODE_UNEXPECTED,
                "${t.javaClass.name}: ${t.message}",
                t,
            )
        }
        return Outcome(result.code, result.message)
    }

    private fun decodeBase64(raw: String?): String? {
        if (raw == null) return null
        return try {
            String(Base64.getDecoder().decode(raw), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private class Outcome(
        val code: Int,
        val message: String,
        val cause: Throwable? = null,
    )

    companion object {
        const val ACTION_TAP = "es.igarciadelamo.nfctest.TAP"
        const val EXTRA_TAG = "tag"
        const val EXTRA_TAG_B64 = "tag_b64"
        const val EXTRA_MODE = "mode"
    }
}
