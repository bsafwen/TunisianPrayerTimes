"""Render an adaptive-icon proposal to preview.png with headless Chrome.

Usage:
    python render_preview.py <proposal-dir> [<proposal-dir> ...]
    python render_preview.py --sheet <out.png> <proposal-dir> [...]

A proposal dir holds background.svg, foreground.svg and monochrome.svg, all on
the 108dp adaptive-icon canvas (viewBox 0 0 108 108). Launchers show only the
central 72dp square (x/y 18..90), cut by their mask; the 66dp circle around
(54, 54) is the only area every mask keeps.

preview.png shows the full canvas with those guides, the four common masks,
the themed (monochrome) icon tinted light and dark, and the icon at 96, 64,
48, 36 and 24 px on a light and a dark wallpaper. --sheet renders several
proposals side by side on both wallpapers instead.
"""

import base64
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

CHROME_CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
]

# Material You themed-icon tints, as Pixel draws them.
THEMED = {
    "light": ("#D7E8E2", "#163A32"),
    "dark": ("#1F2F2B", "#A9D6C8"),
}

MASKS = {
    "circle": "border-radius:50%",
    "squircle": "border-radius:34%",
    "rounded": "border-radius:22%",
    "teardrop": "border-radius:50% 50% 50% 16%",
}


def data_uri(path: Path) -> str:
    return "data:image/svg+xml;base64," + base64.b64encode(path.read_bytes()).decode()


def layers(d: Path):
    bg = data_uri(d / "background.svg")
    fg = data_uri(d / "foreground.svg")
    mono = data_uri(d / "monochrome.svg")
    return bg, fg, mono


def icon(bg: str, fg: str, size: int, mask: str = MASKS["circle"]) -> str:
    """Central 72dp of the 108dp canvas, drawn at `size` px and cut by `mask`."""
    full = size * 108 / 72
    off = -size * 18 / 72
    return (
        f'<div class="ic" style="width:{size}px;height:{size}px;{mask}">'
        f'<img src="{bg}" style="width:{full}px;height:{full}px;left:{off}px;top:{off}px">'
        f'<img src="{fg}" style="width:{full}px;height:{full}px;left:{off}px;top:{off}px">'
        "</div>"
    )


def themed(mono: str, size: int, theme: str) -> str:
    back, ink = THEMED[theme]
    full = size * 108 / 72
    off = -size * 18 / 72
    return (
        f'<div class="ic" style="width:{size}px;height:{size}px;border-radius:50%;background:{back}">'
        f'<div style="position:absolute;width:{full}px;height:{full}px;left:{off}px;top:{off}px;'
        f"background:{ink};-webkit-mask:url({mono}) center/100% 100% no-repeat;"
        f'mask:url({mono}) center/100% 100% no-repeat"></div></div>'
    )


CSS = """
body{margin:0;font:12px/1.3 'Segoe UI',sans-serif;color:#333;background:#f1f0eb}
.ic{position:relative;overflow:hidden;flex:none}
.ic img{position:absolute;display:block}
.cell{display:grid;justify-items:center;gap:6px}
.row{display:flex;gap:22px;align-items:flex-end;flex-wrap:wrap}
h4{margin:0 0 10px;font:600 14px 'Segoe UI',sans-serif;color:#222}
.band{display:flex;gap:26px;align-items:center;padding:14px 16px}
.band.light{background:#e7e3d8;color:#555}
.band.dark{background:#1d2120;color:#bbb}
"""


