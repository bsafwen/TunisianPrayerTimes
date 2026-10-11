# 10 · حضن الهلال — Crescent Embrace

**Idea:** Tunisia's silhouette, in cream, held from the west and the south by a gold crescent on teal. A square, Kairouan-style minaret rises from the crescent's belly. Together they say "prayer, for Tunisia, and nowhere else".

**Symbolism:**
- **The country.** The silhouette is drawn as a designed shape with 24 smooth nodes. It is based on a trace of the app's own `delegation_boundaries.json` (geoBoundaries ADM2), which covers every delegation the app serves. It keeps Cap Bon, the Gulf of Tunis, the Gulf of Hammamet, the Sahel bulge at Ras Kaboudia, the Gulf of Gabès, Djerba and the Saharan tip.
- **The crescent.** It stands for the Islamic frame and the lunar (hijri) calendar the app follows. It opens towards the north-east, the way the country faces the sea. Wrapped around the country, it reads as the app looking after the whole of Tunisia. Its lit side faces south-west, the usual orientation of the crescent emblem. It is not meant as the evening new moon that starts Ramadan or Eid, which in Tunisia is lit on the lower right.
- **The minaret.** It is the prayer cue: the call to prayer, the times the app announces. Its three stacked square tiers and small dome follow the Great Mosque of Kairouan, so the tower is a Tunisian Maliki minaret, not a generic Ottoman pencil one. It also echoes the Kairouan touches in the sister TV app «أفق».
- **What is left out.** There is no star and no red, so the icon cannot be mistaken for the flag.

**Palette:**
- `#00695C` → `#004D40`: background, a radial teal gradient centred slightly above the middle (the existing app teal).
- `#D4AF37`: crescent and minaret, one flat gold (the app's brand gold). The minaret and crescent therefore join without a seam.
- `#FFF8F0`: the country and Djerba (the app's cream).
- `#000000`: the monochrome layer.

**Why it beats the current icon:**
- It is unmistakably Tunisian twice over: the country's shape and a Kairouan square minaret. The current dome between Ottoman pencil minarets could belong to any country, and its minarets are the wrong type for Tunisia.
- The motif is large: the ink spans 42 × 58 dp (x 31.8–74.0, y 24.5–82.5) and the country alone is 50 dp tall. The current hero is a 28 × 42 dp dome flanked by thin minarets.
- Every element renders. The current crescent path has zero area and never shows.
- It has a real monochrome layer.
- It survives every launcher mask. All ink sits inside the 66 dp safe circle: Cap Bon reaches 32.1 dp from the centre and the crescent 29.6 dp.
- It is optically centred. The visual mass sits at (53.9, 55.1) and the ink bounding box is centred at (52.9, 53.5).
- The outline is clean. The old 70-vertex trace showed 3–7 px nicks at 512 px. The new outline uses smooth cubic curves with no wiggle smaller than about 1.5 dp, and every coordinate has at most 2 decimals.

**Small sizes & themed icon:**
- **Gaps.** The country is at least 3.6 dp from the crescent and 3.8 dp from the minaret. Djerba is 2.3 dp from the mainland.
- **48 px.** The country is about 33 px tall. Cap Bon's hook, the Gulf of Gabès notch, Djerba as a dot and the Saharan point all read. The minaret is a clear gold tower about 4–5 px wide on the crescent. Its tier steps blur into a tapered top.
- **24 px.** It becomes a cream wedge cradled by a gold arc with a small spike. The coastline detail and the Djerba channel fill in, but the "moon, tower and land" figure is still recognisable.
- **Monochrome.** The crescent and minaret merge into one flat shape, as they already do in colour, and the country stays a separate shape. Nothing depends on colour contrast, so the themed icons read as well as the colour icon.

**Risks:**
- **It may look official.** A country map can read as a state service. The icon has no flag, emblem or star and uses the app's own teal and gold. Even so, do not put «الرسمي» or similar wording next to this icon in the store listing: Google Play's policy forbids implying government affiliation. Say instead that the times come from the INM.
- **The minaret stands outside the border.** It sits in the pocket west of the Algerian border, so a viewer reading it as a map could place it in Algeria. It is meant as part of the crescent emblem, not as a map pin.
- **Fine detail is lost when small.** The minaret's tier steps are only 0.8–1.1 dp each, so below about 64 px it is just a tower.
- **Cartographic licence.** Djerba is enlarged and given a 2.3 dp channel, Kerkennah and La Galite are omitted, small lagoons are filled, and the coast is simplified to 24 nodes.
- **It names nothing directly.** At 24 px and below, the silhouette is just a blob. Someone who does not already know Tunisia's shape sees only "a crescent, a tower and a shape".
- **The template is common.** Map plus crescent is a familiar layout. The Kairouan minaret helps, but the icon is still less distinctive than a constructed mark.
- **Overlap with 06.** On purpose, this version does not add five prayer dots, so it stays clearly apart from 06 «هلال المواقيت», which uses a fat ivory crescent with dots. Here the crescent is thin, gold and wraps a map.
