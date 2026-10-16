### Bulgaria: the Chief Mufti's Office (`bg_grand_mufti`)

Fitted by `fits/bg_grand_mufti/fit.py` (2026-10-16, a few seconds) on the months collected so far (October 2026);
asset `official-zones/bg_grand_mufti.json`, 48 zones, mapping `NEAREST` (no reach limit), country `BG`. Refit as
months arrive: the collector (`scripts/collectors/bg.py`) saves every city's month.

**The tables are one table.** grandmufti.bg's «Времена за намаз» shows one city's month at a time (48 cities, Айтос to
Ямбол). In October 2026 every city's month is Sofia's shifted by one whole number of minutes on all six times and all
31 days (48 of 48 cities, 186 of 186 times each): Sofia, Montana and Blagoevgrad 0, Gotse Delchev and Yakoruda −1, …,
Varna and Balchik −18, Kavarna −19. The shift follows the longitude (about −4 × (λ − 23.44°), within 1.4 min; it is
read from the tables, not computed). So the office publishes one table and a shift per city, as KMSH does for Albania
(8.8), not 48 computed points: the profile is fitted on Sofia's table, and each city is a zone that adds its shift to
Sofia's unrounded times (`baseZoneId` `sofia`, published offsets), at its town centre (the place catalogue's GeoNames
row; Byala, Kaolinovo, Nikopol and Sitovo from GeoNames, Byala the Varna province's town, whose −17 is Dobrich's).

**Method** (stages of 9.4 by windows from `diyanet_tr`'s parameters at Sofia's centre; an alternative must cover more
than 0.5 % and at least 2 more windows; with one month, stage 0's evaluation serves all six times):

- The sun once a day at 0 h UT (E4 `DAILY_0H_UT`) on all six (stage 0: 54 of 186 windows, the event instant 28,
  10 h UT 24, `KACST` 24), MEEUS.
- Fajr **18.2°**, rounded **down** (30 of 31; 18° with −2 min, up, 29).
- Sunrise − 8, **up** (25); Dhuhr + 5, nearest (30); Asr factor 1, + 4, **up** (27); Maghrib + 8, nearest (28).
- Isha **17.2°**, rounded **down** (26; 17° + 1, nearest, 25).
- The point: Sofia's latitude, 23.31002°E from Dhuhr (1.2 km west of the centre), as the E6 morning and evening point.
  October's windows lean north (42.85°N: 114 against 107 at the centre, a flat ridge from 42.6 to 42.95°N), but the
  halves disagree (the odd days keep 42.698°N, the even days 42.908°N), so the latitude stays (it leaves the centre only
  where both halves move it to the same side).
- `DiyanetTakdir`, `DIYANET_CLAMP` and the 5-hour minimum stay: they never act at 41–44°N.

**Held out** (alternate days: the stages refitted on the odd days score the even ones, and the reverse; common.compute,
every city through its zone and the base zone), exact / within 1 min:

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|---|
| Sofia, and all 48 cities | 93.5 / 100 % | 90.3 / 100 % | 87.1 / 100 % | 77.4 / 100 % | 93.5 / 100 % | 87.1 / 100 % | 1 min |

- In-sample, Sofia: 96.8 / 80.6 / 96.8 / 87.1 / 90.3 / 83.9 % exact, all within 1 min.
- The preset before this fit (`diyanet_tr`'s parameters at Sofia's centre): Fajr 0 % exact and 51.6 % within 1, Maghrib
  3.2 % exact, 2 min at worst. `diyanet_tr` at each city's own point (no table): 45–97 % within 1, 5 min at worst.
- One month only: no record can pass 9.5's coverage (seven held-out days in every month). The roundings (up and down
  per time) and the angles rest on October; refit when the year's months are in.

**Open.** 1. Refit on every month collected (the latitude, the evaluation per time from six months on, the roundings).
2. The shift of a place between two cities is the nearest city's, whatever the distance (plan 8.8); the validity guard
checks it.
