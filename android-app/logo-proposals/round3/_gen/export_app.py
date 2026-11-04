"""Install a round 3 proposal as the phone app's launcher icon.

Usage: python -I export_app.py [proposal-dir]   (default: 15-final-teal)

Writes, under android-app/:
  app/src/main/res/drawable/ic_launcher_foreground.xml   foreground.svg as a VectorDrawable
  app/src/main/res/drawable/ic_launcher_monochrome.xml   monochrome.svg (themed icon)
  app/src/main/res/values/ic_launcher_background.xml     the flat background colour
  app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml    adaptive icons with <monochrome>
  app/src/main/res/mipmap-*/ic_launcher*.png             legacy PNGs (rounded square, circle)
  app-icon.svg, app-icon-512.png                         Play Store icon: the central 72dp, full bleed
  feature-graphic.svg, feature-graphic.png               Play feature graphic: the round icon on the
                                                         app's teal, the app name from strings.xml
                                                         in Amiri Bold (_gen/fonts/Amiri-Bold.ttf, OFL)

The proposal's SVGs come from gen4.py: one <path fill fill-rule d> per colour, with
absolute M/L/Z data only, so the conversion is one-to-one.
"""

import base64
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

ROUND3 = Path(__file__).resolve().parent.parent
ANDROID = ROUND3.parent.parent
RES = ANDROID / "app" / "src" / "main" / "res"
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
PATH_RE = re.compile(r'<path fill="(#[0-9A-Fa-f]{6})" fill-rule="(evenodd|nonzero)" d="([^"]+)"/>')


def write(path: Path, text: str):
    path.write_text(text, encoding="utf-8", newline="\n")
    print(path.relative_to(ANDROID))


def vector(svg: str, comment: str) -> str:
    paths = PATH_RE.findall(svg)
    assert paths and svg.count("<path") == len(paths), "unexpected SVG content"
    body = "".join(
        "    <path\n"
        f'        android:fillColor="{fill}"\n'
        + ('        android:fillType="evenOdd"\n' if rule == "evenodd" else "")
        + f'        android:pathData="{d}" />\n'
        for fill, rule, d in paths
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {comment} -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        f"{body}"
        "</vector>\n"
    )


ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""


def render_pngs(icon_svg: Path, tmp: Path):
    """Draw every PNG on one transparent page with headless Chrome, then crop."""
    uri = "data:image/svg+xml;base64," + base64.b64encode(icon_svg.read_bytes()).decode()
    jobs = [("play", 512, "0")] + [
        (f"{dens}/{name}", size, radius)
        for dens, size in DENSITIES.items()
        for name, radius in (("ic_launcher", "22%"), ("ic_launcher_round", "50%"))
    ]
    cells, divs, x = [], [], 0
    for key, size, radius in jobs:
        cells.append((key, x, size))
        full, off = size * 108 / 72, -size * 18 / 72
        divs.append(
            f'<div style="position:absolute;left:{x}px;top:0;width:{size}px;height:{size}px;'
            f'overflow:hidden;border-radius:{radius}">'
            f'<img src="{uri}" style="position:absolute;left:{off}px;top:{off}px;width:{full}px;height:{full}px"></div>'
        )
        x += size + 8
    html = tmp / "icons.html"
    html.write_text('<html><body style="margin:0;background:transparent">' + "".join(divs) + "</body></html>",
                    encoding="utf-8")
    shot = tmp / "icons.png"
    chrome_shot(html, shot, x, 512, transparent=True)
    sheet = Image.open(shot).convert("RGBA")
    for key, left, size in cells:
        im = sheet.crop((left, 0, left + size, size))
        if key == "play":
            out = ANDROID / "app-icon-512.png"
            im.convert("RGB").save(out)
        else:
            dens, name = key.split("/")
            out = RES / f"mipmap-{dens}" / f"{name}.png"
            im.save(out)
        print(out.relative_to(ANDROID))


def chrome_shot(html: Path, out: Path, width: int, height: int, transparent=False):
    # Headless Chrome sometimes hangs on this PC; a private profile and one retry get past it.
    args = [CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
            "--no-first-run", f"--user-data-dir={html.parent / 'chrome-profile'}",
            f"--screenshot={out}", f"--window-size={width},{height}", html.as_uri()]
    if transparent:
        args.insert(-1, "--default-background-color=00000000")
    for attempt in (1, 2):
        try:
            subprocess.run(args, check=True, timeout=90, capture_output=True)
            return
        except subprocess.TimeoutExpired:
            if attempt == 2:
                raise
            print("headless Chrome hung, retrying")


