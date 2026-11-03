"""Round 3: the owner's ribbon-arch + minaret + Qur'an-on-stand idea, redrawn flat.

Usage: python -I gen.py

Shapes are drawn in a local design space (about 100 x 115 units) and baked onto
the 108dp canvas by fit(). Only absolute M/L/C/Z commands are used, so every
path converts cleanly to a VectorDrawable. Each variant gets background.svg,
foreground.svg, monochrome.svg and icon.svg in ../NN-slug/.
"""

import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Local design space -> 108dp canvas.
SCALE = 0.5
CENTRE_LOCAL = (45.0, 50.0)
CENTRE_CANVAS = (54.0, 54.5)


# The minaret is drawn at x 74..96 and shifted by MINARET_DX, closer to the arch.
MINARET_DX = -3.5
_dx = 0.0


def fit(x, y):
    return (
        CENTRE_CANVAS[0] + (x + _dx - CENTRE_LOCAL[0]) * SCALE,
        CENTRE_CANVAS[1] + (y - CENTRE_LOCAL[1]) * SCALE,
    )


def n(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def P(*cmds):
    """Path from ('M', x, y), ('L', x, y), ('C', x1, y1, x2, y2, x, y), ('Z',)."""
    out = []
    for c in cmds:
        op, nums = c[0], c[1:]
        pts = []
        for i in range(0, len(nums), 2):
            x, y = fit(nums[i], nums[i + 1])
            pts.append(f"{n(x)} {n(y)}")
        out.append(op + " ".join(pts))
    return "".join(out)


def poly(points, close=True):
    cmds = [("M", *points[0])] + [("L", *p) for p in points[1:]]
    if close:
        cmds.append(("Z",))
    return P(*cmds)


def rect(x0, y0, x1, y1):
    return poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)])


def circle(cx, cy, r, ccw=False):
    k = 0.5523 * r
    if not ccw:
        return P(
            ("M", cx, cy - r),
            ("C", cx + k, cy - r, cx + r, cy - k, cx + r, cy),
            ("C", cx + r, cy + k, cx + k, cy + r, cx, cy + r),
            ("C", cx - k, cy + r, cx - r, cy + k, cx - r, cy),
            ("C", cx - r, cy - k, cx - k, cy - r, cx, cy - r),
            ("Z",),
        )
    return P(
        ("M", cx, cy - r),
        ("C", cx - k, cy - r, cx - r, cy - k, cx - r, cy),
        ("C", cx - r, cy + k, cx - k, cy + r, cx, cy + r),
        ("C", cx + k, cy + r, cx + r, cy + k, cx + r, cy),
        ("C", cx + r, cy - k, cx + k, cy - r, cx, cy - r),
        ("Z",),
    )


def crescent(cx, cy, r, dx, dy, r2, steps=40):
    """Outer circle (cx, cy, r) minus inner circle (cx+dx, cy+dy, r2), sampled."""
    ix, iy = cx + dx, cy + dy
    d = math.hypot(dx, dy)
    # Intersection angles on the outer circle.
    a = (r * r - r2 * r2 + d * d) / (2 * d)
    h = math.sqrt(max(r * r - a * a, 0))
    base = math.atan2(dy, dx)
    off = math.atan2(h, a)
    t1, t2 = base + off, base - off + 2 * math.pi  # outer arc away from the bite
    pts = []
    for i in range(steps + 1):
        t = t1 + (t2 - t1) * i / steps
        pts.append((cx + r * math.cos(t), cy + r * math.sin(t)))
    # Inner arc back, inside the outer circle.
    p_end = pts[-1]
    p_start = pts[0]
    u1 = math.atan2(p_end[1] - iy, p_end[0] - ix)
    u2 = math.atan2(p_start[1] - iy, p_start[0] - ix)
    if u2 > u1:
        u2 -= 2 * math.pi
    for i in range(1, steps):
        u = u1 + (u2 - u1) * i / steps
        pts.append((ix + r2 * math.cos(u), iy + r2 * math.sin(u)))
    return poly(pts)


# ---------------------------------------------------------------- geometry

ARCH_X = 38.0  # arch axis


def arch_halves():
    """Ogee/onion arch band split at the apex into a left and a right half: the fold."""
    left = P(
        ("M", 8, 93),
        ("L", 8, 70),
        ("C", 8, 60, 5, 55, 6, 47),
        ("C", 7, 33, 33, 32, ARCH_X, 9),
        ("L", ARCH_X, 30),
        ("C", 34, 43, 17, 42, 18, 54),
        ("C", 18.5, 60, 19, 63, 19, 70),
        ("L", 19, 93),
        ("Z",),
    )
    right = P(
        ("M", 68, 93),
        ("L", 68, 70),
        ("C", 68, 60, 71, 55, 70, 47),
        ("C", 69, 33, 43, 32, ARCH_X, 9),
        ("L", ARCH_X, 30),
        ("C", 42, 43, 59, 42, 58, 54),
        ("C", 57.5, 60, 57, 63, 57, 70),
        ("L", 57, 93),
        ("Z",),
    )
    return left, right


