#### Somalia (`so_fitted`)

`fits/so_fitted/fit.py` (2026-10-15, a few seconds) prints the checks below. Nothing is fitted and no asset is
written: the plan fixes every value (A3, owner, 2026-10-11) and runs the method at the user's point.

**Method** (the plan's, unchanged; 8.7): MWL, Fajr 18°, Isha 17°, no offsets (Dhuhr at transit, Maghrib at sunset),
Asr factor 1, nearest rounding on all six, standard horizon, MEEUS sun at the event instant, `AngleBased` rule (it
never acts). Evidence `APPROXIMATE`, chip «فلكية».

**Zones and mapping.** None: exact coordinates (`EXACT`), so no asset.

**Data** (read at run time, never copied). No official or printed table was found for the federal ministry or
Somaliland's (8.1, 12, Q16); `test-data-local/research-2026-10-08/SD-SO-DJ-KM/so` holds `sun.py`, a research script,
and no table. The only Somali times are the owner's mosque dataset (`mosque-dataset/mosques.db`, read-only), evidence
and never a source (5.6), which tier C (9.5) checks the profile against: 3 records on 3 calendars, two in Hargeisa and
one in Mogadishu.

**Fit.** None. Windows (9.4) per calendar, as a check of the plan's values (the angle most windows allow at the event
instant; whole-minute offsets against the raw events), of 365 days each:

| Calendar | Fajr angle | Isha angle | Sunrise | Dhuhr | Asr | Maghrib |
|---|---|---|---|---|---|---|
| Hargeisa, first | 17.58 (243; 18: 0) | 17.36 (267; 17: 4) | + 0 (357) | + 0 (360) | + 0 (233) | + 0 (359) |
| Hargeisa, second | 18.00 (364) | 17.01 (357) | + 0 (361) | + 0 (361) | − 1 (134; + 0: 98) | + 0 (361) |
| Mogadishu | 18.00 (361) | 17.00 (362) | + 0 (360) | + 0 (360) | − 1 (124; + 0: 115) | + 0 (360) |

Two of the three calendars are MWL 18° / 17° to the minute on every time but Asr; the first Hargeisa calendar has
the same sunrise, Dhuhr and Maghrib but other twilight angles (about 17.6° and 17.4°, no common setting), 1–3 min off.

**Check scores** (held out from this script, in-sample for the plan's values, which the plan read from these
calendars, A3): exact / within 1 min (%), worst minute, 1,095 days.

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| all three | 65.9 / 78.8, 3 | 98.4 / 100, 1 | 98.7 / 100, 1 | 40.7 / 99.1, 2 | 98.6 / 100, 1 | 65.6 / 83.1, 2 |
| the two on the profile (730 days) | 98.9 / 100, 1 | 98.8 / 100, 1 | 98.8 / 100, 1 | 29.2 / 98.6, 2 | 98.8 / 100, 1 | 97.8 / 100, 1 |

"On the profile": both angle windows within 0.1° of 18° and 17°, and sunrise, Dhuhr and Maghrib at + 0. The
calendars' Asr strays up to 2 min from the shadow rule, by season; the declination taken late in the day (E4 `KACST`)
is exact on 52.9 %, against 40.7 % at the event instant: an app's convention, not a published rule, so not adopted.

**Tier A.** Not applicable: tier C (9.5), no official data, so no FitStats; chip «فلكية» (A3).

**Quirk days.** None.

**Open.** Any table of the federal Ministry of Endowments and Islamic Affairs (mesa.gov.so) or of Somaliland's
ministry (9.3); the dataset's three calendars may only show their apps' default (A3).
