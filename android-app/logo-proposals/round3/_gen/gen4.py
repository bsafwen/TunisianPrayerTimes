"""Round 3d: the owner's refined proposal (12-owner-refined) with the agreed fixes.

Usage: python -I gen4.py

Kept from the owner's drawing: its size and place on the canvas, the calligraphic
arch (thin at the apex, thick in the legs), the mushaf and the badge position.
Changed:

1. The minaret is Kairouan's, drawn from a photo of the real one (see minaret()).
2. A thin gap separates the mute badge from the left leg, as in 06.
3. The ground under the minaret is as thick as the legs, so the ribbon reads as one stroke.
4. The rahla's thick boards end flat on the ground band's line (no floor).
5. A blue version next to the cream one.

The owner's paths are in 108dp canvas units already, so fit() is the identity here.
"""

import re
import sys
from pathlib import Path

from shapely import affinity
from shapely.geometry import LineString, Point, Polygon, box
from shapely.ops import unary_union

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gen2  # noqa: E402
from gen2 import bezier, merged, path, radial_bg, rounded_box, svg  # noqa: E402

ROOT = gen2.ROOT
gen2.SCALE, gen2.CENTRE_LOCAL = 1.0, (54.0, 54.0)


def quad(p0, p1, p2, steps=24):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1])
            for t in (i / steps for i in range(steps + 1))]


def parse(d):
    """Absolute M/L/H/V/C/Q/Z path data → list of rings (point lists)."""
    tokens = re.findall(r"[MLHVCQZ]|-?\d*\.?\d+", d)
    rings, pts, i, cmd = [], [], 0, None
    while i < len(tokens):
        if tokens[i].isalpha():
            cmd = tokens[i]
            i += 1
            if cmd == "Z":
                rings.append(pts)
                pts = []
                continue
        num = lambda k: float(tokens[i + k])
        if cmd == "M":
            if pts:
                rings.append(pts)
            pts, i = [(num(0), num(1))], i + 2
            cmd = "L"
        elif cmd == "L":
            pts.append((num(0), num(1)))
            i += 2
        elif cmd == "H":
            pts.append((num(0), pts[-1][1]))
            i += 1
        elif cmd == "V":
            pts.append((pts[-1][0], num(0)))
            i += 1
        elif cmd == "C":
            pts += bezier(pts[-1], (num(0), num(1)), (num(2), num(3)), (num(4), num(5)))[1:]
            i += 6
        elif cmd == "Q":
            pts += quad(pts[-1], (num(0), num(1)), (num(2), num(3)))[1:]
            i += 4
    if pts:
        rings.append(pts)
    return [Polygon(r).buffer(0) for r in rings]


# ---------------------------------------------------------------- the owner's shapes

# Right half: fold at the apex, down the right leg, along the ground to the minaret.
# Fix 3: the ground band is 4.6 thick (was 3.6), like the legs; its end is rounded.
RIGHT = parse(
    "M49.58 23 C50.6 33.3 53.6 36.65 59.2 41.45 C64.7 46.2 67.75 49.4 66.45 55.1 "
    "C65.55 59.25 64.5 63.6 64.5 67.85 V70.4 Q64.5 72.2 66.8 72.2 H76.65 "
    "Q78.3 72.2 78.3 73.85 V75.15 Q78.3 76.8 76.65 76.8 H66.6 Q59.7 76.8 59.7 70.95 "
    "V66.6 C59.7 61.75 61.45 57.55 61.9 53.65 C62.45 49.3 58.9 46.3 54.8 42.65 "
    "C52.45 40.55 50.55 37.75 49.58 34.65 Z"
)[0]
LEFT = parse(
    "M49.65 23 C48.5 33.4 45.45 36.3 39.15 41.3 C33.2 46 30.4 49.6 32.65 56.5 "
    "C33.9 60.6 34.55 64.2 34.55 68.75 L39.05 68.75 C39.05 63.6 38.25 59.3 36.9 55.15 "
    "C35.15 49.8 38.7 46.8 43.6 42.75 C46.55 40.3 48.7 37.8 49.65 34.65 Z"
)[0]

_book = parse(
    "M41.0 55.65 C43.9 54.95 47.05 55.7 49.55 57.35 C52.05 55.7 55.2 54.95 58.1 55.65 "
    "L59.45 63.4 C55.75 62.9 52.25 63.75 49.55 65.5 C46.85 63.75 43.35 62.9 39.65 63.4 Z "
    "M42.0 56.85 C44.35 56.45 46.85 57.15 48.85 58.5 V63.35 C46.5 62.1 43.85 61.65 41.15 61.9 Z "
    "M50.25 58.5 C52.25 57.15 54.75 56.45 57.1 56.85 L57.95 61.9 C55.25 61.65 52.6 62.1 50.25 63.35 Z"
)
BOOK = _book[0]
PAGES = unary_union(_book[1:])
COVER = BOOK.difference(PAGES)