def feature_graphic(src: Path, tmp: Path):
    """1024x500: the round icon with a thin gold ring on the app's teal, the name below."""
    name = re.search(r'<string name="app_name">([^<]+)</string>',
                     (RES / "values" / "strings.xml").read_text(encoding="utf-8")).group(1)
    icon_body = (src / "icon.svg").read_text(encoding="utf-8").split(">", 1)[1].rsplit("</svg>", 1)[0]
    cx, cy, r = 512, 196, 124
    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">
  <!-- Generated by logo-proposals/round3/_gen/export_app.py from {src.name}; edit that script, not this file. -->
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="#00332D"/>
      <stop offset="100%" stop-color="#006B5A"/>
    </linearGradient>
    <clipPath id="disc"><circle cx="54" cy="54" r="36"/></clipPath>
  </defs>
  <rect width="1024" height="500" fill="url(#bg)"/>
  <g opacity="0.1" stroke="#D4AF37" stroke-width="1" fill="none">
    <circle cx="{cx}" cy="{cy}" r="{r + 40}"/>
    <circle cx="{cx}" cy="{cy}" r="{r + 80}"/>
    <circle cx="{cx}" cy="{cy}" r="{r + 120}"/>
  </g>
  <circle cx="{cx}" cy="{cy}" r="{r + 7}" fill="none" stroke="#D4AF37" stroke-width="3" opacity="0.75"/>
  <svg x="{cx - r}" y="{cy - r}" width="{2 * r}" height="{2 * r}" viewBox="18 18 72 72">
    <g clip-path="url(#disc)">{icon_body}</g>
  </svg>
  <text x="512" y="398" text-anchor="middle" font-family="Amiri, serif" font-weight="700" font-size="60" fill="#FFFFFF" direction="rtl">{name}</text>
  <text x="512" y="460" text-anchor="middle" font-family="'Noto Sans Arabic', sans-serif" font-size="20" fill="#D4AF37">Tunisian Prayer Times</text>
</svg>
"""
    write(ANDROID / "feature-graphic.svg", svg)
    # The name is in Amiri Bold (owner's pick, 2026-11-03; _gen/fonts, SIL OFL), the
    # tagline in the app's Noto Sans Arabic. The SVG names both, with generic fallbacks.
    faces = (("Amiri", 700, Path(__file__).parent / "fonts" / "Amiri-Bold.ttf"),
             ("Noto Sans Arabic", 400, RES / "font" / "noto_sans_arabic.ttf"))
    fonts = "".join(
        f"@font-face{{font-family:'{family}';font-weight:{w};"
        f"src:url(data:font/ttf;base64,{base64.b64encode(f.read_bytes()).decode()})}}"
        for family, w, f in faces
    )
    html = tmp / "feature.html"
    html.write_text(f"<html><head><meta charset=\"utf-8\"><style>{fonts}body{{margin:0}}</style></head><body>{svg}</body></html>",
                    encoding="utf-8")
    shot = tmp / "feature.png"
    chrome_shot(html, shot, 1024, 500)
    Image.open(shot).convert("RGB").crop((0, 0, 1024, 500)).save(ANDROID / "feature-graphic.png")
    print("feature-graphic.png")


def main():
    src = ROUND3 / (sys.argv[1] if len(sys.argv) > 1 else "15-final-teal")
    fg = (src / "foreground.svg").read_text(encoding="utf-8")
    mono = (src / "monochrome.svg").read_text(encoding="utf-8")
    bg = re.search(r'<rect width="108" height="108" fill="(#[0-9A-Fa-f]{6})"/>',
                   (src / "background.svg").read_text(encoding="utf-8"))
    assert bg, "the app icon needs a flat background colour"
    note = f"Generated by logo-proposals/round3/_gen/export_app.py from {src.name}; edit gen4.py, not this file."
    write(RES / "drawable" / "ic_launcher_foreground.xml", vector(fg, note))
    write(RES / "drawable" / "ic_launcher_monochrome.xml", vector(mono, note))
    write(RES / "values" / "ic_launcher_background.xml",
          '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
          f'    <color name="ic_launcher_background">{bg.group(1)}</color>\n</resources>\n')
    for name in ("ic_launcher", "ic_launcher_round"):
        write(RES / "mipmap-anydpi-v26" / f"{name}.xml", ADAPTIVE)
    # Play Store icon: the visible 72dp square, full bleed (Play applies its own mask).
    body = (src / "icon.svg").read_text(encoding="utf-8").split(">", 1)[1]
    write(ANDROID / "app-icon.svg",
          '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="18 18 72 72">' + body)
    with tempfile.TemporaryDirectory() as tmp:
        render_pngs(src / "icon.svg", Path(tmp))
        feature_graphic(src, Path(tmp))


if __name__ == "__main__":
    main()
