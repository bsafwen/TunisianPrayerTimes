"""Generator for 07-rug-badge «سجادة صامتة» (Muted Rug).

python -I gen.py            writes the final layers into this folder and every
                            explored variant into _var/<name>/.
Standard library only. Geometry in dp on the 108 canvas.
"""

import math
from pathlib import Path

HERE = Path(__file__).parent


def f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


# ---------------------------------------------------------------- geometry

def arc_flags(cx, cy, a, b):
    """Flags for an arc from a to b going the screen-anticlockwise way."""
    ta = math.atan2(a[1] - cy, a[0] - cx)
    tb = math.atan2(b[1] - cy, b[0] - cx)
    travel = (ta - tb) % (2 * math.pi)
    return (1 if travel > math.pi else 0), 0


def rect_cut(x0, y0, x1, y1, rc, cut):
    """Rounded rect, its top-right corner bitten by circle cut=(cx, cy, r)."""
    cx, cy, r = cut
    inter_top = r * r - (y0 - cy) ** 2
    inter_right = r * r - (x1 - cx) ** 2
    if inter_top <= 0 or inter_right <= 0 or math.hypot(x1 - cx, y0 - cy) > r:
        return rrect(x0, y0, x1, y1, rc)
    xa = cx - math.sqrt(inter_top)
    yb = cy + math.sqrt(inter_right)
    large, sweep = arc_flags(cx, cy, (xa, y0), (x1, yb))
    p = [f"M{f(x0 + rc)} {f(y0)}", f"H{f(xa)}",
         f"A{f(r)} {f(r)} 0 {large} {sweep} {f(x1)} {f(yb)}",
         f"V{f(y1 - rc)}"]
    if rc:
        p.append(f"A{f(rc)} {f(rc)} 0 0 1 {f(x1 - rc)} {f(y1)}")
    p.append(f"H{f(x0 + rc)}")
    if rc:
        p.append(f"A{f(rc)} {f(rc)} 0 0 1 {f(x0)} {f(y1 - rc)}")
    p.append(f"V{f(y0 + rc)}")
    if rc:
        p.append(f"A{f(rc)} {f(rc)} 0 0 1 {f(x0 + rc)} {f(y0)}")
    p.append("Z")
    return "".join(p)


def rrect(x0, y0, x1, y1, rc):
    if not rc:
        return f"M{f(x0)} {f(y0)}H{f(x1)}V{f(y1)}H{f(x0)}Z"
    return (f"M{f(x0 + rc)} {f(y0)}H{f(x1 - rc)}A{f(rc)} {f(rc)} 0 0 1 {f(x1)} {f(y0 + rc)}"
            f"V{f(y1 - rc)}A{f(rc)} {f(rc)} 0 0 1 {f(x1 - rc)} {f(y1)}"
            f"H{f(x0 + rc)}A{f(rc)} {f(rc)} 0 0 1 {f(x0)} {f(y1 - rc)}"
            f"V{f(y0 + rc)}A{f(rc)} {f(rc)} 0 0 1 {f(x0 + rc)} {f(y0)}Z")


def poly(pts):
    return "M" + "L".join(f"{f(x)} {f(y)}" for x, y in pts) + "Z"


def circle_path(cx, cy, r, ccw=False):
    s = 0 if ccw else 1
    return (f"M{f(cx - r)} {f(cy)}A{f(r)} {f(r)} 0 1 {s} {f(cx + r)} {f(cy)}"
            f"A{f(r)} {f(r)} 0 1 {s} {f(cx - r)} {f(cy)}Z")


def diamond(cx, cy, a, b):
    return poly([(cx, cy - b), (cx + a, cy), (cx, cy + b), (cx - a, cy)])


def rect_hits_circle(x0, y0, x1, y1, cut, pad=0.0):
    cx, cy, r = cut
    nx = min(max(cx, x0), x1)
    ny = min(max(cy, y0), y1)
    return math.hypot(cx - nx, cy - ny) < r + pad


def cross_poly(cx, cy, half, w):
    """An X: a plus sign of arm half-length `half`, half-width w, turned 45 deg."""
    pts = [(w, w), (half, w), (half, -w), (w, -w), (w, -half), (-w, -half),
           (-w, -w), (-half, -w), (-half, w), (-w, w), (-w, half), (w, half)]
    c = s = math.sqrt(0.5)
    return poly([(cx + x * c - y * s, cy + x * s + y * c) for x, y in pts])


# ---------------------------------------------------------------- the icon

