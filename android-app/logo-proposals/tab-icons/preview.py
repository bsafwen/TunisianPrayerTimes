"""Render icons.json as a check sheet: each icon outline/filled, large and at 24dp on a 420 dpi phone."""
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

HERE = Path(__file__).parent
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"


def svg(d, px, color):
    return (f'<svg width="{px}" height="{px}" viewBox="0 0 24 24"><path fill="{color}" fill-rule="evenodd" d="{d}"/></svg>')


def main():
    icons = json.loads((HERE / "icons.json").read_text(encoding="utf-8"))
    rows = []
    for name, v in icons.items():
        cells = [f"<td class=n>{name}</td>"]
        for state, color in (("outline", "#6D6D6D"), ("filled", "#004D40")):
            cells.append(f"<td class=big>{svg(v[state], 168, color)}</td>")
        for state, color in (("outline", "#6D6D6D"), ("filled", "#004D40")):
            cells.append(f"<td>{svg(v[state], 63, color)}</td>")
        cells.append(f"<td class=pill><span>{svg(v['filled'], 63, '#004D40')}</span></td>")
        rows.append("<tr>" + "".join(cells) + "</tr>")
    html = f"""<!doctype html><html><head><meta charset="utf-8"><style>
body{{margin:0;padding:16px;background:#fff;font-family:sans-serif}}
td{{padding:6px 14px;text-align:center;vertical-align:middle}} td.n{{font-size:20px;text-align:left}}
td.big svg{{background:repeating-linear-gradient(0deg,#f3f3f3 0 7px,#fff 7px 7px)}}
td.big{{background:#FAFAFA}}
td.pill span{{display:inline-flex;background:#E0F2F1;border-radius:40px;padding:6px 26px}}
</style></head><body><table>{''.join(rows)}</table></body></html>"""
    work = Path(tempfile.mkdtemp(prefix="qicon-"))
    try:
        page = work / "page.html"
        page.write_text(html, encoding="utf-8")
        out = HERE / "icons.png"
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                        "--force-device-scale-factor=1", f"--user-data-dir={work / 'profile'}",
                        f"--window-size=900,{len(icons) * 186 + 40}", f"--screenshot={out}", page.as_uri()],
                       check=True, capture_output=True, timeout=90)
        print(out)
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
