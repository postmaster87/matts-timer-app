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

