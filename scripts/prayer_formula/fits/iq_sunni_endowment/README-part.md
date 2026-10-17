#### Iraq (`iq_sunni_endowment`)

`fits/iq_sunni_endowment/fit.py` (standard library, about 1 to 2 minutes) fits the Sunni Endowment's Baghdad table and
writes `official-zones/iq_sunni_endowment.json`. The table is perpetual, computed for one year: the fit reads each row at
that reference year (E5), and every score comes from `common.compute` at the row's actual date, as the app shows it. The
app shows the table as printed (A8, revised by the owner on 2026-10-17): the method's `referenceYear` 2021 computes
every date as its month and day of 2021.

**Method** (fitted 2026-10-15; changes from 8.2 in bold):

| Time | Rule |
|---|---|
| Fajr | 18°, nearest |
| Sunrise | **the zone's horizon (E2 `PerZone`, below), nearest, + 0** |
| Dhuhr | transit + 5, nearest |
| Asr | factor 1 on the apparent sun (E13) at the zone's pressure and temperature, **its noon shadow at the published Dhuhr instant (E4 `SHADOW_AT_DHUHR`)**, + 3, nearest |
| Maghrib | **sunset on the zone's horizon + 2, nearest** (not + 1 rounded up) |
| Isha | 17°, nearest |
| Sun | VSOP87 at each event's instant; reference year 2021 |

Stages, by windows, at Accurate Times' Baghdad entry (33°20'19"N 44°23'38"E, 46 m, 1008 hPa, 25 °C, 60 %):

1. Dhuhr alone: VSOP87 covers 365 of 365 windows at 2021 (MEEUS 362, TEXTBOOK 306); 2025 and 2017 360.
2. All six times with free shifts and angles: 2021 2,175 of 2,190, then 2025 2,138 and 2017 2,131.
3. Sunrise and Maghrib, at their whole or half minute (of 730): the Hohenkerk & Sinclair law 718 (sunrise nearest + 0,
   Maghrib nearest + 2), the plain standard horizon 660, the engine's `Refraction` (Bennett, at the entry's 46 m,
   1008 hPa, 25 °C) 580 (its best reading is sunrise up + 0 and Maghrib up + 1, the plan's), `StandardWithDip` 583.
   The table's horizon is a constant −1.0142° (a fixed altitude fits 365 and 362 days; the engine's `Refraction`, whose
   semi-diameter follows the sun's distance, at best 363 and 356). The law: −(16′ + R + D), with D = 1.76′·√h the dip
   of the sea horizon seen through the air and R Hohenkerk & Sinclair's refraction (sla_REFRO, written out in `fit.py`)
   at the observed zenith distance 90° + D, at the entry's height, temperature, pressure, 60 % humidity and 0.0065 K/m
   lapse rate, λ = 0.574 µm. It gives −1.0145° at Baghdad, and on the mosque calendars of Mosul (222 m) and Kirkuk
   (350 m), evidence only, the horizons the windows imply there to within 0.002° (side check below).
4. Fajr: 365 of 365 at 17.9997–18.0001°; Isha 365 at 17.0000–17.0002° (17.0 itself: 364 in the fast model, 365
   with the reference engine).
5. Asr, four forms at the whole minute + 3 (of 365): the apparent sun with the noon shadow at the Dhuhr instant
   (transit + 5 min, the true altitude there, refracted) 364, its window + 3.005 to + 3.006 (365 with the reference
   engine); with the shadow at the transit, the apparent sun needs + 3.044 and the geometric + 3.179; the geometric sun
   at the Dhuhr instant + 3.105. Both month halves pick the Dhuhr instant on the apparent sun at + 3 (184 of 184,
   180 of 181). No time takes an E7 fraction.
6. The point: on a ±0.010° × ±0.0008° grid the entry reproduces 2,175 of 2,190 minutes and the best cell 2,177: the
   entry stays the computing point (no E6 points).

**Zones and mapping.** 28 zones, Accurate Times' Iraqi entries outside the Kurdistan Region (Dahuk, Irbil and
Sulaymaniyah left out), `NEAREST` with no reach limit and the validity guard (8.8). `id` from the entry's name
(`mosul` for its «Moosel», `tal_afar`), `nameAr` the city's Arabic name; `point` the entry (the point the Endowment
computes at, 8.5), its `namedPlaces` the same point; `elevationM` the entry's; `pressureHpa` and `temperatureC` only for
Baghdad (1008, 25: the others are standard); `sunriseAltitudeDeg` = `sunsetAltitudeDeg` = the law at the entry's own
inputs (Basra −0.8891° at 3 m to Rutba −1.7494° at 620 m); no offsets. At exact coordinates the `PerZone` fallback is
`SqrtHeight(0.7987, 0.03853)`, the law at 1010 hPa and 10 °C fitted from 0 to 1,500 m (within 0.029°). The asset's
top-level `exactAdmin1` lists the Kurdistan Region's governorates, GeoNames admin1 05 (Sulaymaniyah), 08 (Duhok),
11 (Erbil) and 19 (Halabja): a place there gets no zone, the method at its own point (`EXACT`, 6.5, Q8). `fit.py`
adds it after `common.write_asset`.

