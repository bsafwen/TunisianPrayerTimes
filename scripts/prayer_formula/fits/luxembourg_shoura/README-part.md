### Luxembourg: the Shoura's table (`luxembourg_shoura`)

Fitted by `fits/luxembourg_shoura/fit.py` (2026-10-15, refitted on all 12 months 2026-10-17, under a minute); asset
`official-zones/luxembourg_shoura.json`, one zone, mapping `NATIONAL`.

**The table.** The Shoura of Luxembourg's 2026 timetable (shoura.lu), one table for the Grand Duchy, Luxembourg clock
time with summer time on its own dates. The transcription of 2026-10-11 has 336 days: no February page, 10 November
missing, and 18–19 November printed as October (read as November: their values are November's). February was read on
2026-10-17 from the Shoura's year PDF (`Prayer-Timetable-2026-EN.pdf`, uploaded in September 2026, page 2), whose
January and August pages equal the transcription: 364 days. It is a 2026 table, so it is fitted and scored at 2026
dates (no reference year). Its morning times and Dhuhr follow the sun at the event instant; its Asr, Maghrib and Isha
follow a sun taken much earlier: with the sun once a day at 5 h UT the evening columns fit far better than with any
engine mode (Maghrib 320 of 362 windows at the city centre, against 259 at 0 h UT, 253 at 10 h UT and 157 at the event
instant; printed by `fit.py`). The engine's nearest modes are kept per time (E4).

**Method** (stages of 9.4 by windows at 2026; an alternative must cover more than 0.5 % more windows than the default:
stage 0's evaluation, NEAREST, offset 0; VSOP87 1,673 windows against MEEUS's 1,667, so MEEUS):

- Fajr **12.8°** at the event instant, NEAREST (the windows' own angle 12.824°, 305 of 363; 275 at 12.8°).
- Sunrise and Dhuhr at the event instant, NEAREST, no offset (312 of 364 and 331 of 363).
- Asr factor 1, the sun at 10 h UT (`DAILY_10H_UT`), NEAREST (273 of 342).
- Maghrib at sunset, the sun at 0 h UT (`DAILY_0H_UT`), NEAREST (244 of 362).
- Isha **12.8°**, the sun at 0 h UT, **UP** (232 of 364; the windows' own angle 12.812°, 239). On the eleven months
  of 2026-10-15 the sun at 10 h UT won (212 of 336); February tips it to 0 h UT, and the profile follows
  (`EuropeanPresets.LUXEMBOURG_SHOURA`).
- Stage 0 (one evaluation for all six at the centre, 13° / 13°, NEAREST): `DAILY_10H_UT` 1,014 of 2,158 windows,
  `DAILY_0H_UT` 969, the event instant 891, `KACST` 742. `AngleBasedAbsentNights` (Q37) never acts: 12.8° is reached
  every night south of 53.76°N, and in-sample no time is ESTIMATED.

**Zone.** `lu`, «لوكسمبورغ»: its point is Luxembourg City's centre (GeoNames 2960316, 49.61167 N 6.13 E), its named
place; the fitted computing point 49.61667 N 6.22259 E (6.7 km east) is its E6 morning and evening point (49.63667 N
on the eleven months). Mapping `NATIONAL`: every Luxembourg place takes it where the validity guard allows (6.5).

**Data** (test-data-local, read at run time, never copied): `research-2026-10-11/LU/shoura2026.csv` and
`research-2026-10-17/LU/shoura2026_february.csv` (its README gives the source and the checks).

**Held out** (month split: the stages refitted on the odd months score the even ones, and the reverse; common.compute;
342–364 days per time), exact / within 1 min:

| Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|
| 86.2 / 100 % | 84.6 / 100 % | 89.0 / 100 % | 79.8 / 100 % | 67.7 / 100 % | 59.1 / 100 % | 1 min |

- In-sample, 63.7–90.9 % exact, every time within 1 min. The eleven months held out 55.7–89.9 % exact, Asr's 21
  August 2 min off (now a quirk day, below).
- The preset before the fit (13° / 13°, no offsets, NEAREST, the event instant, at the centre): Fajr 9.9 % exact and
  85.4 % within 1, Isha 21.7 % and 50.5 %, Maghrib 91.4 % within 1, up to 4 min off. The ±4 of 9.2 ("until its fit is
  settled") can become ±1, with the documented misprints skipped.
- Both halves find Fajr 12.8° at the event instant, sunrise and Dhuhr at the event instant, Asr at 10 h UT and Isha
  12.8° rounded up, on VSOP87; they differ on Isha's evaluation (0 h UT on the odd months, 10 h UT on the even ones)
  and take Maghrib at 10 h UT, and their points lie within 3.4 km of the final one.
- No zone record can pass tier A (9.5): every month is held out, but 59–89 % exact per time.

**Quirk days** (26 dates, counted apart): Maghrib on 27 and 28 March and Fajr on 25 October carry the summer-time hour
on the wrong side of its change (60 min off); Dhuhr on 2 October is printed 13:34 between 13:25 and 13:24; Asr from
2 to 23 August is printed one row late: 17:49 on both the 1st and the 2nd, never 17:29 (17:30 on the 23rd, 17:28 on
the 24th), each printed minute the day before's. The PDF prints it so (read 2026-10-17); three mosques of the owner's
dataset that print the table everywhere else (Bettembourg, Mamer, Mersch) print the unslipped column, which the
profile computes (21 August: 17:31.4 computed, 17:31 at the mosques, 17:33 printed).

**Open.**
1. The evening columns' early sun (5–6 h UT) is no engine mode: by the windows at the centre, an E4 mode at that hour
   could lift Maghrib from 67 % to about 88 % exact and Isha from 64 % to about 77 %, but every time is already
   within 1 min, so none is proposed for the first release.
2. The 2027 table, when published, is the held-out year (whether the Shoura keeps the same program, and the same early
   evening sun); 10 November 2026 is not transcribed.
3. Troisvierges and the north, near the validity guard's threshold (6.5), are for the scan with this point.
4. The mosques that print the Shoura's table (5.6) are not followers for a year (`fits/mosque_years.py`): their
   December sunrise is 7–8 min early, 97.2 % of the minutes exact.
