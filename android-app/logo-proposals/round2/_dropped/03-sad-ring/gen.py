"""Generator for 03-sad-ring «حلقة الصمت».

One calligraphic stroke: the flat Maghrebi loop of ص is the bar of the
Do Not Disturb sign, the tooth at its left end rises and turns, and the tail
sweeps round as an open ring that tapers to a point.

Usage: python -I gen.py [out_dir] [--ink #hex] [--accent #hex] [--<param> value]
Standard library only. Writes background.svg, foreground.svg, monochrome.svg
and icon.svg into out_dir (default: this folder). Any numeric key of P can be
overridden, e.g. --w_min 3.0 --end -120.

Variants in _var/:
  python -I gen.py _var/navy --w_min 3.4 --w_max 6.4
  python -I gen.py _var/contrast --w_min 3.0 --w_max 7.2
  python -I gen.py _var/gold --accent #C9A13B
  python -I gen.py _var/sbs --ink #1A4F8E
"""

import math
import sys
from pathlib import Path

CX, CY = 54.0, 54.0

# ---------------------------------------------------------------- parameters
P = {
    "ink": "#1C2A47",
    "accent": None,          # colour for the tooth, or None
    "paper_top": "#FBF7EF",
    "paper_bottom": "#EFE6D4",
    "R": 23.0,               # ring centre-line radius
    "w_min": 3.25,           # thinnest ring stroke
    "w_max": 6.8,            # thickest ring stroke
    "phi_thick": 55.0,       # where the ring is heaviest (deg, screen, 0 = right, 90 = down)
    "join": 165.0,           # where the tooth stroke meets the ring (left)
    "end": -130.0,           # where the tail ends (upper left)
    "taper": 85.0,           # degrees of arc over which the tail thins to a point
    "dx": -0.8,              # optical nudge: the heavy lower-right swell and the
    "dy": -0.6,              # open upper-left pull the weight down and right
    "sh1": 3.4,             # shoulder handle at the top of the tooth
    "sh2": 6.0,              # handle where the sweep meets the ring
}


# ---------------------------------------------------------------- geometry helpers
def cubic(p0, p1, p2, p3, t):
    u = 1 - t
    return (
        u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
        u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1],
    )


def ring_pt(phi, r=None):
    r = P["R"] if r is None else r
    a = math.radians(phi)
    return (CX + r * math.cos(a), CY + r * math.sin(a))


def add(a, b, k=1.0):
    return (a[0] + k * b[0], a[1] + k * b[1])


def unit(v):
    n = math.hypot(*v)
    return (v[0] / n, v[1] / n)


def smooth(x):
    x = min(1.0, max(0.0, x))
    return x * x * (3 - 2 * x)


# ---------------------------------------------------------------- centre line
def centreline():
    """List of (point, width, tag) along the stroke, from inside the loop's
    bottom-left to the tip of the tail."""
    pts = []

    # 1. the baseline under the loop turns up into the tooth; at the top the
    #    pen turns left over a round shoulder and sweeps down into the ring
    q = ring_pt(P["join"])
    ja = math.radians(P["join"])
    tq = (math.sin(ja), -math.cos(ja))  # ring direction for decreasing phi
    peak = (39.7, 46.9)
    segs = [
        ((52.0, 57.55), (45.2, 57.55), (42.5, 57.0), (42.3, 53.0)),
        ((42.3, 53.0), (42.15, 49.4), (41.5, 46.9), peak),
        (peak, add(peak, (-1, 0.05), P["sh1"]), add(q, tq, -P["sh2"]), q),
    ]
    w_q = ring_w(P["join"])
    w_peak = 2.9
    widths = [
        lambda t: 4.3 - 0.6 * smooth((t - 0.3) / 0.7),
        lambda t: 3.7 + (w_peak - 3.7) * smooth(t),
        lambda t: w_peak + (w_q - w_peak) * smooth(t),
    ]
    tags = ["base", "tooth", "join"]
    n = 200
    for (s0, c1, c2, e), wf, tag in zip(segs, widths, tags):
        for i in range(n):
            t = i / n
            pts.append((cubic(s0, c1, c2, e, t), wf(t), tag))

    # 3. ring, decreasing phi (counter-clockwise on screen), tapering at the end
    start, end = P["join"], P["end"]
    m = 900
    for i in range(m + 1):
        phi = start + (end - start) * i / m
        pts.append((ring_pt(phi), ring_w(phi), "ring"))
    return pts


def ring_w(phi):
    k = math.cos(math.radians(phi - P["phi_thick"])) ** 2
    w = P["w_min"] + (P["w_max"] - P["w_min"]) * k
    left = phi - P["end"]  # degrees still to go
    if left < P["taper"]:
        x = left / P["taper"]
        w = 0.35 + (w - 0.35) * (1 - (1 - x) ** 2.2)
    return w


