"""Generator for proposal 08 «ششش» (Shhh), revision 2.

One calligraphic sheen in its initial form: three rounded teeth that step
down hard in reading order (right to left), three round dots, and a long
kashida that thins and fades toward the ground colour, ending in a soft
round tip.  Python 3.12, standard library only.

Usage: python -I gen.py            (writes the final set and _var/r2-*)
The round-1 generator is kept as _var/gen_r1.py.
"""

import math
from pathlib import Path

HERE = Path(__file__).parent

CREAM = "#FFF3E2"
TERRA_A = "#CC6A3E"
TERRA_B = "#A0462A"


def f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


# global placement: scale about (54, 54), then translate
XF = {"s": 1.0, "tx": 0.0, "ty": 0.0}


def tp(p):
    return (54 + (p[0] - 54) * XF["s"] + XF["tx"], 54 + (p[1] - 54) * XF["s"] + XF["ty"])


def pt(p):
    x, y = tp(p)
    return f"{f(x)} {f(y)}"


def fr(r):
    return f(r * XF["s"])


def place(s, tx, ty):
    XF.update(s=s, tx=tx, ty=ty)


# ---------------------------------------------------------------- colour

def hex2rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def rgb2hex(c):
    return "#" + "".join(f"{max(0, min(255, round(v))):02X}" for v in c)


def mix(a, b, t):
    """t = 0 gives a, t = 1 gives b."""
    A, B = hex2rgb(a), hex2rgb(b)
    return rgb2hex(tuple(A[i] + (B[i] - A[i]) * t for i in range(3)))


def ground_at(x, y):
    """Background gradient colour at (x, y): #CC6A3E at (30, 18) to #A0462A at (78, 90)."""
    gx, gy = 48, 72
    t = ((x - 30) * gx + (y - 18) * gy) / (gx * gx + gy * gy)
    return mix(TERRA_A, TERRA_B, min(max(t, 0), 1))


# ---------------------------------------------------------------- geometry

def bez(p0, p1, p2, p3, s):
    u = 1 - s
    return (
        u**3 * p0[0] + 3 * u * u * s * p1[0] + 3 * u * s * s * p2[0] + s**3 * p3[0],
        u**3 * p0[1] + 3 * u * u * s * p1[1] + 3 * u * s * s * p2[1] + s**3 * p3[1],
    )


def bez_d(p0, p1, p2, p3, s):
    u = 1 - s
    return (
        3 * u * u * (p1[0] - p0[0]) + 6 * u * s * (p2[0] - p1[0]) + 3 * s * s * (p3[0] - p2[0]),
        3 * u * u * (p1[1] - p0[1]) + 6 * u * s * (p2[1] - p1[1]) + 3 * s * s * (p3[1] - p2[1]),
    )


def hermite_path(func, s0, s1, n):
    """Cubic segments through func(s) for s in [s0, s1] (func returns a point)."""
    out = []
    ds = (s1 - s0) / n
    eps = 1e-4 * (1 if s1 > s0 else -1)

    def deriv(s):
        a = func(s - eps)
        b = func(s + eps)
        return ((b[0] - a[0]) / (2 * eps), (b[1] - a[1]) / (2 * eps))

    for i in range(n):
        sa = s0 + i * ds
        sb = sa + ds
        pa, pb = func(sa), func(sb)
        da, db = deriv(sa), deriv(sb)
        c1 = (pa[0] + da[0] * ds / 3, pa[1] + da[1] * ds / 3)
        c2 = (pb[0] - db[0] * ds / 3, pb[1] - db[1] * ds / 3)
        out.append(f"C {pt(c1)} {pt(c2)} {pt(pb)}")
    return out


def circle(x, y, r):
    return (f"M {pt((x - r, y))} A {fr(r)} {fr(r)} 0 1 0 {pt((x + r, y))} "
            f"A {fr(r)} {fr(r)} 0 1 0 {pt((x - r, y))} Z")


