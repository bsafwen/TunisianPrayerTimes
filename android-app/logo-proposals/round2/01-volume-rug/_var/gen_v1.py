"""Volume Rug icon generator: writes the three layers (+ icon.svg) for each
explored variant into _var/<name>/, and the chosen one into this folder.

Usage: python -I gen.py [final-variant-name]
"""
import math
import sys
from pathlib import Path

HERE = Path(__file__).parent

# palette
BG_IN, BG_OUT = "#F7F3EA", "#ECE3D3"
RUG_TOP, RUG_BOT = "#BC4631", "#93301F"
CREAM = "#F3E2C0"
FRINGE = "#D2AE7A"
SHADOW = "#6E2216"
THUMB = "#FFFFFF"
GLYPH = "#7F2A1C"


def f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def rrect(x, y, w, h, r):
    return (f"M{f(x+r)} {f(y)}H{f(x+w-r)}A{f(r)} {f(r)} 0 0 1 {f(x+w)} {f(y+r)}"
            f"V{f(y+h-r)}A{f(r)} {f(r)} 0 0 1 {f(x+w-r)} {f(y+h)}H{f(x+r)}"
            f"A{f(r)} {f(r)} 0 0 1 {f(x)} {f(y+h-r)}V{f(y+r)}A{f(r)} {f(r)} 0 0 1 {f(x+r)} {f(y)}Z")


def circle(cx, cy, r):
    return (f"M{f(cx-r)} {f(cy)}A{f(r)} {f(r)} 0 1 0 {f(cx+r)} {f(cy)}"
            f"A{f(r)} {f(r)} 0 1 0 {f(cx-r)} {f(cy)}Z")


def rpoly(pts, r):
    """Closed polygon with corners rounded by quadratic curves of reach r."""
    n = len(pts)
    out = []

    def toward(a, b, d):
        dx, dy = b[0] - a[0], b[1] - a[1]
        L = math.hypot(dx, dy)
        d = min(d, L / 2.05)
        return (a[0] + dx / L * d, a[1] + dy / L * d)

    for i in range(n):
        p0, p1, p2 = pts[i - 1], pts[i], pts[(i + 1) % n]
        rr = r[i] if isinstance(r, (list, tuple)) else r
        a = toward(p1, p0, rr)
        b = toward(p1, p2, rr)
        out.append(("L" if out else "M") + f"{f(a[0])} {f(a[1])}")
        out.append(f"Q{f(p1[0])} {f(p1[1])} {f(b[0])} {f(b[1])}")
    return "".join(out) + "Z"


def speaker(cx, cy, h):
    """Speaker glyph of height h with its optical centre at (cx, cy)."""
    s = h / 16.0
    # unit glyph: magnet 4.6 wide x 7.2 tall, cone 6.2 wide opening to 16 tall
    pts = [(0, -3.6), (4.6, -3.6), (10.8, -8), (10.8, 8), (4.6, 3.6), (0, 3.6)]
    ox = 5.7  # between the bbox centre (5.4) and the area centroid (6.1)
    pts = [(cx + (x - ox) * s, cy + y * s) for x, y in pts]
    return rpoly(pts, [1.1 * s, 0.9 * s, 1.2 * s, 1.2 * s, 0.9 * s, 1.1 * s])


def cross(cx, cy, arm, w):
    """An x of two crossed bars (half-length arm along each diagonal, width w)."""
    q = (w / 2) / math.sqrt(2)
    a = arm
    pts = [
        (0, -2 * q),
        (a - q, -a - q), (a + q, -a + q),
        (2 * q, 0),
        (a + q, a - q), (a - q, a + q),
        (0, 2 * q),
        (-a + q, a + q), (-a - q, a - q),
        (-2 * q, 0),
        (-a - q, -a + q), (-a + q, -a - q),
    ]
    pts = [(cx + x, cy + y) for x, y in pts]
    end = w * 0.48
    rr = [0.2, end, end] * 4
    return rpoly(pts, rr)


def lozenge(cx, cy, w, h):
    return f"M{f(cx)} {f(cy-h/2)}L{f(cx+w/2)} {f(cy)}L{f(cx)} {f(cy+h/2)}L{f(cx-w/2)} {f(cy)}Z"


def svg(body, defs=""):
    d = f"<defs>\n{defs}\n</defs>\n" if defs else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">\n'
            f"{d}{body}</svg>\n")


def background():
    defs = (f'<radialGradient id="bg" gradientUnits="userSpaceOnUse" cx="54" cy="44" r="72">'
            f'<stop offset="0" stop-color="{BG_IN}"/><stop offset="1" stop-color="{BG_OUT}"/></radialGradient>')
    return defs, '<rect x="0" y="0" width="108" height="108" fill="url(#bg)"/>\n'


