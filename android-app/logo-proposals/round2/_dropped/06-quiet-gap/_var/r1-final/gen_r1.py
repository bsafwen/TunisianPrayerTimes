"""Generate «فسحة الهدوء» The Quiet Gap: a waveform whose sound stops for a prayer rug.

Run: python -I gen.py   (writes the final layers here and every variant under _var/)
"""

from pathlib import Path

HERE = Path(__file__).parent
CX = CY = 54.0


def f(v: float) -> str:
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def rrect(x, y, w, h, r):
    """Rounded rectangle as a path (arcs), clockwise."""
    r = min(r, w / 2, h / 2)
    top = f"H{f(x + w - r)}" if w - 2 * r > 0.01 else ""
    bot = f"H{f(x + r)}" if w - 2 * r > 0.01 else ""
    return (
        f"M{f(x + r)} {f(y)}{top}A{f(r)} {f(r)} 0 0 1 {f(x + w)} {f(y + r)}"
        f"V{f(y + h - r)}A{f(r)} {f(r)} 0 0 1 {f(x + w - r)} {f(y + h)}"
        f"{bot}A{f(r)} {f(r)} 0 0 1 {f(x)} {f(y + h - r)}"
        f"V{f(y + r)}A{f(r)} {f(r)} 0 0 1 {f(x + r)} {f(y)}Z"
    )


def arch(x, y_spring, w, y_bottom, pointed=0.0):
    """Arch-topped panel: straight sides from y_bottom up to y_spring, then a round
    (pointed > 0 lifts the apex) top. Counter-clockwise so it can cut a hole."""
    r = w / 2
    cx = x + r
    top = y_spring - r - pointed
    # two cubic quarter curves from the left spring point to the apex and down
    k = 0.5523 * r
    return (
        f"M{f(x)} {f(y_bottom)}V{f(y_spring)}"
        f"C{f(x)} {f(y_spring - k - pointed * 0.6)} {f(cx - k + pointed * 0.35)} {f(top)} {f(cx)} {f(top)}"
        f"C{f(cx + k - pointed * 0.35)} {f(top)} {f(x + w)} {f(y_spring - k - pointed * 0.6)} {f(x + w)} {f(y_spring)}"
        f"V{f(y_bottom)}Z"
    )


def pointed_arch(x, apex_y, w, bottom, lw, r_ratio, style):
    """Pointed (two-centred) arch of span w whose apex sits at apex_y.
    Each half is a circular arc of radius R = r_ratio * w centred on the spring line.
    style: pointed_open (a cream band shaped like an upside-down U),
           pointed_closed (band closed along the bottom), pointed_solid (filled niche)."""
    R = r_ratio * w
    cx = x + w / 2
    rise = (R * R - (R - w / 2) ** 2) ** 0.5
    ys = apex_y + rise
    outer_top = (f"M{f(x)} {f(bottom)}V{f(ys)}A{f(R)} {f(R)} 0 0 1 {f(cx)} {f(apex_y)}"
                 f"A{f(R)} {f(R)} 0 0 1 {f(x + w)} {f(ys)}V{f(bottom)}")
    if style == "pointed_solid":
        return outer_top + "Z"
    Ri = R - lw
    in_apex = ys - (Ri * Ri - (R - w / 2) ** 2) ** 0.5
    if style == "pointed_open":
        return (outer_top + f"H{f(x + w - lw)}V{f(ys)}A{f(Ri)} {f(Ri)} 0 0 0 {f(cx)} {f(in_apex)}"
                f"A{f(Ri)} {f(Ri)} 0 0 0 {f(x + lw)} {f(ys)}V{f(bottom)}Z")
    # closed: outer outline plus an inner outline (evenodd)
    ib = bottom - lw
    return (outer_top + "Z"
            + f"M{f(x + lw)} {f(ib)}V{f(ys)}A{f(Ri)} {f(Ri)} 0 0 1 {f(cx)} {f(in_apex)}"
              f"A{f(Ri)} {f(Ri)} 0 0 1 {f(x + w - lw)} {f(ys)}V{f(ib)}Z")


