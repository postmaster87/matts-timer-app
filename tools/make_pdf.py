"""Reading copy of the timer app doc -> docs/TIMER.pdf"""
import os
from reportlab.lib.pagesizes import LETTER
from reportlab.lib.units import inch
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_LEFT
from reportlab.platypus import (SimpleDocTemplate, Paragraph, Spacer, Table,
                                TableStyle, KeepTogether)

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "docs", "TIMER.pdf")
os.makedirs(os.path.dirname(OUT), exist_ok=True)

INK = colors.HexColor("#111820")
DIM = colors.HexColor("#5C6B7A")
ACC = colors.HexColor("#0E7C42")
LINE = colors.HexColor("#D3DBE3")
PANEL = colors.HexColor("#F2F5F8")

H1 = ParagraphStyle("H1", fontName="Helvetica-Bold", fontSize=19, leading=23,
                    textColor=INK, spaceAfter=2)
SUB = ParagraphStyle("SUB", fontName="Helvetica", fontSize=9.5, leading=13,
                     textColor=DIM, spaceAfter=14)
H2 = ParagraphStyle("H2", fontName="Helvetica-Bold", fontSize=11, leading=14,
                    textColor=ACC, spaceBefore=13, spaceAfter=5)
BODY = ParagraphStyle("BODY", fontName="Helvetica", fontSize=9.5, leading=13.4,
                      textColor=INK, alignment=TA_LEFT, spaceAfter=5)
CELL = ParagraphStyle("CELL", fontName="Helvetica", fontSize=9, leading=12,
                      textColor=INK)
CELLB = ParagraphStyle("CELLB", fontName="Helvetica-Bold", fontSize=9, leading=12,
                       textColor=INK)
MONO = ParagraphStyle("MONO", fontName="Courier", fontSize=8.6, leading=12,
                      textColor=INK)


def table(rows, widths):
    data = [[Paragraph(c, CELLB if i == 0 else CELL) for c in r]
            for i, r in enumerate(rows)]
    t = Table(data, colWidths=widths, hAlign="LEFT")
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), PANEL),
        ("LINEBELOW", (0, 0), (-1, -1), 0.5, LINE),
        ("LINEABOVE", (0, 0), (-1, 0), 0.6, LINE),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 7),
        ("RIGHTPADDING", (0, 0), (-1, -1), 7),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]))
    return t


F = []
F.append(Paragraph("Matt's Timer", H1))
F.append(Paragraph(
    "Native Android app. Countdown timer + stopwatch, built for back-to-back sets, "
    "and it keeps running with the app closed. Installs from dist/MattsTimer.apk - "
    "under 700 KB, no dependencies.", SUB))

F.append(Paragraph("It cannot use the internet", H2))
F.append(Paragraph(
    "There is no INTERNET permission in the manifest, so the phone will not let "
    "it reach the network under any circumstances. Nothing loads, nothing syncs, "
    "nothing needs signal. The chimes are synthesised on the device at startup "
    "rather than shipped as audio files.", BODY))
F.append(Paragraph(
    "The five permissions it does declare are all local to the phone: VIBRATE, "
    "and - so a set keeps running with the app closed - FOREGROUND_SERVICE, "
    "FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS and WAKE_LOCK.", BODY))

F.append(Paragraph("The one rule that drives the layout", H2))
F.append(Paragraph(
    "<b>RESTART puts the clock back to the preset time and runs it immediately.</b> "
    "Between back-to-back core sets that is one tap, not two. <b>RESET</b>, in the "
    "middle, does the same thing but leaves it stopped - it never starts anything.", BODY))

F.append(Paragraph("Presets", H2))
F.append(Paragraph(
    "<b>35s &nbsp; 45s &nbsp; 1m &nbsp; 5m &nbsp; 10m &nbsp; 15m &nbsp; 20m &nbsp; "
    "30m &nbsp; 60m</b> - nine tiles, three by three.", BODY))
F.append(Paragraph(
    "The app <b>always launches on 35s</b>, regardless of what ran last - unless a "
    "set is still running, paused or finished, in which case it opens on that set. "
    "Presets go dim and inert while the timer is running, so a stray tap mid-set "
    "cannot wipe a live timer - pause or let it finish to change duration.", BODY))

F.append(Paragraph("Tap the clock for a one-off time", H2))
F.append(Paragraph(
    "With the timer stopped - ready, paused or finished - <b>tap the digits</b> and "
    "two wheels come up, minutes and seconds. Flick them, or tap a number and type "
    "it. SET &amp; START applies the value and starts it in one tap, the same path "
    "RESTART takes; RESET and RESTART then come back to that value until a preset "
    "is tapped. CANCEL or the back button closes it, and the ready line reads "
    "TAP TO SET as the reminder. Tapping the clock while it is running, or in the "
    "stopwatch, does nothing.", BODY))
