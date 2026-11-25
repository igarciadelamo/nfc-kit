# Spike results

Run date: 2026-11-20.
Environment: macOS arm64, JDK 17.0.21.1, Gradle 9.9.2, AGP 9.5.1,
`compileSdk`/`targetSdk` 37, GMD `Pixel 6` with AOSP images
(`systemImageSource = "aosp"`).

Command run:

```bash
./gradlew allDevicesGroupAndroidTest
# per-API equivalent: ./gradlew api30DebugAndroidTest | api34DebugAndroidTest | api36DebugAndroidTest
```

The final group run started all three devices and each finished with **45/45
tests OK** (0 failures, 0 skipped). PARCEL and AUTO work without touching hidden
API; REFLECTION needs the adjustment described in section 4.

## 1. API × strategy × assertion table

`P` = PARCEL, `R` = REFLECTION, `A` = AUTO. `R` ran with
`hidden_api_policy=1` (see section 4).

| Assertion / case | API 30 P/R/A | API 34 P/R/A | API 36 P/R/A |
| --- | --- | --- | --- |
| `tag.id` = 4, 7 and 10-byte UID | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| `techList` contains `NfcA` and `Ndef` | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| `Ndef.get(tag)` and `NfcA.get(tag)` are non-null | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| `cachedNdefMessage` has 2 records and identical bytes | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| `maxSize` ≥ message, `isWritable`, `type == NFC_FORUM_TYPE_2` | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| `NfcA.sak == 0` and `atqa == 44 00` | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| `readOnly = true` and `readOnly = false` | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| Round trip through `Parcel` (`Tag.CREATOR`) | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| Round trip through `Intent` with `EXTRA_TAG` (typed API on 33+) | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| `ACTION_TECH_DISCOVERED` + `EXTRA_TAG` + `EXTRA_ID` + `EXTRA_NDEF_MESSAGES` | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| NDEF with 1 record | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| NDEF with 3 records (URI + MIME `text/vcard` + external) | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| NDEF of ~2 KB with sufficient `ndefmaxlength` | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| `NfcA`-only tag → `Ndef.get(tag) == null` | OK/OK/OK | OK/OK/OK | OK/OK/OK |
| Invalid UID (0, 2, 5, 8 bytes) rejected | OK/OK/OK | OK/OK/OK | OK/OK/OK |

AUTO nuance: it tries PARCEL, runs its self-check and only falls back to
REFLECTION if PARCEL fails. On API 30 it was tested without hidden API: AUTO
passed; the only 13 failures were the explicit REFLECTION tests.

## 2. Empirical layout comparison

`ParcelLayoutDiagnosticTest` builds the Parcel by hand, decodes it with
`Tag.CREATOR`, serializes it again with `Tag.writeToParcel`, and also compares
against a Tag created with `Tag.createMockTag`. All three dumps match
byte-for-byte:

| API | Bytes | PARCEL vs `writeToParcel` | PARCEL vs REFLECTION |
| --- | --- | --- | --- |
| 30 | 408 | OK, identical | OK, identical |
| 34 | 420 | OK, identical | OK, identical |
| 36 | 428 | OK, identical | OK, identical |

Actual `createMockTag` signature observed with `getDeclaredMethods()`:

- API 30: `(byte[], int[], Bundle[])`
- API 34: `(byte[], int[], Bundle[], long)`
- API 36: `(byte[], int[], Bundle[], long)`

There were no `Bundle` serialization differences: `marshall()` is identical in
all three cases, so the semantic fallback comparison was not needed.

### API 26

Re-run for 0.3.0 on a GMD AOSP API 26 emulator (2026-11-25): `ParcelLayoutDiagnosticTest`
reports 408 B and a byte-for-byte match against both the hand-written layout and
`Tag.createMockTag`; the signature is `(byte[], int[], Bundle[])`. All 46 tests in
`:nfc-test` passed, including REFLECTION, which needs no hidden-API policy on
API 26.

## 3. `connect`/`read`/`write` behavior

Identical on API 30, 34 and 36 (`files/limits-<api>.json` and `NfcTestLimits`
logcat):

