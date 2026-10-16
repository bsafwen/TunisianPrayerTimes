### Mayotte: the traditional table (`yt_traditional`)

Not fitted. `fits/yt_traditional/fit.py` (2026-10-15) only writes the plan's zone, `official-zones/yt_traditional.json`.

**Method** (the plan's, unchanged, 7.6): the Moroni table less 3 min on the five prayers: Fajr = sunrise − 76,
Dhuhr + 1, Maghrib = sunset, Isha = Maghrib + 74, Asr = Maghrib − 168 (E15), nearest minute.

**Zone.** One zone, `mayotte`, «مايوت», at Moroni's point (−11.70, 43.25, `km_fitted`'s national point, 8.7), mapped
`NATIONAL`, with `sunriseAtPlace` (6.5): every time but the sunrise row comes from Moroni's point, which is what makes
the method the Moroni table; at the user's own point the times would be about 8 min earlier in Mamoudzou. No FitStats,
so the chip stays «مستخلصة من جداول» (Q36).

**Data.** None at hand: test-data-local holds no Mayotte table (the 2026 table of horaires-priere-mayotte.fr, read on
2026-10-11, was not saved) and no Moroni table (`research-2026-10-08/SD-SO-DJ-KM/km` has a script only). The owner's
mosque dataset is not a source (5.6).

**Open.** Save the site's 2026 table under test-data-local (or a printed calendar of the Conseil cadial, Q36) and score
it here (9.2: within 2 min on at least 90 % of days per prayer, never more than 4 off; its sunrise column not compared).
If the Comoros fit (`km_fitted`) moves Moroni's point, this zone moves with it.
