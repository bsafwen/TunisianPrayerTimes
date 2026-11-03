"""Round 3c: other ways to say "the phone goes silent", on the round 3b icon.

Usage: python -I gen3.py

Each idea keeps the silence cue on the ribbon or the floor, never on the
minaret or the mushaf, and avoids what earlier rounds ruled out (bells,
slashed or muted religious elements, crescents as sleep, no-entry discs,
pause signs).

  08 wave    The ribbon's tail starts as a sound wave and calms into the arch:
             the noise settles at the door.
  09 slider  The floor under the mushaf is a volume slider with its knob at zero.
  10 ramp    The tail is the classic volume wedge, filled only at its thin tip.
  11 bars    A voice waveform outside the arch, its bars shrinking to a dot at the door.

Shapes come from gen2.py; this file only adds the left side and the floor.
"""

import math
import sys
from pathlib import Path

from shapely.geometry import Point, Polygon, box
from shapely.ops import unary_union

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gen2  # noqa: E402
from gen2 import AXIS, GROUND, W, chain, stroke, path, svg, merged  # noqa: E402

ROOT = gen2.ROOT
FOOT = GROUND + W / 2  # bottom edge of the ground ribbon
LEG_X = 13.0  # left leg centreline


def clip_left(geom):
    return geom.intersection(box(-50, -50, AXIS, 200))


def plain_leg():
    """Left half of the arch dropping straight to the ground, flush with the ribbon's bottom."""
    return clip_left(stroke(chain((LEG_X, FOOT), *gen2.LEFT_LEG), lambda s: W, extend_end=W * 1.6))


# ---------------------------------------------------------------- 08 wave


def wave_leg():
    """A thin sound wave from the left whose swing dies away, then the full-width leg."""
    x0, x1, y0 = -20.0, 3.0, 98.0
    period, amp0 = 8.4, 7.0
    wave = []
    steps = 160
    for i in range(steps + 1):
        x = x0 + (x1 - x0) * i / steps
        k = 1 - (x - x0) / (x1 - x0)  # 1 at the start, 0 at the end
        wave.append((x, y0 - amp0 * k**1.4 * math.sin(2 * math.pi * (x - x0) / period)))
    centre = wave + chain((x1, y0), ("C", 8.5, y0, LEG_X, 96.0, LEG_X, 88.0), *gen2.LEFT_LEG)[1:]
    lengths = [0.0]
    for a, b in zip(centre, centre[1:]):
        lengths.append(lengths[-1] + math.dist(a, b))
    wave_len = lengths[len(wave) - 1]
    total = lengths[-1]

    def width(s):
        d = s * total
        if d <= wave_len:
            return 3.2 + 0.8 * d / wave_len
        return min(W, 4.0 + (W - 4.0) * ((d - wave_len) / 16.0) ** 0.9)

    leg = stroke(centre, width, extend_end=W * 1.6)
    return clip_left(unary_union([leg, Point(wave[0]).buffer(1.6, 24)]))


# ---------------------------------------------------------------- 09 slider


SLIDER_Y = GROUND - W / 2 - 1.4  # the stand's feet rest on the track


def slider():
    """Thin track across the arch floor; the knob sits at the left end: volume zero."""
    track = box(LEG_X + W / 2 - 0.5, SLIDER_Y - 1.7, 63.0 - W / 2 + 0.5, SLIDER_Y + 1.7)
    knob_c = (LEG_X + W / 2 + 5.4, SLIDER_Y - 0.6)
    knob = Point(knob_c).buffer(5.4, 64)
    gap = Point(knob_c).buffer(7.0, 64)
    return track.difference(gap), knob


def slider_stand():
    """The rahla, narrowed so its feet clear the knob."""
    cover = gen2.book()[0]
    top_y, foot_y = 72.0, SLIDER_Y - 1.4
    cx = gen2.BOOK_CX
    a = Polygon([(cx - 7.6, foot_y), (cx - 4.0, foot_y), (cx + 14.0, top_y), (cx + 10.2, top_y)])
    b = Polygon([(2 * cx - x, y) for x, y in a.exterior.coords])
    return unary_union([a, b]).difference(cover.buffer(0.01))


# ---------------------------------------------------------------- 10 ramp


def ramp():
    """The volume wedge laid along the ground, joined to the leg's foot.

    Returns (outline, fill): a hollow wedge and the small filled tip (volume low).
    """
    tip = (-20.0, FOOT)
    top = (LEG_X - W / 2 + 0.2, FOOT - 17.0)
    base = (LEG_X - W / 2 + 0.2, FOOT)
    wedge = Polygon([tip, top, base]).buffer(0.9, 16, join_style=1).buffer(-0.9, 16, join_style=1)
    inner = Polygon([tip, top, base]).buffer(-2.3, join_style=2)
    outline = wedge.difference(inner)
    level = box(-30, FOOT - 30, -11.0, FOOT + 2)
    fill = wedge.intersection(level)
    return outline, fill


