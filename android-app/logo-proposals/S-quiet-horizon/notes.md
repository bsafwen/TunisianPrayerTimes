# S · الأفق الهادئ — The Quiet Horizon

**Idea:** Proposal 02's square Maghrebi minaret under the gold arc of the day, with one change: the arc is a volume line. It rises full from the horizon at sunrise, thins steadily as the day goes on, and stops with a small round end a short, deliberate pause before the sun at Asr. The day's noise fades to zero just as the prayer arrives.

**How silence reads:**
- First reading: a square minaret, the sun and a gold arc over a dusk horizon. It reads as prayer time, in Tunisia. At small sizes the arc also reads as a gold crescent around the minaret, which strengthens the prayer reading.
- Second reading: the arc is a line that gets quieter. It is full where it stands on the ground, thins to a thread over the minaret, and stops before it reaches the sun. It is the shape of a volume level turned down to zero. The empty stretch of sky between its end and the sun is the silence: the moment the ringer goes off.
- The sentence that makes it click: «The day's noise fades to nothing just as prayer time comes. That is the moment the app silences your phone.» In Arabic: «ضجيج النهار يخفت حتى يتلاشى لحظة يحين وقت الصلاة: عندها يُسكت التطبيق هاتفك».
- When presenting it, call it "a volume line that fades to zero at prayer time". Do not use music terms (decrescendo, hairpin): Tunisian users will not know them. The folder name keeps the old working title only.
- The silence is in the shape of the arc, not in a symbol laid on top. Nothing is slashed, crossed or muted. The minaret and the sun are untouched.
- Possible support for the idea (not used in the icon): a reviewer recalled the hadith "arihna biha ya Bilal" ("give us rest through it, Bilal", said of the prayer), cited as Sunan Abi Dawud 4985. It could not be checked here (sunnah.com refused the request). Verify the number and text on sunnah.com before quoting it anywhere.

**Symbolism:**
- Square minaret (from 02): a straight 20 dp shaft running off the tile, a cornice with two pointed corner merlons, a slim lantern with a pointed opening, a gold cupola, rod and ball (jamour), a pointed window and the paired Maghrebi arches. It is the Zitouna/Kairouan form, not an Ottoman pencil. The jamour rod is now 1.6 dp wide (was 1 dp), so the ball sits on it instead of floating.
- Gold arc: the sun's path from the sunrise horizon (left, east) towards sunset. It is 7.5 dp wide where it stands on the horizon, about 3.1 dp over the finial and 1.2 dp at its end, in one smooth fade with no steps (profile power 0.85, slightly fuller than linear). 90% of the extra width grows inward, so the outer curve stays close to 02's and the weight sits nearer the tower.
- Flat foot on the horizon (y 72): the day and its sound start from the ground at full strength. The flat cut also stops the arc from reading as a plain crescent at large sizes.
- Round end: the line keeps a minimum width of 1.2 dp and closes with a round cap at the angle of travel, 46° up the descending side. It ends on purpose instead of vanishing, so the pause before the sun has the same length at every size.
- The pause: 4.0 dp of empty sky between the round end and the sun's edge. It is the moment the phone goes quiet.
- Sun at Asr (26° up the descending side, as in 02), radius 6 dp (was 5.6): the prayer time. Nothing continues past it, which leaves the lower-right sky empty: the window of silence.
- Teal dusk sky and darker ground (from 02): the «أفق» mood of the TV app in the phone palette.

**Palette:**
- #00453A: tile top and bottom edges (never darker than #003A31, so the tile holds on dark wallpapers).
- #004D40: sky halfway down (phone primary teal).
- #0A7363: horizon glow just above the horizon. The gold foot stands on it at 3.07:1, and the gold reaches 5.2:1 to 5.8:1 higher in the sky.
- #004A3D: ground just under the horizon, fading to #00453A.
- #FFF8F0: cream minaret (5.5:1 to 10.4:1 against the sky).
- #E2B845: icon gold for the arc, the sun, the cupola and the jamour (the brand #D4AF37 raised for small sizes, shared with 02).
- The foreground uses 2 colours.

