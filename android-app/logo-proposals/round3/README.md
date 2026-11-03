# Round 3: the owner's arch + minaret + mushaf idea, redrawn flat

The source was the owner's own illustration (2026-11-03): a blue ribbon forming an
onion arch, a square Maghrebi minaret with a crescent, and an open mushaf on a rahla.
Here it is redrawn as an adaptive icon. The illustration's gloss, its tassels and the
text on its pages are dropped. The pages carry abstract lines only, never letters.

| Folder | Look |
|---|---|
| `01-arch-cream` | Blue and navy on cream, wooden stand |
| `02-arch-blue` | White on Sidi Bou Said blue, wooden stand |
| `03-arch-cream-plain` | As 01, with a navy stand and cover (two colours only) |

All three share one geometry. `_gen/gen.py` writes every SVG. Re-run it after any
change, then the round 2 checks:

```
python -I _gen/gen.py
python -I ../round2/_tools/check_icon.py 01-arch-cream 02-arch-blue 03-arch-cream-plain
python -I ../round2/_tools/render_preview.py 01-arch-cream 02-arch-blue 03-arch-cream-plain
python -I ../round2/_tools/render_preview.py --sheet sheet.png 01-arch-cream 02-arch-blue 03-arch-cream-plain
```

## Round 3b (approved improvements, plus a silence hint)

Made by `_gen/gen2.py` (shapely). Compared with 01-03:

- The minaret is the Great Mosque of Kairouan's, squat as the real one: three
  square tiers, each narrower than the last, rounded merlons, a small dome, then the crescent.
- The ribbon is one stroke. A tapered tail rises into the arch's left leg, folds at the
  apex (navy to blue), comes down the right leg and runs along the ground under the minaret.
- The ribbon is one width everywhere. Its ends are soft; the apex is the only point.
- The minaret is sand-ochre (Kairouan stone) against Sidi Bou Said blue.
- The mushaf is a little taller, and the rahla is shorter, so it no longer reads as an X mark.

| Folder | Look |
|---|---|
| `04-kairouan-cream` | Blue ribbon, ochre minaret on cream |
| `05-kairouan-blue` | White ribbon, light ochre minaret on blue |
| `06-kairouan-silent-cream` | As 04; the left leg ends in a round mute badge instead of the tail |
| `07-kairouan-silent-blue` | As 05, with the mute badge |

The badge is attached to the ribbon, never to the minaret or the mushaf. The rule
against muting a religious element still holds.

```
python -I _gen/gen2.py
python -I ../round2/_tools/render_preview.py --sheet sheet-3b.png 04-kairouan-cream 05-kairouan-blue 06-kairouan-silent-cream 07-kairouan-silent-blue
```

## Round 3c (other silence cues, no speaker)

Made by `_gen/gen3.py`, which reuses gen2's shapes. All are in the blue palette.
Each cue sits on the ribbon or the floor, never on the minaret or the mushaf.

| Folder | Idea | Reads at |
|---|---|---|
| `11-bars-blue` | Voice-message bars outside the arch, shrinking to a dot at the door | 48 px and up (best) |
| `08-wave-blue` | The tail starts as a sound wave that calms into the arch | 64 px and up |
| `09-slider-blue` | The floor under the mushaf is a volume slider, knob at zero | 64 px; looks like a dot and a line below that |
| `10-ramp-blue` | The tail is the classic volume wedge, filled only at its tip | weakest: reads as a set square |

08, 10 and 11 reach further left, so their artwork is drawn about 14% smaller than 07's.

```
python -I _gen/gen3.py
python -I ../round2/_tools/render_preview.py --sheet sheet-3c.png 07-kairouan-silent-blue 08-wave-blue 09-slider-blue 10-ramp-blue 11-bars-blue
```

About 1.2% of the ink falls just outside the 66dp safe circle: the crescent's tip and
the tip of the ribbon tail. Every mask in the preview keeps both.

## Round 3d (the owner's refined drawing, merged with the fixes)

`12-owner-refined` is the owner's own redraw: bigger, with all ink inside the 66dp
safe circle, a calligraphic arch (thin at the apex, thick in the legs) and a larger
mushaf. `_gen/gen4.py` keeps its size, its arch and its mushaf, and changes:

1. The minaret is Kairouan's, drawn from a photo of the real one: a tall, slightly
   tapered base with three dark windows, a crest of small close-set merlons on each
   terrace, three blind arches on tier 2, an arched opening on tier 3, a cornice and a
   small dome. The base is about 1.2x taller than the real one so the tower stands by the
   arch. (The owner rejected gen2's minaret, with three big merlons, and variants of it.)
2. A 1dp gap separates the mute badge from the left leg.
3. The ground under the minaret is as thick as the legs (4.6dp, was 3.6).
4. The rahla's boards are thick and end flat on the ground band's line, so the ground is implied.
5. There is a blue version as well as the cream one.

| Folder | Look |
|---|---|
| `13-final-cream` | The owner's colours on flat cream |
| `14-final-blue` | White ribbon, light ochre minaret on blue (as 07) |

```
python -I _gen/gen4.py
python -I ../round2/_tools/check_icon.py 13-final-cream 14-final-blue
python -I ../round2/_tools/render_preview.py --sheet sheet-3d.png 12-owner-refined 13-final-cream 14-final-blue 07-kairouan-silent-blue
```

`sheet-3d-large.png` shows the photo, both icons large and at 96/64/48 px, and the themed icon.

### Owner's second refinement (sheet-3e.png)

The owner then reworked 13 into a version with thicker rahla boards and no floor, a slimmer
untapered base, big dark openings on the upper tiers, corner merlons, a wide cornice slab
and a long finial. 13 and 14 now follow it, with these fixes: a 1dp gap between the badge
and the left leg; the ground band as thick as the legs, with both end corners rounded;
stone merlons between the corner ones (the dark ticks read as a railing); a slight taper on
the base; a cornice only a little wider than the top tier; and a shorter, thicker finial.
`sheet-3e.png` shows the owner's version next to 13 and 14.
