### Egypt (`eg_egsa`)

Fitted by `fits/eg_egsa/fit.py` (2026-10-15; the year stage 2026-10-17; about 6 minutes); asset
`official-zones/eg_egsa.json`, 78 zones, every one tier A.

**Method.** Fajr 19.5° (stated, Dar al-Ifta fatwa 4021) and Isha 17.5° (quoted there): the windows' best plateau
is exactly 19.5 and 17.5 at 0.01° steps. Dhuhr = transit, Maghrib = sunset, Asr factor 1 with the noon shadow's
declination at transit (the engine default: 3,726 of 3,726 windows, against 3,679 with E13 `APPARENT`, 3,625 at
local midnight and 3,570 with factor 2), nearest minute on all six times, no offsets, no elevation. Two values
differ from the textbook "Egyptian" method:

- **Sun: VSOP87** (E14). Both suns let one longitude per city cover all 621 Dhuhr windows, but VSOP87 leaves the
  cities wider common intervals (3.13° summed, against 2.99° for MEEUS). Held out: 97.24 % against 96.70 % with MEEUS.
- **Horizon: `Fixed(-0.8435)`** for sunrise and sunset (E2), about 0.6′ lower than the standard −0.8333°. All
  1,863 sunrise, Dhuhr and sunset windows meet only between −0.844° and −0.843°. The standard horizon leaves 13 of
  them uncovered and gives 95.95 % held out. Saint Catherine (1,586 m) takes the same horizon, so there is no dip.

Rounding and offsets are checked against the town centres. The fitted points sit on the GeoNames centres (median
−0.003° of longitude, −0.01 min). Any Dhuhr minute or other rounding would move every point by 0.125° or more.

**Zones.** Each of the 78 cities in EGSA's all-cities table is a zone.
- `id`: the city's position in EGSA's city menu (1 = Cairo). The menu has no ids of its own.
- `nameAr`: EGSA's spelling without tatweel, with a space added in «6 أكتوبر».
- The computing point (E6 morning and evening points, plan 8.8; `point` is the town): fitted for all 78 on the
  city's whole 2026 from the monthly view (the year stage below), from the 2025-26 daily pages' point.
