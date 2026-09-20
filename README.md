# Matt's Timer

Native Android gym timer. Countdown with presets and a tap-the-clock wheel
picker, plus a stopwatch. Big targets, high contrast, one-tap repeats for
back-to-back sets.

**Runs entirely on the phone.** There is no `INTERNET` permission, so it cannot
reach the network even if it wanted to. Nothing loads, nothing syncs, nothing
needs signal. The five permissions it does declare are `VIBRATE`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`
and `WAKE_LOCK` - all of them local to the phone, all of them there so a set
keeps running with the app closed [measured, 2026-09-20, read of the manifest].

Install: `dist/MattsTimer.apk`  ·  Reading copy: `docs/TIMER.pdf`

## Layout

| Path | What |
|---|---|
| `android/` | The Android app (Kotlin, Gradle) |
| `android/app/src/main/java/com/matt/gymtimer/MainActivity.kt` | The screen: layout, presets, picker, laps, rendering |
| `android/app/src/main/java/com/matt/gymtimer/TimerEngine.kt` | The timer itself: clock math, cues, ducking, state that outlives the screen |
| `android/app/src/main/java/com/matt/gymtimer/TimerService.kt` | Foreground service: the lock-screen notification and the wake lock |
| `android/app/src/main/java/com/matt/gymtimer/Tones.kt` | Synthesised bell cues (PCM, no audio assets) |
| `android/app/src/main/res/layout/` | Portrait layout; `layout-land/` is the two-column landscape one |
| `dist/MattsTimer.apk` | Installable release build |
| `tools/make_android_icons.py` | Regenerates launcher icons (pure stdlib) |
| `tools/make_pdf.py` | Regenerates `docs/TIMER.pdf` |

## Behavior

**Presets** — `35s 45s 1m 5m 10m 15m 20m 30m 60m`, nine tiles in a 3x3 grid
[measured, n=9]. The app always opens on **35s**, regardless of what ran last -
unless a set is still running, paused or finished, in which case it opens on
that set instead [design].

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

**Tap the clock to set a one-off time.** With the timer stopped - ready, paused
or finished - tapping the digits opens two wheels, minutes and seconds. Flick
them, or tap a number and type it. `SET & START` applies the value and starts
it in one tap, the same path RESTART takes; RESET and RESTART then return to
that value until a preset is tapped. CANCEL or the back button closes it. The
ready line says `TAP TO SET` as the reminder. Tapping the clock while the timer
is running, or in the stopwatch, does nothing [design]. The keypad screen and
the CUSTOM tile are gone.

**Finish** — screen goes red and flashes, `TIME`, and both large buttons
restart the set. A C-major chime rings twice; the phone vibrates.

**It keeps running with the app closed.** Starting a countdown or the stopwatch
starts a foreground service: the set stays alive with the app closed, the
screen off, or the app swiped out of recents, and it lands on the lock screen
as an ongoing notification [design; phone behavior verify, n=0]. The
notification counts down by itself (the system draws it, so nothing wakes the
phone once a second) and carries the buttons for that state - PAUSE and RESTART
while running, RESUME and RESTART while paused, RESTART at `TIME`, STOP for the
stopwatch. Tapping it opens the app on the live set. A partial wake lock is
held while the countdown runs, and for six seconds past the finish so the chime
and the buzz complete with the screen off [design]. The service stops itself
the moment nothing is live. Android 13 and up asks once, on the first start,
whether the app may post notifications; the timer runs either way [design].

**The music ducks at the end.** Three seconds from zero the app takes transient
audio focus, so whatever is playing in his headphones drops under the 3-2-1
ticks and the chime and stays down; it comes back up on its own the moment the
set is reset or restarted [design; his words: "gently increase back to where it
was when it resets or restrarts"]. Pausing hands it back too. The app never
writes a stream volume - it only asks the system to duck, which is what makes
the return gradual and what keeps every other app's volume his [measured: no
`setStreamVolume` or `adjustVolume` call exists in the source].

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
Laps show split and cumulative, newest first. A running stopwatch keeps running
with the app closed and shows on the lock screen too; laps are not kept if the
process is killed [design].

**Screen stays awake while anything is counting**, and releases as soon as it
stops. Rotation rebuilds the layout without disturbing a live set, including an
open wheel picker.

**If the app is killed** — the countdown, the stopwatch and the selected preset
are written to the phone on every change, so a restarted process picks the set
back up where it was. A reboot clears it: the app opens fresh on 35s [design].

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