def text_lines(width):
    """The owner's abstract lines (never letters), two per page."""
    pts = [
        bezier((42.65, 58.5), (44.05, 58.2), (45.75, 58.55), (47.2, 59.35)),
        bezier((42.45, 60.15), (43.95, 59.9), (45.75, 60.25), (47.2, 61.0)),
        bezier((51.9, 59.35), (53.35, 58.55), (55.05, 58.2), (56.45, 58.5)),
        bezier((51.9, 61.0), (53.35, 60.25), (55.15, 59.9), (56.65, 60.15)),
    ]
    return unary_union([LineString(p).buffer(width / 2, 12) for p in pts])


# ---------------------------------------------------------------- the fixes

# Fix 1: the minaret of the Great Mosque of Kairouan, drawn from a photo of it, in
# the owner's second refinement: a slim base (about 2.3 times as tall as wide; the
# real one is 1.7) with a slight taper, big dark openings on the upper tiers, a
# crest of stone merlons flush with each tier's edge, a cornice only a little wider
# than the top tier, a small dome in the same stone, and a short finial.
M_X, M_BASE, M_W = 72.85, 72.8, 10.4


def crest(cx, y, width, count, tooth, h):
    """A row of small merlons standing on y, flush with both ends of the tier."""
    gap = (width - count * tooth) / (count - 1)
    x0 = cx - width / 2
    teeth = []
    for i in range(count):
        x = x0 + i * (tooth + gap)
        r = tooth / 2
        teeth.append(unary_union([box(x, y - h + r, x + tooth, y + 0.1),
                                  Point(x + r, y - h + r).buffer(r, 16)]))
    return unary_union(teeth)


def minaret():
    """(stone, shading, moon, openings): shading and the openings are drawn over the stone."""
    cx = M_X
    t1_top, t2_top, t3_top = 50.0, 45.9, 41.6
    w1_top, w2, w3 = M_W * 0.92, 6.5, 4.2
    t1 = Polygon([(cx - M_W / 2, M_BASE), (cx - w1_top / 2, t1_top),
                  (cx + w1_top / 2, t1_top), (cx + M_W / 2, M_BASE)])
    t2 = box(cx - w2 / 2, t2_top, cx + w2 / 2, t1_top)
    t3 = box(cx - w3 / 2, t3_top, cx + w3 / 2, t2_top)
    c1h, c2h = 1.4, 1.1
    crests = unary_union([crest(cx, t1_top, w1_top, 7, 0.9, c1h), crest(cx, t2_top, w2, 5, 0.85, c2h)])
    cornice = box(cx - w3 / 2 - 0.3, t3_top - 0.5, cx + w3 / 2 + 0.3, t3_top + 0.1)
    # A slightly pointed dome on the cornice.
    dw, dh, dy = 3.5, 2.2, t3_top - 0.5
    dome = Polygon(
        [(cx - dw / 2, dy)]
        + bezier((cx - dw / 2, dy), (cx - dw / 2, dy - dh * 0.75), (cx - dw * 0.15, dy - dh * 0.9), (cx, dy - dh))[1:]
        + bezier((cx, dy - dh), (cx + dw * 0.15, dy - dh * 0.9), (cx + dw / 2, dy - dh * 0.75), (cx + dw / 2, dy))[1:]
    )
    stem = box(cx - 0.35, dy - dh - 1.3, cx + 0.35, dy - dh + 0.2)
    moon = gen2.crescent(cx, dy - dh - 3.0, 2.1, 0.9, -0.8, 1.8)
    stone = unary_union([t1, t2, t3, crests, cornice, dome, stem])

    def arch(x, top, w, h):
        return gen2.arched_window(x, top, w, h)

    # The crest in front hides the foot of the tier above, so each tier's arches
    # sit between the crest below and the shadow line under its own crest.
    openings = unary_union(
        [box(cx - 0.65, y, cx + 0.65, y + 2.0) for y in (54.0, 58.5, 63.0)]  # tier 1 windows
        + [arch(cx + dx, t2_top + 0.45, 1.4, 2.2) for dx in (-2.05, 0.0, 2.05)]  # tier 2 arches
        + [arch(cx, t3_top + 0.4, 1.6, 2.6)]  # tier 3 opening
        + [arch(cx + dx, t3_top + 0.7, 0.55, 2.2) for dx in (-1.45, 1.45)]
    )
    # Shade behind each crest where it stands in front of the next wall, so the
    # merlons read there too, and a thin shadow line under each crest.
    shade = unary_union([
        box(cx - w2 / 2, t1_top - c1h, cx + w2 / 2, t1_top),
        box(cx - w3 / 2, t2_top - c2h, cx + w3 / 2, t2_top),
    ]).difference(crests)
    lines = unary_union([
        box(cx - w1_top / 2, t1_top - 0.05, cx + w1_top / 2, t1_top + 0.35),
        box(cx - w2 / 2, t2_top - 0.05, cx + w2 / 2, t2_top + 0.3),
    ])
    return stone, unary_union([shade, lines]), moon, openings


