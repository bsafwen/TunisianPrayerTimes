"""Volume Rug icon generator (revision 2): writes the three layers (+ icon.svg)
for each explored variant into _var/<name>/, and the chosen one into this folder.

Usage: python -I gen.py [final-variant-name]
(The first round's generator is kept as _var/gen_v1.py.)
"""
import math
import sys
from pathlib import Path

HERE = Path(__file__).parent

# palette
BG_IN, BG_OUT = "#F7F3EA", "#EEE6D8"
RUG_TOP, RUG_BOT = "#B5402C", "#A93A27"   # about 6% darker at the foot
CREAM = "#F3E2C0"
FRINGE = "#C2955A"
THUMB = "#FFFFFF"
GLYPH = "#8A2E1F"


def f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def rrect(x, y, w, h, r):
    r = max(0.0, min(r, w / 2, h / 2))
    if r == 0:
        return f"M{f(x)} {f(y)}H{f(x+w)}V{f(y+h)}H{f(x)}Z"
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


def speaker(x0, cy, h):
    """Speaker glyph of height h, left edge at x0, vertically centred on cy.
    Proportions follow Material's volume_off: magnet 3/8 of the height, cone
    opening to the full height. Returns (path, right_edge_x)."""
    s = h / 16.0
    pts = [(0, -3.4), (4.4, -3.4), (10.4, -8), (10.4, 8), (4.4, 3.4), (0, 3.4)]
    pts = [(x0 + x * s, cy + y * s) for x, y in pts]
    return rpoly(pts, [1.0 * s, 0.8 * s, 1.3 * s, 1.3 * s, 0.8 * s, 1.0 * s]), x0 + 10.4 * s


def cross(cx, cy, arm, w):
    """An x of two crossed bars with round ends (half-length arm along each axis, bar width w)."""
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
    end = w * 0.5
    return rpoly(pts, [0.25, end, end] * 4)


def lozenge(cx, cy, w, h):
    return f"M{f(cx)} {f(cy-h/2)}L{f(cx+w/2)} {f(cy)}L{f(cx)} {f(cy+h/2)}L{f(cx-w/2)} {f(cy)}Z"


def svg(body, defs=""):
    d = f"<defs>\n{defs}\n</defs>\n" if defs else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">\n'
            f"{d}{body}</svg>\n")


def background(c=None):
    bg_in, bg_out = (c or {}).get("bg", (BG_IN, BG_OUT))
    defs = (f'<radialGradient id="bg" gradientUnits="userSpaceOnUse" cx="54" cy="44" r="72">'
            f'<stop offset="0" stop-color="{bg_in}"/><stop offset="1" stop-color="{bg_out}"/></radialGradient>')
    return defs, '<rect x="0" y="0" width="108" height="108" fill="url(#bg)"/>\n'


