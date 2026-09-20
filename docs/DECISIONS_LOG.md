# DECISIONS_LOG - matts-timer-app

Fable's record: Matt's decisions in his words, and Fable's review and
sign-off of each Opus build. Newest at the bottom.

## 2026-09-20 - Repo flipped to Fable-main

Matt's words, in this repo's chat: "Flip it and I will tell you what I want
added. It is all local, no internet, no syncing with other apps, etc...
still a simple timer but I need it to work correctly on my phone and that
is where you come in Fable. Build the subagents and workflow rules. Let me
know when you're done and I'll start a new session for the feature adds"

Done by Fable the same day, at commit 4cbb214 as the starting tree:

- `CLAUDE.md` written (Sections 1-4), modelled on matts-doc-reader and
  matts-calendar-app, both flipped 2026-09-18.
- `.claude/agents/opus.md` written (model opus, effort high). No
  `opus-xhigh`: no global rule 3 xhigh class exists in this repo. No
  `fable-*` files: Fable does not spawn Fable in a flipped repo.
- `docs/handoff/FOR_FABLE.md`, `FOR_FABLE_LOG.md`, `NEXT_CHAT_2026-09-20.md`
  and this log created.
- `tools/md2pdf.py` copied in from matts-doc-reader (read there, written
  here) so every .md has its PDF.

Open from this entry: the Fable-owned list in `CLAUDE.md` Section 1 is
Fable's proposal and is NOT accepted by Matt yet (`FOR_FABLE.md` item 1).

Walls recorded from his words: no `INTERNET`, no syncing with other apps,
"still a simple timer". Measured the same day: the manifest's only
permission is `VIBRATE`; `dependencies { }` is empty; release is signed
with the debug key; no tests exist; `adb devices` listed no device (n=1).

## 2026-09-20 - v2 build: Fable review of f0071a1 - SIGNED for phone check

Job: Matt's four adds (his words and answers in `docs/handoff/FOR_FABLE.md`
item 1). Spec by Fable, built by Opus at f0071a1 on base 32f83ed.

Reviewed line by line against the spec [measured, n=1 read]:
`TimerEngine.kt` (492 lines) and `TimerService.kt` (212 lines), the
manifest diff, and the Activity's wiring by grep.

- Clock math moved unchanged: `endsAt = now + remainMs`, pause
  `remainMs = (endsAt - now) >= 0`, `swBase = now - swElapsed`, all
  `elapsedRealtime`.
- The finish is decided only in `finishCue()` (guarded on `running`, cue
  generation counter, re-posts if the clock still shows time). The
  Activity never calls `timerFinish`.
- Audio focus: `GAIN_TRANSIENT_MAY_DUCK`, requested before the 3 s tick
  (or at start inside the last 3 s), only when sound is on; abandoned in
  RESTART, RESET, PAUSE, preset select; held through TIME. No stream
  volume write anywhere (grep: no matches, also none for AlarmManager or
  INTERNET).
- Service: not exported, not bound, `specialUse`, `startForeground` first
  in `onStartCommand`, `onTaskRemoved` does nothing, START_STICKY restore
  through prefs. Partial wake lock only while the countdown runs
  (remaining + 10 s timeout) and 6 s past the finish.
- Manifest: only the `<service>` element added by Opus; the five
  permission lines are Fable's from 32f83ed. `dependencies { }` empty.
- Opus's ten stated deviations read and accepted (tap target is the
  digits block; SET & START dimmed-not-disabled at 0:00 so a typed value
  can commit; restore gated on the boot offset for all state; stopwatch
  START also triggers the one-time notification ask).
- Build: `assembleRelease` passes on the committed tree, APK 674,357
  bytes [measured, n=1]. `dist/MattsTimer.apk` NOT replaced yet.

Follow-ups seen, not fixed: (a) a process restored with under 3 s left
does not re-request the duck; (b) pause at the last instant can leave
"paused 0:00" where START refuses (in v1 too); (c) a process killed and
reopened hours later in the same boot shows TIME for the old set.

