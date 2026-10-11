"""Generate «فسحة الهدوء» The Quiet Gap (revision 2).

Sound bars die down into a dotted quiet stretch; a Kairouan prayer rug stands in it.
Run: python -I gen.py   (writes the final layers here and every variant under _var/)
"""

from pathlib import Path

HERE = Path(__file__).parent
CX = CY = 54.0


def f(v: float) -> str:
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def rrect(x, y, w, h, r):
    """Rounded rectangle as a path, clockwise."""
    r = min(r, w / 2, h / 2)
    top = f"H{f(x + w - r)}" if w - 2 * r > 0.01 else ""
    bot = f"H{f(x + r)}" if w - 2 * r > 0.01 else ""
    side_r = f"V{f(y + h - r)}" if h - 2 * r > 0.01 else ""
    side_l = f"V{f(y + r)}" if h - 2 * r > 0.01 else ""
    return (
        f"M{f(x + r)} {f(y)}{top}A{f(r)} {f(r)} 0 0 1 {f(x + w)} {f(y + r)}"
        f"{side_r}A{f(r)} {f(r)} 0 0 1 {f(x + w - r)} {f(y + h)}"
        f"{bot}A{f(r)} {f(r)} 0 0 1 {f(x)} {f(y + h - r)}"
        f"{side_l}A{f(r)} {f(r)} 0 0 1 {f(x + r)} {f(y)}Z"
    )


def circle_path(cx, cy, r):
    return f"M{f(cx - r)} {f(cy)}A{f(r)} {f(r)} 0 1 0 {f(cx + r)} {f(cy)}A{f(r)} {f(r)} 0 1 0 {f(cx - r)} {f(cy)}Z"


def diamond(cx, cy, hw, hh):
    return f"M{f(cx)} {f(cy - hh)}L{f(cx + hw)} {f(cy)}L{f(cx)} {f(cy + hh)}L{f(cx - hw)} {f(cy)}Z"


def lozenge(cx, cy, hw, hh, ring, core):
    """Kairouan lozenge: a diamond ring with a solid diamond core (evenodd, 3 subpaths)."""
    k = hh / hw
    t = ring * (1 + k * k) ** 0.5 / k  # horizontal inset that gives a ring of even thickness
    out = diamond(cx, cy, hw, hh)
    inn = diamond(cx, cy, hw - t, (hw - t) * k)
    paths = [out, inn]
    if core:
        paths.append(diamond(cx, cy, core, core * k))
    return "".join(paths)


def build(p):
    pal = p["pal"]
    layers = []  # (colour, d, evenodd)

    # sound: bars and quiet dots, listed for the right side (offset from centre), mirrored left
    for bar in p["bars"]:
        off, h, w, col = bar[:4]
        h_right = bar[4] if len(bar) > 4 else h
        for side, hh in ((-1, h), (1, h_right)):
            c = CX + side * off
            layers.append((pal[col], rrect(c - w / 2, CY - hh / 2, w, hh, w / 2), False))
    for off, r, col in p.get("dots", []):
        for side in (-1, 1):
            layers.append((pal[col], circle_path(CX + side * off, CY, r), False))
    for x0, x1, t, col in p.get("lines", []):
        for side in (-1, 1):
            a, b = sorted((CX + side * x0, CX + side * x1))
            layers.append((pal[col], rrect(a, CY - t / 2, b - a, t, t / 2), False))

    # rug: fringe (rounded tassels), body, lozenge
    rw, rh = p["rug_w"], p["rug_h"]
    rx, ry = CX - rw / 2, CY - rh / 2 + p.get("rug_dy", 0)
    n, tw, tl, ins = p["fringe_n"], p["fringe_w"], p["fringe_l"], p["fringe_inset"]
    step = (rw - 2 * ins - tw) / (n - 1)
    tassels = []
    for i in range(n):
        tx = rx + ins + i * step
        tassels.append(rrect(tx, ry - tl, tw, tl + 1.5, tw / 2))
        tassels.append(rrect(tx, ry + rh - 1.5, tw, tl + 1.5, tw / 2))
    fringe = "".join(tassels)
    body = rrect(rx, ry, rw, rh, p.get("rug_r", 2.0))
    lz = lozenge(CX, ry + rh / 2 + p.get("loz_dy", 0), p["loz_w"] / 2, p["loz_h"] / 2, p["loz_ring"], p.get("loz_core", 0))
    if p.get("border"):  # thin cream frame inside the rug (evenodd ring before the lozenge)
        bi, bt = p["border"]
        br = max(0.6, p.get("rug_r", 2.0) - bi)
        lz = (rrect(rx + bi, ry + bi, rw - 2 * bi, rh - 2 * bi, br)
              + rrect(rx + bi + bt, ry + bi + bt, rw - 2 * (bi + bt), rh - 2 * (bi + bt), max(0.3, br - bt)) + lz)
    layers.append((pal["fringe"], fringe, False))
    layers.append((pal["rug"], body, False))
    layers.append((pal["loz"], lz, True))

    head = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">\n'
    bg_rect = f'  <rect x="0" y="0" width="108" height="108" fill="{pal["bg"]}"/>\n'
    background = head + bg_rect + "</svg>\n"

    def path(col, d, eo):
        rule = ' fill-rule="evenodd"' if eo else ""
        return f'  <path fill="{col}"{rule} d="{d}"/>\n'

    # merge consecutive same-colour, non-evenodd shapes into one path
    merged = []
    for col, d, eo in layers:
        if merged and merged[-1][0] == col and not eo and not merged[-1][2]:
            merged[-1] = (col, merged[-1][1] + d, False)
        else:
            merged.append((col, d, eo))
    fg_body = "".join(path(*m) for m in merged)
    foreground = head + fg_body + "</svg>\n"

    sound = "".join(d for col, d, eo in layers[:-3])
    mono = (
        head
        + path("#000000", sound, False)
        + path("#000000", fringe, False)
        + path("#000000", body + lz, True)
        + "</svg>\n"
    )
    icon = head + bg_rect + fg_body + "</svg>\n"
    return background, foreground, mono, icon


