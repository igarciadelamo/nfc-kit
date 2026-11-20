# nfc-test

Injects a fake NFC tag into an app running on an emulator without NFC.

- `:nfc-test-api` — `NfcReader` / `NfcReaders`. Ships in **release**.
- `:nfc-test` — receiver, provider and tag factory. **Debug only**.

## Integration

```kotlin
dependencies {
    implementation(project(":nfc-test-api"))
    debugImplementation(project(":nfc-test"))
}
```

Usage: `NfcReaders.get().enable(this, TagCallback { tag -> ... })` in `onResume` and
`NfcReaders.get().disable(this)` in `onPause`. In debug a `ContentProvider`
replaces the factory when the process starts and the same callback receives both
real and injected tags. In release, `RealNfcReader` remains.

## Sending a tag

```bash
scripts/nfc-tap.sh <package> scripts/examples/uri-text.json foreground
adb shell am broadcast -a dev.nfctest.TAP -p <pkg> -f 32 --es tag_b64 <b64> --es mode auto
```

Modes: `auto` (default), `reader`, `foreground`, `implicit`. Results: `1`
delivered, `2` invalid tag, `3` no destination, `4` reader not active,
`5` unexpected error.

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

UID in hex of 4, 7 or 10 bytes; at least one record; message ≤ 16 KiB.

## Commands

```bash
./gradlew assembleDebug assembleDebugAndroidTest assembleRelease
./gradlew testDebugUnitTest
./gradlew :nfc-test:allDevicesGroupAndroidTest
./gradlew :sample:allDevicesGroupAndroidTest
./gradlew :sample:verifyReleaseHasNoNfcTest
```

`REFLECTION` needs `hidden_api_policy 1` while the emulator boots
(see `docs/STEP2_RESULTS.md`). Architecture: `docs/ARCHITECTURE.md`.
