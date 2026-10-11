# 09 · مزولة — Sundial

**Idea:** The marble sundial (mizwala) of a Tunisian mosque courtyard, used to read Dhuhr and Asr from the sun. The best-known one is the 19th-century marble dial in the courtyard of the Great Mosque of Kairouan (dated 1258 AH, about 1842 CE). The icon shows a half-disc dial plate on a plinth, a rod gnomon with a ball nodus, the sun on one side and the gnomon's dark shadow lying across the plate on the other side, crossing the gold Asr curve. The prayer time is read from the sun, which is what the app does.

**Symbolism:**
- Ivory half-disc plate seen slightly from above, on a straight stone plinth: the engraved marble slab of a mosque sundial, lying in a courtyard.
- Two hour lines cut into the plate (the teal background shows through them), tapering into the gnomon's foot: the fan of hour lines that every line on the dial shares. The noon line is hidden behind the rod.
- Gold rod gnomon (shakhis), 3 dp wide, with a lit face and a shaded face, topped by a ball nodus: the vertical style of a Maghrebi horizontal dial, whose tip marks the time.
- Dark shadow wedge from the gnomon's foot to the plate's rim along an afternoon line: the shadow is the dial's "hand" and the icon's main mark.
- Dark-gold curve on the afternoon side, passing under the shadow: the Asr line. This makes the sundial a prayer instrument rather than a garden sundial.
- Gold disc at upper left: the sun, on the opposite side from the shadow, so the cause and the effect are both in the picture.

**Palette:**
- #00796B → #004D40 background, vertical teal gradient (brand teal). It also shows through the hour lines.
- #FFF8F0 → #EFE2C6 marble plate, cream to light gold (brand cream / light gold)
- #D6C197 plinth front (stone, the ivory's shade facet)
- #E2B845 the single gold: sun, ball nodus and the lit face of the rod (brand gold #D4AF37 lifted for contrast on teal)
- #B8860B gold shade facet (brand accent): the shaded face of the rod and the Asr curve
- #0A3B33 shadow wedge
- That makes 5 foreground tones in 3 families (ivory, gold, ink). The edge strip and the extra hour-line colour are gone.

**Why it beats the current icon:** It says "prayer time from the sun" instead of the generic "mosque" that every prayer app uses, and it has no Ottoman pencil minarets or onion dome. The reference is local and historical (the Kairouan and Zitouna courtyard sundials), not a cliché. The motif is about 57 dp wide and 43 dp tall, against the current small dome. Every shape renders, unlike the zero-area crescent path in the current icon. It ships with a real monochrome layer.

**Small sizes & themed icon:** At 48 px four shapes read: the gold sun, the white dial on its plinth, the dark shadow wedge and the gold pin of the gnomon. The hour lines and the Asr curve stay visible as engraving. At 24 px the hour lines and the Asr curve fade into the plate. What remains is a sun dot, a white mound with a dark wedge and a thin gold pin. That still reads as a sun and a dial, though the gnomon is down to about 1 px. In the monochrome layer the plate is split by a 2 dp channel around the rod. The shadow wedge is a cut-out from the foot to the rim and the two hour lines are cut-outs. The rod stands on the solid plinth and the sun stays a solid disc, so without colour it still reads as sun + gnomon + shadow + dial. The Asr curve is left out of the monochrome layer because it would only add noise at themed-icon sizes.

**Risks:**
- A half-disc with a dark wedge from its centre and a curved line can read as a gauge or speedometer. The sun and the ball-topped gnomon carry the sundial reading, but at a glance the dial-and-needle reading is there.
- Sundials are not a widely known symbol. Many users will see "sun + dial" without knowing the Kairouan mizwala, so the Tunisian specificity lives in the story more than in the picture.
- The 3 dp rod is thin by design (faithful to a mizwala). At 24 px it is about 1 px, and its lit half is low-contrast on the ivory plate. The shaded half and the ball keep it visible.
- The shadow's direction is stylised. The wedge widens toward the rim, which a real rod's shadow does not. The ball nodus has no separate shadow spot, and the shadow is drawn longer than the Asr point to make it the main mark.
- It is still 5 foreground tones, more than the 3 the rules prefer. They read as 3 families, but a strict reading would drop the plinth tone or the gold shade.
- The nodus is r 3 dp rather than the suggested 2.5 dp, so that it still reads at 48 px. Next to the r 8 sun this gives two gold discs, which are told apart only by their size.