def build(c):
    """Return (fg_defs, fg_body, mono_body)."""
    rw, rh, rr = c["rug"]
    rx, ry = 54 - rw / 2, c.get("rug_cy", 54) - rh / 2
    defs = (f'<linearGradient id="rug" gradientUnits="userSpaceOnUse" x1="0" y1="{f(ry)}" x2="0" y2="{f(ry+rh)}">'
            f'<stop offset="0" stop-color="{RUG_TOP}"/><stop offset="1" stop-color="{RUG_BOT}"/></linearGradient>')
    body, mono_solid, mono_holes, mono_islands = [], [], [], []

    # fringe: n strands per end, slightly uneven lengths, round free ends
    n, sw, pitch, lens_top, lens_bot = c["fringe"]
    flare = c.get("flare")  # (root width, tip width): a knotted tassel that splays out
    ticks = []
    for i in range(n):
        xc = 54 + (i - (n - 1) / 2) * pitch
        lt, lb = lens_top[i % len(lens_top)], lens_bot[i % len(lens_bot)]
        if flare:
            wr, wt = flare
            tip = wt * 0.48
            ticks.append(rpoly([(xc + wr / 2, ry + 3), (xc - wr / 2, ry + 3),
                                (xc - wt / 2, ry - lt), (xc + wt / 2, ry - lt)], [0, 0, tip, tip]))
            if i not in c.get("skip_bottom", ()):
                yb = ry + rh
                ticks.append(rpoly([(xc - wr / 2, yb - 3), (xc + wr / 2, yb - 3),
                                    (xc + wt / 2, yb + lb), (xc - wt / 2, yb + lb)], [0, 0, tip, tip]))
            continue
        x = xc - sw / 2
        ticks.append(rrect(x, ry - lt, sw, lt + 3, sw / 2))
        if i not in c.get("skip_bottom", ()):
            ticks.append(rrect(x, ry + rh - 3, sw, lb + 3, sw / 2))
    body.append(f'<path fill="{c.get("fringe_col", FRINGE)}" d="{"".join(ticks)}"/>')
    mono_solid.append("".join(ticks))

    rug = rrect(rx, ry, rw, rh, rr)
    body.append(f'<path fill="url(#rug)" d="{rug}"/>')

    # one cream border (inset bi, width bw), corners follow the rug's radius
    cream_d = ""
    bi, bw = c.get("border") or (0, 0)
    if bw:
        o = rrect(rx + bi, ry + bi, rw - 2 * bi, rh - 2 * bi, max(rr - bi, 0.6))
        i_ = rrect(rx + bi + bw, ry + bi + bw, rw - 2 * (bi + bw), rh - 2 * (bi + bw), max(rr - bi - bw, 0.3))
        cream_d += o + i_
    # optional woven bands: two thin stripes across each end
    bands = c.get("bands")
    if bands:
        b_in, b_w, b_gap = bands
        for y in (ry + b_in, ry + rh - b_in - b_w):
            cream_d += rrect(rx + 2.2, y, rw - 4.4, b_w, 0)
        for y in (ry + b_in + b_w + b_gap, ry + rh - b_in - 2 * b_w - b_gap):
            cream_d += rrect(rx + 2.2, y, rw - 4.4, b_w, 0)

    # lozenge on the axis (outline + centre dot) and the slider track below it
    loz = c.get("lozenge")
    if loz:
        lx, ly, lw, lh, st = loz
        k = st * math.hypot(lw / 2, lh / 2) / (lh / 2)  # inner offset for an even stroke
        cream_d += lozenge(lx, ly, lw, lh) + lozenge(lx, ly, lw - 2 * k, lh - 2 * k * lh / lw)
        cream_d += lozenge(lx, ly, lw * 0.32, lh * 0.32)
    tx, ty, tr = c["thumb"]
    track = c.get("track")
    if track:
        # runs from the lozenge's foot vertex (or t_top) to the top of the thumb;
        # touching, not overlapping, so the evenodd holes in the monochrome stay clean
        t_top, t_w = track
        if loz:
            t_top = loz[1] + loz[3] / 2
        cream_d += rrect(54 - t_w / 2, t_top, t_w, ty - tr - t_top, 0)
    if cream_d:
        body.append(f'<path fill="{CREAM}" fill-rule="evenodd" d="{cream_d}"/>')
        mono_holes.append(cream_d)

    # thumb: flat white disc, optional ring in the ground colour
    ring = c.get("ring")
    if ring:
        body.append(f'<path fill="{c.get("bg", (BG_IN,))[0]}" d="{circle(tx, ty, tr + ring)}"/>')
    body.append(f'<path fill="{THUMB}" d="{circle(tx, ty, tr)}"/>')

    # glyph: speaker + x, centred as a group in the disc
    g = c["glyph"]
    h, gap, arm, xw = g["h"], g["gap"], g["arm"], g["xw"]
    sp_w = 10.4 * h / 16
    x_ext = arm + xw / 2
    total = sp_w + gap + 2 * x_ext
    x0 = tx - total / 2 + g.get("dx", 0)
    sp, sp_r = speaker(x0, ty, h)
    glyph = sp + cross(sp_r + gap + x_ext, ty, arm, xw)
    body.append(f'<path fill="{GLYPH}" d="{glyph}"/>')

    # monochrome: rug + fringe solid with the cream parts and the thumb cut out,
    # the glyph as an island inside the thumb hole
    if ring:
        mono_holes.append(circle(tx, ty, tr + ring))
        mono_islands.append(circle(tx, ty, tr) + glyph)
        mono = [f'<path fill="#000000" d="{mono_solid[0]}"/>',
                f'<path fill="#000000" fill-rule="evenodd" d="{rug}{"".join(mono_holes)}"/>',
                f'<path fill="#000000" fill-rule="evenodd" d="{mono_islands[0]}"/>']
    else:
        mono = [f'<path fill="#000000" d="{mono_solid[0]}"/>',
                f'<path fill="#000000" fill-rule="evenodd" d="{rug}{"".join(mono_holes)}{circle(tx, ty, tr)}"/>',
                f'<path fill="#000000" d="{glyph}"/>']
    return defs, "\n".join(body) + "\n", "\n".join(mono) + "\n"


