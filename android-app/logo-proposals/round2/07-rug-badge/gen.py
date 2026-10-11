"""Generator for 07-rug-badge «سجادة صامتة» (Muted Rug), revision 2.

python -I gen.py            writes the final layers into this folder and every
                            variant listed in VARIANTS into _var/<name>/.
Standard library only. Geometry in dp on the 108 canvas.
Round 1 is kept in _var/r1-final (with its generator gen_r1.py).
"""

import math
from pathlib import Path

HERE = Path(__file__).parent


def f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


# ---------------------------------------------------------------- paths as segment lists
# A segment list is [("M", x, y) | ("L", x, y) | ("A", r, large, sweep, x, y) | ("Z",)].

def seg_str(segs):
    out = []
    for s in segs:
        if s[0] in ("M", "L"):
            out.append(f"{s[0]}{f(s[1])} {f(s[2])}")
        elif s[0] == "A":
            _, r, lg, sw, x, y = s
            out.append(f"A{f(r)} {f(r)} 0 {lg} {sw} {f(x)} {f(y)}")
        else:
            out.append("Z")
    return "".join(out)


def mirror(segs, mx=None, my=None):
    """Mirror x about mx and/or y about my. Arcs flip sweep once per mirrored axis."""
    flips = (mx is not None) + (my is not None)
    out = []
    for s in segs:
        if s[0] == "Z":
            out.append(s)
            continue
        x, y = s[-2], s[-1]
        if mx is not None:
            x = 2 * mx - x
        if my is not None:
            y = 2 * my - y
        if s[0] == "A":
            sw = s[3] if flips % 2 == 0 else 1 - s[3]
            out.append(("A", s[1], s[2], sw, x, y))
        else:
            out.append((s[0], x, y))
    return out


def rrect_tr_cut(x0, y0, x1, y1, rc, cut):
    """Rounded rect whose top-right corner is bitten by circle cut=(cx, cy, r)."""
    cx, cy, r = cut
    segs = [("M", x0 + rc, y0)]
    it = r * r - (y0 - cy) ** 2
    ir = r * r - (x1 - cx) ** 2
    bitten = it > 0 and ir > 0 and math.hypot(x1 - cx, y0 - cy) < r
    if bitten:
        xa = cx - math.sqrt(it)
        yb = cy + math.sqrt(ir)
        ta = math.atan2(y0 - cy, xa - cx)
        tb = math.atan2(yb - cy, x1 - cx)
        travel = (ta - tb) % (2 * math.pi)
        segs += [("L", xa, y0), ("A", r, 1 if travel > math.pi else 0, 0, x1, yb)]
    else:
        segs += [("L", x1 - rc, y0)]
        if rc:
            segs.append(("A", rc, 0, 1, x1, y0 + rc))
    segs.append(("L", x1, y1 - rc))
    if rc:
        segs.append(("A", rc, 0, 1, x1 - rc, y1))
    segs.append(("L", x0 + rc, y1))
    if rc:
        segs.append(("A", rc, 0, 1, x0, y1 - rc))
    segs.append(("L", x0, y0 + rc))
    if rc:
        segs.append(("A", rc, 0, 1, x0 + rc, y0))
    segs.append(("Z",))
    return segs


def rrect_cut(x0, y0, x1, y1, rc, cut, corner):
    """Rounded rect bitten at `corner` ("tr" or "br") by circle cut."""
    if corner == "tr":
        return seg_str(rrect_tr_cut(x0, y0, x1, y1, rc, cut))
    my = (y0 + y1) / 2
    cx, cy, r = cut
    segs = rrect_tr_cut(x0, y0, x1, y1, rc, (cx, 2 * my - cy, r))
    return seg_str(mirror(segs, my=my))


def poly(pts):
    return "M" + "L".join(f"{f(x)} {f(y)}" for x, y in pts) + "Z"


def circle_path(cx, cy, r):
    return (f"M{f(cx - r)} {f(cy)}A{f(r)} {f(r)} 0 1 1 {f(cx + r)} {f(cy)}"
            f"A{f(r)} {f(r)} 0 1 1 {f(cx - r)} {f(cy)}Z")


def diamond(cx, cy, a, b):
    return poly([(cx, cy - b), (cx + a, cy), (cx, cy + b), (cx - a, cy)])


def rect_hits_circle(x0, y0, x1, y1, cut, pad=0.0):
    cx, cy, r = cut
    nx = min(max(cx, x0), x1)
    ny = min(max(cy, y0), y1)
    return math.hypot(cx - nx, cy - ny) < r + pad


