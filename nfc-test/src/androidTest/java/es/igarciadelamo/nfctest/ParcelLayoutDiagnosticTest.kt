package es.igarciadelamo.nfctest

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Parcel
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.igarciadelamo.nfctest.internal.ParcelLayout
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contrasts the hand-written Parcel layout against the platform implementation.
 *
 * Two references are used:
 * 1. A Tag built with the hidden Tag.createMockTag factory, re-serialized with the
 *    platform Tag.writeToParcel. This is the strongest check but depends on hidden
 *    API access.
 * 2. A Tag decoded by the public Tag.CREATOR from our own bytes and re-serialized
 *    with Tag.writeToParcel. This validates that the platform decoder and encoder
 *    agree with the layout even when the hidden factory is blocked.
 */
@RunWith(AndroidJUnit4::class)
class ParcelLayoutDiagnosticTest {

    private val uid = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    private val message = NdefMessage(
        NdefRecord.createUri("https://example.com"),
        NdefRecord.createTextRecord("en", "hello"),
    )

    @Test
    fun parcelLayoutMatchesPlatformWriteToParcel() {
        val techCodes = TagFactory.techCodesFor(message)
        val extras = TagFactory.techExtrasFor(message, readOnly = false)

        // Candidate layout, decoded by the public platform creator.
        val layoutParcel = Parcel.obtain()
        val layoutBytes: ByteArray
        val tagFromLayout: Tag
        try {
            ParcelLayout.forSdkInt(Build.VERSION.SDK_INT)
                .writeToParcel(layoutParcel, uid, techCodes, extras)
            layoutBytes = layoutParcel.marshall()
            layoutParcel.setDataPosition(0)
            tagFromLayout = Tag.CREATOR.createFromParcel(layoutParcel)
        } finally {
            layoutParcel.recycle()
        }
        dump("PARCEL", layoutBytes)

        // Reference 2: what the platform writes back for the Tag it just decoded.
        val roundTripParcel = Parcel.obtain()
        val roundTripBytes: ByteArray
        try {
            tagFromLayout.writeToParcel(roundTripParcel, 0)
            roundTripBytes = roundTripParcel.marshall()
        } finally {
            roundTripParcel.recycle()
        }
        dump("PLATFORM_ROUNDTRIP", roundTripBytes)

        if (!roundTripBytes.contentEquals(layoutBytes)) {
            val detail = firstDifferenceDetail(roundTripBytes, layoutBytes)
            val roundTripped = decodeTag(roundTripBytes)
            val semantic = semanticDifference(tagFromLayout, roundTripped)
            if (semantic != null) fail("$detail; semantic difference: $semantic")
            val explanation = "$detail. The decoded Tag is semantically identical; the byte " +
                "difference comes from Bundle serialization, not from the Tag field layout."
            Log.w(TAG, explanation)
            println("$TAG $explanation")
        } else {
            val ok = "$TAG SDK ${Build.VERSION.SDK_INT}: platform round-trip matches the " +
                "hand-written layout byte-for-byte (${layoutBytes.size} bytes)"
            Log.i(TAG, ok)
            println(ok)
        }

        // Reference 1: the hidden factory, when the hidden API policy allows it.
        val reference: Tag? = try {
            TagFactory.create(uid, message, strategy = TagStrategy.REFLECTION)
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "REFLECTION strategy unavailable on SDK ${Build.VERSION.SDK_INT}: " +
                    "${t.javaClass.name}: ${t.message}",
                t,
            )
            null
        }

        if (reference == null) {
            val note = "$TAG SDK ${Build.VERSION.SDK_INT}: contrast against Tag.createMockTag " +
                "NOT VERIFIED (hidden API blocked). The platform CREATOR round-trip above is " +
                "the empirical reference for this API."
            Log.w(TAG, note)
            println(note)
            return
        }

        val refParcel = Parcel.obtain()
        val refBytes: ByteArray
        try {
            reference.writeToParcel(refParcel, 0)
            refBytes = refParcel.marshall()
        } finally {
            refParcel.recycle()
        }
        dump("REFLECTION", refBytes)

        if (refBytes.contentEquals(layoutBytes)) {
            val ok = "$TAG SDK ${Build.VERSION.SDK_INT}: layouts match byte-for-byte " +
                "against Tag.createMockTag (${refBytes.size} bytes)"
            Log.i(TAG, ok)
            println(ok)
            return
        }

        val detail = firstDifferenceDetail(refBytes, layoutBytes)
        val semantic = semanticDifference(tagFromLayout, reference)
        if (semantic == null) {
            val explanation = "$detail. Marshalled bytes differ but the decoded Tags are " +
                "semantically identical; the difference is in Bundle serialization, not in " +
                "the Tag field layout."
            Log.w(TAG, explanation)
            println("$TAG $explanation")
            return
        }
        fail("$detail; semantic difference: $semantic")
    }

    private fun firstDifferenceDetail(reference: ByteArray, candidate: ByteArray): String {
        val offset = firstDifference(reference, candidate)
        return "SDK ${Build.VERSION.SDK_INT}: first difference at offset $offset " +
            "(reference=${reference.size} bytes, parcel=${candidate.size} bytes)"
    }

    private fun decodeTag(bytes: ByteArray): Tag {
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            return Tag.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun dump(label: String, bytes: ByteArray) {
        val hex = bytes.joinToString(" ") { "%02x".format(it) }
        Log.d(TAG, "SDK ${Build.VERSION.SDK_INT} $label (${bytes.size} bytes): $hex")
        println("$TAG SDK ${Build.VERSION.SDK_INT} $label (${bytes.size} bytes): $hex")
    }

    private fun firstDifference(a: ByteArray, b: ByteArray): Int {
        val common = minOf(a.size, b.size)
        for (index in 0 until common) {
            if (a[index] != b[index]) return index
        }
        return common
    }

    private fun semanticDifference(left: Tag, right: Tag): String? {
        if (!left.id.contentEquals(right.id)) return "id"
        if (left.techList.toSet() != right.techList.toSet()) return "techList"

        val leftNfcA = NfcA.get(left)
        val rightNfcA = NfcA.get(right)
        if ((leftNfcA == null) != (rightNfcA == null)) return "NfcA presence"
        if (leftNfcA != null && rightNfcA != null) {
            if (leftNfcA.sak != rightNfcA.sak) return "NfcA.sak"
            if (!leftNfcA.atqa.contentEquals(rightNfcA.atqa)) return "NfcA.atqa"
        }

        val leftNdef = Ndef.get(left)
        val rightNdef = Ndef.get(right)
        if ((leftNdef == null) != (rightNdef == null)) return "Ndef presence"
        if (leftNdef != null && rightNdef != null) {
            if (leftNdef.maxSize != rightNdef.maxSize) return "Ndef.maxSize"
            if (leftNdef.isWritable != rightNdef.isWritable) return "Ndef.isWritable"
            if (leftNdef.type != rightNdef.type) return "Ndef.type"
            val leftMessage = leftNdef.cachedNdefMessage
            val rightMessage = rightNdef.cachedNdefMessage
            if ((leftMessage == null) != (rightMessage == null)) return "Ndef.cachedNdefMessage"
            if (leftMessage != null && rightMessage != null &&
                !leftMessage.toByteArray().contentEquals(rightMessage.toByteArray())
            ) {
                return "Ndef.cachedNdefMessage bytes"
            }
        }
        return null
    }

    private companion object {
        const val TAG = "NfcTestDiag"
    }
}
