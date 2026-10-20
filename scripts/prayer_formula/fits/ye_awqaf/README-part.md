#### Yemen (`ye_awqaf`)

`fits/ye_awqaf/fit.py` (standard library; about 2 minutes, 3 with the validity-guard side check) fits the General
Authority of Awqaf and Guidance's daily tables (Sanaa, awqaf.gov.ye) and writes `official-zones/ye_awqaf.json`.

**Method.** One rule for the 31 locations and all 134 dates, whatever the endpoint's `season` field (3 until
2026-07-14, 4 from 07-15). Every value was confirmed by windows on all dates and on each half of the month split:

| Time | Rule | Windows (all 134 dates, at the fitted points) |
|---|---|---|
| Fajr | 18° | best 4,096 of 4,154, at 18.000° |
| Sunrise | E2 `Pressure`: −(0.2667° + 0.5667°·e^(−h/8434 m)) at the zone's fitted elevation h | see Points |
| Dhuhr | transit + 10 (E4 at the event instant) | best 4,039 of 4,154, at 9.9999–10.0001 min |
| Asr | factor 1, noon shadow at the declination of 00:00 local time (E4 `LOCAL_MIDNIGHT_SHADOW`), geometric sun | with Dhuhr, one longitude per location: 8,084 of 8,308, against 7,321 at transit, 7,209 (daily 0h UT sun), 6,621 (`KACST`) and 7,969 on the apparent sun |
| Maghrib | pressure sunset + 6 at or below 650 m, + 10 at or above 1,100 m (E10) | + 6: 1,995 of 2,010 at 6.000–6.001; + 10: 2,128 of 2,144 (2,127 at exactly 10) |
| Isha | 16° | best 4,105 of 4,154, at 16.000° |
| Rounding | nearest, all six (E1) | |
| Sun | VSOP87 (E14) | Dhuhr windows, one longitude per location: VSOP87 4,060, MEEUS 4,052; both halves pick VSOP87 |

- The declination's instant is sharp: at the fitted points Asr is exact on 4,032 of 4,154 days with the declination at
  00:00 local (21:00 UT the day before), 3,964 at 22:30 UT, 3,905 at 0h UT.
- E10 holds as the plan has it: the 15 locations at + 6 fit elevations of −71 to 643 m, the 16 at + 10 from 1,091 to
  2,490 m. No location's class crosses its elevation.
- Dhuhr alone, with one longitude per location, holds at most 4,073 windows (the equation of time at 10:00 UT, an
  existing E4 mode), against 4,060 at the event instant. At the fitted points every hour from 09:00 to 13:00 UT gives
  4,036–4,043 exact, so the evaluation stays at the event instant.
- `amsek` (Imsak) equals Fajr on all 4,154 location-days; the app shows no Imsak row (A14).

**Points, elevations, classes.** Stage 2 fits each location jointly: a latitude grid (0.01°, then 0.001°); at each
latitude, the longitude windows of Fajr, Dhuhr, Asr and Isha; at each candidate longitude, the horizon-altitude
windows of sunrise and sunset under either class, as E10 allows it (+ 6 below 1,100 m, + 10 above 650 m). The
point is the cell of the best region nearest its centroid, the town where the town is in that region (none is).
The elevation is the middle of the altitude stretch through the pressure law, rounded to the metre.
- The fitted points lie a median 1.3 km from the towns. The farthest: Raymah 10.1 km from Al Jabin, Jaar 7.7 km,
  Dhi al-Sufal 6.3 km, Al Marawi'ah 5.2 km and Al Jawf 4.1 km from Al Hazm. These are the authority's own points,
  which its tables reproduce; its coordinates are not published.
- The fitted elevations follow the towns' GeoNames dem (median difference 47 m). Dhi al-Sufal is the exception: it
  fits 1,415 m, its town 1,857 m, 6.3 km away. Al Ghaydah fits −71 m (dem 17 m): the pressure law has little leverage,
  since 100 m moves sunrise and sunset by about 0.03 min.

