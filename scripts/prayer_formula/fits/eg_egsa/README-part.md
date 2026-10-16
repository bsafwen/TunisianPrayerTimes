### Egypt (`eg_egsa`)

Fitted by `fits/eg_egsa/fit.py` (2026-10-15, about 30 s); asset `official-zones/eg_egsa.json`, 78 zones.

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
- `point`: fitted for all 78, as the centroid of the region where every one of the city's 2025-26 minutes holds.
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
- Town centres and populations come from GeoNames (`TOWNS` in fit.py, read 2026-10-15).

**Held out** (common.compute). Each of the 8 days of 2025-26 is scored with the profile and points refitted on the
other 7 (leave one day out):
- 3,623 of 3,726 times exact (97.24 %): Fajr 97.9, sunrise 96.9, Dhuhr 96.9, Asr 97.6, Maghrib 97.4, Isha 96.6 %.
  All are within 1 minute.
- The reprints, at the final points: Cairo's Ramadan 2024 imsakiya 180 of 180, the six cities on 2025-08-13 36 of 36.
- Profile record: 97.39 % (3,839 of 3,942).
- Per zone: 24 zones are exact on every held-out time. The weakest is Alexandria, at 90.7 %.
- No zone passes tier A: each has 1 held-out day per month, and none in January, May, September or November.

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
- Tier A needs a year of daily pages, or the monthly form (which needs the owner's go-ahead).
- 41 more Wayback days (2022 to 2026) are listed but not yet saved.
- Four menu cities have no row: Menouf, Diyarb Negm, Abu Tisht and Samalut.
- It is unknown whether Taba's, Mahalla's and the other far points are EGSA typos; the app follows EGSA either way.
- The physical origin of the −0.8435° horizon (50.6′) is unknown.
- Plan 8.8 puts the town centre in `point` and the fitted point in E6's morning and evening points. This asset
  follows the AM2 brief instead: the fitted point in `point`, the town centre in `namedPlaces`.
