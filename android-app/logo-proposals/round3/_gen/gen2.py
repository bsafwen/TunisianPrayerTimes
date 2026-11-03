"""Round 3b: the arch + minaret + mushaf icon with the owner-approved improvements.

Usage: python -I gen2.py

- The minaret is the Great Mosque of Kairouan's: three square tiers, each
  narrower than the last, rounded merlons, a small dome.
- The ribbon is one stroke: a tapered tail rises into the arch's left leg, folds
  at the apex, comes down the right leg and runs along the ground to the minaret.
- One ribbon width everywhere; soft ends; a pointed apex only where the arch is pointed.
- Sand-ochre minaret (Kairouan stone) against Sidi Bou Said blue.
- The mushaf is raised and a little larger.
- 06/07 add the silence hint: the ribbon's tail ends in a round mute badge.

Geometry is built with shapely in a local design space and baked onto the 108dp
canvas by fit(). Output paths use only absolute M/L/Z (VectorDrawable-safe).
"""

import math
from pathlib import Path

from shapely import affinity
from shapely.geometry import Point, Polygon, box
from shapely.ops import unary_union

ROOT = Path(__file__).resolve().parent.parent

SCALE = 0.5
CENTRE_LOCAL = (47.5, 54.0)
CENTRE_CANVAS = (54.0, 54.0)

W = 10.0  # ribbon width
AXIS = 38.0  # arch axis
GROUND = 97.0  # ribbon centreline along the ground


def fit(x, y):
    return (
        CENTRE_CANVAS[0] + (x - CENTRE_LOCAL[0]) * SCALE,
        CENTRE_CANVAS[1] + (y - CENTRE_LOCAL[1]) * SCALE,
    )


# ---------------------------------------------------------------- curves


def bezier(p0, p1, p2, p3, steps=48):
    out = []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        out.append((
            u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0],
            u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1],
        ))
    return out


def chain(start, *segs):
    """Polyline from a start point and segments: ('L', x, y) or ('C', x1, y1, x2, y2, x, y)."""
    pts = [start]
    for s in segs:
        if s[0] == "L":
            a, b = pts[-1], (s[1], s[2])
            for i in range(1, 25):
                t = i / 24
                pts.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
        else:
            pts += bezier(pts[-1], (s[1], s[2]), (s[3], s[4]), (s[5], s[6]))[1:]
    # Drop repeated points.
    clean = [pts[0]]
    for p in pts[1:]:
        if math.dist(p, clean[-1]) > 1e-6:
            clean.append(p)
    return clean


def stroke(pts, width_at, extend_end=0.0):
    """Band along a polyline with width width_at(s), s = 0..1 by arc length.

    extend_end continues the band straight past its last point, so a later clip
    can cut a clean mitre (used at the apex fold).
    """
    if extend_end:
        (x0, y0), (x1, y1) = pts[-2], pts[-1]
        d = math.dist(pts[-2], pts[-1])
        pts = pts + [(x1 + (x1 - x0) / d * extend_end, y1 + (y1 - y0) / d * extend_end)]
    lengths = [0.0]
    for a, b in zip(pts, pts[1:]):
        lengths.append(lengths[-1] + math.dist(a, b))
    total = lengths[-1]
    left, right = [], []
    for i, p in enumerate(pts):
        a = pts[max(i - 1, 0)]
        b = pts[min(i + 1, len(pts) - 1)]
        tx, ty = b[0] - a[0], b[1] - a[1]
        tl = math.hypot(tx, ty)
        nx, ny = -ty / tl, tx / tl
        h = width_at(lengths[i] / total) / 2
        left.append((p[0] + nx * h, p[1] + ny * h))
        right.append((p[0] - nx * h, p[1] - ny * h))
    return Polygon(left + right[::-1]).buffer(0)


def round_end(poly, centre, width):
    return unary_union([poly, Point(centre).buffer(width / 2, 64)])


# ---------------------------------------------------------------- shapes


