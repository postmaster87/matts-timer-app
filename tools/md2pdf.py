"""
Render a Markdown document to PDF with reportlab.

Pure Python, no Cairo, no external binaries. Handles the subset actually used in
this repo's docs: headings, paragraphs, bullet and numbered lists, pipe tables,
fenced code, horizontal rules, and inline bold / italic / code / links.

Usage:
    python tools/md2pdf.py SPEC.md docs/SPEC.pdf
"""

import html
import re
import sys
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import LETTER
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.platypus import (
    BaseDocTemplate,
    Frame,
    HRFlowable,
    KeepTogether,
    ListFlowable,
    ListItem,
    PageTemplate,
    Paragraph,
    Preformatted,
    Spacer,
    Table,
    TableStyle,
)

INK = colors.HexColor("#14201C")
MUTED = colors.HexColor("#5C6A63")
RULE = colors.HexColor("#D5DAD7")
ACCENT = colors.HexColor("#1F5C4B")
CODE_BG = colors.HexColor("#F2F1EC")


def styles():
    base = getSampleStyleSheet()
    s = {}
    s["title"] = ParagraphStyle(
        "title", parent=base["Title"], fontName="Times-Bold",
        fontSize=23, leading=27, textColor=INK, spaceAfter=2, alignment=TA_LEFT,
    )
    s["h1"] = ParagraphStyle(
        "h1", fontName="Times-Bold", fontSize=15.5, leading=19,
        textColor=INK, spaceBefore=18, spaceAfter=7,
    )
    s["h2"] = ParagraphStyle(
        "h2", fontName="Times-Bold", fontSize=12.5, leading=16,
        textColor=INK, spaceBefore=13, spaceAfter=5,
    )
    s["h3"] = ParagraphStyle(
        "h3", fontName="Times-Italic", fontSize=11.5, leading=15,
        textColor=INK, spaceBefore=10, spaceAfter=4,
    )
    s["body"] = ParagraphStyle(
        "body", fontName="Times-Roman", fontSize=10.5, leading=15.2,
        textColor=INK, spaceAfter=7,
    )
    s["bullet"] = ParagraphStyle(
        "bullet", parent=s["body"], spaceAfter=3, leading=14.6,
    )
    s["code"] = ParagraphStyle(
        "code", fontName="Courier", fontSize=8.6, leading=11.4,
        textColor=INK, backColor=CODE_BG,
        borderPadding=(6, 6, 6, 6), spaceBefore=4, spaceAfter=9,
    )
    s["th"] = ParagraphStyle(
        "th", fontName="Helvetica-Bold", fontSize=8.6, leading=11.6,
        textColor=colors.white,
    )
    s["td"] = ParagraphStyle(
        "td", fontName="Times-Roman", fontSize=9.2, leading=12.4, textColor=INK,
    )
    s["meta"] = ParagraphStyle(
        "meta", fontName="Helvetica", fontSize=8.6, leading=12,
        textColor=MUTED, spaceAfter=2,
    )
    return s


INLINE_CODE = re.compile(r"`([^`]+)`")
BOLD = re.compile(r"\*\*([^*]+)\*\*")
ITALIC = re.compile(r"(?<![\*\w])\*([^*\n]+)\*(?!\*)")
LINK = re.compile(r"\[([^\]]+)\]\(([^)]+)\)")


def inline(text):
    """
    Markdown inline -> reportlab mini-HTML.

    Code spans are pulled out to placeholders BEFORE the emphasis passes run.
    Without that, a literal asterisk inside a code span (`*`) is seen by the
    italic regex, which then pairs it with the next real asterisk in the line
    and emits overlapping <i> and <font> tags that reportlab refuses to parse.
    """
    out = html.escape(text, quote=False)

    stash = []

    def park(m):
        stash.append(m.group(1))
        return "\x00%d\x00" % (len(stash) - 1)

    out = INLINE_CODE.sub(park, out)
    out = LINK.sub(lambda m: '<link href="%s" color="#1F5C4B">%s</link>'
                   % (m.group(2), m.group(1)), out)
    out = BOLD.sub(lambda m: "<b>%s</b>" % m.group(1), out)
    out = ITALIC.sub(lambda m: "<i>%s</i>" % m.group(1), out)

    for i, code in enumerate(stash):
        out = out.replace("\x00%d\x00" % i,
                          '<font face="Courier" size="9">%s</font>' % code)
    return out


def split_row(line):
    cells = line.strip().strip("|").split("|")
    return [c.strip() for c in cells]


