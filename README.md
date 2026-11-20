# nfc-test

Injects a fake NFC tag into an app running on an emulator without NFC. Two artifacts:

- `:nfc-test-api` — `NfcReader` / `NfcReaders` interface. Ships in **release**.
- `:nfc-test` — receiver, provider and tag factory. **Debug only**.

## Integration

```kotlin
dependencies {
    implementation(project(":nfc-test-api"))
    debugImplementation(project(":nfc-test"))
}
```

Production code only uses `NfcReaders.get()`:

```kotlin
override fun onResume() {
    super.onResume()
    NfcReaders.get().enable(this, TagCallback { tag -> /* ... */ })
}

override fun onPause() {
    NfcReaders.get().disable(this)
    super.onPause()
}
```

In debug, a `ContentProvider` replaces the factory when the process starts and the same
callback receives both real and injected tags. In release, `RealNfcReader` remains.

## Sending a tag

```bash
adb shell am broadcast -a dev.nfctest.TAP -p <package> -f 32 \
  --es tag_b64 <base64-json> --es mode auto

scripts/nfc-tap.sh <package> scripts/examples/uri-text.json foreground
```

`tag_b64` avoids quoting issues; `--es tag '<json>'` is also accepted.
Modes: `auto` (default), `reader`, `foreground`, `implicit`.
`am broadcast` result codes: `1` delivered, `2` invalid tag, `3` no
destination, `4` reader mode not active, `5` unexpected error.

## JSON

```json
{
  "uid": "01020304",
  "readOnly": false,
  "records": [
    {"type": "uri", "value": "https://txoka.app"},
    {"type": "text", "lang": "en", "value": "hello"},
    {"type": "mime", "mime": "text/vcard", "valueBase64": "..."},
    {"type": "external", "domain": "example.com", "typeName": "t", "valueBase64": "..."}
  ]
}
```

`uid` in hex of 4, 7 or 10 bytes; at least one record; message ≤ 16 KiB.

## Commands

```bash
./gradlew assembleDebug assembleDebugAndroidTest assembleRelease
./gradlew testDebugUnitTest
./gradlew :sample:allDevicesGroupAndroidTest      # e2e on API 30, 34 and 36
./gradlew :nfc-test:allDevicesGroupAndroidTest    # 45 spike tests
./gradlew :sample:verifyReleaseHasNoNfcTest
```

The spike's `REFLECTION` tests need `adb shell settings put global
hidden_api_policy 1` while the emulator boots (see `docs/STEP2_RESULTS.md`).

Details: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md),
[`docs/STEP2_RESULTS.md`](docs/STEP2_RESULTS.md),
[`docs/PARCEL_LAYOUTS.md`](docs/PARCEL_LAYOUTS.md).