- `namedPlaces`: one entry per zone.
  - Centre: the GeoNames town for 65 cities (Ras Sudr: GeoNames' cape; 6 October: GeoNames 7577132). GeoNames has
    no populated place for Toshka or for 12 new cities (ids 56, 65, 66, 69 to 78); their centre is the fitted point.
  - `areaKm`: the radius of a disc that holds the city's GeoNames population at 10,000 inhabitants per km², kept
    between 3 and 15 km. Cairo 15, Alexandria 13, Port Said 5, Mansoura, Tanta, Mahalla and Suez 4.5, 63 cities 3.

45 of the 65 located points lie within 5 km of their town. The far ones are EGSA's own points, which the tables
reproduce: Taba 49 km west, Baris 32 km, Kharga 28 km, Ras Gharib 26 km, Berenice 22 km, El Mahalla el Kubra
22 km west (on 5 of the 8 days its table equals Kafr el-Sheikh's), Baltim 21 km, Aswan 17 km.

**Mapping.** `NEAREST` with `namedFirst` (6.5, 8.8). A place inside a city's area takes that city's table, as does
one matched by catalogue id once M5 fills `catalogueIds` (fit.py keeps the GeoNames ids). Any other Egyptian place
takes the nearest centre or point, under the validity guard.

**Data** (test-data-local, read at run time, never copied):
- `research-2026-10-08/EG/eg/praytimes.html`: the 2026-10-08 table for 78 cities, and the city menu.
- 9 Wayback copies of the same page, 2024-07-23 to 2026-06-12: `research-2026-10-11/EG/wayback/<timestamp>.html`,
  or a folder passed with `--wayback`. Without them, fit.py stops and does not write the asset.
- `research-2026-10-08/EG/eg_skeptic/ram.py` and `aug.py`: transcribed Al-Dostor reprints of Cairo's Ramadan 1445
  imsakiya and of six cities' table for 2025-08-13. They are held out only.
- `research-2026-10-17/EG/monthly/m2026_MM_CI.html` (MANIFEST.tsv beside): EGSA's monthly view
  (esa.gov.eg/monthlymwaket.aspx) for every month of 2026 and every city of its menu, fetched 2026-10-17 with the
  owner's OK, one form request per city and month. All 78 cities have their twelve months; the view leaves out
  two days of 15th of May City (2026-03-20, 05-26) and has no table for Menouf, Diarb Negm, Abu Tisht and Samalut.
  On the 311 city-days the daily pages hold too, the two agree on every time.
- Town centres and populations come from GeoNames (`TOWNS` in fit.py, read 2026-10-15).

**Held out** (common.compute). Each of the 8 days of 2025-26 is scored with the profile and points refitted on the
other 7 (leave one day out):
- 3,623 of 3,726 times exact (97.24 %): Fajr 97.9, sunrise 96.9, Dhuhr 96.9, Asr 97.6, Maghrib 97.4, Isha 96.6 %.
  All are within 1 minute.
- The reprints, at the final points: Cairo's Ramadan 2024 imsakiya 180 of 180, the six cities on 2025-08-13 36 of 36.
- **The year stage** (2026-10-17): each city's point fitted on the monthly view's 2026 (a grid swept over the
  linearised windows, relinearised twice), and held out by month split (the point fitted on the odd months scored
  on the even ones, and the reverse, with common.compute): 169,395 of 170,808 times exact (99.17 %): Fajr 99.3,
  sunrise 99.1, Dhuhr 99.3, Asr 99.3, Maghrib 99.0, Isha 99.1 %, every time within 1 minute. The points move a
  median 0.9 km from the daily pages' (Badr City 3.3 km, El Hamool 3.2, Esna 3.1, Sharm el-Sheikh 3.0).
- The year confirms the profile at those points (windows held, of 170,808): the horizon −0.8435° 169,860 (−0.8445°
  169,803, −0.8425° 169,600), Fajr 19.5° (19.45° 162,749, 19.55° 162,801), Isha 17.5° (17.45° 162,751, 17.55°
  163,125), Asr's shadow at transit (the apparent sun 166,616, at local midnight 162,234, factor 2 141,520).
- Profile record: 99.13 % (173,234 of 174,750: the daily days left out, the reprints and the year's month split).
- **Tier A**: all 78 zones pass (9.5): each zone's record holds its year's month split and its daily days left
  out, 29 or more held-out days in every month, all six times at least 97 % exact and every one within 1 minute.

**Quirk days** (158 city-days, counted apart):
- The 2024-07-23 and 2024-08-11 copies predate a move of 49 cities' points. On those two days alone, the 49 cities
  sit a median 18 km north of today's points. They are menu positions 1 to 51 (except Nuweiba, Halaib and El
  Hammam) and Saint Catherine. At today's points they score 60.0 % on 2024, while the 29 other cities, mostly the
  new ones, score 96.3 %.
- This answers Q9's open point. Cairo's Ramadan 2024 imsakiya still fits today's point, because near the equinox a
  change of latitude hardly moves any time.
- New Toshka and West Nubaria on 2025-04-29: 1 to 2 min off on three or four times each. Their points moved before
  July 2025.

**Open issues.**
- The 2027 monthly view, held out as a whole year, shows whether EGSA moves any point again.
- 41 more Wayback days (2022 to 2026) are listed but not yet saved.
- Four menu cities have no row on the daily page and no table on the monthly view: Menouf, Diyarb Negm, Abu Tisht
  and Samalut.
- It is unknown whether Taba's, Mahalla's and the other far points are EGSA typos; the app follows EGSA either way.
- The physical origin of the −0.8435° horizon (50.6′) is unknown.