# Fix 2: the badge sits under the left leg with a 1dp gap. It is 1.45 left of the
# owner's first place so the rahla's feet clear the gap.
BADGE = (35.8, 73.4, 6.25)
BADGE_GAP = 1.0

# Fix 4 (owner's second refinement): thick boards, no floor; the feet are cut flat
# on the line where the ground band starts, so the ground is implied.
FEET_Y = 72.8


def stand():
    axis = 49.65
    boards = [((axis - 7.3, 63.7), (axis + 5.2, 73.6)), ((axis + 7.3, 63.7), (axis - 5.2, 73.6))]
    legs = unary_union([LineString(b).buffer(0.85, 12, cap_style=2) for b in boards])
    return legs.intersection(box(0, 0, 108, FEET_Y))


def parts():
    body, trim, moon, openings = minaret()
    disc, glyph = gen2.mute_badge(*BADGE)
    gap = Point(BADGE[:2]).buffer(BADGE[2] + BADGE_GAP, 96)
    return dict(left=LEFT.difference(gap), right=RIGHT, body=body, trim=trim, moon=moon,
                openings=openings, stand=stand().difference(gap), cover=COVER, pages=PAGES, lines=text_lines(0.7),
                disc=disc, glyph=glyph, gap=gap)


def foreground(c):
    p = parts()
    out = [
        path(p["body"], c["minaret"]),
        path(p["trim"], c["minaret_dark"]),
        path(p["openings"], c["openings"]),
        path(p["moon"], c["moon"]),
        path(p["right"], c["light"]),
        path(p["left"], c["dark"]),
        path(p["stand"], c["stand"]),
        path(p["pages"], c["pages"]),
        path(p["cover"], c["cover"]),
        path(p["lines"], c["lines"]),
        path(p["disc"].difference(p["glyph"]), c["badge"]),
    ]
    return svg("".join(out))


def monochrome():
    p = parts()
    stone = p["body"].difference(p["openings"])
    ink = unary_union([p["left"], p["right"], stone, p["moon"], p["stand"]])
    ink = ink.difference(BOOK.buffer(0.9))
    ink = unary_union([ink, p["cover"], text_lines(0.9), p["disc"].difference(p["glyph"])])
    return svg(path(ink, "#000000"))


def flat_bg(colour):
    return svg(f'<rect width="108" height="108" fill="{colour}"/>')


CREAM_C = dict(dark=gen2.NAVY, light=gen2.BLUE, minaret=gen2.SAND, minaret_dark=gen2.SAND_DARK,
               moon=gen2.SAND_DARK, cover=gen2.BLUE, pages="#FFFEFA", lines=gen2.NAVY,
               stand=gen2.SAND_DARK, badge=gen2.NAVY, openings="#6E4320")
BLUE_C = dict(gen2.BLUE_COLOURS, badge="#FFFFFF", openings="#7A4D25")
# 13 in the app's own teal (GreenPrimaryDark #004D40, GreenPrimary #00695C in Theme.kt).
TEAL_C = dict(CREAM_C, dark="#004D40", light="#00695C", cover="#00695C", lines="#004D40", badge="#004D40")

VARIANTS = {
    "13-final-cream": (flat_bg("#F5EFE5"), CREAM_C),
    "14-final-blue": (radial_bg("#2A6DB8", "#1A4F8E"), BLUE_C),
    "15-final-teal": (flat_bg("#F5EFE5"), TEAL_C),
}


def main():
    p = parts()
    minaret_ink = unary_union([p["body"], p["trim"], p["moon"]])
    print(f"minaret to right leg: {minaret_ink.distance(RIGHT.intersection(box(0, 0, 200, 70))):.2f}dp")
    print(f"rahla to badge gap: {p['stand'].distance(p['disc'].buffer(BADGE_GAP)):.2f}dp")
    for slug, (bg, colours) in VARIANTS.items():
        d = ROOT / slug
        d.mkdir(exist_ok=True)
        fg = foreground(colours)
        (d / "background.svg").write_text(bg, encoding="utf-8", newline="\n")
        (d / "foreground.svg").write_text(fg, encoding="utf-8", newline="\n")
        (d / "monochrome.svg").write_text(monochrome(), encoding="utf-8", newline="\n")
        (d / "icon.svg").write_text(merged(bg, fg), encoding="utf-8", newline="\n")
        print(d)


if __name__ == "__main__":
    main()
