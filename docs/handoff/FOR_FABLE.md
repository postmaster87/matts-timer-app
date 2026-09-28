# FOR_FABLE - matts-timer-app (Fable's queue, in Matt's order)

Fable writes this file. Opus does not. Answered items move to
`FOR_FABLE_LOG.md`.

## 1. Build v2 - in progress (spawned to Opus 2026-09-20)

Matt's feature adds, his words, 2026-09-20: "Okay I used the timer working
out yesterday and a few other times. I love the speed the presets and the
restart button. Remove the custom button and give me the ability to touch
the clock when it is not running and manual enter a time or scroll - should
be quick, simple and easy to interface with. The sound blends into my
headphones. Can you fade the volume when the timer is expiring and gently
increase back to where it was when it resets or restrarts? If the app is
closed the timer should stay running and available on the lock screen."

His answers to Fable's eight questions, his words: "1. Yes, 2. Your
recommendation, 3. 15m, 4.A, 5. 3, 6. Accept all 3, 7. Survive all 3 and
the stopwatch too, along with pause and restart, 8. accept Build it"

Decoded [design]: (1) two scroll wheels, minutes 0-99 and seconds 0-59,
framework NumberPicker, keypad screen removed; (2) SET & START kept; (3)
9th preset is 15m; (4) audio focus ducking, no media-volume writes; (5)
music goes down at the 3-second tick, comes back on RESET or RESTART; (6)
FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS,
WAKE_LOCK accepted ("all 3" read as the three bullets, four lines - said
back to him in chat); (7) survives leaving the app, screen off and swipe
from recents, stopwatch too, PAUSE and RESTART on the lock screen.

Built at f0071a1, reviewed and signed by Fable (`docs/DECISIONS_LOG.md`). Open: the phone check, item 2.

## 4. Alarm clock - spec written, spawned to Opus 2026-09-27

Matt's words, 2026-09-27: "Okay lets add in an alarm clock feature next".
His answers to Fable's twelve questions, his words: "1. yes, 2. B, 3. B, 4.
12 hour, 5 A, 6. ignores mute but I need more sound options in needs to be
something that can wake but not annoy me, 7. 15 minutes, 8. yes, 9. A, 10.
B, 11. If it is verification testing just close it, 12. effort is high"

Decoded, with the questions as asked, and Fable's seven design calls
(D1-D7, his to overturn): `docs/ALARM_SPEC.md`. Permission lines
`USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `USE_FULL_SCREEN_INTENT`
written by Fable. Built at ccca359, fix at 6544c8b, reviewed and signed by
Fable (`docs/DECISIONS_LOG.md`, 2026-09-27). Open: the phone check, on his
word (list in `docs/ALARM_SPEC.md` Section 12); his ears on the four wake
voices and the ramp. D1-D7 accepted by him 2026-09-27 ("2. accept"). Two
adds ("3. build both") built at 933ef88, signed, and that build installed
on RFGL4275NVH 2026-09-27 on his word ("install now check later"); no
alarm set, nothing checked. Ask him before the check session; the phone
is shared with the golf-tracker session by message first.

Phone check 2026-09-28, his words: "1 - 7 are a go hold on the rest".
Checks 3 (card), 4, 5, 6, 7 PASS [measured, n=1]; record in
`docs/DECISIONS_LOG.md`. Open, in order:
(a) checks 1, 2 and STOP from the ringing screen - need his hands (swipe
the app from recents, power button) before a set alarm time;
(b) his words: "it rang fine the stop button should be on the screen when
the alarm is firing" - built at 68019db, signed, installed 2026-09-28 on
his word ("Do 1"); app-in-front ring PASS [measured, n=1]; leave-and-return,
card STOP with the screen up, editor open, split-screen [verify, n=0];
(c) asked, not answered: alarm firing while he is in another app,
unlocked - pill plus shade, or look at his Samsung pop-up style setting
(own prompt first);
(d) on hold on his word: 15-minute cut and missed card, his ears on the
four voices, restart without unlock, time zone change.
Test alarm deleted on his word 2026-09-28; the alarm list is empty. His
words on (a), (c), (d): "the others have to wait".

## 2. Phone check - CLOSED by Matt 2026-09-27

His words, asked whether to close the open checks on f3ba666 before the
alarm build: "If it is verification testing just close it". The items below
were not run [verify, n=0]; they are closed on his word, not passed.
`dist/MattsTimer.apk` is still the pre-v2 build.

Record: `docs/DECISIONS_LOG.md`, entries "Phone check 1" and "Phone check
2" (2026-09-20). Phone rule after the incident, Matt's words: "finish the
timer and only the timer - do not fucking touch anything else on my
phone" - guarded inputs only (timer activity in front or abort), no BACK /
HOME / recents keys, and ask him before any further phone session.

Open: (a) lock-screen card needs his Samsung lock-screen notification
style decision (icons vs cards) - ask with its own prompt; (b) his ears:
Spotify duck depth, chime climb; (c) lock-screen buttons, rotation with
picker open, typed 5 in minutes; (d) untested: kill/restore, ten-minute
rule, pause at 0:00. After he passes it: replace `dist/MattsTimer.apk` in
its own commit naming f3ba666.

## 3. Follow-up seen while reading the code - not fixed, not asked for

The countdown finish and the 3-2-1 cues are driven only by a `Handler` on
the main thread inside `MainActivity` (`tick`, `postCue`), and the screen
is held on with `FLAG_KEEP_SCREEN_ON` while counting [measured,
2026-09-20, read of `MainActivity.kt`]. What happens to the finish cue if
he leaves the app or turns the screen off mid-set has not been tested
[verify, n=0]. His words on the job: "I need it to work correctly on my
phone". Raise it with him as a question; do not build for it unprompted.

Update 2026-09-20: answered by item 1 - Matt asked for the timer to stay
running with the app closed. The phone check still has to prove it.
