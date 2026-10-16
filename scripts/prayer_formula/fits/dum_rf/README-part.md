### Russia: DUM RF's Moscow table (`dum_rf`)

Fitted by `fits/dum_rf/fit.py` (2026-10-16, about 20 s) on sheets read the same day with `fits/dum_rf/fetch.py`;
asset `official-zones/dum_rf.json`, one zone, mapping `NATIONAL` (Moscow and Moscow Oblast), then
`scripts/places/match_named_places.py` for its named place.

**The tables.** DUM RF prints one month at a time, as an image: «Время намазов по Москве и Московской области»
(dumrf.ru/img/namaz/MM.jpg). Read on 2026-10-16: the site's March–August, October and November 2026 (01, 02, 09 and 12
answer 404), and the Wayback Machine's January and February 2026 and February, June, July, August, October, November
and December 2025 (original bytes, each SHA-1 equal to the capture's CDX digest; six of the site's files are
byte-equal to its 2026 captures). 17 sheets, 516 days, **transcribed by eye** into a JSON beside each image, each half
of each table checked on an enlarged crop, and every column checked for smooth day-to-day steps (the only jumps are
the 3-day blocks of the 2026 Isha ramp, which the rule reproduces). June 2026, which the plan lacked, is on the site.

**One perpetual table, computed for 2025.** Every 2025 sheet equals the 2026 sheet of its month on every time
(weekdays and Hijri dates aside) except the Isha of July (25 of 31 days) and of 1–2 August, where DUM RF sets its
summer ramp by hand each year. Read at other years' dates, the 2025 sun covers the most windows (1,817 of 2,010 with
the fitted profile; 2029 1,785, 2021 1,784, 2017 1,743, 2026 1,374). So the stages run at 2025 (E5) and every sheet is
scored at its own year, as the app shows it (A8): the 2026 sheets meet a sun 6 hours later in the leap cycle.

**Method** (stages of 9.4 at 2025 at Moscow's centre, GeoNames 524901; defaults: the preset's values, nearest; an
alternative must cover more than 0.5 % and at least 2 more windows):

- One evaluation for all six (stage 0): the event instant (1,750 windows; 10 h UT 1,367, 0 h UT 1,060, `KACST` 1,011).
- Dhuhr + 5, nearest (329 of 335) at Moscow's longitude (330 windows at 37.6185°E, 329 at the centre: it stays).
- Sunrise − 5 and Maghrib + 5 on the standard horizon, nearest (each one's best fixed altitude −0.83° and −0.84°: 312
  and 302 of 335). The latitude stays: the halves' best latitudes, 55.757° and 55.747°, lie on both sides of the
  centre (all months: 1,003 windows at 55.757°, 1,000 at the centre).
- Fajr **18°** and Isha **15°**, nearest, on the nights the caps leave alone (192 of 215 and 196 of 223; the windows'
  own angles 18.001° and 14.993°).
- **Asr**: factor 1, nearest, with **the sun once a day at 10 h UT** (`DAILY_10H_UT`): 331 of 335 (the event instant
  264).
- So **every time rounds to the nearest minute**: the preset's Fajr and sunrise up and Maghrib and Isha down fitted
  the 2025 table read at 2026's sun.
- The caps of 7.4 hold: Fajr at 0.30 of the night, Isha at 0.30 in June and DUM RF's 2026 ramp (3-day steps of 1/240
  from 29 April to 2 August, 0.25 outside), kept. But the tables take **the night as 24 h less the date's own day**
  (sunset(D) − sunrise(D)), not the engine's nights (N(D − 1) for Fajr, N(D) for Isha): of the 120 capped Fajr and 112
  capped Isha nights in hand, 110 and 93 are exact with it, 77 and 80 with the engine's (the engine change below).

