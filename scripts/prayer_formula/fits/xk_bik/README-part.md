### Kosovo and the Preševo Valley: BIK's Takvim (`xk_bik`)

Fitted by `fits/xk_bik/fit.py` (2026-10-16, under 10 s); asset `official-zones/xk_bik.json`, 11 zones, mapping
`LISTED_PLACES`, country `XK` (the Preševo Valley's zones carry `RS`). The Takvim PDF is read at run time by
`fits/xk_bik/takvim_pdf.py`, a standard-library PDF text reader (object streams, Flate, ToUnicode); the town radii come
from `fits/town_extents.py`.

**The table.** The Presidency of the Islamic Community of Kosovo's Takvim 2026, calendar part («Pjesa kalendarike»,
dituriaislame.com), prints for every day Imsaku, Sabahu, sunrise, Dreka, Ikindia, Akshami, Jacia and the day's length,
in local clock time. Page 4: computed at 42.5°N 21°E with a temkin of 1.5° (6 min); Sabahu, the start of the Fajr
prayer, is Imsaku + 20 (365 of 365 rows), and the app shows it as Fajr (A14). Page 6 («Diferenca e vendeve»): Sharri
+2, Ferizaj, Gjilani, Prishtina, Podujeva and Vushtrria −1, Presheva −2, on all times. The day's length equals Akshami
− sunrise on all 365 rows. **The table is perpetual**: the 2025 edition equals it by date on every time, summer time
removed, so like `bosnia_iz`'s older columns it is fitted on the years the app computes, 2026–2029 pooled, read by date
(A8). It is hand-smoothed: in early October Sabahu holds almost still for six days, then catches up 2 min a day (a
3-min dip against the 18° time over ten days), and sunrise and Dreka step irregularly.

**Method** (E18): the six plain events at 42.5°N 21°E, Fajr 18° and Isha 17° (the preset's; 16–21° and 15–19° change
the held-out shares by about 1 point), sunrise and sunset −0.8333°, Asr factor 1, the sun at the event instant (the sun
at 0 h UT meets 6,472 printed minutes exactly against 6,483), MEEUS, NEAREST, plus 36 seasonal values per time,
interpolated by the day of the year. They are fitted by windows: each printed minute bounds the day's interpolated
value to a half-open minute; each knot is moved in turn to the value covering the most weight (a minute within 1
weighs 100, an exact one 1, since both tiers need 100 % within 1), from each knot's median window, until none moves;
values to 0.01 min. Without E18 the plain events are never within 1 min (up to 18 min off).

| Time | E18 values, minutes (knot k at day 1 + k × the year's days / 36) |
|---|---|
| Fajr | 15.69 14.83 16.34 16.59 14.59 15.47 15.34 15.79 16.54 13.75 15.29 15.06 12.76 11.65 12.41 10.83 8.58 9.92 9.06 8.66 9.08 10.35 11.77 13.18 13.47 13.03 14.57 12.52 13.90 14.11 13.91 14.85 15.23 15.55 15.88 17.16 |
| Sunrise | −3.92 −4.18 −2.78 −4.30 −3.60 −4.23 −6.32 −5.54 −6.90 −7.52 −7.32 −7.94 −6.60 −8.11 −8.74 −8.80 −10.01 −7.74 −8.88 −9.78 −11.51 −8.28 −8.56 −9.42 −8.61 −8.50 −9.25 −8.86 −8.24 −6.48 −5.48 −6.03 −3.36 −4.03 −3.63 −2.89 |
| Dhuhr | 4.63 3.35 4.86 4.93 3.40 4.84 2.08 3.95 4.22 3.18 2.78 2.21 2.78 1.83 2.04 1.94 1.13 2.91 2.85 1.88 1.27 2.68 4.17 2.48 3.98 4.05 3.90 2.54 3.59 4.27 4.39 4.31 4.50 4.63 4.80 5.70 |
| Asr | 8.94 7.43 6.97 7.00 6.32 6.58 4.68 3.52 2.88 3.13 1.95 0.32 2.89 1.20 2.72 3.15 0.85 4.11 2.91 3.59 2.59 3.54 5.19 2.51 5.79 6.50 5.70 4.84 9.31 8.71 9.40 8.65 8.40 9.05 9.53 11.53 |
| Maghrib | 6.97 6.71 7.09 7.22 7.41 7.89 7.06 7.64 7.55 7.38 7.32 7.14 6.92 5.87 6.92 6.36 5.34 9.03 7.10 7.14 6.85 8.27 8.70 8.08 8.96 8.67 8.40 7.63 8.76 8.17 7.48 6.46 6.91 7.47 7.63 8.42 |
| Isha | 10.32 9.97 9.74 8.66 9.80 10.51 9.50 10.79 9.76 9.38 8.98 8.81 9.08 5.69 7.38 6.86 5.59 10.00 9.01 9.82 8.73 10.02 12.29 11.42 11.91 10.75 11.84 9.62 11.14 10.05 10.06 8.32 9.18 10.21 9.92 10.65 |

**Zones.**

- `kosovo`, «كوسوفو», the Takvim's own point 42.5°N 21°E, the base of every other zone and, beyond every town's reach,
  the zone of every place in Kosovo and of the Valley's places (`listed`'s fallback).
