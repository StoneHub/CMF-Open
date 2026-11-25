# CMF Companion (native Android)

A Kotlin app that replaces Nothing X for the CMF Watch 3 Pro (and Watch Pro 2). It keeps one
Bluetooth LE connection to the watch in a foreground service and does everything the web app
does, without a browser:

| Feature | Status |
| --- | --- |
| Pair (factory `AT GETSECRET`), log in with the stored key, reconnect with backoff | ported from cmfkit, unit-tested against the simulator |
| Time sync on connect, on time-zone change, and every 15 min; battery | ported |
| Notifications from any app, per-app allow/mute list, calls shown as "Call: name" | new |
| Now playing (YouTube, podcasts, Spotify…) on the music card; card buttons control playback and volume | new; button code map unconfirmed, see below |
| Find my phone (rings even on silent) and find watch | new / ported |
| Weather tile from Open-Meteo, by location or a typed city | new; icon ids unconfirmed |
| Watch apps from the web store (`web/public/apps`), run unchanged in a hidden WebView; import your own `.js` | new |
| Install photo and structured watch faces (`.bin`) | ported |
| Watch as speaker: plain call link, or a pretend phone call through Telecom | experiment |

## Layout

* `protocol/` — pure Kotlin port of `src/cmfkit/protocol` and `session.py`. No Android
  dependencies. `./gradlew -PprotocolOnly :protocol:test` runs it on any JDK 17+, including
  byte-for-byte checks against `web/src/lib/__fixtures__/vectors.json` (made by the Python code).
* `app/` — the Android app (Compose UI, BLE transport, services). The web store's apps are copied
  into the APK at build time.

## Build

```sh
cd android
./gradlew :app:assembleDebug          # needs the Android SDK (ANDROID_HOME or local.properties)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/android.yml`) builds the APK on every push that touches `android/`; the
APK is attached to the run as the `cmf-companion-debug` artifact.

## First run

1. Nothing X and the watch can't share the connection. Force-stop Nothing X (or uninstall it)
   before choosing the watch here.
2. Allow Nearby devices, Notification access, notifications and "run in background" from the
   Watch tab's setup card.
3. Choose the watch, then accept the pairing prompt on the wrist. The key is stored, so later
   connections need no prompt.

## Things to check on the real watch

Everything below is logged on the Log tab and in `adb logcat -s CmfCompanion`.

* **Music button codes.** The `FFFF:A05D` code map is not confirmed. Press each control on the
  watch's music card and note the logged code; `Prefs.buttonCodes` holds the map.
* **Weather icons.** `WeatherSync.condition()` guesses the watch's icon ids.
* **Unknown frames on connect** (`FFFF:A06B` "en", `FFFF:A06A`, dial list) are logged and ignored.
* **Speaker.** Needs the watch connected for calls in Android's Bluetooth settings.
