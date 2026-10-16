### Ireland: the Islamic Foundation of Ireland's tables (`ie_ifi`)

Fitted by `fits/ie_ifi/fit.py` (2026-10-16, under a minute); asset `official-zones/ie_ifi.json`, 33 zones, mapping
`NEAREST`. Data fetched by `fits/ie_ifi/fetch.py`.

**The tables.** The IFI's timetable app (islamicfoundation.ie/timetable/, a Flutter page) reads two addresses:
`https://islamicfoundation.ie/api/settings/` (settings, `updated` 1790884515) and the perpetual Dublin table it names,
`https://islamicfoundation.ie/api/timetable/` (month → day → six [hour, minute] pairs, 366 days). Its clock is Irish
summer time from 1 April to 31 October and GMT otherwise (the table jumps by an hour between 31 March and 1 April and
between 31 October and 1 November), not the summer-time Sundays: read in UTC with that clock, the days its clock lags
the legal one (29–31 March and 25–31 October 2026) are the table's own instants, and the app shows them in the legal
clock (all 60 times of those 10 days exact). The Ramadan 2026/1447 page (`/timetable/ramadan-2026/`) links 33 town
PDFs (19 February to 20 March 2026, GMT), read with `ramadan_pdf.py` (standard library: the page's CID fonts and
their ToUnicode maps). Dublin's Ramadan table equals the perpetual table on all 180 times. Athlone's Maghrib column is
printed in the 12-hour clock and is read in the 24-hour clock.

