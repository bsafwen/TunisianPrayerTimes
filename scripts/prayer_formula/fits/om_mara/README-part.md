#### Oman (`om_mara`)

`fits/om_mara/fit.py` (under 1 minute) fits the Ministry of Endowments and Religious Affairs' zone tables and writes
`official-zones/om_mara.json`, 86 zones, every one tier A since 2026-10-17. It needs the calendar_page4 pages (Data,
below) and stops without them.

**Method.**

| Time | Rule | Rounding (E1) |
|---|---|---|
| Fajr | 18° | nearest |
| Sunrise | the standard horizon (−0.8333°), no elevation | nearest |
| Dhuhr | transit + 5 | nearest |
| Asr | shadow factor 1, geometric sun, + 5 | nearest |
| Maghrib | sunset (−0.8333°) + 5 | nearest |
| Isha | 18° | nearest |

Every time takes the sun once a day, at 0h UT of the civil date (E4 `DAILY_0H_UT`, the noon shadow included), and
the sun is ALMANAC (E14): the Astronomical Almanac's low-precision formula with the constants of PrayTimes.js. Every
time is then 0.86 s later (an E18 constant on all six times: `seasonalOffsets`, 36 equal values of 0.86/60 min =
0.014333 min); Isha by angle keeps its own value. Stages, by windows (a published minute bounds the unrounded time to
a minute), on the six zones with full 2026 tables:

1. Dhuhr alone, with a free longitude per zone (so a constant shift or offset decides nothing): ALMANAC at 0h UT
   covers all 2,190 windows, MEEUS and VSOP87 at 0h UT 2,170 each, every sun at the event instant 2,031–2,039,
   `KACST` 2,016–2,017.
2. All six times at whole arc-minute points, then a constant shift by windows (13,140 minutes): ALMANAC 13,129 at
   + 0.86 s (12,948 without), VSOP87 12,962 at + 1.28 s, MEEUS 12,948 at + 0.99 s, TEXTBOOK 10,899. On ALMANAC every
   time alone wants the same shift (Fajr + 0.89, sunrise + 0.86, Dhuhr + 0.85, Asr + 0.90, Maghrib + 0.84, Isha
   + 0.79 s); on VSOP87 and MEEUS they spread over 0.6 and 0.9 s. The shift's origin is unknown (KACST's tables need
   + 1 s on VSOP87, `sa_umm_al_qura`).
3. Each plan value by its own scan, the points refitted (of 13,140): every offset 1 minute off gives about 10,940
   (Dhuhr, Asr and Maghrib + 5, the others 0); rounding up or down 12,736 against nearest 13,129; Asr on the
   apparent sun 12,896, factor 2 10,940; the horizon window is −0.8333° itself (4,377 of 4,380 sunrise and Maghrib
   minutes); Fajr 18.000° (2,189 of 2,190), Isha 18.000° (2,187); the sun at 10:00 UT 10,882, `KACST` 8,855. No
   elevation: Saiq (about 2,000 m) fits the sea-level horizon. No Ramadan rule and no revision: Muscat's 2021 and
   2031 months fit the same rule.

The six full-year points, at whole arc-minutes, are those found before (8.8): Muscat 23°35'N 58°17'E (the
airport), Saiq 23°05'N 57°39'E, Sohar 24°21'N 56°44'E, Salalah 17°02'N 54°05'E, Musandam 26°11'N 56°15'E (Khasab),
Masirah 20°40'N 58°54'E. The month-split folds keep them (shift + 0.89 s on the odd months, + 0.86 s on the even
ones). The 80 other points, at whole arc-minutes too, are fitted on each zone's whole 2026 (calendar_page2, since
2026-10-17; before, on the 25 page4 dates, which fix latitude loosely): 175,025 of their 175,200 minutes, the
lowest zone 2,183 of 2,190. The profile stays the six zones' (the stages above).

**Zones and mapping.** The 86 zones of calendar_page4, `NEAREST` with `namedFirst` (6.5, 8.8).
- `id`: the ministry's CityID, the row order of calendar_page4 (checked against calendar_page2 at the six zones).
- `nameAr`: the ministry's Arabic name.
- The computing point: fitted at whole arc-minutes on each zone's whole 2026, held in `morningPoint` and
  `eveningPoint` (E6).
