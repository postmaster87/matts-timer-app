# Matt's Timer

Single-page PWA. Countdown timer with presets + manual custom entry, plus a
stopwatch. Built for the gym — big targets, high contrast, one-tap repeats for
back-to-back sets.

Reading copy: `docs/TIMER.pdf`

## Files

| File | Purpose |
|---|---|
| `index.html` | The entire app — markup, CSS, JS, no dependencies |
| `manifest.webmanifest` | PWA install metadata |
| `sw.js` | Offline shell cache. **Bump `CACHE` on every change to `index.html`** |
| `icons/` | 192, 512, maskable-512 (generated, pure-stdlib Python) |
| `.claude/launch.json` | Local dev server on port 8791 |

## Behavior

**Presets** — `35s 45s 1m 5m 10m 20m 30m 60m` plus `CUSTOM`. Launch always
selects **35s**, every time, regardless of what ran last.

**RESET restarts the selected preset and starts it immediately.** That is the
back-to-back set button: one tap between rounds, no second tap to start.

**Presets lock while the timer is running** (dimmed, inert) so a stray tap
mid-set cannot wipe a live timer. Pause or finish first to change duration.

**CUSTOM** opens a keypad, MM:SS, digits shift in from the right. `SET & START`
applies and starts in one tap. The last custom value is remembered on the tile
for one-tap recall — but it is never auto-selected on launch.

**Finish** — display goes red and flashes, `TIME`, and both large buttons
restart the set. A major-triad chime rings twice; the phone vibrates.

**Cues** — soft bell tick at 3, 2, 1. All tones are sine-based with a soft
attack and natural decay. Sound and vibration toggle independently in the
header and persist.

Audio cues are scheduled on the Web Audio clock, so they still fire if Chrome
backgrounds the tab. A screen wake lock is held while anything is counting.

**Stopwatch** — START/STOP, LAP while running, RESET when stopped. Laps list
split and cumulative time, newest first.

**Keyboard** (desktop): `Space` = primary button, `R` = reset.

## Deploy

GitHub Pages, `main` branch, root. Push and it is live at
https://postmaster87.github.io/matts-timer-app/

Install on the phone: open that URL in Chrome, menu → **Add to Home screen**.
It then runs full screen and works offline.

## Local preview

```bash
python -m http.server 8791
```

Then open http://localhost:8791 — a real HTTP origin is required for the
service worker and `localStorage` to work (`file://` will not register the SW).

## After editing `index.html`

Bump `CACHE` in `sw.js` (`mt-v1` → `mt-v2` → …). Without that bump, installed
phones keep serving the cached old version.
