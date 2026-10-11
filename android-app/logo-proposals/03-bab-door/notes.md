# 03 · باب — Bab (The Studded Door)

**Idea:** A Tunisian medina door: a green-teal arched leaf in a carved sandstone frame, standing on a stone threshold, its dark nail heads tracing the arch and jambs, with a crescent and a khatam star at its heart (on real doors these are laid out in nails). The app is the door you go through to the prayer, five times a day.

**Symbolism:**
- Arched door (slight horseshoe) on a threshold: the bab of the medina house and of the mosque, so the app is the way in to prayer. The threshold grounds it: the leaf sits on a step, so it reads as a door and not as an arched window or a tombstone.
- Sandstone frame (kadhel): the carved stone surround of Tunis medina doorways, kept plain (no joint detail) so it stays one clean shape. It is now 5 dp wide.
- 15 dark studs: the iron nail heads that are the signature of Tunisian doors. They follow the arch and the jambs the way the nail band does on a real door.
- Crescent (horns up) and khatam: the central motif that Tunisian door makers lay out in nails. The khatam is a true one, two 6 dp squares with one turned 45°, the 8-point star of the Kairouan friezes that the TV app «أفق» also uses. Both are drawn solid gold so they survive at small sizes. The 8-point star also keeps clear of the 5-point star of the national flag.
- Teal tile: the app's brand colour, glowing slightly behind the door.

**Palette:**
- `#00695C` → `#004D40` brand teal radial: background wall (centre to edge).
- `#00695C` medina green-teal: door leaf.
- `#DDB24E` sandstone gold: frame, threshold, crescent and khatam. It is a warm cousin of the brand gold `#D4AF37`.
- `#002A24` iron, a near-black teal: nail heads.
- Foreground total: 3 colours.

**Why it beats the current icon:**
- It is Tunisian. The studded medina door is one of the most recognised images of Tunisian architecture, while the current icon uses generic Ottoman pencil minarets.
- The motif is big. The door spans 39.6 x 58.2 dp, compared with the current icon's small dome.
- The crescent is a real filled shape. The current icon's zero-area crescent path never renders.
- It has a proper monochrome layer for Android 13+ themed icons.
- It stays on the app's teal-and-gold identity, and the saturated tile keeps a clear edge on the white Play Store page and on light wallpapers.

**Small sizes & themed icon:**
- 48 px: the gold arched frame on teal reads at a glance. The crescent and khatam stand out clearly from the leaf (gold on teal), and the studs (r 2.2 dp) still read as separate dots. The khatam's points do not resolve at this size, so it reads as a gold star-dot above the crescent.
- 24 px: the studs merge into a darker edge tone (shading, not noise). What stays is a gold arch on teal with a gold crescent spot. That is still identifiable, but by colour and shape only.
- Monochrome: the door silhouette in one tint, with the 15 studs, the crescent and the khatam cut out as holes. The door stops 1.5 dp above the threshold, so the step reads as its own bar and the shape stays a door, not a window. At 48 px the cut-out crescent and khatam read clearly, and the stud holes become a dotted rim.

**Risks:**
- The leaf is the same teal as the wall. The gold frame does all of the separating, so at a glance the image can read as "gold arch on teal" rather than "teal door in a wall".
- In the colour icon the threshold joins the frame, so the base reads as a closed frame at small sizes. Only the monochrome layer shows the step as a separate bar.
- The door nearly fills the circle mask: the threshold corners sit at 32.9 dp from the centre, right on the 33 dp edge. Nothing is clipped, but the circle crop is tight at the bottom.
- The khatam is only 8.5 dp wide. At 48 px and below it turns into a dot, so the crescent alone carries the religious meaning there.
- On a real door the crescent and star are made of nails. Here they are solid shapes, so the motif is less literal than the real thing.
- Teal and gold is the standard look of prayer apps, so this icon stands out from them less than the earlier blue version did. It relies on the door shape to be distinctive.
- Tested and left out: an inner arc of small studs (r 1.4 dp) around the crescent. It turned into noise at 48 px and crowded the crescent.