def write(dirpath: Path, layers):
    dirpath.mkdir(parents=True, exist_ok=True)
    for name, text in zip(["background.svg", "foreground.svg", "monochrome.svg", "icon.svg"], layers):
        with open(dirpath / name, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)


# palettes: the rug is the only saturated colour; the bars fade toward the quiet stretch
PAL = {
    "bg": "#F6EFE3", "rug": "#B5412F", "fringe": "#B5412F", "loz": "#F6EFE3",
    "b1": "#3E5F88", "b2": "#7C93B2", "b3": "#AEBCCE",
}

RUG = dict(rug_w=16.0, rug_h=24.0, rug_r=2.0, fringe_n=5, fringe_w=2.0, fringe_l=3.0, fringe_inset=1.4,
           loz_w=8.0, loz_h=11.0, loz_ring=1.3, loz_core=1.5)

VARIANTS = {
    # one quiet dot per side, two bars decaying toward it
    "r2a-one-dot": dict(RUG, pal=PAL, bars=[(28.0, 28.0, 5.5, "b1"), (19.6, 14.0, 5.5, "b2")],
                        dots=[(12.4, 1.7, "b3")]),
    # two small dots per side
    "r2b-two-dots": dict(RUG, pal=PAL, bars=[(28.0, 28.0, 5.5, "b1"), (19.6, 14.0, 5.5, "b2")],
                         dots=[(10.9, 1.25, "b3"), (14.4, 1.25, "b3")]),
    # a short thin flat line per side
    "r2c-flat-line": dict(RUG, pal=PAL, bars=[(28.0, 28.0, 5.5, "b1"), (19.6, 14.0, 5.5, "b2")],
                          lines=[(10.0, 15.6, 1.8, "b3")]),
    # WhatsApp rhythm: same pitch, the last bar shrinks to a full-width dot
    "r2d-pitch-dot": dict(RUG, pal=PAL, rug_w=15.0, bars=[(29.0, 26.0, 5.0, "b1"), (21.0, 14.0, 5.0, "b2"),
                                                          (13.0, 5.0, 5.0, "b3")]),
}

VARIANTS.update({
    # each side is a spoken phrase (hump) that dies down into two quiet dots
    "r2e-hump-2dots": dict(RUG, pal=PAL, rug_w=14.0, rug_h=26.0, fringe_n=4, fringe_inset=1.3, loz_w=7.6, loz_h=10.6,
                           bars=[(16.9, 8.0, 4.2, "b2"), (23.1, 28.0, 4.2, "b1"), (29.3, 16.0, 4.2, "b1")],
                           dots=[(9.9, 1.15, "b3"), (12.9, 1.15, "b3")]),
    "r2f-hump-asym": dict(RUG, pal=PAL, rug_w=14.0, rug_h=26.0, fringe_n=4, fringe_inset=1.3, loz_w=7.6, loz_h=10.6,
                          bars=[(16.9, 7.0, 4.2, "b2", 10.0), (23.1, 29.0, 4.2, "b1", 24.0),
                                (29.3, 15.0, 4.2, "b1", 18.0)],
                          dots=[(9.9, 1.15, "b3"), (12.9, 1.15, "b3")]),
    "r2g-v-bigrug": dict(RUG, pal=PAL, rug_h=30.0, bars=[(19.8, 14.0, 5.2, "b2"), (27.6, 30.0, 5.2, "b1")],
                         dots=[(11.1, 1.3, "b3"), (14.7, 1.3, "b3")]),
    "r2h-hump-1dot": dict(RUG, pal=PAL, rug_h=28.0,
                          bars=[(17.6, 9.0, 4.4, "b2"), (23.8, 28.0, 4.4, "b1"), (30.0, 15.0, 4.4, "b1")],
                          dots=[(12.0, 1.5, "b3")]),
})

