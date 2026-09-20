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

## 2. Phone check of v2 - WAITING on Matt

Serial `RFGL4275NVH`, his words 2026-09-20: "Trust the serial is correct."
Also his words: "I have another session working on my phone so don't
interrupt it... Once that session is done you can ask me for permission to
build and test on my phone." No adb command of any kind until he says that
session is done; then ask permission, then install f0071a1's build and run
the ten checks in `docs/DECISIONS_LOG.md` / Opus's list (Spotify is his
player). After a pass: replace `dist/MattsTimer.apk` in its own commit.

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