APEX = (AXIS, 8.0)
MX = 86.0  # minaret axis
MW = 22.0  # minaret base width
LEFT_LEG = (
    ("L", 13.0, 74.0),
    ("C", 13.0, 64.0, 8.5, 59.0, 8.5, 49.0),
    ("C", 8.5, 31.0, 33.5, 28.0, *APEX),
)
LEFT_CENTRE = chain(
    (-2.0, 103.0),
    ("C", 5.0, 102.5, 13.0, 99.0, 13.0, 88.0),
    *LEFT_LEG,
)
RIGHT_CENTRE = chain(
    APEX,
    ("C", 42.5, 28.0, 67.5, 31.0, 67.5, 49.0),
    ("C", 67.5, 59.0, 63.0, 64.0, 63.0, 74.0),
    ("L", 63.0, 88.0),
    ("C", 63.0, 94.0, 66.0, GROUND, 72.0, GROUND),
    ("L", MX + MW / 2, GROUND),
)


def ribbon(with_tail=True):
    """(left half, right half). The halves meet at the apex in a clean fold."""
    if with_tail:
        def taper(s):
            return 2.4 + (W - 2.4) * min(s / 0.16, 1.0) ** 0.8
    else:
        def taper(s):
            return W
    # Without the tail, the left leg drops straight to the ground (the badge sits there).
    centre = LEFT_CENTRE if with_tail else chain((13.0, GROUND), *LEFT_LEG)
    left = stroke(centre, taper, extend_end=W * 1.6)
    right = stroke(RIGHT_CENTRE[::-1], lambda s: W, extend_end=W * 1.6)
    left = left.intersection(box(-50, -50, AXIS, 200))
    right = right.intersection(box(AXIS, -50, 200, 200))
    if with_tail:
        left = round_end(left, LEFT_CENTRE[0], 2.4)
    return left, right


def rounded_box(x0, y0, x1, y1, r):
    return box(x0 + r, y0 + r, x1 - r, y1 - r).buffer(r, 16)


def merlons(cx, y_bottom, width, count, h=4.2):
    """A row of merlons with rounded tops, standing on y_bottom."""
    gap = width / (count * 2 - 1)
    shapes = []
    x = cx - width / 2
    for _ in range(count):
        r = gap / 2
        shapes.append(unary_union([
            box(x, y_bottom - h + r, x + gap, y_bottom + 0.4),
            Point(x + r, y_bottom - h + r).buffer(r, 24),
        ]))
        x += gap * 2
    return unary_union(shapes)


def crescent(cx, cy, r, dx, dy, r2):
    return Point(cx, cy).buffer(r, 64).difference(Point(cx + dx, cy + dy).buffer(r2, 64))


def minaret():
    """Kairouan: three square tiers, rounded merlons, ribbed dome, finial and crescent."""
    # Squat like the real one: the base tier is about twice as tall as it is wide.
    h = MW / 2
    t1 = Polygon([(MX - h, GROUND - W / 2 + 0.6), (MX - h + 1.0, 50), (MX + h - 1.0, 50), (MX + h, GROUND - W / 2 + 0.6)])
    m1 = merlons(MX, 50, MW - 2.0, 3, h=4.6)
    t2 = box(MX - 7.5, 33.5, MX + 7.5, 45.8)
    m2 = merlons(MX, 33.5, 15.0, 3, h=3.9)
    t3 = box(MX - 4.5, 22.0, MX + 4.5, 30.0)
    dome = Point(MX, 22.0).buffer(4.5, 48).intersection(box(MX - 6, 15, MX + 6, 22.0))
    stem = box(MX - 0.7, 12.0, MX + 0.7, 18.0)
    ball = Point(MX, 13.6).buffer(1.6, 32)
    moon = crescent(MX, 6.8, 4.6, 2.0, -1.8, 3.9)
    windows = unary_union([
        arched_window(MX, 60, 3.4, 9.0),
        arched_window(MX, 76, 3.4, 9.0),
        arched_window(MX, 36.5, 3.0, 6.5),
    ])
    body = unary_union([t1, t2, t3]).difference(windows)
    trim = unary_union([m1, m2, dome, stem, ball])
    return body, trim, moon


def arched_window(cx, top, w, h):
    r = w / 2
    return unary_union([box(cx - r, top + r, cx + r, top + h), Point(cx, top + r).buffer(r, 24)])


BOOK_CX, BOOK_DY, BOOK_SX, BOOK_SY = AXIS, -1.0, 0.98, 1.1


def book_shape(pts):
    """Map the round-3 book outline (axis 38, middle y 67) to its new place and size."""
    return Polygon([
        (BOOK_CX + (x - 38.0) * BOOK_SX, 67.0 + BOOK_DY + (y - 67.0) * BOOK_SY) for x, y in pts
    ]).buffer(0)


