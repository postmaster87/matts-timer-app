# FOR_FABLE - matts-timer-app (Fable's queue, in Matt's order)

Fable writes this file. Opus does not. Answered items move to
`FOR_FABLE_LOG.md`.

## 1. Fable-owned list - needs Matt's accept or edit

Proposed by Fable on 2026-09-20 in `CLAUDE.md` Section 1: everything that
decides when the timer ends and whether he hears it (countdown and
stopwatch clock math, tick loop, cue scheduler, `keepAwake`, and anything
added later that keeps a timer alive with the screen off or the app in the
background). Matt has not accepted it. Ask him at the start of the next
session: accept, or edit. Until then it is handled as Fable-owned.

## 2. Matt's feature adds - not yet stated

His words, 2026-09-20: "Flip it and I will tell you what I want added."
and "I'll start a new session for the feature adds". Nothing is queued
until he says it. Record each one here in his words.

## 3. Phone serial - confirm with Matt at the first phone check

`adb devices` showed no device attached on 2026-09-20 [measured, n=1].
matts-calendar-app's CLAUDE.md names `RFGL4275NVH` as his phone [verify -
read from another repo, not confirmed here]. Confirm the serial with him
before the first adb command that names it.

## 4. Follow-up seen while reading the code - not fixed, not asked for

The countdown finish and the 3-2-1 cues are driven only by a `Handler` on
the main thread inside `MainActivity` (`tick`, `postCue`), and the screen
is held on with `FLAG_KEEP_SCREEN_ON` while counting [measured,
2026-09-20, read of `MainActivity.kt`]. What happens to the finish cue if
he leaves the app or turns the screen off mid-set has not been tested
[verify, n=0]. His words on the job: "I need it to work correctly on my
phone". Raise it with him as a question; do not build for it unprompted.