Everything on-phone is [verify, n=0]: lock-screen display, finish with the
screen off, swipe from recents, Spotify ducking. Not DONE until the phone
check passes. Matt, 2026-09-20: "I have another session working on my
phone so don't interrupt it. Trust the serial is correct. Once that
session is done you can ask me for permission to build and test on my
phone." No adb from this session until he says that session is done.

## 2026-09-20 - Rising repeat chime: Fable review of f3d3ed5 - SIGNED for phone check

Matt's words: "one more feature while you are waiting. Can you gradually
increase the timer volume once it has expired". Fable asked three questions
with recommendations (repeat until he taps; 30 percent to full by the 5th
chime, auto-stop at 2 minutes; ramp the app's own sound only, never a
volume slider). His answer: "thats fine build it in". Spec by Fable, built
by Opus at f3d3ed5 on base 570fa71.

Code diff read line by line [measured, n=1 read] (`TimerEngine.kt`,
`TimerService.kt`, `Tones.kt`):

- `Tones.play(pcm, gain = 1f)` sets the AudioTrack's own volume; synthesis
  untouched; no stream volume write (grep: no matches, none for
  AlarmManager or INTERNET either).
- Gains 0.30, 0.475, 0.65, 0.825, 1.0 then 1.0; period = chime length +
  1000 ms; buzz with every chime; sound, voice and vibe read at each fire.
- One cue at a time through `postCue`; `alarming` cleared only in
  `clearCues()`, which RESTART, RESET, preset select and SET & START all
  reach. `timerFinish` clears first, then sets `alarming`.
- No chime starts later than 120 s after the finish, measured on
  `elapsedRealtime`. Accepted deviation: `alarming` is held until the last
  chime has rung out (about 124 s worst case) so the wake lock is not
  dropped mid-tone; the lock's own timeout is 130 s.
- A restored `finished` state does not ring.
- Build: `assembleRelease` passes on the committed tree [measured, n=1].

Seen, not fixed: starting the stopwatch or switching tabs does not stop
the ring-out; the red flash outlives the 2-minute audio stop; the screen
may sleep during the ring-out (the chimes do not depend on it).

On-phone behavior [verify, n=0]. Joins the v2 phone check, still waiting
on Matt's word that the other phone session is done.

## 2026-09-20 - Fix pass before the phone: Fable review of 422e227 - SIGNED for phone check

Matt's words: "change them all nothing broke should touch my phone." Fable's
reading, said to him in chat: the three ring-out items plus the two plain
v2 defects. Spec by Fable, built by Opus at 422e227 on base 92aa947.

Code diff read line by line [measured, n=1 read]:

- `alarmStop()`: only acts while `alarming`; clears cues, state stays
  `finished`, focus stays held. Called first in `swStart()` and from the
  new `onTab()` (user tab taps only); rotation and onCreate call
  `setMode()` directly and do not silence the ring.
- Flash follows `alarming`, not `finished` (`syncFlash`, one decision point).
- keepAwake = running or swRunning or alarming, at onStart, listener and
  rotation.
- `timerPause()` with nothing left routes to `timerFinish()`; "paused
  0:00" cannot occur.
- `restore()` inside the last 3 s requests the duck; `loadSettings()` runs
  before it.
- Accepted deviation: a tap on the tab already showing also silences the
  ring.
- No stream volume, AlarmManager or INTERNET (grep: no matches). Build
  passes on the committed tree [measured, n=1].

Still open by Matt's choice: a process killed and reopened hours later in
the same boot shows TIME for the old set.

Phone: the calendar session reported at 10:36 it is done with
RFGL4275NVH. That is a status notice; the install waits on Matt's
permission in this chat.

## 2026-09-20 - Ten-minute stale rule: Fable review of e5226d2 - SIGNED for phone check

Matt's words: "if the app is "killed with a timer running for more than ten
minutes kill it and shut down the timer app. Yes you can install what is
needed on my PC and Phone". Fable's reading, told to him in chat before the
build: after the process was killed, a set that ended more than ten minutes
ago is dropped (no TIME, no notification, no service, app fresh at 35 s);
ended ten minutes ago or less restores to silent TIME; still counting
keeps running; a never-killed process is unchanged. Built by Opus at
e5226d2 on base 0d5a8b2, no deviations.

