# Round 2 icon files

Each proposal lives in `round2/NN-slug/` and holds:

| File | Content |
|---|---|
| `background.svg` | The background layer, 108x108, fills the whole canvas. Flat colour or linear/radial gradient. |
| `foreground.svg` | The artwork layer, 108x108, transparent where the background shows. |
| `monochrome.svg` | The themed-icon layer: the same artwork as one flat `#000000` on transparent. No gradients, no opacity. Holes are cut with `fill-rule="evenodd"`. |
| `icon.svg` | Background and foreground merged into one file (for the gallery and Play export). |
| `notes.md` | Idea, how silence reads, how prayer reads, palette, small sizes, themed icon, risks. |
| `preview.png` | Made by `python -I ../_tools/render_preview.py .` |
| `gen.py` (optional) | A generator, when geometry is computed. |

## Canvas

- `viewBox="0 0 108 108"`, `width="108" height="108"`.
- Only x/y 18..90 (the 72dp square) is ever visible. Masks keep at least the circle of radius 33 around (54, 54).
- Keep all meaningful ink inside the 66dp circle. A background element or a deliberate bleed may run off.
- Visual centre: optical weight near (54, 54). Leave breathing room; most good icons use 40-60% of the visible square for the main shape.

## VectorDrawable safety

Android Studio's SVG importer must convert every file. Use only:

- `<svg>`, `<g>` (with `transform` if needed), `<path>`, `<rect>`, `<circle>`, `<ellipse>`, `<polygon>`, `<polyline>`, `<line>`.
- `fill`, `fill-rule`, `stroke`, `stroke-width`, `stroke-linecap`, `stroke-linejoin`, `opacity`/`fill-opacity` (colour layers only).
- `<linearGradient>` / `<radialGradient>` in `<defs>` with `gradientUnits="userSpaceOnUse"` (colour layers only).

Never use `filter`, blur, `mask`, `pattern`, `image`, `text` (draw letters as paths), `use`, `symbol`, `style` blocks or `class`. Prefer fills to strokes; if you use strokes, the monochrome layer may keep them.

## Checks

```
python -I ../_tools/check_icon.py .
python -I ../_tools/render_preview.py .
```

`check_icon.py` prints problems and the ink shares. `render_preview.py` writes `preview.png`: the canvas with guides, four masks, the themed icon, and the icon at 96/64/48/36/24 px on a light and a dark wallpaper. Look at it with the Read tool after every change.

## House rules (from the owner)

- Never slash, cross out, mute or "turn off" a religious element. No bells. No dark phone slab with an arch and a crescent (reads as a headstone).
- Western digits only. Arabic letters as custom geometry, no vowel marks.
- Arabic text in notes: plain, no vowel marks unless copied from a checked source.
