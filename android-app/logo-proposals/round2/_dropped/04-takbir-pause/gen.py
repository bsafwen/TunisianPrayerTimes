"""Generator for 04-takbir-pause: two hands raised for the opening takbir = pause sign.

Run: python -I gen.py            (writes the final layers into this folder)
     python -I gen.py --vars     (also writes every explored variant into _var/<name>/)
Standard library only. Coordinates in dp on the 108x108 adaptive canvas.
"""

import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CX = 54.0
XFORM = {"s": 1.0, "dy": 0.0}   # global scale about the centre and vertical shift

PALETTES = {
    "olive": {"bg": ("#5E7A2E", "#34481A"), "glow": "#6E8C38", "hand": "#FFF8EC", "sleeve": "#EADFC6"},
    "olive2": {"bg": ("#6A8A30", "#33471A"), "hand": "#FFF8EC", "sleeve": "#E8DCC0"},
    "olive2-red": {"bg": ("#6A8A30", "#33471A"), "hand": "#FFF8EC", "sleeve": "#B5402B"},
    "madder": {"bg": ("#9E3324", "#6E2016"), "hand": "#FFF8EC", "sleeve": "#F1DCC4"},
}


def f(v: float) -> str:
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


class P:
    """Tiny path builder; mirror=True reflects x around the canvas centre."""

    def __init__(self, mirror: bool = False):
        self.d = []
        self.m = mirror

    def x(self, x):
        return 2 * CX - x if self.m else x

    def pt(self, x, y):
        s, dy = XFORM["s"], XFORM["dy"]
        x = CX + s * (self.x(x) - CX)
        y = CX + s * (y - CX) + dy
        return f"{f(x)} {f(y)}"

    def M(self, x, y):
        self.d.append("M" + self.pt(x, y))
        return self

    def L(self, x, y):
        self.d.append("L" + self.pt(x, y))
        return self

    def C(self, x1, y1, x2, y2, x, y):
        self.d.append("C" + self.pt(x1, y1) + " " + self.pt(x2, y2) + " " + self.pt(x, y))
        return self

    def Z(self):
        self.d.append("Z")
        return self

    def s(self):
        return "".join(self.d)


K = 0.5523  # cubic circle constant


def cap(p, x0, x1, ytop, rise):
    """Rounded fingertip from (x0, ytop+rise) over the top to (x1, ytop+rise)."""
    w = x1 - x0
    xm = (x0 + x1) / 2
    p.C(x0, ytop + rise - rise * K, xm - w / 2 * K, ytop, xm, ytop)
    p.C(xm + w / 2 * K, ytop, x1, ytop + rise - rise * K, x1, ytop + rise)


def slit_bottom(p, x0, x1, y):
    """Round bottom of a finger cut from (x0, y) to (x1, y)."""
    r = (x1 - x0) / 2
    xm = x0 + r
    p.C(x0, y + r * K, xm - r * K, y + r, xm, y + r)
    p.C(xm + r * K, y + r, x1, y + r * K, x1, y)


def arc(p, cx, cy, r, a0, a1):
    """Circular arc as cubics, angles in radians (y-down: increasing = clockwise)."""
    n = max(1, math.ceil(abs(a1 - a0) / (math.pi / 2) - 1e-9))
    da = (a1 - a0) / n
    k = 4 / 3 * math.tan(da / 4) * r
    for i in range(n):
        a, b = a0 + i * da, a0 + (i + 1) * da
        x0, y0 = cx + r * math.cos(a), cy + r * math.sin(a)
        x3, y3 = cx + r * math.cos(b), cy + r * math.sin(b)
        p.C(x0 - k * math.sin(a), y0 + k * math.cos(a), x3 + k * math.sin(b), y3 - k * math.cos(b), x3, y3)