def build(cfg):
    """Return (fg_defs, fg_body, mono_body)."""
    rx, ry, rw, rh, rr = cfg["rug"]
    fringe_n, fringe_w, fringe_pitch, fringe_len = cfg["fringe"]
    defs = (f'<linearGradient id="rug" gradientUnits="userSpaceOnUse" x1="0" y1="{f(ry)}" x2="0" y2="{f(ry+rh)}">'
            f'<stop offset="0" stop-color="{RUG_TOP}"/><stop offset="1" stop-color="{RUG_BOT}"/></linearGradient>')
    body = []
    mono = []
    # fringe ticks (drawn first, the rug covers their inner ends)
    ticks = []
    mid = rx + rw / 2
    for i in range(fringe_n):
        x = mid + (i - (fringe_n - 1) / 2) * fringe_pitch - fringe_w / 2
        ticks.append(rrect(x, ry - fringe_len, fringe_w, fringe_len + 6, fringe_w / 2))
        ticks.append(rrect(x, ry + rh - 6, fringe_w, fringe_len + 6, fringe_w / 2))
    body.append(f'<path fill="{cfg.get("fringe_col", FRINGE)}" d="{"".join(ticks)}"/>')
    rug = rrect(rx, ry, rw, rh, rr)
    body.append(f'<path fill="url(#rug)" d="{rug}"/>')
    # inner cream border: a rounded-rect ring
    bi, bw = cfg["border"]
    if bw:
        o = rrect(rx + bi, ry + bi, rw - 2 * bi, rh - 2 * bi, rr - bi)
        i_ = rrect(rx + bi + bw, ry + bi + bw, rw - 2 * (bi + bw), rh - 2 * (bi + bw), rr - bi - bw)
        body.append(f'<path fill="{CREAM}" fill-rule="evenodd" d="{o}{i_}"/>')
    # optional row of kilim teeth under the head border
    teeth = cfg.get("teeth")
    teeth_d = ""
    if teeth:
        n, th = teeth
        x0 = rx + bi + bw
        x1 = rx + rw - bi - bw
        y0 = ry + bi + bw
        tw = (x1 - x0 - 2 * 2.2) / n
        for k in range(n):
            a = x0 + 2.2 + k * tw
            teeth_d += f"M{f(a + 0.5)} {f(y0 - 0.4)}L{f(a + tw - 0.5)} {f(y0 - 0.4)}L{f(a + tw / 2)} {f(y0 + th)}Z"
        body.append(f'<path fill="{CREAM}" d="{teeth_d}"/>')
    # small Kairouan lozenge in the empty upper track
    loz = cfg.get("lozenge")
    loz_mono = ""
    if loz:
        lx, ly, lw, lh = loz
        outer = lozenge(lx, ly, lw, lh)
        inner = lozenge(lx, ly, lw - 3.4, lh - 3.4 * lh / lw)
        dot = lozenge(lx, ly, lw * 0.3, lh * 0.3)
        body.append(f'<path fill="{CREAM}" fill-rule="evenodd" d="{outer}{inner}{dot}"/>')
        loz_mono = outer + inner + dot
    # thumb
    tx, ty, tr = cfg["thumb"]
    sx, sy = cfg.get("shadow", (1.1, 1.7))
    ring = cfg.get("badge_ring")
    if ring:
        body.append(f'<path fill="{BG_IN}" d="{circle(tx, ty, tr + ring)}"/>')
    body.append(f'<path fill="{SHADOW}" d="{circle(tx + sx, ty + sy, tr)}"/>')
    body.append(f'<path fill="{THUMB}" d="{circle(tx, ty, tr)}"/>')
    g = cfg["glyph"]
    glyph = speaker(tx + g["dx"], ty, g["h"])
    if g.get("cross"):
        cx_, arm, w = g["cross"]
        glyph += cross(tx + cx_, ty, arm, w)
    body.append(f'<path fill="{GLYPH}" d="{glyph}"/>')
    # monochrome: rug + fringe solid, thumb a hole, glyph an island
    mono.append(f'<path fill="#000000" d="{"".join(ticks)}"/>')
    if ring:
        # badge: a gap ring cut from the rug, the badge itself a solid disc with the glyph as a hole
        mono.append(f'<path fill="#000000" fill-rule="evenodd" d="{rug}{circle(tx, ty, tr + ring)}{loz_mono}"/>')
        mono.append(f'<path fill="#000000" fill-rule="evenodd" d="{circle(tx, ty, tr)}{glyph}"/>')
    else:
        mono.append(f'<path fill="#000000" fill-rule="evenodd" d="{rug}{circle(tx, ty, tr)}{loz_mono}"/>')
        mono.append(f'<path fill="#000000" d="{glyph}"/>')
    return defs, "\n".join(body) + "\n", "\n".join(mono) + "\n"


BASE = dict(
    rug=(36, 28, 36, 52, 8.5),
    fringe=(6, 2.1, 4.3, 4.0),
    border=(2.3, 1.5),
    lozenge=(54, 41, 9.5, 12.5),
    thumb=(54, 62.2, 12.2),
    glyph=dict(dx=0, h=17),
)

CROSS = dict(dx=-3.5, h=14.6, cross=(6.0, 2.7, 2.4))

VARIANTS = {
    "a-cross": dict(BASE, glyph=CROSS),
    "b-nowaves": dict(BASE),
    "c-badge": dict(BASE, rug=(33, 26, 34, 50, 8.5), lozenge=(50, 41, 9.5, 12.5),
                    thumb=(64, 68, 12), badge_ring=2.2,
                    glyph=dict(dx=-3.3, h=14, cross=(5.8, 2.6, 2.3))),
    "d-cross-plain": dict(BASE, glyph=CROSS, lozenge=None),
    "e-teeth": dict(BASE, glyph=CROSS, teeth=(5, 3.0), lozenge=(54, 43, 9.5, 12.5)),
}


def write(d, cfg):
    d.mkdir(parents=True, exist_ok=True)
    bdefs, bbody = background()
    fdefs, fbody, mbody = build(cfg)
    (d / "background.svg").write_text(svg(bbody, bdefs), encoding="utf-8", newline="\n")
    (d / "foreground.svg").write_text(svg(fbody, fdefs), encoding="utf-8", newline="\n")
    (d / "monochrome.svg").write_text(svg(mbody), encoding="utf-8", newline="\n")
    (d / "icon.svg").write_text(svg(bbody + fbody, bdefs + "\n" + fdefs), encoding="utf-8", newline="\n")


if __name__ == "__main__":
    final = sys.argv[1] if len(sys.argv) > 1 else None
    for name, cfg in VARIANTS.items():
        write(HERE / "_var" / name, cfg)
    if final:
        write(HERE, VARIANTS[final])
