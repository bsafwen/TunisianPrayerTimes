"""Build round2/gallery.html, the page published as the proposals artifact.

Usage: python -I build_gallery.py

Reads gallery_data.json (copy) and each proposal's icon.svg, monochrome.svg
and listed variant folders, and writes a self-contained page: icons are
embedded as data URIs, so the page works offline and as an artifact.
"""

import base64
import html
import json
from pathlib import Path

ROUND = Path(__file__).resolve().parent.parent
DATA = json.loads((Path(__file__).parent / "gallery_data.json").read_text(encoding="utf-8"))


def uri(path: Path) -> str:
    return "data:image/svg+xml;base64," + base64.b64encode(path.read_bytes()).decode()


def esc(s: str) -> str:
    return html.escape(s, quote=True)


def tile(src: str, size: int, cls: str = "") -> str:
    """The central 72dp of a 108dp icon, cut by the page-wide mask."""
    return f'<span class="tile {cls}" style="--s:{size}px"><img src="{src}" alt=""></span>'


def themed(mono: str, size: int, mode: str) -> str:
    return f'<span class="tile themed {mode}" style="--s:{size}px;--m:url({mono})"><i></i></span>'


def card(p: dict) -> str:
    d = ROUND / p["folder"]
    icon = uri(d / "icon.svg")
    mono = uri(d / "monochrome.svg")
    ladder = "".join(
        f'<figure>{tile(icon, s)}<figcaption>{s}</figcaption></figure>' for s in (96, 64, 48, 36, 24)
    )
    themes = (
        f'<figure>{themed(mono, 48, "light")}<figcaption>themed</figcaption></figure>'
        f'<figure>{themed(mono, 48, "dark")}<figcaption>themed</figcaption></figure>'
    )
    variants = ""
    if p.get("variants"):
        vs = "".join(
            f'<figure class="variant">{tile(uri(ROUND / v["path"] / "icon.svg"), 56)}'
            f"<figcaption>{esc(v['label'])}</figcaption></figure>"
            for v in p["variants"]
        )
        variants = f'<div class="variants"><h4>Also drawn</h4><div class="vrow">{vs}</div></div>'
    rows = "".join(
        f"<dt>{esc(k)}</dt><dd>{esc(v)}</dd>"
        for k, v in (("Silence", p["silence"]), ("Prayer", p["prayer"]), ("Watch out", p["risk"]))
    )
    return f"""
<article class="card" id="{esc(p['anchor'])}">
  <div class="hero">
    {tile(icon, 132, "big")}
    <div class="names">
      <span class="num">{esc(p['num'])}</span>
      <h3 lang="ar" dir="rtl">{esc(p['ar'])}</h3>
      <p class="en">{esc(p['en'])}</p>
    </div>
  </div>
  <p class="idea">{esc(p['idea'])}</p>
  <dl>{rows}</dl>
  <div class="ladder">{ladder}<span class="sep"></span>{themes}</div>
  {variants}
</article>"""


def dropped(p: dict) -> str:
    icon = uri(ROUND / p["folder"] / "icon.svg")
    return (
        f'<figure class="drop">{tile(icon, 56)}<figcaption><b>{esc(p["en"])}</b>'
        f"<span>{esc(p['why'])}</span></figcaption></figure>"
    )


def home(props, wall: str) -> str:
    apps = "".join(
        f'<div class="app">{tile(uri(ROUND / p["folder"] / "icon.svg"), 56)}'
        f'<span lang="ar" dir="rtl">{esc(p["ar"])}</span></div>'
        for p in props
    )
    return f'<div class="wall {wall}">{apps}</div>'


