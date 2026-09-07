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
    "Native Android app. Countdown timer + stopwatch, built for back-to-back sets. "
    "Installs from dist/MattsTimer.apk - 664 KB, no dependencies.", SUB))

F.append(Paragraph("It cannot use the internet", H2))
F.append(Paragraph(
    "The app declares exactly one permission: VIBRATE. There is no INTERNET "
    "permission in the manifest, so the phone will not let it reach the network "
    "under any circumstances. Nothing loads, nothing syncs, nothing needs signal. "
    "The chimes are synthesised on the device at startup rather than shipped as "
    "audio files.", BODY))

F.append(Paragraph("The one rule that drives the layout", H2))
F.append(Paragraph(
    "<b>RESET restarts the selected preset and starts it immediately.</b> Between "
    "back-to-back core sets that is one tap, not two. Both large buttons do this "
    "once the timer has finished, so you can hit either one without looking.", BODY))

F.append(Paragraph("Presets", H2))
F.append(Paragraph(
    "<b>35s &nbsp; 45s &nbsp; 1m &nbsp; 5m &nbsp; 10m &nbsp; 20m &nbsp; 30m &nbsp; 60m</b>, "
    "plus CUSTOM. 60 seconds and 1 minute collapsed into one tile.", BODY))
F.append(Paragraph(
    "The app <b>always launches on 35s</b>, regardless of what ran last. "
    "Presets go dim and inert while the timer is running, so a stray tap mid-set "
    "cannot wipe a live timer - pause or let it finish to change duration.", BODY))

F.append(Paragraph("Custom lengths", H2))
F.append(Paragraph(
    "CUSTOM opens a full-screen keypad. Digits shift in from the right as MM:SS - "
    "tap 2, 3, 0 for 2:30. SET &amp; START applies it and starts it in one tap. "
    "The last custom value stays on the tile for one-tap recall, but it is never "
    "auto-selected at launch.", BODY))

F.append(Paragraph("Buttons", H2))
F.append(table([
    ["State", "Left (large)", "Right (large)"],
    ["Ready", "START", "RESET - restarts &amp; runs"],
    ["Running", "PAUSE", "RESET - restarts &amp; runs"],
    ["Paused", "RESUME", "RESET - restarts &amp; runs"],
    ["Finished", "GO AGAIN", "RESET - restarts &amp; runs"],
    ["Stopwatch running", "STOP", "LAP"],
    ["Stopwatch stopped", "START / RESUME", "RESET"],
], [1.5 * inch, 1.9 * inch, 2.7 * inch]))

F.append(Paragraph("Sound and feedback", H2))
F.append(Paragraph(
    "Soft bell tick at 3, 2, 1. At zero, a C-major arpeggio that lands on a full "
    "triad and rings twice - every tone is a sine with a soft attack and a natural "
    "decay, no buzzer. Screen goes red and flashes; the phone vibrates.", BODY))
F.append(Paragraph(
    "Sound and vibration toggle independently in the header and persist between "
    "sessions. Cues play on the alarm stream so they carry over gym noise.", BODY))

F.append(Paragraph("Stopwatch", H2))
F.append(Paragraph(
    "Second tab in the header. START / STOP, LAP while running, RESET when stopped. "
    "Laps list split and cumulative time, newest first. Hundredths resolution. The "
    "screen stays awake while anything is counting and releases as soon as it stops; "
    "rotating the phone rebuilds the layout without disturbing a live set.", BODY))

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
