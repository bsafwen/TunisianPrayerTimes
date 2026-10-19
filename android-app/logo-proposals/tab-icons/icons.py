"""Tab icon family on a 24-unit grid: every icon in an outline and a filled state.

Shapes are built with shapely (strokes buffered to 1.7 units, round caps and joins) and
written as fill-only paths with even-odd holes, so one VectorDrawable path per state
survives Compose's SrcIn tint.
"""
import json
import math
from pathlib import Path

from shapely.geometry import LineString, Point, Polygon
from shapely.ops import unary_union

HERE = Path(__file__).parent
W = 1.7  # stroke width of the family


def bez(p0, p1, p2, p3, n=28):
    pts = []
    for i in range(n + 1):
        t = i / n
        a, b, c, d = (1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t * t, t ** 3
        pts.append((a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]))
    return pts


def chain(*parts):
    pts = []
    for p in parts:
        pts.extend(p if not pts else p[1:])
    return pts


def stroke(pts, w=W):
    return LineString(pts).buffer(w / 2, quad_segs=12, cap_style="round", join_style="round")


def ring(pts, w=W):
    return stroke(list(pts) + [pts[0], pts[1]], w)


def disk(c, r):
    return Point(c).buffer(r, quad_segs=16)


def circle_pts(c, r, n=96):
    return [(c[0] + r * math.cos(2 * math.pi * i / n), c[1] + r * math.sin(2 * math.pi * i / n)) for i in range(n)]


def mirror(pts):
    return [(24 - x, y) for x, y in pts]


# ---------------------------------------------------------------- Quran: mushaf on a stand
def quran():
    left = chain(bez((12, 5.5), (9.5, 4), (6, 3.8), (2.5, 4.7)), [(2.5, 4.7), (2.5, 13)],
                 bez((2.5, 13), (6, 12.1), (9.5, 12.3), (12, 13.8)))
    right = mirror(left)
    legs = [stroke([(5, 21), (18.5, 12.6)]), stroke([(19, 21), (5.5, 12.6)])]
    spine = [(12, 5.5), (12, 13.8)]
    outline = unary_union([stroke(left), stroke(right), stroke(spine)] + legs)
    pages = unary_union([Polygon(left), Polygon(right), stroke(left), stroke(right)])
    gap = LineString([(12, 4.0), (12, 14.6)]).buffer(0.5, cap_style="flat")
    stand = unary_union(legs).buffer(0.55)
    filled = unary_union([pages.difference(gap).difference(stand)] + legs)
    return outline, filled


# ---------------------------------------------------------------- Adhkar: misbaha
def misbaha():
    from shapely import affinity
    c, r = (12, 8.9), 6.5
    angles = [0, 36, 72, 108, 144, -36, -72, -108, -144]
    beads = [(c[0] + r * math.sin(math.radians(a)), c[1] - r * math.cos(math.radians(a))) for a in angles]
    loop = stroke(circle_pts(c, r) + circle_pts(c, r)[:2], 1.0)
    neck = stroke([(12, c[1] + r), (12, 17.2)], 1.0)
    imam = affinity.scale(Point(12, 17.3).buffer(1, quad_segs=16), 1.15, 1.55)
    tassel = Polygon([(12, 18.4), (13.7, 21.5), (10.3, 21.5)]).buffer(0.45, quad_segs=8)
    filled = unary_union([loop, neck, imam, tassel] + [disk(b, 1.65) for b in beads])
    return filled, filled


# ---------------------------------------------------------------- Adhkar alternative: the current booklet
def booklet():
    left = chain(bez((12, 5.5), (9, 3.5), (5.5, 3.5), (2, 4.5)), [(2, 4.5), (2, 19)],
                 bez((2, 19), (5.5, 18), (9, 18), (12, 20)))
    right = mirror(left)
    lines = [[(5, 8), (9, 8.5)], [(5, 11.5), (9, 12)], [(15, 8.5), (19, 8)], [(15, 12), (19, 11.5)]]
    outline = unary_union([stroke(left), stroke(right), stroke([(12, 5.5), (12, 20)])] + [stroke(l) for l in lines])
    pages = unary_union([Polygon(left), Polygon(right), stroke(left), stroke(right)])
    cut = unary_union([LineString([(12, 4.2), (12, 21)]).buffer(0.5, cap_style="flat")]
                      + [LineString(l).buffer(0.6, cap_style="round") for l in lines])
    filled = pages.difference(cut)
    return outline, filled


