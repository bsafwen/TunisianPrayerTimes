#### Syria (`sy_awqaf`)

`fits/sy_awqaf/fit.py` (standard library, about 10 minutes on 10 processes) fits the year tables of the Ministry of
Awqaf's mosque screens and the live screens' answers, and writes `official-zones/sy_awqaf.json`; `mosque_year.py`
compares the owner's Syrian mosques with the zones (support only).

**The source** (2026-10-18). The ministry's mosque screens (`tv.masjidak.mow.gov.sy/<mosque id>`) and its app «مسجدك»
load a year table per region, `upload/json_prayers_times/prayer_times_region_json_<id>.json`, ids 761–870 (837
missing): 109 files of 366 rows `{Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha}` ("H:MM", UTC+3), no dates, no names
(`test-data-local/research-2026-10-18/SY/masjidak/`, README and MANIFEST there, never committed). A screen shows row
(the date's day in a leap year − 1): `masjidi.min.js`'s `getDayIndex` sums `LEAD_MONTHS_DAYS` = [31, 29, 31, …], with
the mosque's `daylightSaving` mode `none`. The same screen software can instead request
`api.aladhan.com/v1/calendar/2024` at the mosque's own coordinates (`"type": "aladhan-api"`, live screens, below).

**The generator.** Every table is aladhan.com's PrayerTimes (the PHP port of PrayTimes.js that the API runs, read from
its source): MWL 18°/17°; the Astronomical Almanac's low-precision sun with PrayTimes' constants (S1 `ALMANAC`), once
per time at PrayTimes' default hour of local mean time on the date (Fajr 5, sunrise 6, Dhuhr 12, Asr 13, Maghrib and
Isha 18; E4 `PRAYTIMES`); the 0.833° horizon; Dhuhr + 1 and Maghrib + 2 (the request's tune); each time to the nearest
minute; UTC+3. Two traits are aladhan's own:

- **Asr's noon shadow** takes the declination at `gregoriantojd(date) + f + 13/24`, where f is the API server clock's
  day fraction when the table was fetched (`date('G')/24 − 0.5`, wrapped, plus its minutes; the server keeps UTC); the
  hour angle uses the declination at 13h local mean time as usual. So each table keeps its own fetch time, the zone's
  `asrShadowHourUt` = 25 + 24 f hours after 0h UT (42.65–48.73 h here: the ministry's staff fetched between about
  05:30 and 11:40 UTC). Al-Qadmus's (870, uploaded 2026-08-20 08:00 UTC) says 44.87 h: fetched at about 07:52 UTC.
- **The table year.** Each table is computed for its own year, which the low-precision sun distinguishes (its
  perihelion drifts against the calendar: 2003 and 2023 at the same phase differ by up to 3 s in the equation of time,
  enough to move several minutes a year), so the fit names it among 2000–2032: 2003 for 50 tables (Aleppo, Idlib and
  Rif Dimashq), 2001 for the coast (6), 2010 for Daraa (6), 2020 and 2019 for Homs (8), 2023 for Hama (7), 2024 for
  Suwayda (4) and the north-east (10), 2026 for the coast's last four, 2017 (2), 2011 (Damascus) and 2009 (1).

**The rows.** Row 59 of most files (83 tables) repeats row 58, so the screens show 28 February's times on 29 February
(`LEAP_ROWS_FEB28`); Damascus's and one more (2) repeat 1 March (`LEAP_ROWS_MAR1`; region 761's rows 59–61 hold 1, 2
and 2 March, so its 1 March shows the 2nd). The 14 files of 2026-08-20 (Hasakah, Qamishli, Malikiyah, Shaddadi, Ras
al-Ayn, Raqqa, Ma'adan, Tabqa, Jarniyah and Tell Abyad, which are aladhan's 2024, a leap year, with 29 February
dropped; Tartus, Banias, Safita and al-Qadmus, 2026) hold the year's 365 days and then 31 December again: read by
leap-year day, their screens show the next day's row from 1 March to 30 December, and 1 March on 29 February
(`COMMON_ROWS`). The app shows what the screens show (A8 at the zone: `referenceYear` and `tableRows`).

**Region 763** is the Beirut table that tv.masjidi.app serves (`research-2026-10-08/LB/lb/region2.json`): equal on all
366 rows (checked by the fit), Lebanon's Dar al-Fatwa on UTC+2. No Syrian zone.

**Fit, per distinct table** (100 distinct of 108 regions; 764–767, 768/769, 770/800/801, 832/849, 833/834 and
838/843 are identical, so 99 table zones), by windows with the closed form, which is the reference engine's PRAYTIMES
computation (the reference reproduces it on every day): the layout from the file, the year by Dhuhr's windows (the best
four then by the point's score, ties to the year nearest 2025), the point by latitude scan (32.2–37.4°, 0.02°, 0.001°,
0.00005°) and longitude windows of Fajr, sunrise, Dhuhr, Maghrib and Isha (the middle of the best latitudes), and
Asr's shadow hour by minutes (the middle of the longest best run). Fitting the odd months and the even months apart
gives every table the same year.

