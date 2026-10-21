#### UAE (`ae_awqaf`)

`fits/ae_awqaf/fit.py` (about 4 minutes; `--refit` about 8) fits the tables of the General Authority of Islamic
Affairs and Endowments (Awqaf), computed with ICOP's Accurate Times (8.5, Q13), and writes
`official-zones/ae_awqaf.json`: 38 of Awqaf's 41 locations, nine of them fitted on the 2026 tables that Awqaf sent the
owner by email on 2026-10-17, **on the condition that the app makes no money from them** (any paid version,
advertising or other income means asking Awqaf again first; the source line names Awqaf, as its open-data policy
asks).

**Method** (the plan's values, each confirmed by the windows below, and Asr's noon shadow at Dhuhr):

| Time | Rule | Rounding (E1) |
|---|---|---|
| Fajr | 18° at the eastern point | nearest |
| Sunrise | E2 `Refraction`: the zone's morning elevation, pressure and temperature, at the eastern point (Al Ain: at a point of its own, E6 `eventPoints`) | nearest |
| Dhuhr | the transit at the western point + 2 min | nearest |
| Asr | shadow factor 1 on the apparent sun (E13), the noon shadow taken at Dhuhr's published instant, transit + 2 min (E4 `SHADOW_AT_DHUHR`), at the western point | nearest |
| Maghrib | E2 `Refraction` with the evening elevation, at the western point | nearest |
| Isha | 18° at the western point | nearest |

VSOP87 (E14); every other time at its own instant (E4). At exact coordinates the horizons fall back to
`Fixed(−1.4°)`: the fitted zones' horizons at mean distance are −1.376° (sunrise) and −1.377° (sunset) at the median.
Jumu'a stays as the profile has it (A18).

**Zones and mapping.** `NEAREST` by emirate (`byAdmin1`): a place takes, among the locations whose `admin1` lists its
emirate (GeoNames AE admin1: 01 Abu Dhabi, 02 Ajman, 03 Dubai, 04 Fujairah, 05 Ras al-Khaimah, 06 Sharjah, 07 Umm
al-Quwain; Dibba, 72, and the east coast, 88, serve 04 and 06), the one with the nearest named area or own point
(8.8). `id` is Awqaf's official id; `nameAr` the location's principal area (Awqaf's own labels are not in hand; the
emailed file names its nine in English). `point` is the town: a cities1000 row, Al Khaznah's GeoNames point (292818),
or, for 15 locations with no GeoNames town (39, 40, 45, 46, 47, 50, 51, 52, 58, 65, 75, 81, 84, 85, 92), the point of
the Accurate Times entry that names the location (a seed, 8.5). Named places: 71 of Awqaf's 317 areas, matched inside
their emirate to cities1000 rows by Arabic name (with or without the emirate name Awqaf adds) or by a reviewed alias
(`ALIASES` in fit.py), each within 60 km of its location, 57 of them to a catalogue row; the other 246 are left to the
catalogue build (M5). Three locations are left out, no allowed source placing them: 43 «الظاهرة» (GeoNames gives only
Al Ain, whose region bore the name), 74 «الصرم» and 80 «جبل جيس»; their areas take the emirate's nearest other
location.

| Zone | Western point | Eastern point | Morning / evening elevation | Pressure, temperature |
|---|---|---|---|---|
| 36 Abu Dhabi | 24.4625 N 54.2996 E | 54.6444 E (34.9 km) | 222 / 223 m | 988 hPa, 29 °C |
| 37 Al Ain | 24.2125 N 55.5300 E | 56.0710 E (54.9 km); sunrise 56.1910 E (12.2 km further) | 323 / 282 m | 977 hPa, 28 °C |
| 57 Madinat Zayed | 23.6575 N 53.6133 E | 53.8581 E (24.9 km) | 192 / 194 m | 992 hPa, 29 °C |
| 66 Ajman | 25.4150 N 55.4321 E | 55.6262 E (19.5 km) | 225 / 224 m | 988 hPa, 29 °C |
| 69 Dubai | 25.1100 N 55.0778 E | 55.5459 E (47.1 km) | 222 / 234 m | 988 hPa, 29 °C |
| 73 Fujairah | 25.1475 N 56.2188 E | 56.3597 E (14.2 km) | 240 / 239 m | 986 hPa, 28 °C |
| 82 Ras Al Khaimah | 25.7425 N 55.8842 E | 56.1305 E (24.7 km) | 224 / 223 m | 988 hPa, 29 °C |
| 91 Sharjah | 25.3150 N 55.3484 E | 55.6459 E (29.9 km) | 221 / 223 m | 988 hPa, 29 °C |
| 94 Umm Al Quwain | 25.5575 N 55.5370 E | 55.7832 E (24.7 km) | 222 / 223 m | 988 hPa, 29 °C |

