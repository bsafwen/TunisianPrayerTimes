"""Generator for 05-dnd-crescent «هلال السكينة» Quiet Crescent (revision 2).

A crescent in the Do Not Disturb pose (hollow opening to the upper right)
cradles an upright Kairouan prayer rug, now large enough to read at 48px.
The previous round's generator is archived as _var/r1-gen.py.txt.

Usage: python -I gen.py            (writes the final layers + every _var)
"""

import math
from pathlib import Path

HERE = Path(__file__).parent


def f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


# ---------------------------------------------------------------- geometry

def crescent(c1, r1, r2, d, angle_deg):
    """circle(c1, r1) minus circle(c2, r2); c2 sits d away toward angle_deg
    (screen degrees, 0 = right, -90 = up). Returns (path, c2)."""
    ux, uy = math.cos(math.radians(angle_deg)), math.sin(math.radians(angle_deg))
    c2 = (c1[0] + d * ux, c1[1] + d * uy)
    a = (r1 * r1 - r2 * r2 + d * d) / (2 * d)
    h = math.sqrt(r1 * r1 - a * a)
    bx, by = c1[0] + a * ux, c1[1] + a * uy
    px, py = -uy, ux
    p = (bx + h * px, by + h * py)
    q = (bx - h * px, by - h * py)
    inner_large = 1 if a > d else 0
    path = (
        f"M{f(p[0])} {f(p[1])}"
        f"A{f(r1)} {f(r1)} 0 1 1 {f(q[0])} {f(q[1])}"
        f"A{f(r2)} {f(r2)} 0 {inner_large} 0 {f(p[0])} {f(p[1])}Z"
    )
    return path, c2


def rrect(x, y, w, h, r):
    return (
        f"M{f(x + r)} {f(y)}H{f(x + w - r)}"
        f"A{f(r)} {f(r)} 0 0 1 {f(x + w)} {f(y + r)}V{f(y + h - r)}"
        f"A{f(r)} {f(r)} 0 0 1 {f(x + w - r)} {f(y + h)}H{f(x + r)}"
        f"A{f(r)} {f(r)} 0 0 1 {f(x)} {f(y + h - r)}V{f(y + r)}"
        f"A{f(r)} {f(r)} 0 0 1 {f(x + r)} {f(y)}Z"
    )


def diamond(cx, cy, hw, hh):
    return f"M{f(cx)} {f(cy - hh)}L{f(cx + hw)} {f(cy)}L{f(cx)} {f(cy + hh)}L{f(cx - hw)} {f(cy)}Z"


def poly(pts):
    return "M" + "L".join(f"{f(x)} {f(y)}" for x, y in pts) + "Z"


def comb(xl, xr, y_edge, y_base, teeth, notch_w, notch_d):
    """A fringe band from y_base (under the rug body) to y_edge (the free end),
    with teeth-1 notches cut into the free edge. Works for top or bottom."""
    s = 1 if y_edge > y_base else -1           # +1: fringe hangs below
    y_n = y_edge - s * notch_d                 # notch floor
    span = xr - xl
    tooth_w = (span - (teeth - 1) * notch_w) / teeth
    pts = [(xl, y_base), (xl, y_edge)]
    x = xl
    for i in range(teeth - 1):
        x += tooth_w
        pts += [(x, y_edge), (x, y_n), (x + notch_w, y_n), (x + notch_w, y_edge)]
        x += notch_w
    pts += [(xr, y_edge), (xr, y_base)]
    return poly(pts)


