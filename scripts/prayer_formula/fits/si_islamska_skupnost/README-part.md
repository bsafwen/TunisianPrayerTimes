### Slovenia: the Islamic Community in Slovenia (`si_islamska_skupnost`)

Fitted by `fits/si_islamska_skupnost/fit.py` (2026-10-16, a few seconds) on the feed's months collected so far
(October–December 2026); asset `official-zones/si_islamska_skupnost.json`, 6 zones, mapping `LISTED_PLACES` (the
nearest of the six places whatever the distance), country `SI`.

**The table.** vaktija.si's feed serves Ljubljana's current month and the next two, and one fixed offset per time for
five places (Celje −2, −2, −3, −4, −4, −4; Jesenice +3, +3, +2, +1, +1, +1; Koper +2, +2, +3, +4, +4, +4; Maribor −4,
−4, −5, −5, −5, −5; Novo mesto −2 on all six; Fajr to Isha). The research copy of 2026-10-11 and the collector's of
2026-10-16 hold the same 92 days. **Its Isha is not a twilight at Ljubljana**: it is the IZ BiH's Sarajevo Isha before
the 2024-12-30 change (vaktija.ba, the Takvim's Sarajevo, no town difference) + 18 min on 91 of 92 days (+16 once), and
that column is a 17° angle at Sarajevo (16.8–17.3° all year). The other five times are computed, not copied (their gap
to Sarajevo's columns spreads over 2–5 minutes within a month).

**Method** (stages of 9.4 by windows from the preset at Ljubljana's centre, for each sun model of E14; an alternative
must cover more than 0.5 % and at least 2 more windows; one evaluation for all six until six months are collected):

- The **TEXTBOOK** sun (402 windows over the six times, MEEUS 354, VSOP87 349, ALMANAC 377; its Dhuhr alone 86 of 92
  against MEEUS's 77), at the event instant (249 of 552; 10 h UT 244, `KACST` 242, 0 h UT 188).
- The point 45.76608°N 14.41841°E (E6 morning and evening point), 32 km south-south-west of the centre: sunrise, Maghrib
  and Fajr agree on 45.6–45.8°N (212 windows against 166 at the centre) and both month halves move south (45.596 and
  45.731°N). Isha is left out of this stage (it is Sarajevo's).
- Fajr **19.0°** + 1, nearest (65 of 92); sunrise − 5 (66); Dhuhr + 2 (86); Asr factor 1, − 1, **up** (69); Maghrib
  + 5 (73); Isha **18.0°** − 2, nearest (43: the best a Ljubljana angle does).
- One evaluation per time overfits on three months: held out (MEEUS) sunrise falls to 91.3 % and Isha to 62.0 % within
  1, against 98.9 and 79.3 % with one evaluation. The halves' own choices differ (November alone picks MEEUS at 10 h UT,
  October and December TEXTBOOK at the event instant).
- The summer form is not refitted: the feed has served no summer month (8.8); the monthly collector reads April to
  August from April 2027. Its dates stay 24 April–23 May. In the engine's terms the Ramadan 2020 sheet's printed
  «sunrise − 105» and «Maghrib + 90» become `fajrBeforeSunrise` 111 (105 + Fajr's +1 − sunrise's −5) and
  `ishaAfterMaghrib` 92 (90 − Isha's −2), if the sheet's figures are printed differences, as read.

**Zones.** `ljubljana`, «ليوبليانا», at Ljubljana's centre (GeoNames 3196359) with the fitted E6 point; Celje, Jesenice,
Koper, Maribor and `novo-mesto` at their catalogue points, each `baseZoneId` `ljubljana` with the feed's offsets.

**Held out** (month-split: fitted on November, scored on October and December, and the reverse; common.compute),
exact / within 1 min:

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|---|
| Ljubljana, and the six places | 52.2 / 100 % | 42.4 / 98.9 % | 77.2 / 100 % | 59.8 / 100 % | 75.0 / 100 % | 34.8 / 76.1 % | 5 min |

- In-sample: 70.7 / 71.7 / 93.5 / 75.0 / 79.3 / 46.7 % exact; within 1 min 98.9–100 %, Isha 80.4 % (5 min at worst).
- The preset before this fit: Fajr 16.3 % exact and 82.6 % within 1, Isha 20.7 % and 63.0 %, 7 min at worst.
- Isha's misses are all early in the season (1–12 October 2–5 min): a 17° twilight at 43.87°N shortens and lengthens
  unlike an 18° one at 46°N, so no Ljubljana angle follows it, and in summer the gap grows to about 20 min (an engine
  change is proposed: Isha by angle at Sarajevo's point + 18 min meets 91 of 92 days within 1, 49 exact, with the
  TEXTBOOK sun once a day at 0 h UT).
- Three months: no record passes 9.5's coverage.

**Open.** 1. The engine change for Isha (above). 2. The feed's summer months for the summer form. 3. Why the computed
columns sit at about 45.8°N (an approximate solar formula, a point of the Community's own) is not known; a year of the
feed (the collector runs monthly) settles the point and the per-time evaluations.
