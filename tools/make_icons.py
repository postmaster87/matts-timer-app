"""Generate PWA icons for matts-timer-app. Pure stdlib (zlib + struct), no deps."""
import math, os, struct, zlib

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "icons")
BG = (0x0B, 0x0F, 0x14)
GREEN = (0x21, 0xC5, 0x5D)
RING_BG = (0x1E, 0x28, 0x33)

SS = 3  # supersample factor


def shade(u, v, scale):
    """u,v in [0,1] canvas space -> RGB. `scale` shrinks the glyph (maskable safe zone)."""
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
            return BG
        return GREEN

    # inner face
    if r < r_in:
        # hand pointing to ~2 o'clock
        ha = math.radians(-52.0)
        hx, hy = math.cos(ha), math.sin(ha)
        proj = dx * hx + dy * hy
        perp = abs(-dx * hy + dy * hx)
        if 0.0 <= proj <= 0.20 and perp <= 0.026:
            return GREEN
        if r <= 0.035:
            return GREEN
        return RING_BG

    return BG


def render(size, scale, path):
    hi = size * SS
    rows = []
    inv = 1.0 / hi
    for py in range(size):
        row = bytearray()
        for px in range(size):
            rr = gg = bb = 0
            for sy in range(SS):
                v = ((py * SS) + sy + 0.5) * inv
                for sx in range(SS):
                    u = ((px * SS) + sx + 0.5) * inv
                    c = shade(u, v, scale)
                    rr += c[0]; gg += c[1]; bb += c[2]
            n = SS * SS
            row += bytes((rr // n, gg // n, bb // n))
        rows.append(row)

    raw = b"".join(b"\x00" + bytes(r) for r in rows)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)
    print(path, os.path.getsize(path), "bytes")


os.makedirs(OUT, exist_ok=True)
render(192, 1.00, os.path.join(OUT, "icon-192.png"))
render(512, 1.00, os.path.join(OUT, "icon-512.png"))
render(512, 0.78, os.path.join(OUT, "icon-maskable-512.png"))
