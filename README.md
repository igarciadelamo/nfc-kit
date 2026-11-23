<h1 align="center">NFC Test</h1>

<p align="center">
  <strong>Fake NFC tags for Android emulators.</strong><br>
  <sub>Inject tags from <code>adb</code> and exercise the same reader-mode, foreground-dispatch and cold-start paths your app uses in production — without a physical tag or an NFC radio.</sub>
</p>

<p align="center">
  <a href="https://jitpack.io/#igarciadelamo/nfc-test"><img src="https://jitpack.io/v/igarciadelamo/nfc-test.svg" alt="JitPack"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue.svg?style=flat-square" alt="License: Apache 2.0"></a>
  <img src="https://img.shields.io/badge/minSdk-30-3DDC84.svg?style=flat-square&logo=android&logoColor=white" alt="minSdk 30">
  <img src="https://img.shields.io/badge/tests-managed%20devices%20%C2%B7%20API%2030%20%7C%2034%20%7C%2036-success.svg?style=flat-square" alt="Tested on managed devices API 30, 34 and 36">
</p>

---

## Why nfc-test?

Android emulators have no NFC radio. Testing tag discovery normally needs
physical tags, a hardware device or a custom build of your app.

`nfc-test` fabricates an `android.nfc.Tag` with an `NdefMessage`, injects it from
`adb`, and lets your app receive it through the lifecycle it already implements.
No test-only branches in your app.

- **Reader mode, foreground dispatch and cold start** — pick the path you want to test.
- **Automatic delivery** — `auto` mode uses the reader when enabled, falls back to an intent.
- **Declarative tags** — describe UID and NDEF records in a small JSON file.
- **Debug-only** — the injection module never ships in release builds.
- **Verified** — instrumented tests run on API 30, 34 and 36 managed devices.

> **Note:** this is a development and test tool. The injected tag carries
> discovery metadata (`id`, `techList`, `cachedNdefMessage`) but has no
> `INfcTag`, so `connect()`, `readNdefMessage()` and `writeNdefMessage()` still
> fail. Never ship the `nfc-test` module in release.

## How it works

```
adb broadcast ──► NfcTestReceiver ──► TagDispatcher ──┬─► InjectableNfcReader ──► app callback
                                                      └─► Intent (foreground / implicit)
```

The debug provider installs `InjectableNfcReader` in `NfcReaders.factory` before
`Application.onCreate`, and the receiver runs in the app process so the fabricated
tag is usable there. Release builds have no provider, so `NfcReaders.get()` returns
the real platform reader.

## Modules

| Module | Build | Purpose |
| --- | --- | --- |
| `:nfc-test-api` | `implementation` | Release-safe `NfcReader` facade, `RealNfcReader` and `NfcReaders`. |
| `:nfc-test` | `debugImplementation` | Debug-only receiver, init provider, tag factory and cold-start trampoline. |
| `:sample` | demo | Minimal app showing reader mode and intent delivery. |
| `:sample-e2e` | tests | UiAutomator cold-start test against the sample. |

## Requirements

- JDK 17 or newer.
- Android SDK with `compileSdk` 37.
- AOSP API 30, 34 and 36 system images for Gradle Managed Devices.

## Getting started

### 1. Add the dependencies

```kotlin
dependencies {
    implementation(project(":nfc-test-api"))
    debugImplementation(project(":nfc-test"))
}
```

### 2. Read tags through the facade

Call `enable()` from `onResume` and `disable()` from `onPause`. In debug builds a
`ContentProvider` installs an injectable reader before `Application.onCreate`;
release builds get the real platform reader.

```kotlin
class MyActivity : Activity() {
    private val reader = NfcReaders.get()

    private val callback = TagCallback { tag ->
        // Runs on the reader thread, never on the main thread.
        val uid = tag.id.joinToString("") { "%02X".format(it) }
    }

    override fun onResume() {
        super.onResume()
        when (reader.enable(this, callback)) {
            EnableResult.ENABLED -> Unit
            else -> showNfcUnavailable() // NO_ADAPTER or NFC_DISABLED
        }
    }

    override fun onPause() {
        reader.disable(this)
        super.onPause()
    }
}
```

### 3. Send a tag

```bash
scripts/nfc-tap.sh <package> scripts/examples/uri-text.json
```

Or call the broadcast directly:

```bash
adb shell am broadcast -a es.igarciadelamo.nfctest.TAP -p <pkg> -f 32 \
  --es tag_b64 <b64> --es mode auto
```

| Mode | Behaviour |
| --- | --- |
| `auto` *(default)* | Reader if enabled, otherwise foreground, otherwise implicit. |
| `reader` | Delivers to the active reader-mode callback. |
| `foreground` | Starts an activity with the foreground-dispatch intent. |
| `implicit` | Starts an activity through the NDEF/TECH/TAG intent cascade. |

`foreground` and `implicit` require a foreground activity; without one they
return `result=3`.

| Result | Meaning |
| --- | --- |
| `1` | Delivered or launched. |
| `2` | Invalid tag. |
| `3` | No destination. |
| `4` | No reader. |
| `5` | Unexpected error. |

## Cold start

`NfcTestLaunchActivity` is a translucent trampoline that builds the tag,
resolves the NDEF → TECH → TAG cascade and forwards the intent. Launching it from
the shell avoids the background-activity restriction, and the destination still
sees the normal NFC extras.

```bash
scripts/nfc-tap.sh --cold <package> scripts/examples/uri-text.json
```

The script `force-stop`s the app, waits for the `cold id=<id> result=<code>`
logcat line, prints `pid before=... after=...`, and exits non-zero unless the
result is `1`. It honors `ANDROID_SERIAL`; pass `--keep-process` to skip the
`force-stop` and launch into the running process instead.

The activity is debug-only and guarded by `android.permission.DUMP`. The debug
extra `--ez no_destination true` forces the "no destination" branch for testing.
The trampoline that reads it is rejected in release builds by
`verifyReleaseHasNoNfcTest`.

## Tag format

```json
{
  "uid": "01020304",
  "readOnly": false,
  "records": [
    {"type": "uri", "value": "https://example.com"},
    {"type": "text", "lang": "en", "value": "hello"},
    {"type": "mime", "mime": "text/vcard", "valueBase64": "..."},
    {"type": "external", "domain": "example.com", "typeName": "t", "valueBase64": "..."}
  ]
}
```

| Record | Fields |
| --- | --- |
| `uri` | `value` |
| `text` | `value`, optional `lang` (defaults to `en`) |
| `mime` | `mime`, `valueBase64` |
| `external` | `domain`, `typeName`, `valueBase64` |

`uid` is required and must be 4, 7 or 10 bytes in hex; at least one record is
required, and the message is limited to 16 KiB.

See [`scripts/examples/`](scripts/examples) for ready-to-use tags.

## JitPack

The libraries are published from git tags. Add JitPack and depend on the modules
you need:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.igarciadelamo.nfc-test:nfc-test-api:0.1.0")
    debugImplementation("com.github.igarciadelamo.nfc-test:nfc-test:0.1.0")
}
```

`nfc-test` brings `nfc-test-api` transitively in debug builds. Keep the explicit
`nfc-test-api` dependency for release builds.

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

The `REFLECTION` tag strategy needs `hidden_api_policy=1` while the emulator
boots. Without it, the `REFLECTION` cases are skipped instead of failing.

<details>
<summary>Running managed devices one at a time</summary>

`maxConcurrentDevices=1` in `gradle.properties` keeps Gradle Managed Devices from
starting more than one emulator at a time. To run one module and one API per
command, use the per-API task names:

```bash
./gradlew :nfc-test:api30DebugAndroidTest
./gradlew :nfc-test:api34DebugAndroidTest
./gradlew :nfc-test:api36DebugAndroidTest
```

The same pattern applies to `:nfc-test-api`, `:sample` and `:sample-e2e`. The
`allDevicesGroupAndroidTest` aliases run a module across all three APIs.

</details>

## Documentation

| Document | Contents |
| --- | --- |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Module map, delivery flow and lifecycle. |
| [`docs/STEP2_RESULTS.md`](docs/STEP2_RESULTS.md) | Delivery and dispatch test results. |
| [`docs/COLD_START_RESULTS.md`](docs/COLD_START_RESULTS.md) | Cold-start implementation and results. |
| [`docs/SPIKE_RESULTS.md`](docs/SPIKE_RESULTS.md) | Tag fabrication spike. |
| [`docs/PARCEL_LAYOUTS.md`](docs/PARCEL_LAYOUTS.md) | `Tag` parcel layouts per API level. |

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
