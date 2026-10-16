### Norway: Islamic Council Norway's tables (`irn`)

Fitted by `fits/irn/fit.py` (2026-10-16, about 3 min); asset `official-zones/irn.json`, 358 zones, mapping
`ADMIN_AREA`. Data fetched by `fits/irn/fetch.py`. `fits/irn/modes.py` emulates the two sun evaluations the tables use
and the engine lacks, `fits/irn/north.py` the northern rules the tables follow, and `fits/irn/names_ar.py` writes the
Arabic names; none of them changes the engine or the reference.

**The tables.** bonnetid.info, IRN's national prayer-time project, is a single-page app reading its own Supabase REST
endpoint (`https://disp-api.bonnetider.eu/rest/v1/`, with the public key its script carries): `location_t` lists 359
entries, the 357 municipalities (2024 numbers), Svalbard (`NO2100`) and Strömstad in Sweden (`SE1486`, left out), each
with a published point; `prayertime` holds one row per entry and day of 2026, one method per entry (3 south of
63.56°N, 2 from it; no other year is served). Besides the prayer columns (`fajr`, `fajr_endtime`, `duhr`, `shadow_1x`,
`shadow_2x`, `maghrib`, `isha`) each row prints IRN's astronomical columns (`istiwa_noon`, `shuruq_sunrise`,
`ghrub_sunset`, `fajr_sadiq` at 16°, `shafaqal_ahmar_end_redlight` at 15°, `shafaqal_abyadh_end_whitelight` at 18°),
the twilight ones left empty on the days the prayer column is not on them.

**What IRN computes** (stage 1, every tenth southern entry at its published point, 26 tables): every time is rounded
down (truncated). Sunrise, sunset, the transit and Asr take the sun once a day at **12:00 Norwegian time** (11:00 UT in
winter, 10:00 UT in summer): 98.8 %, 99.5 %, 99.3 % and 99.0–99.2 % of minutes exact, against 95.4 %, 95.8 %, 99.0 %
and 96.2–96.8 % with the engine's nearest mode, `DAILY_10H_UT`. The 16° and 15° columns take the declination at the
event and the equation of time at 12:00 Norwegian time: 99.0 % and 99.1 %, against 93.3 % and 92.7 % at the event
instant (`EVENT_INSTANT`). MEEUS beats VSOP87 on every column (VSOP87 97.3–97.6 %).

**Offsets** (stage 2, every entry and day): the sunrise column is the sunrise − 2 and Maghrib the sunset + 5 on all
93,075 southern days; Dhuhr is the transit + 10 from 21 March to 20 September and + 5 otherwise, the same dates at every
entry. Asr is published with both factors (factor 1 by default, as the preset).

**Points** (stage 3): the published points reproduce 525,101 of the astronomical columns' minutes; a point fitted by
windows (longitude from the transit, then latitude from sunrise and sunset) reproduces more than 0.5 % more at 269
entries (528,151 in all) and becomes their E6 morning and evening point, 0.05–0.23 km from the published one; the
`point`, which the mapping reads, stays the published one.

**South of 63.56°N** (255 entries; README H7). Each table's switch days are the days its Isha is off its 15° column
and its Fajr off its 16° column.

- *Isha.* The engine's rule (the first day from 32 whose 15° Isha is at or after 20:00 UTC, mirrored to 341 − first)
  hits 5 of 255 tables. The tables follow a rule of their own on every one of the 255: the day **after** the first day
  (32–172) whose 15° Isha is absent or at or after 20:00 UTC, to the day **before** the last such day (172–365), each
  side on its own (first + last is 340 at 45 entries, 341 at 130 and 342 at 80).
- *Fajr.* The engine's rule (the first day whose 16° Fajr is at least 153 min before sunrise, mirrored) hits 103 of
  255. No single threshold fits every table: the 16° Fajr's distance to sunrise reaches 154.46 min on the day before
  some switch and is down to 152.70 on the switch day of another (the best threshold, 153.35 min, fits 200 springs),
  and in autumn Fajr returns to 16° at 160.9–166.6 min. So the days are stored.
- *Stored.* `switchDays` ISHA at the 250 entries the engine's rule misses, FAJR at the 155 it misses (with either set
  of sun evaluations), both ends included, common-year day numbers; the others keep the rule's days, which equal the
  table's.
- *21 June.* The held Fajr keeps the spring clock (day first − 1) through 21 June and takes the autumn clock
  (last + 1) from 22 June; the engine switches on 21 June, which moves 21 June's Fajr by 3–4 min at six far-southern
  entries (Sokndal and the Kristiansand area), where sunrise − 60 does not cap it.
- Given these days, H7's Isha line and held Fajr reproduce the tables: the proposal's southern scores below.

