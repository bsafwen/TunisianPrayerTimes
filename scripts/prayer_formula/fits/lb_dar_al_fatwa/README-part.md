#### Lebanon (`lb_dar_al_fatwa`)

`fits/lb_dar_al_fatwa/fit.py` (standard library, about 1 to 2 minutes) fits Dar al-Fatwa's Beirut table and writes
`official-zones/lb_dar_al_fatwa.json`. The only official table is the Ramadan 1445 imsakiya (30 days); the perpetual
366-row year table that Dar al-Fatwa's own screen at Ras Beirut shows (tv.masjidi.app, region 2) equals it on all 180
times (checked at each run), so the fit runs on the year table and scores the imsakiya as its official copy. The table
is perpetual and old: the fit reads it at a reference year (E5), and the app shows it as printed, every date as its
month and day of 1931 (A8, revised by the owner on 2026-10-17; `referenceYear`).

**Method** (fitted 2026-10-15; changes from 8.2 and 8.7 in bold):

| Time | Rule |
|---|---|
| Fajr | **19.69°**, nearest, **the sun once a day at 0h UT (E4 `DAILY_0H_UT`)** |
| Sunrise | **a fixed −0.9379° horizon (E2 `Fixed`), nearest, + 0, the sun at 10h UT (local noon, E4 `DAILY_10H_UT`)** |
| Dhuhr | transit, rounded down, the sun at the event instant |
| Asr | factor 1, geometric sun, rounded down, **the sun at 10h UT** |
| Maghrib | standard sunset + 4, rounded up, **the sun at 10h UT** |
| Isha | **17.5°**, nearest, **the sun at 10h UT** |
| Sun | MEEUS; reference year **1931** (1903's leap phase, as Q10 found) |

So the table was computed the way an almanac-based table is: the day's sun taken once, at local noon, for sunrise,
Asr, Maghrib and Isha (and Dhuhr, where it makes no difference), and at 0h UT, the night before, for Fajr. Stages, by
windows, on the year table without its quirk rows (353 rows at 1931):

1. Dhuhr alone (the equation of time): MEEUS 347 of 354 windows (sun at 0h UT, 1926), VSOP87 346, TEXTBOOK 313; no
   model reproduces the column, so the table's own sun is not one of the engine's.
2. The design, by the six free covers (of 2,124): one evaluation for all six, at best 1,833 (event instant, 1915),
   1,839 (0h UT, 1946) or 1,840 (10h UT, 1931); each time its own, 1,946 at 1931 with MEEUS (1,949 with VSOP87, within
   0.5 %, so the default MEEUS stays). With the event instant Fajr covers 300 and sunrise 247; with the noon sun sunrise
   covers 332 but Fajr 180: only the per-event map reproduces both.
3. The point: latitude 33.890° (the four horizon and twilight covers; town 33.894°); longitude from Dhuhr, whose shift
   at the town (−0.334 min) is nearest the reading "rounded down" at 35.4606° E, 3.8 km west of the town (Ras Beirut;
   nearest would put it 8 km east). The zone keeps the town as `point`; the computing point is its E6 morning and
   evening point.
4. Readings: Dhuhr down + 0 (343), sunrise nearest + 0 at −0.9379° (333; the standard horizon rounded down 323, the
   2 % rule decides), Maghrib up + 4 on the standard horizon (328; a fixed −0.835° gives 331), Asr down + 0 geometric
   (329; apparent 326), Fajr 19.69° (290; the window is 19.67–19.71° whatever the hour or the year of its once-a-day sun,
   so it is not the Egyptian 19.5°, which holds 13–28 days), Isha 17.50° (315).

**Quirk days.** The year table's rows of 1 to 12 March hold the next day's values (each matches the next date's
computed times better than its own by 1.5 min or more over Fajr, sunrise, Maghrib and Isha; Fajr and sunrise jump 3
minutes from 28 February to 1 March); the imsakiya's 2024-03-11 and 03-12 are those rows. 14 quirk days in all, counted
apart.

**Zones and mapping.** One zone, «بيروت» (`beirut`), `point` and named place at the town (33.894 N 35.502 E), E6 points
at 33.8900 N 35.4606 E; `NATIONAL` for all of Lebanon, with the validity guard (6.5). No admin1 list.

**Data** (read at run time, never copied): `test-data-local/research-2026-10-11/LB/imsak1445.csv` (the imsakiya,
2024-03-11 to 04-09) and `research-2026-10-08/LB/lb/region2.json` (the year table).

**Held-out scores** (FitStats: the year table by month-split, each half's values scored on the other half for 2026, and
the imsakiya's days for 2024 by the half that left their month out, as the app shows them: as printed; quirk days
apart):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| Exact (FitStats) | 79.3 % | 92.4 % | 95.3 % | 93.4 % | 93.2 % | 87.7 % |
| Within 1 min (FitStats) | 100 % | 99.7 % | 100 % | 100 % | 100 % | 100 % |
| Imsakiya for 2024, exact (28 days) | 89.3 % | 92.9 % | 89.3 % | 96.4 % | 96.4 % | 71.4 % |
| Exact (FitStats) for each date's own sun, before A8's revision | 18.1 % | 26.8 % | 73.8 % | 53.5 % | 27.8 % | 30.7 % |

Worst held-out difference 2 minutes (one sunrise). In-sample at 1931, which the app shows every year: 288, 333, 343,
329, 328, 313 of 353, worst 2; the imsakiya 26, 26, 28, 29, 27, 20 of 30. Before A8's revision the held-out
differences reached 3 minutes, since the table's sun lags the real sun by about a day. Tier A: not reached (Fajr and
Isha 79–88 % at best, the table's seasonal departures below), nor reachable on one official Ramadan (9.5). Tier B:
every held-out time within 1 minute but one sunrise (99.7 %).

**Validity guard** (side check, 2026, approximate town points): the table's own margins at Beirut are Fajr + 0.3,
sunrise + 0.7, Dhuhr + 0.8, Asr + 0.8, Maghrib − 0.2, Isha + 0.3 min. Saida, Tripoli, Zahle, Baalbek and Nabatieh keep
Beirut's table (at most 3.2 min beyond the margin, Baalbek's sunrise); Tyre and Naqoura (Fajr 4.5 and 5.5) and Hermel,
Qaa and Qoubaiyat (sunrise 4.9, 5.1, 4.9) take the method at their own point, as 8.8 expects.

**Open issues.** (1) One official Ramadan only: a second official imsakiya would confirm the reference year and the
per-event evaluations. (2) The table's own sun is none of the engine's (Dhuhr 347 of 354 at best); the misses at the
reference year are its seasonal departures (Fajr's angle wanders 19.62–19.75° by month). (3) The sunrise reading is
close between −0.94° rounded to nearest and the standard horizon rounded down (one month-half picks each). (4) The
North's mosques call another table (12); its publisher is unknown.
