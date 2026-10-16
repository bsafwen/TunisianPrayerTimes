#### UAE (`ae_awqaf`)

`fits/ae_awqaf/fit.py` (about 1 minute) fits the tables of the General Authority of Islamic Affairs and Endowments
(Awqaf), computed with ICOP's Accurate Times (8.5, Q13), and writes `official-zones/ae_awqaf.json`: 38 of Awqaf's 41
locations, three of them fitted on their 2026 tables.

**Method** (the plan's values, each confirmed by the windows below, and Asr's noon shadow at Dhuhr):

| Time | Rule | Rounding (E1) |
|---|---|---|
| Fajr | 18° at the eastern point | nearest |
| Sunrise | E2 `Refraction`: the zone's morning elevation, pressure and temperature, at the eastern point | nearest |
| Dhuhr | the transit at the western point + 2 min | nearest |
| Asr | shadow factor 1 on the apparent sun (E13), the noon shadow taken at Dhuhr's published instant, transit + 2 min (E4 `SHADOW_AT_DHUHR`), at the western point | nearest |
| Maghrib | E2 `Refraction` with the evening elevation, at the western point | nearest |
| Isha | 18° at the western point | nearest |

VSOP87 (E14); every other time at its own instant (E4). At exact coordinates the horizons fall back to
`Fixed(−1.4°)`: the fitted zones' horizons at mean distance are −1.377° (sunrise) and −1.400° (sunset) at the median.
Jumu'a stays as the profile has it (A18).

**Zones and mapping.** `NEAREST` by emirate (`byAdmin1`): a place takes, among the locations whose `admin1` lists its
emirate (GeoNames AE admin1: 01 Abu Dhabi, 02 Ajman, 03 Dubai, 04 Fujairah, 05 Ras al-Khaimah, 06 Sharjah, 07 Umm
al-Quwain; Dibba, 72, and the east coast, 88, serve 04 and 06), the one with the nearest named area or own point
(8.8). `id` is Awqaf's official id; `nameAr` the location's principal area (Awqaf's own labels are not in hand).
`point` is the town: a cities1000 row, Al Khaznah's GeoNames point (292818), or, for 15 locations with no GeoNames
town (39, 40, 45, 46, 47, 50, 51, 52, 58, 65, 75, 81, 84, 85, 92), the point of the Accurate Times entry that names
the location (a seed, 8.5). Named places: 71 of Awqaf's 317 areas, matched inside their emirate to cities1000 rows by
Arabic name (with or without the emirate name Awqaf adds) or by a reviewed alias (`ALIASES` in fit.py), each within
60 km of its location; the other 246 are left to the catalogue build (M5). Three locations are left out, no allowed
source placing them: 43 «الظاهرة» (GeoNames gives only Al Ain, whose region bore the name), 74 «الصرم» and 80
«جبل جيس»; their areas take the emirate's nearest other location.

| Zone | Western point | Eastern point | Morning / evening elevation | Pressure, temperature |
|---|---|---|---|---|
| 36 Abu Dhabi | 24.4700 N 54.2996 E | 54.6430 E (34.8 km) | 223 / 223 m | 988 hPa, 29 °C |
| 37 Al Ain | 24.2075 N 55.5301 E | 56.0703 E (54.8 km) | 428 / 286 m | 965 hPa, 27 °C |
| 69 Dubai | 25.1175 N 55.0763 E | 55.5477 E (47.5 km) | 215 / 240 m | 989 hPa, 29 °C |

Pressure and temperature follow the morning elevation by the climate law of Accurate Times' UAE entries (the ISA lapse
rate with 30 °C at sea level, as whole hPa and °C). The 35 other locations take provisional inputs: the town point,
the fitted zones' median elevations (223 m morning, 240 m evening, 988 hPa, 29 °C), no eastern point, and no fit
record («مستخلصة من جداول», 9.5).

