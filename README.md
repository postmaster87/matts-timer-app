# Matt's Timer

Native Android gym timer. Countdown with presets + manual custom entry, plus a
stopwatch. Big targets, high contrast, one-tap repeats for back-to-back sets.

**Runs entirely on the phone.** The app declares exactly one permission —
`VIBRATE`. There is no `INTERNET` permission, so it cannot reach the network
even if it wanted to. Nothing loads, nothing syncs, nothing needs signal.

Install: `dist/MattsTimer.apk`  ·  Reading copy: `docs/TIMER.pdf`

## Layout

| Path | What |
|---|---|
| `android/` | The Android app (Kotlin, Gradle) |
| `android/app/src/main/java/com/matt/gymtimer/MainActivity.kt` | All UI + timer logic |
| `android/app/src/main/java/com/matt/gymtimer/Tones.kt` | Synthesised bell cues (PCM, no audio assets) |
| `android/app/src/main/res/layout/` | Portrait layout; `layout-land/` is the two-column landscape one |
| `dist/MattsTimer.apk` | Installable release build |
| `tools/make_android_icons.py` | Regenerates launcher icons (pure stdlib) |
| `index.html`, `sw.js`, `manifest.webmanifest`, `icons/` | Earlier browser version, superseded by the Android app |

## Behavior

**Presets** — `35s 45s 1m 5m 10m 20m 30m 60m` plus `CUSTOM`. The app always
opens on **35s**, regardless of what ran last.

**RESET restarts the selected preset and starts it immediately.** That is the
back-to-back set button: one tap between rounds, no second tap to start. Once
the timer has finished, both big buttons do this.

**Presets lock while the timer is running** (dimmed, inert) so a stray tap
mid-set cannot wipe a live timer. Pause or finish first to change duration.

**CUSTOM** opens a full-screen keypad, MM:SS, digits shift in from the right.
`SET & START` applies and starts in one tap. The last custom value is kept on
the tile for one-tap recall — but it is never auto-selected on launch.

**Finish** — screen goes red and flashes, `TIME`, and both large buttons
restart the set. A C-major chime rings twice; the phone vibrates.

**Sound** — soft bell tick at 3, 2, 1. Every tone is a sine with a soft attack
and a natural decay, synthesised at startup into PCM. Cues play on the alarm
stream so they carry over gym noise. Sound and vibration toggle independently
in the header and persist.

**Stopwatch** — second tab. START/STOP, LAP while running, RESET when stopped.
Laps show split and cumulative, newest first.

**Screen stays awake while anything is counting**, and releases as soon as it
stops. Rotation rebuilds the layout without disturbing a live set.

## Build

Needs JDK 17 and the Android SDK (compileSdk 35, build-tools 35). No AndroidX,
no Material library, no third-party dependencies at all.

```bash
cd android && ./gradlew assembleRelease
```

Output lands at `android/app/build/outputs/apk/release/app-release.apk`; copy it
to `dist/MattsTimer.apk` when shipping a new build.

`android/local.properties` must point at the SDK and is not committed:

```bash
echo "sdk.dir=C:\\\\Users\\\\Administrator\\\\AppData\\\\Local\\\\Android\\\\Sdk" > android/local.properties
```

## Install on the phone

With the phone plugged in over USB and USB debugging on:

```bash
adb install -r dist/MattsTimer.apk
```

Or copy `dist/MattsTimer.apk` to the phone over USB and tap it in Files
(allow "install unknown apps" for the file manager once).

The release build is signed with the local debug key — fine for sideloading to
your own device, not for Play Store distribution.
