package dev.nfctest

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.util.Base64
import java.util.UUID

/**
 * Debug-only "trampoline" used to emulate a cold NFC dispatch.
 *
 * The adb shell starts this translucent Activity, which builds the Tag from the
 * JSON extra and starts the destination Activity with the NFC Intent already in
 * place. Starting from a visible Activity bypasses the Android 10+ background
 * activity launch restriction.
 *
 * Every run writes exactly one line to logcat:
 * `cold id=<id> result=<code> data="<message>"`.
 */
class NfcTestLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID) ?: UUID.randomUUID().toString()
        val outcome = handle()
        log(id, outcome)
        finishWithoutAnimation()
    }

    private fun handle(): Outcome {
        NfcTestRuntime.install(this)

        val json = when {
            intent.hasExtra(EXTRA_TAG) -> intent.getStringExtra(EXTRA_TAG)
            intent.hasExtra(EXTRA_TAG_B64) -> decodeBase64(intent.getStringExtra(EXTRA_TAG_B64))
            else -> null
        } ?: return Outcome(TagDispatcher.CODE_INVALID_TAG, "missing tag extra (tag or tag_b64)")

        val spec = try {
            TagSpecParser.parse(json)
        } catch (e: TagSpecException) {
            return Outcome(TagDispatcher.CODE_INVALID_TAG, e.message.orEmpty(), e)
        }

        val tag = try {
            spec.toTag()
        } catch (t: Throwable) {
            return Outcome(
                TagDispatcher.CODE_UNEXPECTED,
                "tag creation failed: ${t.javaClass.name}: ${t.message}",
                t,
            )
        }

        if (intent.getBooleanExtra(EXTRA_NO_DESTINATION, false)) {
            return Outcome(TagDispatcher.CODE_NO_DESTINATION, "no destination (debug flag)")
        }

        val resolution = NfcIntentResolver(this).resolve(spec, tag)
            ?: return Outcome(TagDispatcher.CODE_NO_DESTINATION, "no matching intent-filter")

        val destination = resolution.intent.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            startActivity(destination)
            Outcome(
                TagDispatcher.CODE_DELIVERED,
                "dispatched via cold implicit ${resolution.action.substringAfterLast('.')}",
            )
        } catch (e: ActivityNotFoundException) {
            Outcome(
                TagDispatcher.CODE_UNEXPECTED,
                "cold startActivity failed: ${e.javaClass.name}: ${e.message}",
                e,
            )
        } catch (e: SecurityException) {
            Outcome(
                TagDispatcher.CODE_UNEXPECTED,
                "cold startActivity failed: ${e.javaClass.name}: ${e.message}",
                e,
            )
        } catch (t: Throwable) {
            Outcome(
                TagDispatcher.CODE_UNEXPECTED,
                "cold startActivity failed: ${t.javaClass.name}: ${t.message}",
                t,
            )
        }
    }

    private fun log(id: String, outcome: Outcome) {
        val line = "cold id=$id result=${outcome.code} data=\"${outcome.message}\""
        when (outcome.code) {
            TagDispatcher.CODE_DELIVERED -> Log.i(NfcTestRuntime.LOG_TAG, line)
            TagDispatcher.CODE_UNEXPECTED -> Log.e(NfcTestRuntime.LOG_TAG, line, outcome.cause)
            else -> Log.w(NfcTestRuntime.LOG_TAG, line)
        }
    }

    private fun finishWithoutAnimation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
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
        const val EXTRA_TAG = "tag"
        const val EXTRA_TAG_B64 = "tag_b64"
        const val EXTRA_ID = "id"
        const val EXTRA_NO_DESTINATION = "no_destination"
    }
}