**From 63.56°N** (103 entries; README H8). The tables follow rules the engine's H8 does not have yet (`north.py`,
proposed below):

- Sunrise and sunset never more than 8 h from the transit T (Hitra's Maghrib sits on the cap for over a week before
  its summer ramp).
- Fajr no earlier than T − 9 h and Isha no later than T + 9 h: H7's caps at solar midnight ∓ 3 h with the midnight
  12 h from the day's own transit (the night's lowest point, the engine's, sits about 8 s away in spring, which moves
  one capped minute in seven).
- **Summer**, every entry but Svalbard: sunrise and sunset ramp over 7 days from the real values of day a (the last
  real day) to Mecca's of day a + 7, keep Mecca's until b − 7, and ramp back to the real values of day b; Fajr and
  Isha ramp from their capped values of a + 7 to Mecca's of a + 14, keep Mecca's until b − 15 and ramp back to their
  capped values of b − 8. Each ramp is a straight line in UTC time of day between its end days, like H7's Isha line.
  a and b differ irregularly between neighbouring entries (Gamvik 100 and Berlevåg 106, 39 km apart), so they are
  read per entry: a from 100 (Gamvik) to 123 (Hitra, Indre Fosen), b from 220 (Inderøy) to 244 (Måsøy).
- **Winter**, each half on its own (January belongs to the previous winter): 48 entries ramp all four times over 30
  days into and out of Mecca's day (the autumn's last real day 299–309, the spring's first real day 35–44; the two
  halves are a day apart from a mirror at 23 entries); 43 hold sunset no earlier than T + 2 h and sunrise no later than
  T − 2 h over a period that starts on the first autumn day the real sunset falls below T + 2 h and ends, in January,
  on its mirror about 20.5 December (a polar-night day counts as sunrise and sunset at T); 11 need neither.
  Vestvågøy's table follows the floor in November–December 2026 and the Mecca winter in January 2026.
- **Svalbard**: Mecca's day all year.
- **Mecca's day**: its sunrise, sunset and 18° Fajr and Isha from its transit (21.4225 N, 39.8262 E), with IRN's own
  sun taken at 12:00 Norwegian time, added to T; sunrise − 2 and Maghrib + 5 as everywhere.
- **Asr**: min(max(the shadow Asr, (T + sunset) / 2), sunset − 1 h), the sunset being Maghrib's base after all of the
  above, on every day (365 of 365 at Hitra, Vefsn, Bodø, Tromsø, Alta, Vardø, Nordkapp and Svalbard); south of 63.56°N
  the plain shadow Asr.
- Stored per entry, read only by the proposed H8: `switchDays` MAGHRIB {first: a + 1, last: b − 1} (Svalbard {1,
  365}: Mecca all year, no ramps), SUNRISE {first, last} the Mecca winter (its first ramp day in autumn, its last in
  spring; 0 where that half has none) and DHUHR {first, last} the floor days (0 likewise). The current engine reads
  `switchDays` south of 63.56°N only, so these change nothing today.

**Zones.** 358, ids the municipality numbers (`0301`, …, Svalbard `2100`), `admin2` that number (Svalbard also `2111`,
the catalogue's Longyearbyen; the catalogue's other Norwegian rows all carry a number IRN lists, Jan Mayen's
Olonkinbyen `2211` aside, which takes the nearest entry), the published point as `point` and named place, the fitted
point as E6 where stage 3 keeps it; Arabic names from the overrides of `names_ar.py`, else the catalogue's label of a
town of that name, else a transliteration of the Norwegian name (the Norwegian one where IRN writes a Sami name first).

**Data** (test-data-local, read at run time, never copied): `official/no/irn/location_t.json` and
`official/no/irn/2026/<location_iso>.json` (358 tables, 58 MB, with their MANIFEST.tsv rows).

