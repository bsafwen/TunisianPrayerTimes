### Luxembourg: the Shoura's table (`luxembourg_shoura`)

Fitted by `fits/luxembourg_shoura/fit.py` (2026-10-15, under a minute); asset
`official-zones/luxembourg_shoura.json`, one zone, mapping `NATIONAL`.

**The table.** The Shoura of Luxembourg's 2026 timetable (shoura.lu), one table for the Grand Duchy, Luxembourg clock
time with summer time on its own dates. The transcription has 336 days: no February page, 10 November missing, and
18–19 November printed as October (read as November: their values are November's). It is a 2026 table, so it is
fitted and scored at 2026 dates (no reference year). Its morning times and Dhuhr follow the sun at the event instant;
its Asr, Maghrib and Isha follow a sun taken much earlier: with the sun once a day at 5–6 h UT the evening columns
fit far better than with any engine mode (Maghrib 292 of 334 windows at the city centre, against 242 at 10 h UT, 233
at 0 h UT and 157 at the event instant; printed by `fit.py`). The engine's nearest modes are kept per time (E4).

**Method** (stages of 9.4 by windows at 2026; an alternative must cover more than 0.5 % more windows than the default:
stage 0's evaluation, NEAREST, offset 0; VSOP87 1,551 windows against MEEUS's 1,564, so MEEUS):

- Fajr **12.8°** at the event instant, NEAREST (the windows' own angle 12.815°, 289 of 335; 278 at 12.8°).
- Sunrise and Dhuhr at the event instant, NEAREST, no offset (291 of 336 and 304 of 335).
- Asr factor 1, the sun at 10 h UT (`DAILY_10H_UT`), NEAREST (253 of 336; at the centre, at the event instant, 186).
- Maghrib at sunset, the sun at 0 h UT (`DAILY_0H_UT`), NEAREST (226 of 334).
- Isha **12.8°**, the sun at 10 h UT, **UP** (212 of 336; the windows' own angle 12.787°, 216).
- Stage 0 (one evaluation for all six at the centre, 13° / 13°, NEAREST): `DAILY_10H_UT` 970 of 2,012 windows,
  `DAILY_0H_UT` 890, the event instant 853, `KACST` 729. `AngleBasedAbsentNights` (Q37) never acts: 12.8° is reached
  every night south of 53.76°N, and in-sample no time is ESTIMATED.

**Zone.** `lu`, «لوكسمبورغ»: its point is Luxembourg City's centre (GeoNames 2960316, 49.61167 N 6.13 E), its named
place; the fitted computing point 49.63667 N 6.22259 E (7.1 km east-north-east) is its E6 morning and evening point.
Mapping `NATIONAL`: every Luxembourg place takes it where the validity guard allows (6.5).

**Data** (test-data-local, read at run time, never copied): `research-2026-10-11/LU/shoura2026.csv`.

**Held out** (month split: the stages refitted on the odd months score the even ones, and the reverse; common.compute;
334–336 days per time), exact / within 1 min:

| Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|
| 76.7 / 100 % | 85.7 / 100 % | 89.9 / 100 % | 72.6 / 99.7 % | 56.6 / 100 % | 55.7 / 100 % | 2 min |

- One time is 2 min off: Asr on 21 August. In-sample, 63.1–90.4 % exact, every time but one Asr within 1 min.
- The preset before the fit (13° / 13°, no offsets, NEAREST, the event instant, at the centre): Fajr 10.1 % exact and
  84.2 % within 1, Isha 23.5 % and 54.8 %, Maghrib 92.8 % within 1, up to 4 min off. The ±4 of 9.2 ("until its fit is
  settled") can become ±1 (Asr ±2, for one day), with the documented misprints skipped.
- Both halves find Fajr 12.8° at the event instant, sunrise and Dhuhr at the event instant and Isha 12.8° rounded up;
  they differ on the evening evaluations (0 h or 10 h UT, within a few windows of each other), on Maghrib's rounding
  and on the sun model, and their points lie within 2 km of the final one.

**Quirk days** (4, counted apart): Maghrib on 27 and 28 March and Fajr on 25 October carry the summer-time hour on the
wrong side of its change (60 min off); Dhuhr on 2 October is printed 13:34 between 13:25 and 13:24.

**Open.**
1. The evening columns' early sun (5–6 h UT) is no engine mode: by the windows at the centre, a `DAILY_6H_UT`-like E4
   mode could lift Maghrib from 68 % to about 87 % exact and Isha from 63 % to about 77 %, but every time is already
   within 1 min (Asr's one day aside), so none is proposed for the first release.
2. One year, eleven months: the 2027 table, when published, is the held-out year (whether the Shoura keeps the same
   program, and the same early evening sun).
3. Troisvierges and the north, near the validity guard's threshold (6.5), are for the scan with this point.