**Data** (read at run time, never copied): `test-data-local/research-2026-10-08/IQ/iq/dws_2026.json` (the dws.gov.iq
API's 365 days of 2026), `C:/Accurate Times/loc.txt` (the 31 Iraqi entries), `research-2026-10-11/HIJRI/
icop_ghc_1428_1450.csv` (Ramadan days only) and, for the side check, `mosque-dataset/mosques.db`.

**Held-out scores** (FitStats: the 2026 table by month-split, each half's values scored on the other half, computed for
2026 as the app shows it, at the reference year; all within 1 minute, worst 1 minute):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| Exact, computed for 2026 as printed (FitStats) | 100 % | 98.4 % | 99.7 % | 100 % | 98.4 % | 100 % |
| Within 1 minute | 100 % | 100 % | 100 % | 100 % | 100 % | 100 % |
| Exact for 2026's own sun (before A8's revision) | 82.2 % | 86.6 % | 94.2 % | 91.2 % | 79.2 % | 81.6 % |

The shipped values on every row: at 2021 (as printed) 365, 359, 364, 365, 359, 365 of 365; for 2026's own sun 300,
316, 344, 333, 289, 298. Asr with the shadow at the transit and an E7 + 0.044, the previous values, gives 359 at 2021.
Tier A (9.5): met by Baghdad's zone since 2026-10-17, when the app began to show the table as printed (A8): every time
98.4–100 % exact held out over all twelve months, all within 1 minute. The other 27 cities have no official data and no
fit record, so they show no «مطابقة للجدول الرسمي».

**Side check: mosque calendars** (5.6, evidence only, never fitted): the most common calendar near Mosul's entry
(14 of 16 mosques) gives, computed for 2021, Fajr 365, sunrise 361, Dhuhr 365, Asr 365, Maghrib 359, Isha 365 of 365;
near Kirkuk's (4 of 11) 360, 361, 361, 359, 357, 363. So the cities' printed tables follow the same rule at Accurate
Times' entries, the horizon law and the Dhuhr-instant shadow included.

**Quirk days.** None.

**Open issues.** (1) The 2027 table (held out; the collector asks for it from January 2027) confirms the reference
year 2021 if it repeats 2026 by month and day.
(2) Sunrise alone wants a horizon 0.005° shallower than the law (363 days at −1.0099° against 359): a second of time, not
chased. (3) No printed city table outside Baghdad; the 27 other zones rest on the law and Accurate Times' entries,
supported by the Mosul and Kirkuk calendars. (4) The engine's `Refraction` model (Bennett at the dipped horizon, a
semi-diameter that follows the sun's distance) misses the law by 0.06–0.07° at these heights; the per-zone altitudes
avoid it here, and the `SqrtHeight` fallback approximates it at exact coordinates.
