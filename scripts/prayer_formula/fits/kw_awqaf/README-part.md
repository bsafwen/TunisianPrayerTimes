#### Kuwait (`kw_awqaf`)

`fits/kw_awqaf/fit.py` (about 1.5 minutes) fits the Ministry of Awqaf's perpetual timetable for «مدينة الكويت
وضواحيها» (Mosques Sector, on Dr Saleh Al-Ojairi's calendar) and writes `official-zones/kw_awqaf.json`. The sheet is
keyed by month and day: the fit reads each row at a reference year (E5), and every held-out score is computed at the
row's month and day in 2026, as the app shows it (A8).

**Method.**

| Time | Rule | Rounding (E1) |
|---|---|---|
| Fajr | 18° | nearest |
| Sunrise | `Standard` (−0.8333°) | nearest |
| Dhuhr | the transit, no minutes | nearest |
| Asr | shadow factor 1 on the apparent sun (E13; the zone has no pressure or temperature: 1010 hPa, 10 °C) | nearest |
| Maghrib | sunset at `Standard` | nearest |
| Isha | 17.5° | nearest |

MEEUS (E14 default), every time at the computing point 29.33 N 47.99 E, written as the Kuwait City zone's E6 morning
and evening points (its `point` is the town, GeoNames 285787, 29.367 N 47.974 E). Reference year 2011 (harness only;
the app computes the actual year, A8).

**Zones and mapping.** `LISTED_PLACES` (6.5, 8.8): Kuwait City and the 11 places of the sheet's footer, each a zone
with `baseZoneId` `kuwait_city` and its published offset on all six times (Q15): Ahmadi and Wafra 0, Failaka − 2,
Bubiyan − 1, Jahra + 2, Nuwaiseeb − 2, Al-Salmi + 6, Abdali + 2, Kubbar − 2, Qaruh − 3, Umm al-Maradim − 3. A place
in Kuwait takes the nearest of the 12, whatever the distance. Each zone's `point` (mapping only) and its one named
place: the cities1000 rows of Kuwait City, Al Ahmadi, Al Wafrah, Al Jahra, Mazari' al 'Abdali and Az Zawr (Failaka's
village), with their GeoNames ids as catalogue ids; the islands and border places at GeoNames points read from
geonames.org on 2026-10-15 (Bubiyan Island 285659, An Nuwaysib 387854, Markaz Hudud as Salmi 285459, Kubbar Island
285571, Qaruh Island 285494, Umm al Maradim Island 285420), without catalogue ids.

**Data** (test-data-local, read at run time, never copied): `research-2026-10-08/KW/kw/data.txt` (March, June,
October, November, December), `research-2026-10-08/KW/kw_skeptic/all.txt` (June, October and December read again;
its October is used: 22 October's Dhuhr is 11:33, as the crop `rows20_25.png` shows, where `data.txt` has 11:32),
`research-2026-10-11/KW/feb.txt` (February with the sheet's 29 February row), and
`research-2026-10-11/GEONAMES/cities1000.zip`. 182 rows, 6 of 12 months.

**Fit.** By windows; 29 February and 1 March are left out of the fit (quirk days, below).
0. Sun model and reference year (1990–2026), each time with its own free parameter: MEEUS 2011 1,043 of 1,080
   windows, MEEUS 2015 1,040, VSOP87 2007 1,037, MEEUS 2007 1,037, MEEUS 1999 1,025; TEXTBOOK at most 947. Every
   leading year is in the leap phase of the plan's 1999 (year ≡ 3 mod 4); within it MEEUS's 1900-epoch sun drifts,
   so the best year moves.
1. Dhuhr: the transit with no minutes, nearest.
2. The point: one point for all six times, 29.33 N 47.99 E. E6's two points (morning 29.345 N, evening 29.305 N) fit
   in-sample better but score 0.74 % worse held out, so they are not kept.
3. At the point: Fajr 18° (172 of 180 days), sunrise and Maghrib at the standard altitude (168, 175), Isha 17.5°
   (178), Asr factor 1 on the apparent sun (170; the geometric sun 154).

**Held-out scores** (month-split: fold A fits on March and November, fold B on February, June, October and December;
each row scored at 2026 by the fold that did not see it; quirk days left out):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| exact, held out at 2026 (180 days) | 77.2 % | 74.4 % | 88.3 % | 83.3 % | 78.3 % | 78.3 % |
| within 1 min, held out | 100 % | 100 % | 100 % | 100 % | 100 % | 100 % |
| exact, the asset's values at the reference year 2011 (in-sample) | 95.6 % | 93.3 % | 96.1 % | 94.4 % | 97.2 % | 98.9 % |
| exact, the asset's values computed for 2027 | 89.4 % | 90.0 % | 95.0 % | 90.6 % | 90.6 % | 94.4 % |
| exact, the profile before (18/17.5, geometric Asr, Kuwait City's point), 2026 | 75.6 % | 68.9 % | 86.7 % | 82.2 % | 72.8 % | 75.6 % |

Computed for 2025 the shares are 56–82 %, for 2028 49–78 %: as the plan expects (8.3), most misses come from
computing the actual year rather than the reference year, and they follow the leap phase. Fold A picked VSOP87 at
2015 and 29.335 N; fold B MEEUS at 2011, 29.325 N, Fajr 18.008° and sunrise up at −0.94°. The 11 places' own records
equal Kuwait City's (their printed and computed minutes both add the same whole minute).

**Tier.** Kuwait is tier B (9.5): every time within 1 minute, held out: passes (a perpetual table computed for the
actual year cannot reach tier A, A8).

**A whole year from the mosques** (`fits/kw_awqaf/mosque_year.py` with `fits/mosque_years.py`, 2026-10-17, owner's
idea; plan 9.5). Eleven mosques of the owner's dataset print a listed place's table, Kuwait City's sheet plus the
place's published offset, on all 180 transcribed rows read at 2026 (quirk rows aside): 9 at Al Jahra (+ 2), one at
Abdali (+ 2) and one at Fahaheel, on Ahmadi's (99.9 %, 99.9 % and 98.8 % of the minutes exact, none more than 1 min
off). Since the 12 zones are one table and whole-minute offsets, the year of the first follower with the most rows,
its other 185 days (January, April, May, July, August and September, the months not transcribed), is a held-out body
of every zone's record (`source` "mosque_follower"): Fajr 65.9 %, sunrise 73.5 %, Dhuhr 87.0 %, Asr 87.0 %, Maghrib
70.8 %, Isha 65.9 % exact, every time within 1 min. Lower than the transcribed months held out (77–88 %) on Fajr and
Isha, as A8's leap-cycle differences fall unevenly over the year, but within 1 minute everywhere: tier B holds. About
70 more mosques share one calendar 96.3 % exact against the sheet, every time within 1 min: another computation of
the same method (the ministry's yearly times, perhaps), not a follower.

**Quirk days.** 29 February (read at 2012-02-29 for 2011, at 2028-02-29 for the 2026 score) and 1 March, counted
apart (Q15): the only 2-minute difference of the year is the sheet's 29 February sunrise (06:16, after 28 February's
06:15).

**Open questions.**
- The other six months (January, April, May, July, August, September) are not transcribed; the ministry's PDF would
  replace the photos (9.3). Until then the followers' calendars hold them out (above).
- Asr on the apparent sun is new: the plan's 8.2 row had the geometric sun. A written statement from the ministry or
  the Al-Ojairi centre would confirm it, and the angles.
- Ramadan: the sheet shows nothing; Ramadan 1448 tables would show any change.
- The ministry app's per-area times would replace the nearest-place rule where they differ (Q15).
