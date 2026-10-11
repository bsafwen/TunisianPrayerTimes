"""Generate the «طمأنينة» shh icon: lips (the loop of ط) with a finger (its stem) held across them.

Usage: python -I gen.py            writes the final layers here and every variant under _var/
Standard library only. All geometry is cubic Bezier; the lips are clipped exactly
(de Casteljau splits) so the knockout gaps beside the finger are true holes.
"""

import math
from pathlib import Path

HERE = Path(__file__).resolve().parent
BG_TOP, BG_BOTTOM = "#2169B5", "#0F4C8F"
INK = "#FFFFFF"
K = 0.5523  # quarter-circle Bezier constant


# ---------- Bezier helpers ----------
def lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


def bez(seg, t):
    p0, p1, p2, p3 = seg
    a, b, c = lerp(p0, p1, t), lerp(p1, p2, t), lerp(p2, p3, t)
    d, e = lerp(a, b, t), lerp(b, c, t)
    return lerp(d, e, t)


def split(seg, t):
    p0, p1, p2, p3 = seg
    a, b, c = lerp(p0, p1, t), lerp(p1, p2, t), lerp(p2, p3, t)
    d, e = lerp(a, b, t), lerp(b, c, t)
    m = lerp(d, e, t)
    return (p0, a, d, m), (m, e, c, p3)


def line(p, q):
    return (p, lerp(p, q, 1 / 3), lerp(p, q, 2 / 3), q)


def roots(seg, f, n=96):
    ts, prev_t, prev_v = [], 0.0, f(bez(seg, 0.0))
    for i in range(1, n + 1):
        t = i / n
        v = f(bez(seg, t))
        if (prev_v < 0) != (v < 0):
            lo, hi, vlo = prev_t, t, prev_v
            for _ in range(50):
                mid = (lo + hi) / 2
                vm = f(bez(seg, mid))
                if (vm < 0) == (vlo < 0):
                    lo, vlo = mid, vm
                else:
                    hi = mid
            ts.append((lo + hi) / 2)
        prev_t, prev_v = t, v
    return [t for t in ts if 1e-6 < t < 1 - 1e-6]


def clip(segs, f):
    """Keep the part of a closed cubic subpath where f(p) <= 0; close it along the cut."""
    pieces = []
    for s in segs:
        last = 0.0
        rest = s
        for t in roots(s, f):
            a, rest = split(rest, (t - last) / (1 - last))
            pieces.append(a)
            last = t
        pieces.append(rest)
    inside = [f(bez(p, 0.5)) <= 0 for p in pieces]
    if all(inside):
        return list(segs)
    if not any(inside):
        return None
    n = len(pieces)
    start = next(i for i in range(n) if inside[i] and not inside[i - 1])
    out, i = [], start
    for _ in range(n):
        if inside[i]:
            out.append(pieces[i])
        elif inside[i - 1]:
            # leaving: bridge to the next entry along the cut
            j = i
            while not inside[j % n]:
                j += 1
            out.append(line(out[-1][3], pieces[j % n][0]))
        i = (i + 1) % n
    return out


XF = [1.0, 0.0, 0.0]  # global scale about (54, 54), then shift


def T(p):
    k, dx, dy = XF
    return (54 + (p[0] - 54) * k + dx, 54 + (p[1] - 54) * k + dy)


def d_of(subpaths):
    parts = []
    for segs in subpaths:
        if not segs:
            continue
        p = T(segs[0][0])
        s = [f"M{p[0]:.2f},{p[1]:.2f}"]
        for _, a, b, c in segs:
            a, b, c = T(a), T(b), T(c)
            s.append(f"C{a[0]:.2f},{a[1]:.2f} {b[0]:.2f},{b[1]:.2f} {c[0]:.2f},{c[1]:.2f}")
        s.append("Z")
        parts.append("".join(s))
    return "".join(parts)


def chain(start, *ctrl):
    """chain(p0, (c1, c2, p1), (c1, c2, p2), ...) -> list of cubic segments."""
    segs, p = [], start
    for c1, c2, q in ctrl:
        segs.append((p, c1, c2, q))
        p = q
    return segs


def reverse(segs):
    return [(s[3], s[2], s[1], s[0]) for s in reversed(segs)]