def book():
    cover = book_shape(
        [(19.5, 72.5), (21.5, 59.5)]
        + bezier((21.5, 59.5), (28, 58.5), (34, 59.5), (38, 63))[1:]
        + bezier((38, 63), (42, 59.5), (48, 58.5), (54.5, 59.5))[1:]
        + [(56.5, 72.5)]
        + bezier((56.5, 72.5), (49, 71.5), (42, 72.5), (38, 75.5))[1:]
        + bezier((38, 75.5), (34, 72.5), (27, 71.5), (19.5, 72.5))[1:]
    )
    left_page = book_shape(
        [(22, 70.6), (23.6, 58.2)]
        + bezier((23.6, 58.2), (29, 57.4), (34.5, 58.4), (37.4, 61.6))[1:]
        + [(37.4, 73.2)]
        + bezier((37.4, 73.2), (34.5, 70.6), (28.5, 69.8), (22, 70.6))[1:]
    )
    right_page = affinity.scale(left_page, xfact=-1, yfact=1, origin=(BOOK_CX, 0))
    lines = []
    for y in (61.6, 64.6, 67.6):
        top = bezier((26, y), (30, y - 0.6), (33, y - 0.2), (35.2, y + 1.0))
        bottom = bezier((35.2, y + 2.0), (33, y + 0.8), (30, y + 0.4), (26, y + 1.0))
        line = book_shape(top + bottom)
        lines += [line, affinity.scale(line, xfact=-1, yfact=1, origin=(BOOK_CX, 0))]
    # Rahla: two crossed boards from under the book to the ground line.
    top_y, foot_y = 72.0, GROUND - W / 2
    a = Polygon([(BOOK_CX - 13.0, foot_y), (BOOK_CX - 9.2, foot_y), (BOOK_CX + 14.0, top_y), (BOOK_CX + 10.2, top_y)])
    b = affinity.scale(a, xfact=-1, yfact=1, origin=(BOOK_CX, 0))
    stand = unary_union([a, b]).difference(cover.buffer(0.01))
    return cover, unary_union([left_page, right_page]), unary_union(lines), stand


def mute_badge(cx, cy, r):
    """Round badge with a speaker and a small x: the phone's own 'muted' sign."""
    disc = Point(cx, cy).buffer(r, 96)
    s = r / 8.6
    body = box(cx - 6.2 * s, cy - 2.4 * s, cx - 3.4 * s, cy + 2.4 * s)
    cone = Polygon([(cx - 3.6 * s, cy - 2.4 * s), (cx + 0.6 * s, cy - 5.6 * s),
                    (cx + 0.6 * s, cy + 5.6 * s), (cx - 3.6 * s, cy + 2.4 * s)])
    speaker = unary_union([body, cone]).buffer(0.5 * s, 16).buffer(-0.5 * s, 16)
    xc, yc, k, t = cx + 4.6 * s, cy, 2.3 * s, 0.75 * s
    bar = Polygon([(-k, -k + t), (-k + t, -k), (k, k - t), (k - t, k)])
    cross = unary_union([
        affinity.translate(bar, xc, yc),
        affinity.translate(affinity.scale(bar, xfact=-1, origin=(0, 0)), xc, yc),
    ]).buffer(0.25 * s, 8)
    glyph = unary_union([speaker, cross])
    return disc, glyph


BADGE = (12.0, 96.5, 13.0)  # at the foot of the left leg


# ---------------------------------------------------------------- output


def n(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def ring(coords):
    pts = [fit(x, y) for x, y in coords][:-1]
    return "M" + "L".join(f"{n(x)} {n(y)}" for x, y in pts) + "Z"


def d_of(geom):
    geom = geom.simplify(0.04, preserve_topology=True)
    polys = [geom] if geom.geom_type == "Polygon" else list(geom.geoms)
    out = []
    for p in polys:
        if p.is_empty or p.geom_type != "Polygon":
            continue
        out.append(ring(p.exterior.coords))
        out += [ring(i.coords) for i in p.interiors]
    return "".join(out)


def path(geom, fill):
    return f'<path fill="{fill}" fill-rule="evenodd" d="{d_of(geom)}"/>'


def svg(body):
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">'
        + body + "</svg>"
    )