# ---------------------------------------------------------------- outline
def offsets(cl):
    L, R = [], []
    n = len(cl)
    for i, (p, w, _) in enumerate(cl):
        a = cl[max(0, i - 1)][0]
        b = cl[min(n - 1, i + 1)][0]
        t = unit((b[0] - a[0], b[1] - a[1]))
        nx, ny = -t[1], t[0]
        L.append((p[0] + nx * w / 2, p[1] + ny * w / 2))
        R.append((p[0] - nx * w / 2, p[1] - ny * w / 2))
    return L, R


def seg_x(p1, p2, p3, p4):
    d = (p2[0] - p1[0]) * (p4[1] - p3[1]) - (p2[1] - p1[1]) * (p4[0] - p3[0])
    if abs(d) < 1e-12:
        return None
    ua = ((p4[0] - p3[0]) * (p1[1] - p3[1]) - (p4[1] - p3[1]) * (p1[0] - p3[0])) / d
    ub = ((p2[0] - p1[0]) * (p1[1] - p3[1]) - (p2[1] - p1[1]) * (p1[0] - p3[0])) / d
    if 0 <= ua <= 1 and 0 <= ub <= 1:
        return (p1[0] + ua * (p2[0] - p1[0]), p1[1] + ua * (p2[1] - p1[1]))
    return None


def unloop(pts, window=220):
    """Remove the small self-intersection loops an offset curve makes on the
    inside of a tight turn. Returns (points, corner indices)."""
    out, corners = [], []
    i = 0
    n = len(pts)
    while i < n - 1:
        out.append(pts[i])
        hit = None
        for j in range(min(n - 2, i + window), i + 1, -1):
            x = seg_x(pts[i], pts[i + 1], pts[j], pts[j + 1])
            if x:
                hit = (j, x)
                break
        if hit:
            j, x = hit
            corners.append(len(out))
            out.append(x)
            i = j + 1
        else:
            i += 1
    out.append(pts[-1])
    return out, corners


def decimate(pts, corners, step=1.5, turn=7.0):
    keep = [0]
    acc_d = acc_a = 0.0
    cs = set(corners)
    for i in range(1, len(pts) - 1):
        acc_d += math.dist(pts[i], pts[i - 1])
        v1 = (pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
        v2 = (pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1])
        if math.hypot(*v1) > 1e-9 and math.hypot(*v2) > 1e-9:
            acc_a += abs(math.degrees(math.atan2(v1[0] * v2[1] - v1[1] * v2[0], v1[0] * v2[0] + v1[1] * v2[1])))
        if i in cs or acc_d >= step or acc_a >= turn:
            keep.append(i)
            acc_d = acc_a = 0.0
    keep.append(len(pts) - 1)
    new = [pts[k] for k in keep]
    newc = [keep.index(c) for c in corners if c in keep]
    return new, newc


def f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def catmull(pts, corners, closed_first=True):
    """Cubic Bézier path through pts (Catmull-Rom), sharp at corners."""
    cs = set(corners)
    d = []
    n = len(pts)
    for i in range(n - 1):
        p1, p2 = pts[i], pts[i + 1]
        p0 = pts[i - 1] if i > 0 and i not in cs else p1
        p3 = pts[i + 2] if i + 2 < n and (i + 1) not in cs else p2
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        d.append(f"C{f(c1[0])} {f(c1[1])} {f(c2[0])} {f(c2[1])} {f(p2[0])} {f(p2[1])}")
    return d


def stroke_path(cl):
    L, R = offsets(cl)
    L, cl_ = unloop(L)
    R, cr_ = unloop(R)
    L, cl_ = decimate(L, cl_)
    R, cr_ = decimate(R, cr_)
    # tip: a hair beyond the last centre point
    p_end = cl[-1][0]
    t_end = unit((cl[-1][0][0] - cl[-2][0][0], cl[-1][0][1] - cl[-2][0][1]))
    tip = add(p_end, t_end, 0.45)
    side_a = L + [tip]
    side_b = [tip] + R[::-1]
    d = [f"M{f(side_a[0][0])} {f(side_a[0][1])}"]
    d += catmull(side_a, cl_ + [len(side_a) - 1])
    d += catmull(side_b, [0] + [len(side_b) - 1 - c for c in cr_])
    d.append("Z")
    return "".join(d)