def capsule_band(points, t):
    """A thin closed band of half-width t/2 around a smooth open polyline of cubic segments,
    with round caps. points: list of cubic segments (the centre line)."""
    # sample-based offset fitted back to cubics (Catmull-Rom through offset samples)
    samples = []
    for k, s in enumerate(points):
        for i in range(0 if k == 0 else 1, 13):
            samples.append(bez(s, i / 12))

    def normal(i):
        a = samples[max(i - 1, 0)]
        b = samples[min(i + 1, len(samples) - 1)]
        dx, dy = b[0] - a[0], b[1] - a[1]
        L = math.hypot(dx, dy) or 1
        return (-dy / L, dx / L)

    h = t / 2
    left = [(p[0] + normal(i)[0] * h, p[1] + normal(i)[1] * h) for i, p in enumerate(samples)]
    right = [(p[0] - normal(i)[0] * h, p[1] - normal(i)[1] * h) for i, p in enumerate(samples)]

    def cr(pts):
        segs = []
        for i in range(len(pts) - 1):
            p0 = pts[max(i - 1, 0)]
            p1, p2 = pts[i], pts[i + 1]
            p3 = pts[min(i + 2, len(pts) - 1)]
            c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
            c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
            segs.append((p1, c1, c2, p2))
        return segs

    segs = cr(left)
    segs += cap_end(left[-1], right[-1], samples[-1], samples[-2])
    segs += reverse(cr(right))
    segs += cap_end(right[0], left[0], samples[0], samples[1])
    return segs


def cap_end(a, b, centre, inner):
    """Round cap from a to b around centre, bulging away from `inner`."""
    ux, uy = centre[0] - inner[0], centre[1] - inner[1]
    L = math.hypot(ux, uy) or 1
    r = math.hypot(a[0] - centre[0], a[1] - centre[1])
    ox, oy = ux / L * r, uy / L * r
    mid = (centre[0] + ox, centre[1] + oy)
    ax, ay = a[0] - centre[0], a[1] - centre[1]
    bx, by = b[0] - centre[0], b[1] - centre[1]
    return [
        (a, (a[0] + ox * K, a[1] + oy * K), (mid[0] + ax * K, mid[1] + ay * K), mid),
        (mid, (mid[0] + bx * K, mid[1] + by * K), (b[0] + ox * K, b[1] + oy * K), b),
    ]


def ellipse(cx, cy, rx, ry):
    return chain(
        (cx + rx, cy),
        ((cx + rx, cy + ry * K), (cx + rx * K, cy + ry), (cx, cy + ry)),
        ((cx - rx * K, cy + ry), (cx - rx, cy + ry * K), (cx - rx, cy)),
        ((cx - rx, cy - ry * K), (cx - rx * K, cy - ry), (cx, cy - ry)),
        ((cx + rx * K, cy - ry), (cx + rx, cy - ry * K), (cx + rx, cy)),
    )


# ---------- the drawing ----------
def lips(c):
    cx, cy, w = c["lx"], c["ly"], c["lw"]
    L, R = (cx - w, cy), (cx + w, cy)
    top, dip, bot = c["peak_y"], c["dip_y"], c["bot_y"]
    mx = cx + c.get("bow_dx", 0.0)          # the cupid's bow (and mouth-line tubercle) sits here
    pl = c.get("peak_l", c["peak_dx"])
    pr = c.get("peak_r", c["peak_dx"])
    P1, D, P2 = (mx - pl, top), (mx, dip), (mx + pr, top)
    sl, sr = P1[0] - L[0], R[0] - P2[0]
    rise = (cy - top) * 0.34
    outer = chain(
        L,
        ((L[0] + sl * 0.30, cy - rise), (P1[0] - sl * 0.34, top), P1),
        ((P1[0] + pl * 0.42, top), (mx - pl * 0.30, dip - 0.3), D),
        ((mx + pr * 0.30, dip - 0.3), (P2[0] - pr * 0.42, top), P2),
        ((P2[0] + sr * 0.34, top), (R[0] - sr * 0.30, cy - rise), R),
        ((R[0] - w * 0.22, cy + (bot - cy) * 0.80), (cx + w * 0.50, bot), (cx, bot)),
        ((cx - w * 0.50, bot), (L[0] + w * 0.22, cy + (bot - cy) * 0.80), L),
    )
    # the closed-mouth line: a thin lens that tapers into the corners (the loop's counter)
    e = c["line_end"]
    mt, mb, sag = c["line_top_sag"], c["line_gap"], c["line_dip"]
    a, b = (cx - w + e, cy + 0.2), (cx + w - e, cy + 0.2)
    ka, kb = (mx - a[0]), (b[0] - mx)
    mouth = chain(
        a,
        ((a[0] + ka * 0.6, cy - 0.2), (mx - 5.0, cy + mt), (mx, cy + mt + sag)),
        ((mx + 5.0, cy + mt), (b[0] - kb * 0.6, cy - 0.2), b),
        ((b[0] - kb * 0.6, cy + mb * 0.75), (mx + 7.5, cy + mt + mb), (mx, cy + mt + mb + sag * 0.5)),
        ((mx - 7.5, cy + mt + mb), (a[0] + ka * 0.6, cy + mb * 0.75), a),
    )
    return outer, mouth