CSS = r"""
/* Layout: a header, a home-screen strip on two wallpapers, a mask switcher, then one card per proposal. */
:root {
  --ground: #edf1f4; --surface: #ffffff; --ink: #1b2430; --muted: #5a6573; --line: #d9dfe6;
  --blue: #1f5fa8; --madder: #a8402e; --chip: #e3e9f0;
  --t-light-bg: #d7e8e2; --t-light-fg: #163a32; --t-dark-bg: #1f2f2b; --t-dark-fg: #a9d6c8;
  --text: "IBM Plex Sans Arabic", "Segoe UI", system-ui, sans-serif;
  --mono: "IBM Plex Mono", ui-monospace, Consolas, monospace;
  --mask: 50%;
}
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
  --ground: #12161b; --surface: #1a2027; --ink: #e8ecf0; --muted: #9ba6b3; --line: #2a323c;
  --blue: #7fb0ea; --madder: #e48a73; --chip: #232b34; color-scheme: dark; } }
:root[data-theme="dark"] {
  --ground: #12161b; --surface: #1a2027; --ink: #e8ecf0; --muted: #9ba6b3; --line: #2a323c;
  --blue: #7fb0ea; --madder: #e48a73; --chip: #232b34; color-scheme: dark; }
body { background: var(--ground); color: var(--ink); font: 15px/1.6 var(--text); }
.wrap { max-width: 1120px; margin: 0 auto; padding-inline: 16px; padding-block: 32px 72px; display: grid; gap: 32px; }
header { display: grid; gap: 10px; max-width: 72ch; }
h1 { font: 600 clamp(26px, 4.4vw, 38px)/1.15 var(--text); margin: 0; text-wrap: balance; }
.lede { margin: 0; color: var(--muted); }
h2 { font: 600 12px/1.2 var(--text); letter-spacing: .1em; text-transform: uppercase; color: var(--muted); margin: 0; }
section { display: grid; gap: 14px; }

.tile { width: var(--s); height: var(--s); border-radius: var(--mask); overflow: hidden; position: relative; display: inline-block; flex: none;
  box-shadow: 0 1px 2px rgb(0 0 0 / .12); transition: border-radius .25s ease; }
.tile img { position: absolute; width: 150%; height: 150%; left: -25%; top: -25%; max-width: none; }
.tile.themed i { position: absolute; inset: -25%; -webkit-mask: var(--m) center / 100% 100% no-repeat; mask: var(--m) center / 100% 100% no-repeat; }
.tile.themed.light { background: var(--t-light-bg); } .tile.themed.light i { background: var(--t-light-fg); }
.tile.themed.dark { background: var(--t-dark-bg); } .tile.themed.dark i { background: var(--t-dark-fg); }

.walls { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.wall { border-radius: 20px; padding: 20px 12px; display: flex; flex-wrap: wrap; justify-content: center; gap: 16px 4px; }
.wall.light { background: linear-gradient(155deg, #e4e9ee, #c9d3dc); color: #1d2630; }
.wall.dark { background: linear-gradient(155deg, #1d2329, #0b0e11); color: #e1e6ea; }
.app { width: 84px; display: grid; justify-items: center; gap: 6px; font: 500 11.5px/1.3 var(--text); text-align: center; }

.controls { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.controls span { color: var(--muted); font-size: 13px; margin-inline-end: 4px; }
.controls button { font: 500 13px var(--text); color: var(--ink); background: var(--chip); border: 1px solid transparent; border-radius: 999px; padding: 6px 14px; cursor: pointer; }
.controls button[aria-pressed="true"] { background: var(--surface); border-color: var(--blue); color: var(--blue); }
.controls button:focus-visible { outline: 2px solid var(--blue); outline-offset: 2px; }

.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 500px), 1fr)); gap: 16px; }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: 18px; padding: 20px; display: grid; gap: 14px; align-content: start; min-width: 0; }
.hero { display: flex; gap: 18px; align-items: center; }
.names { display: grid; gap: 2px; min-width: 0; }
.num { font: 500 12px var(--mono); color: var(--muted); }
.names h3 { margin: 0; font: 600 26px/1.25 var(--text); }
.en { margin: 0; font-weight: 500; color: var(--muted); }
.idea { margin: 0; font-size: 16px; }
dl { margin: 0; display: grid; grid-template-columns: max-content 1fr; gap: 6px 14px; }
dt { font: 600 11px/1.9 var(--text); letter-spacing: .08em; text-transform: uppercase; color: var(--muted); }
dd { margin: 0; min-width: 0; }
.ladder { display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end; padding-top: 12px; border-top: 1px solid var(--line); }
figure { margin: 0; display: grid; justify-items: center; gap: 4px; }
figcaption { font: 500 11px var(--mono); color: var(--muted); }
.sep { width: 1px; align-self: stretch; background: var(--line); }
.variants h4 { margin: 0 0 8px; font: 600 11px var(--text); letter-spacing: .08em; text-transform: uppercase; color: var(--muted); }
.vrow { display: flex; flex-wrap: wrap; gap: 14px; }
.variant figcaption { font: 400 12px/1.35 var(--text); max-width: 120px; text-align: center; }

.drops { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 300px), 1fr)); gap: 12px; }
.drop { grid-template-columns: auto 1fr; justify-items: start; align-items: center; gap: 12px; opacity: .85; }
.drop figcaption { font: 400 13.5px/1.45 var(--text); color: var(--ink); display: grid; }
.drop figcaption span { color: var(--muted); }
.next { background: var(--surface); border: 1px solid var(--line); border-radius: 18px; padding: 18px 20px; }
.next p { margin: 0; max-width: 75ch; }
footer { color: var(--muted); font-size: 13px; max-width: 80ch; }

@media (max-width: 640px) {
  .walls { grid-template-columns: 1fr; }
  .hero .tile.big { --s: 96px !important; }
  dl { grid-template-columns: 1fr; gap: 2px; } dd { margin-bottom: 8px; }
}
@media (prefers-reduced-motion: reduce) { .tile { transition: none; } }
"""