Pressure and temperature follow the morning elevation by the climate law of Accurate Times' UAE entries (the ISA lapse
rate with 30 °C at sea level, as whole hPa and °C). The western point lies 2.0–23.6 km from the location's town (Al
Ain and Dubai the farthest). The 29 other locations take provisional inputs: the town point, the fitted zones' median
elevations (222 m morning, 223 m evening, 988 hPa, 29 °C), no eastern point, and no fit record (tier B,
9.5).

These values are fixed in fit.py (`FIRST_FIT`, each fold's own included): the stages below chose them on 2026-10-18,
so a run reproduces them and only rescores; `--refit` runs the stages again (for new tables).

**Data** (test-data-local, read at run time, never copied): `research-2026-10-17/AE/awqaf_email_2026.csv` (Awqaf's
email, every day of 2026 for nine locations: 36, 37, 57, 66, 69, 73, 82, 91, 94; README there with its SHA-256 and the
condition), the fitted rows. The website's tables collected before cross-check it:
`research-2026-10-08/AE/ae/ad36.txt`, `ain37.txt`, `dxb69.txt` (every third day) and `aeskeptic/work/ad_official.txt`
(Abu Dhabi, every day of March, June, October and December) and `dxb_oct.txt` (Dubai, October): equal to the email on
every shared day but one, Abu Dhabi's Maghrib of 2026-03-30, a minute later on the website. The email labels 57 "Al
Ruways", but its fitted point lies 9.4 km from Madinat Zayed, which Awqaf's own area list also gives location 57
(Al Ruways and Al Dhannah are 61): the asset keeps «مدينة زايد». Also `research-2026-10-11/AE/emirates_2026-10-11.json`
(Awqaf's area list), `research-2026-10-11/GEONAMES/cities1000.zip`, and `C:/Accurate Times/loc.txt` in place (seeds
only).

**Fit** (`--refit`). Per zone, by windows, every time nearest: (1) the western longitude from Dhuhr + 2 min (Dubai,
where some days' Dhuhr disagrees, chooses it jointly with stage 2 across Dhuhr's tolerance band); (2) the latitude,
shared by both points, from Asr, Isha and Maghrib; (3) the evening elevation from Maghrib; (4) the eastern longitude
and the morning elevation jointly from Fajr and sunrise (9.4); (5) Al Ain only (`OWN_SUNRISE`): sunrise's own longitude
on the zone's parallel and the morning elevation, jointly from sunrise's windows, then the evening elevation again at
the new pressure and temperature. At Al Ain's shared eastern point no single longitude and elevation serve both Fajr
and sunrise (357 of 365 sunrise windows); at its own point, 364. VSOP87 refits beat MEEUS refits in-sample at all
nine zones (98.1–98.7 % against 99.4–99.8 %; Dubai 93.0 against 93.1 %). At the fixed points:

| Zone | Dhuhr offset window | Fajr angle window | Isha angle window | Asr k = 1, apparent: shadow at Dhuhr / at the transit; geometric at the transit |
|---|---|---|---|---|
| 36 Abu Dhabi | 365/365 days, 1.999–2.001 min | 363, 18.000° | 365, 18.000–18.001° | 362 / 349; 321 |
| 37 Al Ain | 365, 1.998–2.004 | 365, 18.000° | 365, 18.000–18.001° | 362 / 346; 312 |
| 57 Madinat Zayed | 365, 1.996–2.003 | 365, 18.000–18.001° | 365, 18.000° | 359 / 339; 299 |
| 66 Ajman | 365, 1.999–2.004 | 365, 17.999–18.001° | 364, 18.002° | 361 / 356; 321 |
| 69 Dubai | 358 at 1.977–1.984 | 337 at 17.997–17.998° | 336 at 18.017–18.043° | 352 / 354; 315 |
| 73 Fujairah | 365, 1.990–1.991 | 365, 18.000° | 365, 17.999–18.000° | 363 / 352; 315 |
| 82 Ras Al Khaimah | 365, 1.999–2.001 | 365, 17.999–18.000° | 365, 18.000° | 362 / 356; 314 |
| 91 Sharjah | 365, 2.000–2.003 | 365, 17.998–18.001° | 365, 18.001–18.002° | 360 / 349; 314 |
| 94 Umm Al Quwain | 365, 1.999–2.001 | 364, 18.000° | 364, 17.999–18.001° | 363 / 360; 316 |

Asr's misses with the shadow at the transit fall in June and July, when the noon sun is about 1° from the zenith and
the 2 minutes to Dhuhr lengthen the shadow most: Accurate Times measures the noon shadow at its displayed Dhuhr.

**Held-out scores** (month-split per zone: each fold's fixed values, fitted on the other months; all within 1 minute,
worst 1 minute; quirk days none):

| Exact, held out (365 days each) | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| 36 Abu Dhabi | 99.2 % | 99.7 % | 100 % | 99.2 % | 100 % | 99.7 % |
| 37 Al Ain | 99.5 % | 97.8 % | 100 % | 98.9 % | 99.2 % | 99.5 % |
| 57 Madinat Zayed | 100 % | 100 % | 99.5 % | 99.2 % | 100 % | 99.7 % |
| 66 Ajman | 99.2 % | 99.5 % | 99.2 % | 98.6 % | 98.9 % | 98.9 % |
| 69 Dubai | 91.8 % | 90.4 % | 95.6 % | 96.2 % | 91.0 % | 86.3 % |
| 73 Fujairah | 99.2 % | 99.5 % | 99.2 % | 98.6 % | 97.8 % | 98.9 % |
| 82 Ras Al Khaimah | 99.7 % | 99.2 % | 99.7 % | 99.2 % | 97.8 % | 99.2 % |
| 91 Sharjah | 99.7 % | 99.7 % | 99.5 % | 98.6 % | 99.5 % | 99.5 % |
| 94 Umm Al Quwain | 99.2 % | 99.5 % | 99.7 % | 99.5 % | 99.5 % | 99.7 % |
| profile (3,285 days) | 98.6 % | 98.4 % | 99.1 % | 98.7 % | 98.2 % | 97.9 % |

At the same fold points, the noon shadow at the transit gives Asr 341–361 of 365. Al Ain at its shared eastern point
(stages 1–4 only) held out sunrise on 353 days (96.7 %).

**Tier A** (9.5): eight zones pass on every time (36, 37, 57, 66, 73, 82, 91, 94); Dubai (69) fails on every time and
stays within 1 minute (tier B). Each month has at least 28 held-out days per zone.

**Dubai.** Its table (IACAD's, which Awqaf's equals, Q13) is not reproduced. No single constant after the transit
gives its Dhuhr on more than 358 of 365 days, whatever the sun model (VSOP87 358, MEEUS 359, TEXTBOOK 320) or the
Dhuhr rule (the disc's whole crossing, 359), where the other eight zones' Dhuhr fits VSOP87 on 364–365 days. Its 7
odd days fall 1–4 s beyond a rounding edge, in seven different months. With every time at a point of its own (E6, in
exploration), the held-out Fajr, sunrise and Isha still reach only 91–93 %. Its misses are single minutes on scattered
days of every month: a computation that differs from Accurate Times' by a few seconds, not a different place.

**Quirk days.** None.

**Accurate Times' entries as they stand** (CHECK, on the nine tables; not shipped): Abu Dhabi's entry 97.8 % exact and
all within 1 minute; the others 22.2–65.3 % exact (75.9–100 % within 1, worst 1–3 min; Dubai the lowest). The
provisional inputs at the town point (the other zones' medians): 3.5–68.4 % exact, 74.4–100 % within 1 minute (Al Ain
and Dubai the lowest). The entries would do better at some locations, but their parameters stay out of the asset until
a table confirms them (8.5).

**Open questions.**
- The other 29 locations' tables: the federal API (9.3) registers UAE residents only; Awqaf answered the owner's
  email with nine locations, and could be asked for the others. Until then their inputs are provisional; high places
  (Jebel Hafit, 52; Hatta, 71; Masafi, 75 and 84) are the most at risk, their horizons depending on elevation.
- Dubai: IACAD's own inputs or program would show what its table computes (Q13).
- Al Ain's sunrise point is a fitted value: the folds put it at 56.1585 and 56.2260 E (morning elevation 352 and
  293 m), so it is known to about ±0.03° and ±30 m.
- The three locations left out need a point (Awqaf's server answer for coordinates, 8.8), and Awqaf's own Arabic
  location labels would replace the area names used as `nameAr`.