def base_band():
    """The ribbon that runs under the arch and the minaret, ending in a tapered tail."""
    return P(
        ("M", -3, 105),
        ("C", 4, 103, 5, 93, 14, 92),
        ("L", 92.5, 92),
        ("L", 92.5, 103),
        ("L", 18, 103),
        ("C", 10, 103, 5, 105.5, -3, 105),
        ("Z",),
    )


def minaret(with_holes=True):
    """Square Maghrebi minaret: shaft, cornice, lantern with arched window, cap, finial."""
    global _dx
    _dx = MINARET_DX
    shaft = [(77, 92), (77, 37), (93, 37), (93, 92)]
    parts = [poly(shaft)]
    if with_holes:
        # Three slit windows, cut with evenodd.
        for y in (48, 61, 74):
            parts.append(poly([(83.6, y), (83.6, y + 6.5), (86.4, y + 6.5), (86.4, y)]))
    cornice = rect(74, 32, 96, 37)
    lantern = [poly([(79.5, 32), (79.5, 18), (90.5, 18), (90.5, 32)])]
    if with_holes:
        lantern.append(P(
            ("M", 83, 30), ("L", 83, 24.5),
            ("C", 83, 22.4, 85, 21, 85, 20.2),
            ("C", 85, 21, 87, 22.4, 87, 24.5),
            ("L", 87, 30), ("Z",),
        ))
    cap = poly([(77, 18), (85, 11), (93, 18)])
    stem = rect(84.3, 5.5, 85.7, 11.5)
    ball = circle(85, 6.5, 1.9)
    moon = crescent(85, -0.5, 5.2, 2.3, -2.0, 4.4)
    _dx = 0.0
    return {
        "shaft": "".join(parts),
        "cornice": cornice,
        "lantern": "".join(lantern),
        "cap": cap,
        "finial": stem + ball,
        "moon": moon,
    }


def book():
    """Open mushaf seen from the front, resting on a rahla (X stand)."""
    # Cover: one shape slightly larger than both pages.
    cover = P(
        ("M", 19.5, 72.5),
        ("L", 21.5, 59.5),
        ("C", 28, 58.5, 34, 59.5, ARCH_X, 63),
        ("C", 42, 59.5, 48, 58.5, 54.5, 59.5),
        ("L", 56.5, 72.5),
        ("C", 49, 71.5, 42, 72.5, ARCH_X, 75.5),
        ("C", 34, 72.5, 27, 71.5, 19.5, 72.5),
        ("Z",),
    )
    left_page = P(
        ("M", 22, 70.6),
        ("L", 23.6, 58.2),
        ("C", 29, 57.4, 34.5, 58.4, 37.4, 61.6),
        ("L", 37.4, 73.2),
        ("C", 34.5, 70.6, 28.5, 69.8, 22, 70.6),
        ("Z",),
    )
    right_page = P(
        ("M", 54, 70.6),
        ("L", 52.4, 58.2),
        ("C", 47, 57.4, 41.5, 58.4, 38.6, 61.6),
        ("L", 38.6, 73.2),
        ("C", 41.5, 70.6, 47.5, 69.8, 54, 70.6),
        ("Z",),
    )
    # Abstract lines of text, no letters.
    lines = []
    for i, y in enumerate((61.6, 64.6, 67.6)):
        lines.append(P(("M", 26, y), ("C", 30, y - 0.6, 33, y - 0.2, 35.2, y + 1.0),
                       ("L", 35.2, y + 2.0), ("C", 33, y + 0.8, 30, y + 0.4, 26, y + 1.0), ("Z",)))
        lines.append(P(("M", 50, y), ("C", 46, y - 0.6, 43, y - 0.2, 40.8, y + 1.0),
                       ("L", 40.8, y + 2.0), ("C", 43, y + 0.8, 46, y + 0.4, 50, y + 1.0), ("Z",)))
    # Rahla: two crossed boards under the book.
    leg_a = poly([(22.5, 92), (27, 92), (53.5, 74.5), (49, 74.5)])
    leg_b = poly([(53.5, 92), (49, 92), (22.5, 74.5), (27, 74.5)])
    return {
        "cover": cover,
        "pages": left_page + right_page,
        "lines": "".join(lines),
        "stand": leg_a + leg_b,
    }


# ---------------------------------------------------------------- variants


def svg(body):
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">'
        + body + "</svg>"
    )


