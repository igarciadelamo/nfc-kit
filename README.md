# nfc-test spike

Proof of concept: build an `android.nfc.Tag` with an `NdefMessage` without NFC hardware,
to inject it into emulators.

Requirements: JDK 17, Android SDK (platform-tools, emulator) and ~6 GB free for the
API 30/34/36 system images.

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew assembleDebug assembleDebugAndroidTest   # build
./gradlew allDevicesGroupAndroidTest               # API 30 + 34 + 36
./gradlew api34DebugAndroidTest                    # a single API
adb logcat -s NfcTestDiag NfcTestLimits            # diagnostics
```

Reports: `library/build/reports/androidTests/managedDevice/`

Documentation: [`docs/SPIKE_RESULTS.md`](docs/SPIKE_RESULTS.md) and
[`docs/PARCEL_LAYOUTS.md`](docs/PARCEL_LAYOUTS.md).