These values are fixed in fit.py (`FIRST_FIT`, each fold's own included): the stages below chose them on 2026-10-15,
with Asr's noon shadow at the transit, and they were kept when Asr moved to `SHADOW_AT_DHUHR`, so a run reproduces
them and only rescores; `--refit` runs the stages again, the new Asr included (for new tables).

**Data** (test-data-local, read at run time, never copied): `research-2026-10-08/AE/ae/ad36.txt`, `ain37.txt`,
`dxb69.txt` (2026, every third day), `research-2026-10-08/AE/aeskeptic/work/ad_official.txt` (Abu Dhabi, every day
of March, June, October and December) and `dxb_oct.txt` (Dubai, October), equal to the first files on all 52 shared
days: Abu Dhabi 204 days, Al Ain 122, Dubai 142; `research-2026-10-11/AE/emirates_2026-10-11.json` (Awqaf's area
list, copied from the Q-research scratchpad with a README); `research-2026-10-11/GEONAMES/cities1000.zip`;
`C:/Accurate Times/loc.txt` in place (seeds only).

**Fit** (`--refit`). Per zone, by windows, every time nearest: (1) the western longitude from Dhuhr + 2 min (Dubai,
where 4 of 142 days' Dhuhr disagree, chooses it jointly with stage 2 across Dhuhr's tolerance band); (2) the latitude,
shared by both points, from Asr, Isha and Maghrib; (3) the evening elevation from Maghrib; (4) the eastern longitude
and the morning elevation jointly from Fajr and sunrise (9.4). VSOP87 refits beat MEEUS refits in-sample at all three
zones. At the fixed points:

| | Abu Dhabi | Al Ain | Dubai |
|---|---|---|---|
| Dhuhr offset window | 204/204 days, 1.999–2.001 min | 122/122, 1.998–2.004 | 138/142 at 1.929–1.986 (136 at + 2: the joint choice) |
| Fajr angle window | 202, 17.999–18.000° | 121, 18.000–18.001° | 132, 18.000–18.001° |
| Isha angle window | 203, 18.000–18.001° | 122, 18.000–18.004° | 131 at 18.014–18.016° (123 at 18°) |
| Asr k = 1, apparent: shadow at Dhuhr / at the transit; geometric at the transit | 201 / 195; 176 | 120 / 116; 101 | 139 / 140; 126 |

Asr's misses with the shadow at the transit fall in June and July, when the noon sun is about 1° from the zenith and
the 2 minutes to Dhuhr lengthen the shadow most: Accurate Times measures the noon shadow at its displayed Dhuhr.

**Held-out scores** (month-split per zone: each fold's fixed values, fitted on the other months; all within 1 minute,
worst 1 minute; quirk days none):

| Exact, held out | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| 36 Abu Dhabi (204 days) | 98.5 % | 98.5 % | 100 % | 98.5 % | 99.0 % | 98.5 % |
| 37 Al Ain (122 days) | 97.5 % | 97.5 % | 99.2 % | 98.4 % | 99.2 % | 99.2 % |
| 69 Dubai (142 days) | 90.8 % | 90.8 % | 93.7 % | 97.9 % | 95.1 % | 86.6 % |
| profile (468 days) | 95.9 % | 95.9 % | 97.9 % | 98.3 % | 97.9 % | 95.1 % |

At the same fold points, the noon shadow at the transit gives Asr 192 of 204 (Abu Dhabi), 115 of 122 (Al Ain) and
135 of 142 (Dubai).

**Tier A** (9.5): Abu Dhabi and Al Ain pass on every time; Dubai fails on Fajr, sunrise, Dhuhr, Maghrib and Isha.
Each month has at least 9 held-out days per zone.

**Quirk days.** None.

**Accurate Times' entries as they stand** (CHECK, on the three tables; not shipped): Abu Dhabi's entry, with the
climate law for its 1010 hPa and 10 °C, 97.8 % exact and all within 1 minute; Al Ain's 64.5 % (98.2 % within 1, worst
2); Dubai's 21.7 % (75.7 %, worst 3). The provisional inputs at the town point (the other two zones' medians): 50.8 %,
3.8 % and 18.9 % exact (99.9 %, 74.7 % and 98.1 % within 1 minute). The entries would do better where they hold, but
their parameters stay out of the asset until a table confirms them (8.5).

**Open questions.**
- The other 38 locations' tables (the Open Data API, 9.3): until then their inputs are provisional; high places
  (Jebel Hafit, 52; Hatta, 71; Masafi, 75 and 84) are the most at risk, their horizons depending on elevation.
- A held-out year: `--refit` with the new Asr moves Abu Dhabi and Al Ain 0.005–0.0075° in latitude, and Al Ain's
  sunrise then loses a held-out day (118 of 122), so these latitudes are fixed only to about that.
- Dubai's table: month-to-month biases of up to half a minute that no point, angle, horizon or separate morning
  latitude removes (in exploration a free morning latitude gains 2 days); a full daily year, or IACAD's inputs,
  would show the cause (Q13).
- The three locations left out need a point (Awqaf's server answer for coordinates, 8.8), and Awqaf's own location
  labels would replace the area names used as `nameAr`.
