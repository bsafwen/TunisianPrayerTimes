#### Syria (`sy_awqaf`)

`fits/sy_awqaf/fit.py` (standard library, a few seconds) fits the Ministry of Awqaf's governorate tables and writes
`official-zones/sy_awqaf.json`. Every official table at hand is a Ramadan table (February–March), so the fit can only
check what a Ramadan shows; the method is applied all year (Q12).

**Method** (fitted 2026-10-15; changes from 8.2 and 8.7 in bold):

| Time | Rule |
|---|---|
| Fajr | 18°, **rounded up**, **E4 `KACST`** (declination at 21h UT, which is local midnight, equation of time at 9h UT) |
| Sunrise | standard horizon, rounded down, the sun at the event instant |
| Dhuhr | transit, rounded up |
| Asr | factor 1, geometric sun, rounded up, **E4 `KACST`**; **no E7 fraction** |
| Maghrib | standard sunset, rounded up, + 0, then + 2 from 2025-03-30 (E9, provisional) |
| Isha | 17°, rounded up, **E4 `KACST`**; **no E7 fraction** |
| Sun | MEEUS |

These are Umm al-Qura's conventions (Saudi Arabia's rounding, all up but sunrise down, and KACST's once-a-day sun for
the twilight and shadow times) with the MWL angles the ministry adopted and its own Maghrib minutes. Stages, by windows,
on Damascus 1447 and Raqqa 1446 together (55 days without the quirk rows), at the tables' town points:

1. Dhuhr: every sun and evaluation 54 of 55 (Dhuhr cannot tell them apart in a Ramadan).
2. Each time's evaluation (switching from the event instant only past 2 % of the days): Fajr `KACST` 54 (event instant
   47, sun at 10h UT 48), Isha `KACST` 55 (44), Asr `KACST` 54 on the geometric sun (the event instant 48 geometric, 54
   apparent: the tie goes to the geometric sun and the evaluation Fajr and Isha use), sunrise at the event instant 54,
   Dhuhr 54, Maghrib 53. Readings: Fajr up + 0, Isha up + 0, Asr up + 0, sunrise down + 0, Dhuhr up + 0, Maghrib up
   + 0 net of its Ramadan's minutes (+ 0 in 1446, + 2 in 1447).
3. The angles under these evaluations and roundings: Fajr 18.0023–18.0036° (54 at 18.0), Isha 16.9978–17.0040° (55 at
   17.0). So 18° and 17° exactly, and none of 8.7's fractions: the plan's Fajr rounded down and Asr and Isha + 0.25 at
   the event instant reproduce, on all 73 days at the town points, Fajr 64, sunrise 72, Dhuhr 72, Asr 63, Maghrib 70,
   Isha 67, against 70, 72, 72, 70, 70, 71 here.
4. Computing points, as the Libya fit places cities with few days: the town's latitude stays, its longitude too unless
   some longitude within 0.5° reproduces more of the table's minutes, each within the model's precision (0.018 min):
   Damascus 36.3015° E (0.5 km from the town), Raqqa 39.0125° E (0.4 km), Aleppo 37.1659° E (2.8 km), Daraa 36.1869° E
   (7.9 km: 10 of 12 minutes there, 7 at the town); the others stay at the town.

**Quirk days.** Damascus 1447's rows of 2026-02-21 and 02-22 (the table repeats the 20th and then holds the 21st's
values) and Raqqa 1446's of 2025-03-05 (it repeats the 4th): each matches the previous date's computed times better than
its own. 3 quirk days, counted apart (2 minutes off at worst).

**Zones and mapping.** 14 zones, one per governorate table (8.8): Damascus, Aleppo, Homs, Hama, Latakia, Tartus, Idlib,
Raqqa, Deir ez-Zor, Hasakah, Daraa, Suwayda, Quneitra and Eastern Ghouta («الغوطة الشرقية», at Douma, its main town);
`NEAREST` with no reach limit, no admin1 constraint, and the validity guard. `point` and named place: the research's
town points (`cities.py`) for ten, GeoNames' for Tartus, Raqqa and Deir ez-Zor, Douma for Eastern Ghouta; E6 morning
and evening points where step 4 moved the longitude. No E7 offsets.

**Data** (read at run time, never copied): `test-data-local/research-2026-10-08/SY/sy/dam.txt` (Damascus, Ramadan 1447,
2026-02-19 to 03-19), `sy/raq1446.txt` (Raqqa, Ramadan 1446, 2025-03-01 to 03-29) and `sy/cities.py`, read as text
(days 1 and 29 of nine more 1447 tables, and town points). The day-1 rows of the Eastern Ghouta, Tartus, Deir ez-Zor and
Raqqa 1447 tables exist only in `sy/heads.png`; checked by hand with the shipped values: Eastern Ghouta at Douma 5 of 5
(so Douma is its point), Deir ez-Zor 5 of 5, Tartus 4 of 5 (sunrise 1 minute), Raqqa 0 of 5: all five times 2 minutes
later than the method at Raqqa (Fajr 05:43 against 05:41), and 5 of 5 at Tabqa or any point of Raqqa's latitude at
38.48–38.52° E. The current Raqqa table thus seems computed about 45 km west of the city, unlike the 1446 one.