def tooth_path(cl):
    """Only the tooth part of the stroke (for an accent colour)."""
    part = [c for c in cl if c[2] == "tooth"]
    L, R = offsets(part)
    L, cl_ = unloop(L)
    R, cr_ = unloop(R)
    L, cl_ = decimate(L, cl_)
    R, cr_ = decimate(R, cr_)
    d = [f"M{f(L[0][0])} {f(L[0][1])}"]
    d += catmull(L, cl_)
    d.append(f"L{f(R[-1][0])} {f(R[-1][1])}")
    Rr = R[::-1]
    d += catmull(Rr, [len(Rr) - 1 - c for c in cr_])
    d.append("Z")
    return "".join(d)


# ---------------------------------------------------------------- the loop of ص
def loop_path():
    """Long flat Maghrebi loop: flat baseline, round right end, a top that
    dips at the left into the notch before the tooth; the counter is a slit.
    Its bottom-left hides under the stroke that turns up into the tooth."""
    top, bot = 48.3, 59.7
    rx = 69.2                      # right extreme
    r = (bot - top) / 2            # right-end radius
    cx = rx - r
    cy = (top + bot) / 2
    k = 0.5523 * r
    outer = (
        f"M50 {f(bot)}"
        f"L{f(cx)} {f(bot)}"
        f"C{f(cx + k)} {f(bot)} {f(rx)} {f(cy + k)} {f(rx)} {f(cy)}"
        f"C{f(rx)} {f(cy - k)} {f(cx + k)} {f(top)} {f(cx)} {f(top)}"
        f"C58.5 {f(top)} 53 {f(top + 0.05)} 50.2 {f(top + 0.3)}"
        f"C47.6 {f(top + 0.55)} 45.9 50.6 45.3 53.4"
        f"L44.4 55.6L47.5 57.2L50 57.6Z"
    )
    # counter: pointed at the left, round at the right
    ct, cb = 52.2, 55.4
    cr = (cb - ct) / 2
    ccx = rx - 3.3 - cr
    ck = 0.5523 * cr
    counter = (
        f"M48.9 54.3"
        f"C50.8 52.7 53.4 {f(ct)} 56.8 {f(ct)}"
        f"L{f(ccx)} {f(ct)}"
        f"C{f(ccx + ck)} {f(ct)} {f(ccx + cr)} {f(ct + cr - ck)} {f(ccx + cr)} {f(ct + cr)}"
        f"C{f(ccx + cr)} {f(ct + cr + ck)} {f(ccx + ck)} {f(cb)} {f(ccx)} {f(cb)}"
        f"L56.8 {f(cb)}"
        f"C53.6 {f(cb)} 51 55.3 48.9 54.3Z"
    )
    return outer + counter


def cy_(top, bot):
    return (top + bot) / 2


# ---------------------------------------------------------------- files
HEAD = '<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">\n'


def background():
    return (
        HEAD
        + "  <defs>\n"
        + '    <linearGradient id="paper" gradientUnits="userSpaceOnUse" x1="54" y1="0" x2="54" y2="108">\n'
        + f'      <stop offset="0" stop-color="{P["paper_top"]}"/>\n'
        + f'      <stop offset="1" stop-color="{P["paper_bottom"]}"/>\n'
        + "    </linearGradient>\n"
        + "  </defs>\n"
        + '  <rect x="0" y="0" width="108" height="108" fill="url(#paper)"/>\n'
        + "</svg>\n"
    )


def art(ink, accent, mono=False):
    cl = centreline()
    body = [f'  <g transform="translate({f(P["dx"])} {f(P["dy"])})">\n']
    body.append(f'    <path fill="{ink}" fill-rule="evenodd" d="{loop_path()}"/>\n')
    body.append(f'    <path fill="{ink}" d="{stroke_path(cl)}"/>\n')
    if accent and not mono:
        body.append(f'    <path fill="{accent}" d="{tooth_path(cl)}"/>\n')
    body.append("  </g>\n")
    return "".join(body)


def main(argv):
    out = Path(__file__).parent
    args = list(argv)
    while args:
        a = args.pop(0)
        if a == "--ink":
            P["ink"] = args.pop(0)
        elif a == "--accent":
            P["accent"] = args.pop(0)
        elif a.startswith("--"):
            key = a[2:].replace("-", "_")
            P[key] = float(args.pop(0))
        else:
            out = Path(a)
    out.mkdir(parents=True, exist_ok=True)
    bg = background()
    fg = HEAD + art(P["ink"], P["accent"]) + "</svg>\n"
    mono = HEAD + art("#000000", None, mono=True) + "</svg>\n"
    bg_inner = bg[len(HEAD):-len("</svg>\n")]
    icon = HEAD + bg_inner + art(P["ink"], P["accent"]) + "</svg>\n"
    for name, text in (("background.svg", bg), ("foreground.svg", fg), ("monochrome.svg", mono), ("icon.svg", icon)):
        with open(out / name, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
    print(f"wrote {out}")


if __name__ == "__main__":
    main(sys.argv[1:])
