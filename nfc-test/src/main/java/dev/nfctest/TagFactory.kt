package dev.nfctest

import android.nfc.NdefMessage
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.util.Log
import dev.nfctest.internal.ParcelLayout
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/**
 * Builds an [android.nfc.Tag] that behaves like a tag discovered by the platform.
 *
 * No NFC hardware or NFC service is involved: the Tag is either decoded from a
 * hand-written Parcel or produced by the hidden `Tag.createMockTag` factory.
 */
object TagFactory {

    // TagTechnology.NFC_A / TagTechnology.NDEF are public API, but their constants
    // are @hide. Values inlined from AOSP android.nfc.tech.TagTechnology
    // (unchanged in API 30 through 36).
    private const val TECH_NFC_A = 1
    private const val TECH_NDEF = 6

    // @hide keys from android.nfc.tech.Ndef.
    private const val EXTRA_NDEF_MSG = "ndefmsg"
    private const val EXTRA_NDEF_MAXLENGTH = "ndefmaxlength"
    private const val EXTRA_NDEF_CARDSTATE = "ndefcardstate"
    private const val EXTRA_NDEF_TYPE = "ndeftype"

    // @hide keys from android.nfc.tech.NfcA.
    private const val EXTRA_SAK = "sak"
    private const val EXTRA_ATQA = "atqa"

    // @hide values from android.nfc.tech.Ndef.
    private const val NDEF_CARDSTATE_READ_ONLY = 1
    private const val NDEF_CARDSTATE_READ_WRITE = 2
    private const val NDEF_TYPE_2 = 2

    private const val NDEF_MAXLENGTH_MARGIN = 64
    private val VALID_UID_LENGTHS = setOf(4, 7, 10)

    private val SAK: Short = 0x00
    private val ATQA = byteArrayOf(0x44, 0x00)

    /**
     * Creates a fake tag carrying [ndef].
     *
     * @throws IllegalArgumentException if the UID length or the NDEF message is invalid.
     * @throws IllegalStateException if [TagStrategy.AUTO] cannot produce a valid tag.
     */
    @JvmStatic
    fun create(
        uid: ByteArray,
        ndef: NdefMessage,
        readOnly: Boolean = false,
        strategy: TagStrategy = TagStrategy.AUTO,
    ): Tag {
        require(uid.size in VALID_UID_LENGTHS) {
            "uid must be 4, 7 or 10 bytes, got ${uid.size}"
        }
        require(ndef.records.isNotEmpty()) { "NDEF message must contain at least one record" }
        require(ndef.toByteArray().isNotEmpty()) { "NDEF message must not be empty" }
        return createInternal(uid, ndef, readOnly, strategy)
    }

    /**
     * Test-only variant for the "NfcA without Ndef" edge case.
     * Kept internal so the public API stays focused on NDEF tags.
     */
    internal fun createNfcAOnly(uid: ByteArray, strategy: TagStrategy = TagStrategy.AUTO): Tag {
        require(uid.size in VALID_UID_LENGTHS) {
            "uid must be 4, 7 or 10 bytes, got ${uid.size}"
        }
        return createInternal(uid, null, false, strategy)
    }

    private fun createInternal(
        uid: ByteArray,
        ndef: NdefMessage?,
        readOnly: Boolean,
        strategy: TagStrategy,
    ): Tag = when (strategy) {
        TagStrategy.PARCEL -> createWithParcel(uid, ndef, readOnly)
        TagStrategy.REFLECTION -> createWithReflection(uid, ndef, readOnly)
        TagStrategy.AUTO -> createAuto(uid, ndef, readOnly)
    }

    private fun createAuto(uid: ByteArray, ndef: NdefMessage?, readOnly: Boolean): Tag {
        val failures = mutableListOf<String>()
        for (strategy in listOf(TagStrategy.PARCEL, TagStrategy.REFLECTION)) {
            try {
                val tag = when (strategy) {
                    TagStrategy.PARCEL -> createWithParcel(uid, ndef, readOnly)
                    TagStrategy.REFLECTION -> createWithReflection(uid, ndef, readOnly)
                    TagStrategy.AUTO -> error("AUTO cannot recurse")
                }
                val problem = selfCheck(tag, uid, ndef, readOnly)
                if (problem == null) return tag
                failures += "$strategy failed self-check: $problem"
            } catch (t: Exception) {
                failures += "$strategy threw ${t.javaClass.name}: ${t.message}"
            }
        }
        throw IllegalStateException(
            "Could not create a valid mock Tag on SDK ${Build.VERSION.SDK_INT}: " +
                failures.joinToString("; "),
        )
    }

