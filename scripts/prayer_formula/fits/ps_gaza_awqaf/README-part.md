#### Palestine, Gaza Strip (`ps_gaza_awqaf`)

`fits/ps_gaza_awqaf/fit.py` (refitted 2026-10-18, about 30 seconds) fits the Gaza Ministry of Awqaf's times and writes
`official-zones/ps_gaza_awqaf.json`: one `NATIONAL` zone, «محافظات غزة», since the ministry's decree of 2021-10-05
unifies the adhan in every mosque of the Strip and its tables, radio posts and widget name no city (8.8, A17).

**Method** (each time fitted on its own, at Gaza City's point; the windows count the times within 1 minute first,
then the exact ones):

| Time | Rule |
|---|---|
| Fajr | 19.4°, rounded down |
| Sunrise | standard horizon, nearest |
| Dhuhr | transit, nearest |
| Asr | factor 1, apparent sun (E13), nearest |
| Maghrib | standard sunset + 3 min, nearest |
| Isha | 17.394°, rounded up |
| All | MEEUS sun at each event's instant (VSOP87 holds one minute fewer within 1 minute, ten more exact), no dip |

**Zone** (1, `NATIONAL`): id `gaza`, «محافظات غزة», `point` and the one named place at Gaza City (GeoNames 281133);
no E6 points.

**Data** (test-data-local, read at run time, never copied): `research-2026-10-18/PS/gaza_ministry_2026.csv`, the
ministry's 2026 table, every day and all six times, which the ministry emailed to the owner on 2026-10-18 in answer to
the owner's request (README there). Its 365 rows are all on UTC+3, winter included, though tzdata's Asia/Gaza is UTC+2 until
2026-03-28 and from 2026-10-25 (1 January's Dhuhr, 12:45, is the transit only at UTC+3): every row is read as a UTC+3
instant, and the app shows each instant on the phone's own clock. Cross-checks, at UTC+3 in summer:
`research-2026-10-08/PS/ps/gaza_1444_dhulhijja.txt` (the Dhu al-Hijja 1444 table, 2023-06-19 to 07-18, from its
Telegram image), `gaza_2026_radio.txt` (the ministry's radio, 2026-05-19 to 06-09) and `gaza_widget_20261008.txt`
(palwakf.ps's footer, five times). The emailed table equals the widget's day, 20 of the 22 radio days (on 2026-05-26 the
radio's sunrise 05:41 is right and the table's 05:31 a misprint) and 24 of the 2023 table's 30 days at the same dates:
the ministry's table is nearly the same every year.

**What the table is.** No day-by-day computation: its Dhuhr strays from the transit by up to 148 s within a month and
holds one minute for up to 26 days, so no constant reproduces more than 270 of its 365 Dhuhr minutes, whatever the
sun model, evaluation or table year (1990–2030), nor do the crude Fourier suns (Spencer 1971, the B formula). Isolated
misprints break its monotonic runs: Fajr 2026-05-16 (04:00 between 04:04 and 04:02) and 10-21, sunrise 05-26, Dhuhr
03-23; the app shows its computed minute there.

**Held-out scores** (the emailed year by month-split: fold A fitted on the odd months, fold B on the even ones, each
scored on the other months; common.compute):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| Exact | 56.4 % | 59.2 % | 66.3 % | 65.8 % | 55.3 % | 53.7 % |
| Within 1 minute | 98.4 % | 99.2 % | 99.7 % | 99.2 % | 99.2 % | 98.4 % |

365 days, every month whole; worst 9 minutes (the sunrise misprint), 2–4 minutes on 21 more times. The year's values
score the earlier sources at Fajr 5–17, sunrise 9–13, Dhuhr 13–21, Asr 11–25, Maghrib 14–17 and Isha 10–11 exact of
their 22 to 30 days, 2 minutes at worst.

**Tier.** Not B (9.5): about 1 % of each time is 2 minutes or more off held out, at the table's own irregular steps
and misprints. The record now covers the whole year, against 53 summer days before (2026-10-15: Fajr 19.37°, sunrise
and Maghrib + 2 rounded up, Isha 17.31°).

**Quirk days.** None counted: the misprints above are listed, and the table's held minutes are its way.

**Open questions.**
- The ministry's own rule, if it computes at all (its Dhuhr suggests a table smoothed by hand), and whether its 2027
  table repeats 2026's (a held-out year).
- The clock: the table's winter rows are on UTC+3 while tzdata's Asia/Gaza is UTC+2; if the Strip kept summer time
  all year, tzdata (and the phone) would need to follow.
