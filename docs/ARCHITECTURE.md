# Architecture

## Modules

```
nfc-test/
├── nfc-test-api: Android library, es.igarciadelamo.nfctest.api
│   NfcReader, RealNfcReader, NfcReaders, TagCallback
│   → implementation() in the app · shipped in RELEASE
│
├── nfc-test: Android library, es.igarciadelamo.nfctest · DEBUG ONLY
│   TagFactory (spike) · TagSpec/Parser · NfcTestReceiver
│   NfcTestInitProvider · ResumedActivityTracker
│   InjectableNfcReader · TagDispatcher · NfcIntentResolver
│   NfcTestLaunchActivity (cold-start trampoline)
│   → debugImplementation() in the app
│
├── sample: demo app, es.igarciadelamo.nfctest.sample
│   ReaderActivity · IntentActivity · ReceivedTags
│   (does not reference es.igarciadelamo.nfctest; only es.igarciadelamo.nfctest.api)
│
└── sample-e2e: com.android.test, es.igarciadelamo.nfctest.sample.e2e
    ColdStartTest (UiAutomator, separate process)
    targetPackage = "es.igarciadelamo.nfctest.sample"
```

Dependencies: `:sample → :nfc-test-api` (implementation) and
`:sample → :nfc-test` (debugImplementation); `:nfc-test → :nfc-test-api`
(`api`). `:sample-e2e` does not depend on `:nfc-test`; it targets `:sample` and
uses UiAutomator.

## Flow

```
 adb shell am broadcast -a es.igarciadelamo.nfctest.TAP -f 32 --es tag_b64 ... --es mode ...
        │
        ▼
 NfcTestReceiver            (debug-only, android.permission.DUMP)
        │
        ▼
 TagDispatcher.dispatch(spec, mode)
        │
        ├── reader: InjectableNfcReader.deliver(tag)
        ├── foreground: startActivity(Intent)
        ├── implicit: startActivity(Intent)
        └── auto: reader if enabled, else foreground if an activity is resumed,
                  else implicit
        │
        ▼
 Result code and data are returned through the broadcast.
```

## Cold-start flow

```
 adb shell am start -n <pkg>/es.igarciadelamo.nfctest.NfcTestLaunchActivity \
     --es tag_b64 <b64> --es id <id>
        │
        ▼
 NfcTestLaunchActivity (debug-only, DUMP, translucent, noHistory)
        │
        ├── TagSpecParser.parse(json)
        ├── TagFactory (PARCEL or REFLECTION)
        ├── NfcIntentResolver.resolve(spec, tag)
        ├── startActivity(Intent + FLAG_ACTIVITY_NEW_TASK)
        └── finish() without animation
        │
        ▼
 Destination activity (onCreate)
        │
        ▼
 logcat: cold id=<id> result=<code> data="..."
```

The trampoline is a real activity, so launching it from the shell does not hit
the Android background-activity restriction. It builds the tag, resolves the
NDEF → TECH → TAG cascade and forwards the intent. The destination sees the
normal NFC extras and `path=onCreate` or `path=onNewIntent`. The script waits
for the `cold id=... result=...` logcat line and uses `am start` without `-W`
because `-W` waits for an activity that may never open when the destination is
missing.

## Why the receiver runs in the app process

`android.nfc.Tag` is `Parcelable`, but its hidden fields are populated by the
platform. A separate helper process cannot create a tag that the app can use
for discovery metadata without sharing the same process. The debug provider
installs the injectable reader before `Application.onCreate`, and the receiver
delivers the tag through the same process. The production reader remains the
real platform reader in release builds.

## Start without touching app code

`NfcTestInitProvider` (`initOrder=1000`, `exported=false`) runs before any
component:

1. Registers `ResumedActivityTracker` (`ActivityLifecycleCallbacks`).
2. Creates `InjectableNfcReader` and installs it in `NfcReaders.factory`.

In release the provider does not exist (the module is absent), so
`NfcReaders.get()` returns `RealNfcReader`.

## Delivery and lifecycle

- Only one callback is active: the last activity that called `enable`.
- `disable()` (app `onPause`) clears it; `onActivityDestroyed` also clears it.
- `deliver()` checks `activity.isDestroyed` before enqueueing.
- The injected callback runs on `NfcTest-binder`, never on the main thread.