def round_x(cx, cy, half, w):
    """An X of two bars with round caps: arm reach `half` to the cap centre, half-width w."""
    c = s = math.sqrt(0.5)

    def rot(x, y):
        return (cx + x * c - y * s, cy + x * s + y * c)

    # walk the plus outline clockwise on screen (before the 45 deg turn):
    # each arm = (start, end) of its round cap, then the inner corner after it
    arms = [((half, -w), (half, w)), ((w, half), (-w, half)),
            ((-half, w), (-half, -w)), ((-w, -half), (w, -half))]
    inner = [(w, w), (-w, w), (-w, -w), (w, -w)]
    segs = [("M", *rot(*arms[0][0]))]
    for i in range(4):
        if i:
            segs.append(("L", *rot(*arms[i][0])))
        segs.append(("A", w, 0, 1, *rot(*arms[i][1])))
        segs.append(("L", *rot(*inner[i])))
    segs.append(("Z",))
    return seg_str(segs)


def crescent(cx, cy, r, ox, oy, r2):
    """Crescent: circle (cx, cy, r) minus circle (cx+ox, cy+oy, r2), as one contour."""
    bx, by = cx + ox, cy + oy
    d = math.hypot(ox, oy)
    a = (r * r - r2 * r2 + d * d) / (2 * d)
    h = math.sqrt(r * r - a * a)
    mx, my = cx + a * ox / d, cy + a * oy / d
    p1 = (mx + h * oy / d, my - h * ox / d)
    p2 = (mx - h * oy / d, my + h * ox / d)
    # outer arc of the moon (large), then inner arc of the biting circle back
    return (f"M{f(p1[0])} {f(p1[1])}A{f(r)} {f(r)} 0 1 0 {f(p2[0])} {f(p2[1])}"
            f"A{f(r2)} {f(r2)} 0 0 1 {f(p1[0])} {f(p1[1])}Z")


def capsule(x0, y0, x1, y1):
    r = (y1 - y0) / 2
    return (f"M{f(x0 + r)} {f(y0)}H{f(x1 - r)}A{f(r)} {f(r)} 0 0 1 {f(x1 - r)} {f(y1)}"
            f"H{f(x0 + r)}A{f(r)} {f(r)} 0 0 1 {f(x0 + r)} {f(y0)}Z")


# ---------------------------------------------------------------- the icon

BASE = dict(
    # rug body (without fringe)
    x0=34.0, y0=30.0, w=33.0, h=40.0, rc=2.0,
    fringe=3.2, tassels=6, tassel_w=2.5, tassel_inset=2.2,
    frame_in=3.0, frame_t=2.6,           # white border inset and thickness
    loz_a=8.6, loz_b=11.4, loz_t=3.2,    # lozenge half-width/height, band width
    loz_dot=0.0,
    # badge
    corner="br",                         # "br" (bottom-right) or "tr" (top-right)
    badge_dx=-1.8, badge_dy=-1.8,        # badge centre relative to that rug corner
    badge_r=12.0, gap=2.8,
    glyph="x",                           # "x", "crescent", "minus", "plain"
    shift=(0.0, 0.0),
    # colours
    bg="#FBF9F5", bg2="#F0EAE0",
    rug="#1F5FA8", line="#FFFFFF", fringe_col=None,
    badge="#16365F", glyph_col="#FFFFFF",
)


def glyph_paths(c, bx, by):
    """Badge glyph, drawn for a 12.5dp badge and scaled with badge_r."""
    k = c["badge_r"] / 12.5

    def P(pts):
        return poly([(bx + k * x, by + k * y) for x, y in pts])

    g = c["glyph"]
    if g == "x":
        spk = P([(-8.0, -2.8), (-5.0, -2.8), (-1.0, -6.6), (-1.0, 6.6), (-5.0, 2.8), (-8.0, 2.8)])
        return spk + round_x(bx + k * 4.6, by, k * 3.9, k * 1.5)
    if g == "plain":
        return P([(-5.6, -3.2), (-2.0, -3.2), (2.8, -7.6), (2.8, 7.6), (-2.0, 3.2), (-5.6, 3.2)])
    if g == "crescent":
        return crescent(bx - 0.6 * k, by + 0.3 * k, 7.4 * k, 4.0 * k, -3.4 * k, 6.4 * k)
    if g == "minus":
        return capsule(bx - 7.0 * k, by - 1.9 * k, bx + 7.0 * k, by + 1.9 * k)
    raise ValueError(g)