# ---------------------------------------------------------------- Alarms: alarm clock
def alarm():
    c, r = (12, 13.2), 7.3
    face = ring(circle_pts(c, r))
    hands = [(12, 9.4), (12, 13.2), (14.7, 14.8)]
    bells = [stroke(bez((3.4, 7.3), (4.2, 5.8), (5.5, 4.6), (7.0, 3.8))),
             stroke(mirror(bez((3.4, 7.3), (4.2, 5.8), (5.5, 4.6), (7.0, 3.8))))]
    feet = [stroke([(7.4, 19.1), (6.0, 20.6)]), stroke([(16.6, 19.1), (18.0, 20.6)])]
    outline = unary_union([face, stroke(hands)] + bells + feet)
    filled = unary_union([disk(c, r + W / 2).difference(stroke(hands, 1.5))] + bells + feet)
    return outline, filled


# ---------------------------------------------------------------- Qibla: the Kaaba
def kaaba():
    top, rgt, bot, lft = (12, 3.4), (20.2, 6.5), (12, 20.6), (3.8, 6.5)
    mid = (12, 9.6)
    hexa = [top, rgt, (20.2, 17.5), bot, (3.8, 17.5), lft]
    band = [(3.8, 9.9), (12, 13.0), (20.2, 9.9)]
    door = [(6.6, 13.2), (9.4, 14.25), (9.4, 17.6), (6.6, 16.55)]
    outline = unary_union([ring(hexa), stroke([lft, mid, rgt]), stroke([mid, bot]), stroke(band, 1.5),
                           Polygon(door).buffer(0.2)])
    body = unary_union([Polygon(hexa), ring(hexa)])
    cut = unary_union([LineString([lft, mid, rgt]).buffer(0.42), LineString([mid, bot]).buffer(0.42),
                       LineString([(2, 9.22), (12, 13.0 - 0.0), (22, 9.22)]).buffer(0.8, cap_style="flat")])
    filled = body.difference(cut).difference(Polygon(door).buffer(0.1))
    return outline, filled


# ---------------------------------------------------------------- Qibla alternative: the current arrow
def arrow():
    pts = [(12, 2.9), (19.2, 20.3), (12, 16.6), (4.8, 20.3)]
    outline = ring(pts)
    filled = unary_union([Polygon(pts), ring(pts)])
    return outline, filled


# ---------------------------------------------------------------- Prayer: sujud pictogram
def sujud():
    head = disk((4.9, 16.9), 2.3)
    back = chain(bez((7.0, 15.9), (9.3, 13.0), (12.3, 9.6), (15.3, 9.2)),
                 bez((15.3, 9.2), (17.6, 8.9), (18.1, 11.5), (17.5, 14.0)), [(17.5, 14.0), (16.9, 18.3)],
                 [(16.9, 18.3), (21.2, 18.5)])
    body = stroke(back, 3.4)
    arm = stroke([(9.6, 14.2), (10.6, 17.4), (8.6, 18.9)], 1.9)
    filled = unary_union([head, body, arm])
    inner = filled.buffer(-W)
    outline = filled.difference(inner)
    return outline, filled


# ---------------------------------------------------------------- Prayer alternative: prayer rug
def rug():
    box = [(5.2, 2.9), (18.8, 2.9), (18.8, 18.6), (5.2, 18.6)]
    arch = chain([(8.4, 16.0), (8.4, 10.6)], bez((8.4, 10.6), (8.4, 7.9), (10.6, 6.2), (12, 5.4)),
                 bez((12, 5.4), (13.4, 6.2), (15.6, 7.9), (15.6, 10.6)), [(15.6, 10.6), (15.6, 16.0)])
    fringe = [stroke([(x, 18.6), (x, 21.2)], 1.5) for x in (7.2, 10.4, 13.6, 16.8)]
    outline = unary_union([ring(box), stroke(arch + [arch[0]])] + fringe)
    body = Polygon([(4.35, 2.05), (19.65, 2.05), (19.65, 19.45), (4.35, 19.45)])
    filled = unary_union([body.difference(stroke(arch + [arch[0]], 1.4))] + fringe)
    return outline, filled


def path_data(geom, tol=0.02):
    geom = geom.simplify(tol)
    polys = getattr(geom, "geoms", [geom])
    out = []
    for p in polys:
        for r in [p.exterior, *p.interiors]:
            cs = list(r.coords)[:-1]
            out.append("M" + "L".join(f"{x:.2f},{y:.2f}".replace(".00", "") for x, y in cs) + "Z")
    return "".join(out)


ICONS = {"quran": quran, "misbaha": misbaha, "booklet": booklet, "alarm": alarm, "kaaba": kaaba,
         "arrow": arrow, "sujud": sujud, "rug": rug}

if __name__ == "__main__":
    data = {}
    for name, fn in ICONS.items():
        o, f = fn()
        data[name] = {"outline": path_data(o), "filled": path_data(f)}
    (HERE / "icons.json").write_text(json.dumps(data, indent=1), encoding="utf-8")
    print({k: (len(v["outline"]), len(v["filled"])) for k, v in data.items()})
