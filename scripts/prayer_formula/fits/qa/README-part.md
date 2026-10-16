#### Qatar (`qa_awqaf`, `qa_qch`)

`fits/qa/fit.py` (about 1–2 minutes with the validity-guard side check, `--no-guard` skips it) fits both profiles,
which the plan treats together, and writes `official-zones/qa_awqaf.json` and `qa_qch.json`: the same six zones,
each asset with its own records.

**Method.** The Ministry's table and QCH's are one perpetual table, read here at its reference year 2006 (E5) and
computed for the actual year in the app (A8).

| Time | Rule | Rounding (E1) |
|---|---|---|
| Fajr | 18°, − 1 min from 2020-08-20 (E9) | nearest |
| Sunrise | the standard horizon (−0.8333°) | nearest |
| Dhuhr | transit; `qa_qch` + 1 from 2025-06-26 (E9) | nearest |
| Asr | shadow factor 1, the noon declination at transit, geometric sun; `qa_qch` + 1 from 2025-06-26 | nearest |
| Maghrib | sunset + 2, + 3 on Umm al-Qura's Ramadan days (E8 `UMM_AL_QURA_COMPUTED`) | nearest |
| Isha | Maghrib + 90, after all of Maghrib's minutes | Maghrib's |

The sun is VSOP87 at the event instant (E14). Stages, by windows, on the Ministry's 2026 table (365 days):

1. Dhuhr alone does not choose: the best sun, evaluation and reference year cover 361–364 windows (ALMANAC at
   10:00 UT in 2006 364, MEEUS with `KACST` 363, MEEUS at the event instant 362, VSOP87 at the event instant 361).
