package dev.nfctest.sample

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory record of every delivery, so the instrumented tests can assert what
 * the app actually received. Production code only reads from it.
 */
object ReceivedTags {

    data class Delivery(
        val source: String,
        val action: String?,
        val data: String?,
        val type: String?,
        val uid: String,
        val records: List<String>,
        val thread: String,
        val timestamp: Long,
        val path: String? = null,
    )

    private val entries = CopyOnWriteArrayList<Delivery>()

    val all: List<Delivery>
        get() = entries.toList()

    val last: Delivery?
        get() = entries.lastOrNull()

    fun clear() {
        entries.clear()
    }

    fun fromTag(source: String, tag: Tag, thread: String): Delivery {
        val message = Ndef.get(tag)?.cachedNdefMessage
        return Delivery(
            source = source,
            action = null,
            data = null,
            type = null,
            uid = tag.id.toHex(),
            records = message?.records?.map(NdefSummary::describe).orEmpty(),
            thread = thread,
            timestamp = System.currentTimeMillis(),
        )
    }

    fun fromIntent(
        source: String,
        intent: Intent,
        thread: String,
        path: String? = null,
    ): Delivery {
        val tag = intent.tagExtra()
        val messages = intent.ndefMessages()
        val message = messages?.firstOrNull() ?: tag?.let { Ndef.get(it)?.cachedNdefMessage }
        val uid = intent.getByteArrayExtra(NfcAdapter.EXTRA_ID) ?: tag?.id
        val delivery = Delivery(
            source = source,
            action = intent.action,
            data = intent.dataString,
            type = intent.type,
            uid = uid?.toHex().orEmpty(),
            records = message?.records?.map(NdefSummary::describe).orEmpty(),
            thread = thread,
            timestamp = System.currentTimeMillis(),
            path = path,
        )
        entries.add(delivery)
        return delivery
    }

    fun addTagDelivery(delivery: Delivery) {
        entries.add(delivery)
    }
}

/** Safe across API 30-36: the typed overload only exists from API 33. */
@Suppress("DEPRECATION")
fun Intent.ndefMessages(): Array<NdefMessage>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
    } else {
        // The platform returns a Parcelable[] whose runtime type is not NdefMessage[].
        getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            ?.filterIsInstance<NdefMessage>()
            ?.toTypedArray()
    }

@Suppress("DEPRECATION")
fun Intent.tagExtra(): Tag? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
    } else {
        getParcelableExtra(NfcAdapter.EXTRA_TAG)
    }

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun format(delivery: ReceivedTags.Delivery): String = buildString {
    appendLine("source: ${delivery.source}")
    delivery.path?.let { appendLine("path: $it") }
    appendLine("thread: ${delivery.thread}")
    appendLine("action: ${delivery.action}")
    appendLine("data: ${delivery.data}")
    appendLine("type: ${delivery.type}")
    appendLine("uid: ${delivery.uid}")
    delivery.records.forEach { appendLine("record: $it") }
}

/** Human-readable "type: value" lines for the UI and the tests. */
object NdefSummary {

    fun describe(record: NdefRecord): String {
        val tnf = record.tnf
        return when {
            tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals(NdefRecord.RTD_URI) ->
                "uri: ${record.toUri()}"

            tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals(NdefRecord.RTD_TEXT) ->
                "text: ${textValue(record)}"

            tnf == NdefRecord.TNF_MIME_MEDIA ->
                "mime ${record.toMimeType()}: ${String(record.payload, Charsets.UTF_8)}"

            tnf == NdefRecord.TNF_EXTERNAL_TYPE ->
                "external ${String(record.type)}: ${record.payload.size} bytes"

            else ->
                "tnf=$tnf type=${record.type.toHex()}: ${record.payload.size} bytes"
        }
    }

    private fun textValue(record: NdefRecord): String {
        val payload = record.payload
        if (payload.isEmpty()) return ""
        val status = payload[0].toInt()
        val langLength = status and 0x3F
        val textStart = 1 + langLength
        if (textStart > payload.size) return ""
        val charset = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
        return String(payload, textStart, payload.size - textStart, charset)
    }
}
