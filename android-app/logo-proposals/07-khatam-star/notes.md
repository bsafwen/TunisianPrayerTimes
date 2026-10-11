# 07 · خاتم — Khatam Star

**Idea:** A flat stone-gold khatam, the eight-pointed star of two squares, on the app's own teal. A clock dial is cut into the star's centre and set to 3:00, with the hour hand on one of the star's axes. Prayer times sit inside the ornament found on Tunisian walls.

**Symbolism:**
- Eight-pointed star of two squares, one turned 45°: the khatam of Maghrebi zellige and carved stucco friezes, from Kairouan to the Tunis medina. It is also the motif the «أفق» TV app is built on (ui/theme/Motifs.kt: the khatam frieze, the khatam keystone over the mihrab, and the Blue Qur'an verse medallion, which is a khatam around a disc). The icon is that medallion made solid, with the disc opened into a dial.
- Teal lines cut into the gold: the upright square drawn as a complete outline, and the turned square shown only in its four points (V cuts). The upright square reads as lying over the turned one, a simple form of the interlaced band with no over-and-under breaks.
- Central dial at 3:00: the minute hand is on 12 and the hour hand points right along the star's 90° axis, so the time is real and the clock follows the star's geometry. 3:00 is near Asr in a Tunis winter. The dial stands for prayer times and the Fajr alarm.
- The upright point and the four cardinal points also suggest a compass rose, which fits the qibla compass.

**Palette:**
- `#00695C` → `#004D40`: background, a radial gradient that is lighter just above the dial. These are the phone app's two teals, so existing users keep their colour cue.
- `#D9B45C`: star and clock hands, one flat tone. It is the «مداد» stone gold of the TV app and sits close to the phone gold `#D4AF37`.
- The foreground is a single gold fill. Every teal line inside the star is a real cut-out that shows the background layer.

**Why it beats the current icon:**
- It has a strong, single silhouette that spans 62 dp. The current dome-and-minarets motif is small and thin, and its round pencil minarets are Ottoman, not Tunisian.
- It brings prayer times and the qibla compass together under the khatam, a Maghrebi motif, and does not look like a government emblem.
- It keeps the phone app's teal and gold, and takes its star and its gold from the «أفق» TV app, so the two apps clearly belong together.
- Every shape renders. There is no zero-area crescent path, every cut-out is a true evenodd hole, and the clock shows a time that can actually occur.
- It ships a proper monochrome layer for Android 13 and later themed icons.

**Small sizes & themed icon:**
- 48 px: the star, the square outline, the V cuts in the four points, the dial and both hands all read. The cut lines are 2.5 dp (about 1.7 px), and the hour hand is visibly shorter than the minute hand.
- 24 px: the inner square is now solid gold around the dial, with no diagonal cuts in its corners, so far less line work turns into texture. What remains is a gold eight-pointed star with a dark dial, easy to identify. The hands shrink to a small L mark.
- Themed icon: the monochrome layer is the foreground geometry in flat black. The cut-outs (square outline, V cuts, dial) carry the design without colour, and the hands stay solid inside the dial hole. It reads cleanly at 48 px on both light and dark themes.

**Risks:**
- The eight-pointed star is common across Islamic apps (Rub el Hizb marks and badge-style icons). The teal-and-gold pairing and the dial do most of the differentiating.
- A clock in a frame is close to a generic "schedule" glyph. The khatam around it is what keeps it from looking like a stock icon.
- With the corner cuts gone, the inner square is a large flat gold area. At 96 px and above it reads as a square plaque on the star more than as a full interlace.
- Below 32 px the hands become a small mark, so the clock depends mostly on the dial hole.