def build(cfg):
    c = dict(BASE, **cfg)
    sx, sy = c["shift"]
    x0, y0 = c["x0"] + sx, c["y0"] + sy
    x1, y1 = x0 + c["w"], y0 + c["h"]
    corner = c["corner"]
    if corner == "br":
        bx, by = x1 + c["badge_dx"], y1 + c["badge_dy"]
    else:
        bx, by = x1 + c["badge_dx"], y0 + c["badge_dy"]
    br = c["badge_r"]
    cut = (bx, by, br + c["gap"])
    rcx, rcy = (x0 + x1) / 2, (y0 + y1) / 2

    rug = rrect_cut(x0, y0, x1, y1, c["rc"], cut, corner)

    # fringe: n tassels per end, rounded free ends, whole tassels dropped near the badge
    n, fw, fi = c["tassels"], c["tassel_w"], c["tassel_inset"]
    gap = (c["w"] - 2 * fi - n * fw) / (n - 1)
    tassel_paths = []
    rr = fw / 2
    for i in range(n):
        tx = x0 + fi + i * (fw + gap)
        for top in (True, False):
            ty0, ty1 = ((y0 - c["fringe"], y0 + 0.6) if top else (y1 - 0.6, y1 + c["fringe"]))
            if rect_hits_circle(tx, ty0 - 0.3, tx + fw, ty1 + 0.3, cut, 0.3):
                continue
            if top:
                tassel_paths.append(
                    f"M{f(tx)} {f(ty1)}V{f(ty0 + rr)}A{f(rr)} {f(rr)} 0 0 1 {f(tx + fw)} {f(ty0 + rr)}"
                    f"V{f(ty1)}Z")
            else:
                tassel_paths.append(
                    f"M{f(tx)} {f(ty0)}H{f(tx + fw)}V{f(ty1 - rr)}A{f(rr)} {f(rr)} 0 0 1 {f(tx)} {f(ty1 - rr)}Z")

    # one white border (ring), cut by the badge circle
    fi_, ft = c["frame_in"], c["frame_t"]
    fo = rrect_cut(x0 + fi_, y0 + fi_, x1 - fi_, y1 - fi_, 0.8, cut, corner)
    fin = rrect_cut(x0 + fi_ + ft, y0 + fi_ + ft, x1 - fi_ - ft, y1 - fi_ - ft, 0.3, cut, corner)
    frame = fo + fin

    # one bold lozenge: a white band diamond (optional dot)
    la, lb, lt = c["loz_a"], c["loz_b"], c["loz_t"]
    k = lt * math.hypot(la, lb) / lb
    lo = diamond(rcx, rcy, la, lb)
    li = diamond(rcx, rcy, la - k, lb - k * lb / la)
    dot = diamond(rcx, rcy, c["loz_dot"], c["loz_dot"] * lb / la) if c["loz_dot"] else ""

    glyph = glyph_paths(c, bx, by)
    badge = circle_path(bx, by, br)

    fringe_col = c["fringe_col"] or c["rug"]
    fg = [
        f'<path fill="{fringe_col}" d="{"".join(tassel_paths)}"/>',
        f'<path fill="{c["rug"]}" d="{rug}"/>',
        f'<path fill="{c["line"]}" fill-rule="evenodd" d="{frame}"/>',
        f'<path fill="{c["line"]}" fill-rule="evenodd" d="{lo}{li}{dot}"/>',
        f'<path fill="{c["badge"]}" d="{badge}"/>',
        f'<path fill="{c["glyph_col"]}" d="{glyph}"/>',
    ]
    mono = [
        f'<path fill="#000000" d="{"".join(tassel_paths)}"/>',
        f'<path fill="#000000" fill-rule="evenodd" d="{rug}{frame}{lo}{li}{dot}"/>',
        f'<path fill="#000000" fill-rule="evenodd" d="{badge}{glyph}"/>',
    ]

    if c["bg2"]:
        bgdef = (f'<defs><radialGradient id="g" gradientUnits="userSpaceOnUse" cx="54" cy="44" r="70">'
                 f'<stop offset="0" stop-color="{c["bg"]}"/><stop offset="1" stop-color="{c["bg2"]}"/>'
                 f'</radialGradient></defs><rect width="108" height="108" fill="url(#g)"/>')
    else:
        bgdef = f'<rect width="108" height="108" fill="{c["bg"]}"/>'
    return bgdef, "".join(fg), "".join(mono)


def svg(body):
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">'
            + body + "</svg>\n")


def write(dirpath, cfg):
    dirpath.mkdir(parents=True, exist_ok=True)
    bg, fg, mono = build(cfg)
    for name, body in (("background.svg", bg), ("foreground.svg", fg),
                       ("monochrome.svg", mono), ("icon.svg", bg + fg)):
        (dirpath / name).write_text(svg(body), encoding="utf-8", newline="\n")


TEAL = dict(bg="#00695C", bg2=None, rug="#F1E4C6", line="#00695C", badge="#C6453A")
VARIANTS = {
    "r2-navy": dict(badge_r=12.5, badge_dx=-2.0, badge_dy=-2.0),
    "r2-red": dict(badge="#B8392F"),
    "r2-crescent": dict(glyph="crescent"),
    "r2-minus": dict(glyph="minus"),
    "teal-cream": dict(TEAL),
    "r2-b115": dict(badge_r=11.5, badge_dx=-1.5, badge_dy=-1.5, shift=(0.5, 0.5)),
    "final": dict(),  # written to the folder root only
    "r2-apart": dict(x0=29.0, y0=26.0, w=30.0, h=38.0, badge_r=10.5, gap=2.5,
                     badge_dx=11.0, badge_dy=4.5, loz_a=7.8, loz_b=10.4, loz_t=3.0),
}
FINAL = "final"

if __name__ == "__main__":
    import sys
    names = sys.argv[1:] or [n for n in VARIANTS if n != FINAL]
    for name in names:
        write(HERE / "_var" / name, VARIANTS[name])
    write(HERE, VARIANTS[FINAL])
