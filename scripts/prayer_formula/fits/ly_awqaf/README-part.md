#### Libya (`ly_awqaf`)

`fits/ly_awqaf/fit.py` (about 1 minute) fits the General Authority of Awqaf's table and writes
`official-zones/ly_awqaf.json`. The table is perpetual, keyed by month and day: the fit reads each row at its
reference year (E5), and every score is computed by `common.compute` at the row's actual date, as the app shows
it: the table as printed, every date as its month and day of 2006 (A8, revised by the owner on 2026-10-17).

**Method.** Fajr and Isha 18.357° at a twilight latitude of 0.99330562·φ (E17), Dhuhr transit + 4, Maghrib sunset
+ 4, sunrise on the standard horizon, Asr factor 1 on the geometric sun, all six truncated (E1 DOWN), the TEXTBOOK
sun once a day at 10:00 UT (E14, E4 `DAILY_10H_UT`), reference year 2006. Stages, by windows, on Tripoli's 60
month-days (the only city with rows across the year):

1. Dhuhr alone: TEXTBOOK covers 58 of 60 windows, MEEUS and VSOP87 53.
2. All six times over a grid of points, each evaluation at its best reference year (1980 to 2014, the year of the
   first capture; the evaluation hour trades with the year, 0h UT in 2029 being 10h UT in 2006 to within 20 min):
   `DAILY_10H_UT` 349 of 360, `DAILY_0H_UT` 337, `KACST` 336, `EVENT_INSTANT` 313. Reference year 2006 (349), then
   2010 (347) and 2002 (346), the same leap phase.
3. Dhuhr + 4 and Maghrib + 4 by windows (sunrise and Asr take none, 8.2); E17 349 against 324 without. Up, down and
   nearest rounding give the same 349 at points 0.125° of longitude apart (13.518, 13.393 and 13.268 E): down puts
   Tripoli nearest the town.
4. Tripoli's point 32.902 N 13.273 E, the region of 349 spanning 32.893–32.909 N, 13.271–13.276 E, 8 km east of
   the centre (Mitiga airport, about 32.894 N 13.276 E from memory, not checked, also reaches 349, at 18.358°; Oman's
   Muscat zone is its airport, 8.3). The common angle's window there is 18.3557–18.3579 (Fajr alone 58 of 60 at
   18.3551–18.3614, Isha alone 58 at 18.3557–18.3579).

The month halves pick the same rules: odd months 2010, 32.882 N 13.278 E, 18.358°; even months 2006, 32.903 N
13.271 E, 18.356°. Confidence: high for the rules, the sun and the rounding (17 of the 21 other cities, unused in
these stages, sit at their town centres under it; nearest or up would put them about 12 or 24 km east); the angle
±0.003°; the reference year only within its leap phase. Tripoli's 11 misses at its point fall 0.020–0.210 min outside their minute (median
0.030): that is the model's precision against the Awqaf's own code.

**Zones and mapping.** The 22 cities of the site's selector, `NEAREST` with the validity guard (6.5): every place in
Libya takes the nearest city's table unless the guard acts. `id` is the site's city code, `nameAr` its label, except
Awjila (`Ojala`), which the selector labels «اجدابيا» like Ajdabiya: «أوجلة» (8.8). Each zone's named place is its
town centre (`cities.csv`), `areaKm` 0. The points: Tripoli fitted as above; the 21 others keep their town's latitude
(two October days hardly fix one) and their town centre where it reproduces as many of the city's minutes as any
longitude within 0.5° does, each minute within the model's 0.03 min. 17 keep it; 4 are fitted in longitude, the
middle of the longitudes that reproduce every minute: Al Bayda (`beda`) 21.991 E, 22 km east of its centre (0 of 12
there); Hun (`Hoon`) 15.756 E, 19 km west (3 of 12); Al Marj 20.889 E, 5.5 km east (6 of 12);
Sabha 14.465 E, 3.6 km east (10 of 12). Jalo and Awjila take a zone offset of Asr + 8: their Asr is 7.2–7.5 min
after the profile's on both days, and the windows admit + 8 only (8.3, 8.7).

