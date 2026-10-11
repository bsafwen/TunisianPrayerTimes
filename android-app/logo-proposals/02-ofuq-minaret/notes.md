# 02 · صومعة الأفق — Minaret on the Horizon

**Idea:** A square Maghrebi minaret (صومعة) rises in cream from the ground, framed by the gold arc of the sun's day. The arc's two feet rest on a glowing teal horizon, and the sun sits on the arc's descending side at about Asr. Prayer (the minaret), Tunisia (its square Zitouna/Kairouan form) and the time of day (the sun on its path) read as one emblem: the phone version of the TV app's «أفق».

**Symbolism:**
- Square shaft, 20 dp wide with straight sides, running off the bottom of the tile: the Maliki minaret of Ez-Zitouna and Kairouan, square instead of the round Ottoman "pencil". It is not tapered and has no door at its foot, so it doesn't read as a lighthouse or a castle keep.
- Projecting cornice with two pointed corner merlons: the crenellated parapet of Tunisian minarets. The stepped Maghrebi merlon is reduced to a point, so it doesn't read as the square crenels of a rook.
- Slim lantern (8.6 dp, 43% of the shaft) with a pointed opening: the lantern the call is given from.
- Jamour finial in gold: a cupola, then a thin rod, then a small ball. It is the tower's only gold and ties the minaret to the sun. The rod lifts the ball off the dome, so the top no longer reads as a ball resting on a half-dome (a pawn or an avatar head).
- Shaft openings: a pointed-arch window under the cornice and, lower down, a pair of pointed arches side by side (the paired arch of Maghrebi minarets such as the Kasbah mosque in Tunis). The pair replaces the earlier lone sebka lozenge, so this tower no longer repeats proposal 01's lozenge motif.
- Gold arc: the sun's path from sunrise (left foot) to sunset (right foot). It is taller than it is wide, so it also echoes the horseshoe arch of the Kairouan arcades, the arch of the TV app's «أفق» identity.
- Gold sun at 26° up the descending side: 43% of the way from sunset back to noon. In Tunis at the equinox, Asr comes about 2 h 40 before the end of a 6 h afternoon (about 44%), so the sun stands at Asr, a prayer time on the day's path. It is cut out of the arc with 2.5 dp gaps on both sides, and the arc carries on past it to the sunset foot, so the day reads as a path and not as a progress bar. No five-prayer dots: at 48 px they wouldn't be countable next to a tower.
- Background: a teal dusk sky, darkest at the top, brightening to a glow just above the horizon (y 72), then a hard step down to darker teal ground. It has the mood of the TV banner's Maghrib sky in the phone palette.

**Palette:**
- #00453A: tile top and bottom edges. That is 1.52:1 against a #1B1F1E dark wallpaper, and lighter than #003A31, so the tile never goes near-black.
- #004D40: sky halfway between the tile top and the horizon (the phone app's primary teal).
- #0A7363: horizon glow just above the horizon, darkened from #0E7A69 so the gold arc feet reach 3.07:1 (the 3:1 guideline for graphics).
- #004A3D: ground right under the horizon, a 1.78:1 step down from the glow, fading to #00453A.
- #FFF8F0: cream minaret (the brand cream). 9.3:1 on #004D40 and 5.5:1 on the glow.
- #E2B845: icon gold for the arc, the sun and the jamour. It is the brand gold #D4AF37 raised for small sizes, shared with proposals 02 and 09. 5.2:1 on #004D40, 5.8:1 on #00453A, 3.07:1 on the glow. The brand #D4AF37 would only reach 2.74:1 on the glow, and fixing that would need a glow as dark as #076B5B, which flattens the horizon step to 1.59:1. Recommendation: adopt #E2B845 as the icon gold for the whole family, including 01.
- The foreground uses 2 colours.

