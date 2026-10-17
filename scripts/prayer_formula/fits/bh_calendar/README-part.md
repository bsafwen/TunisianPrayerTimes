#### Bahrain (`bh_calendar`)

`fits/bh_calendar/fit.py` (about 2 minutes) fits the Bahraini calendar of 1448 that the Supreme Council for Islamic
Affairs (SCIA) issues, and writes `official-zones/bh_calendar.json`. The SCIA's page states the method (two points,
Isha at 18°, Dhuhr once the whole disc has crossed the meridian); the fit keeps what the calendar confirms and fits
the rest.

**Method** (refitted 2026-10-17: each time at a point of its own, S6 `eventPoints`).

| Time | Rule | Point | Rounding (E1) |
|---|---|---|---|
| Fajr | 17.98° | 26.210 N 50.72 E (the zone's morning point) | down |
| Sunrise | `Fixed(−0.88°)` | 26.205 N 50.62 E (its own) | down |
| Dhuhr | the transit + 1.07 min (`dhuhrAfterTransitMin`, E7: the whole disc past the meridian) | 50.44 E (the evening point) | down |
| Asr | shadow factor 1, geometric sun, the noon shadow at the declination of the Asr instant itself (E4 `SHADOW_AT_EVENT`) | 26.180 N 50.44 E (its own) | down |
| Maghrib | sunset at `Standard` (−0.8333°) | 26.150 N 50.44 E (the zone's evening point) | nearest |
| Isha | 18.05° | 26.145 N 50.72 E (its own) | down |

VSOP87 (E14). The stated points are 26.27 N 50.68 E (Dilmunia: Fajr and sunrise) and 26.22 N 50.44 E (Salman Town:
the rest), the eastern longitude printed «68.50».

**Why a point per time.** The fit of 2026-10-15 kept the two stated longitudes with a fitted latitude each and left
Asr, Maghrib and Isha at 94.4–96.9 % exact held out. Each time alone wants another point, and each month-split fold
finds the same one: Isha at the stated 18° only near 26.15 N 50.66–50.72 E, a minute earlier than at the western point
(there the angle must drop to 17.82°, which generalises worse: 94.4 % against 98.9 % held out); Maghrib at 26.15 N and
Asr at 26.18 N on the western longitude, which no shared latitude serves (at 26.18 N Maghrib holds 343 days, at 26.15 N
Asr 333; every other Asr evaluation and sun at 26.15 N at most 339); Fajr and sunrise near 26.21 N, the eastern point's
reduced latitude (tan u = (b/a) tan φ: 26.194) give or take a hundredth. Maghrib's 26.15 N is the western point's
reduced latitude (26.144). Tried and dropped: Asr's hour angle and noon shadow at two different latitudes (the reduced
and the stated: at best 343 days, against 349 at 26.18 N for both); Isha at 18° on the western point a minute early
(326 days); an E7 fraction on Asr (none beats zero).

**Zone and mapping.** `NATIONAL`: one zone «البحرين» for the whole Kingdom, the Hawar Islands included, with the
validity guard (8.8). Its `point` is Manama (GeoNames 290340), which serves no computation; `morningPoint` is Fajr's,
`eveningPoint` Maghrib's (on Dhuhr's longitude), and `eventPoints` hold sunrise, Asr and Isha. It has no named places
(the SCIA names none).

**Data** (test-data-local, read at run time, never copied): `research-2026-10-11/BH/bh1448.csv`, the 355 days of
1448 (2026-06-16 to 2027-06-05) from the book PDF's text layer (re-extracted on 2026-10-15: all 355 days equal; the
daily PDF agrees, Q7); `research-2026-10-11/BH/almajles_calendar.html`, the stated method (both, with the two PDFs,
copied there on 2026-10-15 from the Q7 research's scratchpad, with a README of SHA-256 sums);
`research-2026-10-11/GEONAMES/cities1000.zip`.

**Fit**, by windows (a published minute bounds the parameter; values within 0.75 % of the best count are equal; a
conventional value wins, then the fewest decimals):
1. Dhuhr at the stated 50.44 E: VSOP87 349 of 355 windows, MEEUS 348, TEXTBOOK 300. Down, with the offset window
   64.1–64.8 s: the 16′ semi-diameter's crossing time (64 s), not divided by cos δ.
2. Each other time scans a grid, 26.100–26.300 N by 0.005° and 50.44–50.72 E by 0.02° (Asr and Maghrib on 50.44 E,
   their latitude only), picks its rounding and value at each point (the stated 18° and the standard horizon first),
   and keeps, among the points whose pick covers the most days, the one nearest the middle of that plateau.
On all 355 days: Fajr 352, sunrise 353, Dhuhr 349, Asr 349, Maghrib 351, Isha 352.

**Held-out scores** (month-split: fold A fitted on the odd months, fold B on the even ones; each day scored by the
fold that did not see it, with common.compute and the fold's own points; quirk days none):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| exact, held out (355 days) | 98.6 % | 99.4 % | 97.2 % | 96.6 % | 98.6 % | 98.9 % |
| within 1 min, held out | 100 % | 100 % | 100 % | 100 % | 100 % | 100 % |
| exact, the asset's values in-sample | 99.2 % | 99.4 % | 98.3 % | 98.3 % | 98.9 % | 99.2 % |
| exact held out, the two points of 2026-10-15 | 97.5 % | 98.0 % | 97.2 % | 96.9 % | 96.9 % | 94.4 % |

**Tier.** Bahrain is tier B (9.5): every time within 1 minute held out. Tier A fails on Asr alone, by two days (343
of 355, 97 % needs 345): fold A's months leave Asr's latitude a plateau from 26.18 to 26.20 N, and its middle,
26.20 N, scores the even months 165 of 171, where 26.18 N scores 171.

**Quirk days.** None.

**Open questions.**
- The 1449 calendar (about June 2027) is the held-out year; with two calendars Asr's latitude is settled.
- Why the SCIA's times sit at these points (reduced latitudes for some, the eastern longitude for Isha); the SCIA's
  own program or statement would settle it (Q7).