def corner(p, a, c, b, r):
    """Line towards corner c (coming from a, leaving to b), rounded with radius-ish r."""
    def towards(u, v, dist):
        dx, dy = v[0] - u[0], v[1] - u[1]
        ln = math.hypot(dx, dy)
        return (u[0] + dx / ln * dist, u[1] + dy / ln * dist)
    p1 = towards(c, a, r)
    p2 = towards(c, b, r)
    p.L(*p1)
    q1 = (p1[0] + (c[0] - p1[0]) * 0.6, p1[1] + (c[1] - p1[1]) * 0.6)
    q2 = (p2[0] + (c[0] - p2[0]) * 0.6, p2[1] + (c[1] - p2[1]) * 0.6)
    p.C(*q1, *q2, *p2)


def hand(g, mirror=False):
    """Left hand on the canvas (the person's right hand), thumb on the inner side.

    The four fingers stand on [L, Rf]; the palm spans [L, R]; the thumb tip sits
    beside the index finger above the palm's inner strip, leaning slightly into
    the gap, so the bar keeps a straight inner edge below the thumb.
    """
    p = P(mirror)
    L, R, Rf = g["L"], g["R"], g["Rf"]
    c = g["cut"]
    fw = (Rf - L - 3 * c) / 4
    tops = g["tops"]          # fingertip tops, outer (little) to inner (index)
    ycut = g["ycut"]          # where the finger cuts end
    yw = g["yw"]              # wrist line (bottom of the palm)
    rise = g["rise"]          # height of the rounded part of a fingertip
    rc = g.get("rc", 1.6)     # bottom corner radius of the palm
    yn = g["yn"]              # bottom of the V between index and thumb
    tcx, tcy = g["tip"]       # thumb tip circle centre
    rt = g["rt"]
    yj = g["yj"]              # thumb's outer edge lands on the palm edge

    wt = g.get("wrist_in", 0.0)
    p.M(L + wt, yw - rc)
    if wt:
        yb = yw - g.get("bulge_h", 10.0)
        p.C(L + wt, yw - rc - 3.0, L, yb + 3.5, L, yb)
    p.L(L, tops[0] + rise)
    x = L
    for i, t in enumerate(tops):
        cap(p, x, x + fw, t, rise)
        if i < len(tops) - 1:
            p.L(x + fw, ycut)
            slit_bottom(p, x + fw, x + fw + c, ycut)
            p.L(x + fw + c, tops[i + 1] + rise)
            x += fw + c
    # V notch: index edge straight down, thumb's finger side back up
    nb = (Rf + g.get("v_bottom_w", 0.5), yn)
    dx, dy = nb[0] - tcx, nb[1] - tcy
    d = math.hypot(dx, dy)
    a0 = math.atan2(dy, dx) + math.acos(rt / d)
    tp = (tcx + rt * math.cos(a0), tcy + rt * math.sin(a0))
    nr = g.get("notch_r", 0.45)
    p.L(Rf, yn - nr)
    p.C(Rf, yn - nr + nr * K, nb[0] - nr * 0.6, yn, nb[0], yn)
    p.L(*tp)
    # outer edge of the thumb: tangent from the palm point (R, yj) to the tip
    q = (R, yj)
    dx, dy = q[0] - tcx, q[1] - tcy
    d = math.hypot(dx, dy)
    a1 = math.atan2(dy, dx) - math.acos(rt / d) + 2 * math.pi
    arc(p, tcx, tcy, rt, a0, a1)
    ex, ey = tcx + rt * math.cos(a1), tcy + rt * math.sin(a1)
    # gentle convex bow on the thumb's outer edge, landing vertical on the palm
    bow = g.get("bow", 0.6)
    ux, uy = q[0] - ex, q[1] - ey
    ln = math.hypot(ux, uy)
    nx, ny = uy / ln, -ux / ln
    if nx < 0:
        nx, ny = -nx, -ny
    p.C(ex + ux / 3 + nx * bow, ey + uy / 3 + ny * bow, R + 0.0, yj - ln * 0.28, R, yj)
    p.L(R, yw - rc)
    p.C(R, yw - rc + rc * K, R - rc + rc * K, yw, R - rc, yw)
    p.L(L + wt + rc, yw)
    p.C(L + wt + rc - rc * K, yw, L + wt, yw - rc + rc * K, L + wt, yw - rc)
    p.Z()
    return p.s()


