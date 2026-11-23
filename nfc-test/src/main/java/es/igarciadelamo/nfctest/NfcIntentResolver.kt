package es.igarciadelamo.nfctest

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag

/**
 * Resolves the platform's NDEF → TECH → TAG cascade against this app's own
 * intent filters and builds the Intent the platform would deliver.
 *
 * TECH_DISCOVERED is resolved by action only; the tech-list XML match is not
 * emulated (documented approximation).
 */
class NfcIntentResolver(private val context: Context) {

    data class Resolution(val action: String, val intent: Intent)

    /**
     * Returns the first action that resolves, or null when no filter matches.
     * The returned Intent is ready to be started by the caller; it carries the
     * same extras as the platform's discovered-tag Intent.
     */
    @Suppress("DEPRECATION")
    fun resolve(spec: TagSpec, tag: Tag): Resolution? {
        val base = ndefIntent(spec, tag)
        for (action in CASCADE) {
            val intent = Intent(base).apply {
                this.action = action
                setPackage(context.packageName)
                if (action != NfcAdapter.ACTION_NDEF_DISCOVERED) {
                    data = null
                    type = null
                }
            }
            val matches = context.packageManager.queryIntentActivities(intent, 0)
            if (matches.isNotEmpty()) return Resolution(action, intent)
        }
        return null
    }

    companion object {
        private val CASCADE = listOf(
            NfcAdapter.ACTION_NDEF_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED,
        )

        /** Mirrors the extras the platform puts on a discovered-tag Intent. */
        fun ndefIntent(spec: TagSpec, tag: Tag): Intent {
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
    }
}
