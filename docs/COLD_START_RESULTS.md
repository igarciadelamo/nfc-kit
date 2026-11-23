# Cold-start results

Date: 2026-11-23.
Environment: macOS arm64, JDK 21.0.13 (Temurin), Gradle 9.9.2, AGP 9.5.1,
`compileSdk`/`targetSdk` 37, GMD `Pixel 6` with AOSP images,
`maxConcurrentDevices=1`.

Commands run:

```bash
./gradlew assembleDebug assembleDebugAndroidTest assembleRelease testDebugUnitTest
./gradlew :sample-e2e:api30DebugAndroidTest
./gradlew :sample-e2e:api34DebugAndroidTest :sample-e2e:api36DebugAndroidTest
./gradlew :sample-e2e:api30DebugAndroidTest :nfc-test:api30DebugAndroidTest
./gradlew :sample-e2e:api34DebugAndroidTest :sample-e2e:api36DebugAndroidTest \
    :nfc-test:api34DebugAndroidTest :nfc-test:api36DebugAndroidTest
./gradlew :nfc-test:api30DebugAndroidTest :nfc-test-api:api30DebugAndroidTest \
    :sample:api30DebugAndroidTest
./gradlew :nfc-test:api34DebugAndroidTest :nfc-test:api36DebugAndroidTest \
    :nfc-test-api:api34DebugAndroidTest :nfc-test-api:api36DebugAndroidTest \
    :sample:api34DebugAndroidTest :sample:api36DebugAndroidTest
./gradlew allDevicesGroupAndroidTest verifyReleaseHasNoNfcTest
```

GMD emulators were booted with `hidden_api_policy=1` applied over adb during
boot, as in step 2 (the `:nfc-test` REFLECTION tests need it).

`scripts/nfc-tap.sh --cold` was tested against the API 36 AVD:
`pid before=2205 after=2254` and `cold id=... result=1`.

## 1. API × scenario cases

| Case | API 30 | API 34 | API 36 | Observed path |
| --- | --- | --- | --- | --- |
| 1. Cold URI → `IntentActivity` with `path=onCreate`, `NDEF_DISCOVERED`, 2 records, correct UID | OK | OK | OK | `onCreate` |
| 2. Cold text → falls back to `TAG_DISCOVERED`, `path=onCreate` | OK | OK | OK | `onCreate` |
| 3. App in background (no `force-stop`) → the tag arrives with 2 records | OK | OK | OK | `onCreate` |
| 4. 5-byte UID → `result=2` and no activity opened | OK | OK | OK | n/a |
| 5. No destination (`no_destination`) → `result=3` and no activity opened | OK | OK | OK | n/a |
| 6. The `cold id=<id> result=1` line matches the id sent from the shell | OK | OK | OK | n/a |
| 7. PID: `force-stop` kills the process and the cold start uses a new PID | OK | OK | OK | `onCreate` |
| 8. `IntentActivity` already on top → `onNewIntent`, no duplicate instance | OK | OK | OK | `onNewIntent` |

Case 7 evidence (logcat `NfcE2E`): API 30 `2103 → 2145`, API 34 `2468 → 2524`,
API 36 `2526 → 2572`. No PID remains after `force-stop`.

Case 8 evidence: `NfcSample: created=1 live=1 path=onNewIntent` on all three
APIs; the instance is not duplicated.

Case 5 is also covered without the flag by
`NfcIntentResolverTest.cascadeReturnsNullWithoutNfcFilters` (`:nfc-test`): the
test APK has no NFC filters and `resolve()` returns `null`.

## 2. Regression

| Module | API 30 | API 34 | API 36 |
| --- | --- | --- | --- |
| `:nfc-test` | 46/46 | 46/46 | 46/46 |
| `:nfc-test-api` | 2/2 | 2/2 | 2/2 |
| `:sample` | 13/13 | 13/13 | 13/13 |
| `:sample-e2e` | 8/8 | 8/8 | 8/8 |

## 3. `DUMP` permission on the trampoline

| API | `am start` from shell | Result |
| --- | --- | --- |
| 30 | OK | OK |
| 34 | OK | OK |
| 36 | OK | OK |

## 4. `verifyReleaseHasNoNfcTest`

- Clean release: **OK**. The task prints
  `release APK (sample-release-unsigned.apk) and 3 merged manifest(s) are clean`.