**Held-out scores** (FitStats, every table zone: month split, the year, point and shadow hour fitted on the odd months
and scored on the even ones and the reverse, through `common.compute` on the screens' dates of 2028; 36,234 days):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| exact | 99.87 % | 99.90 % | 99.93 % | 99.65 % | 99.89 % | 99.85 % |
| within 1 min | 100 % | 100 % | 100 % | 100 % | 100 % | 100 % |

All 99 table zones pass tier A (each at least 98.1 % exact on every time). In-sample with the shipped values: 100,
100, 100, 99.94, 100 and 99.97 %. The misses are single rows, such as 761's 1 March.

**Live screens.** A sample of the screens' configuration pages (2,047 of them: every 10th id from 3 and every 29th from
1 and 14 up to 23,500; `research-2026-10-18/SY/masjidak/mosques.tsv`, never committed) shows 538 (26 %) with no table:
their `apiOptions` are latitude, longitude and `shafaq` only, so aladhan answers with its default for the point, the
authority whose seat is nearest, Jordan's ministry (id 23: Fajr 18°, Isha 18°, Maghrib = sunset + 5 min, no tune) for
most of Syria and Turkey's Diyanet (id 13: 18°/17°, tune sunrise − 7, Dhuhr + 5, Asr + 4, Maghrib + 7) near the
northern border, and the screen keeps its first answer for good (`masjidi.min.js`'s saved fetch refetches only when
the options change). Where a town's sampled screens are mostly live (at least 3, and twice as many as on tables: 28
towns, all of Deir ez-Zor governorate's sampled ones among them, Quneitra, eastern Aleppo, northern Aleppo and Afrin
villages, Salqin, rural Homs, Dayr Atiyah, Ayn al-Fijah, Halfaya, Qastal Ma'af), the town is a zone of its own: the
API's answer for the town's point (`research-2026-10-18/SY/aladhan_live/`, 28 calendars of 2024, fetched 2026-10-18,
never committed) gives the default's angles and minutes, carried as the zone's `ishaAngleDeg` (S5) and `offsets` on
this method (Jordan's: Dhuhr − 1, Maghrib + 3, Isha 18°; Turkey's: sunrise − 7, Dhuhr + 4, Asr + 4, Maghrib + 5), with
2024's calendar (`referenceYear` 2024) and Asr's shadow hour at the middle of the day's possible fetch times (37 h:
each screen's own is unknown, and daytime fetches fall about 12 h either side of it, since aladhan's day fraction wraps
at 12:00 UTC). Against those answers, every day held out (nothing is fitted on them): Fajr, sunrise, Dhuhr, Maghrib
and Isha 100 % exact, Asr 75.7 % (71–80 % by town), every time within 1 min; with this fetch's own hour (46.55 h) Asr
is 100 % too, so the rest is aladhan's computation. Tier B, not A: a screen's Asr is its fetch time's.

**Zones and mapping** (`NEAREST`, `namedFirst`, the validity guard). Each table zone computes at its fitted point (E6
morning and evening points). A screen's town (else its address) is the catalogue place of that Arabic name nearest the
table's computing point (within 120 km), and each place goes to the zone with the most screens there, a live town to
its own zone. A table zone's `point` and name are its main town: the computing point's own place (within 3 km) where
the screens hold it or name no town in a quarter of them, else the screens' first town. So region 783, computed at
Aleppo's point, is Jindires (its screens: Jindires, Afrin, Hajib, Sheikh al-Hadid), and Aleppo city is 804; 809 is
Salamiyah, computed 60 km east of it at 35.01 N 37.71 E; 856 is al-Tall. Every town a zone's screens name is a named
place of it (a catalogue pick there takes that zone). A zone without a main town keeps its computing point and the
nearest place's name, with its region number where another zone has that name. Tables that no sampled screen shows,
computed in another zone's main town or at another table's point, are left to manual picks (tier 1): 761 (Damascus,
whose screens show 764–767), 762 (at 768/769's point) and 786 (Khanasir, a live town). The guard's side check (2026,
approximate town points): Tabqa, Zabadani, Khan Shaykhun, Palmyra, Ras al-Ayn, Tell Abyad, Malikiyah, Qamishli and
Nabek take their own regions' tables; Sukhnah and Al-Tanf take Palmyra's (65 and 124 km; 2.7 and 3.9 min beyond its
margin, so the guard keeps it); Deir ez-Zor, Mayadin, Abu Kamal and Quneitra take their live towns.

**Cross-checks** (support): the owner's mosque dataset has 6 of its 75 Syrian mosques on a table zone on all 365 days
(764 three times, 844, 846, 847; `mosque_year.py`); its two Deir ez-Zor mosques follow neither the tables nor the live
screens. The ministry's printed Ramadan tables (Telegram @SyMOfE, 1446 and 1447) are another product, 1–2 minutes off
the screens; the profile fitted on them before 2026-10-18 (Umm al-Qura's roundings with `KACST` evaluations,
91.8–98.6 % on 73 Ramadan days) is replaced.

**Open issues.** (1) Unsampled regions are named and placed by their computing point, which may not be the town their
screens serve; no sampled screen shows the north-east's tables yet. (2) A live screen set up when aladhan's default for
its point differed keeps that default. (3) If the ministry rewrites a file (the north-east's next-day rows, a new
year), or the sample changes, the fit must run again.