F.append(Paragraph(
    "<b>Typing raises a number pad, not the full keyboard, and its check key sets "
    "the time and starts it</b> - one key, nothing to reach for afterwards. What "
    "you type is read as a number: 5 in the seconds wheel is five seconds, 12 is "
    "twelve, and the minutes wheel reads the same way. CANCEL "
    "and SET &amp; START stay above the keyboard the whole time it is up: the "
    "wheels are what gives way, never the buttons. Closing the picker drops the "
    "keyboard with it, and at 0:00 the check key just puts the value in, since "
    "there is nothing to start.", BODY))

F.append(Paragraph("The three buttons", H2))
F.append(table([
    ["Button", "Timer", "Stopwatch"],
    ["Left", "START / PAUSE / RESUME / GO AGAIN", "START / STOP / RESUME"],
    ["Middle - RESET", "Back to the preset time, stopped", "Back to zero, clears laps"],
    ["Right", "RESTART - back to the preset time <b>and runs</b>", "LAP"],
], [1.3 * inch, 2.9 * inch, 1.9 * inch]))

F.append(Paragraph("Sound", H2))
F.append(table([
    ["Voice", "Character"],
    ["BELL", "C major arpeggio landing on a full triad. Warm, the default"],
    ["CHIME", "Falling G-E-C resolving to an open fifth. Calmer, longer ring"],
    ["PULSE", "Three clipped tones then a high hold. Cuts through a loud gym"],
    ["MUTE", "Silent - vibration still fires"],
], [1.0 * inch, 5.1 * inch]))
F.append(Spacer(1, 5))
F.append(Paragraph(
    "The header button cycles these and always shows the current one; each pick "
    "plays a short preview. Changing voice or muting mid-set takes effect on the "
    "next cue, with no restart.", BODY))

F.append(Paragraph("At zero", H2))
F.append(Paragraph(
    "Soft tick at 3, 2, 1 in the selected voice, then the finish chime. The digits "
    "go red, the line reads TIME and the phone vibrates. Every tone is a sine with a "
    "soft attack and a natural decay, synthesised on the device - no buzzer, no "
    "audio files. Cues play on the alarm stream so they carry over gym noise, and "
    "vibration toggles separately. Both settings persist.", BODY))
F.append(Paragraph(
    "The screen <b>flashes for exactly as long as the chime is repeating</b>. When "
    "the repeats end - on their own at two minutes, or because you stopped them - "
    "the flash stops with them and the screen sits still on red TIME. "
    "<b>PAUSE on the last instant is a finish:</b> if the clock has already hit zero "
    "by the time the tap lands, the set rings like any other finish rather than "
    "freezing at PAUSED 0:00.", BODY))

F.append(Paragraph("The chime repeats, and gets louder", H2))
F.append(Paragraph(
    "The finish chime does not ring once and give up. It <b>repeats until you stop "
    "it</b> - RESTART, RESET, a preset, SET &amp; START, the button on the lock "
    "screen, tapping either tab, or starting the stopwatch - and each repeat is "
    "louder than the last, so a set that ends while you are under a bar does not go "
    "unheard. Tapping a tab or starting the stopwatch only silences it: the set "
    "stays on TIME and the music stays ducked until RESET or RESTART. Rotating the "
    "phone does not silence it.", BODY))
F.append(table([
    ["The ring-out", "How it behaves"],
    ["Volume", "30% on the first chime, then 47.5, 65, 82.5, and full from the fifth on"],
    ["Spacing", "the chime's own ring-out plus a one-second gap - every 4 to 5 seconds"],
    ["It stops itself", "nothing new starts more than two minutes past the finish: "
                        "about 30 chimes on BELL, 25 on CHIME and PULSE"],
], [1.25 * inch, 4.85 * inch]))
F.append(Spacer(1, 5))
F.append(Paragraph(
    "<b>Full means as loud as your alarm volume already is.</b> The app turns its own "
    "cue up toward that and no further - it never writes a stream volume, so nothing "
    "touches the phone's sliders and nothing else on the phone gets louder. The buzz "
    "fires with every chime. On MUTE the buzz carries the sequence alone; with "
    "vibration off the chimes carry it alone; with both off it is silent and still "
    "ends at two minutes. Changing voice or muting mid-ring takes effect on the next "
    "repeat. If the two minutes run out on their own the clock stays on TIME and the "
    "music stays ducked - only RESET or RESTART hands that back.", BODY))