| Operation | Result |
| --- | --- |
| `Ndef.connect()` | `NullPointerException`: `INfcTag.connect(int, int)` on a null reference |
| `Ndef.isConnected` | `false` |
| `Ndef.getNdefMessage()` (read) | `IllegalStateException: Call connect() first!` |
| `Ndef.writeNdefMessage(...)` | `IllegalStateException: Call connect() first!` |
| `Ndef.close()` | `NullPointerException`: `INfcTag.resetTimeouts()` on a null reference |

The mock Tag has `mTagService == null` (that is why it is a mock). The NDEF
discovery metadata (`cachedNdefMessage`, `maxSize`, `type`, `isWritable`) is
correct; what does not exist is the IPC channel to the NFC service for I/O and
transceive. A future wrapper would have to intercept those calls, not just
build the Tag.

Files on the device: `/data/user/0/es.igarciadelamo.nfctest.test/files/limits-30.json`,
`limits-34.json`, `limits-36.json` (GMD emulators are destroyed on completion;
the full content remains in logcat and in the result XML files).

## 4. Hidden API policy

- **API 30, without the setting**: `Tag::class.java.declaredMethods` did not
  return any `createMockTag` (`candidates = 0`). The 13 REFLECTION tests failed
  and the comparison against `createMockTag` was skipped. PARCEL and AUTO passed.
- **API 30, with `hidden_api_policy=1`** (and `hidden_api_policy_p_apps`,
  `hidden_api_policy_pre_p_apps`): REFLECTION found the 3-argument signature and
  all 45 tests passed.
- **API 34 and 36**: all 45 tests passed with the same setting applied over adb
  while the emulator booted. The clean comparison without the setting was not
  repeated on these two APIs because the GMD snapshot kept the value between
  runs: **NOT VERIFIED** whether the default blocks them.

Commands applied to each GMD emulator (from the host, during boot):

```bash
adb shell settings put global hidden_api_policy 1
adb shell settings put global hidden_api_policy_p_apps 1
adb shell settings put global hidden_api_policy_pre_p_apps 1
```

On API 30, with `hidden_api_policy=1`, `getDeclaredMethods()` returns the hidden
method; with the AOSP image default, it does not.

Practical conclusion: REFLECTION **requires** relaxing hidden API (at least on
API 30); PARCEL requires nothing. That is why AUTO uses PARCEL first.

## 5. Problems found and resolution

1. **The layout hypothesis was wrong.** `writeBytesWithNull` writes the id
   length and `writeByteArray` writes it again; the tech list has another
   redundant length; `cookie` appears on API 34, not earlier; and Android 13 has
   `mCookie` but does not serialize it. Fixed by reading AOSP `writeToParcel` and
   validating with `marshall()`.
2. **`ndefmsg` is not `byte[]`.** `Ndef` reads a Parcelable `NdefMessage` with
   `getParcelable`. The extras were fixed.
3. **GMD changed its DSL in AGP 9.5.** `devices["api30"]` no longer exists; the
   references returned by `localDevices.create(...)` are stored and added to the
   group.
4. **Group task name.** AGP generates `allDevicesGroupDebugAndroidTest`, not
   `allDevicesGroupAndroidTest`. A Gradle alias was added so the README command
   works.
5. **Wrapper download.** The default `networkTimeout` (10 s) cut off the Gradle
   download; it was raised to 120 s.
6. **Hidden API.** The setting was applied over adb while each GMD emulator
   booted; without it, REFLECTION does not see the method.

## 6. Conclusion

**CONTINUE WITH LIMITATIONS.**

- Building an `android.nfc.Tag` with NDEF that `Ndef.get(tag)` treats as real is
  **viable on API 30, 34 and 36** using PARCEL, without hidden APIs. The layout
  matches the framework byte-for-byte on all three APIs.
- REFLECTION is a functional fallback, but it **depends on relaxing hidden API**
  (`adb shell settings put global hidden_api_policy 1`). It should not be the
  primary strategy.
- Important limitation: the Tag is for **discovery**. `connect`, `read` and
  `write` do not work because there is no `INfcTag` (`transceive` was not tested
  in this spike). It works for apps that read `EXTRA_NDEF_MESSAGES` /
  `cachedNdefMessage` when receiving the Intent, not for apps that open an `Ndef`
  session and read/write.
- Out of scope for this spike (and a pending risk): how to inject that Tag into
  the app process (broadcast, reader mode or NFC binder hook). Only fabrication
  and its verification were demonstrated here.
