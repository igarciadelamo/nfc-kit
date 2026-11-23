# nfc-test

Injects a fake NFC tag into an app running on an emulator without NFC.

- `:nfc-test-api`: release-safe `NfcReader` facade, `RealNfcReader` and `NfcReaders`.
- `:nfc-test`: debug-only receiver, init provider and tag factory.

## Requirements

- JDK 17 or newer. AGP 9.5.1 targets Java 17 bytecode (class file 61), so the
  minimum is JDK 17; Gradle runs on Temurin 21.0.13 in this environment. Steps 1
  and 2 were verified with JDK 17; cold start was verified with 21.0.13.
- Android SDK with `compileSdk` 37 and AOSP API 30/34/36 images for GMD.

## Integration

```kotlin
dependencies {
    implementation(project(":nfc-test-api"))
    debugImplementation(project(":nfc-test"))
}
```

Call `NfcReaders.enable(activity, callback)` from `onResume` and
`NfcReaders.disable(activity)` from `onPause`. In debug builds a `ContentProvider`
installs an injectable reader before `Application.onCreate`; release builds use
the real reader. The mock Tag supports discovery metadata but not
`connect()`/`transceive()` because there is no `INfcTag`.

## Sending a tag

```bash
scripts/nfc-tap.sh <package> scripts/examples/uri-text.json foreground
adb shell am broadcast -a dev.nfctest.TAP -p <pkg> -f 32 --es tag_b64 <b64> --es mode auto
```

Modes: `auto` (default), `reader`, `foreground`, `implicit`. `foreground` and
`implicit` require a foreground activity; without one they return `result=3`.
Result codes: `1` delivered/launched, `2` invalid tag, `3` no destination,
`4` no reader, `5` unexpected error.

## Cold start

`scripts/nfc-tap.sh --cold <package> <json-file|-> [--keep-process]` starts
`NfcTestLaunchActivity` from the shell after `force-stop`, waits for the
`cold id=... result=...` logcat line, and exits non-zero unless the result is 1.
It honors `ANDROID_SERIAL`. The activity is debug-only and guarded by
`android.permission.DUMP`.

```bash
scripts/nfc-tap.sh --cold <package> scripts/examples/uri-text.json
```

The result is written to logcat as `cold id=<id> result=<code> data=<...>`.
The script also prints `pid before=... after=...`.

The debug extra `--ez no_destination true` forces the "no destination" branch
for testing. It is debug-only and `verifyReleaseHasNoNfcTest` rejects it in
release builds.

## JSON

```json
{"uid":"01020304","readOnly":false,"records":[
  {"type":"uri","value":"https://txoka.app"},
  {"type":"text","lang":"en","value":"hello"},
  {"type":"mime","mime":"text/vcard","valueBase64":"..."},
  {"type":"external","domain":"example.com","typeName":"t","valueBase64":"..."}]}
```

UID is 4, 7 or 10 bytes in hex. At least one record is required. Message size is
at most 16 KiB.

## Verification

```bash
./gradlew assembleDebug assembleDebugAndroidTest assembleRelease
./gradlew testDebugUnitTest
./gradlew :nfc-test:allDevicesGroupAndroidTest
./gradlew :sample:allDevicesGroupAndroidTest
./gradlew :sample-e2e:allDevicesGroupAndroidTest
./gradlew :sample:verifyReleaseHasNoNfcTest
```

`REFLECTION` needs `hidden_api_policy=1` while the emulator boots. Architecture
and detailed results: `docs/ARCHITECTURE.md`, `docs/STEP2_RESULTS.md` and
`docs/COLD_START_RESULTS.md`.
