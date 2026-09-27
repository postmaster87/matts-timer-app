# ALARM_SPEC - alarm clock for Matt's Timer

Fable's spec, 2026-09-27, base commit 7a8d5f1. Opus builds to this; Fable
reviews the diff against it line by line. Tags: `[measured]` `[design]`
`[inferred]` `[verify]`.

## 1. Matt's words

2026-09-27, this repo's chat: "Okay lets add in an alarm clock feature next"

His answers to Fable's twelve questions, his words: "1. yes, 2. B, 3. B, 4.
12 hour, 5 A, 6. ignores mute but I need more sound options in needs to be
something that can wake but not annoy me, 7. 15 minutes, 8. yes, 9. A, 10.
B, 11. If it is verification testing just close it, 12. effort is high"

Decoded against the questions as asked:

| # | Question | His answer | Means |
|---|---|---|---|
| 1 | Rings at a time of day, on a third tab `ALARM` | yes | third tab `ALARM` |
| 2 | How many alarms | B | several, each with its own on/off switch |
| 3 | Repeat | B | one-shot or chosen days of the week, per alarm |
| 4 | Time entry | 12 hour | hour and minute wheels with the number pad, AM/PM |
| 5 | Snooze | A | no snooze, just STOP |
| 6 | Sound | see his words | the alarm ignores MUTE; more sound options, "something that can wake but not annoy me" |
| 7 | Ring length untouched | 15 minutes | stops itself at 15 minutes, leaves a missed-alarm card |
| 8 | `USE_EXACT_ALARM` | yes | accepted |
| 9 | Survive a reboot, `RECEIVE_BOOT_COMPLETED` | A | accepted |
| 10 | Ringing on a locked phone | B | full-screen takeover, `USE_FULL_SCREEN_INTENT` accepted |
| 11 | Base build | see his words | the open phone checks on f3ba666 are closed; build on it |

## 2. Fable's design calls - not his words, his to overturn

Each of these is `[design]` by Fable. Opus builds them as written; Fable
lists them to Matt in chat.

- D1. **It rings after a restart even before he unlocks the phone.** Android
  delivers `BOOT_COMPLETED` only after the first unlock. A phone that
  restarts overnight would stay silent until he picked it up. The alarm
  parts are therefore direct-boot aware and listen for
  `LOCKED_BOOT_COMPLETED` too (same permission, no new one).
- D2. **Late ring.** When the phone comes back up (or the app is updated,
  or the process returns) and an alarm's time passed 15 minutes ago or
  less, it rings now. Past more than 15 minutes, it does not ring: a
  missed-alarm card is posted instead.
- D3. **Volume ramp.** Gain rises in a straight line from 0.08 at the start
  of the ring to 1.0 at 90 seconds, then stays at 1.0. 1.0 is whatever his
  alarm volume already is; no stream volume is written.
- D4. **Sound choice is one setting for all alarms**, on the ALARM tab,
  separate from the timer's voice. Seven choices: four new wake voices
  (Section 7) plus BELL, CHIME, PULSE. Default is the first new voice.