# ---------------------------------------------------------------- 11 bars


def bars():
    """Voice-message bars outside the arch, shrinking toward the door."""
    cy = GROUND - 2.0
    shapes = []
    for x, h in ((-18.0, 22.0), (-10.6, 15.0), (-3.4, 9.0), (3.6, 4.6)):
        w = 4.6
        r = w / 2
        hh = max(h / 2 - r, 0)
        shapes.append(unary_union([
            box(x - r, cy - hh, x + r, cy + hh),
            Point(x, cy - hh).buffer(r, 24),
            Point(x, cy + hh).buffer(r, 24),
        ]))
    return unary_union(shapes)


# ---------------------------------------------------------------- assembly


def parts(mode):
    _, right = gen2.ribbon(with_tail=True)
    body, trim, moon = gen2.minaret()
    cover, pages, lines, stand = gen2.book()
    extra = {}
    if mode == "wave":
        left = wave_leg()
    elif mode == "slider":
        left = plain_leg()
        extra["track"], extra["knob"] = slider()
        stand = slider_stand()
    elif mode == "ramp":
        left = plain_leg()
        outline, fill = ramp()
        left = unary_union([left, outline])
        extra["ramp_fill"] = fill
    elif mode == "bars":
        left = plain_leg()
        extra["bars"] = bars()
    return dict(left=left, right=right, body=body, trim=trim, moon=moon, cover=cover,
                pages=pages, lines=lines, stand=stand, **extra)


def foreground(c, mode):
    p = parts(mode)
    out = [
        path(p["left"], c["dark"]),
        path(p["right"], c["light"]),
        path(p["body"], c["minaret"]),
        path(p["trim"], c["minaret_dark"]),
        path(p["moon"], c["moon"]),
    ]
    if "track" in p:
        out += [path(p["track"], c["track"]), path(p["knob"], c["accent"])]
    if "ramp_fill" in p:
        out.append(path(p["ramp_fill"], c["accent"]))
    if "bars" in p:
        out.append(path(p["bars"], c["dark"]))
    out += [
        path(p["stand"], c["stand"]),
        path(p["cover"], c["cover"]),
        path(p["pages"], c["pages"]),
        path(p["lines"], c["lines"]),
    ]
    return svg("".join(out))


def monochrome(mode):
    p = parts(mode)
    pieces = [p["left"], p["right"], p["body"], p["trim"], p["moon"], p["stand"]]
    pieces += [p[k] for k in ("track", "knob", "ramp_fill", "bars") if k in p]
    ink = unary_union(pieces)
    ink = ink.difference(p["cover"].buffer(1.0))
    ink = unary_union([ink, p["cover"].difference(p["pages"]), p["lines"]])
    return svg(path(ink, "#000000"))


BLUE_BG = gen2.radial_bg("#2A6DB8", "#1A4F8E")
CREAM_BG = gen2.radial_bg("#FDFAF4", "#F1E8DA")
BLUE_C = dict(gen2.BLUE_COLOURS, accent=gen2.SAND_LIGHT, track="#8FB3DF")
CREAM_C = dict(gen2.CREAM_COLOURS, accent=gen2.SAND, track="#9DB5D4")

# (mode, scale, local centre). The wave, ramp and bars reach further left,
# so the whole drawing is a little smaller and sits further right.
WIDE = (0.43, (40.0, 54.5))
NARROW = (0.5, (47.5, 54.0))
VARIANTS = {
    "08-wave-blue": ("wave", WIDE, BLUE_BG, BLUE_C),
    "09-slider-blue": ("slider", NARROW, BLUE_BG, BLUE_C),
    "10-ramp-blue": ("ramp", WIDE, BLUE_BG, BLUE_C),
    "11-bars-blue": ("bars", WIDE, BLUE_BG, BLUE_C),
}


def main():
    for slug, (mode, (scale, centre), bg, colours) in VARIANTS.items():
        gen2.SCALE, gen2.CENTRE_LOCAL = scale, centre
        d = ROOT / slug
        d.mkdir(exist_ok=True)
        fg = foreground(colours, mode)
        (d / "background.svg").write_text(bg, encoding="utf-8", newline="\n")
        (d / "foreground.svg").write_text(fg, encoding="utf-8", newline="\n")
        (d / "monochrome.svg").write_text(monochrome(mode), encoding="utf-8", newline="\n")
        (d / "icon.svg").write_text(merged(bg, fg), encoding="utf-8", newline="\n")
        print(d)


if __name__ == "__main__":
    main()
