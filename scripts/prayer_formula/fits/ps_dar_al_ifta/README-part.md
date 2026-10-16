#### Palestine, West Bank and Jerusalem (`ps_dar_al_ifta`)

`fits/ps_dar_al_ifta/fit.py` (2026-10-15, about 30 s on 16 workers) fits Dar al-Ifta's perpetual table «توقيت الصلاة
الدهري حسب توقيت المسجد الأقصى المبارك» (Jerusalem 1433/2012, a reprint of an older table, still served on darifta.ps)
and writes `official-zones/ps_dar_al_ifta.json`. The table gives 366 rows keyed by month and day, in winter time (UTC+2)
all year; the fit reads each row at a reference year (E5) and every held-out score is computed for the actual year,
as the app shows it (A8).

**Method** (each time fitted on its own; the windows count the times within 1 minute first, the tier-B gate, then
the exact ones, since no formula reproduces the table to the minute):

| Time | Rule | Exact at the reference year (of 366) |
|---|---|---|
| Fajr | 18.065°, rounded down | 211 |
| Sunrise | horizon `Fixed(-1.5559)`, rounded up | 256 |
| Dhuhr | transit, rounded down (the table truncates it) | 331 |
| Asr | factor 1 on the apparent sun (E13, standard air), rounded down | 295 |
| Maghrib | horizon `Fixed(-1.0643)` + 5 min, nearest | 257 |
| Isha | 17.847°, rounded up | 212 |
| All | MEEUS sun at each event's instant, reference year 2016, point 31.858 N 35.2061 E | every time within 1 min |

- Dhuhr alone points to years of 1994's and 1998's phase (MEEUS 1998: 362 of 365; VSOP87 1994: 364), at most 345 in a
  leap year. All six times together prefer a leap year, as the table's 29 February row does: 2016 (MEEUS: all 2,196
  within 1 minute, 1,563 exact), 2012 (1,561), then 2008 and 2004; VSOP87 does no better (2016: 1,555).
- The point is fitted: 9 km north of Al-Aqsa and 2.7 km west, 4.6 km from Ramallah's town point; the month halves put
  it at 31.866 and 31.886 N, 35.206 E. It is the zone's E6 point; the zone's own `point` is Jerusalem's.
- Simpler forms score lower at the reference year (a side test): 18°/18°, the standard sunrise − 3 and sunset + 6,
  nearest, Dhuhr down, at the same point, gives Fajr 135 and Isha 180 exact, with 46 Fajr and 14 Isha 2 min off (the
  current profile also lacks the − 3 on sunrise). Maghrib offsets above 6 min are not tried: with a raised horizon (up to −0.36° + 8
  min) they gain at most 4 minutes, by bending the season rather than by any rule.

**Zones** (16, `LISTED_PLACES`): the 16 places in Palestine that the table's page 2 lists. `quds` (Jerusalem, Al-Aqsa's
table) is the base: `point` at GeoNames' East Jerusalem, the fitted point in `morningPoint` and `eveningPoint`. The 15
others have `baseZoneId` `quds` and `offsets` on all six times: Ramallah, Bethlehem, Jenin and Nablus 0; Jericho −1;
Hebron, Idhna, Dura, Beit Awwa and Tulkarm +1; Qalqilya +2 (printed +1.5, owner rule, 8.8); Gaza +3; Rafah, Khan Younis
and Deir al-Balah +4. Ids are slugs; `nameAr` as Arabic usage writes the towns (the table's page 2 spells them the same,
«بيت عوا», «إذنا»). `point` and the one named place: the town (GeoNames, read 2026-10-15; Idhna and Beit Awwa
approximate). The 13 places in Israel are not used (8.8).

**Mapping.** `LISTED_PLACES`: the nearest of the 16, whatever the distance (8.8, Q1). The Gaza places serve when the
profile is chosen in Gaza (A17).

**Data** (test-data-local, read at run time, never copied): `research-2026-10-08/PS/ps/dahri2012.txt` (the 366 rows,
parsed from `ssalah2012.pdf` pages 4-27; an independent parse is identical) and, read by eye,
`research-2026-10-08/PS/pdf2012/p02.png` (the differences).

**Held-out scores** (the table split by month, each half fitted on the other; every held-out row computed for 2026,
the 29 February row for 2028; common.compute):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| Exact | 40.4 % | 44.0 % | 95.9 % | 74.6 % | 59.8 % | 50.8 % |
| Within 1 minute | 92.6 % | 97.5 % | 100 % | 100 % | 99.5 % | 100 % |

The final profile computed for each year (exact / within 1, of 365 or 366):

| Year | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| 2016 (reference) | 211 / 366 | 256 / 366 | 331 / 366 | 295 / 366 | 257 / 366 | 212 / 366 |
| 2025 | 154 / 358 | 195 / 365 | 337 / 365 | 271 / 365 | 229 / 359 | 190 / 360 |
| 2026 | 125 / 342 | 160 / 356 | 350 / 365 | 282 / 365 | 219 / 364 | 188 / 365 |
| 2027 | 105 / 313 | 147 / 337 | 341 / 365 | 267 / 365 | 180 / 365 | 184 / 364 |
| 2028 | 215 / 366 | 260 / 366 | 325 / 366 | 283 / 366 | 246 / 366 | 198 / 361 |

The leap phase carries most of the difference: the further a year is from a leap year, the further its sun is from
the table's. Every place's record equals Al-Aqsa's, shifted by its whole-minute difference; Qalqilya's +2 meets either
neighbouring minute of the printed +1.5 on 79 % of times.

**Tier.** B (9.5) is met only at the reference year: computed for the actual year (A8), up to 7 % of Fajr times and
2.5 % of sunrise times are 2 min off in 2026 (worst 2). The chip stays «مستخلصة من جداول».

**Quirk days.** None identified. The table's residuals against every formula tried wander by about ±1.5 min with the
season (against the fitted rule, Fajr runs earliest from February to May, Isha earliest in June and latest in August and
September), more than one rounding explains; no single row stands out.

**Open questions.**
- The table's own source (an almanac or program, its year and point) is unknown; ICOP's preset (18/18, elevation)
  matches only the angles roughly.
- Dar al-Ifta's Ramadan imsakiyas of 2023 to 2025 (images, `research-2026-10-08/SY/img2/`) equal the table (research of
  2026-10-08) and were not re-transcribed.
- Tubas, Salfit and Qalqilya's area have no mosque data to confirm the nearest-place rule (Q1).