# revision 2, first step: square corners, one cream border, axis track, gold strands
STEP = dict(
    rug=(38, 48, 4.5),
    fringe=(5, 2.4, 6.6, [5.4, 4.8, 5.6, 5.0, 5.3], [5.1, 5.6, 4.9, 5.5, 5.0]),
    border=(2.8, 1.5),
    lozenge=(54, 40.6, 8.0, 9.8, 1.7),
    track=(45.5, 2.5),
    thumb=(54, 62.2, 10.6),
    glyph=dict(h=12.2, gap=1.5, arm=3.2, xw=2.9),
)

# final: undyed-wool fringe (6 knotted strands, uneven, splaying), deeper sand ground
WOOL = dict(
    rug=(38, 46, 4.5),
    fringe=(6, 2.0, 5.6, [6.6, 5.6, 7.0, 5.9, 6.8, 5.7], [5.9, 6.9, 5.7, 6.7, 6.0, 7.0]),
    flare=(1.8, 3.0), fringe_col="#FFFBF2", bg=("#EBDDC3", "#DBC9A8"),
    border=(2.4, 1.4),
    lozenge=(54, 40.6, 7.6, 9.4, 1.6),
    track=(45.5, 2.5),
    thumb=(54, 61.4, 10.4),
    glyph=dict(h=12.0, gap=1.5, arm=3.15, xw=2.85),
)

VARIANTS = {
    "l-axis3-big": STEP,
    "g-bands": dict(STEP, border=None, bands=(3.0, 1.3, 1.3), lozenge=(54, 41.5, 8.0, 9.8, 1.7),
                    thumb=(54, 62.6, 10.0), glyph=dict(h=11.5, gap=1.4, arm=3.0, xw=2.7)),
    "j-foot": dict(STEP, rug=(38, 44, 4.5), rug_cy=51, lozenge=(54, 38.6, 8.0, 9.8, 1.7),
                   thumb=(54, 73, 10.0), ring=2.0, skip_bottom=(1, 2, 3),
                   glyph=dict(h=11.5, gap=1.4, arm=3.0, xw=2.7)),
    "s-wool3": WOOL,
}

def write(d, cfg):
    d.mkdir(parents=True, exist_ok=True)
    bdefs, bbody = background(cfg)
    fdefs, fbody, mbody = build(cfg)
    (d / "background.svg").write_text(svg(bbody, bdefs), encoding="utf-8", newline="\n")
    (d / "foreground.svg").write_text(svg(fbody, fdefs), encoding="utf-8", newline="\n")
    (d / "monochrome.svg").write_text(svg(mbody), encoding="utf-8", newline="\n")
    (d / "icon.svg").write_text(svg(bbody + fbody, bdefs + "\n" + fdefs), encoding="utf-8", newline="\n")


if __name__ == "__main__":
    final = sys.argv[1] if len(sys.argv) > 1 else None
    names = [a for a in sys.argv[2:]] or list(VARIANTS)
    for name in names:
        write(HERE / "_var" / name, VARIANTS[name])
    if final:
        write(HERE, VARIANTS[final])
