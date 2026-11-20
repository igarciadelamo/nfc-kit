# Spike results

Run date: 2026-11-20.
Environment: macOS arm64, JDK 17.0.21.1, Gradle 9.9.2, AGP 9.5.1, `compileSdk`/`targetSdk` 37,
GMD `Pixel 6` emulators with AOSP image (`systemImageSource = "aosp"`).

Command run:

```bash
./gradlew allDevicesGroupAndroidTest
# per-API equivalent: ./gradlew api30DebugAndroidTest | api34DebugAndroidTest | api36DebugAndroidTest
```

The final group run started the three devices and each finished with
**45/45 tests OK** (0 failures, 0 skipped). The PARCEL and AUTO strategies work without
touching hidden API; REFLECTION needs the setting from section 4.

## 1. API × strategy × assertion table

`P` = PARCEL, `R` = REFLECTION, `A` = AUTO. `R` ran with
`hidden_api_policy=1` (see section 4).

| Assertion / case | API 30 P/R/A | API 34 P/R/A | API 36 P/R/A |
|---|---|---|---|
| `tag.id` = 4, 7 and 10-byte UID | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| `techList` contains `NfcA` and `Ndef` | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| `Ndef.get(tag)` and `NfcA.get(tag)` non-null | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| `cachedNdefMessage` with 2 records and identical bytes | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| `maxSize` ≥ message, `isWritable`, `type == NFC_FORUM_TYPE_2` | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| `NfcA.sak == 0` and `atqa == 44 00` | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| `readOnly = true` and `readOnly = false` | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| Parcel round-trip (`Tag.CREATOR`) | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| Intent round-trip with `EXTRA_TAG` (typed API on 33+) | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| `ACTION_TECH_DISCOVERED` + `EXTRA_TAG` + `EXTRA_ID` + `EXTRA_NDEF_MESSAGES` | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| NDEF with 1 record | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| NDEF with 3 records (URI + MIME `text/vcard` + external) | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| NDEF of ~2 KB and `ndefmaxlength` sufficient | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| `NfcA`-only tag → `Ndef.get(tag) == null` | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| Invalid UID (0, 2, 5, 8 bytes) rejected | OK/OK/OK | OK/OK/OK | OK/OK/OK |

AUTO nuance: AUTO uses PARCEL first and falls back to REFLECTION only when the
self-check fails. On API 30 without the hidden-API setting, AUTO still passed.

## 2. Empirical contrast of the layout

`ParcelLayoutDiagnosticTest` builds the Parcel by hand, reads it with `Tag.CREATOR`,
and compares it byte by byte with the framework's `writeToParcel` on all three APIs.

| API | Bytes | PARCEL vs `writeToParcel` | PARCEL vs REFLECTION |
|---|---|---|---|
| 30 | 408 | OK, identical | OK, identical |
| 34 | 420 | OK, identical | OK, identical |
| 36 | 428 | OK, identical | OK, identical |

`createMockTag` signature:

- API 30: `(byte[], int[], Bundle[])`
- API 34: `(byte[], int[], Bundle[], long)`
- API 36: `(byte[], int[], Bundle[], long)`

There were no `Bundle` serialization differences: `marshall()` matched byte for byte
on all three APIs.

## 3. Behavior of `connect`/`read`/`write`

Identical on API 30, 34 and 36 (`files/limits-*.json` and `adb logcat -s NfcTestLimits`):

| Operation | Result |
|---|---|
| `Ndef.connect()` | `NullPointerException`: `INfcTag.connect(int, int)` on a null reference |
| `Ndef.isConnected` | `false` |
| `Ndef.getNdefMessage()` (read) | `IllegalStateException: Call connect() first!` |
| `Ndef.writeNdefMessage(...)` | `IllegalStateException: Call connect() first!` |
| `Ndef.close()` | `NullPointerException`: `INfcTag.resetTimeouts()` on a null reference |