- `point` and `namedPlaces`: the town the zone is named after, with `areaKm` 10: GeoNames cities1000 for 31
  towns (ids in `TOWNS`, fit.py), OpenStreetMap places for 37 (Overpass, read 2026-10-11). `catalogueIds` stay
  empty until M5 builds the catalogue. 59 computing points lie within 7 km of their town. The far ones are the
  ministry's own: «الحمرة» 74 km north of Al Hamra, «قرن العلم» 66 km from Qarn Alam, «رخيوت» 55 km east of
  Rakhyut, then Muscat 13 km (GeoNames' Muscat, the catalogue city; the old town is 32 km away), Haima 12 km,
  Quriyat 11 km.
- 18 zones have no town, and their `point` is the computing point: الجازر، الحلانيات، الخضرفي، القابل الظاهرة،
  الهويسة، جدة الحراسيس، جعلان بوحسن، جعلان بوعلي، خزان، رمال الوهيبة، سيح الرول، صراب، ظلكوت، فهود، كنهات،
  محوت، مرمور، هرويل (fields, stations, islands and a sand desert, and six wilayat whose seat no source at hand
  places: Al Jazir, Al Qabil in al-Dhahirah, the two Jaalans, Dhalkut, Mahout).

**Data** (test-data-local, read at run time, never copied):
- `research-2026-10-08/OM/omv/om_check.csv`: calendar_page2, all of 2026, zones 0, 49, 52, 56, 73 and 75.
- `research-2026-10-08/OM/om/yrs/y2021_m*.html`, `y2031_m*.html`: Muscat, March, June, September and December of
  2021 and 2031 (244 days).
- `research-2026-10-08/OM/om/cal_p4.html`, `en_p4.html`: calendar_page4 of 2026-10-08, Arabic and English.
- `research-2026-10-11/OM/page4/` (or `--pages DIR`): calendar_page4 pages `p4_YYYY_MM_DD.html`, 25 dates of 2026
  (two a month) and, under `holdout/`, 2027-02-14, 2027-08-10, 2029-04-18, 2029-11-02, 2031-06-28 and 2031-12-30,
  collected on 2026-10-11 (their SHA-256 sums in the folder's README).
- `research-2026-10-17/OM/page2/p2_2026_MM_Z.html`: calendar_page2, every month of 2026 at the 80 other zones
  (960 pages, fetched 2026-10-17 with the owner's OK; MANIFEST.tsv one level up). Each page's own selectors (year,
  month, CityID) are checked against its file name, and its rows against calendar_page4's on the 25 dates.
- FitSample sources: `om_mara_page2_2026` (calendar_page2, six zones), `om_mara_page2_2026_zones` (calendar_page2,
  the 80 other zones), `om_mara_page4_2026` (the 25 page4 dates),
  `om_mara_page4_2027_2031` (the 6 later dates), `om_mara_page2_muscat_2021_2031` (Muscat's 8 months);
  `_month_split` marks a month-split.

**Held-out scores** (FitStats, common.compute; all within 1 minute, worst 1 minute):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| Profile, 32,150 zone-days | 99.9 % | 99.8 % | 100 % | 99.9 % | 99.9 % | 99.9 % |
| Six full-year zones: month-split, 6 later dates, Muscat's 2021 and 2031 | 99.9 % | 99.9 % | 100 % | 99.9 % | 99.9 % | 99.8 % |
| 80 other zones: month-split of their 2026, 6 later dates | 99.9 % | 99.8 % | 100 % | 99.9 % | 99.9 % | 99.9 % |
| The 6 dates of 2027–2031 alone, 86 zones (516 each) | 99.8 % | 100 % | 100 % | 100 % | 100 % | 99.6 % |
| Muscat, 2021 and 2031 (244 days) | 243 | 244 | 244 | 242 | 243 | 244 |

On the 25 dates of 2026 the 86 points reproduce 12,882 of 12,900 minutes (the lowest zone 149 of 150). Before
ALMANAC was in the engine, the profile shipped VSOP87 with + 1.28 s: 98.15 % held out (MEEUS with + 0.99 s 97.88 %).

**Tier A** (9.5). All 86 zones pass (2026-10-17): every time 99.2–100 % exact held out, all within 1 minute, 28 or
more held-out days in every month; the weakest times are 99.2 % (Al Hashman's, Sur's and Manah's sunrise,
Mudhaibi's and Marmour's Fajr). Before calendar_page2's year reached the 80 other zones they failed on coverage
(31 held-out days, at most 3 in a month).

**A whole year from the mosques** (`fits/om_mara/mosque_year.py` with `fits/mosque_years.py`, 2026-10-17, owner's
idea; plan 9.5). Nine mosques of the owner's dataset print a zone's table on all 25 page4 dates of 2026 (two a
month), at least 98.7 % of the minutes exact and none more than 1 min off: two at Muscat (0), whose full-year table
leaves nothing more to score, and seven at five of the 80 other zones. Each zone's follower with the most dates is
its held-out year on the other 340 days of 2026 (`source` "mosque_follower"):

| Zone | Followers | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Tier A |
|---|---|---|---|---|---|---|---|---|
| 3 «ازكي» (Izki) | 1 | 100 % | 100 % | 100 % | 100 % | 100 % | 100 % | passes |
| 28 «بركاء» (Barka) | 1 | 100 % | 100 % | 100 % | 100 % | 100 % | 99 % | passes |
| 29 «بهلاء» (Bahla) | 1 | 100 % | 100 % | 100 % | 100 % | 100 % | 100 % | passes |
| 53 «صحم» (Saham) | 2 | 98 % | 99 % | 99 % | 99 % | 99 % | 99 % | passes |
| 58 «ضنك» (Dhank) | 2 | 99 % | 100 % | 99 % | 92 % | 99 % | 100 % | no: Asr beyond 1 min on 14 days of February |

Every time of the first four is within 1 minute, so they passed 9.5 before their official years came. Since
2026-10-17 every zone's whole 2026 is official, so the followers have no day left to score and no record holds a
follower year (`mosque_year.py` reads calendar_page2's zone pages too). Dhank's two followers share one calendar, whose Asr is 2 min early on 10 days of early February and 7–9 min late on its last
four; no other follower strays in February, so that Asr is the mosques', not the ministry's, and Dhank stays
«مبنية على جداول منشورة». 21 more mosques print 80–97 % of a zone's minutes (four at Barka 95.3 %, one at Samail 96.7 %,
five at Al Buraimi 89.3 %): not followers.

**Quirk days.** None: every miss is 1 minute, at a rounding boundary.

**Open issues.**
- Another year of calendar_page2 (11 years are online, 2021–2031) as a held-out year for every zone.
- The towns of six wilayat (above), and M5's catalogue rows for every named town.
- The origin of the constant shift; a method field for it would read better than an E18 constant.
