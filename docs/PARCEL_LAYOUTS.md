# `android.nfc.Tag` Parcel layout

Source review: **2026-11-20**. Everything here was read directly from the AOSP code
of the corresponding branch; the empirical contrast is in
[`SPIKE_RESULTS.md`](SPIKE_RESULTS.md).

## Summary

`Tag.writeToParcel` does not use the same layout on every version. There are two
families relevant to this spike:

| Family | APIs | `long cookie` | `Tag.createMockTag` |
|---|---|---|---|
| A | 30, 31, 32, 33 | No | `(byte[] id, int[] techList, Bundle[] extras)` |
| B | 34, 36 (35 not reviewed) | Yes | `(byte[] id, int[] techList, Bundle[] extras, long cookie)` |

`Tag.java` was reviewed in `frameworks/base` for API 30 and 34, and in the Mainline
module `packages/modules/Nfc` for API 36. Android 15 (API 35) was not reviewed.

## Fields, in order

| # | AOSP write | Bytes in the Parcel |
|---|---|---|
| 1 | `writeBytesWithNull(dest, id)` | `int len` + `writeByteArray(id)` (which writes `int len` + bytes again) |
| 2 | `dest.writeInt(mTechList.length)` | `int` with the length |
| 3 | `dest.writeIntArray(mTechList)` | `int len` + `len * int` |
| 4 | `dest.writeTypedArray(mTechExtras, 0)` | `int count` + one `Bundle` per technology |
| 5 | `dest.writeInt(mServiceHandle)` | `int` (0 for a mock tag) |
| 6 | `dest.writeLong(mCookie)` | **API 34+ only** |
| 7 | `dest.writeInt(isMock)` | `int`; `1` = mock, no binder follows |
| 8 | `dest.writeStrongBinder(...)` | only if `isMock == 0` |

Two non-obvious details:

- The id is written with its own `int` length and then `writeByteArray` writes its
  length too. The length appears **twice**.
- The tech list also carries an extra length before `writeIntArray`.
- `mTechList` are `TagTechnology` codes (`NFC_A = 1`, `NDEF = 6`), not strings.
  `Tag` builds `mTechStringList` internally in the constructor.

## API 30 (Android 11)

Fuente:
<https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/core/java/android/nfc/Tag.java>

```java
public void writeToParcel(Parcel dest, int flags) {
    int isMock = (mTagService == null) ? 1 : 0;
    writeBytesWithNull(dest, mId);
    dest.writeInt(mTechList.length);
    dest.writeIntArray(mTechList);
    dest.writeTypedArray(mTechExtras, 0);
    dest.writeInt(mServiceHandle);
    dest.writeInt(isMock);
    if (isMock == 0) dest.writeStrongBinder(mTagService.asBinder());
}

public static Tag createMockTag(byte[] id, int[] techList, Bundle[] techListExtras) {
    return new Tag(id, techList, techListExtras, 0, null);
}
```

Status: **VERIFIED in source**. Empirical contrast: see `SPIKE_RESULTS.md`.

## API 34 (Android 14)

Fuente:
<https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/nfc/Tag.java>

```java
public void writeToParcel(Parcel dest, int flags) {
    int isMock = (mTagService == null) ? 1 : 0;
    writeBytesWithNull(dest, mId);
    dest.writeInt(mTechList.length);
    dest.writeIntArray(mTechList);
    dest.writeTypedArray(mTechExtras, 0);
    dest.writeInt(mServiceHandle);
    dest.writeLong(mCookie);
    dest.writeInt(isMock);
    if (isMock == 0) dest.writeStrongBinder(mTagService.asBinder());
}

public static Tag createMockTag(byte[] id, int[] techList, Bundle[] techListExtras,
        long cookie) {
    return new Tag(id, techList, techListExtras, 0, cookie, null);
}
```

Status: **VERIFIED in source**. This is the first family with a serialized `cookie`.
Android 13 (API 33) has the `mCookie` field but does **not** write it to the Parcel.

## API 36 (Android 16)

Fuente:
<https://android.googlesource.com/platform/packages/modules/Nfc/+/refs/heads/android16-release/framework/java/android/nfc/Tag.java>

Same `writeToParcel` and same `createMockTag` signature as API 34 (the file is
identical between `android14-release` and `android16-release` at review time).

Status: **VERIFIED in source**.

## `Ndef` and `NfcA` extras

Contrasted with `Ndef.java` and `NfcA.java` from the same branches:

| Tech | Key | Real type in the `Bundle` |
|---|---|---|
| Ndef | `ndefmsg` | `NdefMessage` (Parcelable), **not** `byte[]` |
| Ndef | `ndefmaxlength` | `int` |
| Ndef | `ndefcardstate` | `int` (1 = read-only, 2 = read-write) |
| Ndef | `ndeftype` | `int` (2 = Type 2) |
| NfcA | `sak` | `short` |
| NfcA | `atqa` | `byte[]` |

On API 30, the `Ndef` constructor calls `extras.getParcelable(EXTRA_NDEF_MSG)`; on
API 34+ it uses the typed variant. In both cases the value must be an `NdefMessage`.

## Empirical contrast

`ParcelLayoutDiagnosticTest` compares `Parcel.marshall()` of the hand-written layout
with the one from a Tag created by `Tag.createMockTag` and with the one produced by
`Tag.writeToParcel` after decoding the layout with `Tag.CREATOR`:

| API | Size | PARCEL vs platform | `createMockTag` signature |
|---|---|---|---|
| 30 | 408 B | identical byte for byte | `(byte[], int[], Bundle[])` |
| 34 | 420 B | identical byte for byte | `(byte[], int[], Bundle[], long)` |
| 36 | 428 B | identical byte for byte | `(byte[], int[], Bundle[], long)` |

Run on 2026-11-20 on GMD AOSP emulators (Pixel 6). Details and commands are in
[`SPIKE_RESULTS.md`](SPIKE_RESULTS.md).