2. All six times at the reference year, with Doha's point refitted (of 2,190): VSOP87 at the event instant in 2006
   2,148, at 25.2821 N 51.5330 E (0.4 km from GeoNames' Doha); MEEUS 2,144; ALMANAC (outside the engine) 2,131 in
   2010; every daily evaluation 1,964 or fewer. By reference year (VSOP87, the point refitted): 1998 2,124,
   2002 2,129, 2006 2,148, 2010 2,120, 2014 2,111, 2018 2,097 (all in 2026's leap phase): 2006, as the plan says.
3. The plan's values at that point (of 2,190): Maghrib + 1/+ 2/+ 3: 1,452/2,146/1,458; Fajr's offset − 2/− 1/0:
   1,794/2,146/1,789; Dhuhr − 1/0/+ 1: 1,788/2,146/1,789; Asr on the apparent sun 2,108, factor 2 1,782; rounding
   up 1,098, down 1,092; no Ramadan minute 2,088 against + 1 2,146 (on the API's own Ramadan days); Fajr's angle
   window 18.001° (359 of 365); the horizon −0.80° 2,013, −0.85° 2,081. Isha − Maghrib is 90 on all 448 API days.
   In-sample at 2006: 2,146 of 2,190 (97.99 %). Every miss lies at a rounding boundary (the computed minute at
   .47 to .54), except Maghrib on 14–16 and 25–27 September, 1 minute later than any point gives (0.2–0.4 min
   past the boundary) in both the API and QCH: a glitch of the perpetual table, left in the scores.
4. QCH equals the API, plus 1 minute on Dhuhr and Asr from 2025-06-26, on 152 of the 153 days both publish; the
   other is 2026-03-19, Umm al-Qura's 30 Ramadan, where QCH keeps the Ramadan minute and the API does not (Q3).

The towns. QCH prints, under Doha's day, three Fajr and three Maghrib minutes to add for Al Shamal, Dukhan and Abu
Samra, without naming the columns (the labels are artwork). Fitted, right to left: Abu Samra, Dukhan, Al Shamal
(329 of the 354 printed minutes of 1448, against 279 with the middle two swapped). Each town is computed for the
actual date at a point fitted to its printed minutes (Fajr at 18° without the − 1: zone offset Fajr + 1), and
sunrise, Dhuhr, Asr and Isha at the same point, as QCH's `/cal` data does:

| Place | Computing point (E6) | Fitted on | Town centre (`point`) |
|---|---|---|---|
| Al Shamal | 26.0988 N 51.2530 E | 102 of 118 printed minutes, 1448 | Madīnat ash Shamāl (GeoNames 289739), 6.2 km |
| Dukhan | 25.3769 N 50.8236 E | 116 of 118 | Dukhān (289813), 6.8 km |
| Abu Samra | 24.6929 N 50.9186 E | 111 of 118 | Abū Samrah (398806), 10.4 km |
| Mesaieed | 24.9923 N 51.6235 E | 11 of 12 `/cal` minutes (2 days; longitude only, at the town's latitude) | Musay'īd (289525), 7.3 km |
| Halul | 25.6732 N 52.5835 E | 11 of 12 | Ḩālūl island (289790), 17.3 km |

The 1444 (2022) and 1446 (2025) prints give the towns other minutes: at these points, Maghrib is exact on 16, 26
and 1 of their 59 days (Al Shamal, Dukhan, Abu Samra), the printed one mostly 1–2 minutes earlier than the computed
one. The town minutes changed before 1448, likely with QCH's 1447 change (the 1447 calendars are images); those
prints count only as a side check, while their Doha times are held out.

**Zones and mapping.** `LISTED_PLACES` with `namedFirst` (6.5, 8.8): ids are QCH's `/cal` city ids (Doha 3,
Al Shamal 6, Mesaieed 13, Dukhan 14, Abu Samra 15, Halul 16), names its own. Each `point` is the town centre
(GeoNames; Abū Samrah and Ḩālūl read on geonames.org on 2026-10-15), from which the nearest place is measured; the
computing point is in `morningPoint` and `eveningPoint`. Doha's named places are Doha itself (`areaKm` 0, its
catalogue rows only), Al Khor (Al Khawr, 289962) and Al Wakra (Al Wakrah, 289915), each with `areaKm` 6 (QCH's rule:
the east coast from Al Khor to Al Wakra follows Doha); each town names itself (`areaKm` 0). `catalogueIds` stay empty
until M5. The five towns carry the zone offset Fajr + 1.

Validity guard (side check, every third day of 2026, approximate points marked ~ in fit.py): Al Shahaniya on Doha's
table 1.4 min beyond the margin (Asr), Al Jumayliyah on Dukhan's 1.6 (sunrise), Umm Bab 0.3, Al Ghuwayriyah on Al
Shamal's 0.8, Ras Laffan 1.7 (sunrise), Khor Al Udaid on Mesaieed's 1.9 (Fajr), Sealine 0.7, Al Kharrara on Abu
Samra's 1.3: all keep their table, as 8.8 expects.

**Data** (test-data-local/research-2026-10-08/QA, read at run time, never copied): `qa/awqaf_2026.csv`,
`qa/awqaf_api_raw.json`, `qa_skeptic/awq.csv`, `awq2026.csv`, `awq_misc.csv` (the API, 2006-2027); `qa/doha_all.csv`
(QCH's Doha, 148 days of 2026); `qa/pdfs/rabia1_1448.pdf`, `rabia2_1448.pdf`, `qa_skeptic/dl/rabia2_1444.pdf`,
`shaw1446.pdf` (QCH's calendars, read with fit.py's PDF reader; the PDFs' Doha equals `doha_all.csv` where both
exist); `qa/cal.html` (`/cal`, 2026-10-08) and more `/cal` pages from `research-2026-10-11/QA/cal/` or `--cal DIR`
(2026-10-11 is today only in the session scratchpad, `official/QA/dl/cal_20261011.html`). Umm al-Qura's Ramadan
days are checked against KACST's 2025-2027 tables (`research-2026-10-08/SA`, their Hijri dates only). FitSample
sources: `qa_awqaf_api_2026_at_2006` (the API's 2026 days, read at 2006), `qa_awqaf_api_2026_month_split`,
`qa_awqaf_api_2020_2027`, `qa_qch_doha` (every QCH Doha day), `qa_qch_print_1448` and
`qa_qch_print_1448_month_split` (the printed town minutes), `qa_qch_cal` and `qa_qch_cal_leave_one_out` (`/cal`),
and the profiles' `qa_qch_towns_print_1448_cal` and `qa_qch_towns`.

**Held-out scores** (FitStats, common.compute at the actual dates; all within 1 minute, worst 1 minute outside the
quirk days):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| `qa_awqaf` Doha, record (439 days) | 85.0 % | 89.1 % | 96.4 % | 96.8 % | 88.8 % | 88.8 % |
| month-split of 2026 (363 days) | 85.7 % | 89.8 % | 97.2 % | 97.5 % | 92.6 % | 92.6 % |
| the API's 2020-2025 and 2027 days (76) | 81.6 % | 85.5 % | 92.1 % | 93.4 % | 71.1 % | 71.1 % |
| `qa_qch` Doha, every QCH day (207: 2022, 2025, 2026) | 79.7 % | 82.1 % | 96.6 % | 94.7 % | 89.4 % | 89.4 % |
| Al Shamal (61 printed days; others 2 `/cal` days) | 90.2 % | 1 of 2 | `qa_qch` 0 of 2 | `qa_qch` 2 of 2 | 68.9 % | 2 of 2 |
| Dukhan | 96.7 % | 0 of 2 | 2 of 2 | 2 of 2 | 93.4 % | 2 of 2 |
| Abu Samra | 86.9 % | 2 of 2 | 1 of 2 | 0 of 2 | 80.3 % | 2 of 2 |
| Mesaieed, Halul (leave one `/cal` day out) | 1 of 2 each | 1 of 2 each | 2/2, 1/2 | 2/2, 2/2 | 1/2, 2/2 | 1/2, 2/2 |

Computed at the reference year the table is 98.0 % exact; computed for 2026, 1 minute off on 2–14 % of the times
(Fajr the most), as 8.3 expects. Held-out Ramadan days: `qa_awqaf` 36, `qa_qch` 30 (Ramadan 1447 in full).

**Tier A** (9.5). No zone passes. Doha fails on the perpetual table computed for the actual year (Fajr 85.0 %,
sunrise 89.1 %, Maghrib and Isha 88.8 %; `qa_qch` likewise) and would fail even in-sample at 2006 (Maghrib and Isha
96.4 %). The towns lack data: Fajr and Maghrib on 61 days of August to October, the other times on two `/cal` days.

**Quirk days.** `qa_awqaf`: 2026-02-17 and 2026-03-19, where the API's Ramadan follows its own Hijri labels
(Maghrib and Isha up to 2 minutes off there); 2027-02-07 has no API row at hand. None for `qa_qch`.

**Open issues.**
- A year of `/cal` days (all six places, every time) and of QCH's calendars, for the towns' points and records;
  the 1447 calendars (images) would date the change of the town minutes.
- Al Shamal's Maghrib: no point reproduces more than 47 of its 59 printed minutes (the plan's point the same).
- The September Maghrib glitch of the perpetual table (six days, both publishers): kept as misses, not quirk days.
- QCH's Ramadan 1448 page (`qa_skeptic/dl/ramadan.html`, 2027-02-08 to 03-08) fits no Doha computation (its Dhuhr
  runs from 11:42 to 11:49 across February, against 11:48 to 11:45 computed): not used.
