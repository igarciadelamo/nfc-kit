package dev.nfctest

/**
 * Declarative description of a fake tag, independent of Android so it can be
 * parsed and validated in plain JVM unit tests.
 */
data class TagSpec(
    val uid: ByteArray,
    val readOnly: Boolean,
    val records: List<TagRecord>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TagSpec) return false
        return readOnly == other.readOnly &&
            uid.contentEquals(other.uid) &&
            records == other.records
    }

    override fun hashCode(): Int {
        var result = uid.contentHashCode()
        result = 31 * result + readOnly.hashCode()
        result = 31 * result + records.hashCode()
        return result
    }
}

/** One NDEF record of a [TagSpec]. Payloads stay base64-encoded until build time. */
sealed interface TagRecord {
    data class Uri(val value: String) : TagRecord

    data class Text(val lang: String, val text: String) : TagRecord

    data class Mime(val mime: String, val valueBase64: String) : TagRecord

    data class External(val domain: String, val typeName: String, val valueBase64: String) : TagRecord
}

/** Validation failure of a [TagSpec]; the receiver reports it as result code 2. */
class TagSpecException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
