#### Palestine, Gaza Strip (`ps_gaza_awqaf`)

`fits/ps_gaza_awqaf/fit.py` (2026-10-15, under a second) fits the Gaza Ministry of Awqaf's times and writes
`official-zones/ps_gaza_awqaf.json`: one `NATIONAL` zone, «محافظات غزة», since the ministry's decree of 2021-10-05
unifies the adhan in every mosque of the Strip and its tables, radio posts and widget name no city (8.8, A17).

**Method** (each time fitted on its own, at Gaza City's point; the windows count the times within 1 minute first,
then the exact ones):

| Time | Rule |
|---|---|
| Fajr | 19.37°, nearest |
| Sunrise | standard horizon, rounded up |
| Dhuhr | transit, nearest |
| Asr | factor 1, geometric sun, nearest |
| Maghrib | standard sunset + 2 min, rounded up |
| Isha | 17.31°, rounded up |
| All | VSOP87 sun at each event's instant (MEEUS scores 1 exact minute fewer: 236 against 237), no dip |

- In-sample, all 53 days: Fajr 34, sunrise 41 of 52, Dhuhr 35, Asr 42, Maghrib 43, Isha 42, every time within 1 minute.
- The 2023 table's Dhuhr spreads over 2.1 min against the transit (−0.76 to +1.35), more than any one rounding
  allows: it holds one minute for up to 8 days while the transit moves 0.17 min a day. The 2026 posts fit a
  rounded-up transit (−0.14 to +0.98). So no single rule makes both sources exact.
- The two sources disagree on Fajr: the 2023 table fits 19.33° alone, the 2026 posts 19.47°, about 0.8 min apart.
  Egypt's 19.5° and 17.5° (8.2, the research's estimate) lie outside both best windows.
- The point stays at Gaza City (GeoNames 281133): 53 days of May to July and one October day fix no latitude, and the
  2023 Dhuhr fixes no longitude (the windows' best lies near 34.42-34.43 E, at sea).

**Zone** (1, `NATIONAL`): id `gaza`, «محافظات غزة», `point` and the one named place at Gaza City; no E6 points.

**Data** (test-data-local, read at run time, never copied; every row at UTC+3, checked against tzdata's Asia/Gaza):
`research-2026-10-08/PS/ps/gaza_1444_dhulhijja.txt` (the ministry's Dhu al-Hijja 1444 table, 2023-06-19 to 07-18, 30
days, transcribed from its Telegram image), `gaza_2026_radio.txt` (the ministry's radio, 2026-05-19 to 06-09, 22 days)
and `gaza_widget_20261008.txt` (palwakf.ps, 2026-10-08, five times; its sunrise came from another site and is not read).

**Held-out scores** (each source scored with the parameters fitted on the other; common.compute):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| Exact | 20.8 % | 78.8 % | 52.8 % | 67.9 % | 77.4 % | 62.3 % |
| Within 1 minute | 96.2 % | 100 % | 100 % | 100 % | 100 % | 98.1 % |

53 days (52 for sunrise), worst 2 min (Fajr twice, Isha once).

**Tier.** B (9.5): not met held out (Fajr and Isha 2 min off on 3 days); in-sample every time is within 1 minute. The
record covers May, June, July and one October day only.

**Quirk days.** None counted; the 2023 table's held minutes are its way, not isolated anomalies.

**Open questions.**
- A year of the ministry's daily posts or monthly tables (9.3) would fix the angles, the point and whether the 2023
  and 2026 Fajr really differ (a rule change between them would be an E9 revision).
- The evidence: the times now come from the ministry's own tables (`FROM_TABLES`), but on 53 summer days.
