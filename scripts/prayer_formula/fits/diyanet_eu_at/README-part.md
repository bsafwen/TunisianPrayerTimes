### Austria: IGGÖ's city tables, Diyanet's Austrian tables and `diyanet_eu` (`diyanet_eu_at`)

Checked and fitted by `fits/diyanet_eu_at/fit.py` (tier 0 on 2026-10-15, tier 1 on 2026-10-16; about 1 min); asset
`official-zones/diyanet_eu_at.json` (method `diyanet_eu`, country `AT`, mapping `NEAREST`, 200 zones), 6.5's two tiers:
IGGÖ's nine state capitals (tier 0, within 25 km) and Diyanet's 191 Austrian entries (tier 1, the nearest).

**Does `diyanet_eu` hold in Austria?** Outside Diyanet's summer takdir, yes: as `EuropeanPresets.DIYANET_EU` stands
(18° / 16°, sunrise − 7, Dhuhr + 5, Asr + 4, Maghrib + 7, the sun once a day at 0 h UT, NEAREST), computed at each
table's fitted point, IGGÖ's April–December 2026 tables are reproduced on 97.4–98.2 % of days per time held out, and
Diyanet's own 2027 tables on 97.7–98.4 %, every time within 1 min but the takdir's first day at the northernmost places
(IGGÖ's Vienna and St. Pölten on 30 April 2026, Diyanet's Zwettl, Hollabrunn, Mistelbach and Berg bei Rohrbach on 27
April 2027: Fajr 2–3 min, Diyanet's season starting a day before the engine's). **In the takdir season it did not**
with the former k·D takdir: from May to early August, Fajr and Isha were about 23 % and 31 % exact, up to 11 min off at
IGGÖ's cities and 16 min at Diyanet's northern entries. Since 2026-10-16 H6 is Diyanet's own criteria
(`fits/diyanet_takdir`, which held these 190 tables out): 96.8 % and 97.9 % exact on the takdir days and 98.0 % and
98.2 % on the whole of 2027, every time within 1 min.

**IGGÖ's tables are Diyanet's.** On their 31 common days (2026-10-16 to 11-15) each of IGGÖ's nine tables equals a
Diyanet entry on all 186 times: BREGENZ, EISENSTADT, GRAZ, INNSBRUCK, KLAGENFURT, SALZBURG, SANKT POLTEN, VIYANA (Wien)
and, for Linz, LEONDING (Diyanet's LINZ entry is computed 10 km east of IGGÖ's Linz table). The fitted points agree
within 0.2–0.5 km.

**What the takdir is.** In every table the summer Fajr and Isha are straight lines in clock time: from the last day the
plain 18° Fajr (16° Isha) is printed to 21 June, then back to the first plain day, each printed minute within 0.6–1.5 min
of those two lines (IGGÖ, nine cities, 2026) and within 1.3–2.2 min (up to 3.3 and 4.9 min north of 48.5° N) over
Diyanet's 190 Austrian tables of 2027 (Sankt Veit an der Gölsen left out, below). Over Diyanet's tables, by latitude
(days of 2027; the line in latitude φ, largest residual):

| | 46.5–47° N | 47–47.5° | 47.5–48° | 48–48.5° | 48.5–49° | line |
|---|---|---|---|---|---|---|
| Fajr: last plain day | 129 (9 May) | 126 | 123 | 120 | 116 (26 April) | 418.7 − 6.19 φ (2.1 days) |
| Fajr: first plain day after | 212 (31 July) | 215 | 219 | 222 | 227 (15 August) | −122.5 + 7.15 φ (2.6) |
| Fajr: 21 June, share of the midnight depression | 0.735 | 0.721 | 0.701 | 0.677 | 0.629 | 2.979 − 0.0478 φ (0.033) |
| Fajr: plain Fajr to sunrise on the last plain day | 130.8 min | 130.3 | 130.0 | 129.0 | 126.7 | |
| Isha: last plain day | 136 (16 May) | 134 | 130 | 128 | 122 (2 May) | 455.1 − 6.80 φ (2.6) |
| Isha: first plain day after | 214 (2 August) | 216 | 219 | 222 | 226 (14 August) | −67.4 + 6.00 φ (2.0) |
| Isha: 21 June, share of the midnight depression | 0.684 | 0.670 | 0.652 | 0.629 | 0.585 | 2.796 − 0.0450 φ (0.030) |
| Isha: plain sunset to Isha on the last plain day | 118.3 min | 118.4 | 117.8 | 117.0 | 114.5 | |
| entries | 26 | 73 | 36 | 45 | 10 | |

Fajr's season is symmetric in day numbers (last plain day + first plain day after = 341–343); Isha's starts about a week
later and ends with Fajr's (sum 348–350). At the solstice the k(φ) lines of `DiyanetTakdir.kt` hold within about 0.02
between 46.6° and 48.3° N but not north of it (clamped at 48.27°: 0.671 for Fajr where the tables give 0.629 at
48.75° N), and the engine applies k·D on every night, a curve where Diyanet draws a V, so it is 4–16 min off in mid-May
and in July (Fajr and Isha on the takdir days: 22.9 % and 30.9 % exact over Diyanet's tables).

**Tier 0: IGGÖ's nine capitals.** Ids `bregenz`, `eisenstadt`, `graz`, `innsbruck`, `klagenfurt`, `linz`, `salzburg`,
`st-poelten`, `wien`, Arabic names transliterated; each `point` is the city centre (OpenStreetMap, as in the former
IGGÖ fit `fits/diyanet_takdir.py`, in git history), from which IGGÖ's 25 km are measured (`reachKm` 25, `mappingTier` 0), and its fitted point is
its E6 morning and evening point: 0.1–4.2 km from the centre, Eisenstadt's 14.5 km east-north-east (47.9077 N
16.69417 E, Diyanet's EISENSTADT point too). At the city centres the tables are 83.6–85.9 % exact (Eisenstadt 24–40 %).

**Tier 1: Diyanet's 191 Austrian entries** (namazvakitleri.diyanet.gov.tr: GetRegList with CountryId 35 gives them at
once, state «AVUSTURYA» 657; 191 on 2026-10-16, 190 on 2026-10-11). Ids `diyanet-<IlceID>`, `mappingTier` 1, no reach
limit: a place more than 25 km from every capital takes the nearest, as IGGÖ's site does.

