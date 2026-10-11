# 04 · كوفي — Kufi

**Idea:** The word «صلاة» built in square Kufic (al-kufi al-murabba') on a 13 x 11 grid where every band and every gap is one unit (4dp), set in gold on the app's teal. The app's subject written as a tile-panel word, not as a picture.

**Symbolism:**
- ص (right): a wide closed loop on the baseline, with its tooth as a 1-unit rise on the loop's left wall. The tooth is kept short so the letter cannot read as ط.
- لا (centre): lam and alif as two full-height verticals joined at the base, the usual square-Kufic lam-alif. The baseline from ص runs into the lam's foot, so «صلا» is one connected stroke. The two upright bars are the strongest shape in the icon and also suggest someone standing in prayer (qiyam).
- ة (left): stands alone, because alif never joins forward. It is a closed upright loop with its two dots as two 1-unit squares above it. The dots are what make the word read as «صلاة».
- Square Kufic on an equal grid comes from tile and brick panels of the kind found in Kairouan and Maghrebi interiors. It also matches the Kufic touches in the sister TV app «أفق».

Grid (`#` = ink, `.` = gap; read right to left; row 0 is the top):

```
col  0 1 2 3 4 5 6 7 8 9 10 11 12
r0   . . . . # . # . . .  .  .  .     alif | lam
r1   . . . . # . # . . .  .  .  .
r2   . . . . # . # . . .  .  .  .
r3   . . . . # . # . . .  .  .  .
r4   # . # . # . # . . .  .  .  .     dots of ة
r5   . . . . # . # . . .  .  .  .
r6   # # # . # . # . . .  .  .  .     top of ة
r7   # . # . # . # . # .  .  .  .     tooth of ص
r8   # . # . # . # . # #  #  #  #     top of ص loop
r9   # . # . # . # . # .  .  .  #     counters
r10  # # # . # # # # # #  #  #  #     baseline (ة stays apart)
```

On the canvas the block spans x 28.5-80.5 and y 29-73 (52 x 44dp). It is moved 0.5dp right and 3dp up so that the bottom-heavy baseline sits on the optical centre. The farthest corner (bottom right of ص) is 32.6dp from the centre, inside the 33dp safe circle. Everything is one evenodd path, and all coordinates are whole or half dp.

**Palette:**
- `#005F53` → `#00443A`: radial teal background, lighter just above the centre. This is the brand teal (#00695C / #004D40), darkened a little for contrast.
- `#F0D06A` → `#D4AF37`: vertical gold gradient on the word, top to bottom. It ends on the brand gold #D4AF37.
- Contrast: the darkest gold on the lightest teal is 3.6:1, and the typical pairing is 5 to 6.5:1.
- Monochrome: `#000000` on transparent.

**Why it beats the current icon:**
- It says what the app is about, prayer, in a script tied to North African tile work. The current icon is a generic dome between two Ottoman pencil minarets.
- The motif is about 52 x 44dp instead of a small dome, and it fills the safe circle.
- Every shape renders: no zero-area crescent, no strokes, no opacity tricks.
- It has a real monochrome layer.
- It is made only of 4dp orthogonal bands, so it stays crisp on the pixel grid at every density.

**Small sizes & themed icon:**
- 48px: one unit is about 2.7px. All three letters, both ة dots and the counters are readable.
- 24px: one unit is about 1.3px. The counters nearly close up, but the silhouette stays recognisable: two tall bars over a block word, with a short block on each side.
- Themed icon: the monochrome layer is the same single shape. The word is already pure cut-outs (counters and gaps), so it reads the same when tinted on light or dark.

**Risks:**
- It is a word, not a picture. Users who don't read Arabic only see an abstract gold glyph, though the two tall bars still make it memorable.
- Square Kufic is shared across the Islamic world and is best known from Persian brickwork. The Tunisian link is a style and heritage reference, not a unique national marker.
- The empty space above ص leaves the composition slightly heavy on the left. Nothing can go there, because a mark above ص would read as ض.
- At 24px the 4dp counters (the slit inside ص and the slot inside ة) fill in, and the word becomes a texture.
- Readers who know Kufic may expect a longer, flatter ص. A 6-unit loop was tried, but it made the bands thinner and the block less balanced.