def rug_parts(cfg):
    cx, cy = cfg["rug_c"]
    w, bh, fh = cfg["rug_w"], cfg["body_h"], cfg["fringe_h"]
    x0, y0 = cx - w / 2, cy - bh / 2
    inset, gap = cfg["fringe_inset"], cfg.get("fringe_gap", 0.0)
    ov = 0.6 if not gap else 0.0
    top = comb(x0 + inset, x0 + w - inset, y0 - gap - fh, y0 - gap + ov,
               cfg["teeth"], cfg["notch_w"], cfg["notch_d"])
    bot = comb(x0 + inset, x0 + w - inset, y0 + bh + gap + fh, y0 + bh + gap - ov,
               cfg["teeth"], cfg["notch_w"], cfg["notch_d"])
    lw, lh = cfg["loz"]
    loz = diamond(cx, cy + cfg.get("loz_dy", 0.0), lw / 2, lh / 2)
    parts = {"body": rrect(x0, y0, w, bh, cfg["rug_r"]), "fringe": top + bot, "loz": loz}
    if cfg.get("loz_hole"):
        hw, hh = cfg["loz_hole"]
        parts["loz_in"] = diamond(cx, cy, hw / 2, hh / 2)
    if cfg.get("niche"):
        # mihrab niche: an inset panel with a pointed-arch top
        side, top_in, bot_in, ah = cfg["niche"]
        xl, xr = x0 + side, x0 + w - side
        ya, yb = y0 + top_in, y0 + bh - bot_in
        ys = ya + ah
        pw = xr - xl
        parts["niche"] = (
            f"M{f(xl)} {f(yb)}V{f(ys)}"
            f"C{f(xl)} {f(ys - 0.5 * ah)} {f(cx - 0.18 * pw)} {f(ya + 0.3 * ah)} {f(cx)} {f(ya)}"
            f"C{f(cx + 0.18 * pw)} {f(ya + 0.3 * ah)} {f(xr)} {f(ys - 0.5 * ah)} {f(xr)} {f(ys)}"
            f"V{f(yb)}Z"
        )
    if cfg.get("frame"):
        t = cfg["frame"]
        parts["frame"] = parts["body"] + rrect(x0 + t, y0 + t, w - 2 * t, bh - 2 * t, max(cfg["rug_r"] - t, 0.3))
    if cfg.get("bands"):
        # two thin end bands across the field, tonal with the lozenge
        bt, bo = cfg["bands"]
        parts["bands"] = (rrect(x0 + 1.6, y0 + bo, w - 3.2, bt, bt / 2)
                          + rrect(x0 + 1.6, y0 + bh - bo - bt, w - 3.2, bt, bt / 2))
    return parts


def rug_box(cfg):
    cx, cy = cfg["rug_c"]
    hw = cfg["rug_w"] / 2
    hh = cfg["body_h"] / 2 + cfg.get("fringe_gap", 0.0) + cfg["fringe_h"]
    return cx - hw, cy - hh, cx + hw, cy + hh


def crescent_edge(cfg, c2, n=3600):
    c1, r1, r2 = cfg["c1"], cfg["r1"], cfg["r2"]
    pts = []
    for k in range(n):
        t = 2 * math.pi * k / n
        p = (c2[0] + r2 * math.cos(t), c2[1] + r2 * math.sin(t))
        if math.dist(p, c1) <= r1:
            pts.append(p)
        p = (c1[0] + r1 * math.cos(t), c1[1] + r1 * math.sin(t))
        if math.dist(p, c2) >= r2:
            pts.append(p)
    return pts


def clearance(cfg, c2, edge=None):
    x0, y0, x1, y1 = rug_box(cfg)
    best = 99.0
    for p in edge or crescent_edge(cfg, c2):
        dx = max(x0 - p[0], 0, p[0] - x1)
        dy = max(y0 - p[1], 0, p[1] - y1)
        best = min(best, math.hypot(dx, dy))
    return best


def place_rug(cfg, c2):
    """Slide the rug from c2 along the opening direction (plus a nudge) until
    it clears every crescent edge by cfg['gap'] dp."""
    ux, uy = math.cos(math.radians(cfg["angle"])), math.sin(math.radians(cfg["angle"]))
    nx, ny = cfg.get("rug_nudge", (0, 0))
    edge = crescent_edge(cfg, c2, 1800)
    s = -8.0
    while s < 14:
        cfg["rug_c"] = (c2[0] + s * ux + nx, c2[1] + s * uy + ny)
        if clearance(cfg, c2, edge) >= cfg["gap"]:
            return
        s += 0.05


def report(name, cfg, c2):
    x0, y0, x1, y1 = rug_box(cfg)
    sx = sy = n = 0
    c1, r1, r2 = cfg["c1"], cfg["r1"], cfg["r2"]
    for i in range(0, 1080, 3):
        for j in range(0, 1080, 3):
            x, y = i / 10, j / 10
            if (math.dist((x, y), c1) <= r1 and math.dist((x, y), c2) > r2) or \
                    (x0 <= x <= x1 and y0 <= y <= y1):
                sx += x; sy += y; n += 1
    far = max(math.dist((54, 54), (x, y)) for x in (x0, x1) for y in (y0, y1))
    thick = r1 + cfg["d"] - r2
    print(f"{name:10s} rug {x1 - x0:.1f}x{y1 - y0:.1f} at ({cfg['rug_c'][0]:.1f},{cfg['rug_c'][1]:.1f})"
          f"  clear {clearance(cfg, c2):.2f}  centroid ({sx / n:.1f},{sy / n:.1f})"
          f"  rug corner r={far:.1f}  moon thick {thick:.1f}")


# ---------------------------------------------------------------- variants

