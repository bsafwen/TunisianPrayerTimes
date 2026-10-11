"""Generator for proposal 08 «ششش» (Shhh).

A single calligraphic sheen in its initial form: three rounded teeth that
step down in height, three rhombic dots, and a long kashida that thins to a
soft round tip.  Python 3.12, standard library only.

Usage: python -I gen.py            (writes the final folder and _var/*)
"""

import math
from pathlib import Path

HERE = Path(__file__).parent.parent  # moved to _var/ in revision 2; writes the round-1 set

CREAM = "#FFF3E2"
TERRA_A = "#CC6A3E"
TERRA_B = "#A0462A"


def f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


# global placement: scale about (54, 54), then translate
XF = {"s": 1.0, "tx": 0.0, "ty": 0.0}


def pt(p):
    x = 54 + (p[0] - 54) * XF["s"] + XF["tx"]
    y = 54 + (p[1] - 54) * XF["s"] + XF["ty"]
    return f"{f(x)} {f(y)}"


def fr(r):
    return f(r * XF["s"])


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


class Letter:
    def __init__(self, **kw):
        self.wt = kw.get("wt", 5.0)        # tooth width at the cap
        self.wb = kw.get("wb", 5.8)        # tooth width at the valley
        self.t = kw.get("t", 7.0)          # baseline thickness
        self.B = kw.get("B", 73.0)         # bottom of the baseline
        self.cx = kw.get("cx", [75.0, 64.8, 54.6])    # tooth centres, right to left
        self.top = kw.get("top", [50.0, 53.0, 56.0])  # tooth tops, right to left
        self.valley = kw.get("valley", 0.6)  # valley bottom above baseline top
        self.rc = kw.get("rc", 5.5)        # bottom-right corner
        self.K = kw.get("K", [(56, 69.5), (44, 69.5), (34.5, 71.8), (26.5, 66.0)])
        self.h0 = self.t / 2
        self.h1 = kw.get("tip", 1.5)       # tip radius
        self.taper = kw.get("taper", 1.25)
        self.ease = kw.get("ease", "pow")  # "pow" (draft) or "smooth"
        self.fil = kw.get("fil", 2.4)      # fillet size where the kashida meets the last tooth
        self.dots = kw.get("dots", [(62.6, 36.4), (57.7, 43.0), (67.5, 43.0)])
        self.dot = kw.get("dot", (3.9, 3.9, 0.7, 0.55))  # half-w, half-h, skew, corner

    def h(self, s):
        s = min(max(s, 0.0), 1.0)
        if self.ease == "smooth":
            # full weight leaving the teeth, thinning steadily, calm near the tip
            k = (0.5 + 0.5 * math.cos(math.pi * s)) ** self.taper
            return self.h1 + (self.h0 - self.h1) * k
        return self.h1 + (self.h0 - self.h1) * (1 - s) ** self.taper

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
        cmds += hermite_path(top, 1.0, sj, 8)
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
        cmds += hermite_path(bot, 0.0, 1.0, 8)
        cmds.append(f"A {fr(self.h1)} {fr(self.h1)} 0 0 1 {pt(tip_top)}")
        cmds.append("Z")
        return " ".join(cmds)

    def dot_paths(self):
        hw, hh, sk, rc = self.dot
        out = []
        for (x, y) in self.dots:
            V = [(x + sk, y - hh), (x + hw, y), (x - sk, y + hh), (x - hw, y)]
            segs = []
            for i in range(4):
                a, b, c = V[i - 1], V[i], V[(i + 1) % 4]
                da = math.hypot(b[0] - a[0], b[1] - a[1])
                dc = math.hypot(c[0] - b[0], c[1] - b[1])
                k = rc * 1.6
                p_in = (b[0] + (a[0] - b[0]) * k / da, b[1] + (a[1] - b[1]) * k / da)
                p_out = (b[0] + (c[0] - b[0]) * k / dc, b[1] + (c[1] - b[1]) * k / dc)
                segs.append((p_in, b, p_out))
            d = f"M {pt(segs[0][2])}"
            for i in range(1, 5):
                p_in, b, p_out = segs[i % 4]
                d += f" L {pt(p_in)} Q {pt(b)} {pt(p_out)}"
            out.append(d + " Z")
        return out

    def all_paths(self):
        return [self.path()] + self.dot_paths()


# ---------------------------------------------------------------- extras

