# Step 2 results

Date: 2026-11-20.
Environment: macOS arm64, JDK 17.0.21.1, Gradle 9.9.2, AGP 9.5.1,
`compileSdk`/`targetSdk` 37, GMD `Pixel 6` with AOSP image,
`maxConcurrentDevices=1`.

Commands run:

```bash
./gradlew assembleDebug assembleDebugAndroidTest assembleRelease
./gradlew testDebugUnitTest
./gradlew :nfc-test:allDevicesGroupAndroidTest        # 45 spike tests × 3 APIs
./gradlew :nfc-test-api:api30DebugAndroidTest         # NfcReader on an emulator
./gradlew :sample:allDevicesGroupAndroidTest          # e2e × 3 APIs
./gradlew :sample:verifyReleaseHasNoNfcTest
```

## 1. Suites

| Module | API 30 | API 34 | API 36 |
|---|---|---|---|
| `:nfc-test` (spike, no regression) | 45/45 OK | 45/45 OK | 45/45 OK |
| `:sample` (end to end) | 12/12 OK | 12/12 OK | 12/12 OK |
| `:nfc-test-api` | 2/2 OK | not run | not run |

Tests JVM (`testDebugUnitTest`): `TagSpecParserTest` 11/11 y
`NfcReadersDefaultTest` 2/2.

The 45 spike tests ran with `hidden_api_policy=1` applied over adb while the
emulator booted, as in step 1. The `REFLECTION` tests need it.

## 2. API × path × scenario table

Every scenario is exercised with the same command a person would use:
`adb shell am broadcast -a dev.nfctest.TAP -p dev.nfctest.sample -f 32 ...`.

| Scenario | Path | API 30 | API 34 | API 36 |
|---|---|---|---|---|
| 2-record tag reaches `onNewIntent` with `ACTION_NDEF_DISCOVERED`, usable `EXTRA_TAG`, `EXTRA_ID` and `data=https://txoka.app` | foreground | OK | OK | OK |
| 2-record tag reaches the callback | reader | OK | OK | OK |
| Callback runs on a non-main thread (`NfcTest-binder`) | reader | OK | OK | OK |
| First URI record: resolves the `NDEF_DISCOVERED` filter and opens `IntentActivity` | implicit | OK | OK | OK |
| First text record: falls back to `TAG_DISCOVERED` | implicit | OK | OK | OK |
| `auto` with an active reader mode uses reader | auto | OK | OK | OK |
| `auto` without reader and with `IntentActivity` on top uses foreground | auto | OK | OK | OK |
| Invalid JSON or invalid UID → `result=2` | any | OK | OK | OK |
| `mode=reader` without an active reader → `result=4` | reader | OK | OK | OK |
| `mode=foreground` without a foreground Activity → `result=3` | foreground | OK | OK | OK |
| After `onPause`/`disable()` the reader stops receiving → `result=4` | reader | OK | OK | OK |
| 7-byte UID and 3 records (URI + MIME vcard + external) | foreground | OK | OK | OK |
| `recreate()` of the Activity leaves no stuck callback and the reader keeps delivering | reader | OK | OK | OK |
| One tag per reader does not also produce an Intent delivery (single entry) | reader | OK | OK | OK |

Logcat evidence (`adb logcat -s NfcTest`), for example on API 30:

```
NfcTest : request mode=reader -> result=1 data="delivered via reader"
NfcTest : request mode=implicit -> result=1 data="delivered via implicit NDEF_DISCOVERED"
NfcTest : request mode=implicit -> result=1 data="delivered via implicit TAG_DISCOVERED"
NfcTest : request mode=foreground -> result=3 data="no foreground activity"
NfcTest : intent action=android.nfc.action.NDEF_DISCOVERED data=https://txoka.app uid=01020304
           records=[uri: https://txoka.app, text: hola]
```

## 3. `DUMP` permission

`NfcTestReceiver` is declared `exported=true` with
`android:permission="android.permission.DUMP"`. The broadcast sent from the shell
was accepted on all three APIs.

In addition, `scripts/nfc-tap.sh` was tested against a manually started API 30
emulator:

```
Broadcast completed: result=1, data="delivered via reader"            (exit 0)
Broadcast completed: result=1, data="delivered via foreground intent"  (exit 0)
```

Sending from another app without `DUMP` was not tested; by the semantics of
`android:permission` the framework rejects it before `onReceive`.
**NOT VERIFIED** empirically with a second app.

