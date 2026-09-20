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