**Held-out scores** (FitStats: Damascus 1447 scored with the values fitted on Raqqa 1446, Raqqa 1446 with those fitted
on Damascus 1447, each with its computing point fitted on its other days, odd against even; the nine tables' two days
with the values fitted on both and their points fitted on the other day; each Ramadan keeps its own E9 Maghrib minutes,
which only its own table gives; 73 days, quirk days apart):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| All 73 days (FitStats) | 91.8 % | 95.9 % | 97.3 % | 98.6 % | 95.9 % | 95.9 % |
| Damascus 1447, 27 days | 22 | 26 | 27 | 27 | 27 | 26 |
| Raqqa 1446, 28 days | 28 | 26 | 27 | 28 | 27 | 28 |
| Nine tables, 18 days | 17 | 18 | 17 | 17 | 16 | 16 |

Every held-out time is within 1 minute. Damascus's Fajr misses are the fold's: Raqqa alone cannot choose `KACST` (27
days against 26 at the event instant), Damascus alone can (27 against 21). Daraa keeps 7 of 12 held out (one day cannot
place its point for the other). In-sample with the shipped values and points: 73, 72, 72, 73, 69, 71 of 73. Tier B
(9.5): met, within 1 minute; no zone can reach tier A on Ramadan days only.

**Validity guard** (side check, 2026, approximate town points): Tabqa (Raqqa's table, 43 km), Zabadani and Khan
Shaykhun keep their tables (at most 2.5 min beyond the margin); Al-Tanf (Eastern Ghouta's, 211 km, sunrise 9.3) takes the
method at its own point, as 8.8 expects. Of the places 8.8 leaves to the scan: Ras al-Ayn (Isha 4.6), Sukhnah (Fajr
6.3), Tell Abyad (Isha 4.2), Palmyra (sunrise 6.7), Malikiyah (sunrise 7.5) and Abu Kamal (sunrise 5.5) take their own
point; Qamishli (sunrise 3.5) and Nabek (2.5) keep the table. With every time rounded toward the safe side (up, sunrise
down), a table's own margin is at most 0 on every time, so the guard acts closer to the tables than 8.8's distances
suggest.

**The ministry's mosque screens (2026-10-18; not yet used).** The ministry's screen system and its app «مسجدك» load a
year table per region, `tv.masjidak.mow.gov.sy/upload/json_prayers_times/prayer_times_region_json_<id>.json`, ids
761–870 (109 regions, 100 distinct tables, 366 rows in a leap year's order; kept in
`test-data-local/research-2026-10-18/SY/masjidak/` with a README, never committed). Mosques print these: 6 of the
owner's 75 Syrian mosques match a region's table on all 365 days to the minute (regions 764, 844, 846, 847, Damascus
area), none the Ramadan tables above. They are another product than the printed Ramadan tables: Damascus (764–767)
differs from Ramadan 1447 by 1–2 minutes, and the method above, at Damascus, agrees with region 764 on only 75 Fajr,
161 sunrise, 165 Dhuhr, 56 Asr, 219 Maghrib and 182 Isha of 365 days (Asr up to 3 minutes off). In exploration
(`research-2026-10-18/SY/region_fits_2011_vsop87.tsv`): Damascus's table is a perpetual table of a year one before a
leap year (2015, 2007, 2011 tie), and at a fitted point (33.5095 N 36.2833 E) reproduces Fajr 18° 358, sunrise 356,
Dhuhr (transit + 1) 365, Maghrib (sunset + 2) 359 and Isha 17° 347 of 365, every time nearest at its own instant;
its Asr is factor 1 with the previous day's declination in the hour angle (318–325 of 365; the standard rule 64).
Aleppo's, Idlib's, Hama's and Rif Dimashq's regions fit that model at 90–98 %, the coast, Homs, the south and the
north-east (Raqqa, Hasakah, Deir ez-Zor) at 20–85 %: another reference year or other settings per group, not yet
found. Moving Syria to these tables (regions as zones, their own method) waits for that.

**Open issues.** (1) Only Ramadan tables in the fit; the screens' year tables above are the next source. (2) Raqqa's 1447 table points about 0.5° west of the city (one day, from the image): if
confirmed, Raqqa's computing point moves to about 38.50° E; transcribing `heads.png` into `test-data-local` would let
the fit use the four rows. (3) The E9 revision date stays the plan's (2025-03-30): the tables show + 0 in 1446 and + 2 in
1447, not when it changed. (4) Daraa's table fits a point 8 km east of the town on two days only.
