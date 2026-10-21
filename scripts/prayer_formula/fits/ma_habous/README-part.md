#### Morocco (`ma_habous`)

`fits/ma_habous/fit.py` (about 90 s) writes `official-zones/ma_habous.json`.

**Method.** Fajr at 19° rounded down. Isha at 17°, nearest. Dhuhr = transit + 5. Asr uses factor 1 on the apparent sun at 1010 hPa and 10 °C (E13). Sunrise − 1 and Maghrib + 2, nearest, on each locality's own horizons (E2 `PerZone`). The MEEUS sun is taken at each event's instant. Every value equals the profile the app already has, and the fit confirms each one:

- Asr on the geometric sun gives 712 of 780 days held out, against 764 on the apparent sun (other values fixed).
- On the archived 2024 tables, Fajr 19.1° nearest gives 79 of 88 days against 86 for 19.0° rounded down, and Isha 16.9° rounded up gives 78 against 86.
- On the same tables, Maghrib + 1 gives 77 against 83. With no fixed minutes, sunrise gives 72 and Maghrib 70.
- Dhuhr + 5 nearest is the only variant whose fitted longitudes centre on the towns (median − 1.1 km). + 5 rounded down or up, + 4 and + 6 move every point by 11 to 25 km.
- VSOP87 ties with MEEUS: 4,551 against 4,541 of 4,680 on the month split, 506 against 508 on the archive.

**Fit.** Windows, not least squares. Each published minute bounds the event's instant to a half-open minute, inverted exactly with the sun at that instant:

- Dhuhr gives longitude windows.
- Fajr, Isha and Asr give latitude windows.
- Sunrise and Maghrib give horizon-altitude windows.

The point is the GeoNames town moved into the interval most windows cover, at least a quarter of its width from either end. The horizons take that interval's centre. The stages choose Asr, then Fajr, then Isha, then Fajr again, each by in-sample exact count with every zone refitted (`common.compute`). Dhuhr + 5, sunrise − 1 and Maghrib + 2 are not re-chosen: within one month they trade exactly with longitude and horizon. Held out by a month split: fitted on September and scored on October, then the reverse, with the stages rerun on each half.

**Zones.** 191 localities: ids 1–169 and 301–322, with Ceuta (24) and Melilla (40) carrying `"country": "ES"`. Mapping is `NEAREST` with no reach limit (8.8). Each zone's `namedPlaces` holds its town, with the GeoNames id where the town is a populated place.

- **All 191 fitted** on their own table (refit 2026-10-17), each with its own held-out record: the collector's first run (9.3, 2026-10-16) saved every locality's table of Jumada I 1448 (2026-10-13 to 11-11), beside Rabi II for the first 26. The fitted points lie a median 1.6 km from the GeoNames towns. One is far: 155 «لمسيد» (Lemsid, Boujdour province), whose table's Dhuhr and sun put it at 28.04°N 10.82°W, 342 km north-east of the town (26.54°N 13.84°W), so Habous computes it there. The app reproduces the table, and the validity guard (6.5) gives a user at Lemsid itself their own coordinates, the table being about 12 min off there.
- Before the refit, 26 were fitted and 145 sat at their GeoNames town with the height law.
- **20 read once** on habous.gov.ma on 2026-10-15, before the collector ran, because their GeoNames match was missing or doubtful (now fitted like the others): ids 30, 94, 102, 109, 114, 116, 131, 140, 301, 303, 306–311, 316–318 and 322. Their point and horizons are constants in `LOCATED`; they have no record, and no times are kept. These reads corrected four earlier guesses:
  - 30 «بوسكور» is in the eastern Rif (34.71°N 3.61°W), not Bouskoura near Casablanca.
  - 307 «تفنتان» is near Tighedouine, not Tifnit.
  - 322 «إيمين ثلاث» is in the central High Atlas (31.60°N 6.53°W), not Imi n'Tlit.
  - 306 «سيدي غانم» is Sidi Rhanem in the Haha.
- **The height law** (exact coordinates only): the profile keeps the law of the first 26 zones, sunrise −(1.194 + 0.0244·√h), rms 0.117°, and sunset −(1.190 + 0.0205·√h), rms 0.098°, h the GeoNames dem. The 191 zones give −(1.141 + 0.0253·√h), rms 0.149°, and −(1.161 + 0.0210·√h), rms 0.120°, for the profile's next update.

