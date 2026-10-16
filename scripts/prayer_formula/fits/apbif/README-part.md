### France: APBIF's method (`apbif`)

Fitted by `fits/apbif/fit.py` (2026-10-16, about 20 s) on pages read the same day with `fits/apbif/fetch.py`. No
asset: `apbif` is a method at the user's point (7.5).

**The tables.** APBIF's «Horaires des prières de votre ville» (apbif.fr, a GET of `cityId` and `month`, one town and
month per page, the year 2026 on Paris's clock): Paris's 12 months (365 days), Lille's, Marseille's and Strasbourg's
(365 each) and Rennes's January (the site answered 429 Too Many Requests after 40 pages; nothing was retried). Every
row prints an Imsak at Fajr − 20 (not stored, A14). The table is **perpetual**: the Wayback Machine's copies of the
Paris page in May and August 2025 equal the 2026 rows of those dates on all seven columns (62 of 62 rows; a third copy,
November 2025, is the site's bot check). Its sun is about 12 years old: read at another year's dates, 2014 covers the
most windows of Paris's six times (1,877; 2010 1,870, 2018 1,865, 2022 1,836, 2026 1,800), every good year in 2026's
leap phase. So the stages run at 2014 (E5) and every score is computed for 2026, as the app shows it (A8).

**Method** (stages of 9.4 at 2014 at Paris's catalogue point; the methodology's values and the preset's roundings are
the defaults; an alternative must cover more than 0.5 % and at least 2 more windows):

- Fajr **18°**, + 6, nearest (325 windows; the windows' own angle 18.004°); Isha **12°**, nearest (345; 12.006°). At
  Lille, Marseille and Strasbourg the windows give 18.002–18.004° and 11.998°.
- Sunrise at −1°, rounded down (307 windows); Dhuhr + 6, nearest (357; the longitude the windows give, 2.342°E, is
  0.5 km west of the town point, a diagnostic only); Maghrib at −1°, + 6, rounded up (320). All the preset's.
- **Asr**: factor 1, + 6, nearest, with **the apparent sun (E13) and the noon shadow taken at Dhuhr**
  (`SHADOW_AT_DHUHR`, transit + 6): 332 windows, against 223 for the preset (the geometric sun at the event instant) and
  270 for the best geometric choice (up). APBIF's model was built from observed shadows; with the refracted sun the
  residuals' winter/summer drift (about 0.4 min) disappears, and the shadow at Dhuhr adds about 4 s. Both halves of the
  months pick it, and it lifts Asr at every held-out town (below).
- The absent nights: the middle of the night, **unramped** (7.4): Paris 13–30 June (18 nights, 17 exact, all within
  1 min), Lille 28 May–16 July (50, 36 exact, all within 1), Strasbourg 19–23 June (5, 4 exact); each season's first and
  last nights are the app's own for 2026, and the published Fajr jumps at both ends (Paris 02:02 → 01:57 on 12–13
  June, 02:00 → 02:08 on 30 June–1 July).
- The horizon: **−1° kept**. Each town's own best fixed altitude at 2014: Paris −0.98° (sunrise 347 and Maghrib 353 of
  365 windows, against 307 and 320 at −1°), Lille −1.02° and −1.01°, Rennes −1.00° and −1.02°, around the stated −1°;
  but Marseille −1.10° and Strasbourg −1.26° and −1.25°: APBIF gives those two towns a lower horizon, which a method at
  the user's point cannot know (Open).

**Held out** (computed for 2026 with common.compute; exact / within 1 min):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|---|
| Paris, month split | 84.4 / 100 | 83.3 / 100 | 96.4 / 100 | 87.4 / 100 | 87.9 / 100 | 84.1 / 100 | 1 |
| Lille | 78.4 / 99.7 | 84.1 / 100 | 97.5 / 100 | 87.9 / 100 | 83.0 / 100 | 81.6 / 100 | 2 |
| Marseille | 86.6 / 100 | 38.6 / 100 | 97.3 / 100 | 94.2 / 100 | 38.4 / 100 | 88.8 / 100 | 1 |
| Strasbourg | 81.6 / 100 | 0 / 28.5 | 95.9 / 100 | 89.3 / 100 | 0 / 29.6 | 83.0 / 100 | 3 |
| Rennes (January) | 90.3 / 100 | 96.8 / 100 | 100 / 100 | 87.1 / 100 | 83.9 / 100 | 0 / 0 | 5 |

- Both halves of Paris's months select the same profile, so the month split equals the in-sample score. The other
  towns were never fitted. Lille's one 2-min time is Fajr on 27 May, the last night with 18° (the angle time moves fast
  there).
- At 2014, the table's own year, Paris is 84.1–97.8 % exact per time (Fajr 93.2, Isha 94.5, Asr 91.0), every time
  within 1 min but Fajr on 12 June (2 min, the night before the absent season). Computed for 2026 the year's sun costs
  about 10 points on Fajr and Isha, as A8 accepts.
- The preset before this fit: the same at Paris but Asr 61.6 % exact (Lille 63.8, Marseille 76.4, Strasbourg 65.5).
- Plan 9.2 (`apbif` within 1 min on all six times on Paris's June, July, October and December 2026): met on all 365
  days of 2026.

**Data** (test-data-local, read at run time, never copied): `research-2026-10-16/FR/apbif/` (pages, `MANIFEST.tsv`).

**Open.**
1. APBIF's towns are not one method at their centres: Marseille's and Strasbourg's lower horizons (about 1 and 2–3 min
   on sunrise and Maghrib) and Rennes's Isha (5 min after 12° in January, as if 12.8°). A user's point near those
   towns gets the stated method, not the town's table.
2. The table is perpetual with a sun of about 2014; the app computes the actual year (A8), so 1-minute differences on
   Fajr and Isha are expected (about 16 % of Paris's days).
3. Rennes's other months and the other 74 towns, when the site's rate limit allows.
