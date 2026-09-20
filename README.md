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

**Tap the clock to set a one-off time.** His words, 2026-09-20: *"give me the
ability to touch the clock when it is not running and manual enter a time or
scroll - should be quick, simple and easy to interface with"*. With the timer
stopped - ready, paused or finished - tapping the digits opens two wheels,
minutes and seconds. Flick them, or tap a number and type it. `SET & START`
applies the value and starts it in one tap, the same path RESTART takes; RESET
and RESTART then return to that value until a preset is tapped. CANCEL or the
back button closes it. The ready line says `TAP TO SET` as the reminder.
Tapping the clock while the timer is running, or in the stopwatch, does nothing
[design]. The keypad screen and the CUSTOM tile are gone.

**Typing raises a number pad, and its check key sets the time and starts it.**
One key, no reaching for `SET & START` after it [design]. Typing `12` into the
seconds wheel still means 12, and minutes still run 0-99. Both buttons stay
above the keyboard while it is up: the window is padded by whichever is taller,
the navigation bar or the keyboard, so the wheels are what gives - they can
shrink or clip in landscape - and the buttons never do [design]. Closing the
picker any way at all drops the keyboard with it, and at `0:00` the check key
just commits the value and drops the keyboard, because there is nothing to
start [design]. Before this the seconds wheel raised the full QWERTY keyboard
and it covered CANCEL and `SET & START` [measured, 2026-09-20, n=1 on his
phone]. [phone behavior verify, n=0]

**Finish** — the digits go red, the line reads `TIME`, the phone vibrates, and
both large buttons restart the set. **The stage flashes for exactly as long as
the chime is repeating**: when the repeats end - on their own at two minutes, or
because he stopped them - the flash stops with them and the screen sits still on
red `TIME` [design]. A finished set picked back up after the process was killed
shows that same still red `TIME`, no flash, no sound - as long as it finished
inside the last ten minutes; past that it is dropped, see **If the app is
killed** [design].

**PAUSE on the last instant is a finish.** If the clock has already hit zero by
the time the tap lands, the set rings like any other finish instead of freezing
at `PAUSED 0:00`, which was a state START refused to leave [design].

**The chime repeats at `TIME`, and gets louder each time.** His words,
2026-09-20: *"Can you gradually increase the timer volume once it has
expired"*. The first chime is soft, each repeat is louder, and it keeps going
until he stops it — **RESTART**, **RESET**, a preset, `SET & START`, the
lock-screen button, **tapping either tab, or starting the stopwatch** — so a set
that ends while he is under a bar does not go unheard.

Tapping a tab or starting the stopwatch **only silences it**: the set stays on
`TIME`, and the music stays ducked until RESET or RESTART, exactly as it does
when the two minutes run out on their own [design]. Rotating the phone does not
silence it [design].

| | |
|---|---|
| Gain, chimes 1-5 | 30%, 47.5%, 65%, 82.5%, 100% — then 100% for every later one [design] |
| Spacing | the chime's own ring-out plus a 1-second gap [design] |
| In practice | every 4.1 s (BELL), 4.9 s (CHIME), 5.0 s (PULSE) [inferred from the rendered cue lengths, n=3 voices] |
| It stops itself | no repeat starts more than 120 s after the finish — 30 chimes on BELL, 25 on CHIME and PULSE [inferred, n=3] |

"100%" means **as loud as his alarm volume already is**. The app scales only
its own cue; it never writes a stream volume, so nothing moves the phone's
sliders [measured: no `setStreamVolume`, `adjustVolume` or
`adjustStreamVolume` call exists in the source]. The buzz fires with every
chime. MUTE silences the chimes and the buzz goes on; turning vibration off
stops the buzz and the chimes go on; with both off the sequence is silent and
still ends at two minutes. Changing voice or muting mid-ring takes effect on
the next repeat [design]. When the two minutes run out on their own the clock
stays on `TIME` and the music stays ducked — only RESET or RESTART hands that
back [design]. A set that ran out while the process was dead does not ring
when the app reopens: it is history, and it shows `TIME` without a sound -
if it ran out more than ten minutes ago it is not shown at all [design].
[phone behavior verify, n=0]

**It keeps running with the app closed.** Starting a countdown or the stopwatch
starts a foreground service: the set stays alive with the app closed, the
screen off, or the app swiped out of recents, and it lands on the lock screen
as an ongoing notification [design; phone behavior verify, n=0]. The
notification counts down by itself (the system draws it, so nothing wakes the
phone once a second) and carries the buttons for that state - PAUSE and RESTART
while running, RESUME and RESTART while paused, RESTART at `TIME`, STOP for the
stopwatch. Tapping it opens the app on the live set. A partial wake lock is
held while the countdown runs, and then for the whole ring-out at `TIME` — a
130-second timeout, released the moment the repeats end — so every chime and
buzz lands with the screen off [design; phone behavior verify, n=0]. The
service stops itself
the moment nothing is live. Android 13 and up asks once, on the first start,
whether the app may post notifications; the timer runs either way [design].

**The lock-screen card.** His words, 2026-09-20: *"If the app is closed the
timer should stay running and available on the lock screen."* The notification
sits on the channel `timer_v2` at **default** importance, so the phone draws it
as a card with the countdown and the buttons instead of the small icon a
low-importance channel was given on his lock screen [measured, 2026-09-20, n=1:
the old `timer` channel, IMPORTANCE_LOW, showed as an icon in the top row and
nothing else]. It is still silent - the channel sets no sound and no vibration,
and the notification alerts only once, so PAUSE, RESUME and RESTART never pop a
banner; every sound comes from the timer itself [design]. The old channel is
deleted the first time the service starts. On Android 12 and up the card
appears at once rather than after the system's ten-second foreground-service
delay [design]. Whether the lock screen draws cards or icons is **also** his own
Samsung setting for lock-screen notifications - a phone setting, not the app's
[verify, n=0]. [phone behavior verify, n=0]

**The music ducks at the end.** Three seconds from zero the app takes transient
audio focus, so whatever is playing in his headphones drops under the 3-2-1
ticks and the chime and stays down; it comes back up on its own the moment the
set is reset or restarted [design; his words: "gently increase back to where it
was when it resets or restrarts"]. Pausing hands it back too. A set that the
phone restores inside its last three seconds - the process was killed and
reopened just before zero - takes the duck as it comes back, so those last ticks
are not the one time the music stays over them [design]. The app never
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

**Screen stays awake while anything is counting - and while the chime is still
repeating** - and releases as soon as that stops, so with the app in front the
ring-out is never cut short by the screen going to sleep [design; phone behavior
verify, n=0]. Rotation rebuilds the layout without disturbing a live set,
including an open wheel picker, and without silencing a ring-out.

**If the app is killed** — the countdown, the stopwatch and the selected preset
are written to the phone on every change, so a restarted process picks the set
back up where it was. A reboot clears it: the app opens fresh on 35s [design].

**Ten minutes and a killed set is dropped.** His words, 2026-09-20: *"if the
app is "killed with a timer running for more than ten minutes kill it and shut
down the timer app."* When the process comes back, a set whose finish is more
than ten minutes in the past is not picked up at all — no red `TIME`, no
notification, no service, no hold on the music. The app is simply fresh: 35s,
ready [design]. A set that is **still counting** comes back however long the
process was dead ("If the app is closed the timer should stay running"), and one
that ended ten minutes ago or less still comes back on a silent `TIME`
[design]. A paused set and the stopwatch are not touched by the rule [design].
[phone behavior verify, n=0]

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