JS = r"""
const masks = { circle: '50%', squircle: '34%', rounded: '22%', teardrop: '50% 50% 50% 16%' };
const root = document.documentElement;
function setMask(name) {
  root.style.setProperty('--mask', masks[name]);
  document.querySelectorAll('[data-mask]').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.mask === name)));
  try { localStorage.setItem('icon-mask', name); } catch (e) {}
}
document.querySelectorAll('[data-mask]').forEach(b => b.addEventListener('click', () => setMask(b.dataset.mask)));
let saved = 'circle';
try { saved = localStorage.getItem('icon-mask') || 'circle'; } catch (e) {}
setMask(masks[saved] ? saved : 'circle');
"""


def main():
    props = DATA["proposals"]
    page = f"""<title>{esc(DATA['title'])}</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+Arabic:wght@400;500;600&family=IBM+Plex+Mono:wght@500&display=swap">
<style>{CSS}</style>
<div class="wrap">
<header>
  <h1>{esc(DATA['title'])}</h1>
  {''.join(f'<p class="lede">{esc(x)}</p>' for x in DATA['lede'])}
</header>
<section aria-label="On a home screen">
  <h2>On a home screen</h2>
  <div class="walls">{home(props, 'light')}{home(props, 'dark')}</div>
</section>
<section aria-label="Proposals">
  <div class="controls" role="group" aria-label="Launcher mask">
    <span>Launcher mask</span>
    <button type="button" id="mask-circle" data-mask="circle">Circle</button>
    <button type="button" id="mask-squircle" data-mask="squircle">Squircle</button>
    <button type="button" id="mask-rounded" data-mask="rounded">Rounded square</button>
    <button type="button" id="mask-teardrop" data-mask="teardrop">Teardrop</button>
  </div>
  <div class="grid">{''.join(card(p) for p in props)}</div>
</section>
<section aria-label="Dropped">
  <h2>Drawn, then dropped</h2>
  <div class="drops">{''.join(dropped(p) for p in DATA['dropped'])}</div>
</section>
<section class="next" aria-label="Next step">
  <h2>Next step</h2>
  {''.join(f'<p>{esc(x)}</p>' for x in DATA['next'])}
</section>
<footer>{esc(DATA['footer'])}</footer>
</div>
<script>{JS}</script>
"""
    out = ROUND / "gallery.html"
    out.write_text(page, encoding="utf-8", newline="\n")
    print(out, f"{out.stat().st_size / 1024:.0f} KB")


if __name__ == "__main__":
    main()
