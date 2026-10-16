#### Mauritania (`mr_islamic_affairs`)

`fits/mr_islamic_affairs/fit.py` (standard library; about 20 s, 35 s with the validity-guard side check) fits the
Ministry of Islamic Affairs and Original Education's Ramadan imsakiyas and writes `official-zones/mr_islamic_affairs.json`.

**Method.** The current rule, in force since the 1444/2023 imsakiya, is applied all year (Q6):

| Time | Rule |
|---|---|
| Fajr | 19.5° |
| Sunrise | `Fixed(−1.066°)` (E2); the 12 interior capitals add a zone offset of − 1.5 min (E7: floor − 1) |
| Dhuhr | transit |
| Asr | factor 1, on the apparent sun (E13; 1010 hPa, 10 °C) |
| Maghrib | sunset at `Fixed(−1.051°)` (E2) + 2 min: the ministry's own rule (its notice of 2024-03-10; the 2023 and 2024 tables print the sunset and Maghrib = sunset + 2 on all 60 rows) |
| Isha | 17.5° |
| Rounding | nearest, all six (E1) |
| Sun | MEEUS (E14); VSOP87 ties it on Dhuhr (450 and 450 of 450 windows) |

The tables are a perpetual imsakiya since 2023: 2023, 2024 and 2025 agree on all 30 of Nouakchott's shared
month-days, and 12 of the 13 Imsak times Al-Akhbar relayed for 2026-03-04 equal it. The fit reads every row at the
reference year 2023 (E5): all six times give 2,684 of 2,700 at 2023, against 2,663 at 2019, 2,633 at 2015 and
1,815 at 2020.

What the windows show, on the 450 rows of the current rule:
- **Asr is on the apparent sun.** With Dhuhr, one longitude per capital covers 899 of 900 windows on the apparent sun
  and 890 on the geometric one. At the fitted points Asr is exact on 450 of 450 rows against 421.
- **Both horizons are lower than standard, and Maghrib is sunset + 2.** Nouakchott's printed sunrise and sunset carry
  no offset, and its sunrises give −1.0697° to −1.0632° (89 of 90; also −1.0624° to −1.0603°). Every capital's
  Maghrib, at the stated sunset + 2, gives −1.0510° to −1.0501° (445 of 450). The interior capitals' sunrise is
  − 1.5 min from there: 355 of 360 windows.
- The plan's representation (8.2, 8.7) is standard horizons with Maghrib + 3 and sunrise − 1 and − 2.5. With its
  points refitted, Maghrib is exact on 426 rows against 445, and sunrise on 435 against 444. Held out, as the app
  shows it, Maghrib is 79.6 % against 91.1 %.
- Fajr: 450 of 450 at 19.4997–19.5003°. Isha: 447 of 450 at 17.5000–17.5016°.
- Imsak = Fajr − 5 on all 510 rows. The app shows no Imsak row (A14).

**Older rules (E9), past-only fixtures** (9.3). Nouakchott's 2021 and 2022 tables were computed for their own years:
- **2021**: 19.5° and 17.5°, standard horizons, Maghrib = sunset + 2.
- **2022**: Fajr 19.0° and Isha 17.0°, otherwise the same. Its windows: 30 of 30 at 18.997–19.016° and at
  16.988–16.998°. Tig's relay of 2022-04-09 gives the 19.0° Fajr in all 13 capitals (13 of 13).