BASE = dict(
    # rug body (without fringe)
    x0=35.0, y0=34.0, w=34.0, h=44.0, rc=1.2,
    fringe=4.0, tassel_w=2.0, tassel_gap=1.6, tassel_inset=1.6,
    frame_in=3.4, frame_t=2.0,           # white frame inset and thickness
    saw=False,                           # Kairouan sawtooth inside the frame
    saw_w=3.0, saw_h=2.2,
    loz_a=8.0, loz_b=11.0, loz_t=2.6,    # lozenge half-width/height, band width
    loz_dot=2.2,
    # badge
    badge_dx=-4.0, badge_dy=3.0,         # badge centre relative to rug top-right
    badge_r=11.5, gap=2.6,
    glyph="x",                           # "x" or "plain"
    shift=(0.0, 0.0),
    # colours
    bg="#F6F2EA", bg2=None,
    rug="#1F5FA8", line="#FFFFFF", fringe_col=None,
    badge="#D2453A", glyph_col="#FFFFFF",
)


def build(cfg):
    c = dict(BASE, **cfg)
    sx, sy = c["shift"]
    x0, y0 = c["x0"] + sx, c["y0"] + sy
    x1, y1 = x0 + c["w"], y0 + c["h"]
    bx, by = x1 + c["badge_dx"], y0 + c["badge_dy"]
    br = c["badge_r"]
    cut = (bx, by, br + c["gap"])
    rcx, rcy = (x0 + x1) / 2, (y0 + y1) / 2

    rug = rect_cut(x0, y0, x1, y1, c["rc"], cut)

    # fringe tassels, dropped where the badge ring passes
    fw, fg_, fi = c["tassel_w"], c["tassel_gap"], c["tassel_inset"]
    span = c["w"] - 2 * fi
    n = int((span + fg_) // (fw + fg_))
    used = n * fw + (n - 1) * fg_
    start = x0 + fi + (span - used) / 2
    # tassels: rounded free end only -> draw as rect plus end circle
    tassel_paths = []
    for i in range(n):
        tx = start + i * (fw + fg_)
        for top in (True, False):
            ty0, ty1 = ((y0 - c["fringe"], y0 + 0.6) if top else (y1 - 0.6, y1 + c["fringe"]))
            if rect_hits_circle(tx, ty0 - 0.5, tx + fw, ty1, cut, 0.4):
                continue
            rr = fw / 2
            if top:
                tassel_paths.append(
                    f"M{f(tx)} {f(ty1)}V{f(ty0 + rr)}A{f(rr)} {f(rr)} 0 0 1 {f(tx + fw)} {f(ty0 + rr)}"
                    f"V{f(ty1)}Z")
            else:
                tassel_paths.append(
                    f"M{f(tx)} {f(ty0)}H{f(tx + fw)}V{f(ty1 - rr)}A{f(rr)} {f(rr)} 0 0 1 {f(tx)} {f(ty1 - rr)}Z")

    # white frame (ring), cut by the badge circle
    fi_, ft = c["frame_in"], c["frame_t"]
    fo = rect_cut(x0 + fi_, y0 + fi_, x1 - fi_, y1 - fi_, 0.4, cut)
    fin = rect_cut(x0 + fi_ + ft, y0 + fi_ + ft, x1 - fi_ - ft, y1 - fi_ - ft, 0, cut)
    frame = fo + fin

    # Kairouan sawtooth: small triangles pointing in, on the long sides
    saw = ""
    if c["saw"]:
        ix0, ix1 = x0 + fi_ + ft, x1 - fi_ - ft
        iy0, iy1 = y0 + fi_ + ft, y1 - fi_ - ft
        tw, th = c["saw_w"], c["saw_h"]
        k = int((iy1 - iy0) // tw)
        off = ((iy1 - iy0) - k * tw) / 2
        for i in range(k):
            ya = iy0 + off + i * tw
            for side in (0, 1):
                if side == 0:
                    pts = [(ix0, ya), (ix0 + th, ya + tw / 2), (ix0, ya + tw)]
                else:
                    pts = [(ix1, ya), (ix1 - th, ya + tw / 2), (ix1, ya + tw)]
                if any(math.hypot(px - cut[0], py - cut[1]) < cut[2] + 0.3 for px, py in pts):
                    continue
                saw += poly(pts)

    # lozenge: white band diamond with a dot
    la, lb, lt = c["loz_a"], c["loz_b"], c["loz_t"]
    k = lt * math.hypot(la, lb) / lb  # inset along the axes for an even band
    lo = diamond(rcx, rcy, la, lb)
    li = diamond(rcx, rcy, la - k, lb - k * lb / la)
    dot = diamond(rcx, rcy, c["loz_dot"], c["loz_dot"] * lb / la) if c["loz_dot"] else ""
    loz_white = lo + li + dot

    # badge glyph: speaker (+ x)
    if c["glyph"] == "x":
        gx = bx - 1.3
        spk = poly([(gx - 6.0, by - 2.7), (gx - 3.0, by - 2.7), (gx + 1.0, by - 6.4),
                    (gx + 1.0, by + 6.4), (gx - 3.0, by + 2.7), (gx - 6.0, by + 2.7)])
        x_ = cross_poly(gx + 5.6, by, 3.9, 1.05)
    else:
        gx = bx + 1.6
        spk = poly([(gx - 7.2, by - 3.2), (gx - 3.6, by - 3.2), (gx + 1.2, by - 7.6),
                    (gx + 1.2, by + 7.6), (gx - 3.6, by + 3.2), (gx - 7.2, by + 3.2)])
        x_ = ""
    badge = circle_path(bx, by, br)

    fringe_col = c["fringe_col"] or c["rug"]
    fg = [
        f'<path fill="{fringe_col}" d="{"".join(tassel_paths)}"/>',
        f'<path fill="{c["rug"]}" d="{rug}"/>',
        f'<path fill="{c["line"]}" fill-rule="evenodd" d="{frame}"/>',
    ]
    if saw:
        fg.append(f'<path fill="{c["line"]}" d="{saw}"/>')
    fg += [
        f'<path fill="{c["line"]}" fill-rule="evenodd" d="{loz_white}"/>',
        f'<path fill="{c["badge"]}" d="{badge}"/>',
        f'<path fill="{c["glyph_col"]}" d="{spk}{x_}"/>',
    ]

    # monochrome: rug solid with frame/lozenge as holes; badge island with glyph holes
    mono_rug = rug + frame + lo + li + dot
    mono = [
        f'<path fill="#000000" d="{"".join(tassel_paths)}"/>',
        f'<path fill="#000000" fill-rule="evenodd" d="{mono_rug}"/>',
    ]
    if saw:
        # sawtooth teeth become holes: draw the inner field again minus teeth
        mono = [
            f'<path fill="#000000" d="{"".join(tassel_paths)}"/>',
            f'<path fill="#000000" fill-rule="evenodd" d="{rug + frame + saw + lo + li + dot}"/>',
        ]
    mono.append(f'<path fill="#000000" fill-rule="evenodd" d="{badge}{spk}{x_}"/>')

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


def write(dirpath, cfg, merged=True):
    dirpath.mkdir(parents=True, exist_ok=True)
    bg, fg, mono = build(cfg)
    for name, body in (("background.svg", bg), ("foreground.svg", fg), ("monochrome.svg", mono)):
        (dirpath / name).write_text(svg(body), encoding="utf-8", newline="\n")
    if merged:
        (dirpath / "icon.svg").write_text(svg(bg + fg), encoding="utf-8", newline="\n")


B12 = dict(badge_r=12.2, badge_dx=-4.6, badge_dy=3.4)
WIDE = dict(x0=33.5, w=36.5, h=43.0, y0=34.5, loz_a=8.6, loz_b=10.6)
VARIANTS = {
    "blue-x": dict(),
    "blue-wide": dict(WIDE),
    "blue-wide-saw": dict(WIDE, saw=True),
    "blue-wide-b12": dict(WIDE, badge_r=12.2, badge_dx=-4.6, badge_dy=3.4),
    "blue-plain": dict(glyph="plain"),
    "teal-cream": dict(bg="#00695C", rug="#F1E4C6", line="#00695C"),
    "blue-wsb": dict(WIDE, **B12, saw=True),
    "teal-wsb": dict(WIDE, **B12, saw=True, bg="#00695C", rug="#F1E4C6", line="#00695C"),
    "blue-big-teeth": dict(WIDE, **B12, saw=True, saw_w=4.2, saw_h=2.6, shift=(-1.0, 0.0)),
    "blue-grad": dict(WIDE, **B12, saw=True, saw_w=4.2, saw_h=2.6, shift=(-1.0, 0.0),
                      bg="#FCFAF6", bg2="#ECE5D8"),
    "final": dict(WIDE, **B12, saw=True, saw_w=4.2, saw_h=2.5, shift=(-1.0, 0.0),
                  loz_a=7.8, loz_b=10.3, loz_t=2.5, loz_dot=2.0, bg="#FBF9F5", bg2="#F0EAE0"),
}
FINAL = "final"

if __name__ == "__main__":
    for name, cfg in VARIANTS.items():
        write(HERE / "_var" / name, cfg, merged=False)
    write(HERE, VARIANTS[FINAL])