def preview_html(d: Path) -> str:
    bg, fg, mono = layers(d)
    canvas = (
        '<div style="position:relative;width:324px;height:324px">'
        f'<img src="{bg}" style="position:absolute;width:324px;height:324px">'
        f'<img src="{fg}" style="position:absolute;width:324px;height:324px">'
        '<div style="position:absolute;left:54px;top:54px;width:216px;height:216px;'
        'border:1.5px dashed #e0245e;box-sizing:border-box"></div>'
        '<div style="position:absolute;left:63px;top:63px;width:198px;height:198px;'
        'border:1.5px dashed #18d3f0;border-radius:50%;box-sizing:border-box"></div>'
        "</div>"
    )
    masks = "".join(
        f'<div class="cell">{icon(bg, fg, 150, css)}<span>{name}</span></div>'
        for name, css in MASKS.items()
    )
    themes = (
        f'<div class="cell">{themed(mono, 96, "light")}<span>themed light</span></div>'
        f'<div class="cell">{themed(mono, 96, "dark")}<span>themed dark</span></div>'
        f'<div class="cell">{themed(mono, 48, "light")}<span>48px</span></div>'
        f'<div class="cell">{themed(mono, 48, "dark")}<span>48px</span></div>'
        f'<div class="cell">{themed(mono, 24, "light")}<span>24px</span></div>'
        f'<div class="cell">{themed(mono, 24, "dark")}<span>24px</span></div>'
    )
    sizes = [96, 64, 48, 36, 24]
    light = "".join(
        f'<div class="cell">{icon(bg, fg, s)}<span>{s}px</span></div>' for s in sizes
    )
    dark = "".join(
        f'<div class="cell">{icon(bg, fg, s, MASKS["squircle"])}<span>{s}px</span></div>'
        for s in sizes
    )
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>{CSS}</style></head><body>
<div style="display:flex;gap:34px;padding:16px">
  <div class="cell">{canvas}<span>108dp canvas · red = 72dp visible · cyan = 66dp safe circle</span></div>
  <div style="display:grid;gap:18px;align-content:start">
    <div><h4>Launcher masks (central 72dp)</h4><div class="row">{masks}</div></div>
    <div><h4>Themed icon (monochrome layer)</h4><div class="row">{themes}</div></div>
  </div>
</div>
<div class="band light">{light}<span>light wallpaper · circle</span></div>
<div class="band dark">{dark}<span>dark wallpaper · squircle</span></div>
</body></html>"""


def sheet_html(dirs) -> str:
    def band(cls, mask, size):
        cells = []
        for d in dirs:
            bg, fg, _ = layers(d)
            cells.append(
                f'<div class="cell" style="width:{size + 34}px">{icon(bg, fg, size, mask)}'
                f'<span style="text-align:center">{d.name}</span></div>'
            )
        return f'<div class="band {cls}" style="flex-wrap:wrap;align-items:flex-start">{"".join(cells)}</div>'

    themes = []
    for d in dirs:
        _, _, mono = layers(d)
        themes.append(
            f'<div class="cell" style="width:82px">{themed(mono, 48, "light")}{themed(mono, 48, "dark")}</div>'
        )
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>{CSS}</style></head><body>
{band("light", MASKS["circle"], 96)}
{band("dark", MASKS["squircle"], 64)}
{band("light", MASKS["rounded"], 48)}
<div class="band" style="background:#f1f0eb;flex-wrap:wrap">{"".join(themes)}</div>
</body></html>"""


def chrome() -> str:
    for c in CHROME_CANDIDATES:
        if os.path.exists(c):
            return c
    sys.exit("no Chrome or Edge found")


def screenshot(html: str, out: Path, width: int, height: int) -> None:
    work = Path(tempfile.mkdtemp(prefix="iconprev-"))
    try:
        page = work / "page.html"
        page.write_text(html, encoding="utf-8")
        subprocess.run(
            [
                chrome(),
                "--headless=new",
                "--disable-gpu",
                "--hide-scrollbars",
                "--force-device-scale-factor=1",
                f"--user-data-dir={work / 'profile'}",
                f"--window-size={width},{height}",
                f"--screenshot={out}",
                page.as_uri(),
            ],
            check=True,
            capture_output=True,
            timeout=90,
        )
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main(argv) -> None:
    if argv[:1] == ["--sheet"]:
        out = Path(argv[1]).resolve()
        dirs = [Path(a).resolve() for a in argv[2:]]
        per_row = max(1, min(len(dirs), 9))
        rows = (len(dirs) + per_row - 1) // per_row
        screenshot(sheet_html(dirs), out, 140 * per_row + 60, 200 + rows * 330)
        print(out)
        return
    for a in argv:
        d = Path(a).resolve()
        out = d / "preview.png"
        screenshot(preview_html(d), out, 1080, 660)
        print(out)


if __name__ == "__main__":
    main(sys.argv[1:])