The engine's E9 changes offsets and angles but not a horizon. So the proposed profile's own fields are the 2021
rule, with the standard horizon written as sunrise + 1 and Maghrib + 1 on the fixed horizons: 30 of 30 windows hold
at 0.92–0.94 and 0.89–0.98 min in 2021, and at 0.81–0.88 and 0.92–0.96 min in 2022. It then has two revisions:
- 2022-04-02, the first row of the 2022 table: 19.0° and 17.0°.
- 2022-05-02, the day after its last row (Syria's convention, E9): 19.5° and 17.5°, sunrise + 0, Maghrib + 2.

Both tables also sit 0.021° and 0.025° east of the zone's computing point. At that point the profile reproduces
2021 on 24–28 of 30 days per time and 2022 on 25–29, all within 1 minute. An E9 able to change the horizon would do
no better (24–28 and 25–28), so no engine change is proposed.

**Zones and mapping.** There are 13 zones, one per capital. `id` is the town's slug, and `nameAr` the capital as its
page heads it («إمساكية مدينة كيفه» gives «كيفه»; «نوا ذيبو» is written «نواذيبو»). `point` is the GeoNames town and
`namedPlaces` holds it with its GeoNames id. `admin1` holds GeoNames codes: Nouakchott 13, 14 and 15 (the three
Nouakchott wilayas), Néma 01, Aioun 02, Kiffa 03, Kaédi 04, Aleg 05, Rosso 06, Atar 07, Nouadhibou 08, Tidjikja 09,
Sélibaby 10, Zouérat 11, Akjoujt 12. The mapping is `NEAREST` with `byAdmin1` (8.8): the wilaya's capital, otherwise
the nearest capital, then the validity guard.
- **Points.** Each capital keeps its town's latitude, since Ramadan's equinox days hardly fix one. Its longitude is
  the middle of the best stretch, or the town's where the town is in it. Kiffa and Kaédi stay at the town. The 11
  others are 0.2–2.1 km away, stored as E6 morning and evening points.
- **Offsets.** The interior zones carry `SUNRISE: -1.5`.

**Data** (test-data-local, read at run time, never copied):
- `research-2026-10-08/MR/mr/c2025/p04.csv` … `p16.csv`: the 2025 imsakiya, 13 capitals, 2025-03-01 to 03-30. The
  page order gives the capital, and the headings come from `MR/mrv/py/r2025.json`.
- `research-2026-10-08/MR/mr/nkc2021.csv` … `nkc2024.csv`: Nouakchott 2021–2024.
- Side checks: `MR/mrv/py/akh.py`, read as text and never run, and `official/mr/tig2022_d8.csv`. The latter was copied
  on 2026-10-15 from the Q6 session's scratchpad and carries its own MANIFEST.
- Town points: GeoNames cities1000, read 2026-10-15, in `CAPITALS`.

**Held-out scores.** Nouakchott is split by month (the March and April month-days of 2023–2025), the interior
capitals by date (odd and even). Fold A fits on Nouakchott's March and the odd dates; fold B the reverse. Every
stage is refitted on each fold. Scored with `common.compute`:

| Exact, 450 rows | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| As the app shows it, the actual year (FitStats) | 58.7 % | 61.1 % | 85.1 % | 88.0 % | 91.1 % | 86.7 % |
| At the reference year 2023: the table itself | 98.4 % | 97.6 % | 98.4 % | 99.6 % | 98.2 % | 98.9 % |

Every held-out time is within 1 minute. The app's lower scores are the perpetual table's (A8): its 2024 and 2025
reprints repeat 2023's minutes, while the app computes each year's own sun. The folds agree on the rules. Their
fitted values are sunrise −1.065° and −1.055°, sunset −1.047° and −1.048°, and interior shift −1.5 and −1.54.

**Tier.** B (9.5): every held-out time is within 1 minute. No zone has held-out days outside March and April.

**Quirk days.** None.

**Validity guard** (side check, 2026, by wilaya; GeoNames points, Aïn Ben Tili and Chami at the plan's approximate
points). Each figure is the worst-day sunrise beyond the table's own margin:
- Aïn Ben Tili: + 18.6 min on Zouérat's table, so its own point, as the plan expects.
- Ouad Naga: + 3.9 on Rosso's, kept, but only just.
- The places the plan leaves near the threshold all exceed it, so they would take their own point: Boutilimit
  (+ 6.4, Rosso's), Bir Moghrein (+ 8.9), Tichitt (+ 7.9), Bassikounou (+ 6.6), Ouadane (+ 6.5) and Chami (+ 6.0). The
  worst day adds the latitude's share to the longitude's: up to about 2 min at these distances, in June for a place
  north of its capital and in December for one south of it. The "about 4–6 min" of 8.8 are not worst days.

**Open questions.**
- Whether the 1448 imsakiya (about 2027-02-08) is again the 2023 reprint. Also any year-round or summer table, and any
  rule for places far from their capital (Q6, 12).
- The physical origin of the −1.066° and −1.051° horizons, 13–14′ below standard.
- The interior's − 1.5 (floor − 1) against Nouakchott's nearest rounding: two hands or two programs.
- The 2021 and 2022 tables were computed about 2.5 km east of the 2023 point. Their angles fit there; at the zone's
  point they are only fixtures.
