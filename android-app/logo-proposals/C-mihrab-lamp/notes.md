# C · قنديل المحراب — The Lamp in the Niche

**Idea:** A bold Kairouan horseshoe mihrab in pale sand, with a deep teal niche. One gold Maghrebi lantern hangs on a single chain inside it, and a soft warm glow lights the half-dome around it. It shows the mosque at night: everything is quiet, and only the lamp is lit. The image is the niche and the lamp of the Light Verse (an-Nur 24:35), so its meaning holds all year, not only in Ramadan.

**How calm reads:**
- There is one light and one still object, centred in a symmetric arch. Nothing moves, points or competes for attention.
- The light fades slowly from warm gold to deep teal, so the niche has no hard edge. It feels like a mosque between prayers.
- The lantern hangs straight down. A plumb line is the opposite of a phone that rings and shakes. This is the app's promise: while you stand in the niche, nothing disturbs you. The calm makes that point, so the icon needs no mute symbol.
- The palette is warm and closely matched (sand, honey gold, teal). There is no white-on-black flash and no saturated accent.

**Symbolism:**
- **The niche and the lamp:** the Light Verse (an-Nur 24:35) compares God's light to a niche (مشكاة) that holds a lamp (مصباح). The verse goes on to say the lamp is lit from a blessed olive tree (زيتونة), and the olive tree is the name of the Zitouna mosque in Tunis. This gives the icon a scriptural reason and a Tunisian one. The verse number is 24:35 in the app's own Qaloun index ("later Madani" numbering, checked in `android-app/app/src/main/assets/quran/index.json`: surah 24, ayah 35), and also in Hafs. None of the verse's text appears in the icon.
- **Horseshoe arch on columns with capitals:** the mihrab of the Great Mosque of Kairouan (and of Zitouna). The arch curves back inward below its widest point, 12 dp under the arch centre. This is a Maghrebi form, not an Ottoman or Mughal one. The mihrab shows the direction of prayer, which also points to the qibla compass.
- **The lamp in the mihrab:** the lamp that hangs in the niche on prayer rugs (sajjada). It stands for prayer, for the night prayers (Isha, and Fajr with the app's wake alarms) and for light in the heart.
- **The lit window with a horseshoe top:** the lantern's glass panel repeats the shape of the niche. It is a mihrab inside the mihrab, the light inside the place of prayer.
- **The deep niche and its radial glow:** depth, stillness and the inward turn of prayer. It is made only from a radial gradient (no blur), so it stays VectorDrawable-safe.

**Palette:**
- `#004D40`: tile edge (brand teal)
- `#00695C`: tile centre, in a radial gradient behind the arch (light brand teal)
- `#F8EEDB`: arch, columns, sill and the lantern's lit window (pale sand, a warmer step from brand cream `#FFF8F0`)
- `#EDC659`: lantern and chain (honey gold, a little lighter than icon gold `#E2B845` so it stands out from its own halo)
- Niche: a radial gradient centred on the lantern at (54, 51.5), r 24. The stops are `#D8B65C` (0) → `#9A8746` (0.20) → `#3F6A50` (0.46) → `#0F4A3D` (0.80) → `#033D34` (1, deep teal). The edge stays clear of black, so the niche reads as teal, not as a hole.
- There are 3 foreground colours: sand, gold and the niche gradient.
- Contrast: the frame is 8.5:1 against the tile edge and 5.75:1 against its centre. The lantern edge against the halo is 3.7–4.0:1 at the dome, the bands and the finial, and 2.5:1 at its thinnest point beside the window. In round 1 these figures were 3.0–3.3:1 and 1.9:1.

**Geometry:** The arch has an outer radius of 24 dp and an inner radius of 18 dp around (54, 48), so the frame band is 6 dp. The sill is 4.5 dp tall and exactly as wide as the columns (x 34.88–73.12). The ink spans x 30–78 and y 24–80.5 (48 × 56.5 dp), and none of it falls outside the 66 dp safe circle. The base corners are 0.3 dp inside the safe circle and 3.3 dp inside a circular launcher mask. The arch top has 6 dp of margin in round masks. Foreground ink covers 43.4% of the visible 72 dp square, and the monochrome layer covers 22.4%. The lantern is 17.6 dp wide and 31 dp tall (y 34.2–65.2). The chain is 3 dp wide. The window is 9.1 × 9.3 dp, and each gold border around it is at least 2.26 dp.

**Small sizes & themed icon:**
- 48 px: you can read a sand horseshoe niche, a gold lantern with a crisp teardrop outline and a pointed dome, and a cream window. The halo is a warm haze that stops short of the lantern's edge.
- 36 px: the lantern stays a distinct gold shape on darker teal, and its window still shows as a light inside it.
- 24 px: the details merge into a sand arch with a gold drop of light and a pale dot in a dark niche. It still reads as an arch with a light in it, on both light and dark wallpapers.
- Monochrome: the frame and sill are solid, and the niche is an evenodd hole, so the launcher's tint shows through as the niche. The chain and lantern are solid, and the window is cut out of the lantern. It reads as a lantern hanging in an arch on both themed light and themed dark, at 48 px and at 24 px. The glow is lost (no partial alpha), so the themed icon is crisper and less atmospheric than the colour one.

**Round 2 changes (judges' notes):**
- The halo is tighter: the stops moved to 0.20 `#9A8746` and 0.46 `#3F6A50`. The lantern's sides now sit on darker teal, so its outline is crisper at 36–48 px.
- The window is 10% larger, so the light inside the lamp still shows at 36 px. The lantern is 6% wider so the gold borders stay at least 2.26 dp. The window keeps the frame's sand tone, because `#FFF8F0` would add a fourth foreground colour for only 1.55:1 against 1.42:1.
- The sill is cut to the width of the columns, and the whole group moves down 1 dp. The arch is better centred (bounding-box centre at y 52.25, was 51.25), and it reads more like a framed niche than a gate on a plinth.
- The chain is 3 dp wide (was 2.6 dp). This adds a little themed-icon ink.
- Two ideas were tried and declined (in `_var/R2c`, `_var/R2d`):
  - A sill running the full arch width (48 dp) only fits inside the safe circle if it sits 3 dp higher, which shortens the columns. At 48 px it read as a door on a threshold, more Moroccan and less like a niche.
  - A rectangular alfiz cannot surround a 48 dp arch inside the 66 dp circle. Even with the arch scaled to 78%, the frame's corners sat 37.6 dp from the centre, and 4.1% of the ink fell outside the safe circle. Circular masks cut its corners, the smaller arch lost presence, and the result read as a door in a door frame.

**Variants tried (in `_var/`):**
- Round 1 lanterns:
  - A: a shield-shaped window, which read as a badge or an acorn.
  - B: the glass mosque lamp of the prayer rugs. At icon size it read as a vase, and its flame read as a water drop.
  - B with a three-chain hanger: this added clutter.
  - F, the lantern: kept. Of the frame and niche options tried with it, the sand frame and the deep-teal niche edge were the calmest pair.
- Round 2: R2a, a column-width sill; R2b, an ivory `#FFF8F0` frame and window, a little colder for almost no gain in contrast; R2c, a full-width sill; R2d, an alfiz; R3b and R3c, a fuller lantern and a lower glow centre; R3d, the 3 dp chain, which is final. R0 is round 1's final design.
- `python gen.py` regenerates the final design, and the header of `gen.py` lists the settings for each variant.

**Risks:**
- The Maghrebi lantern (fanous) is strongly linked with Ramadan, so at first glance some users may see a Ramadan app rather than a daily prayer app. The Light Verse gives a reason that holds all year, but the pitch has to say so. The icon cannot show it.
- Outside the Maghreb, a horseshoe arch with a lantern is also the familiar mark of Moroccan riads, restaurants and hammam spas. The column-width sill makes it read more like a niche, but the risk remains.
- The calm depends on the halo. In the themed icon the halo disappears, and what is left is a plain lantern in an arch: clear, but less peaceful.
- The window reads mainly by hue (1.42:1 against the gold). It is bigger now, but at 24 px it is only a pale dot.
- The themed icon has 22.4% ink, just above the 22% floor. The base corners are only 0.3 dp inside the safe circle, which is legal but leaves no room to move the group lower.
- The icon shows the mosque and stillness, not time. Unlike the mihrab-clock proposal, nothing says "prayer *times*". The link to "my phone won't disturb me" goes through the mood (serenity), not through a symbol of silence.