**Zone.** `moscow`, «موسكو»: Moscow's centre (55.75204 N 37.61781 E), its named place (catalogue row 524901, filled by
`match_named_places.py`); `admin1` 48 and 47 (Moscow, Moscow Oblast); mapping `NATIONAL`: every place in Moscow and
Moscow Oblast, with the validity guard (6.5). The point did not move, so the zone has no E6 points.

**Held out** (month split: the stages refitted on the odd months score the even ones, and the reverse; each sheet at
its own year, common.compute; exact / within 1 min). The asset's record is the first row (the engine as it is):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|---|
| all 17 sheets, engine as is | 73.1 / 97.3 | 70.2 / 100 | 96.1 / 100 | 79.3 / 100 | 68.6 / 100 | 68.8 / 96.9 | 4 |
| the 10 sheets of 2026, engine as is | 64.1 / 95.4 | 52.3 / 100 | 94.4 / 100 | 67.1 / 100 | 57.2 / 100 | 62.8 / 100 | 2 |
| all 17 sheets, the tables' night | 74.4 / 97.3 | 70.2 / 100 | 96.1 / 100 | 79.3 / 100 | 68.6 / 100 | 76.9 / 97.9 | 3 |
| the 10 sheets of 2026, the tables' night | 60.9 / 95.4 | 52.3 / 100 | 94.4 / 100 | 67.1 / 100 | 57.2 / 100 | 75.7 / 100 | 2 |

- Beyond 1 min (engine as is, 30 times): July 2025's Isha on 14 days (+2 to +4) and 1–2 August 2025's (+2), where the
  2025 ramp was daily and reached 0.25 about 29 July; on the 2026 sheets Fajr on 8 days of April (+2) and 6 of late
  August (−2), where the angle time moves fast and the 2026 sun is 6 hours from the table's. With the tables' night,
  25: July 2025's Isha on 11 days (+2 to +3), and the same 14 Fajr days.
- At 2025, the table's own year: Fajr 81.4, sunrise 95.2, Dhuhr 98.3, Asr 98.4, Maghrib 86.6, Isha 79.1 % exact, every
  time within 1 min but the 2025 Isha of July and of 1–2 August; with the tables' night, Fajr 91.1 and Isha 82.2 %, and
  only July 2025's Isha beyond 1 min.
- The preset before this fit, each sheet at its own year: Fajr 45.5 / 94.0, sunrise 45.0 / 96.5, Maghrib 43.0 / 99.6,
  Isha 45.7 / 97.1 %, 3 min at worst.
- Plan 9.2 (±2 min on the 2026 sheets and June 2025, ±3 on July 2025's Isha): met on the 2026 sheets and June 2025;
  July 2025's Isha reaches 4 min on three days with the engine as is and 3 with the tables' night. No tier: September
  is missing and A8's year differences keep exact shares far below 97 %, so «مستخلصة من جداول».

**Engine change (proposed, not applied).** `HighLatitudeRule.DumRf` takes the night of its date, N = 24 h − (sunset(D)
− sunrise(D)), for both caps: Fajr = later(t, sunrise(D) − 0.30·N), Isha = earlier(t, sunset(D) + r(D)·N). `fit.py
--proposed-night` scores it by replacing the reference's DumRf in memory; with it adopted on both sides, run `fit.py`
again to rewrite the asset's record.

**Data** (test-data-local, read at run time, never copied): `research-2026-10-16/RU/dum_rf/` (images, their JSON
transcriptions, `wayback/cdx_img_namaz.txt`, `MANIFEST.tsv`).

**Open.**
1. The July ramp changes from year to year (July 2025 daily, July 2026 in 3-day blocks): each new summer sheet is
   checked; the September and December 2026 sheets, when published, are the missing months.
2. The table is perpetual (2025 = 2026); the app computes the actual year (A8), so 2026's times are 1 minute off on
   about a third of the times, 2 on 14 Fajr days.
3. Whether DUM RF's practice holds outside Moscow and its oblast is unknown (Q22): elsewhere in Russia `dum_rf` runs
   at the user's point.
