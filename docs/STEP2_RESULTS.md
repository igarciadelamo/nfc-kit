# Step 2 results

Date: 2026-11-20.
Environment: macOS arm64, JDK 17.0.21.1, Gradle 9.9.2, AGP 9.5.1,
`compileSdk`/`targetSdk` 37, GMD `Pixel 6` with AOSP images,
`maxConcurrentDevices=1`.

Commands run:

```bash
./gradlew assembleDebug assembleDebugAndroidTest assembleRelease
./gradlew testDebugUnitTest
./gradlew allDevicesGroupAndroidTest                # three modules × 3 APIs
./gradlew :sample:verifyReleaseHasNoNfcTest
```

## 1. Suites

| Module | API 30 | API 34 | API 36 |
| --- | --- | --- | --- |
| `:nfc-test` (spike, no regression) | 45/45 OK | 45/45 OK | 45/45 OK |
| `:sample` (e2e) | 13/13 OK | 13/13 OK | 13/13 OK |
| `:nfc-test-api` (reader) | 2/2 OK | 2/2 OK | 2/2 OK |

The root alias `./gradlew allDevicesGroupAndroidTest` runs the three modules and
finished with `BUILD SUCCESSFUL` (the results in the table).

JVM tests (`testDebugUnitTest`): `TagSpecParserTest` 11/11 and
`NfcReadersDefaultTest` 2/2.

The 45 spike tests ran with `hidden_api_policy=1` applied over adb while the
emulator booted, as in step 1 (the `REFLECTION` tests cannot see
`Tag.createMockTag` without that setting). `PARCEL` and `AUTO` do not need it.

## 2. API × path × scenario table

Every scenario uses the same command a person would use:
`adb shell am broadcast -a dev.nfctest.TAP -p dev.nfctest.sample -f 32 ...` from
`UiAutomation.executeShellCommand` (shell identity, so the `DUMP` permission is
also exercised).

| Scenario | Path | API 30 | API 34 | API 36 |
| --- | --- | --- | --- | --- |
| 2-record tag reaches `onNewIntent` with `ACTION_NDEF_DISCOVERED`, usable `EXTRA_TAG` (2 records), `EXTRA_ID` and `data=https://txoka.app` | foreground | OK | OK | OK |
| 2-record tag reaches the callback | reader | OK | OK | OK |
| The callback runs on a non-main thread (`NfcTest-binder`) | reader | OK | OK | OK |
| First record is a URI: matches the `NDEF_DISCOVERED` filter and opens `IntentActivity` | implicit | OK | OK | OK |
| First record is text: falls back to `TAG_DISCOVERED` | implicit | OK | OK | OK |
| No foreground activity: `mode=implicit` → `result=3` and no delivery after 2 s | implicit | OK | OK | OK |
| `auto` with reader mode active uses reader | auto | OK | OK | OK |
| `auto` without reader and with `IntentActivity` on top uses foreground | auto | OK | OK | OK |
| Invalid JSON or invalid UID → `result=2` | any | OK | OK | OK |
| `mode=reader` without an active reader → `result=4` | reader | OK | OK | OK |
| `mode=foreground` without a foreground activity → `result=3` | foreground | OK | OK | OK |
| After `onPause`/`disable()` the reader stops receiving → `result=4` | reader | OK | OK | OK |
| 7-byte UID and 3 records (URI + MIME vcard + external) | foreground | OK | OK | OK |
| `recreate()` leaves no dangling callback and the reader keeps delivering | reader | OK | OK | OK |
| One tag per reader does not also produce an Intent delivery (single entry) | reader | OK | OK | OK |

Logcat evidence (`adb logcat -s NfcTest`), for example on API 30:

```
NfcTest : request mode=reader -> result=1 data="dispatched via reader"
NfcTest : request mode=implicit -> result=1 data="dispatched via implicit NDEF_DISCOVERED"
NfcTest : request mode=implicit -> result=1 data="dispatched via implicit TAG_DISCOVERED"
NfcTest : request mode=implicit -> result=3 data="app in background: implicit needs a foreground activity"
NfcTest : request mode=foreground -> result=3 data="no foreground activity"
NfcSample : created=1 live=1 path=onNewIntent action=android.nfc.action.NDEF_DISCOVERED data=https://txoka.app uid=01020304 records=[uri: https://txoka.app, text: hello]
```

## 3. `DUMP` permission

`NfcTestReceiver` is declared `exported=true` with
`android:permission="android.permission.DUMP"`. On all three APIs the broadcast
sent with shell identity was delivered (`result=1` in the modes that have a
destination), so the shell holds the permission and the no-permission fallback
was not needed.

`scripts/nfc-tap.sh` was also tested against a manually started API 30 emulator
with the debug APK installed:

```
Broadcast completed: result=1, data="dispatched via reader"             (exit 0)
Broadcast completed: result=1, data="dispatched via foreground intent"  (exit 0)
```

