"""Generate Android launcher icons for android/app/src/main/res/mipmap-*.

Pure stdlib (zlib + struct), no Pillow. Same stopwatch glyph as the web icons.
Writes RGBA PNGs: legacy square icons on the dark ground, and adaptive-icon
foregrounds on transparency (the background layer supplies the colour).
"""
import math
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

BG = (0x0B, 0x0F, 0x14, 255)
GREEN = (0x21, 0xC5, 0x5D, 255)
FACE = (0x1E, 0x28, 0x33, 255)
CLEAR = (0, 0, 0, 0)

SS = 3  # supersample factor

DENSITIES = ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]
LEGACY = [48, 72, 96, 144, 192]
FOREGROUND = [108, 162, 216, 324, 432]


def shade(u, v, scale, ground):
    """u,v in [0,1] canvas space -> RGBA. `scale` shrinks the glyph."""
    cx, cy = 0.5, 0.525
    dx, dy = (u - cx) / scale, (v - cy) / scale
    r = math.hypot(dx, dy)
    ang = math.degrees(math.atan2(dy, dx))  # -90 == straight up

    r_out, r_in = 0.345, 0.268

    # crown (stem on top of the dial)
    if abs(dx) <= 0.066 and (-r_out - 0.085) <= dy <= (-r_out + 0.02):
        return GREEN

    # dial ring, with a gap at 12 o'clock where the crown sits
    if r_in <= r <= r_out:
        if -103.0 <= ang <= -77.0:
            return ground
        return GREEN

    # inner face
    if r < r_in:
        ha = math.radians(-52.0)
        hx, hy = math.cos(ha), math.sin(ha)
        proj = dx * hx + dy * hy
        perp = abs(-dx * hy + dy * hx)
        if 0.0 <= proj <= 0.20 and perp <= 0.026:
            return GREEN
        if r <= 0.035:
            return GREEN
        return FACE

    return ground


def render(size, scale, ground, path):
    hi = size * SS
    inv = 1.0 / hi
    rows = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            rr = gg = bb = aa = 0
            for sy in range(SS):
                v = ((py * SS) + sy + 0.5) * inv
                for sx in range(SS):
                    u = ((px * SS) + sx + 0.5) * inv
                    c = shade(u, v, scale, ground)
                    a = c[3]
                    # premultiply so transparent edges do not fringe dark
                    rr += c[0] * a // 255
                    gg += c[1] * a // 255
                    bb += c[2] * a // 255
                    aa += a
            n = SS * SS
            a = aa // n
            if a == 0:
                row += bytes((0, 0, 0, 0))
            else:
                # un-premultiply back to straight alpha
                row += bytes((
                    min(255, (rr // n) * 255 // a),
                    min(255, (gg // n) * 255 // a),
                    min(255, (bb // n) * 255 // a),
                    a,
                ))
        rows.append(row)

    raw = b"".join(b"\x00" + bytes(r) for r in rows)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)
    return os.path.getsize(path)


total = 0
for dens, legacy_px, fg_px in zip(DENSITIES, LEGACY, FOREGROUND):
    d = os.path.join(RES, "mipmap-" + dens)
    total += render(legacy_px, 1.00, BG, os.path.join(d, "ic_launcher.png"))
    # adaptive foreground: glyph inside the 66/108 safe circle, transparent ground
    total += render(fg_px, 0.72, CLEAR, os.path.join(d, "ic_launcher_fg.png"))
    print("mipmap-%-8s ic_launcher %dpx, ic_launcher_fg %dpx" % (dens, legacy_px, fg_px))

print("total %d bytes" % total)
