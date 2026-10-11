"""Check an adaptive-icon proposal for VectorDrawable safety and safe-zone use.

Usage: python check_icon.py <proposal-dir> [...]

Static checks on background.svg, foreground.svg and monochrome.svg:
  - viewBox is 0 0 108 108
  - no elements Android Studio's SVG importer drops or mangles (filter, mask,
    pattern, image, text, use, foreignObject, style blocks, CSS classes)
  - monochrome.svg uses one colour and no opacity

Raster checks (headless Chrome): how much of the foreground and monochrome ink
falls outside the 72dp visible square and outside the 66dp safe circle, and
how much of the visible square the monochrome ink covers.
"""

import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from render_preview import chrome, data_uri  # noqa: E402

FORBIDDEN = {"filter", "mask", "pattern", "image", "text", "use", "foreignObject", "style", "symbol"}
SCALE = 6  # px per dp in the raster check


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def static_checks(path: Path, mono: bool):
    problems = []
    root = ET.parse(path).getroot()
    if root.get("viewBox", "").split() != ["0", "0", "108", "108"]:
        problems.append(f"{path.name}: viewBox must be 0 0 108 108")
    colours = set()
    for el in root.iter():
        name = local(el.tag)
        if name in FORBIDDEN:
            problems.append(f"{path.name}: <{name}> is not VectorDrawable-safe")
        if el.get("class"):
            problems.append(f"{path.name}: class attribute (CSS) is not supported")
        style = el.get("style", "")
        for attr in ("fill", "stroke"):
            v = el.get(attr)
            m = re.search(rf"{attr}\s*:\s*([^;]+)", style)
            if m:
                v = m.group(1).strip()
            if v and v != "none" and not v.startswith("url("):
                colours.add(v.lower())
        if mono:
            if any(el.get(a) not in (None, "1") for a in ("opacity", "fill-opacity", "stroke-opacity")) or "opacity" in style:
                problems.append(f"{path.name}: opacity is not allowed in the monochrome layer")
            if name in ("linearGradient", "radialGradient"):
                problems.append(f"{path.name}: gradients are not allowed in the monochrome layer")
    if mono and len(colours - {"#000", "#000000", "black"}) > 0:
        problems.append(f"{path.name}: monochrome must be pure #000000, found {sorted(colours)}")
    return problems


def raster_alpha(svg: Path, work: Path) -> Image.Image:
    px = 108 * SCALE
    page = work / f"{svg.stem}.html"
    page.write_text(
        f'<!doctype html><html><body style="margin:0;background:transparent">'
        f'<img src="{data_uri(svg)}" style="display:block;width:{px}px;height:{px}px"></body></html>',
        encoding="utf-8",
    )
    out = work / f"{svg.stem}.png"
    subprocess.run(
        [chrome(), "--headless=new", "--disable-gpu", "--hide-scrollbars",
         "--force-device-scale-factor=1", "--default-background-color=00000000",
         f"--user-data-dir={work / 'profile'}", f"--window-size={px},{px}",
         f"--screenshot={out}", page.as_uri()],
        check=True, capture_output=True, timeout=90,
    )
    return Image.open(out).convert("RGBA").getchannel("A")


def coverage(alpha: Image.Image):
    w, h = alpha.size
    data = alpha.load()
    total = in_sq = out_sq = out_circle = 0.0
    c = 54 * SCALE
    r2 = (33 * SCALE) ** 2
    lo, hi = 18 * SCALE, 90 * SCALE
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            a = data[x, y] / 255
            if not a:
                continue
            total += a
            inside_sq = lo <= x < hi and lo <= y < hi
            if inside_sq:
                in_sq += a
            else:
                out_sq += a
            if (x - c) ** 2 + (y - c) ** 2 > r2:
                out_circle += a
    sq_area = ((hi - lo) / 2) ** 2
    return {
        "ink_of_visible_square": in_sq / sq_area,
        "share_outside_72dp_square": out_sq / total if total else 0,
        "share_outside_66dp_circle": out_circle / total if total else 0,
    }


def main(argv):
    for a in argv:
        d = Path(a).resolve()
        print(f"== {d.name}")
        problems = []
        for name in ("background.svg", "foreground.svg", "monochrome.svg"):
            p = d / name
            if not p.exists():
                problems.append(f"missing {name}")
                continue
            problems += static_checks(p, mono=name == "monochrome.svg")
        work = Path(tempfile.mkdtemp(prefix="iconchk-"))
        try:
            for name in ("foreground.svg", "monochrome.svg"):
                p = d / name
                if p.exists():
                    cov = coverage(raster_alpha(p, work))
                    print(f"  {name}: " + ", ".join(f"{k} {v:.1%}" for k, v in cov.items()))
        finally:
            shutil.rmtree(work, ignore_errors=True)
        print("  OK" if not problems else "  PROBLEMS:\n    " + "\n    ".join(problems))


if __name__ == "__main__":
    main(sys.argv[1:])