- D5. **Vibration follows the existing VIB toggle** as read at the last
  time the app was open (mirrored into the alarm's own storage).
- D6. **Time zone and clock changes re-arm every alarm**, so 5:30 AM stays
  5:30 AM local.
- D7. **No alarm labels, no per-alarm sound, no cap shown to him**; the
  list is capped at 20 alarms in code.

## 3. Walls

- No `INTERNET`. No dependency. No exported component besides the launcher
  activity: both new receivers, the new service and the new activity are
  `android:exported="false"`.
- The `<uses-permission>` lines are already in the manifest at the base
  commit, written by Fable. Opus adds components only and does not touch
  those lines.
- `TimerEngine.kt` and `TimerService.kt` are not changed by this job,
  except the one mirror call in D5 (`toggleVibe` and `init` call
  `AlarmEngine.mirrorVibe(vibe)`).
- No stream volume write, anywhere.
- Nothing he did not ask for.

## 4. Files

| File | Owner | What |
|---|---|---|
| `AlarmEngine.kt` | Fable-owned | the alarm list, storage, next-trigger math, arming, fire handling, ring state |
| `AlarmService.kt` | Fable-owned | foreground service while ringing: wake lock, ring loop, notification, full-screen intent, missed card |
| `AlarmReceiver.kt` | Fable-owned | boot, locked boot, package replaced, time set, time zone changed: re-arm |
| `AlarmActivity.kt` | Opus | the full-screen ringing screen: time, STOP |
| `MainActivity.kt`, layouts | Opus | ALARM tab, list, editor overlay, sound button |
| `Tones.kt` | Opus | four wake voices, `play` returning a handle that can be stopped |

All in `android/app/src/main/java/com/matt/gymtimer/`.

## 5. AlarmEngine (Fable-owned)

### 5.1 Storage

- `ctx.createDeviceProtectedStorageContext().getSharedPreferences("alarms", MODE_PRIVATE)`.
  Never the `mt` prefs: those are credential-protected and unreadable
  before the first unlock. AlarmEngine, AlarmService, AlarmReceiver and
  AlarmActivity must not call `TimerEngine` at all.
- Keys: `list` (String, JSON array via `org.json`, framework),
  `nextId` (Int), `voice` (Int, index into the alarm voice list), `vibe`
  (Boolean, D5), `ringId` (Int, -1 when not ringing), `ringAtWall` (Long,
  wall-clock ms the ring began).
- One alarm: `{"id":Int,"h":0..23,"m":0..59,"days":Int,"on":Boolean,"next":Long}`.
  `days` is a bitmask, bit 0 Sunday to bit 6 Saturday; 0 means one-shot.
  `next` is the wall-clock ms it is armed for, 0 when off.
- Written with `commit()` inside the fire path and the receiver (the
  process may die right after), `apply()` elsewhere.
- Malformed JSON or a bad field: that alarm is skipped, the rest load.

### 5.2 Next-trigger math

`nextTrigger(h, m, days, nowWall): Long`

- `Calendar.getInstance()` (default time zone), set to `nowWall`, then
  hour-of-day `h`, minute `m`, second 0, millisecond 0.
- If the result is `<= nowWall`, add one day.
- If `days != 0`, add a day at a time (at most 7) until the bit for that
  day of the week is set.
- Calendar stays lenient, so a time inside a daylight-saving gap lands on
  the next valid instant.

### 5.3 Arming

- `arm(a)`: `AlarmManager.setAlarmClock(AlarmClockInfo(a.next, show), op)`.
  - `op` = `PendingIntent.getForegroundService(ctx, a.id, Intent(ctx,
    AlarmService::class.java).setAction(ACT_FIRE).putExtra("id", a.id),
    FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)`.
  - `show` = `PendingIntent.getActivity` to `MainActivity`, request code
    `100000 + a.id`, `FLAG_IMMUTABLE`.
  - Wrapped in try/catch for `SecurityException` (API 31 and 32 without
    the permission); a failed arm sets `armFailed = true` for the UI.
- `disarm(a)`: `AlarmManager.cancel(op)` built the same way, then
  `a.next = 0`.
- `armAll(nowWall)`: for every alarm with `on`:
  - `next == 0` or `next > nowWall`: recompute `next` with `nextTrigger`
    and arm. (Recompute always: the time zone may have changed.)
  - `next <= nowWall` and `nowWall - next <= 15 min`: late ring (D2):
    start `AlarmService` with `ACT_FIRE` for it.
  - `next <= nowWall` and older: call `missed(a)`, then roll it: one-shot
    goes `on = false, next = 0`; repeating gets a new `next` and is armed.
- Public edits, each ending in persist + listener callback:
  `add(h, m, days)`, `update(id, h, m, days)`, `setOn(id, on)`,
  `delete(id)`, `setVoice(i)`, `mirrorVibe(v)`. `add` and `update` switch
  the alarm on. Turning on or saving always recomputes `next` from now.
- `init(ctx)` is idempotent, loads the list and calls `armAll`. Called
  from `MainActivity.onCreate`, `AlarmService.onCreate`,
  `AlarmReceiver.onReceive` and `AlarmActivity.onCreate`.

### 5.4 Fire

`fire(id)`, called by `AlarmService` on `ACT_FIRE`:

1. Alarm missing or `on == false`: return false (service stops itself if
   it is not already ringing).
2. `next` more than 60 s in the future: a stale intent; re-arm and return
   false.
3. Roll the alarm first, before any sound: one-shot goes `on = false,
   next = 0`; repeating gets `nextTrigger(h, m, days, now + 60 s)` and is
   armed. Persist with `commit()`.
4. If already ringing, keep the ring that is running (its clock is not
   reset) and return true.
5. Set `ringId`, `ringAtWall`, `ringFrom = elapsedRealtime()`, persist
   with `commit()`, return true.

`stop()`: clears `ringId` to -1, persists, tells the service to end the
ring. Called by the STOP button on the screen and on the notification.

## 6. AlarmService (Fable-owned)

- Foreground, `specialUse`, not exported, not bound, direct-boot aware.
  `startForeground` is the first call in `onStartCommand`, every time,
  with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34 and up.
- Actions: `ACT_FIRE` (extra `id`), `ACT_STOP`. A null intent (sticky
  restart) resumes the ring if `ringId != -1` and the ring began 15
  minutes ago or less by wall clock; otherwise posts the missed card and
  stops. Returns `START_STICKY`.
- Wake lock: partial, tag `matttimer:alarm`, not reference counted,
  acquired with a 16-minute timeout when the ring begins, released the
  moment the ring ends.
- Audio focus: `AUDIOFOCUS_GAIN_TRANSIENT` with `USAGE_ALARM` attributes
  when the ring begins, abandoned when it ends.
- Ring loop, main-looper Handler, one cue at a time with a generation
  counter (same pattern as `TimerEngine.postCue`):
  - each fire reads the voice and `vibe` from AlarmEngine, plays the
    voice's phrase at gain `min(1.0, 0.08 + 0.92 * t / 90 s)` where `t` is
    `elapsedRealtime() - ringFrom`, and buzzes `longArrayOf(0, 300, 120,
    300, 120, 500)` with `VibrationAttributes` / `AudioAttributes`
    `USAGE_ALARM` if `vibe`;
  - the next fire is posted at phrase length + 1500 ms;
  - no phrase starts at `t > 15 min` (900 000 ms). When that cut is
    reached the ring ends as MISSED.
- The alarm never reads `TimerEngine.sound`. MUTE does not reach it.
- Ending the ring (STOP or the 15-minute cut): kill the cues, stop the
  playing track now (Section 7, the handle), cancel the vibration,
  abandon focus, release the wake lock, clear `ringId`,
  `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf()`. On the
  15-minute cut, post the missed card first.
- Channels, both created in `onCreate` with sound null and vibration off
  (the service makes every sound):
  - `alarm_ring`, `IMPORTANCE_HIGH`, `VISIBILITY_PUBLIC`;
  - `alarm_missed`, `IMPORTANCE_DEFAULT`.
- Ringing notification (id 8): ongoing, category `CATEGORY_ALARM`, title
  `ALARM`, text the alarm's time in 12-hour form (`5:30 AM`), one action
  `STOP` (`PendingIntent.getService`, `ACT_STOP`), content intent and
  `setFullScreenIntent(pi, true)` both to `AlarmActivity`,
  `FOREGROUND_SERVICE_IMMEDIATE` on API 31 and up.
- Missed card (id 9 + alarm id): not ongoing, auto-cancel, title `MISSED
  ALARM`, text the alarm's time, content intent to `MainActivity`.
- `onTaskRemoved` does nothing.

## 7. Tones (Opus)

- `play(pcm, gain)` returns a handle (the `AudioTrack` or a small
  wrapper) with `stop()`, so STOP silences a phrase mid-ring. Existing
  callers ignore the return value; their behavior is unchanged.
- Four new wake voices, his words: "something that can wake but not annoy
  me". Constraints `[design]`: every fundamental at or below 900 Hz; attack
  40 ms or longer (the current 14 ms stays for the existing cues); phrase
  4 to 8 seconds; no clipped pure tones; each clearly different from the
  others in contour and tempo. Names are one short word each, Opus's
  choice. Each gets a preview of 3 seconds or less.
- The alarm voice list is the four new ones followed by BELL, CHIME,
  PULSE (their `chime` phrase). The timer's voice cycle stays BELL, CHIME,
  PULSE, MUTE and does not gain the new ones.
- Phrases are rendered on first use, not at startup, so the timer's
  startup cost does not grow.

## 8. AlarmReceiver (Fable-owned)

- Not exported, direct-boot aware. Intent filter:
  `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`,
  `TIME_SET`, `TIMEZONE_CHANGED`.
- `onReceive`: `AlarmEngine.init(ctx)` then `AlarmEngine.armAll(now)`.
  Nothing else. No work off the main thread is needed; the list is at most
  20 entries.
- `[verify]` on the phone: that a non-exported receiver gets the system's
  boot broadcast. If the build or the platform refuses it, that is a
  BLOCKED, not an exported receiver.

## 9. Manifest (Opus adds components only)

- `<service android:name=".AlarmService" android:exported="false"
  android:directBootAware="true" android:foregroundServiceType="specialUse">`
  with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property: "Local alarm clock
  that must ring with the app closed and the phone locked".
- `<receiver android:name=".AlarmReceiver" android:exported="false"
  android:directBootAware="true">` with the filter in Section 8.
- `<activity android:name=".AlarmActivity" android:exported="false"
  android:directBootAware="true" android:showWhenLocked="true"
  android:turnScreenOn="true" android:launchMode="singleInstance"
  android:excludeFromRecents="true" android:taskAffinity="">`.
- `MainActivity`, `TimerService` and the `<application>` element are not
  made direct-boot aware.

## 10. Screens (Opus)

### 10.1 ALARM tab

- Third tab button `ALARM` after `STOPWATCH`, portrait and landscape. A
  tap on it goes through the existing `onTab` (so it also silences a timer
  ring-out, as the other tabs do).
- In this mode the presets, the stage, the progress bar, the laps and the
  three big buttons are hidden. Shown instead:
  - a status line: `NEXT  5:30 AM  ·  IN 7H 12M`, or `NO ALARM SET`;
  - the list, one row per alarm, sorted by time of day: the time large in
    12-hour form, under it `ONCE` or the days (`EVERY DAY`, `WEEKDAYS`,
    `WEEKENDS`, or `M W F` style), and an on/off control at the right with
    a touch target of 56 dp or more. Tapping the row opens the editor;
    tapping the control only switches it;
  - `ADD ALARM` button, full width, same height as the big buttons;
  - the sound button, showing the current alarm voice name; a tap cycles
    to the next voice and plays its preview at full gain.
- Warning lines, shown only when true, in amber:
  - notifications not granted: `NOTIFICATIONS OFF  ·  TAP TO ALLOW`
    (requests `POST_NOTIFICATIONS`; asked by itself the first time an
    alarm is saved);
  - API 34 and up and `NotificationManager.canUseFullScreenIntent()` is
    false: `FULL SCREEN OFF  ·  TAP TO ALLOW`, opening
    `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` for this package;
  - `AlarmEngine.armFailed`: `ALARM COULD NOT BE SET`.
- The status line's countdown refreshes once a minute while the tab is
  showing, and on every engine callback.

### 10.2 Editor overlay

- Same look as the timer's picker. Hour wheel 1 to 12, minute wheel 00 to
  59 (formatter, not displayedValues - see the typed-5 finding in
  `README.md`), both typeable with the number pad. An `AM` / `PM` pair of
  buttons, one lit.
- Seven day buttons `S M T W T F S`, each a toggle. None lit means it
  rings once.
- `CANCEL`, `SAVE`, and `DELETE` (editing an existing alarm only). The
  buttons stay above the keyboard the same way the picker's do. The
  number pad's check key commits the typed value and drops the keyboard;
  it does not save.
- A new alarm opens on 6:00 AM, no days. Back closes the editor.
- Survives rotation the way the picker does.
- After SAVE a short line in the status area reads `RINGS IN 7H 12M`.

### 10.3 AlarmActivity

- Full screen over the lock screen, screen on, kept on while ringing.
  Black background, the alarm's time large, `ALARM` above it, one `STOP`
  button spanning the width, at least 96 dp tall.
- STOP calls `AlarmEngine.stop()` and finishes the activity. Back does
  nothing while ringing. When the ring ends any other way the activity
  finishes itself.
- Touches `AlarmEngine` only.

## 11. Docs (Opus, same commit as the behavior)

`README.md` gains an **Alarm** section and the permission paragraph reads
eight permissions; `tools/make_pdf.py` changes with it and
`docs/TIMER.pdf` is re-run. Everything on-phone is tagged `[verify, n=0]`.

## 12. Checks that prove it

Build: `android\gradlew.bat -p android assembleRelease` passes on the
committed tree. Greps on the source: no `INTERNET`, no `setStreamVolume`,
`adjustVolume` or `adjustStreamVolume`; no `TimerEngine` reference in the
four alarm files; `exported="true"` appears once in the manifest.

Phone checks, Fable's, each on Matt's word: alarm 2 minutes out rings with
the app closed and the screen off; full screen over the lock screen; STOP
from the screen and from the card; MUTE on the timer and the alarm still
sounds; ramp heard by him; each voice previewed by him; one-shot switches
itself off; repeating alarm shows the next day; 15-minute cut and missed
card; restart the phone with an alarm set and do not unlock; time zone
change; timer running while the alarm fires.