Code diff read line by line [measured, n=1 read]: `STALE_MS = 600_000`;
`finishedAt` set at the finish instant and persisted (0 when not
finished); `restore()` drops through `dropSet()` when the end is over ten
minutes past, or `finishedAt` is missing, zero or in the future; the
ran-out-while-dead branch keeps `finishedAt = savedEnds`. Service
shutdown after a drop rides the existing `serviceWanted` paths
(`syncService`, `TimerService.onStartCommand`). No stream volume,
AlarmManager or INTERNET. Build passes on the committed tree
[measured, n=1].

Known and accepted: a paused set restores after any gap in the same boot
(his words name a running timer). First open after upgrading from a
persisted `finished` state without `finishedAt` opens fresh.

Phone order, Matt's words: "hold on my phone that session is ready so it
is going to build first" - the Nudge session (Calendar app revision spec
[3294e9], reminder-app repo) has the phone first; this session sends no
adb command until it reports done. Install permission from Matt stands
("Yes you can install what is needed on my PC and Phone"); confirm with
him when the phone comes back before running it.

## 2026-09-20 - Phone check 1 of the e5226d2 build (Fable, adb-driven) - NOT DONE, 2 findings

Matt's words: "yes go ahead and install" and "make sure you test it".
Installed with `adb -s RFGL4275NVH install -r` (Success; settings kept:
voice PULSE, VIB on). Driven by adb taps; state read from dumpsys
(activity services, power, audio, notification) and screenshots. Every
line below is [measured, n=1] on his phone unless it says otherwise.

PASS
- Nine presets with 15m sixth; no CUSTOM tile. Tap clock opens the wheels
  at the current preset; flick moves a wheel (35 to 34); typing 12 works.
- First start raised "Allow Timer to send you notifications?"; the set
  ran behind the prompt; Allowed (POST_NOTIFICATIONS granted, USER_SET).
- Running: foreground service (specialUse), wake lock `matttimer:run`
  held, notification RUNNING with PAUSE + RESTART, vis PUBLIC. Paused:
  "PAUSED 0:43", RESUME + RESTART, wake lock released. Finished: TIME,
  RESTART. RESET: focus 0, service 0, notification 0.
- Duck: focus entry GAIN_TRANSIENT_MAY_DUCK / USAGE_ALARM present at 3 s
  left, held through TIME, held after the ring ended and after a tab tap,
  gone after RESET/RESTART.
- Ring-out: chime every 5.0 s on PULSE (predicted 4.989 s); last chime
  120 s after the finish; then wake lock released, flash stopped, static
  red TIME.
- STOPWATCH tab tap at TIME: no chime after the tap (12 s watched).
- Left the app (HOME) and swiped the card out of recents while ringing:
  same pid, service foreground, chimes kept coming.
- Stopwatch with the app left: notification STOPWATCH with STOP, no wake
  lock; elapsed right on return; service gone once stopped.
- Screen off (35 s set, phone Dozing): go 11:02:36.592, ticks +32.02,
  +33.00, +34.00 s, chime +35.00 s, repeats every 5 s.
- No crash in logcat.

FINDINGS (not fixed)
1. Typing in a wheel opens the full QWERTY keyboard, which covers CANCEL
   and SET & START until the keyboard's check key is pressed. Not "quick,
   simple and easy". Flicking is unaffected.
2. On his lock screen the timer shows only as a small icon in the top
   row, not as a card with the countdown and PAUSE / RESTART. Cause not
   yet separated [verify]: the channel is IMPORTANCE_LOW (silent), and
   Samsung's lock-screen notification style may be set to icons - a phone
   setting, his call (global Section 12 item 17).

NOT TESTED by adb: how deep Spotify ducks and whether the chime climb is
audible (his ears, headphones, Spotify playing); rotation with the picker
open (would need a rotation setting change); lock-screen buttons (phone
locked, PIN is his); process-kill restore and the ten-minute rule; pause
at 0:00.

State left: phone locked, screen on lock screen, timer at TIME for a 35 s
set (ring ends by itself at 2 min; music focus held until RESET/RESTART).
`dist/MattsTimer.apk` not replaced - the check has not passed.

