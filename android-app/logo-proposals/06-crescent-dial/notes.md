# 06 · هلال المواقيت — Crescent Dial

**Idea:** A bold ivory crescent whose missing part of the circle is completed by five evenly spaced marks, so the moon becomes a dial of the day's five prayers; the middle mark is larger and gold: the next prayer.

**Symbolism:**
- Crescent: the lunar (Hijri) calendar, night and dawn, the universal sign of Islamic time-keeping. It also echoes the Ramadan and Eid detection the app does.
- Five marks on the moon's own circle: the five daily prayers (Fajr, Dhuhr, Asr, Maghrib, Isha). They sit on the line the moon's full disc would have drawn, so the crescent and the marks read as one clock face.
- The gold, larger mark: "the next prayer", the app's main promise (countdown, silent mode, Fajr wake alarm).
- Night-sky background lit from the upper right, behind the gold mark: the sky brightening toward the next prayer.

**Palette:**
- #EEE7D8 ivory (Midad): crescent and the four regular marks
- #D4AF37 gold (phone-app brand gold): the next-prayer mark
- #00695C → #004D40 → #062A27 radial gradient (brand teal down to deep ink-teal): background

**Why it beats the current icon:**
- One strong silhouette instead of several separate small pieces (the current minarets are only 6dp wide and the star about 12dp): the crescent alone spans about 50dp, the whole motif about 53dp (x 29-82, y 26-80).
- It says what the app does (time, five prayers, what comes next), not just "mosque". No generic Ottoman pencil minarets or dome.
- Nothing broken: the current crescent path has zero area and never renders. Here every shape is a filled path that converts to a VectorDrawable as is.
- It ships a real monochrome layer for Android 13+ themed icons.
- Optically centred: the ring is nudged 3.8dp toward the opening so the heavy back of the crescent and the light dots balance (ink centroid near 51,56 and bbox centre near 56,53 average out at the canvas centre).

**Small sizes & themed icon:**
- 48px: the crescent is solid and unmistakable; the five marks still read as separate dots (spacing 2.5-2.9dp gaps), and the gold one is visible.
- 24px: the crescent alone carries the icon; the dots merge into a faint dotted arc that still suggests the "dial" without becoming noise.
- Tips are rounded (r1.9dp) and the crescent is 16.5dp at its thickest, so it never shrinks to a sliver.
- Monochrome: the same crescent plus five dots in one colour; the next-prayer mark is told by size (r4.8 vs r2.9) rather than colour. It reads cleanly on both light and dark themed backgrounds.

**Risks:**
- Not specifically Tunisian: a crescent is pan-Islamic, and this concept has no Kairouan or Zitouna reference. It leans on the app name for its Tunisian identity.
- A crescent with a trail of dots can remind some people of a loading spinner; the uniform dot size and the single gold mark limit this, but it is not eliminated.
- Crescent icons are common in the prayer-app category on the Play Store; distinctiveness rests on the dial idea, which is subtle at 24px.
- In the themed icon, "next prayer" depends on a small size difference that may go unnoticed.