**Method** (`ie_ifi`, fitted at 2026 dates, E5; the app computes the actual year, A8): Fajr = sunrise − 100 + E18,
Isha = Maghrib + 90 + E18 (the Isha values are held at 0 or more, the table's floor of Maghrib + 90), and E18 values
on sunrise, Dhuhr, Asr (factor 1: one offset covers 103 Asr windows, factor 2 23) and Maghrib; standard horizon, MEEUS
at the event instant, NEAREST, no integer offsets. Each time's 36 values are set by windows, one value at a time with the others
held (the value most of its days' windows cover, the line through its neighbours where several do), until nothing
moves; Isha after Maghrib, since it counts from Maghrib's unrounded instant. In minutes, January first:

- Fajr: −17.37, −14.57, −11.60, −7.97, −4.75, −1.52, 0.36, 1.58, 1.50, 0.29, −1.81, −5.11, −8.99, −13.83, −19.06,
  −24.29, −29.10, −30.80, −29.62, −26.20, −21.33, −16.46, −11.79, −7.67, −5.30, −2.94, −2.28, −2.37, −3.92, −5.47,
  −8.06, −10.87, −13.69, −16.01, −18.01, −17.69
- Sunrise: −3.02, −2.64, −2.27, −1.88, −1.50, −1.12, −0.93, −0.74, −0.81, −1.09, −1.38, −1.66, −1.94, −2.23, −2.36,
  −2.76, −3.14, −3.75, −4.36, −4.78, −4.93, −5.08, −4.88, −4.69, −4.49, −4.30, −4.55, −4.57, −4.87, −5.18, −5.08,
  −4.99, −4.58, −4.18, −3.79, −3.40
- Dhuhr: 1.70, 1.48, 1.58, 1.75, 1.94, 2.14, 2.53, 2.60, 2.45, 2.44, 2.07, 1.96, 1.84, 1.73, 2.01, 1.91, 1.81, 1.71,
  1.61, 1.51, 1.66, 1.81, 1.95, 2.37, 2.57, 2.59, 2.62, 2.31, 1.90, 1.76, 1.68, 1.79, 1.90, 1.91, 1.92, 1.92
- Asr: 1.01, 0.27, −0.01, −0.28, −0.09, 0.16, 0.35, 0.51, 0.66, 0.67, 0.55, 0.44, 0.71, 0.97, 1.20, 1.42, 1.61, 1.80,
  1.99, 1.97, 2.33, 2.68, 3.36, 3.81, 4.25, 4.52, 4.42, 4.38, 4.18, 3.99, 3.80, 3.62, 3.43, 2.96, 2.49, 1.75
- Maghrib: 2.17, 1.48, 0.79, 0.65, 0.52, 0.69, 0.86, 0.88, 0.91, 0.74, 0.57, 0.40, 0.49, 0.86, 1.24, 1.99, 2.75, 3.51,
  4.26, 4.56, 5.19, 5.60, 6.02, 6.44, 6.85, 6.81, 6.57, 6.33, 5.79, 5.58, 5.36, 5.14, 4.92, 4.70, 3.98, 3.25
- Isha: 18.00, 15.75, 12.84, 9.92, 7.01, 4.68, 2.89, 1.79, 1.74, 2.33, 4.44, 6.55, 9.63, 13.64, 7.51, 0, 0, 0, 0,
  2.24, 12.33, 13.41, 9.66, 6.44, 3.88, 2.44, 2.09, 1.74, 2.91, 4.91, 6.90, 9.89, 12.75, 15.60, 17.59, 18.17

The table's sunrise is 0.7–5.2 min before the computed one and its Maghrib 0.4–6.9 min after, by season; its Dhuhr
1.5–2.6 min after the transit; its Fajr interval runs from 98 min (equinoxes) to 127 (June) and its Isha interval from
91 to 108, with the summer floor: from 17 May the interval falls by about a minute a day to exactly 90 on 1 June,
stays there to 9 July, and climbs back to 106 by 25 July.

**Zones.** 33, ids the IFI's file names (`galway` for its `galwau`), Arabic names from the place catalogue where it has
one, else transliterated; each `point` is the town centre (GeoNames cities1000, by id or name; Clonmel and Mullingar by
their alternate names), its named place; the computing point fitted on the town's own 30 Ramadan days with Dublin's
values (longitude from Dhuhr, then latitude from sunrise, Asr and Maghrib, by windows) is its E6 morning and evening
point, 0.1–13.2 km from the centre (Tipperary 13.2 km, Waterford 7.5, Letterkenny 7.2; Ballina and Ballinasloe keep
their centre). Dublin's zone is the perpetual table at the city centre (GeoNames 2964574, 53.33306 N 6.24889 W), no
E6 point. Mapping `NEAREST` with no reach limit and with the validity guard (6.5, Q23).

**Are the 33 towns zones?** Yes, as the plan reads. Every town's table keeps Dublin's Fajr and Isha intervals at its own
sunrise and Maghrib: with the town's fitted point, Fajr is within 1 min on 99.7 % of the 990 town days (83.0 % exact)
and Isha on 99.9 % (84.4 %), where Dublin's depression angle carried to the town gives 59.7 % and 62.0 % within 1 min.
The IFI publishes one table per town and no rule for other places, so each Irish place takes the nearest town's table,
computed at that town's point (the owner's rule of 2026-10-11, reproduce the official source through its places), not
the method at the user's own point; «حسب موقعي بالضبط» stays the option.

**Data** (test-data-local, read at run time, never copied): `official/ie/ifi/timetable.json`, `settings.json`,
`ramadan-2026.html`, `ramadan-2026/<town>_ramadan_timetable_2026.pdf` (33), and
`research-2026-10-11/GEONAMES/cities1000.zip`.

**Scores** (common.compute), exact / within 1 min:

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|---|
| Dublin, held out (month split: the values refitted on the odd months score the even ones, and the reverse) | 47.4 / 90.4 % | 89.6 / 100 % | 90.4 / 100 % | 90.1 / 100 % | 85.8 / 100 % | 51.8 / 77.8 % | 13 min |
| Dublin, in-sample (the asset's values) | 98.9 / 100 % | 100 / 100 % | 100 / 100 % | 100 / 100 % | 100 / 100 % | 93.2 / 96.2 % | 4 min |
| Dublin as the app shows 2027 (A8) | 63.0 / 100 % | 61.9 / 100 % | 93.4 / 100 % | 73.2 / 100 % | 62.7 / 100 % | 61.6 / 95.9 % | 5 min |
| Dublin as the app shows 2028 | 18.9 / 93.7 % | 24.3 / 96.2 % | 88.5 / 100 % | 52.2 / 100 % | 19.9 / 95.6 % | 21.9 / 90.4 % | 4 min |
| Dublin as the app shows 2029 | 57.0 / 100 % | 56.4 / 100 % | 94.5 / 100 % | 71.5 / 100 % | 56.7 / 100 % | 52.1 / 96.2 % | 4 min |
| 33 towns, held out (day split: each point fitted on the odd Ramadan days scores the even ones, and the reverse) | 82.9 / 99.8 % | 95.1 / 100 % | 95.5 / 100 % | 91.9 / 100 % | 94.6 / 100 % | 84.1 / 100 % | 2 min |

- The preset before the fit (sunrise − 100, Maghrib + 90, Dhuhr + 2, the GeoNames point): Fajr 3.0 % exact and 11.0 %
  within 1, Isha 0 and 0, up to 31 min off.
- In-sample, Isha misses on 25 days (14 by more than 1 min, up to 4) around the summer floor's four bends (17 May,
  1 June, 9 July, 25 July): E18's points are 10.1 days apart and linear between, so a bend between two points is cut
  by up to 2–3 min on the days near it. The month split interpolates each held-out month's three points from its
  neighbours, so the fast-moving Fajr and Isha intervals miss most there (June's Fajr by 2–3 min).
- The table is perpetual and the app computes the actual year (A8): a calendar date's sun moves by about a quarter
  of a day a year and comes back with each 29 February, so with the values fitted at 2026 the other years drift by up
  to about half a day of the sun's motion (2028, after its leap day), up to 1–2 min on the fast-changing spring and
  autumn sunrises and sunsets and the intervals counted from them. Fitting the values over a whole leap cycle
  (2025–2028) instead moves the towns' fitted points by up to 21 km and leaves 2028's Fajr 93.7 % within 1 min, so the
  fit stays at 2026, the year of the Ramadan tables (the plan's E5 reference year; the IFI's own is unknown).
- **Tier** (9.5): Dublin's held-out record fails the gate (Fajr and Isha below 97 % exact, Isha up to 13 min held
  out), and A8's drift alone keeps later years below it, so every zone ships with «مستخلصة من جداول». The towns'
  records cover February and March only (no year tables: the IFI publishes only Ramadan tables for its towns).

**Quirk days** (1, counted apart): Tralee's Isha on 14 March 2026 is printed 12 min after its neighbours' line.

**Open.**
1. The Isha floor's bends need a finer E18 (73 points, 5 days apart, say) or a rule of their own (Isha = Maghrib +
   max(90, the interval)); either is an engine change, not proposed for the first release.
2. The IFI's year tables for its towns, if it publishes them, are the held-out data for the towns' rule (Q23).
3. A8's drift: the app's 2028 Fajr and Isha are within 1 min of the printed table on 93.7 % and 90.4 % of days only
   (up to 4 min). Computing a perpetual table at its reference year (E5 in the engine, not only in the harness) would
   remove it; the owner's A8 decision keeps the actual year.