- The seven towns, each the base plus its published shift on all six times, its point the town's centre in
  OpenStreetMap (GeoNames puts Dragash 4 km south of the town) and `reachKm` the town's extent: the farthest vertex of
  its OpenStreetMap boundary from that point, rounded up to 0.5 km: `sharr` (Dragash, R8600255) +2, 3 km; `ferizaj`
  −1, 4 km; `gjilan` −1, 3.5 km; `prishtina` −1, 7 km; `vushtrri` −1, 6 km; `podujeve` −1, 4 km (a point only in
  OpenStreetMap: the median of the other Kosovo towns, 3.67 km); `presevo` (RS) −2, 5 km (its settlement boundary,
  R6654047).
- The Preševo Valley's three, unshifted, `country` `RS`, «وادي بريشيفو», on Kotlin's Valley points (CountryMethodDefaults):
  `valley-presevo` 15 km, `valley-bujanovac` 13.5 km, `valley-medvedja` 13 km. Each radius leaves the least area wrong
  against its own municipality (OpenStreetMap R1630000, R1629999, R2860306): the municipality outside the disc plus the
  neighbouring Serbian municipalities inside it (Vranje, Trgovište, Lebane, Bojnik, Kuršumlija; Kosovo and North
  Macedonia count as neither, they have their own defaults), on a 0.25 km grid, from 5 km to the municipality's
  farthest point. Preševo's 15 km holds its whole municipality and no neighbour; at 12 km Bujanovac leaves 257 km² wrong
  (13.5 km: 257), Medveđa 249 (13 km: 244), Preševo 10 (15 km: 0). Vranje, 14.9 km from Bujanovac, stays outside.
- Preševo's town zone and its Valley zone share one point and the town comes first, so in town the shifted zone wins
  the tie of `LISTED_PLACES` (`minByOrNull` keeps the first of equal distances).

**Data** (test-data-local, read at run time, never copied): `official/xk/takvim/takvimi2026vaktet.pdf` and
`takvimi2025.pdf` (fetched 2026-10-16, MANIFEST.tsv), `research-2026-10-16/osm/` (the boundaries, © OpenStreetMap
contributors, ODbL).

**Held out** (alternate rows: the values fitted on the table's odd days score its even days, and the reverse, in
each of 2026–2029; common.compute at the base zone), exact / within 1 min:

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|---|
| 2026–2029 | 56.4 / 96.2 % | 66.2 / 99.0 % | 81.8 / 100 % | 72.6 / 100 % | 67.3 / 99.9 % | 58.2 / 99.0 % | 2 min |
| 2026 | 61.6 / 99.2 % | 74.8 / 99.5 % | 81.9 / 100 % | 74.0 / 100 % | 74.8 / 100 % | 65.2 / 99.7 % | 2 min |
| 2028 | 53.7 / 93.4 % | 58.9 / 98.1 % | 82.2 / 100 % | 72.1 / 100 % | 59.7 / 99.7 % | 51.8 / 98.6 % | 2 min |

- In-sample (the shipped values), 2026: 65.2 / 80.8 / 90.7 / 83.6 / 85.5 / 73.4 % exact, every time within 1 min but
  0.8 % of Fajr and 0.5 % of sunrise (2 min); 2028 the worst year (Fajr 94.8 % within 1, 3 min once).
- The towns' records equal the base's: a whole-minute shift on both sides keeps every minute's difference.
- No record passes 9.5's tier A (97 % exact per time); 9.2's ±2 holds on every held-out day.

**Open.**
1. The hand-smoothed stretches (early October's Fajr, sunrise and Dreka steps) cap the reproduction; 36 knots cannot
   follow a 3-minute dip over ten days.
2. Podujevë's radius is an estimate (no town boundary in OpenStreetMap).
3. Kotlin's `CountryMethodDefaults.PRESEVO_REACH_KM` (12 km for the three towns) can take the three radii above, or read
   them from the asset's Valley zones.