def path(d, fill, evenodd=False):
    rule = ' fill-rule="evenodd"' if evenodd else ""
    return f'<path fill="{fill}"{rule} d="{d}"/>'


def flat_bg(colour):
    return svg(f'<rect width="108" height="108" fill="{colour}"/>')


def radial_bg(inner, outer):
    return svg(
        '<defs><radialGradient id="g" gradientUnits="userSpaceOnUse" cx="54" cy="44" r="70">'
        f'<stop offset="0" stop-color="{inner}"/><stop offset="1" stop-color="{outer}"/>'
        '</radialGradient></defs><rect width="108" height="108" fill="url(#g)"/>'
    )


def foreground(c, stand=True, lines=True):
    left, right = arch_halves()
    m = minaret()
    b = book()
    body = [
        path(base_band(), c["band"]),
        path(left, c["dark"]),
        path(right, c["light"]),
        path(m["shaft"], c["minaret"], evenodd=True),
        path(m["cornice"], c["minaret_dark"]),
        path(m["lantern"], c["minaret"], evenodd=True),
        path(m["cap"], c["minaret_dark"]),
        path(m["finial"], c["minaret_dark"]),
        path(m["moon"], c["moon"]),
    ]
    if stand:
        body.append(path(b["stand"], c["stand"]))
    body += [path(b["cover"], c["cover"]), path(b["pages"], c["pages"])]
    if lines:
        body.append(path(b["lines"], c["lines"]))
    return svg("".join(body))


def monochrome(stand=True):
    """One flat colour. The arch fold and the book are kept apart by real gaps."""
    left, right = arch_halves()
    m = minaret()
    b = book()
    body = [
        path(base_band(), "#000000"),
        path(left, "#000000"),
        path(right, "#000000"),
        path(m["shaft"], "#000000", evenodd=True),
        path(m["cornice"], "#000000"),
        path(m["lantern"], "#000000", evenodd=True),
        path(m["cap"], "#000000"),
        path(m["finial"], "#000000"),
        path(m["moon"], "#000000"),
        # Book as an outline: the cover with the two pages cut out, lines kept.
        path(b["cover"] + b["pages"], "#000000", evenodd=True),
        path(b["lines"], "#000000"),
    ]
    if stand:
        body.append(path(b["stand"], "#000000"))
    return svg("".join(body))


def merged(bg_svg, fg_svg):
    strip = lambda s: s.split(">", 1)[1].rsplit("</svg>", 1)[0]
    return svg(strip(bg_svg) + strip(fg_svg))


SIDI_BOU_SAID = "#1F5FA8"
NAVY = "#16365F"
CREAM = "#FBF6EE"
WOOD = "#9A5F33"

VARIANTS = {
    "01-arch-cream": dict(
        bg=radial_bg("#FDFAF4", "#F1E8DA"),
        colours=dict(band=SIDI_BOU_SAID, dark=NAVY, light=SIDI_BOU_SAID,
                     minaret=SIDI_BOU_SAID, minaret_dark=NAVY, moon=SIDI_BOU_SAID,
                     cover=SIDI_BOU_SAID, pages="#FFFFFF", lines=NAVY, stand=WOOD),
    ),
    "02-arch-blue": dict(
        bg=radial_bg("#2A6DB8", "#1A4F8E"),
        colours=dict(band="#FFFFFF", dark="#D9E6F5", light="#FFFFFF",
                     minaret="#FFFFFF", minaret_dark="#D9E6F5", moon="#FFFFFF",
                     cover="#0F2F57", pages=CREAM, lines=SIDI_BOU_SAID, stand="#C98F5E"),
    ),
    "03-arch-cream-plain": dict(
        bg=radial_bg("#FDFAF4", "#F1E8DA"),
        colours=dict(band=SIDI_BOU_SAID, dark=NAVY, light=SIDI_BOU_SAID,
                     minaret=SIDI_BOU_SAID, minaret_dark=NAVY, moon=SIDI_BOU_SAID,
                     cover=NAVY, pages="#FFFFFF", lines=SIDI_BOU_SAID, stand=NAVY),
    ),
}


def main():
    for slug, v in VARIANTS.items():
        d = ROOT / slug
        d.mkdir(exist_ok=True)
        fg = foreground(v["colours"])
        (d / "background.svg").write_text(v["bg"], encoding="utf-8", newline="\n")
        (d / "foreground.svg").write_text(fg, encoding="utf-8", newline="\n")
        (d / "monochrome.svg").write_text(monochrome(), encoding="utf-8", newline="\n")
        (d / "icon.svg").write_text(merged(v["bg"], fg), encoding="utf-8", newline="\n")
        print(d)


if __name__ == "__main__":
    main()