Sending from another app without `DUMP` was not tested; because of the
`android:permission` semantics on a receiver, the framework rejects it before
`onReceive`. **NOT VERIFIED** empirically with a second app.

## 4. `verifyReleaseHasNoNfcTest`

The task builds `:sample:assembleRelease` and checks:

- merged release manifests: no `NfcTestReceiver`, `NfcTestInitProvider`,
  `nfctest-init` or `android.permission.DUMP`;
- APK `classes*.dex`: no `Ldev/nfctest/TagFactory;`,
  `Ldev/nfctest/NfcTestReceiver;`, `Ldev/nfctest/NfcTestInitProvider;` or
  `Ldev/nfctest/InjectableNfcReader;`.

Result: **OK** (clean release). It is wired into `check`.

About `NfcReaders.get()` in release: `NfcReadersDefaultTest` (JVM, in
`:nfc-test-api`) checks that the default factory returns `RealNfcReader` and that
it is only replaced when someone does so explicitly; the replacing provider does
not exist in release (verified above).

## 5. Edge cases and risks

| Risk | Result |
| --- | --- |
| Freshly installed app in the *stopped* state does not receive broadcasts without `-f 32` | Verified on API 30 (AOSP emulator): `pm clear` → `stopped=true`; broadcast without the flag → `result=0`; with `-f 32` → `result=3` (received, no activity). The script uses `-f 32`. |
| Android 10+ blocks launching activities from the background | `foreground`/`implicit` launch from the resumed activity. With no foreground activity, both return `result=3`; `implicit` adds the message "app in background: implicit needs a foreground activity". Verified on API 30/34/36: nothing is delivered after 2 s. |
| Android 14+ blocks implicit intents to non-exported components | `IntentActivity` is `exported=true`, as real dispatch requires. Verified on API 34/36. |
| Double dispatch (reader + intent) | Verified: a single delivery per tag in reader mode. |
| Callback thread | Verified: `NfcTest-binder`, never `main`. |
| Lifecycle: activity destroyed/recreated | Verified: `disable()` and `onActivityDestroyed` clear the callback; `recreate()` registers again and delivers. |
| `DUMP` permission on any API | Verified on 30, 34 and 36; no fallback was needed. |
| `ACTION_TECH_DISCOVERED` matching by tech-list XML | **Not emulated**: it only resolves by action. The sample has no TECH filter, so the text case falls back to `TAG_DISCOVERED`. |

## 6. Problems found and resolution

1. **`ActivityScenario.close()` timed out** when the dispatcher had opened
   `IntentActivity` on top: the scenario activity stayed in `PAUSED` and never
   reached `DESTROYED`. Every resumed/started/paused activity is finished first
   through `ActivityLifecycleMonitorRegistry`, then the scenario is closed.
2. **The launch intent (`ACTION_MAIN`) was recorded as a tag**: the first
   `await` returned that delivery. `IntentActivity` now ignores any action that
   does not start with `android.nfc.action.`.
3. **API 30 returns `Parcelable[]`**, not `NdefMessage[]`, from
   `getParcelableArrayExtra`. The direct cast threw `ClassCastException`; it is
   filtered with `filterIsInstance<NdefMessage>()` only below API 33.
4. **GMD cannot be configured from a script plugin** (`apply(from=...)`) because
   the script classpath does not include the AGP API. It moved to a convention
   in the root `build.gradle.kts` (`subprojects` + `pluginManager.withPlugin`),
   without `buildSrc` and without duplicating the block.
5. **`devices[...]` no longer exists in AGP 9.5** (it came from step 1): the
   references returned by `localDevices.create(...)` are used.
6. **`implicit` without a resumed activity returned a false-positive `result=1`**:
   it called `context.startActivity` with `NEW_TASK` and Android 10+ blocked it
   silently. Now, if the cascade resolves but there is no resumed activity, it
   returns `result=3` with "app in background: implicit needs a foreground
   activity"; `startActivity` catches `ActivityNotFoundException` and
   `SecurityException` (result 5). Success messages become "dispatched via
   implicit ..." because only the Intent is launched, not processed.

## 7. Limitations

- The injected tag works for discovery (extras and Intent). `connect()`,
  `readNdefMessage()` and `writeNdefMessage()` still fail as in step 1: there is
  no `INfcTag`.
- `implicit` approximates the system cascade; it does not evaluate tech-list XML
  or priorities between apps. It only resolves against its own package. It
  requires a resumed activity, like `foreground`; without one it returns
  `result=3`.
- The spike's `REFLECTION` still needs relaxed hidden API.
- The debug module must not be published: `verifyReleaseHasNoNfcTest` prevents it
  in the sample, but a consuming app must use `debugImplementation`.

## 8. Conclusion

**CONTINUE WITH LIMITATIONS.**

The three paths (foreground, reader and implicit) and `auto` work on API 30, 34
and 36 with a single `adb shell am broadcast`, without UI or a companion app, and
the release stays clean. The limitations are those of the mock Tag itself (no
NDEF I/O) and those of the emulation of the implicit cascade / background launch.