## 4. `verifyReleaseHasNoNfcTest`

The task builds `:sample:assembleRelease` and checks:

- merged release manifests: no `NfcTestReceiver`, `NfcTestInitProvider`,
  `nfctest-init` or `android.permission.DUMP`;
- `classes*.dex` of the APK: no `Ldev/nfctest/TagFactory;`,
  `Ldev/nfctest/NfcTestReceiver;`, `Ldev/nfctest/NfcTestInitProvider;`,
  `InjectableNfcReader;` or `NfcIntentResolver;`.

Result: **OK** (clean release). It is wired into `check`.

About `NfcReaders.get()` in release: `NfcReadersDefaultTest` (JVM, in
`:nfc-test-api`) checks that the default factory returns `RealNfcReader` and
that only an explicit call replaces it.

## 5. Edge cases and risks

| Risk | Result |
|---|---|
| Freshly installed app in *stopped* state: does not receive broadcasts without `-f 32` | Verified on API 30 (AOSP emulator): `pm clear` → `stopped=true`; broadcast without flag → `result=0`; with `-f 32` → `result=3` (received, no Activity). The script uses `-f 32`. |
| Android 10+ blocks starting Activities from the background | The `foreground`/`implicit` modes start from the resumed Activity. Without a foreground Activity, `foreground` returns 3. `implicit` tries `context.startActivity` with `NEW_TASK`; on the background Android may block it and the code would return 1 even though nothing opens: **NOT VERIFIED** and documented as a limitation. |
| Android 14+ blocks implicit intents to non-exported components | `IntentActivity` is `exported=true`, as real dispatch requires. Verified on API 34/36. |
| Double dispatch (reader + intent) | Verified: one delivery per tag in reader mode. |
| Callback thread | Verified: `NfcTest-binder`, never `main`. |
| Lifecycle: Activity destroyed/recreated | Verified: `disable()` and `onActivityDestroyed` clear the callback; `recreate()` re-registers and delivers. |
| `DUMP` permission on some API | Verified on 30, 34 and 36; no fallback was needed. |
| `ACTION_TECH_DISCOVERED` matching by XML tech-list | **Not emulated**: resolves by action only. The sample has no TECH filter, so the text case falls back to `TAG_DISCOVERED`. |

## 6. Problems found and resolution

1. **`ActivityScenario.close()` timed out** when the dispatcher had opened
   `IntentActivity` on top: the scenario's Activity stayed `PAUSED` and never reached
   `DESTROYED`. The fix finishes every resumed/started/paused Activity first via
   `ActivityLifecycleMonitorRegistry` and then closes the scenario.
2. **The launch `Intent` (`ACTION_MAIN`) was registered as a tag**: the first
   `await` returned that delivery. `IntentActivity` now ignores any action that does
   not start with `android.nfc.action.`.
3. **API 30 returns `Parcelable[]`, not `NdefMessage[]`**, in
   `getParcelableArrayExtra`. The direct cast threw `ClassCastException`; the fix
   filters with `filterIsInstance<NdefMessage>()` below API 33.
4. **GMD cannot be configured from a script plugin** (`apply(from=...)`) because the
   script classpath does not include the AGP API. Moved to a convention in the root
   `build.gradle.kts` (`subprojects` + `pluginManager.withPlugin`), without
   `buildSrc` and without duplicating the block.
5. **`devices[...]` no longer exists in AGP 9.5** (it came from step 1): the
   references returned by `localDevices.create(...)` are used.

## 7. Limitations

- The injected tag serves discovery (extras and Intent). `connect()`,
  `readNdefMessage()` and `writeNdefMessage()` keep failing as in step 1:
  there is no `INfcTag`.
- `implicit` approximates the system cascade; it does not evaluate XML tech-lists or
  priorities between apps. It only resolves against its own package.
- The spike's `REFLECTION` still needs relaxed hidden API.
- The debug module must not be published: `verifyReleaseHasNoNfcTest` prevents it in
  the sample, but a consuming app must use `debugImplementation`.

## 8. Conclusion

**CONTINUE WITH LIMITATIONS.**

The three paths (foreground, reader and implicit) and `auto` work on API 30, 34 and
36 with a single `adb shell am broadcast`, without UI or a companion app, and the
release stays clean. The limitations are those of the mock Tag itself (no NDEF I/O)
and those of the implicit cascade / background-start emulation.
