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
| `tools/make_pdf.py` | Regenerates `docs/TIMER.pdf` |

## Behavior

**Presets** — `35s 45s 1m 5m 10m 20m 30m 60m` plus `CUSTOM`. The app always
opens on **35s**, regardless of what ran last.

**Three buttons, left to right:**

| Button | Does |
|---|---|
| **START** / PAUSE / RESUME / GO AGAIN | The primary control |
| **RESET** | Back to the preset time, **stopped**. Never starts anything |
| **RESTART** | Back to the preset time **and runs** - the back-to-back set button |

RESTART is the one that matters between rounds: one tap, no second tap to
start. In the stopwatch the middle button is still RESET; the right one is LAP.

**Presets lock while the timer is running** (dimmed, inert) so a stray tap
mid-set cannot wipe a live timer. Pause or finish first to change duration.

**CUSTOM** opens a full-screen keypad, MM:SS, digits shift in from the right.
`SET & START` applies and starts in one tap. The last custom value is kept on
the tile for one-tap recall — but it is never auto-selected on launch.

**Finish** — screen goes red and flashes, `TIME`, and both large buttons
restart the set. A C-major chime rings twice; the phone vibrates.

**Sound** — three voices, cycled by tapping the header button, which always
shows the current one:

| Voice | Character |
|---|---|
| **BELL** | C major arpeggio landing on a full triad. Warm, the default |
| **CHIME** | Falling G-E-C resolving to an open fifth. Calmer, longer ring |
| **PULSE** | Three clipped tones then a high hold. Cuts through a loud gym |

A fourth tap mutes it (**MUTE**). Each selection plays a short preview. Soft
tick at 3, 2, 1 in the selected voice. Every tone is a sine with a soft attack
and a natural decay, synthesised at startup into PCM — no audio assets. Cues
play on the alarm stream so they carry over gym noise. Changing the voice or
muting mid-set takes effect immediately, with no restart. Vibration toggles
separately. Both settings persist.

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
