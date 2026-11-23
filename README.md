# nfc-test

Injects a fake NFC tag into an app running on an emulator without NFC.

- `:nfc-test-api`: release-safe `NfcReader` facade, `RealNfcReader` and `NfcReaders`.
- `:nfc-test`: debug-only receiver, init provider and tag factory.

## Why

Android emulators have no NFC radio. Testing tag discovery normally needs
physical tags, a hardware device or a custom build. This project fabricates an
`android.nfc.Tag` with an `NdefMessage`, injects it from `adb`, and lets the app
receive it through the same reader-mode, foreground-dispatch or cold-start paths
it uses in production.

It is a development and test tool. The injected tag carries discovery metadata
(`id`, `techList`, `cachedNdefMessage`) but has no `INfcTag`, so `connect()`,
`readNdefMessage()` and `writeNdefMessage()` still fail. Do not ship the debug
module in release.

<p align="center">
  <img src="docs/demo.gif" alt="Cold-start demo" width="480">
</p>

## Requirements

- JDK 17 or newer.
- Android SDK with `compileSdk` 37 and AOSP API 30/34/36 images for Gradle
  Managed Devices.

## Integration

```kotlin
dependencies {
    implementation(project(":nfc-test-api"))
    debugImplementation(project(":nfc-test"))
}
```

Call `NfcReaders.get().enable(activity, callback)` from `onResume` and
`NfcReaders.get().disable(activity)` from `onPause`. In debug builds a
`ContentProvider` installs an injectable reader before `Application.onCreate`;
release builds use the real reader. The mock Tag supports discovery metadata but
not `connect()`/`transceive()` because there is no `INfcTag`.

## JitPack

The libraries are published from git tags. Add JitPack to your repositories and
depend on the modules you need:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.igarciadelamo.nfc-test:nfc-test-api:v0.1.0")
    debugImplementation("com.github.igarciadelamo.nfc-test:nfc-test:v0.1.0")
}
```

`nfc-test` is debug-only and brings `nfc-test-api` transitively in debug builds.
Keep the explicit `nfc-test-api` dependency for release builds.

## Sending a tag

```bash
scripts/nfc-tap.sh <package> scripts/examples/uri-text.json foreground
adb shell am broadcast -a es.igarciadelamo.nfctest.TAP -p <pkg> -f 32 --es tag_b64 <b64> --es mode auto
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
  {"type":"uri","value":"https://example.com"},
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
./gradlew publishToMavenLocal
```

`REFLECTION` needs `hidden_api_policy=1` while the emulator boots. Without it,
the REFLECTION cases are skipped instead of failing. Architecture and detailed
results: `docs/ARCHITECTURE.md`, `docs/STEP2_RESULTS.md` and
`docs/COLD_START_RESULTS.md`.

### Running GMD serially

`maxConcurrentDevices=1` in `gradle.properties` keeps Gradle Managed Devices
from starting more than one emulator at a time. To run one module and one API
per command, use the per-API task names:

```bash
./gradlew :nfc-test:api30DebugAndroidTest
./gradlew :nfc-test:api34DebugAndroidTest
./gradlew :nfc-test:api36DebugAndroidTest

./gradlew :nfc-test-api:api30DebugAndroidTest
./gradlew :nfc-test-api:api34DebugAndroidTest
./gradlew :nfc-test-api:api36DebugAndroidTest

./gradlew :sample:api30DebugAndroidTest
./gradlew :sample:api34DebugAndroidTest
./gradlew :sample:api36DebugAndroidTest

./gradlew :sample-e2e:api30DebugAndroidTest
./gradlew :sample-e2e:api34DebugAndroidTest
./gradlew :sample-e2e:api36DebugAndroidTest
```

The `allDevicesGroupAndroidTest` aliases run the same tasks for one module
across all three APIs.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