class Letter:
    def __init__(self, **kw):
        self.wt = kw.get("wt", 5.2)        # tooth width at the cap
        self.wb = kw.get("wb", 5.6)        # tooth width at the valley
        self.t = kw.get("t", 7.4)          # baseline thickness
        self.B = kw.get("B", 73.0)         # bottom of the baseline
        self.cx = kw.get("cx", [77.0, 67.4, 57.8])    # tooth centres, right to left
        self.top = kw.get("top", [43.6, 49.8, 55.7])  # tooth tops, right to left
        self.valley = kw.get("valley", 0.6)
        self.rc = kw.get("rc", 5.5)        # bottom-right corner
        self.K = kw.get("K", [(58, 69.3), (44, 69.3), (32, 71.4), (21.5, 66.0)])
        self.h0 = self.t / 2
        self.h1 = kw.get("tip", 1.8)       # tip radius (diameter 3.6)
        self.taper = kw.get("taper", 0.9)
        self.fil = kw.get("fil", 3.6)
        self.dots = kw.get("dots", [(62.6, 37.6), (57.6, 43.9), (67.6, 43.9)])
        self.dot_r = kw.get("dot_r", 3.0)

    def h(self, s):
        s = min(max(s, 0.0), 1.0)
        k = (0.5 + 0.5 * math.cos(math.pi * s)) ** self.taper
        return self.h1 + (self.h0 - self.h1) * k

    def side(self, s, sign):
        s = min(max(s, 0.0), 1.0)
        p = bez(*self.K, s)
        d = bez_d(*self.K, s)
        L = math.hypot(*d)
        n = (d[1] / L, -d[0] / L)
        if n[1] > 0:
            n = (-n[0], -n[1])
        hh = self.h(s) * sign
        return (p[0] + n[0] * hh, p[1] + n[1] * hh)

    def tip(self):
        return bez(*self.K, 1.0)

    def path(self):
        rt = self.wt / 2
        hb = self.wb / 2
        yb = self.B - self.t
        vy = yb - self.valley
        cmds = []
        top = lambda s: self.side(s, +1)
        bot = lambda s: self.side(s, -1)
        xs = list(reversed(self.cx))
        tops = list(reversed(self.top))
        left3 = xs[0] - hb
        sj = 0.0
        for i in range(2001):
            s = i / 2000
            if top(s)[0] <= left3 - self.fil:
                sj = s
                break
        tip_top = top(1.0)
        cmds.append(f"M {pt(tip_top)}")
        cmds += hermite_path(top, 1.0, sj, 10)
        pj = top(sj)
        fy = yb - self.fil * 1.1
        a, b = top(sj + 1e-3), top(max(sj - 1e-3, 0.0))
        dl = math.hypot(b[0] - a[0], b[1] - a[1])
        u = ((b[0] - a[0]) / dl, (b[1] - a[1]) / dl)
        k1 = self.fil * 0.58
        cmds.append(f"C {pt((pj[0] + u[0] * k1, pj[1] + u[1] * k1))} {pt((left3, fy + self.fil * 0.5))} {pt((left3, fy))}")
        for i, (x, ytop) in enumerate(zip(xs, tops)):
            cy = ytop + rt
            cmds.append(f"L {pt((x - rt, cy))}")
            cmds.append(f"A {fr(rt)} {fr(rt)} 0 0 1 {pt((x + rt, cy))}")
            if i < len(xs) - 1:
                xn = xs[i + 1]
                gr = ((xn - hb) - (x + hb)) / 2
                cmds.append(f"L {pt((x + hb, vy - gr))}")
                cmds.append(f"A {fr(gr)} {fr(gr)} 0 0 0 {pt((xn - hb, vy - gr))}")
        xr = xs[-1] + hb
        rc = self.rc
        cmds.append(f"L {pt((xr, self.B - rc))}")
        cmds.append(f"C {pt((xr, self.B - rc * 0.45))} {pt((xr - rc * 0.45, self.B))} {pt((xr - rc, self.B))}")
        cmds.append(f"L {pt(bot(0.0))}")
        cmds += hermite_path(bot, 0.0, 1.0, 10)
        cmds.append(f"A {fr(self.h1)} {fr(self.h1)} 0 0 1 {pt(tip_top)}")
        cmds.append("Z")
        return " ".join(cmds)

    def dot_paths(self):
        return [circle(x, y, self.dot_r) for (x, y) in self.dots]