F.append(Paragraph("The music ducks under the last three seconds", H2))
F.append(Paragraph(
    "Three seconds out the app asks the system for transient audio focus, so "
    "whatever is playing in the headphones drops under the 3-2-1 and the chime "
    "instead of fighting them, and <b>stays</b> down at TIME. It comes back up on "
    "its own the moment the set is reset or restarted - pausing hands it back too. "
    "The app never writes a volume; it only asks for the duck, which is what makes "
    "the return gradual and leaves every other app's volume alone. A set the phone "
    "restores inside its last three seconds takes the duck as it comes back.", BODY))

F.append(Paragraph("It keeps running with the app closed", H2))
F.append(Paragraph(
    "Starting a countdown - or the stopwatch - starts a foreground service, so the "
    "set survives leaving the app, the screen going off, and swiping the app out of "
    "recents. It sits on the lock screen as an ongoing notification that counts "
    "itself down, with the buttons for whatever state it is in: PAUSE and RESTART "
    "while running, RESUME and RESTART while paused, RESTART at TIME, STOP for the "
    "stopwatch. Tapping the notification opens the app on the live set, and the "
    "service stops itself the moment nothing is live.", BODY))
F.append(Paragraph(
    "The notification sits on a <b>default-importance</b> channel, so the phone "
    "draws it on the lock screen as a card with the countdown and the buttons "
    "rather than as a small icon in the top row - which is what the old silent "
    "channel got. It stays silent all the same: no sound, no vibration, and it "
    "alerts only once, so PAUSE, RESUME and RESTART never pop a banner - every "
    "sound comes from the timer itself. On Android 12 and up the card shows "
    "immediately instead of ten seconds in. Whether your lock screen draws cards "
    "or icons at all is also a phone setting of your own (Samsung: lock-screen "
    "notification style), which the app does not touch.", BODY))
F.append(Paragraph(
    "A partial wake lock is held while the countdown runs, and then for the whole "
    "ring-out at TIME, so every repeat of the chime and the buzz lands with the "
    "screen off; it is released the moment the repeats end. "
    "Android 13 and up asks once, on the first start, whether the app may post "
    "notifications - the timer runs either way. Every change is written to the "
    "phone, so a killed process picks the set back up; a reboot clears it and the "
    "app opens fresh on 35s.", BODY))
F.append(Paragraph(
    "<b>Ten minutes and a killed set is dropped.</b> If the process comes back to "
    "a set whose finish is more than ten minutes in the past, it is not picked up "
    "at all - no red TIME, no notification, no service, no hold on the music. The "
    "app is simply fresh on 35s. A set that is still counting comes back however "
    "long the process was dead, and one that ended ten minutes ago or less still "
    "comes back on a silent TIME. A paused set and the stopwatch are not touched "
    "by the rule.", BODY))

F.append(Paragraph("Stopwatch", H2))
F.append(Paragraph(
    "Second tab in the header. START / STOP, LAP while running, RESET when stopped. "
    "Laps list split and cumulative time, newest first. Hundredths resolution. The "
    "screen stays awake while anything is counting - and while the chime is still "
    "repeating - and releases as soon as that stops; rotating the phone rebuilds the "
    "layout without disturbing a live set, or an open wheel picker.", BODY))

F.append(Paragraph("Install on the phone", H2))
F.append(Paragraph(
    "<b>Easiest:</b> plug the phone into the desktop over USB with USB debugging "
    "on, and the install runs from here in one command - no files to move by hand.", BODY))
F.append(Paragraph(
    "<b>By hand:</b> copy <font face='Courier'>dist/MattsTimer.apk</font> to the "
    "phone over USB, tap it in Files, and allow \"install unknown apps\" for the "
    "file manager the first time. The build is signed with the local debug key, "
    "which is fine for sideloading to your own phone.", BODY))

F.append(Paragraph("Rebuilding it", H2))
F.append(KeepTogether([
    Paragraph(
        "Needs JDK 17 and the Android SDK (compileSdk 35). No AndroidX, no Material "
        "library, no third-party dependencies - framework views only, which is why "
        "the APK is under 700 KB.", BODY),
    Spacer(1, 4),
    Paragraph("cd android &amp;&amp; ./gradlew assembleRelease", MONO),
    Spacer(1, 4),
    Paragraph(
        "Bumping <font face='Courier'>versionCode</font> in "
        "<font face='Courier'>android/app/build.gradle.kts</font> is only needed if "
        "an install ever refuses to replace the previous one.", BODY),
]))

doc = SimpleDocTemplate(OUT, pagesize=LETTER,
                        leftMargin=0.85 * inch, rightMargin=0.85 * inch,
                        topMargin=0.8 * inch, bottomMargin=0.7 * inch,
                        title="Matt's Timer", author="Matt")
doc.build(F)
print(OUT, os.path.getsize(OUT), "bytes")