- Each point is fitted as Turkey's are (`fits/diyanet_tr/points.py`): a 1/400 degree grid, on all of 2027, Fajr and Isha
  from 15 April to 31 August left out. The zone's `point` is the entry's town (GeoNames, matched by name within 30 km of
  the fitted point; 162 entries), from which the nearest entry is measured, with the fitted point as its E6 morning and
  evening point; fitted point to town: median 0.8 km (DORNBIRN 21 km, EISENSTADT 14.7 km, as Diyanet computes them). The
  29 entries whose hamlet GeoNames' cities500 lacks (BAIERHAM, GRIESBACHWINKL, TIMMLS, ZURS …) keep their fitted point.
  AINRING lies in Bavaria.
- `nameAr`: the catalogue's Arabic name where the town is a catalogue row (24), else D3's label, the native name in an
  LTR isolate then «النمسا» (167; «Sankt Martin» twice, by Traun and by Villach). Towns more than 25 km from every
  capital are the zones' named places (101; 47 matched to a catalogue row by scripts/places/match_named_places.py's rule,
  run on this asset only); a town inside a capital's 25 km names none, so the capital's catalogue row stays tier 0's.
- **Held out** (a month-split of 2027, refits from the year's point, and the 31 days of 2026; common.compute; Fajr and
  Isha outside May–August and outside the engine's estimated days):

| Held out | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|---|
| Tier 0, all nine (month-split of April–December 2026) | 97.4 / 99.9 % | 97.9 / 100 % | 98.2 / 100 % | 97.9 / 100 % | 98.0 / 100 % | 97.4 / 100 % | 2 min |
| Tier 1, 191 entries | 97.9 / 100 % | 98.2 / 100 % | 98.4 / 100 % | 98.4 / 100 % | 98.3 / 100 % | 97.7 / 100 % | 3 min |
| Tier 1, per entry min / median exact | 94.9 / 98.1 % | 96.0 / 98.2 % | 95.5 / 98.5 % | 95.7 / 98.5 % | 96.2 / 98.2 % | 94.5 / 97.8 % | |

  109 of the 191 tier-1 records reach 97 % exact on every time scored; no zone record can pass 9.5's coverage (no
  summer Fajr or Isha; tier 0 also no January–March).
- **A summer-time slip:** SANKT VEIT AN DER GOLSEN (33393) prints its 2027 summer-time days (28 March to 30 October,
  217 days) one hour early, as if Austria had no summer time (its 2026 days are right). Those days are its quirk days
  (8.3): left out of the fit and the scores (`quirkDays` 217). The app, on Austria's clock, gives the intended times.
- IGGÖ's locate API on 2026-10-11 named the nearest tier-1 entry, by fitted point and by town alike, at 9 of the 10
  points read (Wels, Eferding, Pöchlarn, Tulln, Zell am See, Lienz → Heiligenblut, Leibnitz …). The exception, a query
  labelled Melk that returned POCHLARN while Diyanet lists MELK, is unexplained (its coordinates were not noted).
- **January to March from a mosque** (`fits/diyanet_eu_at/mosque_year.py` with `fits/mosque_years.py`, 2026-10-17,
  owner's idea; plan 9.5). One Vienna mosque of the owner's dataset prints IGGÖ's Wien table on all 275 days, every
  time to the minute (another prints 96.8 % of them, every time within 1 min; none of the other 29 Austrian mosques
  follows a capital's table). Its January to March 2026 (90 days) are a held-out body of `wien`'s record (`source`
  "mosque_follower"): Fajr 100 %, sunrise 100 %, Dhuhr 97.8 %, Asr 98.9 %, Maghrib 98.9 %, Isha 97.8 % exact, every
  time within 1 min. `wien` now has every month on all times but Fajr and Isha from May to August, which 9.2 leaves to
  the takdir, so it still cannot pass 9.5's coverage.

**Data** (test-data-local, read at run time, never copied): `research-2026-10-11/AT/iggo2026.csv` (derislam.at, nine
cities, 1 April–31 December 2026, 275 days each), `iggo_locate_api_2026-10-11.txt` (the site's nearest-place API, notes
only), and `official/at/` from `fits/diyanet_tr/collect.py` (2026-10-16): `lists/country_35.json` and 191
`pages/<IlceID>.html.gz` (31 days from 2026-10-16 and all of 2027 each; 192 requests).

**Open.**
1. **Engine** (done 2026-10-16): Diyanet's takdir was to be the V above. The V is the Prayer Times Congress's third of
   the sharia night with its 20-minute transitions, read from Diyanet's tables across Europe (`fits/diyanet_takdir`,
   README H6); the k lines and their clamp at 48.27° are gone, and 9.2's ±10 for estimated times holds (every time
   within 1 min).
2. Next year's tables (2028 from about September 2027) to hold out another year, and IGGÖ's 2027 tables to confirm tier 0.