PAL3 = dict(PAL, b3="#9DAEC4")
TALL = dict(RUG, pal=PAL3, rug_w=14.0, rug_h=30.0, fringe_n=4, fringe_w=2.0, fringe_l=3.2, fringe_inset=1.3,
            loz_w=8.0, loz_h=11.6, loz_ring=1.35, loz_core=1.6)
HUMP = [(17.6, 8.0, 4.0, "b2", 10.0), (23.6, 34.0, 4.0, "b1", 28.0), (29.6, 17.0, 4.0, "b1", 21.0)]
VARIANTS.update({
    "r3a-tall-2dots": dict(TALL, bars=HUMP, dots=[(9.6, 1.3, "b3"), (13.3, 1.3, "b3")]),
    "r3b-tall-dash": dict(TALL, bars=HUMP, lines=[(9.2, 14.4, 2.0, "b3")]),
    "r3c-tall-1dot": dict(TALL, rug_w=15.0, bars=[(17.6, 9.0, 4.2, "b2", 11.0), (23.8, 34.0, 4.2, "b1", 28.0),
                                                 (30.0, 17.0, 4.2, "b1", 20.0)],
                          dots=[(11.4, 1.6, "b3")]),
})

# decrescendo: two thick bars, then dots shrinking toward the rug
DEC = dict(RUG, pal=PAL3, rug_w=15.0, rug_h=24.0, fringe_n=4, fringe_w=2.0, fringe_l=3.0, fringe_inset=1.3,
           loz_w=8.0, loz_h=11.0, loz_ring=1.35, loz_core=1.6)
VARIANTS.update({
    "r4a-decrescendo": dict(DEC, bars=[(28.0, 28.0, 5.0, "b1"), (21.6, 15.0, 5.0, "b2")],
                            dots=[(15.1, 2.5, "b2"), (10.3, 1.3, "b3")]),
    "r4b-decrescendo-asym": dict(DEC, bars=[(28.0, 28.0, 5.0, "b1", 23.0), (21.6, 13.0, 5.0, "b2", 17.0)],
                                 dots=[(15.1, 2.5, "b2"), (10.3, 1.3, "b3")]),
    "r4c-decrescendo-tallrug": dict(DEC, rug_h=28.0, bars=[(28.0, 28.0, 5.0, "b1", 23.0), (21.6, 13.0, 5.0, "b2", 17.0)],
                                    dots=[(15.1, 2.5, "b2"), (10.3, 1.3, "b3")]),
})

# refine: rug no taller than the loudest bar, dots fade out too
PAL5 = dict(PAL3, b4="#B9C5D4")
ASYM = [(28.0, 28.0, 5.0, "b1", 23.0), (21.6, 13.0, 5.0, "b2", 17.0)]
VARIANTS.update({
    "r5a-shortrug": dict(DEC, pal=PAL5, rug_h=22.0, loz_h=10.4, bars=ASYM,
                         dots=[(15.1, 2.4, "b2"), (10.3, 1.3, "b3")]),
    "r5b-fading-dots": dict(DEC, pal=PAL5, rug_h=22.0, loz_h=10.4, bars=ASYM,
                            dots=[(15.1, 2.4, "b3"), (10.3, 1.35, "b4")]),
    "r5c-wide-rug": dict(DEC, pal=PAL5, rug_w=16.0, rug_h=23.0, fringe_n=5, fringe_w=1.9, fringe_inset=1.4,
                         loz_h=10.6, bars=[(28.3, 28.0, 5.0, "b1", 23.0), (21.9, 13.0, 5.0, "b2", 17.0)],
                         dots=[(15.6, 2.3, "b3"), (11.0, 1.3, "b4")]),
})

# fine fringe (a soft band, not pins), optional cream frame, lighter outer bars
PAL6 = dict(PAL5, b1="#46658D")
R6 = dict(VARIANTS["r5b-fading-dots"], pal=PAL6, fringe_n=6, fringe_w=1.3, fringe_l=3.0, fringe_inset=1.2)
VARIANTS.update({
    "r6a-fine-fringe": dict(R6),
    "r6b-fine-fringe-frame": dict(R6, border=(1.6, 0.9), loz_w=7.0, loz_h=9.4, loz_ring=1.2, loz_core=1.4),
})

FINAL = "r6b-fine-fringe-frame"

if __name__ == "__main__":
    for name, p in VARIANTS.items():
        write(HERE / "_var" / name, build(p))
    write(HERE, build(VARIANTS[FINAL]))
    print("ok")