**Zones and mapping.** 31 zones. `id` is the API's `location_id`, and `nameAr` its `location_name` («حضرموت - المكلا»).
`point` is the town the location names (GeoNames): a governorate's entry takes its capital, Al Hazm for «الجوف»,
Al Jabin for «ريمة», Ataq for «شبوة», Al Ghaydah for «المهرة», Hadibu for «سقطرى» and Al Hawtah for «لحج». The fitted point
is the E6 `morningPoint` and `eveningPoint`. `elevationM` is the fitted elevation; `terrainClass` is `HIGH` for Al Jawf,
whose 1,091 m lies between E10's limits. `namedPlaces` holds the town with its GeoNames id. Mapping is `NEAREST` with
`terrainClassKm` 60 (E10, 8.8): the nearest location of the place's own class within 60 km, otherwise the nearest,
then the validity guard.

**Data** (test-data-local, read at run time, never copied):
- `official/ye/d2026MMDD.json`: 134 dates from 2026-05-21 (the endpoint's first) to 2026-10-11, fetched on 2026-10-11.
  The endpoint answered empty for 2026-06-15 to 06-17, 06-28, 06-29, 08-26, 08-27 and 10-03 to 10-05.
  `official/ye/MANIFEST.tsv` gives each file's URL and SHA-256. The files were copied there on 2026-10-15, byte for
  byte, from the Q2 session's scratchpad, where they would not have lasted.
- `research-2026-10-08/YE/ye/api_2026-*.json` and `YE/ye_skeptic/data/d2026*.json`: 16 of the same dates, fetched on
  2026-10-08. A date fetched twice must agree, and all do.
- Town points and dem: GeoNames cities1000 and the YE extract (Seiyun), read 2026-10-15, in `TOWNS`.

**Held-out scores.** A month split: fit on May, July and September, score June, August and October, then the
reverse. Every stage is refitted on each half, and each half picks the same profile. Scored with `common.compute`:

| Exact | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| Held out, 31 zones × 134 days | 97.7 % | 97.9 % | 96.7 % | 96.2 % | 98.1 % | 97.6 % |
| Final asset, in-sample | 98.6 % | 99.1 % | 97.2 % | 97.1 % | 99.3 % | 98.8 % |

Every time is within 1 minute, held out and in-sample. Per zone, the held-out shares run from 91 to 100 %. The
lowest are Bayt al-Faqih's Fajr (91 %), Al Mukalla's Asr (91 %), Marib's Maghrib (93 %), Seiyun's sunrise and Isha
(94 %), and the Dhuhr of Aden, Taiz and Al Mahwit (94 %). Fold A (scored on June, August and October) gives Asr
94.9 %, fold B 97.4 %.

**Tier A** (9.5): 4 of the 31 zones reach the shares (97 % exact on each time, all within 1 minute): Saada, Dhamar,
Tarim and Jaar. None passes: the held-out days cover May to October only, and the gate needs 7 in every month. Every
zone ships with «مبنية على جداول منشورة» until a year of the monthly collection (9.3) is scored.

**Quirk days.** None: no held-out time is more than 1 minute off.

**Validity guard** (side check, 2026; E10's attachment, GeoNames points). Each verdict is the worst day beyond the
table's own margin:
- Kept: Al Mahabishah → Hajjah (Isha + 1.3 min), Yarim → Dhamar (Fajr + 0.7), Mocha → Taiz (Fajr + 3.7, near the
  threshold, as the plan expects).
- Own point: Rumah → Al Ghaydah's table (Isha + 9.5) and Kilmia on Abd al-Kuri → Socotra's (Fajr + 8.1).

**Open questions.**
- Winter and Ramadan. Winter rows start from 2026-12 and Ramadan 1448 about 2027-02-08. The monthly collection must go
  on, both for the gate's coverage and to confirm one rule all year (Q2).
- Dhuhr (96.7 %) and Asr (96.2 %) held out. Against VSOP87, the authority's noon drifts by up to about 6 s. In an
  analysis of the Dhuhr windows (outside `fit.py`), the common shift that fits all 31 locations on a date is about 0
  from May to August, and −0.05 to −0.11 min in late September and October. No sun model (TEXTBOOK holds only 3,611
  Dhuhr windows) or evaluation instant of the engine removes it, and Asr, which shares the noon, likely follows it.
  The winter rows will show whether the authority uses a lower-precision sun, which would be a new E14 model.
- Five computing points lie 4–10 km from their towns. The authority's coordinates would settle them.
- Whether the mosques of the Aden-held areas follow Sanaa's table (12).