Every zone carries `elevationM` (the town's dem). Only the exact-coordinate fallback and the validity guard read it.

**Data**, all local and never copied:

- `official/ma/` (the collector, 2026-10-16): all 191 localities' Jumada I 1448 tables, 2026-10-13 to 11-11, 30 days each.
- `research-2026-10-08/MA/skeptic_ma/v{1,14,18,24,31,33,58,66,70,73,81,99,100,103,104,106,111,117,128,133,136,138,156,165}.html` and `MA/ma/x_{40,160}.html`: Rabi II 1448, 2026-09-13 to 10-12, 30 days each.
- The 191 Arabic names, from the pages' locality list.
- Embedded constants from the GeoNames MA, EH and cities1000 extracts of 2026-10-11 (CC BY 4.0).
- Optional, with `--archive DIR`: the archived 2024 tables of Rabat and Laayoune (`rabat_202406.txt`, `rabat_202412.txt`, `laayoune_202406.txt`; web.archive.org captures of Q5). They are not in `test-data-local/` yet.

**Held-out scores** (exact share per time; within 1 minute: 100 % everywhere):

| Held out | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Max |
|---|---|---|---|---|---|---|---|
| Month split, 191 zones (in the asset) | 97.0 % | 96.1 % | 96.7 % | 97.5 % | 95.4 % | 97.4 % | 1 min |
| 2024 archive, 88 days at Rabat and Laayoune (`--archive`) | 95.5 % | 96.6 % | 97.7 % | 96.6 % | 94.3 % | 88.6 % | 1 min |
| A locality with no table (leave one out: GeoNames point, height law) | 90.9 % | 52.0 % | 91.7 % | 91.1 % | 58.4 % | 91.7 % | 15 min (Lemsid; otherwise a few) |

Per zone, the month split gives 70–100 % per time, every time within 1 min; the lowest are Fquih Ben Salah's Maghrib (70 %), Imilchil's Maghrib (72 %), Souk El Arbaa's Fajr (73 %) and Al Hoceima's sunrise (77 %). No zone passes tier A, since there are one or two months (9.5).

**A whole year from the mosques** (`fits/ma_habous/mosque_year.py` with `fits/mosque_years.py`, 2026-10-17, owner's idea). Of the owner's dataset's 1,516 Moroccan mosques, 195 reproduce a locality's Habous table on every collected day (all of 30 to 53 days, to the minute; Habous's GMT week left out), at 58 localities. Their 2026 calendars, on the days Habous's own tables do not cover, are a held-out year of their own (`source` "mosque_follower", the follower with the most official days per locality, scored at the zone as the asset holds it): Fajr 95.4 %, sunrise 95.0 %, Dhuhr 97.4 %, Asr 96.2 %, Maghrib 95.1 %, Isha 95.5 % exact, every time within 1 min, as the 2024 archive shows on Habous's own tables. Days printed on a wrong clock are quirk days, left out (2,148 follower-days): the calendars print UTC−1 from 15 February to 13 March 2026, an hour behind the legal UTC+0 of Ramadan's suspension, a few days two hours off, and the change days' mornings on the other clock. No locality passes tier A with that year (97 % exact on every time), so the localities stay in tier B; the 58 localities' records hold the year, which the explainer shows as «تقويم مسجد يتّبع الجدول الرسمي». The owner's dataset is supporting evidence, never the truth: it is read in place, never copied, and the official days stay the fit's.

**Quirk days.** The rows of 2026-09-13 to 09-19 are printed in GMT while the legal clock was still +01 (8.3). They are read in GMT (6.7): Rabat's Dhuhr then sits − 0.3 min from transit + 5, against − 60.3 min read at + 01. So they score as ordinary days, and `quirkDays` is 0.

**Open issues.**

- Only Sep–Nov 2026 is local. The monthly collector (9.3) adds a month for all 191 localities every Hijri month; `fit.py` reads any dated Habous page under `research-*/MA/` or `official/ma/`, so a refit takes them in (about 12 min). The three archived 2024 tables are in `research-2026-10-11/MA/wayback/` (with their SHA-256), so the records hold that held-out year.
- The horizon depression drifts with the season. On the 2024 tables it is about 0.01–0.02° shallower in June and a little deeper in December than in Sep–Oct, which a fixed altitude cannot follow. Expect 92–96 % exact on sunrise and Maghrib away from the equinoxes.
- At exact coordinates the profile uses the height law, `PerZone(SqrtHeight(1.194, 0.0244))` at sunrise and `PerZone(SqrtHeight(1.190, 0.0205))` at sunset (8.2): within 2 min, 55 % and 62 % exact, where `PerZone(Standard)` was off by up to 8 min, `Refraction()` 3 and `StandardWithDip` 4.
- Habous lists near-duplicates: 94 and 303 are both Tahla, 1.8 km apart; 309 and 310 are near Agoudal, 0.7 km apart; 308 and 311 are near Oukaïmeden, 3.5 km apart.
- Maghrib + 2 is confirmed only on coastal tables (Q5). Inland official tables from another season would settle it.
- 93 (Bourd) sits on its commune's point, unverified. The 8 unnamed localities take the dem of the nearest GeoNames place.