def sleeve(g, mirror=False):
    p = P(mirror)
    so, si = g["sl_out"], g["sl_in"]
    y0, y1 = g["yw"] + g["cuff_gap"], g["ys"]
    x0, x1 = g["L"] - so, g["R"] + si
    style = g.get("sleeve", "rect")
    r = 1.6
    if style == "rect":
        p.M(x0 + r, y0)
        p.L(x1 - r, y0)
        p.C(x1 - r + r * K, y0, x1, y0 + r - r * K, x1, y0 + r)
        p.L(x1, y1)
        p.L(x0, y1)
        p.L(x0, y0 + r)
        p.C(x0, y0 + r - r * K, x0 + r - r * K, y0, x0 + r, y0)
        p.Z()
    elif style == "converge":
        # inner edge slants down towards the centre: two sleeves meet one body
        xb = g.get("sl_meet", CX - 1.0)
        p.M(x0 + r, y0)
        p.L(x1 - r, y0)
        p.C(x1 - r + r * K, y0, x1, y0 + r - r * K, x1, y0 + r)
        p.C(x1, y0 + (y1 - y0) * 0.55, xb, y1 - (y1 - y0) * 0.35, xb, y1)
        p.L(x0, y1)
        p.L(x0, y0 + r)
        p.C(x0, y0 + r - r * K, x0 + r - r * K, y0, x0 + r, y0)
        p.Z()
    elif style == "flare":
        # long sleeve running out of the frame, outer edge flaring a little
        fl = g.get("flare", 2.0)
        yb = g.get("ybleed", 96.0)
        p.M(x0 + r, y0)
        p.L(x1 - r, y0)
        p.C(x1 - r + r * K, y0, x1, y0 + r - r * K, x1, y0 + r)
        p.L(x1, yb)
        p.L(x0 - fl * (yb - y0) / 25.0, yb)
        p.L(x0, y0 + r)
        p.C(x0, y0 + r - r * K, x0 + r - r * K, y0, x0 + r, y0)
        p.Z()
    elif style == "clip":
        # flared sleeve whose bottom follows one circle around the icon centre:
        # both sleeves end on the same shoulder arc (one person), ink stays inside
        fl = g.get("flare", 2.0)
        rr = g.get("clip_r", 32.0)
        sx = -fl / 25.0                      # outer edge: x = x0 + sx * t, y = y0 + t
        a_ = sx * sx + 1
        b_ = 2 * (sx * (x0 - CX) + (y0 - CX))
        c_ = (x0 - CX) ** 2 + (y0 - CX) ** 2 - rr * rr
        t = (-b_ + math.sqrt(b_ * b_ - 4 * a_ * c_)) / (2 * a_)
        xo, yo_ = x0 + sx * t, y0 + t
        yi = CX + math.sqrt(rr * rr - (x1 - CX) ** 2)
        ai = math.atan2(yi - CX, x1 - CX)
        ao = math.atan2(yo_ - CX, xo - CX)
        p.M(x0 + r, y0)
        p.L(x1 - r, y0)
        p.C(x1 - r + r * K, y0, x1, y0 + r - r * K, x1, y0 + r)
        p.L(x1, yi)
        arc(p, CX, CX, rr, ai, ao)
        p.L(x0, y0 + r)
        p.C(x0, y0 + r - r * K, x0 + r - r * K, y0, x0 + r, y0)
        p.Z()
    elif style == "smile":
        # both sleeve bottoms lie on one shallow arc: one garment, one person
        rr = g.get("smile_r", 46.0)
        cy = y1 - rr
        def ya(x):
            return cy + math.sqrt(rr * rr - (x - CX) ** 2)
        p.M(x0 + r, y0)
        p.L(x1 - r, y0)
        p.C(x1 - r + r * K, y0, x1, y0 + r - r * K, x1, y0 + r)
        p.L(x1, ya(x1))
        arc(p, CX, cy, rr, math.atan2(ya(x1) - cy, x1 - CX), math.atan2(ya(x0) - cy, x0 - CX))
        p.L(x0, y0 + r)
        p.C(x0, y0 + r - r * K, x0 + r - r * K, y0, x0 + r, y0)
        p.Z()
    elif style == "cuff":
        # a short cuff band only (no long sleeve)
        p.M(x0 + r, y0)
        p.L(x1 - r, y0)
        p.C(x1 - r + r * K, y0, x1, y0 + r - r * K, x1, y0 + r)
        p.L(x1, y1 - r)
        p.C(x1, y1 - r + r * K, x1 - r + r * K, y1, x1 - r, y1)
        p.L(x0 + r, y1)
        p.C(x0 + r - r * K, y1, x0, y1 - r + r * K, x0, y1 - r)
        p.L(x0, y0 + r)
        p.C(x0, y0 + r - r * K, x0 + r - r * K, y0, x0 + r, y0)
        p.Z()
    return p.s()


