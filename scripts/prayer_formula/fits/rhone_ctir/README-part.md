### Rhône: the CTIR's unified calendar (`rhone_ctir`)

Fitted by `fits/rhone_ctir/fit.py` (2026-10-15, about 30 s); asset `official-zones/rhone_ctir.json`, one zone.

**The table.** The Conseil Théologique des Imams du Rhône's 2020 table, one table for the département, computed "from
Lyon's latitude and longitude" (cmrofficiel.fr, harmonisation page) and still called by date in 2026 (Q33). Two things
the fit found in it:

- **Its summer-time rows (03-29 to 10-24) hold the previous date's times.** Read so, the profile below gives the
  printed sunrise and Maghrib on 97–98 % of the 366 rows; read at their own date, on 49 %. This is the "drift with
  the season" noted in 7.2 and 9.2. It is a quirk of the table (8.3), not reproduced: the app computes the actual
  date (A8).
- **Fajr, Isha and Asr use a declination taken late in the day** (about 22–23 h UT; sunrise, Dhuhr and Maghrib use the
  sun at the event instant). E4's `KACST` (declination at 21 h UT, equation of time at 9 h UT) comes closest of the
  engine's modes: Fajr 329 of 366 windows, against at most 121 at the event instant (any angle or rounding); Isha 328
  against 290; Asr 349 against 319.

**Method** (fitted at the reference year 2020, E5; month-split): Fajr 14.5°, Isha 13.5° (the windows' own values,
14.497° and 13.4998°, kept to 0.1°), Asr factor 1; Dhuhr + 5 and Maghrib + 2; UP on the five prayers and DOWN on
sunrise; `KACST` for Fajr, Isha and Asr. MEEUS (VSOP87 covers 0.1 % more windows). Both halves of the month-split
find the same rules. E19 never moves a time at Lyon.

**Zone.** `69`, «الرون», at the fitted point 45.7441 N 4.8425 E (0.6 km from GeoNames' Lyon, 45.7491 N 4.8479 E,
read 2026-10-15), Lyon as its named place. Mapping `NATIONAL` with `admin2` ["69"]: a `NATIONAL` zone that lists admin areas covers only those, so a French
place outside département 69, or with no stored département, takes the method at its own point (6.5, Q33).

**Data** (test-data-local, read at run time, never copied): `research-2026-10-11/FR/ctir_2020.txt`, the text of
`Horaire-CTIR-horaire-2020.pdf`, 366 rows, Paris clock time (summer time on the same dates as in 2026).

**Held out** (month-split: each half scored with the rules fitted on the other; each row against the app's 2026
computation for the same date, as the mosques show the table in 2026; 365 days, E19 included): exact / within 1 min:

| Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|
| 33.4 / 99.5 % | 39.5 / 99.5 % | 87.9 / 100 % | 66.0 / 100 % | 39.7 / 99.7 % | 35.9 / 99.7 % | 2 min |

- At 2020, the summer rows read as the previous date (in-sample): 89.6–99.5 % exact, every time within 1 min.
- The minutes 2026 misses come from the leap cycle: on a 2026 date the sun stands about half a day behind 2020's, and
  the summer rows a day behind that. In 2027, 51–90 % exact and 96.4–100 % within 1 (worst 2); in 2028, a leap year,
  the winter rows match and the summer rows are a full day off: 64.7–80.5 % within 1 for Fajr, sunrise, Maghrib
  and Isha (worst 3).
- The preset before the fit (Maghrib + 3 and sunrise nearest, the sun at the event instant): within 1 min on 84.1 %
  (Fajr), 86.6 % (Isha), 94–96 % (sunrise, Asr, Maghrib), worst 3.

**Open.** A Kotlin test on the 2020 dates (9.2) should read the
summer rows at the previous date (then every time is within 1 min); read at their own date, Fajr, Maghrib and Isha
reach 3 min. A table after 2020 would be fitted again.