def finger(c):
    fx, tip, base = c["fx"], c["tip_y"], c["base_y"]
    rt, rb = c["hw_tip"], c["hw_base"]
    # tip: a half-ellipse a touch taller than wide, sides taper out to the base
    th = rt * 1.08
    tl, tr = (fx - rt, tip + th), (fx + rt, tip + th)
    bl, br = (fx - rb, base - c["base_r"]), (fx + rb, base - c["base_r"])
    br_r = c["base_r"]
    segs = chain(
        (fx, tip),
        ((fx + rt * K, tip), (fx + rt, tip + th - th * K), tr),
        ((tr[0] + (br[0] - tr[0]) / 3, tr[1] + (br[1] - tr[1]) / 3),
         (tr[0] + 2 * (br[0] - tr[0]) / 3, tr[1] + 2 * (br[1] - tr[1]) / 3), br),
        ((br[0], br[1] + br_r * K), (fx + rb * K * 0.9 if br_r >= rb else br[0] - br_r + br_r * K, base), (fx, base) if br_r >= rb else (br[0] - br_r, base)),
    )
    if br_r < rb:
        segs.append(line(segs[-1][3], (bl[0] + br_r, base)))
        segs += chain(segs[-1][3], ((bl[0] + br_r - br_r * K, base), (bl[0], bl[1] + br_r * K), bl))
    else:
        segs += chain(segs[-1][3], ((fx - rb * K * 0.9, base), (bl[0], bl[1] + br_r * K), bl))
    segs += chain(
        segs[-1][3],
        ((bl[0] + (tl[0] - bl[0]) / 3, bl[1] + (tl[1] - bl[1]) / 3),
         (bl[0] + 2 * (tl[0] - bl[0]) / 3, bl[1] + 2 * (tl[1] - bl[1]) / 3), tl),
        ((fx - rt, tip + th - th * K), (fx - rt * K, tip), (fx, tip)),
    )

    holes = []
    # fingernail: the cuticle as a U-shaped cut a little below the tip
    nail = c.get("nail")
    if nail:
        ny, nw, arm, ry, nt = nail  # arm top y, half-width, straight arm length, bottom depth, cut width
        yb = ny + arm
        centre = chain(
            (fx - nw, ny),
            ((fx - nw, ny + arm / 3), (fx - nw, ny + 2 * arm / 3), (fx - nw, yb)),
            ((fx - nw, yb + ry * K), (fx - nw * K, yb + ry), (fx, yb + ry)),
            ((fx + nw * K, yb + ry), (fx + nw, yb + ry * K), (fx + nw, yb)),
            ((fx + nw, ny + 2 * arm / 3), (fx + nw, ny + arm / 3), (fx + nw, ny)),
        )
        holes.append(capsule_band(centre, nt))
    crease = c.get("crease")
    if crease:
        cy_, cw, cb, ct = crease  # y, half-length, bow, cut width
        centre = chain((fx - cw, cy_), ((fx - cw * 0.4, cy_ + cb), (fx + cw * 0.4, cy_ + cb), (fx + cw, cy_)))
        holes.append(capsule_band(centre, ct))
    return segs, holes


def edge_fn(fc, side, gap):
    """Signed function for the cut beside the finger; <= 0 keeps the lip part."""
    fx, tip, base = fc["fx"], fc["tip_y"], fc["base_y"]
    rt, rb = fc["hw_tip"], fc["hw_base"]
    th = rt * 1.08
    y0, y1 = tip + th, base - fc["base_r"]

    def hw(y):
        t = (y - y0) / (y1 - y0)
        return rt + (rb - rt) * t

    if side < 0:
        return lambda p: p[0] - (fx - hw(p[1]) - gap)
    return lambda p: (fx + hw(p[1]) + gap) - p[0]


def crescent(c):
    x, y, r, off, ang = c["moon"]
    # outer circle minus offset circle, built as two arcs meeting at the horns
    a = math.radians(ang)
    ox, oy = x + off * math.cos(a), y + off * math.sin(a)
    # horns: intersections of circle(x,y,r) and circle(ox,oy,r*0.84)
    r2 = r * 0.84
    d = off
    l = (r * r - r2 * r2 + d * d) / (2 * d)
    h = math.sqrt(max(r * r - l * l, 0))
    ux, uy = math.cos(a), math.sin(a)
    px, py = x + ux * l, y + uy * l
    h1 = T((px - uy * h, py + ux * h))
    h2 = T((px + uy * h, py - ux * h))
    r, r2 = r * XF[0], r2 * XF[0]
    return (
        f"M{h1[0]:.2f},{h1[1]:.2f}A{r:.2f},{r:.2f} 0 1,1 {h2[0]:.2f},{h2[1]:.2f}"
        f"A{r2:.2f},{r2:.2f} 0 1,0 {h1[0]:.2f},{h1[1]:.2f}Z"
    )


