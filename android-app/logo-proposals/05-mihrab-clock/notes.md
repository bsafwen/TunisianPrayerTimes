# 05 · محراب الوقت — Mihrab of Time

**Idea:** The front of the Kairouan mihrab (a horseshoe arch on two columns with capitals, standing on a base) drawn in ivory on teal, with a deep niche behind it. Its ribbed half-dome doubles as the top half of a clock face: tapered ribs fan out from a point low in the niche like hour lines, and two gold hands, set to 3:00, start from that same point.

**Symbolism:**
- Horseshoe arch: the curve runs past its widest point and returns inward (about 3.6 dp on the outside and 5 dp on the inside of each side) before it lands on the capitals. This is the Kairouan / Maghrebi arch, not an Ottoman or Mughal outline.
- Columns with capitals: the Kairouan mihrab is framed by two marble columns with capitals. Here each capital is 1.5 dp wider than its shaft on both sides, so the columns read as columns and the shape reads as a building rather than a keyhole or a clock case. The base is a plain step that grounds the shape.
- Ribbed half-dome: 4 tapered ribs and a short 12 o'clock mark fan out from the hub and widen from 2.5 dp to 5 dp where they meet the arch rim. The ribs recall the 24-rib dome over the mihrab bay of the Great Mosque of Kairouan. They also act as hour lines on a dial and as dawn rays, and the fan recalls the sundial in the mosque's courtyard, which was used to tell prayer times.
- Gold hands at 3:00 (minute hand on 12, hour hand on 3): about the time of Asr in Tunis in winter (roughly 14:45 to 15:20 from December to January). The hands stand for the official prayer times, which are the app's core job.
- Deep niche gradient: the depth of the prayer niche, which marks the direction of prayer (this also hints at the qibla compass).

**Palette:**
- #004D40: background edge (brand teal)
- #00695C: background glow behind the arch (brand teal, light)
- #FFF8F0: arch, capitals, columns, base and ribs (brand cream)
- #00342B → #001C17: niche hollow, a vertical gradient (deep teal)
- #D4AF37: clock hands and hub (brand gold)

**Why it beats the current icon:**
- It is specifically Tunisian. The horseshoe arch on columns with capitals comes from the Kairouan mihrab, and the ribbed half-dome recalls the ribbed dome over the Kairouan mihrab bay. The current icon uses generic round Ottoman pencil minarets and an onion-like dome.
- It says "prayer + time" in one shape. The current icon says only "mosque".
- The motif is much bigger: about 47 × 54 dp (x 30.5–77.5, y 25–79.5), against a small dome. No foreground ink falls outside the 66 dp safe circle. Its visual centre of mass sits at about (54, 52), slightly above the middle.
- It has no broken parts. The current crescent path has zero area and never renders.
- It has a real monochrome layer.
- It uses three foreground colours, all flat fills or simple gradients, and is safe to convert to a VectorDrawable.

**Small sizes & themed icon:**
- 48 px: the arch-on-columns outline, the dark niche and the gold 3:00 hands read clearly. The ribs read as tapering rays that widen toward the rim, not as thin gauge ticks. The capitals show as one-pixel steps. The minute hand ends 3.5 dp below the 12 mark, and the hour hand (10 dp long) ends about 3 dp from the right capital and well clear of the 2 o'clock rib, so nothing touches.
- 24 px: the ribs merge into texture. What remains is a light horseshoe gate with a gold L in a dark niche, which is still recognisable.
- Monochrome: the frame, ribs, 12 mark, hands and hub are solid, and the niche hollow is a see-through hole (evenodd), so the tinted launcher background shows through as the niche. A filled horseshoe with the ribs and hands cut out was tried in the first round and dropped: the solid slab loses the niche and reads as a tombstone or a bell.

**Risks:**
- An arched top with a dial in it can still read as a grandfather clock or a clock tower, and the 3:00 "L" is a very clock-like pose. The capitals, the pronounced horseshoe return and the wide base reduce this, but do not remove it.
- People outside the Maghreb may see an ornate doorway, not a mihrab.
- The ribs radiate from the hub, which sits 11 dp below the arch centre, so the 2 and 10 o'clock ribs meet the rim about 30° off square. They now enter the rim as straight wedges with no fillets, so this shows only as slightly uneven corners at large sizes. Aiming the ribs at the arch centre was tried and turned the half-dome into a cog wheel.
- At 24 px the ribs are lost, and at 48 px the capitals are only one pixel wide.
- 3:00 matches winter Asr only approximately; in summer Asr in Tunis is closer to 16:10.
- The hour hand on 3 puts a little more weight on the right half.
- The clock hands carry the gold, which leaves no gold for any other accent.
