package es.igarciadelamo.nfctest

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import java.util.Base64

/** Builds the real NDEF payload for a [TagSpec]. */
fun TagSpec.toNdefMessage(): NdefMessage =
    NdefMessage(records.map { it.toNdefRecord() }.toTypedArray())

private fun TagRecord.toNdefRecord(): NdefRecord = when (this) {
    is TagRecord.Uri -> NdefRecord.createUri(value)
    is TagRecord.Text -> NdefRecord.createTextRecord(lang, text)
    is TagRecord.Mime -> NdefRecord.createMime(mime, decode(valueBase64))
    is TagRecord.External -> NdefRecord.createExternal(domain, typeName, decode(valueBase64))
}

private fun decode(valueBase64: String): ByteArray = Base64.getDecoder().decode(valueBase64)

/** Fabricates the tag, using the cheapest strategy that self-checks. */
fun TagSpec.toTag(): Tag = TagFactory.create(
    uid = uid,
    ndef = toNdefMessage(),
    readOnly = readOnly,
    strategy = TagStrategy.AUTO,
)