def build(p):
    s = p.get("scale", 1.0)

    def X(v):
        return CX + (v - CX) * s

    def Y(v):
        return CY + (v - CY) * s

    bars = []  # (cx, height)
    rug_w = p["rug_w"]
    space = p["space"]
    bw = p["bw"]
    pitch = bw + p["bar_gap"]
    inner_c = CX - rug_w / 2 - space - bw / 2
    for i, h in enumerate(p["heights"]):  # heights listed from the gap outwards
        c = inner_c - i * pitch
        bars.append((c, h))
        bars.append((2 * CX - c, p.get("heights_r", p["heights"])[i]))

    bar_paths = []
    for c, h in bars:
        x0, x1 = X(c - bw / 2), X(c + bw / 2)
        y0, y1 = Y(CY - h / 2), Y(CY + h / 2)
        bar_paths.append(rrect(x0, y0, x1 - x0, y1 - y0, (x1 - x0) / 2))

    # rug body
    rh = p["rug_h"]
    rx0, ry0 = X(CX - rug_w / 2), Y(CY - rh / 2 + p.get("rug_dy", 0))
    rw, rhh = rug_w * s, rh * s
    body = rrect(rx0, ry0, rw, rhh, p.get("rug_r", 1.2) * s)

    # inner arch band (cream line)
    ins = p["arch_inset"] * s
    lw = p["arch_lw"] * s
    ax0, aw = rx0 + ins, rw - 2 * ins
    ab = ry0 + rhh - ins
    aspring = ry0 + p["arch_spring"] * s
    style = p.get("arch_style", "round")
    if style == "round":
        outer = arch(ax0, aspring, aw, ab, p.get("pointed", 0) * s)
        inner = arch(ax0 + lw, aspring, aw - 2 * lw, ab - lw, max(0, p.get("pointed", 0) * s - lw * 0.3))
        arch_band = outer + inner  # evenodd ring
    else:
        arch_band = pointed_arch(ax0, ry0 + p.get("apex_inset", p["arch_inset"]) * s, aw, ab, lw,
                                 p.get("arch_R", 0.8), style)

    # fringe tassels, top and bottom
    fringe = []
    n = p["fringe_n"]
    tw = p["fringe_w"] * s
    tl = p["fringe_l"] * s
    span = rw - 2 * p.get("fringe_inset", 1.0) * s
    step = (span - tw) / (n - 1)
    for i in range(n):
        tx = rx0 + p.get("fringe_inset", 1.0) * s + i * step
        overlap = 0.8 * s
        fringe.append(rrect(tx, ry0 - tl, tw, tl + overlap, tw / 2))
        fringe.append(rrect(tx, ry0 + rhh - overlap, tw, tl + overlap, tw / 2))

    # optional diamond (kilim motif) under the arch apex
    extras = []
    if p.get("diamond"):
        d = p["diamond"] * s
        dcx = rx0 + rw / 2
        dcy = ry0 + p["diamond_y"] * s
        extras.append(f"M{f(dcx)} {f(dcy - d)}L{f(dcx + d)} {f(dcy)}L{f(dcx)} {f(dcy + d)}L{f(dcx - d)} {f(dcy)}Z")

    # optional quiet dots between the inner bars and the rug
    dots = []
    if p.get("dots"):
        r = p["dots"] * s
        for side in (-1, 1):
            dx = rug_w / 2 + space / 2
            dots.append((X(CX + side * dx), Y(CY), r))

    pal = p["pal"]
    head = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">\n'
    if pal.get("bg2"):
        bg_defs = (
            '  <defs>\n'
            f'    <radialGradient id="g" cx="54" cy="44" r="62" gradientUnits="userSpaceOnUse">\n'
            f'      <stop offset="0" stop-color="{pal["bg"]}"/>\n'
            f'      <stop offset="1" stop-color="{pal["bg2"]}"/>\n'
            '    </radialGradient>\n  </defs>\n'
            '  <rect x="0" y="0" width="108" height="108" fill="url(#g)"/>\n'
        )
    else:
        bg_defs = f'  <rect x="0" y="0" width="108" height="108" fill="{pal["bg"]}"/>\n'
    background = head + bg_defs + "</svg>\n"

    fg_body = (
        f'  <path fill="{pal["bars"]}" d="{"".join(bar_paths)}"/>\n'
        + "".join(f'  <circle cx="{f(x)}" cy="{f(y)}" r="{f(r)}" fill="{pal["bars"]}"/>\n' for x, y, r in dots)
        + f'  <path fill="{pal["fringe"]}" d="{"".join(fringe)}"/>\n'
        + f'  <path fill="{pal["rug"]}" d="{body}"/>\n'
        + f'  <path fill="{pal["line"]}" fill-rule="evenodd" d="{arch_band}"/>\n'
        + "".join(f'  <path fill="{pal["line"]}" d="{e}"/>\n' for e in extras)
    )
    foreground = head + fg_body + "</svg>\n"

    # monochrome: rug body with the arch band (and diamond) cut out, fringe and bars solid
    mono_rug = body + arch_band + "".join(extras)
    mono = (
        head
        + f'  <path fill="#000000" d="{"".join(bar_paths)}"/>\n'
        + "".join(f'  <circle cx="{f(x)}" cy="{f(y)}" r="{f(r)}" fill="#000000"/>\n' for x, y, r in dots)
        + f'  <path fill="#000000" d="{"".join(fringe)}"/>\n'
        + f'  <path fill="#000000" fill-rule="evenodd" d="{mono_rug}"/>\n'
        + "</svg>\n"
    )
    icon = head + bg_defs + fg_body + "</svg>\n"
    return background, foreground, mono, icon


def write(dirpath: Path, layers, full=False):
    dirpath.mkdir(parents=True, exist_ok=True)
    names = ["background.svg", "foreground.svg", "monochrome.svg", "icon.svg"]
    for name, text in zip(names, layers):
        if name == "icon.svg" and not full:
            continue
        with open(dirpath / name, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)


SAND = {"bg": "#F6EFE3", "bars": "#1F4E8C", "rug": "#B5412F", "fringe": "#B5412F", "line": "#F6EFE3"}