**Scores** (common.compute for the current engine, held out by month split: E18's Dhuhr points refitted on the odd
months score the even ones, and the reverse; the proposed rules, emulated, fit nothing from minutes and read each
entry's days from its own table, so their scores are in-sample in that respect), exact / within 1 min, quirk days
apart:

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|---|
| Current engine, south (255) | 93.7 / 100 % | 95.9 / 100 % | 94.6 / 97.1 % | 97.2 / 100 % | 96.1 / 100 % | 93.4 / 100 % | 5 min |
| Current engine, 63.56–66.33° (36) | 76.4 / 89.8 % | 80.8 / 88.6 % | 94.6 / 97.1 % | 41.4 / 43.7 % | 86.0 / 93.3 % | 77.1 / 89.8 % | 98 min |
| Current engine, from 66.33° (66) | 58.2 / 73.4 % | 69.6 / 76.5 % | 94.7 / 97.1 % | 42.9 / 43.5 % | 69.9 / 76.5 % | 59.3 / 73.2 % | 214 min |
| Current engine, Svalbard | 74.0 / 80.5 % | 70.7 / 80.5 % | 95.1 / 97.0 % | 57.8 / 60.3 % | 71.2 / 80.5 % | 76.7 / 80.5 % | 168 min |
| Proposed rules, south | 99.8 / 100 % | 99.7 / 100 % | 99.9 / 100 % | 99.7 / 100 % | 99.8 / 100 % | 99.5 / 100 % | 1 min |
| Proposed rules, 63.56–66.33° | 98.9 / 100 % | 99.6 / 100 % | 99.8 / 100 % | 99.5 / 100 % | 99.6 / 100 % | 99.0 / 100 % | 2 min |
| Proposed rules, from 66.33° | 98.7 / 100 % | 99.5 / 100 % | 99.9 / 100 % | 99.6 / 100 % | 99.6 / 100 % | 98.9 / 100 % | 1 min |
| Proposed rules, Svalbard | 95.6 / 100 % | 100 / 100 % | 100 / 100 % | 99.7 / 100 % | 99.7 / 100 % | 95.1 / 100 % | 1 min |

- The current engine's south is held back by its evaluations (sunrise, Maghrib and Asr on the sun at 10 h UT, Fajr and
  Isha at the event instant: 93–97 % exact) and by E18's Dhuhr step, which can only ramp over the 10 days between two
  points (about 9 days off by 1–4 min at each switch); its north by H8's sudden seasons, its 63.56–66.33° Maghrib
  floor and its polar Asr (Tromsø up to 214 min off in the ramps).
- **Tier** (9.5): with the current engine no zone passes (Dhuhr's ramp days alone break "every time within 1 min"),
  so the asset ships its records with «مستخلصة من جداول» everywhere; with the proposed rules 354 of 358 zones pass
  (below it: Svalbard, Fajr 95.6 % and Isha 95.1 % exact; Vestnes, Isha 96.4 %; Hasvik, Fajr 96.2 %; each within
  1 min on every day; and Røyrvik, whose sunrise of 31 December comes 2 min early), and `records.py` writes them
  now that the engine has the rules (below).

**Quirk days** (5, counted apart): one day each in September at five eastern Finnmark entries (Sør-Varanger 8 Sep,
Vadsø and Vardø 6 Sep, Båtsfjord 11 Sep, Berlevåg 13 Sep), whose tables print Mecca's day between two real ones.

**In the engine** (2026-10-16, Kotlin and the reference together, README S4, E18, H7 and H8; `modes.py` and
`north.py` emulated them for the fit): the evaluations `DAILY_LOCAL_NOON` (sunrise, Dhuhr, Asr, Maghrib) and
`EVENT_DECLINATION` (Fajr, Isha); the dated Dhuhr + 5 from 21 March to 20 September in place of E18's points; H7's
southern Isha days (the day after the first late day, the day before the last, no mirror) and the held Fajr's spring
clock through day 172; H8's northern rules from the zones' MAGHRIB, SUNRISE and DHUHR switch days, with the
caps, ramps, floor, Svalbard's Mecca year and the Asr rule. `EuropeanPresets.IRN` takes them (16° and 15°, sunrise − 2,
Dhuhr + 5, Maghrib + 5, every time rounded down, MEEUS).

**Records** (`fits/irn/records.py`, the reference engine on every day of the 358 tables, about half an hour on 8
processes): 354 of 358 zones at tier A (Svalbard, Vestnes, Hasvik and Røyrvik below it, as above); all zones together
Fajr 99.5 %, sunrise 99.6 %, Dhuhr 99.9 %, Asr 99.6 %, Maghrib 99.7 % and Isha 99.3 % exact, every time within 1 min
but Røyrvik's sunrise of 31 December (2 min); the 5 quirk days apart. H7's own rule gives the days of all 250 stored
ISHA pairs, which `records.py` removed; the FAJR pairs stay at the 155 entries no rule fits. The records are in-sample
for the switch days, read from the same tables (Open 1).

**Open.**
1. IRN's 2027 tables, when served, are the held-out year: whether the per-entry days (south and north) repeat by
   calendar date, as `switchDays` assumes, and whether the January half follows the autumn of the year before.
2. ADMIN_AREA reads `place_admin2`: the catalogue has rows in 95 of the 357 municipalities, so a GPS place labelled by
   the nearest catalogue row (6.8) often carries a neighbouring municipality's number; M5 needs each place's own
   municipality (polygons), or the nearest entry's point where it has none.
3. The Fajr switch rule: no simple rule found; a better one would shrink the 155 stored FAJR pairs.