def crescent(cx, cy, R, k, ang, offk=0.55):
    """Crescent: disc radius R minus disc radius R*k offset along ang (deg, SVG y down)."""
    r2 = R * k
    a = math.radians(ang)
    off = R * offk
    aa = (R * R - r2 * r2 + off * off) / (2 * off)
    hh = math.sqrt(max(R * R - aa * aa, 0))
    mx, my = cx + aa * math.cos(a), cy + aa * math.sin(a)
    p1 = (mx + hh * -math.sin(a), my + hh * math.cos(a))
    p2 = (mx - hh * -math.sin(a), my - hh * math.cos(a))
    return (f"M {pt(p1)} A {fr(R)} {fr(R)} 0 1 1 {pt(p2)} "
            f"A {fr(r2)} {fr(r2)} 0 {1 if aa - off > 0 else 0} 0 {pt(p1)} Z")


# ---------------------------------------------------------------- writers

SVG_HEAD = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">\n'

BG_DEFS = ('    <linearGradient id="bg" gradientUnits="userSpaceOnUse" x1="30" y1="18" x2="78" y2="90">\n'
           f'      <stop offset="0" stop-color="{TERRA_A}"/>\n'
           f'      <stop offset="1" stop-color="{TERRA_B}"/>\n'
           '    </linearGradient>\n')
BG_RECT = '  <rect x="0" y="0" width="108" height="108" fill="url(#bg)"/>\n'


def write_set(folder: Path, letter: Letter, extras=(), ghosts=(), fade=0.6):
    """extras: list of path data drawn solid; ghosts: list of (d, opacity) drawn behind."""
    folder.mkdir(parents=True, exist_ok=True)

    def w(name, text):
        with open(folder / name, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)

    # tone fade along the kashida, from full cream where it leaves the last tooth
    x_full = letter.cx[-1] - letter.wb / 2 - 2.0
    tipx, tipy = letter.tip()
    x_end = tipx - letter.h1
    end_col = mix(CREAM, ground_at(*tp((tipx, tipy))), fade)
    mid_col = mix(CREAM, ground_at(*tp(((x_full + x_end) / 2, tipy))), fade * 0.35)
    (gx1, gy), (gx2, _) = tp((x_full, tipy)), tp((x_end, tipy))
    ink_defs = (f'    <linearGradient id="ink" gradientUnits="userSpaceOnUse" x1="{f(gx1)}" y1="{f(gy)}" x2="{f(gx2)}" y2="{f(gy)}">\n'
                f'      <stop offset="0" stop-color="{CREAM}"/>\n'
                f'      <stop offset="0.5" stop-color="{mid_col}"/>\n'
                f'      <stop offset="1" stop-color="{end_col}"/>\n'
                '    </linearGradient>\n')
    body = "".join(f'  <path fill="{CREAM}" fill-opacity="{f(o)}" d="{d}"/>\n' for d, o in ghosts)
    body += f'  <path fill="url(#ink)" d="{letter.path()}"/>\n'
    body += "".join(f'  <path fill="{CREAM}" d="{d}"/>\n' for d in letter.dot_paths() + list(extras))
    mono = "".join(f'  <path fill="#000000" d="{d}"/>\n'
                   for d in [letter.path()] + letter.dot_paths() + list(extras))

    w("background.svg", SVG_HEAD + "  <defs>\n" + BG_DEFS + "  </defs>\n" + BG_RECT + "</svg>\n")
    w("foreground.svg", SVG_HEAD + "  <defs>\n" + ink_defs + "  </defs>\n" + body + "</svg>\n")
    w("monochrome.svg", SVG_HEAD + mono + "</svg>\n")
    w("icon.svg", SVG_HEAD + "  <defs>\n" + BG_DEFS + ink_defs + "  </defs>\n" + BG_RECT + body + "</svg>\n")


def letter_r2(**kw):
    base = dict(wt=5.8, wb=6.2, B=70.0, cx=[76.0, 66.4, 56.8], top=[43.6, 48.6, 53.6],
                K=[(57, 66.3), (45, 66.3), (34, 68.0), (26.5, 63.6)],
                dots=[(61.8, 33.4), (57.6, 40.4), (66.0, 40.4)], dot_r=2.9)
    base.update(kw)
    return Letter(**base)