def radial_bg(inner, outer):
    return svg(
        '<defs><radialGradient id="g" gradientUnits="userSpaceOnUse" cx="54" cy="44" r="70">'
        f'<stop offset="0" stop-color="{inner}"/><stop offset="1" stop-color="{outer}"/>'
        '</radialGradient></defs><rect width="108" height="108" fill="url(#g)"/>'
    )


def parts(silent):
    left, right = ribbon(with_tail=not silent)
    body, trim, moon = minaret()
    cover, pages, lines, stand = book()
    badge = mute_badge(*BADGE) if silent else None
    if silent:
        # The tail ends in the badge, with a thin gap around the disc.
        left = left.difference(Point(BADGE[0], BADGE[1]).buffer(BADGE[2] + 1.6, 96))
    return dict(left=left, right=right, body=body, trim=trim, moon=moon,
                cover=cover, pages=pages, lines=lines, stand=stand, badge=badge)


def foreground(c, silent):
    p = parts(silent)
    out = [
        path(p["left"], c["dark"]),
        path(p["right"], c["light"]),
        path(p["body"], c["minaret"]),
        path(p["trim"], c["minaret_dark"]),
        path(p["moon"], c["moon"]),
        path(p["stand"], c["stand"]),
        path(p["cover"], c["cover"]),
        path(p["pages"], c["pages"]),
        path(p["lines"], c["lines"]),
    ]
    if silent:
        disc, glyph = p["badge"]
        out += [path(disc, c["badge"]), path(glyph, c["badge_glyph"])]
    return svg("".join(out))


def monochrome(silent):
    p = parts(silent)
    ink = unary_union([p["left"], p["right"], p["body"], p["trim"], p["moon"], p["stand"]])
    # The book as an outline: cover minus pages, lines kept; a hairline gap from the stand.
    ink = ink.difference(p["cover"].buffer(1.0))
    ink = unary_union([ink, p["cover"].difference(p["pages"]), p["lines"]])
    if silent:
        disc, glyph = p["badge"]
        ink = unary_union([ink, disc.difference(glyph)])
    return svg(path(ink, "#000000"))


def merged(bg_svg, fg_svg):
    strip = lambda s: s.split(">", 1)[1].rsplit("</svg>", 1)[0]
    return svg(strip(bg_svg) + strip(fg_svg))


BLUE = "#1F5FA8"
NAVY = "#16365F"
CREAM = "#FBF6EE"
SAND = "#C98E50"
SAND_DARK = "#A46A33"
SAND_LIGHT = "#E6B97F"
SAND_LIGHT_DARK = "#C48A4E"

CREAM_COLOURS = dict(dark=NAVY, light=BLUE, minaret=SAND, minaret_dark=SAND_DARK, moon=SAND_DARK,
                     cover=BLUE, pages="#FFFFFF", lines=NAVY, stand=SAND_DARK,
                     badge=NAVY, badge_glyph="#FFFFFF")
BLUE_COLOURS = dict(dark="#D3E2F4", light="#FFFFFF", minaret=SAND_LIGHT, minaret_dark=SAND_LIGHT_DARK,
                    moon="#FFFFFF", cover="#0F2F57", pages=CREAM, lines=BLUE, stand=SAND_LIGHT_DARK,
                    badge="#FFFFFF", badge_glyph=NAVY)

VARIANTS = {
    "04-kairouan-cream": (radial_bg("#FDFAF4", "#F1E8DA"), CREAM_COLOURS, False),
    "05-kairouan-blue": (radial_bg("#2A6DB8", "#1A4F8E"), BLUE_COLOURS, False),
    "06-kairouan-silent-cream": (radial_bg("#FDFAF4", "#F1E8DA"), CREAM_COLOURS, True),
    "07-kairouan-silent-blue": (radial_bg("#2A6DB8", "#1A4F8E"), BLUE_COLOURS, True),
}


def main():
    for slug, (bg, colours, silent) in VARIANTS.items():
        d = ROOT / slug
        d.mkdir(exist_ok=True)
        fg = foreground(colours, silent)
        (d / "background.svg").write_text(bg, encoding="utf-8", newline="\n")
        (d / "foreground.svg").write_text(fg, encoding="utf-8", newline="\n")
        (d / "monochrome.svg").write_text(monochrome(silent), encoding="utf-8", newline="\n")
        (d / "icon.svg").write_text(merged(bg, fg), encoding="utf-8", newline="\n")
        print(d)


if __name__ == "__main__":
    main()
