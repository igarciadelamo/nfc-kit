package es.igarciadelamo.nfcreader

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag

/** Receives tags discovered by an [NfcReader]. */
fun interface TagCallback {
    fun onTagDiscovered(tag: Tag)
}

/** Outcome of [NfcReader.enable]. */
enum class EnableResult {
    ENABLED,
    NO_ADAPTER,
    NFC_DISABLED,
}

/**
 * Minimal NFC reader-mode facade.
 *
 * Production code depends on this interface only; the debug-only `:nfc-test`
 * module swaps [NfcReaders.factory] for an implementation that can also inject
 * fake tags.
 */
interface NfcReader {
    fun enable(
        activity: Activity,
        callback: TagCallback,
        flags: Int = DEFAULT_FLAGS,
    ): EnableResult

    fun disable(activity: Activity)

    companion object {
        const val DEFAULT_FLAGS: Int =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
    }
}

/**
 * Wraps the platform reader mode.
 *
 * The adapter is looked up lazily on every call, so constructing this class is
 * safe on the JVM (used by the default-factory unit test).
 */
class RealNfcReader : NfcReader {

    override fun enable(activity: Activity, callback: TagCallback, flags: Int): EnableResult {
        val adapter = defaultAdapter(activity) ?: return EnableResult.NO_ADAPTER
        if (!adapter.isEnabled) return EnableResult.NFC_DISABLED
        adapter.enableReaderMode(activity, { tag -> callback.onTagDiscovered(tag) }, flags, null)
        return EnableResult.ENABLED
    }

    override fun disable(activity: Activity) {
        defaultAdapter(activity)?.disableReaderMode(activity)
    }

    private fun defaultAdapter(activity: Activity): NfcAdapter? =
        activity.getSystemService(NfcManager::class.java)?.defaultAdapter
}

/** Entry point used by production code. */
object NfcReaders {
    @Volatile
    var factory: () -> NfcReader = { RealNfcReader() }

    fun get(): NfcReader = factory()
}
