# 01 · صومعة الزيتونة — Zitouna Minaret

**Idea:** The square minaret of the Ez-Zitouna Mosque in the Tunis medina, drawn as one bold cream tower on the app's teal and capped with a gold cupola and jamour finial. A square Maghrebi minaret says "Tunisia" before any text does, which the current round Ottoman-style minarets never did.

**Symbolism:**
- Square shaft (26dp wide): the Maliki/Maghrebi minaret of Zitouna, the Kasbah mosque and Kairouan, the opposite of the round "pencil" minaret.
- Three negative-space lozenges (one whole, two halved by the tower edges): the sebka lozenge net carved on the Zitouna faces. The halves suggest that the net carries on past the edges.
- Pointed horseshoe arch at the foot: the Almohad and Hafsid arch, built from two true circular arcs that swell past the jambs. It also reads as the mosque door.
- Projecting band with four pointed merlons (two on each side of the lantern): the crenellated parapet. The stepped Almohad merlon is reduced to its pyramidal point so it survives at small sizes and doesn't look like a castle's square crenels.
- Slim lantern (about 1/3 of the shaft width) with a small pointed window: the jamour lantern the call is given from.
- Gold cupola with two stacked brass spheres: the jamour finial and the only accent colour, which marks the top of the icon.
- Soft radial glow behind the lantern: light around the minaret at the hour of the call. It is only a background tone, never a shape.

**Palette:**
- `#004D40`: background edge (brand teal)
- `#00594D`: background mid-tone
- `#00796B`: glow centre behind the lantern
- `#FFF8F0`: tower (brand cream)
- `#D4AF37`: cupola and finial (brand gold)

The foreground uses 2 colours.

**Why it beats the current icon:**
- It is recognisably Tunisian. Square tower, sebka lozenges, horseshoe arch and pointed merlons all come from the Tunis medina, where the current icon has generic round minarets and an onion-like dome.
- It is one motif filling the safe circle: 62.5dp tall × 29dp wide, against small scattered pieces now.
- No broken geometry. The current crescent path has zero area and never renders.
- It has a real monochrome layer. Every detail is a true cut-out (`evenodd` holes) or a separate silhouette, so nothing depends on colour contrast.
- It is VectorDrawable-clean: 1 rect and 5 paths, one radial gradient, no strokes, every path value with at most 2 decimals.
- 0% of the ink falls outside the 66dp safe circle. The base corners sit at radius 32.7.

**Small sizes & themed icon:**
- 48 px: the silhouette, the four merlon points, the central lozenge, the door arch and the gold cap all read. The half-lozenges and the lantern window shrink to accents.
- 24 px: a cream square tower with a gold tip and a notched crown. The lozenges become specks, but the tower is still clear.
- Themed (monochrome): the silhouette is the same as the colour icon. The lozenges, door and lantern window are holes, so the tint shows them. The gold cupola merges with the lantern, but it still reads as a cap because it is 0.9dp wider than the lantern on each side.

**Risks:**
- A crenellated tower can be read at a glance as a castle, a chess rook or a lighthouse. The lantern, cupola, pointed merlons and lozenges reduce this but don't remove it.
- It abstracts what makes Zitouna itself unique (the two-tone ochre stone, the full sebka net), so many people will read "a Maghrebi minaret" rather than Zitouna specifically.
- The shaft is chunkier than the real slender tower, a trade-off for presence at 48 px.
- The finial spheres are only 3-4dp and vanish below about 36 px.
- The gold cupola is a stylisation: the real lantern dome is not gilded.
- A tall, narrow motif leaves plain teal on both sides of the mask.
- Minaret icons are common among prayer apps, although the square Maghrebi form is rare.
