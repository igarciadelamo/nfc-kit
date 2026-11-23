# Cold-start results

Date: 2026-11-23.
Environment: macOS arm64, JDK 21.0.13 (Temurin), Gradle 9.9.2, AGP 9.5.1,
`compileSdk`/`targetSdk` 37, GMD `Pixel 6` with AOSP image,
`maxConcurrentDevices=1`.

Commands run:

```bash
./gradlew assembleDebug assembleDebugAndroidTest assembleRelease testDebugUnitTest
./gradlew :sample-e2e:api30DebugAndroidTest
./gradlew :sample-e2e:api34DebugAndroidTest :sample-e2e:api36DebugAndroidTest
./gradlew :nfc-test:api30DebugAndroidTest :nfc-test-api:api30DebugAndroidTest \
    :sample:api30DebugAndroidTest
./gradlew :nfc-test:api34DebugAndroidTest :nfc-test:api36DebugAndroidTest \
    :nfc-test-api:api34DebugAndroidTest :nfc-test-api:api36DebugAndroidTest \
    :sample:api34DebugAndroidTest :sample:api36DebugAndroidTest
./gradlew allDevicesGroupAndroidTest verifyReleaseHasNoNfcTest
```

The GMD emulators were booted with `hidden_api_policy=1` applied over adb, as in step 2
(the `REFLECTION` tests in `:nfc-test` need it).

## 1. Cases API × scenario

| Case | API 30 | API 34 | API 36 | Observed path |
|---|---|---|---|---|
| 1. URI in cold start → `IntentActivity` with `path=onCreate`, `NDEF_DISCOVERED`, 2 records, correct UID | OK | OK | OK | `onCreate` |
| 2. Text in cold start → falls back to `TAG_DISCOVERED`, `path=onCreate` | OK | OK | OK | `onCreate` |
| 3. App in background (no `force-stop`) → tag arrives with 2 records | OK | OK | OK | `onCreate` (30/34/36) |
| 4. 5-byte UID → `result=2` and no Activity opened | OK | OK | OK | n/a |
| 5. No destination (`no_destination`) → `result=3` and no Activity opened | OK | OK | OK | n/a |
| 6. `cold id=<id> result=1` line matches the requested id | OK | OK | OK | `onCreate` |

## 2. `DUMP` permission in the trampoline

| API | `am start` from shell | Result |
|---|---|---|
| 30 | OK | OK |
| 34 | OK | OK |
| 36 | OK | OK |

## 3. `verifyReleaseHasNoNfcTest`

- Clean release: **OK**. The task prints
  `release APK (sample-release-unsigned.apk) and 3 merged manifest(s) are clean`.
- Deliberately putting `:nfc-test` into release: **expected FAIL**. The task detected
  `NfcTestReceiver`, `NfcTestInitProvider`, `NfcTestLaunchActivity`,
  `nfctest-init`, `android.permission.DUMP` in the merged manifests and
  `Ldev/nfctest/TagFactory;`, `NfcTestReceiver;`, `NfcTestInitProvider;`,
  `InjectableNfcReader;`, `NfcTestLaunchActivity;` and `NfcIntentResolver;` in the
  dex.
- Reverted: **OK**, the release APK is clean again.

## 4. Differences from the real system

- `getReferrer()` of the destination Activity will be the app itself (the trampoline
  launches it), not null as with the real system.
- The resulting task uses `NEW_TASK` like the system. `IntentActivity` is
  `singleTop`: with the app in the background (case 3) `onCreate` was observed because
  it was not the top Activity. A `singleTask` destination was not tested (the sample
  has none): **NOT VERIFIED**.
- The process starts with the trampoline as the first Activity:
  `Application.onCreate` and `NfcTestInitProvider` have already run before launching
  the destination (same as a real cold start).
- A freshly installed app or one in *stopped* state can still be started with
  `am start` (unlike the broadcast, it does not need `-f 32`).
- `am start` is used without `-W`: `-W` does not return when the trampoline closes
  without opening a destination (cases 4 and 5).
- Case 5 uses the debug extra `no_destination` because the sample always has a
  `TAG_DISCOVERED` filter; without that flag the cascade always resolves.
- `ACTION_TECH_DISCOVERED` still does not emulate XML tech-lists (action-only
  resolution).

## 5. Problems found

1. **`am start -W` hangs when the trampoline closes without opening a destination**
   (cases 4 and 5): it waits for an Activity that never becomes idle. Changed to
   `am start` without `-W`; the result is read from the logcat line.
2. **Stale GMD lock**: after killing a hung run, the file
   `~/.android/avd/gradle-managed/active_gradle_devices` still had
   `MDLockCount 1` and the next run waited 600 s. Fixed with
   `./gradlew cleanManagedDevices`.
3. **Case 5 needs a debug extra** (`no_destination`): the sample has a
   `TAG_DISCOVERED` filter that always resolves, so there is no valid tag that leaves
   the cascade without a destination.

## 6. Conclusion

**CONTINUE WITH LIMITATIONS.**

Cold start works on API 30, 34 and 36: the trampoline opens the destination Activity
with the tag in `onCreate` (also with the app in the background) and result codes
1/2/3 match the design. Limitations:

- `ACTION_TECH_DISCOVERED` only resolves by action; it does not evaluate XML tech-lists.
- Case 5 uses the debug extra `no_destination` because the sample always
  resolves `TAG_DISCOVERED`.
- `am start` is used without `-W` to avoid hanging when there is no destination.
- A `singleTask` destination was not tested: **NOT VERIFIED**.
- `getReferrer()` is not null and the mock Tag still has no NDEF I/O (known mock
  limitations).