def collar(g):
    """Optional: both sleeves grow out of one robe band with a V neckline."""
    if not g.get("neck"):
        return ""
    vd, bh = g["neck"]          # V depth below the band top, band height
    x0, x1 = g["L"] - g["sl_out"], g["R"] + g["sl_in"]
    y1 = g["ys"]
    yb = y1 - bh
    p = P()
    p.M(x0, yb)
    p.L(x1, yb)
    p.L(CX, yb + vd)
    p.L(2 * CX - x1, yb)
    p.L(2 * CX - x0, yb)
    p.L(2 * CX - x0, y1 - 1.6)
    p.C(2 * CX - x0, y1 - 1.6 + 1.6 * K, 2 * CX - x0 - 1.6 + 1.6 * K, y1, 2 * CX - x0 - 1.6, y1)
    p.L(x0 + 1.6, y1)
    p.C(x0 + 1.6 - 1.6 * K, y1, x0, y1 - 1.6 + 1.6 * K, x0, y1 - 1.6)
    p.Z()
    return p.s()


def svg(body: str, defs: str = "") -> str:
    d = f"  <defs>\n{defs}  </defs>\n" if defs else ""
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">\n'
            f"{d}{body}</svg>\n")


def bg_parts(pal):
    if pal.endswith("-glow"):
        a, b = PALETTES[pal[:-5]]["bg"]
        glow = PALETTES[pal[:-5]].get("glow", a)
        defs = ('    <radialGradient id="bg" gradientUnits="userSpaceOnUse" cx="54" cy="38" r="70">\n'
                f'      <stop offset="0" stop-color="{glow}"/>\n'
                f'      <stop offset="0.45" stop-color="{a}"/>\n'
                f'      <stop offset="1" stop-color="{b}"/>\n'
                "    </radialGradient>\n")
        body = '  <rect x="0" y="0" width="108" height="108" fill="url(#bg)"/>\n'
        return defs, body
    a, b = PALETTES[pal]["bg"]
    defs = ('    <linearGradient id="bg" gradientUnits="userSpaceOnUse" x1="30" y1="0" x2="78" y2="108">\n'
            f'      <stop offset="0" stop-color="{a}"/>\n'
            f'      <stop offset="1" stop-color="{b}"/>\n'
            "    </linearGradient>\n")
    body = '  <rect x="0" y="0" width="108" height="108" fill="url(#bg)"/>\n'
    return defs, body


def fg_body(g, pal, mono=False):
    base = pal[:-5] if pal.endswith("-glow") else pal
    hand_c = "#000000" if mono else PALETTES[base]["hand"]
    sl_c = "#000000" if mono else PALETTES[base]["sleeve"]
    hands = hand(g) + hand(g, True)
    sleeves = sleeve(g) + sleeve(g, True) + collar(g)
    if mono:
        return f'  <path fill="{hand_c}" fill-rule="evenodd" d="{hands}{sleeves}"/>\n'
    return (f'  <path fill="{sl_c}" d="{sleeves}"/>\n'
            f'  <path fill="{hand_c}" d="{hands}"/>\n')