BASE = dict(
    pal=SAND,
    bw=5.0, bar_gap=3.2, heights=[10, 30, 18], rug_w=12.0, rug_h=22.0, space=5.0,
    arch_inset=2.0, arch_lw=1.5, arch_spring=9.0, pointed=0.0,
    fringe_n=4, fringe_w=1.6, fringe_l=3.2, fringe_inset=1.0,
)

VARIANTS = {
    "a-three-bars": dict(BASE),
    "b-two-bars": dict(BASE, bw=6.5, bar_gap=4.0, heights=[12, 28], rug_w=13.0, rug_h=23.0, space=6.0,
                       fringe_n=4, fringe_w=1.8),
    "c-terracotta": dict(BASE, pal={"bg": "#F6EFE3", "bars": "#C4643F", "rug": "#1F4E8C", "fringe": "#1F4E8C",
                                    "line": "#F6EFE3"}),
    "d-dots-diamond": dict(BASE, dots=1.4, diamond=1.6, diamond_y=14.0),
    "e-two-big": dict(BASE, bw=6.6, bar_gap=3.6, heights=[13, 32], rug_w=16.0, rug_h=25.0, space=4.6,
                      arch_inset=2.4, arch_lw=1.8, arch_spring=10.5, fringe_n=4, fringe_w=2.0, fringe_l=3.4,
                      fringe_inset=1.4),
    "f-two-asym": dict(BASE, bw=6.6, bar_gap=3.6, heights=[11, 32], heights_r=[15, 27], rug_w=16.0, rug_h=25.0,
                       space=4.6, arch_inset=2.4, arch_lw=1.8, arch_spring=10.5, fringe_n=4, fringe_w=2.0,
                       fringe_l=3.4, fringe_inset=1.4),
    "g-three-big": dict(BASE, bw=5.6, bar_gap=2.9, heights=[10, 31, 17], rug_w=15.0, rug_h=24.0, space=4.2,
                        arch_inset=2.3, arch_lw=1.7, arch_spring=10.0, fringe_n=4, fringe_w=1.9, fringe_l=3.3,
                        fringe_inset=1.3),
}


FIT = dict(BASE, bw=5.0, bar_gap=2.5, heights=[9, 30, 15], rug_w=13.0, rug_h=23.0, space=3.6,
           arch_inset=2.1, arch_lw=1.6, arch_spring=9.6, fringe_n=4, fringe_w=1.7, fringe_l=3.5, fringe_inset=1.2)
VARIANTS.update({
    "h-fit-wave": dict(FIT),
    "i-fit-ramp": dict(FIT, heights=[9, 18, 28]),
    "j-fit-wave-deepfringe": dict(FIT, pal=dict(SAND, fringe="#8C2F22")),
    "k-fit-wave-tanfringe": dict(FIT, pal=dict(SAND, fringe="#C8A273")),
})

TALL = dict(BASE, rug_w=13.0, rug_h=26.0, arch_inset=2.2, arch_lw=1.7, arch_spring=10.6, fringe_n=4,
            fringe_w=1.8, fringe_l=3.7, fringe_inset=1.2)
VARIANTS.update({
    "n-two-wide": dict(TALL, bw=6.2, bar_gap=3.2, heights=[13, 34], rug_w=14.0, space=6.5),
    "o-three-wide": dict(TALL, bw=4.8, bar_gap=2.4, heights=[11, 34, 17], space=5.2),
    "p-three-wide-asym": dict(TALL, bw=4.8, bar_gap=2.4, heights=[11, 34, 17], heights_r=[14, 29, 21], space=5.2),
})

P = VARIANTS["p-three-wide-asym"]
VARIANTS.update({
    "q-pointed-open": dict(P, arch_style="pointed_open", arch_R=0.8, apex_inset=2.4),
    "r-pointed-closed": dict(P, arch_style="pointed_closed", arch_R=0.8, apex_inset=2.4),
    "s-pointed-solid": dict(P, arch_style="pointed_solid", arch_R=0.8, arch_inset=2.6, apex_inset=3.0),
    "t-pointed-open-grad": dict(P, arch_style="pointed_open", arch_R=0.8, apex_inset=2.4,
                                pal=dict(SAND, bg2="#EDE1CC")),
})

Q = VARIANTS["q-pointed-open"]
VARIANTS.update({
    "u-bold-bars": dict(Q, bw=5.4, bar_gap=2.2, space=4.8, scale=0.95),
    "v-bold-bars-big-rug": dict(Q, bw=5.4, bar_gap=2.2, space=4.6, rug_w=14.0, rug_h=27.0, scale=0.93,
                                heights=[11, 35, 17], heights_r=[14, 30, 21]),
})

V = VARIANTS["v-bold-bars-big-rug"]
VARIANTS.update({
    "w-taller": dict(V, rug_h=28.5, heights=[11, 38, 18], heights_r=[14, 32, 23], arch_spring=11.0),
})

FINAL = "w-taller"

if __name__ == "__main__":
    for name, p in VARIANTS.items():
        write(HERE / "_var" / name, build(p), full=True)
    write(HERE, build(VARIANTS[FINAL]), full=True)
    print("ok")
