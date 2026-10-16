### Bosnia and Herzegovina: the Islamic Community's Takvim (`bosnia_iz`)

Fitted by `fits/bosnia_iz/fit.py` (2026-10-15, under a minute); asset `official-zones/bosnia_iz.json`, 136 zones, mapping
`LISTED_PLACES`, country `BA` (the Croatian, Slovenian, Serbian and Montenegrin zones carry their `country`).

**The tables.** vaktija.ba serves the IZ BiH's perpetual Sarajevo table (CET all year) and the Takvim 2025's monthly
town differences («mjesne vremenske razlike»): its January differences equal the printed page 23 on all 318 values
checked, and its 2026 API equals table plus difference plus summer time. Its 2024-12-30 commit («nova vremena 25»)
changed Fajr and Isha on all 366 rows and nothing else, so the table has two layers:

- **Fajr and Isha are the IZ's 2025 computation.** At the Takvim's Sarajevo (43°52′N 18°26′E, pages 8–12) with 18° and
  16°, the sun taken once a day at 0 h UT (E4 `DAILY_0H_UT`) covers 360 and 358 of 2025's 365 rows (the windows' own
  angles 17.991° and 16.009°), the sun at the event instant at most 315 and 146.
- **Sunrise, Dhuhr, Asr and Maghrib are older and follow no single year.** At the Takvim's point, with the sun at the
  event, sunrise fits 1900 best (255 of 365 rows) and Maghrib 1999 (289); with the sun at 0 h UT, sunrise 1900 and
  Maghrib 2025. Their sunrise behaves as if computed about 25 km north of their Maghrib (44.05 N against 43.82 N, the
  sun at 0 h UT, 2026–2029), and their Asr column steps by one minute a day with catch-up jumps (a hand-made column).
  So they are fitted on the years the app computes, 2026–2029 pooled, as the app shows a perpetual table (A8); E5's
  reference year applies to Fajr and Isha only.

**Method** (stages of 9.4 by windows; an alternative must cover more than 0.5 % more windows than the published value,
NEAREST or stage 0's evaluation): 18° / 16°, sunrise − 6, Dhuhr + 1, Maghrib + 6 (the published values, unchanged),
Asr factor 1, NEAREST on all six, **the sun once a day at 0 h UT on all six** (stage 0: 4,095 of 6,574 minutes,
against 3,286 at the event instant, 3,431 at 10 h UT and 2,629 with `KACST`), MEEUS, standard horizons. Counts at
the fitted point: Dhuhr 1,232 of 1,461 (the event instant 1,227), sunrise 552 (438), Maghrib 889 (583), Asr 789
(689), Fajr 348 of 365, Isha 336. The profile carries Table 1 (page 12) as `takvimMeans`: north 3.40, 1.90, 0.15,
−1.68, −3.33, −4.30, −3.90, −2.35, −0.60, 1.25, 2.98, 3.98; south −2.80, −1.58, −0.13, 1.33, 2.73, 3.53, 3.18,
1.95, 0.50, −1.03, −2.43, −3.23 (printed signs).

**Zones.**

- `sarajevo`, «سراييفو», the base of every other zone: its point is GeoNames' Sarajevo (43.84864 N 18.35644 E), its
  computing point the fitted 43.85667 N 18.42236 E (E6 morning and evening points), 1.4 km south-west of the Takvim's
  stated point. Computed at GeoNames' point instead, 2026's Dhuhr would be exact on 72.1 % of days instead of 85.2 %,
  and sunrise within 1 min on 89.3 % instead of 97.0 %.
- 130 Takvim places, each `baseZoneId` `sarajevo`, `offsetRule` `TAKVIM`, its town as `point` (GeoNames) and the point
  fitted to its printed differences as E6 morning and evening points: Bosnia's 109 (vaktija.ba's 106 and the Takvim's
  Bosanska Kostajnica, Teočak and Ustikolina), Croatia's 5 (Zagreb, Rijeka, Osijek, Split, Dubrovnik), Slovenia's 5
  (Ljubljana, Maribor, Velenje, Koper, Jesenice) and Serbia's 11 (vaktija.ba's Nova Varoš, Novi Pazar, Priboj,
  Prijepolje, Sjenica, Tutin; the Takvim's Beograd, Novi Sad, Subotica, Preševo, Niš). A point is the one whose
  derived differences equal the most printed values (each kept 0.001 min inside its window), nearest the town, within
  15 km; within 60 km where the month split shows the wider search predicts the left-out months better (Tutin only:
  vaktija.ba prints Sjenica's differences for it, and its point lies 55 km away, beside Sjenica's).