## 2026-09-20 - Phone check 1 fixes: Fable review of 40f11f2 + f3ba666 - SIGNED for phone check 2

Matt's words on the two findings: "yes fix them now". Built by Opus on
base 9b3d7fa.

- `TimerService.kt` (Fable-owned) read line by line [measured, n=1 read]:
  channel `timer_v2` at IMPORTANCE_DEFAULT, sound null, vibration off,
  old `timer` channel deleted, FOREGROUND_SERVICE_IMMEDIATE on API 31+,
  nothing else in the notification changed. No deviation.
- Keyboard: number pad (TYPE_CLASS_NUMBER), check key commits and runs the
  SET & START path (at 0:00 it only closes the keyboard), root inset
  listener pads for the keyboard so CANCEL / SET & START stay above it,
  `adjustResize` on the activity, every close path hides the keyboard.
  Accepted deviations: IME_FLAG_NO_EXTRACT_UI (landscape), `@+id/root`.
- f3ba666: Opus reported typed `5` in seconds became 50 (NumberPicker
  prefix-matches displayedValues). Fable sent it back under "nothing broke
  should touch my phone". Fixed with a two-digit formatter in place of
  displayedValues; no reflection.
- Build passes on the committed tree, APK 677,345 bytes [measured, n=1].
  No stream volume, AlarmManager, INTERNET; permission lines untouched.

Seen, not fixed: full lintRelease has 2 old `Suspicious0dp` errors on
`lapsScroll` (lintVital is clean; the release build passes).

All on-phone behavior of these fixes is [verify, n=0] until phone check 2.
Install waits on Matt's word.

## 2026-09-20 - Phone check 2 of the f3ba666 build (Fable, adb-driven) - fixes confirmed, one item is Matt's phone setting

Matt's words: "yes install it". Installed with `adb install -r` (Success).

INCIDENT, Fable's fault: during this check Fable's unguarded coordinate
taps got out of step with the app; a BACK key press left the timer and the
following taps landed outside it. Matt, his words: "what the fuck are you
doing opening a browser and going to the playstation store that has
nothing to fucking do with installing a timer app" and then "finish the
timer and only the timer - do not fucking touch anything else on my
phone". Inputs sent in those sequences: taps (540,1418) (806,670)
(806,1103) (757,1280) (540,2086), one BACK, END, three backspaces, text
"12" twice. Rule from here on in this repo's phone work: every input is
sent only after a check that `com.matt.gymtimer/.MainActivity` is the
resumed activity, else abort; no BACK, HOME or recents keys; one action
per step with a read between. The guarded driver was used for everything
below.

PASS [measured, n=1 each]
- Number pad opens (not QWERTY); with it up CANCEL and SET & START sit
  above it (button row at y=1281 of 2340).
- Typed 5 + check key: a 0:05 set ran (ticks +2, +3, +4 s, chime +5.0 s).
- Typed 12 + SET & START tapped above the pad: go 11:28:47.13, ticks +9,
  +10, +11 s, chime +12.0 s. RESET returned to READY 12S.
- Notification on channel `timer_v2`, importance 3; titles RUNNING / TIME.
- Audio focus log: every request paired with an abandon; nothing held at
  idle. (An earlier "focus: 1" read was a log line of the old process
  dying at reinstall, not a held focus.)
- No crash in logcat.

NOT FIXED BY THE APP CHANGE
- Lock screen still shows the timer only as a small icon in the top row
  with the DEFAULT-importance channel. The app side is done; what is left
  is his Samsung lock-screen notification style (icons vs cards) - his
  setting, his call, its own prompt first (global Section 12 item 17).

STILL HIS TO CHECK: Spotify duck depth and the chime climb in his
headphones; lock-screen buttons once cards show; rotation with the picker
open; typed 5 in minutes. Not tested by anyone: process-kill restore and
the ten-minute rule; pause at 0:00.

State left: phone locked on the lock screen; a 1m set was started at
about 11:29 for the lock-screen read, so it reaches TIME and rings for up
to 2 minutes on its own; music stays ducked until RESET/RESTART.
`dist/MattsTimer.apk` not replaced.

