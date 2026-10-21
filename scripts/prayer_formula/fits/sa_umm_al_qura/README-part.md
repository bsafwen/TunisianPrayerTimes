#### Saudi Arabia (`sa_umm_al_qura`)

KACST computes the Umm al-Qura calendar's prayer times for any point and any year with today's rule
(ummulqura.org.sa, API `umqserv.kacst.gov.sa`) and publishes no written method, so the profile is fitted to its
tables: `python scripts/prayer_formula/fits/sa_umm_al_qura/fit.py` (about 2 minutes) prints the fit and writes
`official-zones/sa_umm_al_qura.json`.

**Method.**

| Time | Rule | Rounding (E1) |
|---|---|---|
| Fajr | 18.5°, E4 `KACST` (δ at 21:00 UT, equation of time at 09:00 UT) | up |
| Sunrise | `Fixed(−0.83°)` at the event instant | down |
| Dhuhr | the transit at the event instant | up |
| Asr | shadow factor 1, geometric sun, E4 `KACST` | up |
| Maghrib | sunset at `Standard` (−0.8333°) | up |
| Isha | Maghrib + 90, + 120 on `UMM_AL_QURA_COMPUTED` days (E8) | Maghrib's |

The sun is VSOP87 (E14), and every computed time is 1 s later than the engine's: an E18 constant of 1/60 min on
Fajr, sunrise, Dhuhr, Asr and Maghrib (Isha follows Maghrib). No elevation, no minute offsets. On MEEUS, Dhuhr's
best offset is −0.2 s on the 2026 tables and +1.1 s on those of 2025 and 2027 (its 1900-epoch sun drifts), and a
constant shift gains nothing. On VSOP87 each time alone wants about the same shift (Fajr +1.05 s, sunrise +1.08 s
at −0.83°, Dhuhr +0.89 s, Asr +0.92 s, Maghrib +0.69 s, on all 13 tables), and with it the exact share rises from
97.8–98.6 % to 99.4–99.8 % per time. This replaces Q4's and E14's MEEUS. The second's origin is not known, likely a
time-scale or equation-of-time convention in KACST's code (Meeus's chapter-28 equation of time differs from the
engine's by only +0.22 s).

**Zones and mapping.** `NEAREST_LOCAL` (6.5, 8.8): KACST's 173 places (`sa/web/cities.json`: 26 «city», 142
«governorate» and 5 «center» entries), each a zone at its published coordinates (to 1e-5°) with KACST's id, its
Arabic name, `admin1` its region's code in KACST's list (`sa/web/regions.json`: RD, MQ, MN, QA, SQ, AS, TB, HA, SH,
GA, NG, BA, GO) and `reachKm` 25. Beyond 25 km of every entry, the method at the user's point, as KACST computes
there. Abha (about 2,200 m) and Al Bahah (about 2,150 m) fit the coast's horizons: no elevation.

**Data** (`test-data-local/research-2026-10-08/SA`, read at run time, never copied): KACST's year tables
(`GetPrayerByYear` at a place's published point, zone +3), found through `MANIFEST.tsv`, whose source URLs give the
query points. 2026: `sa/web/riyadh2026.json`, `mecca_2026.json`, `jeddah_2026.json`, `dammam_2026.json`,
`abha_2026.json`, `qurayyat_2026.json`, `jazan_2026.json`, `sa_skeptic/data/albahah_2026.json`. 2025:
`sa_skeptic/data/arar_2025.json`, `najran_2025.json`. 2027: `sa_skeptic/data/medina_2027.json`, `tabuk_2027.json`,
`buraydah_2027.json`. `medina_2000.json` and `medina_2008.json`, outside the app's years, are a side check. Each
row's Umm al-Qura date, which decides Ramadan and the quirk days, equals Java's `HijrahDate` on all 5,477 rows.
Ha'il's 2026 table (KACST place 1008, the 13th region), fetched on 2026-10-17 with the owner's OK, is in
`test-data-local/research-2026-10-17/SA/hail_2026.json`, listed by that folder's own `MANIFEST.tsv`.

**Fit.** Two folds by year: A fits on the nine 2026 tables and scores 2025 and 2027, B the reverse; both pick the
same values, so each table is scored by values fitted without it. Each published minute bounds the unrounded time,
hence the parameter (9.4). Among the configurations and values covered by at least the best count minus 0.75 % of
the windows, a conventional value wins (plain rounding, the standard altitude, an integer factor), then the fewest
decimals, then the default configuration; any tolerance from about 0.45 % to 1.1 % gives the same values.
1. Dhuhr: VSOP87 at the event instant (MEEUS 1.1–1.4 % fewer windows), up, 1.0 s after the transit (no shift
   1.6–1.7 % fewer).
2. Sunrise −0.83°, down (the standard altitude 1.2–1.4 % fewer); sunset at the standard altitude, up.
3. Fajr 18.5°, `KACST`, up (a daily instant 21–24 % fewer, the event instant 35–42 %). Isha − Maghrib is 90 on every
   other day and 120 on every Ramadan day.
4. Asr factor 1, geometric, `KACST`, up (apparent sun 3.4–3.5 % fewer, the event instant 7–8 %).

**Held-out scores**, quirk days left out (14 tables, 4,867 days):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| exact | 99.42 % | 99.53 % | 99.82 % | 99.53 % | 99.51 % | 99.51 % |
| fold A (2025, 2027) | 99.36 % | 99.65 % | 99.71 % | 99.31 % | 99.19 % | 99.19 % |
| fold B (2026) | 99.46 % | 99.46 % | 99.87 % | 99.65 % | 99.68 % | 99.68 % |
| the profile before (MEEUS, no shift) | 98.16 % | 97.85 % | 98.16 % | 98.57 % | 98.28 % | 98.28 % |

Every time is within 1 minute (2 at most on quirk days). Each of the 14 zones has its own record: the lowest is
98.27 % on one time (Tabuk, Arar), every month has at least 26 days, and its whole Ramadan is held out (415 Ramadan
days in all). Ha'il's own: 99.1–100 % exact, all within 1 minute. Side check, Medina 2000 and 2008: 95.1–98.4 % per
time, all within 1 minute (there the previous-date days fall elsewhere in the Hijri month and count as misses).

**Tier A** (9.5) passes since 2026-10-17: every time 99.4 % exact or more and all within 1 minute, at least 26
held-out days in every month, a whole Ramadan held out, and held-out points in all 13 regions (Ha'il, the 13th, from
its 2026 table). Every Saudi place shows «رسمية» and this national record (6.11). The values
are unchanged: both folds pick the shipped profile.

**Quirk days** (8.3, 8.7, A8). A row on Hijri days 2–6 whose Fajr, sunrise, Dhuhr, Asr and Maghrib all equal the
profile's times for the previous civil date, and not all five its own date's, is counted apart: 243 days, 15–20 per
table, on Hijri days 4–6 in 2025, 3–5 in 2026 and 2–3 in 2027. The app does not reproduce them.

**Open.**
- The second's origin. E18 carries it as a constant; a method field for a shift would read better.
- The −0.83° sunrise beside the standard sunset (with the shift, the standard sunrise needs 2 s, not 1).
- The previous-date days move one Hijri day earlier each year and leave days 2–6 from 2028; later tables need the
  rule widened.
- Each entry's radius from its place's extent (25 km for all until then).
- `admin1` holds KACST's region codes; the phone stores no admin1 for Saudi places.
