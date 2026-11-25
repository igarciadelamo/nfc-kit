package es.igarciadelamo.nfctest.internal

import android.os.Build
import android.os.Bundle
import android.os.Parcel

/**
 * Encodes the exact byte layout that [android.nfc.Tag.writeToParcel] produces.
 *
 * The layout is not stable across Android releases, so each version family gets
 * its own implementation. See docs/PARCEL_LAYOUTS.md for the AOSP references.
 */
internal interface TagParcelLayout {
    fun writeToParcel(dest: Parcel, id: ByteArray, techCodes: IntArray, extras: Array<Bundle>)
}

/**
 * Android 8.0 through Android 13 (API 26-33).
 *
 * AOSP `Tag.writeToParcel` is identical in this range:
 *   writeBytesWithNull(id)  -> int length + byte[] (writeByteArray writes the length again)
 *   int techList.length
 *   int[] techList          -> writeIntArray also writes its own length
 *   Bundle[] techExtras     -> writeTypedArray
 *   int serviceHandle
 *   int isMock
 *
 * Android 13 has the mCookie field but does not serialize it.
 */
internal object ParcelLayoutApi26To33 : TagParcelLayout {
    override fun writeToParcel(
        dest: Parcel,
        id: ByteArray,
        techCodes: IntArray,
        extras: Array<Bundle>,
    ) {
        dest.writeInt(id.size)
        dest.writeByteArray(id)
        dest.writeInt(techCodes.size)
        dest.writeIntArray(techCodes)
        dest.writeTypedArray(extras, 0)
        dest.writeInt(0) // serviceHandle
        dest.writeInt(1) // isMock: 1 means no tag service binder follows
    }
}

/**
 * Android 14 and newer (API 34+).
 *
 * Same as [ParcelLayoutApi26To33] plus `long cookie` between serviceHandle and isMock.
 */
internal object ParcelLayoutApi34Plus : TagParcelLayout {
    override fun writeToParcel(
        dest: Parcel,
        id: ByteArray,
        techCodes: IntArray,
        extras: Array<Bundle>,
    ) {
        dest.writeInt(id.size)
        dest.writeByteArray(id)
        dest.writeInt(techCodes.size)
        dest.writeIntArray(techCodes)
        dest.writeTypedArray(extras, 0)
        dest.writeInt(0) // serviceHandle
        dest.writeLong(0L) // cookie, added in API 34
        dest.writeInt(1) // isMock: 1 means no tag service binder follows
    }
}

internal object ParcelLayout {
    fun forSdkInt(sdkInt: Int = Build.VERSION.SDK_INT): TagParcelLayout = when {
        sdkInt >= 34 -> ParcelLayoutApi34Plus
        sdkInt >= 26 -> ParcelLayoutApi26To33
        else -> error("Unsupported SDK $sdkInt: NFC Kit supports API 26+")
    }
}