def rug(x, y, w, h, n=3):
    """Small rug lying flat, fringe on both short ends (x, y = centre)."""
    r = 0.7
    x0, x1, y0, y1 = x - w / 2, x + w / 2, y - h / 2, y + h / 2
    d = (f"M {pt((x0 + r, y0))} L {pt((x1 - r, y0))} A {fr(r)} {fr(r)} 0 0 1 {pt((x1, y0 + r))} "
         f"L {pt((x1, y1 - r))} A {fr(r)} {fr(r)} 0 0 1 {pt((x1 - r, y1))} L {pt((x0 + r, y1))} "
         f"A {fr(r)} {fr(r)} 0 0 1 {pt((x0, y1 - r))} L {pt((x0, y0 + r))} A {fr(r)} {fr(r)} 0 0 1 {pt((x0 + r, y0))} Z")
    out = [d]
    fl, fw = 1.5, 0.9
    for i in range(n):
        yy = y0 + h * (i + 0.5) / n
        for xa, xb in ((x0 - fl, x0 + 0.2), (x1 - 0.2, x1 + fl)):
            out.append(f"M {pt((xa, yy - fw / 2))} L {pt((xb, yy - fw / 2))} L {pt((xb, yy + fw / 2))} L {pt((xa, yy + fw / 2))} Z")
    return out


def capsule(p, q, r):
    """Stadium from p to q with radius r."""
    dx, dy = q[0] - p[0], q[1] - p[1]
    L = math.hypot(dx, dy)
    nx, ny = -dy / L * r, dx / L * r
    a, b = (p[0] + nx, p[1] + ny), (q[0] + nx, q[1] + ny)
    c, d = (q[0] - nx, q[1] - ny), (p[0] - nx, p[1] - ny)
    return (f"M {pt(a)} L {pt(b)} A {fr(r)} {fr(r)} 0 0 0 {pt(c)} "
            f"L {pt(d)} A {fr(r)} {fr(r)} 0 0 0 {pt(a)} Z")


def ghost(letter, dx, dy, op):
    keep = dict(XF)
    XF.update(tx=keep["tx"] + dx, ty=keep["ty"] + dy)
    paths = [(letter.path(), op)] + [(d, op) for d in letter.dot_paths()]
    XF.update(keep)
    return paths


def main():
    V = HERE / "_var"
    place(1.04, -2.5, -1.1)
    K = [(57, 66.3), (45, 66.3), (35.0, 68.0), (30.0, 63.8)]
    L = letter_r2(K=K)
    # the final set is (a), the letter alone, written at the end
    # (c) a small mosque-finial crescent, horns up, where the sound has gone
    write_set(V / "r2-c-finial-left", L, [crescent(35.5, 50.5, 4.2, 0.84, -90, offk=0.62)])
    # (c) crescent tucked beside the dots
    write_set(V / "r2-c-tucked", L, [crescent(50.0, 36.0, 3.8, 0.84, -60, offk=0.62)])
    # (c) crescent crowning the dots like the finial on a Tunisian minaret
    write_set(V / "r2-c-crown", L, [crescent(61.8, 25.6, 3.3, 0.84, -90, offk=0.62)])
    # (b) a fringed rug where the kashida ends (kashida shortened to make room)
    Lb = letter_r2(K=[(57, 66.3), (47, 66.3), (40, 67.6), (35.5, 64.4)])
    write_set(V / "r2-b-rug", Lb, rug(28.0, 64.6, 7.4, 4.6))
    # ghost echo «ششش»
    write_set(V / "r2-echo", L, ghosts=ghost(L, -5.0, -3.5, 0.15) + ghost(L, -2.5, -1.75, 0.35))
    # the kashida breaks into two dashes instead of fading
    Ld = letter_r2(K=[(57, 66.3), (49, 66.3), (43, 67.4), (39.5, 66.6)], tip=2.0)
    write_set(V / "r2-dashes", Ld, [capsule((33.4, 66.6), (37.0 - 1.6, 66.4), 1.6),
                                    capsule((28.6, 65.4), (29.6, 65.0), 1.3)], fade=0.0)
    # final: (a) the letter alone
    write_set(HERE, L)


if __name__ == "__main__":
    main()
