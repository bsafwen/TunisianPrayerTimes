#### Bahrain (`bh_calendar`)

`fits/bh_calendar/fit.py` (about 15 seconds) fits the Bahraini calendar of 1448 that the Supreme Council for Islamic
Affairs (SCIA) issues, and writes `official-zones/bh_calendar.json`. The SCIA's page states the method (two points,
Isha at 18°, Dhuhr once the whole disc has crossed the meridian); the fit keeps what the calendar confirms and fits
the rest.

**Method.**

| Time | Rule | Rounding (E1) |
|---|---|---|
| Fajr | 18.01° at the morning point | down |
| Sunrise | `Fixed(−0.83°)` at the morning point | down |
| Dhuhr | the transit at the evening point + 1.07 min (`dhuhrAfterTransitMin`, E7: the whole disc past the meridian) | down |
| Asr | shadow factor 1, geometric sun, the noon shadow at the declination of the Asr instant itself (E4 `SHADOW_AT_EVENT`) | down |
| Maghrib | sunset at `Standard` (−0.8333°) at the evening point | nearest |
| Isha | 17.82° at the evening point | down |

VSOP87 (E14). The zone's E6 points keep the stated longitudes with fitted latitudes: morning 26.205 N 50.68 E,
evening 26.175 N 50.44 E (stated: 26.27 N 50.68 E, printed «68.50», and 26.22 N 50.44 E).

These values are fixed in fit.py (`FIRST_FIT`, each fold's own included): the stages below chose them on 2026-10-15,
with Asr on `DAILY_10H_UT`, and they were kept when Asr moved to `SHADOW_AT_EVENT`, so a run reproduces them and only
rescores; `--refit` runs the stages again (for a new calendar).

**Zone and mapping.** `NATIONAL`: one zone «البحرين» for the whole Kingdom, the Hawar Islands included, with the
validity guard (8.8). Its `point` is Manama (GeoNames 290340), which serves no computation; it has no named places
(the SCIA names none).

**Data** (test-data-local, read at run time, never copied): `research-2026-10-11/BH/bh1448.csv`, the 355 days of
1448 (2026-06-16 to 2027-06-05) from the book PDF's text layer (re-extracted on 2026-10-15: all 355 days equal; the
daily PDF agrees, Q7); `research-2026-10-11/BH/almajles_calendar.html`, the stated method (both, with the two PDFs,
copied there on 2026-10-15 from the Q7 research's scratchpad, with a README of SHA-256 sums);
`research-2026-10-11/GEONAMES/cities1000.zip`.

**Fit** (as run on 2026-10-15; `--refit` repeats it). By windows on all 355 days, read to the data's resolution
(values within 0.75 % of the best count are equal; a conventional value wins, then the fewest decimals).
1. Dhuhr at the stated 50.44 E: VSOP87 349 of 355 windows, MEEUS 348, TEXTBOOK 300. Down, with the offset window
   64.1–64.8 s: the 16′ semi-diameter's crossing time (64 s), not divided by cos δ (in exploration that varying form
   fits 344); 1.1 min fits 345. The table is computed for its own dates (exploration: the sun of one year earlier or
   later loses 14 Dhuhr days, two years 38–40).
2. Sunrise −0.83° (the standard altitude 3–4 days fewer) and Maghrib at the standard altitude, nearest (345 days).
3. Fajr 18.01° and Isha 17.82° (the stated 18° Isha fits 35 days: it is a minute late).
4. Asr, at the fixed evening point, factor 1, best rounding: `SHADOW_AT_EVENT` 344 days on the geometric sun,
   `DAILY_10H_UT` 339, `EVENT_INSTANT` 327, `KACST` 322, `DAILY_0H_UT` 295; the apparent sun at most 308.
The latitudes: Fajr and sunrise together were best at 26.205 N (700 of 710 days; 663 at the stated 26.27), Asr,
Maghrib and Isha at 26.155–26.175 N (with Asr on `DAILY_10H_UT`; 993 at the stated 26.22). Each time alone prefers
Fajr 26.210, sunrise 26.205, Asr 26.185, Maghrib 26.155, Isha 26.185.

**Held-out scores** (month-split: fold A's values fitted on the odd months, fold B's on the even ones; each day scored
by the fold that did not see it; quirk days none):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| exact, held out (355 days) | 97.5 % | 98.0 % | 97.2 % | 96.9 % | 96.9 % | 94.4 % |
| within 1 min, held out | 100 % | 100 % | 100 % | 100 % | 100 % | 100 % |
| exact, the asset's values in-sample | 98.0 % | 99.2 % | 98.3 % | 96.9 % | 97.2 % | 95.2 % |
| exact, the profile before (26.22 N, 18/17.8, + 1.1, Asr at the event instant) | 95.5 % | 97.7 % | 97.2 % | 91.5 % | 91.3 % | 94.6 % |

At the same fold points `DAILY_10H_UT` gives Asr 336 of 355. The folds: A 26.215 / 26.180 N, Fajr 18.008°, Dhuhr
+ 1.1, Maghrib −0.95° down, Isha 17.81°; B 26.205 / 26.165 N, Fajr 18.01°, + 1.07, Maghrib at the standard altitude
to the nearest minute, Isha 17.825°; both with sunrise at the standard altitude.

**Tier.** Bahrain is tier B (9.5): every time within 1 minute, held out: passes. Tier A would fail on Asr, Maghrib
and Isha (94.4–96.9 %).

**Quirk days.** None.

**Open questions.**
- `--refit` with `SHADOW_AT_EVENT` moves the evening latitude to 26.180 N and Maghrib's pick to −0.95° down, at the
  same held-out total (2,062 of 2,130; Asr 98.3 %, Maghrib 95.2 %): the two readings of Maghrib are near-equivalent
  on this calendar, and the 1449 calendar would decide.
- The latitudes: both fitted latitudes lie 0.05–0.07° south of the stated ones, and the reduced (parametric)
  latitudes of the stated points, tan u = (b/a) tan φ, are 26.194 and 26.144, where Fajr and sunrise, and Maghrib,
  fit best: the SCIA's program may take u for φ. Isha does not follow: 18° fits only at about 26.15 N 50.66 E (351
  days in exploration, not shipped: E6 has no Isha point), a minute earlier than at the stated western point.
- Why the stated 18° Isha and 26.27 N fit worse than 17.82° and 26.205 N; the SCIA's own statement or program would
  settle it (Q7).
- The 1449 calendar (about June 2027) is the held-out year.