**Small sizes & themed icon:**
- Ink: 26.9% of the visible 72 dp square (high-contrast ink at 3:1 or more: 26.7%). Bounding box x 26.8-84.0, y 24.0-90. The arc's farthest point is 30.2 dp from the centre and the sun's edge 32.3 dp. The 5.0% of ink outside the 66 dp circle is the tower's foot running off the tile on purpose. No ink falls outside the 72 dp square. The high-contrast centroid moved from x 50.9 to 52.0 (centre 54): the composition is still a little left-weighted, which the sun on the right answers.
- 48 px (preview cell): tower, gold finial, arc and sun all read. The fade is plain to see: thick on the left, a thread at about half past one, a clear gap, then the sun. The 1.2 dp end is under one pixel here but still shows as a faint line, so the gap stays the same.
- On a real phone 48 dp is about 130-170 physical px (48 dp at 2.6x to 3.5x), so the 72 dp visible square is drawn at about 2 px per dp. There the 1.2 dp end is 2 to 3 px wide and the fade is far easier to see than in the 48 px preview cell.
- 24 px: a cream tower inside a gold crescent, with a gold dot. Prayer reads clearly. The fade softens into "a crescent that thins on the right", which the brief allows.
- Themed icon: one flat #000000 on transparent with no partial alpha. The tower's openings are evenodd holes and the cupola is cut 0.8 dp above the lantern, as in 02. The arc never touches the tower or the sun, so the tint shows three parts: the fading arc, the sun disc and the minaret. In one tint, the arc and the dot also recall the moon of the system's bedtime / Do-Not-Disturb mode: a quiet, accidental hint of silence, not the main idea.

**Changes in this round (judges' notes):**
- Weight: foot 8.8 to 7.5 dp, inward growth raised to 90% of the width, sun 5.6 to 6 dp. Centroid x 50.9 to 52.0, ink 27.0% to 26.9%. A thinner foot (7.0 dp, centroid 52.3) was tried and dropped: the fade got weaker at 48 px for little gain.
- Tip: minimum width 1.2 dp with a round cap and a fixed 4 dp pause before the sun (was a point that vanished below 64 px, leaving a 2.6 dp gap that grew with smaller sizes).
- Jamour rod 1 to 1.6 dp in both the colour and the monochrome layers.
- The duplicate vertex at the arc's tip is gone. The generator now drops repeated vertices in every polygon.
- Arc foot vs horizon on launchers that move the layers apart: risk accepted knowingly. Stopping the foot 0.5 dp above the horizon was rendered and dropped. At large sizes (Play Store, 96 px and up) it shows as a sliver of light teal under the foot, which looks like a near miss, and it would only hide shifts under 0.5 dp. Independent layer motion is brief and rare (launch and drag animations), and during it the foot simply sinks into or lifts off the horizon for a moment, like the tower crossing the horizon.

**Variants tried (gen.py and variants.py keep them):**
- Stepped radial bars: the most literal "volume going down", but they read as a dotted dial or loading spinner at 48 px and turned to noise at 24 px. Dropped.
- Fade then rise again after the sun (the ringer coming back): at a glance it read as 02 again and hid the fade. Dropped.
- Round thick foot: read as a blob or comet head. Replaced by the flat foot.
- Pointed tip (round 1): the end disappeared below 64 px and the gap changed with the size. Replaced by the round end.

**Risks:**
- Reading direction: an Arabic reader scanning right to left meets the thin end first and could read the arc as growing out from the sun. The sun anchors it (the arc is the day already travelled), and the explanation sentence fixes it, but the fade is not self-evident.
- Crescent: the thick left half can read first as a gold crescent with a disc beside it, which a reviewer may link to the crescent and star. It is not the flag: there is no red, the disc sits outside the curve, and it is the sun, not a five-pointed star.
- Comet or swoosh: a tapering arc next to a disc can suggest a comet, a sports swoosh or a weather or sunrise app at a glance. With the minaret present it never reads as a sleep or DND app.
- The silence is subtle by design. Without the one sentence most people will see "a minaret under a gold arc with the sun", not a muted phone. The owner asked for a discreet cue, but it does depend on being told once.
- Still slightly left-weighted (centroid x 52.0, not 54).
- Inherited from 02: the tower can suggest a lighthouse or a rook, and the sun is fixed at Asr.
- The arc is a 160-point polyline with one arc command for the cap. It is fine for a VectorDrawable but cannot be hand-edited like 02's arcs: change it through gen.py.