- Deliberate failure: adding `:nfc-test` to the release variant produces the
  expected **FAIL**. The task detects `NfcTestReceiver`, `NfcTestInitProvider`,
  `NfcTestLaunchActivity`, `nfctest-init` and `android.permission.DUMP` in the
  merged manifests, and `Les/igarciadelamo/nfctest/TagFactory;`, `NfcTestReceiver;`,
  `NfcTestInitProvider;`, `InjectableNfcReader;`, `NfcTestLaunchActivity;` and
  `NfcIntentResolver;` in the dex. Reverted afterwards; the release APK is clean
  again.

## 5. Differences from the real platform

- `getReferrer()` on the destination activity is the app itself (the trampoline
  starts it), not null as with the real platform.
- The resulting task uses `NEW_TASK` like the platform. `IntentActivity` is
  `singleTop`: with `ReaderActivity` on top (case 3) `onCreate` was observed;
  with the app in the background and `IntentActivity` on top (case 8)
  `onNewIntent` was observed and the instance was not duplicated
  (`created=1 live=1`). A `singleTask` destination was not tested:
  **NOT VERIFIED**.
- The process starts with the trampoline as the first activity:
  `Application.onCreate` and `NfcTestInitProvider` have already run before the
  destination is launched (same as a real cold start).
- A freshly installed app in the *stopped* state can be started with `am start`
  (unlike a broadcast, it does not need `-f 32`).
- `am start` is used without `-W`: `-W` does not return when the trampoline
  closes without opening a destination (cases 4 and 5).
- Case 5 uses the debug extra `no_destination` because the sample always has a
  `TAG_DISCOVERED` filter; without that flag the cascade always resolves.
- `ACTION_TECH_DISCOVERED` still does not emulate tech-list XML (action-only
  resolution).

## 6. Problems found

1. **`am start -W` hangs when the trampoline closes without opening a
   destination** (cases 4 and 5): it waits for an activity that never becomes
   idle. Switched to `am start` without `-W`; the result is read from the logcat
   line.
2. **Stale GMD lock**: after killing a hung run, the
   `~/.android/avd/gradle-managed/active_gradle_devices` file still had
   `MDLockCount 1` and the next run waited 600 s. Resolved with
   `./gradlew cleanManagedDevices`. It happened again during the closing run
   when `:sample-e2e` and `:sample` were launched in the same command; they were
   run afterwards in series, one module/API per command.
3. **Case 5 needs a debug extra** (`no_destination`): the sample has a
   `TAG_DISCOVERED` filter that always resolves, so there is no valid tag that
   leaves the cascade without a destination.
4. **Incompatible `dev30` snapshot**: the emulator failed with
   `The snapshot requires the feature: 54 (GLESDynamicVersion)`. The managed AVD
   was deleted and GMD recreated it:
   ```bash
   rm -rf ~/.android/avd/gradle-managed/dev30_default_arm64-v8a_Pixel_6.avd \
     ~/.android/avd/gradle-managed/dev30_default_arm64-v8a_Pixel_6.ini \
     ~/.android/avd/gradle-managed/dev30_default_arm64-v8a_Pixel_6.lock
   ```
   `./gradlew cleanManagedDevices` was also run for the stale lock.
5. **`:nfc-test` REFLECTION needs `hidden_api_policy=1`**: applied during each
   GMD boot from the host:
   ```bash
   adb shell settings put global hidden_api_policy 1
   adb shell settings put global hidden_api_policy_p_apps 1
   adb shell settings put global hidden_api_policy_pre_p_apps 1
   ```

## 7. Conclusion

**CONTINUE WITH LIMITATIONS.**

Cold start works on API 30, 34 and 36: the trampoline opens the destination
activity with the tag in `onCreate` (also with the app in the background), and
result codes 1/2/3 match the design. Case 7 shows that `force-stop` kills the
process and the cold start uses a new PID; case 8 shows that with
`IntentActivity` already on top the tag arrives via `onNewIntent`. Case 5 is
also covered without the flag by `NfcIntentResolverTest`. Limitations:

- `ACTION_TECH_DISCOVERED` only resolves by action; it does not evaluate
  tech-list XML.
- Case 5 uses the debug extra `no_destination` because the sample always
  resolves `TAG_DISCOVERED`.
- `am start` is used without `-W` to avoid hanging when there is no destination.
- A `singleTask` destination was not tested: **NOT VERIFIED**.
- `getReferrer()` is non-null and the mock Tag still has no NDEF I/O (known mock
  limitations).
