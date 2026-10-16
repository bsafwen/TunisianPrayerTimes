### Réunion: the Centre Islamique de la Réunion (`re_cir`)

Fitted by `fits/re_cir/fit.py` (2026-10-15, a few seconds); asset `official-zones/re_cir.json`, five zones.

**The table.** The CIR prints Fajr for four regions (Nord, Est, Sud, Ouest) and iftar for Nord/Sud/Est, Ouest and Le
Tampon, from 19 February to 10 May; its 2024 and 2025 tables and Baytoul Hayaate's 2026 copy are one table by month
and day (a perpetual table). Ouest's iftar is always Nord/Sud/Est + 1 and Le Tampon's + 2. The iftar columns, already
printed in 2023, fit a leap year's sun (2016, 2020 or 2024: 79–80 of 80 windows, against 69–73 for other years); the
four Fajr columns, new in 2024, the year after a leap year (2017, 2021, 2025), so the fit reads each family at its own
reference year (E5). It prints no Dhuhr, Asr or Isha.

**Method** (the plan's `re_cir`, unchanged): Fajr and Isha 18° (the Bras-Fusil display, Q34), Maghrib = sunset + 2,
UP on the five prayers and DOWN on sunrise, Asr factor asked, the CIR's elevation shifts (E16). The columns cannot
choose another minute or rounding: each is the same column at a point 0.125° of longitude away.

**Zones** (`NAMED_REGIONS`; GeoNames points, read 2026-10-15):

| id | nameAr | point | Fajr and sunrise at | Offsets (min) | Named localities |
|---|---|---|---|---|---|
| `nord` | «شمال الجزيرة» | Saint-Denis | Saint-Denis | Maghrib + 0.07 | Saint-Denis, Sainte-Marie, Sainte-Suzanne, La Montagne |
| `est` | «شرق الجزيرة» | Saint-Benoît | Saint-Benoît | Fajr − 0.23, Maghrib + 0.07 | Saint-Benoît, Saint-André, Bras-Panon, Sainte-Anne, Cambuston |
| `sud` | «جنوب الجزيرة» | Saint-Pierre | Saint-Pierre | Fajr + 0.66, Maghrib + 0.07 | Saint-Leu, Saint-Louis, Saint-Pierre, Saint-Joseph |
| `ouest` | «غرب الجزيرة» | Saint-Paul | La Possession | Fajr + 1.05, Maghrib + 1.07 | La Possession, Le Port, Saint-Paul, Saint-Gilles les Bains, La Saline |
| `sud_tampon` | «جنوب الجزيرة» | Le Tampon | Saint-Pierre | Fajr + 0.66, Maghrib + 0.07; `elevationShiftFloor` 2 | Le Tampon |

Dhuhr, Asr, Maghrib and Isha of every zone are computed at Saint-Denis (E6 `eveningPoint`), as the one Nord/Sud/Est
iftar column; Fajr and sunrise at the region's own locality, so sunrise stays a real town's. The fractional offsets
(E7) are fitted by windows; a locality other than the region's main town is taken only where it covers more than
5 % more windows (Ouest: La Possession, 80 against 75 at Saint-Paul). Ouest's + 1.05 is the plan's "Fajr 18° + 1".
A point fitted freely to one Fajr column drifts up to 25 km out to sea (81 days around the equinox barely bind its
latitude) and takes sunrise with it, so it is not used. Any other place takes the zone of its nearest named locality
(Cilaos and the Plaine des Cafres: Le Tampon; Salazie: Saint-André; Sainte-Rose: Sainte-Anne), then its own E16 shift.

**Data** (test-data-local/research-2026-10-11/FR, read at run time, never copied): `cir_ramadan1445.csv`,
`cir_chawwal1445.csv`, `cir_ramadan1446.csv`, `bh_ramadan1447.csv` (the perpetual table), `cir_ramadan1444.csv` (2023:
its iftar rows; its two Fajr columns, Nord-Est and Sud-Ouest, an earlier version, are not scored),
`brasfusil_masjidbox_2026-10.csv` (the Bras-Fusil display, a check at Saint-Benoît's own point). `cir_all.csv`
repeats the four CIR files. The sehri columns (imsak, A14) are not used.

**Held out** (month-split by month-day: fitted on March and May, scored on February and April, and the reverse; every
printed row at its own date, 2023–2026, as the app computes it, A8): exact / within 1 min, every time within 1:

| Zone | Fajr (122 rows) | Maghrib (152 rows) |
|---|---|---|
| `nord` | 95.1 / 100 % | 71.1 / 100 % |
| `est` | 91.8 / 100 % | 71.1 / 100 % |
| `sud`, `sud_tampon` | 93.4 / 100 % | 71.1 / 100 % |
| `ouest` | 89.3 / 100 % | 71.1 / 100 % |

- By printed year (final zones): the iftar is exact on 98 % of the 2024 rows, the year whose sun matches its
  reference, and on 42–74 % of the 2023, 2025 and 2026 rows; Fajr on 99 % of the 2025 rows and 87–92 % of the others.
- Bras-Fusil (12–17 October 2026, at Saint-Benoît, exact coordinates): Fajr 6 of 6 exact, Isha 3 of 6 exact (the
  display rounds to the nearest minute), all within 1.
- FitStats carry Fajr and Maghrib only: the CIR prints no sunrise, Dhuhr, Asr or Isha, so those shares are 0.

Le Tampon's + 2 is the zone's `elevationShiftFloor`: plan 7.6 counts the column as the first 2 min of the place's
elevation shift, so Maghrib and Isha move max(2, E16) (a Le Tampon place below 500 m + 2, Cilaos at about 1,200 m
+ 5), in the engine and the reference alike.

**Open.**
- A CIR month between May and February, and a timetable with Dhuhr, Asr and Isha (Q34), would test the localities
  out of the equinox season and the unpublished times.