def build_table(rows, s, width):
    header, body = rows[0], rows[1:]
    data = [[Paragraph(inline(c), s["th"]) for c in header]]
    for r in body:
        data.append([Paragraph(inline(c), s["td"]) for c in r])

    ncols = max(len(r) for r in data)
    for r in data:
        while len(r) < ncols:
            r.append(Paragraph("", s["td"]))

    # First column gets more room; the rest share what is left.
    first = width * (0.30 if ncols > 2 else 0.38)
    rest = (width - first) / (ncols - 1) if ncols > 1 else width
    widths = [first] + [rest] * (ncols - 1)

    t = Table(data, colWidths=widths, repeatRows=1, hAlign="LEFT")
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), ACCENT),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LEFTPADDING", (0, 0), (-1, -1), 7),
        ("RIGHTPADDING", (0, 0), (-1, -1), 7),
        ("LINEBELOW", (0, 0), (-1, -2), 0.4, RULE),
        ("BOX", (0, 0), (-1, -1), 0.5, RULE),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1),
         [colors.white, colors.HexColor("#F7F7F4")]),
    ]))
    return t


def parse(md, s, width):
    flow = []
    lines = md.split("\n")
    i = 0
    first_heading = True

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if not stripped:
            i += 1
            continue

        # fenced code
        if stripped.startswith("```"):
            i += 1
            buf = []
            while i < len(lines) and not lines[i].strip().startswith("```"):
                buf.append(lines[i])
                i += 1
            i += 1
            flow.append(Preformatted("\n".join(buf), s["code"]))
            continue

        # horizontal rule
        if re.fullmatch(r"-{3,}|\*{3,}|_{3,}", stripped):
            flow.append(Spacer(1, 4))
            flow.append(HRFlowable(width="100%", thickness=0.6, color=RULE,
                                   spaceBefore=2, spaceAfter=10))
            i += 1
            continue

        # table
        if stripped.startswith("|") and i + 1 < len(lines) and \
                re.match(r"^\|[\s:|-]+\|?$", lines[i + 1].strip()):
            rows = [split_row(stripped)]
            i += 2
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(split_row(lines[i].strip()))
                i += 1
            flow.append(Spacer(1, 2))
            flow.append(build_table(rows, s, width))
            flow.append(Spacer(1, 10))
            continue

        # headings
        m = re.match(r"^(#{1,6})\s+(.*)$", stripped)
        if m:
            level, text = len(m.group(1)), m.group(2)
            if level == 1 and first_heading:
                flow.append(Paragraph(inline(text), s["title"]))
                first_heading = False
            else:
                flow.append(Paragraph(inline(text), s["h%d" % min(level, 3)]))
            i += 1
            continue

        # lists
        if re.match(r"^\s*([-*+]|\d+[.)])\s+", line):
            ordered = bool(re.match(r"^\s*\d+[.)]\s+", line))
            items = []
            while i < len(lines) and re.match(r"^\s*([-*+]|\d+[.)])\s+", lines[i]):
                text = re.sub(r"^\s*([-*+]|\d+[.)])\s+", "", lines[i])
                i += 1
                # continuation lines belonging to this item
                while (i < len(lines) and lines[i].strip()
                       and not re.match(r"^\s*([-*+]|\d+[.)])\s+", lines[i])
                       and not lines[i].strip().startswith(("#", "|", "```"))):
                    text += " " + lines[i].strip()
                    i += 1
                items.append(ListItem(Paragraph(inline(text), s["bullet"]),
                                      leftIndent=16))
            flow.append(ListFlowable(
                items,
                bulletType="1" if ordered else "bullet",
                bulletFontName="Times-Roman",
                bulletFontSize=9,
                start="1" if ordered else None,
                leftIndent=16,
            ))
            flow.append(Spacer(1, 5))
            continue

        # paragraph
        buf = [stripped]
        i += 1
        while (i < len(lines) and lines[i].strip()
               and not lines[i].strip().startswith(("#", "|", "```", "- ", "* "))
               and not re.fullmatch(r"-{3,}", lines[i].strip())):
            buf.append(lines[i].strip())
            i += 1
        flow.append(Paragraph(inline(" ".join(buf)), s["body"]))

    return flow


def render(src, dst):
    md = Path(src).read_text(encoding="utf-8")
    s = styles()

    dst = Path(dst)
    dst.parent.mkdir(parents=True, exist_ok=True)

    margin = 0.9 * inch
    width = LETTER[0] - margin * 2

    doc = BaseDocTemplate(
        str(dst), pagesize=LETTER,
        leftMargin=margin, rightMargin=margin,
        topMargin=margin, bottomMargin=margin,
        title=Path(src).stem, author="Matt",
    )

    def decorate(canvas, d):
        canvas.saveState()
        canvas.setFont("Helvetica", 8)
        canvas.setFillColor(MUTED)
        canvas.drawCentredString(LETTER[0] / 2, margin / 2 + 2, str(d.page))
        canvas.restoreState()

    frame = Frame(margin, margin, width, LETTER[1] - margin * 2, id="body")
    doc.addPageTemplates([PageTemplate(id="main", frames=[frame],
                                       onPage=decorate)])
    doc.build(parse(md, s, width))
    print("wrote %s" % dst)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    render(sys.argv[1], sys.argv[2])