def crescent(cx, cy, R, k, ang, offk=0.55):
    """Crescent: disc radius R minus disc radius R*k offset along ang (deg)."""
    r2 = R * k
    a = math.radians(ang)
    off = R * offk
    ox, oy = cx + off * math.cos(a), cy + off * math.sin(a)
    # intersection points of the two circles
    d = off
    aa = (R * R - r2 * r2 + d * d) / (2 * d)
    hh = math.sqrt(max(R * R - aa * aa, 0))
    mx, my = cx + aa * math.cos(a), cy + aa * math.sin(a)
    p1 = (mx + hh * -math.sin(a), my + hh * math.cos(a))
    p2 = (mx - hh * -math.sin(a), my - hh * math.cos(a))
    # outer arc (large) from p1 to p2 on circle R, then inner arc back on circle r2
    return (f"M {pt(p1)} A {fr(R)} {fr(R)} 0 1 1 {pt(p2)} "
            f"A {fr(r2)} {fr(r2)} 0 {1 if aa - off > 0 else 0} 0 {pt(p1)} Z")


# ---------------------------------------------------------------- writers

SVG_HEAD = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">\n'


def bg_svg(kind):
    if kind == "cream":
        return (SVG_HEAD +
                '  <defs>\n'
                '    <linearGradient id="bg" gradientUnits="userSpaceOnUse" x1="30" y1="18" x2="78" y2="90">\n'
                '      <stop offset="0" stop-color="#FFF7EC"/>\n'
                '      <stop offset="1" stop-color="#F7E5CC"/>\n'
                '    </linearGradient>\n'
                '  </defs>\n'
                '  <rect x="0" y="0" width="108" height="108" fill="url(#bg)"/>\n'
                '</svg>\n')
    if kind == "terra":
        return (SVG_HEAD +
                '  <defs>\n'
                '    <linearGradient id="bg" gradientUnits="userSpaceOnUse" x1="30" y1="18" x2="78" y2="90">\n'
                f'      <stop offset="0" stop-color="{TERRA_A}"/>\n'
                f'      <stop offset="1" stop-color="{TERRA_B}"/>\n'
                '    </linearGradient>\n'
                '  </defs>\n'
                '  <rect x="0" y="0" width="108" height="108" fill="url(#bg)"/>\n'
                '</svg>\n')
    return SVG_HEAD + f'  <rect x="0" y="0" width="108" height="108" fill="{kind}"/>\n</svg>\n'


def fg_body(paths, colour, indent="  "):
    return "".join(f'{indent}<path fill="{colour}" d="{d}"/>\n' for d in paths)


def write_set(folder: Path, paths, bg="terra", ink=CREAM):
    folder.mkdir(parents=True, exist_ok=True)

    def w(name, text):
        with open(folder / name, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)

    w("background.svg", bg_svg(bg))
    w("foreground.svg", SVG_HEAD + fg_body(paths, ink) + "</svg>\n")
    w("monochrome.svg", SVG_HEAD + fg_body(paths, "#000000") + "</svg>\n")
    bg_text = bg_svg(bg)
    inner = bg_text[len(SVG_HEAD):-len("</svg>\n")]
    w("icon.svg", SVG_HEAD + inner + fg_body(paths, ink) + "</svg>\n")


def place(s, tx, ty):
    XF.update(s=s, tx=tx, ty=ty)


def main():
    place(0.92, -0.5, -1.0)
    L = Letter()
    write_set(HERE / "_var" / "c-round", L.all_paths())
    moon = crescent(36.0, 43.0, 5.2, 0.86, -40)
    write_set(HERE / "_var" / "d-crescent", L.all_paths() + [moon])

    # round 3: smooth taper, calmer belly, slightly smaller
    place(0.9, -0.3, -0.6)
    L3 = Letter(ease="smooth", taper=0.9,
                K=[(56, 69.5), (44, 69.5), (34.5, 71.3), (26.0, 65.8)])
    write_set(HERE / "_var" / "g-smooth", L3.all_paths())
    moon3 = crescent(35.5, 42.0, 5.6, 0.84, -40)
    write_set(HERE / "_var" / "h-smooth-crescent", L3.all_paths() + [moon3])

    # round 4: near-parallel teeth, wider fillet, slimmer crescent
    L4 = Letter(ease="smooth", taper=0.9, wt=5.2, wb=5.6, fil=3.6,
                K=[(56, 69.5), (44, 69.5), (34.5, 71.3), (26.0, 65.8)])
    write_set(HERE / "_var" / "i-letter", L4.all_paths())
    moon4 = crescent(35.0, 41.5, 6.0, 0.92, -40, offk=0.38)
    write_set(HERE / "_var" / "j-crescent", L4.all_paths() + [moon4])

    # final: round-4 letter, crescent a touch sturdier for 24 px
    moon = crescent(35.0, 41.5, 6.0, 0.9, -40, offk=0.42)
    final = L4.all_paths() + [moon]
    write_set(HERE / "_var" / "k-final-terracotta", final)
    write_set(HERE / "_var" / "e-blue", final, bg="cream", ink="#1B5E9E")
    write_set(HERE / "_var" / "f-olive", final, bg="cream", ink="#4E5A28")
    write_set(HERE, final)


if __name__ == "__main__":
    main()