**Why it beats the current icon:**
- It is Tunisian. A square minaret with pointed merlons, a lantern, a jamour, a pointed arch and a paired arch replaces the round Ottoman pencil minarets and the pointed dome.
- It says prayer and time, not only "mosque": the minaret stands for the prayer, and the sun at Asr on its arc above a horizon stands for the time of day.
- It fills the tile. Ink covers about 29% of the visible 72 dp square. The arc spans 58 dp wide and reaches y 22. The minaret runs 59 dp from the finial (y 30.9) to the tile edge, and the visual weight is centred at x 54.2, y 56.5.
- Every shape renders. The foreground has 2 paths with no strokes, fill-rule evenodd only for the tower's openings, and at most 2 decimals. The background is two plain rects (sky gradient down to y 72, ground gradient from y 72), so the hard horizon needs no pair of gradient stops at one offset, which imports more safely into Android Studio. No opening crosses the horizon seam: the generator asserts that every cut-out ends at least 1 dp above it (the paired arches end at y 70) or starts 1 dp below it.
- It ships a real monochrome layer, which the current icon lacks.
- It ties the phone app to the TV app's «أفق» redesign (horizon, dusk glow, horseshoe arch) and keeps the phone's teal and gold.

**Small sizes & themed icon:**
- 48 px (0.67 px per dp): the tower (13 px wide), the gold top, the arc (3 px thick) and the sun (7.5 px across) all read. The window shows as one opening and the paired arches as two small slots below it. The merlons show as two small points. Gaps: finial to the arc's inner edge 4.1 dp (2.7 px), sun to each arc end 2.5 dp, sun to the nearest merlon 6.8 dp. The lantern has 2.8 dp walls; the window and the paired arches are separated by 2.6 dp of wall and each arch has 5.3 dp of wall to the shaft edge.
- 24 px: a cream tower with a gold tip inside a gold arch, with the sun as a dot on its right shoulder. The openings become specks and the rod disappears, so the top reads as a dome with a dot, but the silhouette is still recognisable.
- Themed icon: the same shapes in one flat colour, with no alpha. The tower's openings are real evenodd holes. The sun is separated from the arc by real gaps, not by tone. The arc never touches the tower, so the tint shows three parts: arch, sun and minaret. The cupola is cut 0.8 dp above the lantern in this layer only, so the tint keeps a separate lantern and dome instead of one bullet-shaped tip (the gap shows as a thin line at 96 px; at 48 px it is half a pixel and nearly closes).
- No ground cue in the themed layer: short plinths under the arc feet were tried and read as serifs at 48 px, and a continuous horizon bar would cross the tower and form a cross in a single tint. The arc feet end in round caps at y 69.
- Safe zone: everything meaningful sits inside the 66 dp circle. The sun's outer edge is 31.9 dp from the centre and the arc's top 32 dp. The 4.7% of ink outside the circle is the tower's foot below y 85, which runs off the tile on purpose. No ink falls outside the 72 dp square. The sun at 26° also sits lower than the top-right corner where launchers draw the notification dot.

**Risks:**
- At large sizes, the sun above the short sunset-side stub of the arc can read as the dot of a letter «i» (a smaller gap under the sun was tried and made it worse). The tower inside the arch can also be read at a glance as a keyhole or a badge.
- The jamour rod is 1 dp wide, under the 2.5 dp detail guideline. It renders at 96 px and up; at 48 px it is a soft sub-pixel neck, and the ball then sits as a dot over the dome.
- A white tower with a domed lantern can still suggest a lighthouse, and pointed merlons a rook. Paired arches over a single window are also found on Romanesque bell towers; the merlons and the jamour argue for a minaret.
- The arc's feet are in the foreground and the horizon is in the background. Launchers that move the two layers separately (parallax, press animations) can shift the feet a little off the horizon.
- The arc is taller than a real sun path, so the tower fits under it. The sun's position is fixed at Asr: the icon can't follow the real next prayer.
- No room for the full jamour (three balls and a crescent) under the arc: the finial is a cupola, a rod and one ball.
- On masks smaller than the 66 dp circle, the tower's foot is cut. That is harmless, but the tower then looks cropped rather than grounded.
- Composition (b) was tried and dropped: a deep-teal minaret silhouette against a gold-rose dusk glow, with a half sun on the horizon. At 48 px its top vanished into the dark sky, the silhouette read as a castle, and its monochrome layer was weak.
- Minaret icons are common among prayer apps, although the square Maghrebi form is rare.
