package es.igarciadelamo.nfctest

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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

    private val resolver = NfcIntentResolver(context)

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
            DeliveryResult(CODE_DELIVERED, "dispatched via reader")
        } else {
            DeliveryResult(CODE_NO_READER, "no active reader mode")
        }

    private fun deliverToForeground(spec: TagSpec, tag: Tag): DeliveryResult {
        val activity = tracker.resumed
            ?: return DeliveryResult(CODE_NO_DESTINATION, "no foreground activity")

        val intent = NfcIntentResolver.ndefIntent(spec, tag).apply {
            setClass(activity, activity.javaClass)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return try {
            activity.startActivity(intent)
            DeliveryResult(CODE_DELIVERED, "dispatched via foreground intent")
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
     * Needs a resumed Activity: background activity starts are blocked on
     * Android 10+, so without one this returns [CODE_NO_DESTINATION].
     */
    private fun deliverImplicit(spec: TagSpec, tag: Tag): DeliveryResult {
        val resolution = resolver.resolve(spec, tag)
            ?: return DeliveryResult(CODE_NO_DESTINATION, "no matching intent-filter")

        // Android 10+ blocks background activity starts silently. Starting
        // from the application context would report success without opening
        // anything, so require a resumed Activity instead.
        val activity = tracker.resumed
            ?: return DeliveryResult(
                CODE_NO_DESTINATION,
                "app in background: implicit needs a foreground activity",
            )

        return try {
            activity.startActivity(resolution.intent)
            DeliveryResult(
                CODE_DELIVERED,
                "dispatched via implicit ${resolution.action.substringAfterLast('.')}",
            )
        } catch (e: ActivityNotFoundException) {
            DeliveryResult(
                CODE_UNEXPECTED,
                "implicit startActivity failed: ${e.javaClass.name}: ${e.message}",
            )
        } catch (e: SecurityException) {
            DeliveryResult(
                CODE_UNEXPECTED,
                "implicit startActivity failed: ${e.javaClass.name}: ${e.message}",
            )
        }
    }

    companion object {
        const val CODE_DELIVERED = 1
        const val CODE_INVALID_TAG = 2
        const val CODE_NO_DESTINATION = 3
        const val CODE_NO_READER = 4
        const val CODE_UNEXPECTED = 5
    }
}