**Data** (test-data-local, read at run time, never copied): `research-2026-10-11/LY/official_rows.csv` (107 rows:
Tripoli 62 dates of 2016–2018 and 2025–2026, from awqaf.gov.ly captures and api.ifta.ly; Benghazi 5; the 20 other
cities 2026-10-08 and 10-11), `research-2026-10-11/LY/cities.csv` (town centres: Dar al-Ifta's headquarters for
Tripoli, the 2026-10-08 research scripts for 11 cities, approximate reference values for 10),
`research-2026-10-08/LY/ly/awqaf_home.html` (the selector), `research-2026-10-11/HIJRI/icop_ghc_1428_1450.csv`
(Ramadan days only) and, as a side check, the LCRSSS Benghazi transcription in `research-2026-10-08/LY/ly/bgz.py`.

**Held-out scores** (FitStats; Tripoli by month-split, each other city by day-split, Benghazi by month-split; all
within 1 min, worst 1 min):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| Profile, 107 days | 98.1 % | 95.3 % | 93.5 % | 93.5 % | 94.4 % | 90.6 % |
| Tripoli, 62 days | 98.4 % | 95.2 % | 91.9 % | 95.2 % | 98.4 % | 90.3 % |
| Profile, each date's own sun (before A8's revision) | 85.0 % | 84.1 % | 91.6 % | 83.2 % | 84.1 % | 78.5 % |

The final asset reproduces 619 of the 642 official minutes (96.4 %) as printed, which the app shows; computed for
each date's own sun, as before A8's revision, 546 (85.0 %). LCRSSS's Benghazi Ramadan 1440 table (another publisher, 2019-05-06
to 06-04) gives 145 of 150 computed for 2019. No zone can pass tier A (9.5: October data only outside Tripoli; LY
is tier B).

**A whole year from the mosques** (`fits/ly_awqaf/mosque_year.py` with `fits/mosque_years.py`, 2026-10-17, owner's
idea; plan 9.5). Seven Tripoli mosques of the owner's dataset print Tripoli's table on all 60 of its month-days read
at 2026 (the table is perpetual), to the minute. The first of them (0.4 km from the zone's point) is Tripoli's
held-out year on its other 305 days (`source` "mosque_follower"): as printed, Fajr 92.1 %, sunrise 96.7 %, Dhuhr
95.7 %, Asr 97.4 %, Maghrib 95.4 %, Isha 92.5 % exact, every time within 1 min (for each date's own sun, before A8's
revision, 83.3–95.7 %). Tripoli's record now holds 28–32 held-out days in every month (tier B either way). The
other 21 cities have 3 official days each (2026-10-08, 10-11 and the collector's 10-16), against the 20 a follower
needs; 31 of their mosques print the city's minute on every time of those days (17 at Ajdabiya, 7 at Sirte, 4 at Zawiya,
2 at Benghazi on its 5 days, 1 at Al Bayda), and 45 mosques 17–26 km from Zawiya's point print its minute + 1.
The script reads the daily collector's pages too, so a
run once 20 days are collected (about 2026-11-02) adds those cities' years.

**Quirk days.** None counted: Jalo's and Awjila's Asr is reproduced by its zone offset. The table has no 29 February
row (A8 computes it as 2008's, the first leap year from 2006).

**Validity guard** (side check, 2026; Sabratha and Ghat at `ZoneMappingTest`'s points, the others approximate):
Sabratha and Zuwara keep Zawiya's table (Isha 1.1 and 3.4 min beyond its margin); Nalut and Wazin (Fajr 11.1 and
11.7), Tazirbu (Kufra's Isha, 13.6) and Ghat (Sabha's Fajr, 24.4) take the method at their own point.

**Open issues.**
- A year of the daily collection (9.3) for all 22 cities: the 21 cities' points rest on two October days (their
  latitudes not at all), Benghazi's on five; Jalo's and Awjila's Asr + 8 may vary with the season. From 20 collected
  days, `mosque_year.py --write` (after `fit.py`) gives the cities whose mosques follow them a held-out year.
- Al Bayda and Hun are computed about 20 km from their towns: the Awqaf's own coordinates, or not its formula there.
  `NEAREST` maps by the zone point, so their zone boundaries move with it; E6 morning and evening points would keep
  the town centres for mapping.
- The town centres come from `cities.csv` (10 approximate), not checked against GeoNames; M5 matches the catalogue
  rows.
- LCRSSS's Tripoli Ramadan 1440 image (`lcimg/Tripoli1440.jpg`) is not transcribed; the eastern Awqaf's tables, if
  any, are unknown (Q14).
