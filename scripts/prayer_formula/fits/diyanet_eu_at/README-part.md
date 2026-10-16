### Austria: IGGÖ's city tables and `diyanet_eu` (`diyanet_eu_at`)

Checked and fitted by `fits/diyanet_eu_at/fit.py` (2026-10-15, under 1 min); asset `official-zones/diyanet_eu_at.json`
(method `diyanet_eu`, country `AT`, mapping `NEAREST`), the first of 6.5's two tiers: IGGÖ's nine state capitals.

**Does `diyanet_eu` hold in Austria?** Outside Diyanet's summer takdir, yes: as `EuropeanPresets.DIYANET_EU` stands
(18° / 16°, sunrise − 7, Dhuhr + 5, Asr + 4, Maghrib + 7, the sun once a day at 0 h UT, NEAREST), computed at each
table's fitted point, IGGÖ's April–December 2026 tables are reproduced on 97.4–98.2 % of days per time held out, every
time within 1 min but 30 April's Fajr at Vienna and St. Pölten (2 min, the takdir's first day, a day early). **In the
takdir season it does not**: from May to early August, IGGÖ's Fajr and Isha are 23.9 % and 32.4 % exact and 38.5 % and
41.7 % within 1 min, up to 11 min off (in-sample: the takdir constants of `DiyanetTakdir.kt` were derived from these same
tables).

**What IGGÖ's takdir is.** In every city the summer Fajr and Isha are straight lines in clock time: from the last day
the plain 18° Fajr (16° Isha) is printed to 21 June, then back to the first plain day, each printed minute within
0.6–1.5 min of those two lines. Fajr leaves the plain time on 30 April (Vienna, St. Pölten, Linz) to 9 May
(Klagenfurt) and Isha 7–8 days later; both come back within a day of each other, 31 July (Klagenfurt) to 10 August
(Vienna, Linz). The slopes are 0.385–0.465 min a day before the solstice and 0.40–0.525 after (Isha 0.444–0.571 and
0.40–0.488). On 21 June the printed Fajr lies at 0.672 (Linz, 48.28°N) to 0.738 (Klagenfurt, 46.62°N) of the midnight
depression and Isha at 0.625 to 0.686: the k(φ) lines of `DiyanetTakdir.kt` roughly hold at the solstice (within
0.015), but the engine applies k·D on every night, a curve where IGGÖ draws a V, so it is 4–11 min off in mid-May and
in July.

**Zones.** Nine, ids `bregenz`, `eisenstadt`, `graz`, `innsbruck`, `klagenfurt`, `linz`, `salzburg`, `st-poelten`,
`wien`, Arabic names transliterated; each `point` is the city centre (OpenStreetMap, as in `fits/diyanet_takdir.py`),
from which IGGÖ's 25 km are measured (`reachKm` 25, `mappingTier` 0), and its fitted point is its E6 morning and
evening point: 0.1–4.2 km from the centre, Eisenstadt's 14.5 km east-north-east (47.9077 N 16.69417 E). At the city
centres the tables are 83.6–85.9 % exact (Eisenstadt 24–40 %). The second tier, Diyanet's own Austrian entries (190 on
2026-10-11, no reach limit), is not in the local data: until it is collected, a place beyond 25 km of every capital
keeps `diyanet_eu` at its own point.

**Data** (test-data-local, read at run time, never copied): `research-2026-10-11/AT/iggo2026.csv` (derislam.at, nine
cities, 1 April–31 December 2026, 275 days each) and `iggo_locate_api_2026-10-11.txt` (the site's nearest-place API,
notes only).

**Held out** (month split: each city's point fitted on the odd months scores the even ones, and the reverse;
common.compute; Fajr and Isha outside May–August only), exact / within 1 min:

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|---|
| All nine | 97.4 / 99.9 % | 97.9 / 100 % | 98.2 / 100 % | 97.9 / 100 % | 98.0 / 100 % | 97.4 / 100 % | 2 min |
| Per city | 95.4–99.3 % | 97.1–99.3 % | 96.7–99.6 % | 97.1–99.3 % | 97.1–99.3 % | 94.7–99.3 % | |

- Zone records leave out Fajr and Isha from May to August (the takdir constants were fitted on them), so their
  `daysPerMonth` show the gap; no record can pass 9.5's coverage (no January–March days, no summer Fajr or Isha).

**Open.**
1. **Engine**: Diyanet's takdir (H6, `DiyanetTakdir.kt`) should be the V above, built like IRN's summer Isha (H7): a
   straight line in clock time from the last plain day to the solstice value (about the k(φ)·D depression on 21 June)
   and on to the first plain day after. Fajr's season is symmetric in day numbers as IRN's is (the last plain day
   before and the first after sum to 341 at five cities, 342 at four), and Isha's season ends with Fajr's. What sets
   its first day is not settled from IGGÖ alone: on the last plain day the plain time is 128.8–130.7 min before sunrise
   (Fajr) and 116.7–118.7 min after sunset (Isha), on the first plain day after 131.9–134.8 and 113.3–114.6, so no one
   duration fits every city. Diyanet's own tables at more latitudes (Bad Lippspringe, Nordic and German cities) would
   settle the rule; until then 9.2's ±10 for estimated times does not hold at Klagenfurt (11 min).
2. The second tier (Diyanet's 190 Austrian entries, their town points) for the asset, and the 2027 tables, to hold out a
   year and confirm the points.