The mock Tag has `mTagService == null` (that is why it is a mock). The discovery NDEF
metadata (`cachedNdefMessage`, `maxSize`, `type`, `isWritable`) is correct; what is
missing is the IPC channel to the NFC service for I/O and transceive. A future wrapper
would have to intercept that channel, not just build the Tag.

Device files: `/data/data/dev.nfctest.test/files/limits-30.json`,
`limits-34.json` and `limits-36.json` (the GMD emulators are destroyed at the end; the
full content is also in the test reports).

## 4. Hidden API policy

- **API 30, without the setting**: `Tag::class.java.declaredMethods` returned no
  `createMockTag` (`candidates = 0`). The 13 REFLECTION tests failed and the contrast
  against `createMockTag` was skipped. PARCEL and AUTO passed.
- **API 30, with `hidden_api_policy=1`** (and `hidden_api_policy_p_apps`,
  `hidden_api_policy_pre_p_apps`): REFLECTION found the 3-argument signature and all 45
  tests passed.
- **API 34 and 36**: all 45 tests passed with the same setting applied over adb while
  the emulator booted. The clean contrast without the setting was not repeated on these
  two APIs because the GMD snapshot kept the value between runs: **NOT VERIFIED**
  whether the default blocks them.

Commands applied to each GMD emulator from the host during boot:

```bash
adb shell settings put global hidden_api_policy 1
adb shell settings put global hidden_api_policy_p_apps 1
adb shell settings put global hidden_api_policy_pre_p_apps 1
```

On API 30 with `hidden_api_policy=1`, `getDeclaredMethods` exposes the hidden method;
without it, REFLECTION cannot see `createMockTag`.

Practical conclusion: REFLECTION **requires relaxing hidden API** (at least on API 30).
PARCEL works without it, so AUTO prefers PARCEL.

## 5. Problems found and resolution

1. **The layout hypothesis was wrong.** `writeBytesWithNull` writes the id length and
   then `writeByteArray` writes it again; the tech list also carries an extra length;
   the `cookie` appears on API 34 but not before; and Android 13 has `mCookie` but does
   not serialize it. The fix reads `writeToParcel` from AOSP and validates with
   `marshall()`.
2. **`ndefmsg` is not `byte[]`.** `Ndef` reads a Parcelable `NdefMessage` with
   `getParcelable`. The extras were fixed.
3. **GMD changed DSL in AGP 9.5.** `devices["api30"]` no longer exists; the references
   returned by `localDevices.create(...)` are kept and added to the group.
4. **Group task name.** AGP generates `allDevicesGroupDebugAndroidTest`, not
   `allDevicesGroupAndroidTest`. A Gradle alias was added so the README command works.
5. **Wrapper download.** The default `networkTimeout` (10 s) cut the Gradle download;
   it was raised to 120 s.
6. **Hidden API.** The setting was applied by adb during each GMD boot; without it,
   REFLECTION does not see the method.

## 6. Conclusion

**CONTINUE WITH LIMITATIONS.**

- Building an `android.nfc.Tag` with NDEF and having `Ndef.get(tag)` treat it as real is
  **viable on API 30, 34 and 36** with PARCEL, without hidden APIs. The layout matches
  the framework byte for byte on all three APIs.
- REFLECTION is a functional fallback, but it **depends on relaxing hidden API**
  (`adb shell settings put global hidden_api_policy 1`). It should not be the primary
  strategy.
- Important limitation: the Tag is for **discovery**. `connect`, `read` and `write` do
  not work because there is no `INfcTag` (`transceive` was not tested in this spike).
  It serves apps that read `EXTRA_NDEF_MESSAGES`/`cachedNdefMessage` when receiving the
  Intent, not apps that open an `Ndef` session and read/write.
- Out of scope for this spike (and a pending risk): how to inject that Tag into the app
  process (broadcast, reader mode or NFC binder hook). Only fabrication and its
  verification are demonstrated here.