def write(dirpath: Path, g, pal):
    dirpath.mkdir(parents=True, exist_ok=True)
    XFORM["s"] = g.get("scale", 1.0)
    XFORM["dy"] = g.get("shift", 0.0)
    defs, bgb = bg_parts(pal)
    files = {
        "background.svg": svg(bgb, defs),
        "foreground.svg": svg(fg_body(g, pal)),
        "monochrome.svg": svg(fg_body(g, pal, mono=True)),
        "icon.svg": svg(bgb + fg_body(g, pal), defs),
    }
    for name, text in files.items():
        with open(dirpath / name, "w", newline="\n", encoding="utf-8") as fh:
            fh.write(text)


def geom(**over):
    """Base geometry: bars 14.5 wide, gap 10, centred on x = 54."""
    g = dict(
        L=34.5, R=49.0,
        cut=0.9, rise=1.5,
        tops=[33.4, 30.4, 29.4, 30.6],
        ycut=38.0,
        y_open=42.0, notch=(46.2, 49.0), tip=(49.2, 46.4), rt=2.1, yj=60.0,
        yw=67.0, rc=1.6,
        cuff_gap=2.2, ys=79.0, sl_out=0.8, sl_in=0.8,
    )
    g.update(over)
    return g


BIG = dict(notch=(45.6, 49.6), tip=(49.5, 45.6), rt=2.3, yj=61.0, ycut=39.0)

BIG = dict(notch=(45.6, 49.6), tip=(49.5, 45.6), rt=2.3, yj=61.0, ycut=39.0)
UP = dict(tops=[31.4, 28.4, 27.4, 28.6], ycut=37.5, y_open=40.0,
          notch=(45.6, 47.8), tip=(49.5, 43.8), rt=2.3, yj=59.0, yw=64.0)

UP = dict(tops=[31.4, 28.4, 27.4, 28.6], ycut=37.5, y_open=40.0,
          notch=(45.6, 47.8), tip=(49.5, 43.8), rt=2.3, yj=59.0, yw=64.0)
E = dict(L=34.0, R=48.5, cut=1.1, rise=1.6,
         tops=[31.6, 28.4, 27.4, 28.6], ycut=37.8, y_open=39.6,
         notch=(44.9, 47.6), tip=(49.1, 43.4), rt=2.45, yj=59.0, yw=64.0,
         wrist_in=0.8, sleeve="flare", flare=2.0, sl_out=0.8, sl_in=0.8)

F = dict(L=33.5, R=49.0, Rf=45.6, cut=1.0, rise=1.6,
         tops=[31.4, 28.2, 27.2, 28.4], ycut=37.6,
         yn=50.0, tip=(49.4, 41.2), rt=2.3, yj=57.5, bow=0.6, yw=64.0,
         wrist_in=0.8, sleeve="flare", flare=2.0, sl_out=0.8, sl_in=0.8,
         cuff_gap=2.2, ybleed=96.0)

G = {**F, "tip": (49.1, 41.4), "rt": 2.25, "Rf": 45.2, "yn": 47.2, "v_bottom_w": 0.9,
     "notch_r": 0.7, "cut": 1.1}

VARIANTS = {
    "h-bleed": (geom(**G), "olive"),
    "i-lower": (geom(**{**G, "shift": 2.0, "scale": 0.97, "ybleed": 100.0}), "olive"),
    "i-lower-olive2": (geom(**{**G, "shift": 2.0, "scale": 0.97, "ybleed": 90.6}), "olive2"),
    "i-lower-madder": (geom(**{**G, "shift": 2.0, "scale": 0.97, "ybleed": 100.0}), "madder"),
    "i-clip33-olive2": (geom(**{**G, "shift": 2.0, "scale": 0.97, "sleeve": "clip", "clip_r": 33.0}), "olive2"),
}
VARIANTS["i-lower-redsleeve"] = (VARIANTS["i-lower-olive2"][0], "olive2-red")
VARIANTS["final"] = VARIANTS["i-lower-olive2"]


def main(argv):
    if "--vars" in argv:
        for name, (g, pal) in VARIANTS.items():
            if name != "final":
                write(HERE / "_var" / name, g, pal)
    final = VARIANTS.get("final")
    if final:
        write(HERE, *final)


if __name__ == "__main__":
    main(sys.argv[1:])
