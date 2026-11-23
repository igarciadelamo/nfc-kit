package es.igarciadelamo.nfctest

import java.net.URI
import java.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses and validates the JSON tag specification sent through adb.
 *
 * Deliberately free of Android APIs so the validation logic is covered by fast
 * JVM unit tests; `TagSpecNdef.kt` turns the result into an NdefMessage.
 */
object TagSpecParser {

    /** Upper bound for the encoded message, to keep adb payloads sane. */
    const val MAX_MESSAGE_BYTES = 16 * 1024

    private val UID_PATTERN = Regex("^[0-9a-fA-F]+$")
    private val VALID_UID_BYTES = setOf(4, 7, 10)

    fun parse(json: String): TagSpec {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw TagSpecException("invalid JSON: ${e.message}", e)
        }

        val uid = parseUid(root.optString("uid").takeIf { it.isNotEmpty() })
        val readOnly = root.optBoolean("readOnly", false)
        val records = parseRecords(root.optJSONArray("records") ?: throw TagSpecException(
            "records is required and must be a non-empty array",
        ))

        val spec = TagSpec(uid, readOnly, records)
        val estimated = records.sumOf { it.estimatedSize() }
        if (estimated > MAX_MESSAGE_BYTES) {
            throw TagSpecException(
                "message too large: $estimated bytes (max $MAX_MESSAGE_BYTES)",
            )
        }
        return spec
    }

    private fun parseUid(raw: String?): ByteArray {
        if (raw == null) throw TagSpecException("uid is required")
        val hex = raw.trim()
        if (!UID_PATTERN.matches(hex)) {
            throw TagSpecException("uid must be hexadecimal, got \"$raw\"")
        }
        if (hex.length % 2 != 0) {
            throw TagSpecException("uid must have an even number of hex chars, got ${hex.length}")
        }
        val bytes = ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        if (bytes.size !in VALID_UID_BYTES) {
            throw TagSpecException(
                "uid must be 4, 7 or 10 bytes (8, 14 or 20 hex chars), got ${bytes.size} bytes",
            )
        }
        return bytes
    }

    private fun parseRecords(array: JSONArray): List<TagRecord> {
        if (array.length() == 0) throw TagSpecException("records must contain at least one record")
        return (0 until array.length()).map { index ->
            val record = array.optJSONObject(index)
                ?: throw TagSpecException("record $index must be a JSON object")
            parseRecord(index, record)
        }
    }

    private fun parseRecord(index: Int, record: JSONObject): TagRecord {
        val prefix = "record $index"
        return when (val type = record.optString("type", "")) {
            "uri" -> {
                val value = requireString(record, "value", prefix)
                val uri = try {
                    URI(value)
                } catch (e: Exception) {
                    throw TagSpecException("$prefix: value is not a valid URI: $value", e)
                }
                if (!uri.isAbsolute) {
                    throw TagSpecException("$prefix: value must be an absolute URI, got $value")
                }
                TagRecord.Uri(value)
            }

            "text" -> TagRecord.Text(
                lang = record.optString("lang", "en").ifEmpty {
                    throw TagSpecException("$prefix: lang must not be empty")
                },
                text = requireString(record, "value", prefix),
            )

            "mime" -> {
                val mime = requireString(record, "mime", prefix)
                if (!mime.contains('/')) {
                    throw TagSpecException("$prefix: mime must look like type/subtype, got $mime")
                }
                TagRecord.Mime(mime, requireBase64(record, prefix))
            }

            "external" -> TagRecord.External(
                domain = requireString(record, "domain", prefix),
                typeName = requireString(record, "typeName", prefix),
                valueBase64 = requireBase64(record, prefix),
            )

            else -> throw TagSpecException(
                "$prefix: unknown type \"$type\" (expected uri, text, mime or external)",
            )
        }
    }

    private fun requireString(record: JSONObject, key: String, prefix: String): String {
        val value = record.optString(key, "")
        if (value.isEmpty()) throw TagSpecException("$prefix: $key is required")
        return value
    }

    private fun requireBase64(record: JSONObject, prefix: String): String {
        val value = requireString(record, "valueBase64", prefix)
        try {
            Base64.getDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            throw TagSpecException("$prefix: valueBase64 is not valid base64", e)
        }
        return value
    }

    /** Rough encoded size: header plus type plus payload. */
    private fun TagRecord.estimatedSize(): Int = when (this) {
        is TagRecord.Uri -> 5 + 1 + value.toByteArray().size
        is TagRecord.Text -> 5 + 1 + 1 + lang.toByteArray().size + text.toByteArray().size
        is TagRecord.Mime -> 5 + mime.toByteArray().size + decodedLength(valueBase64)
        is TagRecord.External ->
            5 + "$domain:$typeName".toByteArray().size + decodedLength(valueBase64)
    }

    private fun decodedLength(valueBase64: String): Int =
        Base64.getDecoder().decode(valueBase64).size
}