def knuckle(c):
    """The curled middle finger: a rounded lump beside the base of the index finger."""
    kx0, ky0, kx1, ky1, r = c["knuckle"]
    return chain(
        (kx0, ky0),
        line((kx0, ky0), (kx1 - r, ky0))[1:],
        ((kx1 - r + r * K, ky0), (kx1, ky0 + r - r * K), (kx1, ky0 + r)),
        line((kx1, ky0 + r), (kx1, ky1 - r))[1:],
        ((kx1, ky1 - r + r * K), (kx1 - r + r * K, ky1), (kx1 - r, ky1)),
        line((kx1 - r, ky1), (kx0, ky1))[1:],
        line((kx0, ky1), (kx0, ky0))[1:],
    )


def artwork(c):
    XF[:] = [c.get("scale", 1.0), c.get("dx", 0.0), c.get("dy", 0.0)]
    outer, mouth = lips(c)
    fseg, fholes = finger(c)
    paths = []
    for side in (-1, 1):
        f = edge_fn(c, side, c["gap"])
        o = clip(outer, f)
        m = clip(mouth, f)
        paths.append(d_of([o] + ([m] if m else [])))
    paths.append(d_of([fseg] + fholes))
    if c.get("knuckle"):
        paths.append(d_of([knuckle(c)]))
    if c.get("moon"):
        paths.append(crescent(c))
    return paths


def svg(body, defs=""):
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">\n'
        + (f"<defs>{defs}</defs>\n" if defs else "")
        + body
        + "</svg>\n"
    )


GRAD = (
    '<linearGradient id="bg" gradientUnits="userSpaceOnUse" x1="30" y1="0" x2="78" y2="108">'
    f'<stop offset="0" stop-color="{BG_TOP}"/><stop offset="1" stop-color="{BG_BOTTOM}"/>'
    "</linearGradient>"
)


def write(dirpath, c):
    dirpath.mkdir(parents=True, exist_ok=True)
    paths = artwork(c)
    fg = "".join(f'<path fill="{INK}" fill-rule="evenodd" d="{d}"/>\n' for d in paths)
    mono = "".join(f'<path fill="#000000" fill-rule="evenodd" d="{d}"/>\n' for d in paths)
    bg_rect = '<rect x="0" y="0" width="108" height="108" fill="url(#bg)"/>\n'
    files = {
        "background.svg": svg(bg_rect, GRAD),
        "foreground.svg": svg(fg),
        "monochrome.svg": svg(mono),
        "icon.svg": svg(bg_rect + fg, GRAD),
    }
    for name, text in files.items():
        (dirpath / name).write_bytes(text.encode("utf-8"))


BASE = dict(
    # lips (the loop of ط)
    lx=58.0, ly=61.0, lw=26.0,
    peak_dx=5.4, peak_y=49.6, dip_y=54.6, bot_y=75.0,
    line_end=3.4, line_top_sag=0.0, line_gap=2.5, line_dip=0.6,
    # finger (the stem of ط)
    fx=47.0, tip_y=30.5, base_y=77.5, hw_tip=5.7, hw_base=6.2, base_r=2.4,
    nail=(33.6, 3.2, 3.2, 3.0, 1.6),
    crease=None,
    gap=2.3,
    moon=None,
    scale=0.88, dx=-1.0, dy=-1.0,
)

M = dict(lx=57.0, fx=50.0, bow_dx=3.6, peak_l=5.0, peak_r=6.6, dip_y=54.4,
         tip_y=32.0, base_y=79.0, dx=-1.5, dy=-1.0)
FINAL = dict(M, scale=0.86, dy=-3.5, nail=(33.6, 3.2, 3.2, 3.0, 1.8))

VARIANTS = {
    # explored and kept for the record (see notes.md)
    "a-offset": dict(fx=43.5, lx=56.0, lw=24.0, peak_dx=5.6, peak_y=51.0, dip_y=53.6, bot_y=73.0,
                     tip_y=28.0, base_y=78.0, hw_tip=4.7, hw_base=5.4, base_r=5.4,
                     nail=(34.6, 2.7, 0.0, 4.6, 1.7), gap=2.4, scale=1.0, dx=0.0, dy=0.0),
    "k-centred": dict(fx=54.0, lx=54.0, peak_dx=10.5, dip_y=53.0, peak_y=49.6, dx=0.0),
    "s-final-moon": dict(FINAL, moon=(75.5, 32.5, 6.2, 3.0, -155.0), dx=-2.5),
}


if __name__ == "__main__":
    import sys

    which = sys.argv[1:] or ["final"] + list(VARIANTS)
    if "final" in which:
        write(HERE, dict(BASE, **FINAL))
        print("wrote final")
    for name in [w for w in which if w != "final"]:
        cfg = dict(BASE, **VARIANTS[name])
        write(HERE / "_var" / name, cfg)
        print("wrote", name)
