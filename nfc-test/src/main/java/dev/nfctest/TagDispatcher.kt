package dev.nfctest

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.util.Log

/** How a fake tag should reach the app. */
enum class DeliveryMode {
    AUTO,
    READER,
    FOREGROUND,
    IMPLICIT;

    companion object {
        fun from(raw: String?): DeliveryMode? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

/** Result reported back to `am broadcast` through [android.content.BroadcastReceiver.setResult]. */
data class DeliveryResult(val code: Int, val message: String)

/**
 * Delivers a fabricated Tag to the host app.
 *
 * The tag never leaves the app process: it is built here and handed over either
 * to the reader callback or to an Intent.
 */
class TagDispatcher(
    private val context: Context,
    private val tracker: ResumedActivityTracker,
    private val reader: InjectableNfcReader,
) {

    fun dispatch(spec: TagSpec, mode: DeliveryMode): DeliveryResult {
        val tag = try {
            spec.toTag()
        } catch (t: Throwable) {
            return DeliveryResult(
                CODE_UNEXPECTED,
                "tag creation failed: ${t.javaClass.name}: ${t.message}",
            )
        }

        val effective = when (mode) {
            DeliveryMode.AUTO -> when {
                reader.isActive -> DeliveryMode.READER
                tracker.resumed != null -> DeliveryMode.FOREGROUND
                else -> DeliveryMode.IMPLICIT
            }
            else -> mode
        }
        Log.i(NfcTestRuntime.LOG_TAG, "dispatching tag, requested=$mode effective=$effective")

        return when (effective) {
            DeliveryMode.READER -> deliverToReader(tag)
            DeliveryMode.FOREGROUND -> deliverToForeground(spec, tag)
            DeliveryMode.IMPLICIT -> deliverImplicit(spec, tag)
            DeliveryMode.AUTO -> error("AUTO must be resolved before dispatching")
        }
    }

    private fun deliverToReader(tag: Tag): DeliveryResult =
        if (reader.deliver(tag)) {
            DeliveryResult(CODE_DELIVERED, "delivered via reader")
        } else {
            DeliveryResult(CODE_NO_READER, "no active reader mode")
        }

    private fun deliverToForeground(spec: TagSpec, tag: Tag): DeliveryResult {
        val activity = tracker.resumed
            ?: return DeliveryResult(CODE_NO_DESTINATION, "no foreground activity")

        val intent = ndefIntent(spec, tag).apply {
            setClass(activity, activity.javaClass)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return try {
            activity.startActivity(intent)
            DeliveryResult(CODE_DELIVERED, "delivered via foreground intent")
        } catch (t: Throwable) {
            DeliveryResult(
                CODE_UNEXPECTED,
                "foreground startActivity failed: ${t.javaClass.name}: ${t.message}",
            )
        }
    }

    /**
     * Emulates the platform cascade against this app's own intent filters:
     * NDEF_DISCOVERED, then TECH_DISCOVERED, then TAG_DISCOVERED.
     *
     * TECH_DISCOVERED is resolved by action only; the tech-list XML match is not
     * emulated (documented approximation).
     */
    @Suppress("DEPRECATION")
    private fun deliverImplicit(spec: TagSpec, tag: Tag): DeliveryResult {
        val base = ndefIntent(spec, tag)
        val cascade = listOf(
            NfcAdapter.ACTION_NDEF_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED,
        )

        for (action in cascade) {
            val intent = Intent(base).apply {
                this.action = action
                setPackage(context.packageName)
                if (action != NfcAdapter.ACTION_NDEF_DISCOVERED) {
                    data = null
                    type = null
                }
            }
            val matches = context.packageManager.queryIntentActivities(intent, 0)
            if (matches.isEmpty()) continue

            val activity = tracker.resumed
            return try {
                if (activity != null) {
                    activity.startActivity(intent)
                } else {
                    // Background activity starts are blocked on Android 10+; this
                    // branch only exists for completeness.
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                DeliveryResult(CODE_DELIVERED, "delivered via implicit ${action.substringAfterLast('.')}")
            } catch (t: Throwable) {
                return DeliveryResult(
                    CODE_UNEXPECTED,
                    "implicit startActivity failed: ${t.javaClass.name}: ${t.message}",
                )
            }
        }
        return DeliveryResult(CODE_NO_DESTINATION, "no foreground activity or matching intent-filter")
    }

    /** Mirrors the extras the platform puts on a discovered-tag Intent. */
    private fun ndefIntent(spec: TagSpec, tag: Tag): Intent {
        val intent = Intent(NfcAdapter.ACTION_NDEF_DISCOVERED)
        intent.putExtra(NfcAdapter.EXTRA_TAG, tag)
        intent.putExtra(NfcAdapter.EXTRA_ID, tag.id)
        intent.putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(spec.toNdefMessage()))
        when (val first = spec.records.first()) {
            is TagRecord.Uri -> intent.data = Uri.parse(first.value)
            is TagRecord.Mime -> intent.type = first.mime
            else -> Unit
        }
        return intent
    }

    companion object {
        const val CODE_DELIVERED = 1
        const val CODE_INVALID_TAG = 2
        const val CODE_NO_DESTINATION = 3
        const val CODE_NO_READER = 4
        const val CODE_UNEXPECTED = 5
    }
}