BASE = dict(
    # crescent: One UI / iOS DND pose, hollow opening to the upper right
    c1=(54.9, 53.4), r1=20.6, r2=17.6, d=10.4, angle=-45, gap=3.2, rug_nudge=(0.0, 0.0),
    # rug: upright, mihrab niche + lozenge, fringe bands split off by a 0.8dp gap
    rug_w=15.6, body_h=18.6, rug_r=1.2, fringe_h=2.2, fringe_gap=0.8, fringe_inset=0.9,
    teeth=6, notch_w=0.9, notch_d=1.4, niche=(2.0, 2.0, 2.0, 5.6),
    loz=(5.6, 7.4), loz_dy=1.6, loz_hole=None, bands=None, bands_fill="#F6E9CF",
)
BLUE = dict(bg=("#3D86D2", "#1D5FA6"), moon="#FFFFFF", rug="#F6E9CF",
            niche_fill="#B5412F", loz_fill="#F6E9CF", fringe="#F6E9CF")
DAY = dict(bg=("#F2F7FC", "#B9D3EE"), moon="#1D5FA6", rug="#B5412F",
           niche_fill="#F6E9CF", loz_fill="#B5412F", fringe="#C99F5E")

VARIANTS = {
    "blue": dict(BLUE),
    "day": dict(DAY),
    # the critics' literal suggestion: cream rug with one madder lozenge, no niche
    "blue-lozenge": dict(BLUE, niche=None, loz=(10.0, 13.0), loz_dy=0.0, loz_fill="#B5412F"),
    # madder field in a cream frame (Kairouan border)
    "blue-red": dict(BLUE, niche=None, rug="#B5412F", frame=1.5, loz=(8.0, 10.4), loz_dy=0.0),
    # notched fringe attached to the body (reads as battlements at 96px)
    "blue-attached": dict(BLUE, body_h=18.4, fringe_gap=0.0, fringe_h=3.0, notch_d=1.8,
                          fringe="#E3C998"),
}
FINAL = "blue"


def build(cfg):
    moon, c2 = crescent(cfg["c1"], cfg["r1"], cfg["r2"], cfg["d"], cfg["angle"])
    place_rug(cfg, c2)
    rp = rug_parts(cfg)
    top, bot = cfg["bg"]
    head = '<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">\n'
    grad = (
        '  <defs>\n'
        '    <linearGradient id="sky" gradientUnits="userSpaceOnUse" x1="54" y1="18" x2="54" y2="90">\n'
        f'      <stop offset="0" stop-color="{top}"/>\n'
        f'      <stop offset="1" stop-color="{bot}"/>\n'
        '    </linearGradient>\n'
        '  </defs>\n'
    )
    bg_body = '  <rect x="0" y="0" width="108" height="108" fill="url(#sky)"/>\n'
    loz = rp["loz"] + rp.get("loz_in", "")
    fg_body = (
        f'  <path fill="{cfg["moon"]}" d="{moon}"/>\n'
        f'  <path fill="{cfg["fringe"]}" d="{rp["fringe"]}"/>\n'
        f'  <path fill="{cfg["rug"]}" d="{rp["body"]}"/>\n'
    )
    if "frame" in rp:
        fg_body += f'  <path fill="{cfg["fringe"]}" fill-rule="evenodd" d="{rp["frame"]}"/>\n'
    if "niche" in rp:
        fg_body += f'  <path fill="{cfg["niche_fill"]}" d="{rp["niche"]}"/>\n'
    if "bands" in rp:
        fg_body += f'  <path fill="{cfg["bands_fill"]}" d="{rp["bands"]}"/>\n'
    fg_body += f'  <path fill="{cfg["loz_fill"]}" fill-rule="evenodd" d="{loz}"/>\n'
    # themed layer: one solid lozenge hole, fringe split from the body by a gap
    mono_cfg = dict(cfg)
    if not cfg.get("fringe_gap"):
        mono_cfg.update(fringe_gap=0.8, fringe_h=cfg["fringe_h"] - 0.8, notch_d=1.0)
    mrp = rug_parts(mono_cfg)
    mono_body = (
        f'  <path fill="#000000" d="{moon}"/>\n'
        f'  <path fill="#000000" d="{mrp["fringe"]}"/>\n'
        f'  <path fill="#000000" fill-rule="evenodd" d="{mrp["body"]}{mrp.get("niche", "")}{mrp["loz"]}"/>\n'
    )
    return {
        "background.svg": head + grad + bg_body + "</svg>\n",
        "foreground.svg": head + fg_body + "</svg>\n",
        "monochrome.svg": head + mono_body + "</svg>\n",
        "icon.svg": head + grad + bg_body + fg_body + "</svg>\n",
    }, c2


def write(dirpath, files):
    dirpath.mkdir(parents=True, exist_ok=True)
    for name, text in files.items():
        with open(dirpath / name, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)


if __name__ == "__main__":
    for name, over in VARIANTS.items():
        cfg = {**BASE, **over}
        files, c2 = build(cfg)
        report(name, cfg, c2)
        if name != FINAL:
            write(HERE / "_var" / name, files)
    files, _ = build({**BASE, **VARIANTS[FINAL]})
    write(HERE, files)
    print("final =", FINAL)