    private fun createWithParcel(uid: ByteArray, ndef: NdefMessage?, readOnly: Boolean): Tag {
        val techCodes = techCodesFor(ndef)
        val extras = techExtrasFor(ndef, readOnly)
        val parcel = Parcel.obtain()
        try {
            ParcelLayout.forSdkInt(Build.VERSION.SDK_INT)
                .writeToParcel(parcel, uid, techCodes, extras)
            parcel.setDataPosition(0)
            return Tag.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun createWithReflection(uid: ByteArray, ndef: NdefMessage?, readOnly: Boolean): Tag {
        val techCodes = techCodesFor(ndef)
        val extras = techExtrasFor(ndef, readOnly)

        val candidates = Tag::class.java.declaredMethods.filter {
            it.name == "createMockTag" && Modifier.isStatic(it.modifiers)
        }
        val signatures = candidates.joinToString { method ->
            method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
        }
        Log.d(TAG_DIAG, "Tag.createMockTag signatures on SDK ${Build.VERSION.SDK_INT}: $signatures")

        val method = candidates.firstOrNull { it.parameterTypes.size == 4 }
            ?: candidates.firstOrNull { it.parameterTypes.size == 3 }
            ?: throw IllegalStateException(
                "Tag.createMockTag not found on SDK ${Build.VERSION.SDK_INT}; " +
                    "getDeclaredMethods() returned ${candidates.size} candidates ($signatures). " +
                    "An empty list usually means the hidden API policy is filtering it; " +
                    "try: adb shell settings put global hidden_api_policy 1",
            )

        val args: Array<out Any> = if (method.parameterTypes.size == 4) {
            arrayOf<Any>(uid, techCodes, extras, 0L) // API 34+: cookie
        } else {
            arrayOf<Any>(uid, techCodes, extras) // API 30-33
        }

        try {
            return method.invoke(null, *args) as Tag
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            throw IllegalStateException(
                "Tag.createMockTag invocation failed on SDK ${Build.VERSION.SDK_INT} " +
                    "(hidden API restriction?): ${cause.javaClass.name}: ${cause.message}",
                cause,
            )
        } catch (e: Exception) {
            throw IllegalStateException(
                "Tag.createMockTag reflection failed on SDK ${Build.VERSION.SDK_INT} " +
                    "(hidden API restriction?): ${e.javaClass.name}: ${e.message}",
                e,
            )
        }
    }

    /** Returns null when the tag is valid, or a human-readable problem otherwise. */
    private fun selfCheck(
        tag: Tag,
        uid: ByteArray,
        ndef: NdefMessage?,
        readOnly: Boolean,
    ): String? {
        if (!tag.id.contentEquals(uid)) return "id does not match"
        val techList = tag.techList.toSet()
        if (NfcA::class.java.name !in techList) return "NfcA missing from tech list"

        val nfcA = NfcA.get(tag) ?: return "NfcA.get(tag) returned null"
        if (nfcA.sak != SAK) return "NfcA.sak=${nfcA.sak}, expected $SAK"
        if (!nfcA.atqa.contentEquals(ATQA)) return "NfcA.atqa does not match"

        if (ndef == null) {
            if (Ndef::class.java.name in techList) return "Ndef present in a NfcA-only tag"
            if (Ndef.get(tag) != null) return "Ndef.get(tag) is not null for a NfcA-only tag"
            return null
        }

        if (Ndef::class.java.name !in techList) return "Ndef missing from tech list"
        val ndefTech = Ndef.get(tag) ?: return "Ndef.get(tag) returned null"
        val cached = ndefTech.cachedNdefMessage ?: return "cachedNdefMessage is null"
        if (!cached.toByteArray().contentEquals(ndef.toByteArray())) {
            return "cachedNdefMessage does not match the original message"
        }
        val size = ndef.toByteArray().size
        if (ndefTech.maxSize < size) return "maxSize=${ndefTech.maxSize} < message size=$size"
        if (ndefTech.isWritable == readOnly) {
            return "isWritable=${ndefTech.isWritable} while readOnly=$readOnly"
        }
        if (ndefTech.type != Ndef.NFC_FORUM_TYPE_2) {
            return "type=${ndefTech.type}, expected ${Ndef.NFC_FORUM_TYPE_2}"
        }
        return null
    }

    internal fun techCodesFor(ndef: NdefMessage?): IntArray =
        if (ndef == null) intArrayOf(TECH_NFC_A) else intArrayOf(TECH_NFC_A, TECH_NDEF)

    internal fun techExtrasFor(ndef: NdefMessage?, readOnly: Boolean): Array<Bundle> {
        val nfcA = Bundle().apply {
            putShort(EXTRA_SAK, SAK)
            putByteArray(EXTRA_ATQA, ATQA)
        }
        if (ndef == null) return arrayOf(nfcA)

        val messageBytes = ndef.toByteArray()
        val ndefExtras = Bundle().apply {
            putParcelable(EXTRA_NDEF_MSG, ndef)
            putInt(EXTRA_NDEF_MAXLENGTH, messageBytes.size + NDEF_MAXLENGTH_MARGIN)
            putInt(
                EXTRA_NDEF_CARDSTATE,
                if (readOnly) NDEF_CARDSTATE_READ_ONLY else NDEF_CARDSTATE_READ_WRITE,
            )
            putInt(EXTRA_NDEF_TYPE, NDEF_TYPE_2)
        }
        return arrayOf(nfcA, ndefExtras)
    }

    private const val TAG_DIAG = "NfcTestDiag"
}
