# Architecture

## Modules

```
nfc-test/
├── nfc-test-api/   Android library · dev.nfctest.api
│   NfcReader, RealNfcReader, NfcReaders, TagCallback
│   → implementation() in the app · ships in RELEASE
│
├── nfc-test/       Android library · dev.nfctest · DEBUG ONLY
│   TagFactory (spike) · TagSpec/Parser · NfcTestReceiver
│   NfcTestInitProvider · ResumedActivityTracker
│   InjectableNfcReader · TagDispatcher
│   → debugImplementation() in the app
│
└── sample/         sample app · dev.nfctest.sample
    ReaderActivity · IntentActivity · ReceivedTags
    (does not reference dev.nfctest: only uses dev.nfctest.api)
```

Dependencies: `:sample → :nfc-test-api` (implementation) and `:sample → :nfc-test`
(debugImplementation); `:nfc-test → :nfc-test-api` (`api`).

## Flow

```
 adb shell am broadcast -a dev.nfctest.TAP -f 32 --es tag_b64 ... --es mode ...
        │
        ▼
 NfcTestReceiver            (in the app process, DUMP permission)
        │  TagSpecParser.parse(json)          -> TagSpec or TagSpecException (result 2)
        ▼
 TagDispatcher.dispatch(spec, mode)
        │  spec.toTag()  ->  TagFactory (PARCEL, AUTO)   [Tag Parcelable in process]
        │
        ├── reader      -> InjectableNfcReader.deliver(tag)
        │                     └─ executor "NfcTest-binder" -> TagCallback
        ├── foreground  -> activity.startActivity(intent + SINGLE_TOP)
        │                     └─ IntentActivity.onNewIntent
        ├── implicit    -> queryIntentActivities(NDEF → TECH → TAG)
        │                     └─ startActivity (same Activity in the foreground)
        └── auto        -> reader if active, else foreground, else implicit
        │
        ▼
 setResult(code, message, null)  ->  am broadcast prints result=/data=
```

## Why the receiver lives inside the app

`android.nfc.Tag` is `Parcelable` but it is built from a `Parcel` with hidden
fields. Injecting it from another process would require marshalling that state.

## Cold start without touching the app code

`NfcTestInitProvider` (`initOrder=1000`, `exported=false`) runs before any
component:

1. Registra `ResumedActivityTracker` (`ActivityLifecycleCallbacks`).
2. Crea `InjectableNfcReader` y lo instala en `NfcReaders.factory`.

In release the provider does not exist (the module is absent), so
`NfcReaders.get()` returns `RealNfcReader`.

## Delivery and lifecycle

- A single active callback: the one from the last Activity that called `enable`.
- `disable()` (the app's `onPause`) clears it; `onActivityDestroyed` also clears it.
- `deliver()` checks `activity.isDestroyed` before enqueuing.
- The injected callback runs on `NfcTest-binder`, never on the main thread.