- Montenegro's five vaktija.ba towns, published offsets on all six times all year (Pljevlja − 3, Bijelo Polje − 5,
  Gusinje and Plav − 6, Rožaje − 7), `reachKm` 8 (8.8's starting value); elsewhere in Montenegro the mapping builds
  the longitude-shift zone on the phone (6.2).
- `nameAr` are transliterations of the Takvim's names (Hlivno as «ليفنو»), to be replaced by the catalogue's Arabic
  labels if M5 has better ones; catalogue ids are left to M5.

**The rule reproduces the print.** Fitted points give 3,924 of Bosnia's 3,924 printed values (the towns' own points
3,531), all 180 Croatian ones (171), 179 of 180 Slovenian (173; Koper's February evening, below) and 360 of 396
Serbian (272), at a median 3.2 km from the town in Bosnia (11.6 km at most), 0.6 in Croatia and 0.4 in Slovenia. The
Serbian misses are vaktija.ba's Sandžak towns, whose June and July evening differences are 1–2 min later than Table 1
gives at any point (Novi Pazar 34 of 36, Nova Varoš 31, Priboj and Prijepolje 30, Sjenica and Tutin 29); Beograd,
Novi Sad and Niš miss one value each, Subotica and Preševo none. Through the reference engine (zone and base zone) the derived shifts equal
`fit.py`'s on all 432 checked times **when the TAKVIM rule reads the zone's E6 point**; read at the zone's `point`
(the town), as the engine does today, 326 of 432 equal the print (see Open).

**Data** (test-data-local, read at run time, never copied): `research-2026-10-11/BA/iz_bih/` `vaktija.json`,
`vaktija_499a8ec.json`, `places.json`, `mesihat.json` (checked against the pages on 2026-10-15: every value as
printed), `takvim_extra.json` (Bosanska Kostajnica, Teočak, Ustikolina, transcribed 2026-10-15 from pages 23–45),
`api_77_2026.json`, `api_91_2026.json`, `api_2_2026.json`. Town points of the mesihat towns and the three extra places:
GeoNames (constants in `fit.py`).

**Held out** (2026, month split: the base table's rules and each place's point fitted on the odd months score the
even ones, and the reverse; common.compute), exact / within 1 min:

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|---|
| Sarajevo's table | 69.0 / 100 % | 37.8 / 95.1 % | 84.7 / 100 % | 52.6 / 98.9 % | 67.4 / 100 % | 62.5 / 100 % | 2 min |
| All 136 zones (the profile's record) | 64.6 / 99.0 % | 37.6 / 93.1 % | 84.7 / 100 % | 49.7 / 96.8 % | 62.5 / 98.3 % | 57.9 / 98.1 % | 3 min |
| Derived differences alone (Bosnia) | 3,703 of 3,924 values (94.4 %) | | | | | | 1 min |

- As shipped (points fitted on all months), Trebinje's and Bihać's 2026 API equal Sarajevo's shares above: every
  difference is the printed one. Held out they reach 3 min (Trebinje's Fajr 98.4 % within 1).
- The table in later years, as shipped: 2026 69.9 / 44.1 / 85.2 / 52.6 / 67.9 / 61.4 % exact; 2027 34.5 / 52.3 /
  81.9 / 48.8 / 44.9 / 35.1 %; 2028 58.5 / 27.0 / 82.8 / 57.7 / 52.7 / 58.7 %; 2029 94.5 / 27.7 / 87.4 / 57.0 / 77.8 /
  91.0 %; never more than 2 min off; within 1 min Fajr, Dhuhr and Isha 99.7–100 %, Maghrib 98.4–100 %, Asr
  97.5–99.7 %, sunrise 80.1–98.1 %.
- The preset before the fit (the sun at the event instant, at the Takvim's point): 2026 within 1 min 94.2 % (sunrise)
  to 100 %, but 2028 within 1 min only 59.8 % (Isha), 71.3 % (sunrise) and 75.7 % (Maghrib), 3 min at worst.
- Zone records: all 109 Bosnian zones are within 1 min on every held-out day (median of the lowest exact share
  91.5 %; 14 pass 97 %); 7 of the 11 Serbian.

**Quirk days**: Koper's February evening is printed 12, as in January, between January's 12 and March's 19 of a smooth
column; the rule gives 15–16 at any point near Koper. Its 28 days are counted apart (`quirkDays` 28).

**Open.**
1. **Engine**: TAKVIM reads `zone.point` (Kotlin `PrayerEngine.takvim`, reference `takvim_shift`), while 6.2 and 8.8
   put the fitted point in the E6 points and keep the town as `point` for the mapping. The asset follows 8.8; the rule
   must read `morningPoint` for Fajr and sunrise and `eveningPoint` for Dhuhr, Asr, Maghrib and Isha (`point` where
   absent). Until then the app derives the differences at the towns (75 % of printed values on the six towns checked).
2. Sarajevo's older columns cap the table's reproduction (sunrise 44 % exact in 2026, 27 % in 2028–2029; Asr about
   50 %): even their best years in the scan cover only 255 (sunrise) and 289 (Maghrib) of 365 rows at the Takvim's
   point, and a sunrise-only point would cost the 2025 Fajr its fit (E6 couples Fajr and sunrise).
3. vaktija.ba's six Sandžak towns follow Table 1 only to 84.7 %: reproducing them exactly needs their printed monthly
   differences (12 × 3 whole minutes per town), which 8.8 chose not to store.
4. Montenegro's town reach (8 km) and every zone's catalogue ids wait for M5's town extents and catalogue.
