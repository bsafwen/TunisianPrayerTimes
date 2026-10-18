# INM Prayer-Time Formula

A reconstruction of how Tunisia's **Institut National de la Météorologie (INM)**, the source behind [meteo.tn](https://www.meteo.tn), computes the daily prayer times for every delegation. It reproduces INM's published times **to the minute** and runs fully offline, so the app can produce any year without scraping.

It is validated on 2020–2026. One-off quirks in INM's future tables, like Zeriba's 2026 sunrise (see [INM data quirks](#inm-data-quirks)), cannot be anticipated.

| File | Purpose |
|------|---------|
| `inm_prayer_times.py` | Reference implementation: `--verify` against the meteo.tn CSVs in `docs/csv`, and CSV output for any delegation, year or month |
| `../../data/prayer-formula/delegation_params.json` | Per-delegation inputs: INM's coordinates and elevation, plus quirks of INM's own tables. Shared with the Android app, which bundles it as an asset. |
| `multiplatform/shared/.../InmPrayerFormula.kt` | The Kotlin port the Android app computes its prayer times with |
| `tv-app/app/src/main/assets/dashboard/formula.js` | The JavaScript port the TV's phone dashboard explains and previews the times with (tab «حساب المواقيت») |
| `check_dashboard_formula.js` | Node check of `formula.js`: every delegation and day of 2026 in `docs/csv`, plus the custom values pinned in the shared tests |
| `vsop87_terms.py`, `vsop87d_earth_terms.tsv` | The truncated VSOP87D Earth series of the [shared engine](#shared-engine-places-outside-tunisia)'s VSOP87 sun, and the script that makes it and its Kotlin arrays |
| `prayer_engine_reference.py` | The independent Python reference of the [shared engine](#shared-engine-places-outside-tunisia), written from this README, with its unittest `test_prayer_engine_reference.py` |
| `astronomy_fixtures.py` | Fetches the shared engine's astronomy fixtures from JPL Horizons and the US Naval Observatory into `test-data/engine-reference/` |

## Validation

Per-delegation parameters were fitted on **2026 only**. Model variants were compared on 2026 plus a 2025 sample, so **2020–2024 are fully held out**. meteo.tn serves 2020–2026. On 2026-09-28, the probed years 2010, 2015, 2018, 2019, 2027 and 2028 returned `data: null`.

| Data | Times checked | Exact match |
|------|---------------|-------------|
| 2026: every delegation and day (`docs/csv`, scraped March 2026; 258 delegations × 365 days × 6 prayers) | 565,020 | **100 %** |
| 2020: every day of every delegation, fetched from meteo.tn in October 2026 | 557,958 | **100 %** |
| 2021: every day of every delegation, fetched from meteo.tn in October 2026 | 565,020 | **100 %** |
| 2022: every day of every delegation, fetched from meteo.tn in October 2026 | 562,390 | **100 %** |
| 2023: every day of every delegation, fetched from meteo.tn in October 2026 | 552,273 | **100 %** |
| 2024: every day of every delegation, fetched from meteo.tn in October 2026 | 566,202 | **100 %** |
| 2025: every day of every delegation, fetched from meteo.tn in September 2026 | 561,490 | **100 %** |
| 2026: every day of every delegation, fetched from meteo.tn in September 2026 | 565,020 | 96.1 %: the rest is INM's later Kairouan / Sidi Bouzid edit (see [INM data quirks](#inm-data-quirks)) |

"Exact" means the same `HH:MM` for Fajr, Sunrise (Shuruk), Dhuhr, Asr, Maghrib and Isha. The meteo.tn API also returns solar noon (`pm`) and sunset (`coucher`); both match the formula exactly as well.

## Inputs

For each delegation (`data/prayer-formula/delegation_params.json`):

| Field | Meaning |
|-------|---------|
| `lat`, `lng` | INM's published coordinates in degrees (up to 3 decimals, as returned by the meteo.tn API). North latitude and east longitude are positive. |
| `elevation_m` | Elevation in **whole metres**, as used by INM. |
| `overrides` | Optional per-year exceptions that reproduce quirks in INM's own tables. The only supported key is `sunrise_elevation_m` (see [section 6](#6-fajr-sunrise-maghrib-isha)). |
| `name`, `gouvernorat` | Informational; not used by the formula. |

Arram (495) has no entry: INM publishes no data for it in any year. The app bundle keeps an empty CSV set for it, which the validation excludes.

Constants: time zone **UTC+1** with no daylight saving time; Earth radius **R = 6,378,137 m** (WGS 84).

## The Formula

All angles are in degrees: `sin`, `cos` and `tan` take degrees, and `asin`, `acos` and `atan` return degrees (this includes `dip`, `a_asr` and `δ`). Convert with π/180 in code.

### 1. Julian day

`JD₀` is the Julian day at **0h UT** of the civil date, for year `Y`, month `M` and day `D`:

```
if M ≤ 2:  Y = Y − 1,  M = M + 12
A = floor(Y / 100),  B = 2 − A + floor(A / 4)
JD₀ = floor(365.25·(Y + 4716)) + floor(30.6001·(M + 1)) + D + B − 1524.5
```

Equivalently, `JD₀ = 2440587.5 + days since 1970-01-01`; for example 2026-01-01 → 2461041.5. The Julian day is always taken from the civil date, never from the device clock or time zone.

### 2. Sun position: Meeus, *Astronomical Formulae for Calculators* (1900 epoch)

For a Julian day `JD` (a **UT** Julian day; no ΔT or ephemeris-time correction is applied, even though Meeus defines T in ephemeris time, and adding ΔT breaks exactness):

```
T  = (JD − 2415020.0) / 36525                                   Julian centuries since 1900
L  = 279.69668 + 36000.76892·T + 0.0003025·T²    (mod 360)       mean longitude
M  = 358.47583 + 35999.04975·T − 0.000150·T² − 0.0000033·T³      mean anomaly
e  = 0.01675104 − 0.0000418·T − 0.000000126·T²                   eccentricity
C  = (1.919460 − 0.004789·T − 0.000014·T²)·sin M
   + (0.020094 − 0.000100·T)·sin 2M + 0.000293·sin 3M            equation of centre
Ω  = 259.18 − 1934.142·T
λ  = L + C − 0.00569 − 0.00479·sin Ω                             apparent longitude
ε  = 23.452294 − 0.0130125·T − 0.00000164·T² + 0.000000503·T³
   + 0.00256·cos Ω                                               apparent obliquity
δ  = asin(sin ε · sin λ)                                         declination
y  = tan²(ε/2)
E  = y·sin 2L − 2e·sin M + 4e·y·sin M·cos 2L − ½y²·sin 4L − (5/4)e²·sin 2M   (radians)
EoT = 4 · E·(180/π)                                               equation of time, minutes
```

The obliquity used for both δ and EoT is the **apparent** one (with the `0.00256·cos Ω` term). Reducing angles modulo 360 or not makes no difference.

### 3. Hour angle

The time (in hours) between solar noon and the moment the sun is at altitude `a`, for latitude `φ` and declination `δ`:

```
H(a, φ, δ) = acos( (sin a − sin φ·sin δ) / (cos φ·cos δ) ) / 15
```

### 4. Dhuhr and Asr: sun at 0h UT

Take `δ₀, EoT₀` from the sun at `JD₀` (0h UT of the date, no refinement):

```
noon  = 12 − EoT₀/60 − lng/15 + 1                                  local hours
Dhuhr = noon + 7 min
Asr   = noon + H(a_asr, φ, δ₀),   a_asr = atan( 1 / (1 + tan|φ − δ₀|) )
```

The Asr shadow factor is **1** (object shadow = object height + its noon shadow). This is the Maliki, Shafi'i and Hanbali convention; only Hanafi uses 2.

### 5. Horizon dip from elevation (single precision)

```
dip = acos( float32( R / (R + h) ) )        h = elevation in metres, R = 6378137
```

The quotient `R / (R + h)` is rounded to **IEEE single precision** before the `acos`; everything else runs in double precision. INM's program presumably stores this ratio in a single-precision variable, such as a Fortran `REAL`. Rounding the double-precision quotient gives the same float32 value as doing the whole division in single precision, for every h from 0 to 3000 m and for 15.6 m.

It matters: near 1, float32 values are spaced 2⁻²⁴ apart, so `R / (R + h)` can only take discrete values. These correspond to elevation steps of R·2⁻²⁴ ≈ 0.38 m. The same formula in double precision misses about 550 of the 565,020 2026 times (Fajr, Sunrise, Maghrib and Isha only), even with the fitted elevations.

### 6. Fajr, Sunrise, Maghrib, Isha

Target altitudes:

| Event | Altitude `a` | Side |
|-------|--------------|------|
| Fajr | −(18 + dip) | morning (s = −1) |
| Sunrise | −(0.83 + dip_sunrise) | morning (s = −1) |
| Sunset | −(0.83 + dip) | evening (s = +1) |
| Isha | −(18 + dip) | evening (s = +1) |

`dip` uses `elevation_m`. `dip_sunrise` is the same, except that when `overrides[str(year)].sunrise_elevation_m` exists, that elevation is used for Sunrise only, through the same float32 dip formula.

For each event, start from the sun at 0h UT and re-evaluate it at the event's *apparent solar time*, taken as a UT hour of the date:

```
(δ, EoT) = sun(JD₀)
repeat 5 times:
    (δ, EoT) = sun( JD₀ + (12 + s·H(a, φ, δ)) / 24 )
time = 12 + s·H(a, φ, δ) − EoT/60 − lng/15 + 1                     local hours
```

The result has converged: any count of 2 or more passes gives the same minutes, while a single pass does not.

**Maghrib = sunset + 2 min.** Fajr, Sunrise and Isha have no offset.

### 7. Rounding

Convert every time to minutes after local midnight and round half up: `floor(minutes + 0.5)`.

## INM Data Quirks

These are properties of INM's published tables, not of the formula:

- **Zeriba (409), 2026 sunrise only.** INM's 2026 sunrises for Zeriba require an elevation between about 15.4 and 15.8 m, not its 156 m. This is consistent with 15.6 m, a slipped decimal point. Its Fajr, Maghrib and Isha in 2026, and its sunrises in 2020–2025, all use 156 m. It is stored as `"overrides": {"2026": {"sunrise_elevation_m": 15.6}}`, the only non-integer elevation.
- **Kairouan and Sidi Bouzid, live 2026 tables.** Between 2026-02-18 and 2026-09-29, INM rewrote its 2026 tables for 20 delegations: all 10 of Kairouan (521–529, 630) and 10 of Sidi Bouzid (504–508, 510–512, 628, 1522; not 509 or 513). Their Fajr, Chourouk, sunset and Isha are now computed with an elevation of 0 m, while Maghrib still uses the real elevation. As a result, Fajr and Chourouk are 2–3 min later and Isha 2 min earlier, and Maghrib falls 3–6 min after the published sunset instead of 2. INM's own Ramadan 1447 imsakia (2026-02-18), the scraped 2026 tables, all other years and the SRTM terrain model (66–476 m for these delegations) use the real elevations. The app therefore keeps the real elevations and deliberately differs from today's meteo.tn for those three prayers in these 20 delegations in 2026.
- **Missing data.** meteo.tn returns `{"data": null}` for some delegation-days:
  - Arram (495) in every year.
  - Frequent sunrise gaps in 2020 and 2023.
  - A few prayer-time gaps, such as Chrarda's and Tina's 2025 prayer times.
  - Bni Mhira's 2024 sunrises.
- **App coordinates differ from INM's** by more than 0.01° for 11 delegations in `android-app/app/src/main/assets/gouvernorats.json`: El Hamma (1024, 0.17°), Smar (472), Utique (567), Sened (609), Hichria (1522), Degueche (478), Ben Arous (622), Ariana (631), Matmata Nouvelle (1023), Mahdia (618) and Omrane Supérieur (392). Use the coordinates in `data/prayer-formula/delegation_params.json` to reproduce INM.

## meteo.tn API Fields

The two per-day endpoints (`/horaire_gouvernorat/{date}/{gouvernorat}/{delegation}/` and `/lever_coucher_gouvernorat/...`) map to the formula as follows:

| Field | Meaning |
|-------|---------|
| `sobh` | Fajr |
| `lever` | Sunrise (Shuruk) |
| `dhohr` | Dhuhr |
| `aser` | Asr |
| `magreb` | Maghrib |
| `isha` | Isha |
| `pm` | Solar noon (Dhuhr − 7 min) |
| `coucher` | Sunset (Maghrib − 2 min) |
| `lat`, `lng` | The delegation's coordinates |

## Android App

The app computes prayer times on the device with `InmPrayerFormula` (in `multiplatform/shared`), instead of bundling meteo.tn's tables:

- `PrayerTimesRepository` computes any day for `SUPPORTED_YEARS` (2020–2100).
- `data/prayer-formula/delegation_params.json` is packaged as the asset `prayer-formula/delegation_params.json` by the `bundlePrayerFormulaParams` Gradle task.
- `InmPrayerFormulaTest` (`./gradlew :shared:javaTest`) checks the Kotlin port against every 2026 day in `docs/csv` and against the sampled 2020–2025 times in `test-data/inm-prayer-times/`.

## Custom Values

INM's values are the default everywhere, and the only ones the phone app uses. The mosque TV lets a mosque change a few of them (the settings file's `prayerTimes` section, or the dashboard's «حساب المواقيت»), as `PrayerFormulaSettings`, passed to `InmPrayerFormula.minutes` / `dayPrayerTimes` and `InmPrayerTimes.loadDayPrayerTimes`:

| Value | Official | Range | Replaces |
|-------|----------|-------|----------|
| `fajrAngle`, `ishaAngle` | 18 | 15–20, by 0.5 | the 18° twilight depression of Fajr and Isha |
| `asrShadow` | 1 | 1, or 2 (the Hanafi rule) | the `1 +` in `a_asr` |
| `dhuhrMinutes` | 7 | 0–15 | Dhuhr's minutes after solar noon |
| `maghribMinutes` | 2 | 0–10 | Maghrib's minutes after sunset |
| `elevation` | true | true / false | false: every dip is 0, the sunrise override included |
| `adjust` | 0 | −15 to +15 whole minutes | added to Fajr, Dhuhr, Asr, Maghrib or Isha (never the sunrise) |

With the official values the results are INM's, bit for bit. Tunis (615) on 2026-09-30, for example: official 04:47 06:13 12:16 15:31 18:07 19:31; with Fajr at 16°, Asr at two shadows, the elevation off and Isha +2: 04:58 06:14 12:16 16:22 18:06 19:32 (`PrayerFormulaSettingsTest`).

## Porting to Kotlin/Java

The single-precision step must be reproduced exactly:

```kotlin
const val R = 6378137.0                                    // metres; must be a Double
fun dip(elevationM: Double): Double {                     // Double: the Zeriba override is 15.6 m
    val ratio = (R / (R + elevationM)).toFloat().toDouble()   // IEEE round-to-nearest single precision
    return Math.toDegrees(Math.acos(ratio))
}
```

Everything else must stay in `Double`. An `Int` radius or elevation would turn the division into integer division.

## How It Was Established

Each part below was tested against the data. The iteration count and angle reductions are implementation choices that the data cannot distinguish (see above).

- **Coordinates.** The meteo.tn API returns each delegation's `lat`/`lng`. Using exactly these values leaves no per-date conflict in Dhuhr across the 258 delegations, so INM computes with them. They are identical in every year from 2020 to 2026.
- **Sun model.** Dhuhr depends only on longitude and the equation of time.
  - The NOAA/Meeus (2000-epoch) equations leave 54 whole-minute Dhuhr misses in 2026; their equation of time differs by only hundredths of a second.
  - A precise VSOP87 ephemeris is worse, and so is evaluating the sun at any time other than 0h UT.
  - The 1900-epoch *Astronomical Formulae for Calculators* equations give **zero** Dhuhr and Asr misses in every year.
- **Offsets.** Dhuhr = noon + 7 min and Maghrib = sunset + 2 min. The API's own `pm` (noon) and `coucher` (sunset) fields match the formula exactly, and shifting either offset by ±0.01 min causes about 950 misses each.
- **Elevation.** Sunrise and sunset shift symmetrically with terrain height, and Fajr and Isha use the same dip. The fitted elevations are realistic (Tunis 9 m, Béja 242 m, Kesra 1005 m). They match the public SRTM terrain model at INM's coordinates: median difference 1 m, 254 of 258 within 25 m, and 59 identical to SRTM 90 m, so INM very likely took them from SRTM. With SRTM elevations instead, about 97.8 % of all 2026 times would still match; the rest would be off by one minute, mostly in coastal cities.
- **Altitudes.** 0.83° (not 0.8333°, which gives 3,206 misses in 2026) for sunrise/sunset, and exactly 18° for twilight. Moving either by 5×10⁻⁶° breaks exactness.
- **Evaluation time.** Evaluating the sun at the event's apparent solar time beats UT event time (about 8,900 misses), local mean time and fixed hours.
- **Single-precision dip.** Solving each delegation's 2026 times backwards gives an exact window of admissible dips.
  - All 258 windows contain exactly one value of `acos(float32(R/(R+h)))` with integer `h`. By chance, that would happen with probability about 10⁻²⁶⁶.
  - In double precision only 81 of 258 do.
  - Any R from about 6,378,093 to 6,378,171 m fits, including WGS 84 and IAU 1976; 6,371,000 m does not.

## Shared engine (places outside Tunisia)

Tunisian delegations keep INM's formula above. Every other place is computed by the shared engine, `PrayerEngine` in `multiplatform/shared/src/commonMain/kotlin/com/tunisianprayertimes/engine/` (plan: `docs/prayer-times-beyond-tunisia-plan.md`, 6.2). This section writes out its math, with every coefficient, so that the independent Python reference `prayer_engine_reference.py` follows it without reading the Kotlin. The engine reuses INM's sun (section 2) and Julian day (section 1) without changing them.

Conventions:

- Angles are in degrees unless stated: `sin`, `cos` and `tan` take degrees, and `asin`, `acos` and `atan` return degrees. Latitude φ is positive north, longitude λ positive east.
- Instants are UTC, written as seconds since 1970-01-01T00:00Z. `JD(t) = 2440587.5 + t / 86400`.
- **Hours** of a cycle are hours after 0h UT of the cycle's UT date U (S1): instant = `(JD₀(U) − 2440587.5)·86400 + 3600·hours`, with `JD₀` from section 1.
- The output is a `PrayerDay`: for Fajr, sunrise, Dhuhr, Asr, Maghrib and Isha, an instant rounded to the whole UTC minute and a status: `COMPUTED`, `ESTIMATED` (with the rule that set it) or `ABSENT`.
- Dates are actual dates in 2020–2100 (`SUPPORTED_YEARS`).

### S1. The sun (E14)

Each method names a sun model. Every model returns, for a UT Julian day `JD`, the apparent declination δ, the equation of time `E` in minutes (apparent minus mean solar time, so that local apparent solar time = UT + λ/15 + E/60 hours) and the Earth–sun distance `r` in AU, which only the refraction horizon of S5 reads.

**MEEUS** (the default): δ and `E` (`EoT`) are INM's sun of section 2 at `JD`, with no ΔT. `r` is the Astronomical Almanac's: `g = 357.528 + 0.9856003·(JD − 2451545.0)`, `r = 1.00014 − 0.01671·cos g − 0.00014·cos 2g`.

**VSOP87**, as Accurate Times computes (plan 8.6):

```
y    = 2000 + (JD − 2451545.0) / 365.25
ΔT   = 62.92 + 0.32217·u + 0.005589·u²,                  u = y − 2000          if y < 2050   (seconds)
     = −20 + 32·w² − 0.5628·(2150 − y),                  w = (y − 1820) / 100  otherwise
JDE  = JD + ΔT / 86400
τ    = (JDE − 2451545.0) / 365250,   T = 10·τ
L, B, R = Σₖ τᵏ · Σ A·cos(B + C·τ)                        radians, radians, AU (vsop87d_earth_terms.tsv)
Θ = L + π (radians),  β = −B
L′ = Θ − (1.397·T + 0.00031·T²)°                          FK5
Θ = Θ − 0.09033″,     β = β + 0.03916″·(cos L′ − sin L′)
Ω  = 125.04452 − 1934.136261·T + 0.0020708·T² + T³/450000
Ls = 280.4665 + 36000.7698·T,   Lm = 218.3165 + 481267.8813·T
Δψ = −17.20″·sin Ω − 1.32″·sin 2Ls − 0.23″·sin 2Lm + 0.21″·sin 2Ω
Δε =   9.20″·cos Ω + 0.57″·cos 2Ls + 0.10″·cos 2Lm − 0.09″·cos 2Ω
λ  = Θ + Δψ − 20.4898″ / R                                aberration
ε  = 23°26′21.448″ − (46.8150·T + 0.00059·T² − 0.001813·T³)″ + Δε
α  = atan2(sin λ·cos ε − tan β·sin ε, cos λ)
δ  = asin(sin β·cos ε + cos β·sin ε·sin λ)
d  = JD − 2451545.0,  Tu = d / 36525
GAST = 280.46061837 + 360.98564736629·d + 0.000387933·Tu² − Tu³/38710000 + Δψ·cos ε    (degrees)
UTh  = 24 · fractional part of (JD − 0.5)
E    = 4·(GAST − α) + 720 − 60·UTh,  brought into [−720, 720) by whole multiples of 1440    (minutes)
r    = R
```

`vsop87d_earth_terms.tsv` holds the VSOP87D Earth terms (Bretagnon & Francou 1988) with amplitude A ≥ 1e-8: 621 of the 2,425, within 0.038″ of the full series over 2020–2100. `vsop87_terms.py` regenerates it, and the Kotlin arrays, from the full `VSOP87D.ear`.

**TEXTBOOK**, the low-precision sun of Libya's perpetual table (plan 8.2):

```
T  = (JD − 2451545.0) / 36525
L  = (280.46646 + 36000.76983·T + 0.0003032·T²) mod 360
M  = L − 282
C  = 1.9146·sin M + 0.019993·sin 2M
λ  = L + C
δ  = asin(sin 23.44 · sin λ)
y  = tan²(23.5 / 2)
E  = 4·(−C + y·sin 2λ·180/π)                              minutes
r  = the Almanac's, as for MEEUS
```

**ALMANAC**, the Astronomical Almanac's low-precision sun with the constants PrayTimes.js uses, which Oman's tables follow (`om_mara`):

```
d  = JD − 2451545.0
g  = (357.529 + 0.98560028·d) mod 360
q  = (280.459 + 0.98564736·d) mod 360
λ  = q + 1.915·sin g + 0.020·sin 2g
ε  = 23.439 − 0.00000036·d
α  = atan2(cos ε·sin λ, cos λ)                            degrees
δ  = asin(sin ε·sin λ)
E  = 4·(((q − α + 180) mod 360) − 180)                    minutes
r  = 1.00014 − 0.01671·cos g − 0.00014·cos 2g             with this g
```

### S2. The solar cycle of a date (solver step 1)

D is a civil date in the place's time zone Z.

```
N   = the instant of 12:00 on D in Z, plus the summer-time saving Z has at that instant
U   = the UTC date of the instant N + 3600·λ/15           (the date N has in local mean time)
JD₀ = JD₀(U)
M   = 12 − λ/15                                            hours: the cycle's mean noon
```

λ is the longitude of the point the method runs at (S6). U = D wherever a zone keeps to its own side of the date line; U = D − 1 at Wallis-et-Futuna, U = D + 1 in the western Aleutians. The neighbouring cycles of D − 1 and D + 1 are simply U − 1 and U + 1. The reference takes N as `D 12:00 − (standard UTC offset of Z at that time)`, the standard offset (offset minus summer saving) being an input; for the other dates of S11's derived shifts it reuses that offset (the cases' zones keep one standard offset through those dates).

### S3. Events (solver steps 2–4)

Every event uses one sun function `sun(JD)` (S4 says which).

**Transit** at longitude λ:

```
t = M
repeat at most 10 times:
    t′ = M − E(JD₀ + t/24) / 60
    stop with t′ when |t′ − t| < 0.1 s;   t = t′
```

**An event at altitude a**, latitude φ, longitude λ, on side s (−1 morning, +1 evening), started from the transit t₀ of the same sun function at the same λ:

```
t = t₀
repeat at most 10 times:
    (δ, E) = sun(JD₀ + t/24)
    c = (sin a − sin φ·sin δ) / (cos φ·cos δ)
    if |c| > 1: leave the loop (no answer yet)
    t′ = M − E/60 + s·acos(c)/15
    stop with t′ when |t′ − t| < 0.1 s;   t = t′
```

If the loop gave no answer (an iterate with |c| > 1, or 10 passes without converging), a bisection decides. With the altitude function

```
alt(t) = asin( sin φ·sin δ + cos φ·cos δ·cos H ),   H = 15·(t − M) + E/4,   (δ, E) = sun(JD₀ + t/24)
```

let `lo = t₀ + 12·s` and `hi = t₀`. If `alt(lo) ≥ a` or `alt(hi) ≤ a`, the event is **absent**; otherwise bisect: `mid = (lo + hi)/2`; if `alt(mid) < a` then `lo = mid`, else `hi = mid`; stop when `|hi − lo| < 0.1 s` and return `(lo + hi)/2`. An absent event is never clamped.

### S4. When the sun is evaluated (E4)

Each event has an evaluation mode (default `EVENT_INSTANT`). For a cycle with `JD₀`:

| Mode | sun(JD) |
|---|---|
| `EVENT_INSTANT` | the model at JD |
| `DAILY_0H_UT` | the model at `JD₀`, whatever JD |
| `DAILY_10H_UT` | the model at `JD₀ + 10/24` |
| `KACST` | δ of the model at `JD₀ + 21/24`, with E and r at `JD₀ + 9/24` |
| `LOCAL_MIDNIGHT_SHADOW` (Asr only) | the model at JD; only the Asr altitude's δ differs (S7) |
| `SHADOW_AT_DHUHR` (Asr only) | the model at JD; the noon shadow is the one at Dhuhr's published instant (S7) |
| `SHADOW_AT_EVENT` (Asr only) | the model at JD; δₙ is δ at the Asr instant itself, iterated (S7) |
| `DAILY_LOCAL_NOON` | the model at 12:00 local civil time in Z (summer time included) of the date being computed, whatever JD |
| `EVENT_DECLINATION` | δ of the model at JD, with E and r of the model at 12:00 local civil time in Z of that date |

The mode of SUNRISE serves sunrise, MAGHRIB serves sunset and E12's Maghrib angle, DHUHR the transit of Dhuhr, ASR the Asr, FAJR and ISHA their angle events. A fixed sun converges on the first repeated pass. The night's lowest point (S8) and E19's bounds (S10) always use `EVENT_INSTANT`.

### S5. Horizons (E2), twilight (E3, E17)

Sunrise is the morning event and sunset the evening event at the altitude of the method's sunrise or sunset model:

| Model | Altitude |
|---|---|
| `Standard` | −0.8333 |
| `Fixed(a)` | a |
| `Pressure` | −(0.2667 + 0.5667·e^(−h/8434)) |
| `Refraction(fallback)` | −(sd + R + dip): sd = 959.63″/r, dip = acos(6378137 / (6378137 + h)) (0 when h ≤ 0), R = Bennett(−dip) |
| `PerZone(fallback)` | the zone's `sunriseAltitudeDeg` or `sunsetAltitudeDeg` |
| `StandardWithDip` | −0.8333 − dip |
| `SqrtHeight(b, k)` | −(b + k·√h), h ≥ 0 (`ma_habous` at exact coordinates: b = 1.194, k = 0.0244 at sunrise; 1.190, 0.0205 at sunset) |

- h is the morning elevation for sunrise and the evening elevation for sunset (S6). P and T are the zone's pressure and temperature, 1010 hPa and 10 °C when it has none or at exact coordinates.
- Bennett(a) = `1 / tan(a + 7.31/(a + 4.4))` arcminutes, ÷ 60 for degrees, × (P / 1010) × (283 / (273 + T)).
- r in sd is the sun's distance at the transit of the same evaluation and point.
- `Refraction` at exact coordinates (no zone) uses its fallback when it has one, else the place's own elevation at 1010 hPa and 10 °C. `PerZone` uses its fallback when there is no zone or the zone has no value.
- Twilight takes no dip (E3; `dipOnTwilight` is INM's, which never reaches the engine). Fajr and Isha by angle α are the events at altitude −α at latitude φ·`twilightLatitudeScale` (E17; 1 by default, `ly_awqaf` 0.99330562); sunrise, sunset and Asr use the real φ.
- An Isha angle may name a point `at` (`{latE5, lngE5}`, with `atNameAr`, its name in the app, which the reference ignores; `si_islamska_skupnost`: Slovenia's table copies Sarajevo's 17° Isha). Its event is then solved at that point's latitude (times the E17 scale) and longitude, on the place's own cycle and with Isha's E4 sun, and the high-latitude rules read that same angle time; everything else stays at the place's points.

### S6. Points and elevations (E6)

- No zone (exact coordinates): every event at the place's point; both elevations the place's `elevationM` (0 when null).
- A zone: `point`; Fajr and sunrise at `morningPoint` if set, the rest (Dhuhr, Asr, sunset and Maghrib, Isha) at `eveningPoint` if set. Morning elevation `elevationM`, evening elevation `eveningElevationM` else `elevationM` (0 when null).
- A time's own point (2026-10-17): `eventPoints`, a map from a time (`FAJR`, `SUNRISE`, `DHUHR`, `ASR`, `MAGHRIB`, `ISHA`) to `{latE5, lngE5}`, puts that time's event at its own point instead of the morning or evening point: the sunrise and the sunset with their horizon (and the day's ends the nights and the bounds read), Dhuhr's transit, the Maghrib angle (E12), the shadow Asr (its transit and noon shadow there too), and Fajr and Isha by angle (an Isha angle's `at` still wins). The plain bounds of E19 and S12 (the 12° and floor angles, A1's factor-1 Asr) and the season's noon and lowest point stay at the morning and evening points, as do the elevations. `bh_calendar` alone uses it: Bahrain's calendar computes each time at a point of its own (its [record](fits/bh_calendar/README-part.md)).
- A zone with a base zone runs at the base zone's points and elevations (S11).
- A zone with `sunriseAtPlace` (`km_fitted`, `yt_traditional`) shows the sunrise row from the place's own point: the raw sunrise at the exact site (S5, its elevation, the method's sunrise horizon and evaluation), then step 7's sunrise minutes. Fajr by interval still counts from the zone's sunrise, and the nights stay the zone's.
- λ of S2 is `point`'s (or the place's).

### S7. Asr (E13, E15)

The shadow altitude, with factor k = `asrShadow` (1 or 2) and the noon declination δₙ:

```
Shadow:        a = atan(1 / (k + tan|φ − δₙ|))            absent when |φ − δₙ| ≥ 90
SignedShadow:  a = atan(1 / (k + tan(φ − δₙ)))            absent when k + tan(φ − δₙ) ≤ 0
APPARENT sun:  h₀ = 90 − |φ − δₙ|;  h₀′ = h₀ + Bennett(h₀);  a′ = atan(1 / (k + tan(90 − h₀′)));  a = a′ − Bennett(a′)
```

Asr is then the evening event at a, at the evening point, with Asr's sun function, started from that function's transit t₀. δₙ is δ at t₀ (`EVENT_INSTANT`), the fixed δ (daily modes and `KACST`), or, for `LOCAL_MIDNIGHT_SHADOW`, δ of the model at the instant of 00:00 on D in Z (read as java.time's `ZonedDateTime.of` does, a time in a gap moving later; the reference takes that instant as an input). `BeforeMaghrib(n)` is Maghrib − n (step 5).

- `SHADOW_AT_DHUHR` (`ae_awqaf`, as Accurate Times computes): the noon zenith distance |φ − δₙ| is replaced by 90 − h_D, where h_D = alt(t₀ + m/60) (S3) and m = the date's Dhuhr offset (after E9) + `dhuhrAfterTransitMin`, in minutes; the APPARENT form takes h₀ = h_D. With `SignedShadow`, 90 − h_D takes the sign of φ − δ at t₀ (no method uses it).
- `SHADOW_AT_EVENT` (`bh_calendar`): Asr is solved with δₙ = δ at t₀, giving t₁; then δₙ = δ at tᵢ gives tᵢ₊₁, until |tᵢ₊₁ − tᵢ| < 0.1 s or after 5 re-solves. Asr is the last tᵢ, absent if any solve is.

### S8. Night and lowest point

N(D) runs from sunset(D) to sunrise(D + 1), the sunrise and sunset of steps 1–3 (the raw events of S5, or those H2 sets) of those dates' own cycles (U and U + 1); N(D − 1) from sunset(D − 1) to sunrise(D). Its length is sunrise − sunset. Its **lowest point** is where the sun's altitude stops falling, at the evening point with `EVENT_INSTANT`, or, when H2 set either end, the middle of the night:

```
rising(t) = alt(t + 0.5 s) > alt(t − 0.5 s)
lo = sunset, hi = sunrise;   if rising(lo) or not rising(hi): the midpoint
bisect: mid = (lo + hi)/2;  if rising(mid) then hi = mid else lo = mid;  until hi − lo < 0.1 s;  return (lo + hi)/2
```

A night with a missing end has no lowest point.

### S9. Order of operations

All minutes are added to unrounded instants; nothing is rounded before step 10.

0. **Revisions (E9).** The method's own fields are its rule before its first revision; each revision with `validFrom` ≤ D applies in date order, its `offsets` replacing the offsets of the times they name, and its `fajr`, `isha` or `jumua` replacing the method's.
   **A perpetual table's year (A8, revised by the owner on 2026-10-17).** A method with `referenceYear` R, when D's year is not R, computes D as D′, D's month and day in R (29 February in the first leap year from R), with the method as it stands on D (its revisions applied, then none), the case's Ramadan flag, and Z's standard offset at D as its only offset (the table's clock): every time keeps its clock minute and moves to D by (D − D′) whole days. So the app shows the table as printed, every year.
1. **Raw events** of D's cycle: sunrise (S5), Dhuhr = the transit at the evening point, Asr (S7, unless `BeforeMaghrib`), and Maghrib's base = E12's event at −`maghribAngle` if set, else sunset.
2. and 3. **Polar rules** (D6): H2 below. They give the sunrise and sunset of steps 1–3 (raw, or set by the 5-hour minimum), which every later step reads, and the polar Asr; `irn`'s Mecca cycle (H8) also sets Fajr and Isha.
4. **Maghrib** = its base + the method offset + E10's terrain minutes + E8's Ramadan offset (Ramadan days) + the zone's MAGHRIB shift (S11) + E16's shift + E18's seasonal and dated values.
5. **Fajr and Isha.** Fajr: the angle event (S5), or the sunrise of steps 1–3 − n (`MinutesBeforeSunrise`). Isha: the angle event, or `MinutesAfter(n, from, ramadan)`: step 4's Maghrib (or the sunset of steps 1–3, `from = SUNSET`) + n, + `ramadan` instead of n on Ramadan days when it is set. An Asr `BeforeMaghrib(n)` = step 4's Maghrib − n.
6. **High-latitude rule**, on the angle times only (a Fajr or Isha by interval has no rule): the method's `highLatitude`, H3–H7 below, on the nights of steps 1–3. A time the rule changes (by more than 1 ms) is `ESTIMATED` with the rule's name (its type in upper case, `irn`'s being `IRN_SOUTHERN_SUMMER`; `SummerForm` keeps its name outside its dates, and the takdir its own for its α/60 fallback); an absent angle time takes the rule's value.
7. **Other offsets.** For Fajr, sunrise, Dhuhr, Asr and Isha: the method offset (Dhuhr also `dhuhrAfterTransitMin`), E8's Ramadan offset, the zone's shift (S11), E16's shift (Fajr and sunrise earlier by it, an Isha by angle later; an Isha by interval already follows Maghrib) and E18's seasonal and dated values.
8. **Caps and envelope.** `ishaLatest`: Isha = min(Isha, that clock time on D in Z, or on D + 1 when it is before noon), `ESTIMATED` (`ISHA_LATEST`) when it moves, unless already `ESTIMATED` (it then keeps its rule). `ishaEarliest` (custom methods): Isha = max(Isha, that clock time, read the same way), status unchanged. Then E19 (S10).
9. **The user's steppers and the window check** (S12).
10. **The order guard and the rounding** (S13).

**E1, rounding.** NEAREST (default): `60·floor((t + 30)/60)`; UP: `60·ceil((t − 0.001)/60)`; DOWN: `60·floor((t + 0.001)/60)` (t in seconds). An Isha or Asr by interval takes Maghrib's mode; E19 sets a moved time's mode (S10); S13 overrides near the night's lowest point.

**E7.** `dhuhrAfterTransitMin` (Bahrain's 1.1) is added in step 7; a zone's fractional offsets are kept until step 10.

**E8.** `ramadanDay` is an input (the engine knows no calendar).

**E10, terrain.** `maghribTerrain` (Yemen: + 6 at or below 650 m, + 10 at or above 1,100 m). The elevation is the zone's `elevationM` (0 when null) with its `terrainClass` deciding between the limits, or, with no zone, the place's own `elevationM`, where between the limits the case's `terrainClass` decides (the class of the authority's nearest location in the place's country, which the app passes, plan E10) and, without one, the nearer limit (the lower class at an exact tie).

**E16, elevation shifts.** The shift is the `minutes` of the last entry, by increasing `fromM`, whose `fromM` ≤ the place's own `elevationM` (never the zone's); none when null. A zone's `elevationShiftFloor` (`re_cir`'s Le Tampon) makes Maghrib's and Isha's shift max(floor, shift): its column counts as the first minutes of the shift.

**E18, seasonal offsets.** A list v of 36 values: with day of year n (1-based) and N days in the year, p = 36·(n − 1)/N, k = floor(p), w = p − k: value = v[k]·(1 − w) + v[(k + 1) mod 36]·w. *Dated offsets* (`datedOffsets`): for an event, calendar dates from (month, day) to (month, day), both ends included (a span past 31 December wraps), and minutes, added on those dates of every year (`irn`: Dhuhr + 5 from 21 March to 20 September).

### S10. E19, the validity envelope

It applies to every method at a mainland French place (country code FR; overseas territories have their own codes) and to a custom method (`id = custom`) anywhere. The bounds use the plain angles at the real latitude, the method's points and `EVENT_INSTANT`; the raw sunrise, transit and sunset are the method's (S9 step 1).

| Time | Rule (each checked in this order, on the time as it stands) | Moved to, rounding |
|---|---|---|
| Fajr | before F₁₈ − 60 s, F₁₈ the 18° instant (20° for a custom method outside mainland France; on a night without it, N(D − 1)'s lowest point) | F₁₈, UP |
| Fajr | after the 12° instant (no bound on a night without it) | the 12° instant, DOWN |
| Sunrise | after the raw sunrise + 60 s | the raw sunrise, DOWN |
| Dhuhr | before transit + 72 s − 60 s | transit + 72 s, UP |
| Asr | before A₁ − 60 s, A₁ the factor-1 `Shadow` Asr on the geometric sun | A₁, UP |
| Maghrib | before sunset + 60 s − 60 s | sunset + 60 s, UP |
| Isha | estimated (an Isha by interval, or one already `ESTIMATED`): before sunset + the «Shafaq Ahmer» minutes | that instant, UP |
| | estimated and not before it, but before the 12° instant (or on a night without it) | not moved; `ESTIMATED` |
| | not estimated: before the 12° instant | the 12° instant, UP |

A moved time is `ESTIMATED` (`VALIDITY_ENVELOPE`) unless it already was. «Shafaq Ahmer» is the Moonsighting Committee's curve (adhan-js 4.4.6, MIT), at the evening point's latitude:

```
a = 62 + 17.40·|φ|/55,  b = 62 − 7.16·|φ|/55,  c = 62 + 5.12·|φ|/55,  d = 62 + 19.44·|φ|/55
dyy = day of year + 10 (north; − 365 or 366 when it reaches the year's length), or day of year − 172 (− 173 in a leap year; + the year's length when negative) in the south
minutes = a + (b−a)/91·dyy                (dyy < 91)
        = b + (c−b)/46·(dyy − 91)         (< 137)
        = c + (d−c)/46·(dyy − 137)        (< 183)
        = d + (c−d)/46·(dyy − 183)        (< 229)
        = c + (b−c)/46·(dyy − 229)        (< 275)
        = b + (a−b)/91·(dyy − 275)        otherwise
```

### S11. Zone shifts

A zone's shift Δ(event), in minutes, is added in steps 4 (Maghrib) and 7 (the others).

- A zone without `baseZoneId` (or with no base zone given): Δ = its `offsets`, and the method runs at the zone (S6).
- A zone with a base zone: the method runs at the base zone, and Δ = the base zone's `offsets` + the shift of the zone's `offsetRule`:
  - `PUBLISHED`: the zone's `offsets`.
  - `MONTHLY_DIFFERENCE` (Jordan): for each event, (own − base) on day 15 of D's month, rounded to the half-minute (`floor(2x + 0.5)/2`), own and base being the unrounded steps 1–7 at the zone's and at the base zone's own sites, with no zone shift and no Ramadan day.
  - `WINDOW_CEILING` (Algeria): for each event, `ceil(max(own − base) − 1e-9)` over every day of the offset window (the date alone when none is given).
  - `TAKVIM` (`bosnia_iz`): from Sarajevo, 43°52′N 18°26′E, with the φ and λ of the zone's E6 points, its morning point for Fajr and sunrise and its evening point for Dhuhr, Asr, Maghrib and Isha (its `point`, the town the mapping reads, where it has none): Δλ = 4·(18°26′ − λ); north (φ ≥ 43°52′): τ = `takvimMeans`[month − 1], g = 1.1; south: τ = `takvimMeans`[12 + month − 1], g = 0.9; Δφ = τ·g·(φ − 43°52′). Fajr and sunrise floor(Δλ + Δφ + 0.5) at the morning point; Dhuhr floor(Δλ + 0.5), Asr, Maghrib and Isha floor(Δλ − Δφ + 0.5) at the evening point.

### S12. Step 9: the user's steppers

Each stepper moves its own time by n whole minutes (u → v = u + 60n). The window check then uses the bounds of the sun's disk: Dhuhr's lower bound transit + 72 s, Asr's A₁, Maghrib's the raw sunset; Fajr's and sunrise's upper bound the raw sunrise.

- Lower bound L: if u ≥ L, the time is v unless v < L − 60 s, when it is L. If u < L (already past), it is v when n > 0 and u when n < 0.
- Upper bound U: if u ≤ U, v unless v > U + 60 s, when it is U. If u > U, v when n < 0 and u when n > 0.
- Isha has no bound here. A held time is `ESTIMATED` (`ADJUSTMENT_HELD`) unless it already was.

### S13. Step 10: the order guard and the rounding

With the unrounded times of S12 and the lowest points P₋ of N(D − 1) and P₊ of N(D):

1. **The walk.** Fajr = min(Fajr, sunrise); then sunrise, Dhuhr, Asr, Maghrib and Isha in turn, each = max(itself, the last present time before it).
2. **The night hold.** Maghrib and Isha = min(itself, P₊); Fajr and sunrise = max(itself, P₋).
3. A time either step moves by more than 1 ms becomes `ESTIMATED` with the rule `HELD_IN_ORDER` (the walk) or `NIGHT_LOWEST_POINT` (the hold), unless it already was.
4. **Rounding** (E1), except that Maghrib and Isha less than 60 s from P₊, and Fajr and sunrise less than 60 s from P₋, round to NEAREST.
5. **The walk on the minutes**, as in 1; statuses unchanged.

### High-latitude rules

The rules of plan 7.4 (milestone M2). "The angle" is the method's Fajr or Isha angle α (S5, with E17's latitude and E4's sun); a night "with the angle" is one on which that event exists. Isha takes N(D) (from sunset(D) to sunrise(D + 1)), Fajr N(D − 1); "sunrise" and "sunset" are those of steps 1–3. For another date d, its events and nights are computed the same way (its own cycle, its own steps 1–3).

**H1. Every-night bounds.** `later(t, b)`: a Fajr t no earlier than b (an absent t becomes b). `earlier(t, b)`: an Isha no later than b.

**H2. Steps 2 and 3, the polar rules (D6).**

- *When.* `DIYANET_CLAMP`, or any `minDayNightHours` (the Diyanet presets): every day. `MIN_DAY_NIGHT_5H` (the default): only on a day that *needs* it, sunrise or sunset absent, or sunrise outside [T − 9.5 h, T − 2.5 h], or sunset outside [T + 2.5 h, T + 9.5 h] (T: Dhuhr's raw transit), and only when the *gate* passes: the place's latitude (the larger of the morning and evening points') is at least 60°, and some day of D's calendar year has no raw sunrise, no raw sunset, or no shadow Asr (S7). `IRN_MECCA_CYCLE`: never (H8 instead).
- *The clamp.* With the centre c = T, or T + 5 min for the Diyanet presets: sunrise = clamp(raw sunrise, c − 9.5 h, c − 2.5 h) and sunset = clamp(raw sunset, c + 2.5 h, c + 9.5 h). An absent event is a polar night when the sun's altitude at T is below the sunrise horizon (S5), giving c − 2.5 h and c + 2.5 h, otherwise a midnight sun, giving c − 9.5 h and c + 9.5 h. The Diyanet presets clamp their published times (fits/diyanet_takdir): with o_r and o_m the method's SUNRISE and MAGHRIB offsets in seconds (step 0), sunrise = clamp(raw sunrise + o_r, c − 9.5 h, c − 2.5 h) − o_r and sunset = clamp(raw sunset + o_m, c + 2.5 h, c + 9.5 h) − o_m, an absent event taking the bound above minus o_r or o_m. A time the clamp moves by more than 1 ms is `ESTIMATED` (`MIN_DAY_NIGHT_5H` or `DIYANET_CLAMP`), sunrise as itself and sunset as Maghrib's base (unless `maghribAngle` is set).
- *The Diyanet presets' held night.* 21 June of D's year *holds its night* when its raw sunrise + o_r is before c − 9.5 h and its raw sunset + o_m after c + 9.5 h (c of 21 June), an absent event counting as held under a midnight sun (never in a polar night). Then, on a date D from 1 March to 31 October of that year, with each time read as seconds after 00:00 UTC of its own date (a time of day): sunrise = max(sunrise, 21 June's) and sunset = min(sunset, 21 June's) (their clamped values); then, from 2 March to 21 June, sunset = max(sunset, the previous date's sunset), and from 22 June to 31 October, sunrise = max(sunrise, the previous date's sunrise), the previous date's after these same steps. A time this moves from the raw event is `ESTIMATED` (`DIYANET_CLAMP`) as above.
- *The polar Asr.* A shadow Asr that is absent (S7) becomes (T + sunset)/2 (`POLAR_ASR_MIDPOINT`), or T for the Diyanet presets (`DIYANET_CLAMP`), whose Asr + 4 the order guard then ties to Dhuhr + 5.

**H3. Absent-nights rules, and the ramp.** On a night with the angle the rule returns the angle time. Otherwise it takes an estimate x(d), seconds after sunset for Isha (before sunrise for Fajr), and, when ramped, holds it to the real times nearby: for k = 1, 2, … up to 200, take the first date D − k with the angle, whose real interval is r (Isha − its sunset, or its sunrise − Fajr), and clamp x into [r − 300·k, r + 300·k] seconds; then the same with the first date D + k. The time is sunset + x (Isha) or sunrise − x (Fajr). The estimates, with N the night's length in seconds:

| Rule | x | Ramped |
|---|---|---|
| `AngleBasedAbsentNights` | α/60 · N | yes |
| `LocalRelativeEstimation` | R · N | yes |
| `OneSeventh` | N/7 | yes |
| `MiddleOfNight(ramped)` | N/2 | as given (`apbif`: no) |
| `NearestDay(false)` | the nearest date with the angle (D − k before D + k, k = 1, 2, …): its interval / its own night × N | yes |
| `NearestDay(true)` | the clock time, in Z, of the angle event on the last date D − k with it, read on its local date + k days, as an interval from D's sunset (before D's sunrise) | yes |

R (LRE) is the mean, over every date of D's calendar year with an Isha by angle (or, for a method whose Isha is not an angle, a Fajr by angle), of the interval / the night's length, leaving out unstable dates: those whose interval differs by more than 10 min from either neighbouring date's, or whose neighbour has no interval. The neighbours of 1 January and 31 December are the adjacent years' dates, computed like the others. One R per method and year, from the Isha data, serves Fajr and Isha. With no stable date, R = α/60.

**H4. Every-night rules.**

| Rule | Isha | Fajr |
|---|---|---|
| `AngleBased` (D5) | earlier(t, sunset + α/60 · N) | later(t, sunrise − α/60 · N) |
| `ClockCaps(i, f)` | earlier(t, i on D in Z, or on D + 1 before noon) | later(t, f on D in Z, or on D − 1 after noon) |
| `MoonsightingSeasonal(fajrOnly)` | unless fajrOnly: min(base, sunset + the *general* curve) | max(base, sunrise − the *Fajr* curve) |
| `DumRf` | earlier(t, sunset + r(D) · N′) | later(t, sunrise − 0.30 · N′) |
| `DumRt` | sunset + 90 min when N's lowest altitude is above −18°, else t | sunrise − 120 min when N's lowest altitude is above −18°, else t |

- Moonsighting: base is the angle time, or, at |φ| ≥ 55°, sunset + N/7 (sunrise − N/7); an absent base takes the curve. The curves are the four of plan 7.4 (adhan-js 4.4.6), built like S10's: value = a + (b − a)/91 · dyy … with a, b, c, d = base + k·|φ|/55 for Fajr (75; 28.65, 19.44, 32.74, 48.10) and Isha general (75; 25.60, 2.05, −9.21, 6.14). φ is the evening point's latitude.
- DUM RF's night N′ is 24 h minus D's own day (sunset − sunrise, steps 1–3), for Fajr and Isha alike, as its tables take it. Its share r(D): 0.30 from 1 to 30 June; before 1 June, with n = floor((days from D to 1 June − 1)/3) + 1, and after 30 June, with n = floor((days from 30 June to D − 1)/3) + 1: max(0.25, 0.30 − n/240).
- DUM RT's "lowest altitude" is the sun's altitude (S3, `EVENT_INSTANT`, evening point) at N's lowest point (S8).

**H5. Summer forms.** `SummerForm(from, to, f, i)`: on a date whose month and day lie from `from` to `to` (both included; wrapping over the new year when `from` > `to`): Fajr = later(t, sunrise − f min) and, when i is set, Isha = earlier(t, step 4's Maghrib + i min). On other dates, D5's `AngleBased` (H4).

**H6. Diyanet's takdir** (`DiyanetTakdir`): the criteria of the 2021 International Prayer Times Congress as Diyanet's 2027 tables apply them (fits/diyanet_takdir). It acts only where the evening point's latitude is at least 44.375° north: Diyanet's southern tables (45.9 to 54.8° S) keep the angle times all year. The rule builds a table for the whole of D's year Y. For each date x of Y, as seconds after 00:00 UTC of x (times of day, which compare across dates): F(x) and I(x) the angle events (S5, E4; absent when the sun does not reach the angle), R(x) and M(x) the sunrise and sunset of steps 1–3 (H2, with the held night) plus o_r and o_m (H2: the published sunrise and Maghrib), the sharia night S(x) = F(x) + 86,400 − M(x), the night N(x) = R(x) + 86,400 − M(x), and k = the Fajr angle / the Isha angle. Each date's Fajr and Isha start as F(x) and I(x).

- *Case A*, F on every date from 1 February to 30 November of Y: both sides anchor on 21 June, with A_F = R − k·S/3 and A_I = M + S/3 of 21 June.
- *Case B*, otherwise: xa and xb are the first and the last of those dates without F, l the date before xa, and r = S(l)/3/N(l). On every date x from xa to xb: Fajr = R(x) − k·r·N(x) and Isha = M(x) + r·N(x). The spring side anchors on xa and the autumn side on xb, each with the value of its date.
- *Transitions*, for Fajr and Isha apart, on each side of the anchor date a with the value A, moving away from a (to earlier dates on the spring side, to later ones on the autumn side): n is the number of consecutive dates next to a whose angle time is *past*, F earlier than A + 1,200 s for Fajr and I later than A − 1,200 s for Isha, an absent angle counting as past (n stops at 200). On the date i days from a (1 ≤ i ≤ n, within Y), the line is A + 1,200·i/n (Isha: A − 1,200·i/n), and the time is the later Fajr (the earlier Isha) of the line and the angle time, or the line where the angle is absent. On a itself (within Y), the time is A.
- *Held night* (21 June holds its night, H2): every date's Fajr becomes max(Fajr, 21 June's Fajr) and its Isha min(Isha, 21 June's Isha), absent times staying absent. In case B, then, on each date from the day after xa − n (n the spring Isha transition's; never before 1 January) to 21 June, Isha = max(Isha, the previous date's Isha), and on each date from 22 June to xb + n (n the autumn Fajr transition's; never after 31 December), Fajr = max(Fajr, the previous date's Fajr), in date order and where both times are present.
- D's Fajr and Isha are D's own row of the table, as instants; a time that differs from the angle time by more than 1 ms is `ESTIMATED` (`DIYANET_TAKDIR`). A time still absent (south of 44.375° N, or where nothing above sets it) takes D5's α/60 of the night (H4's `AngleBased` bound), also `DIYANET_TAKDIR`.

**H7. IRN** (`Irn`), south of 63.56° (the zone's or place's point):

- Switch days are day numbers of a common year (the same calendar date every year; 29 February counts as 28 February). The zone's `switchDays` give [first, last] for ISHA and FAJR; otherwise Isha's first is the day after the first day from 32 to 172 whose angle Isha is absent or at or after 20:00 UTC (time of day), and its last the day before the last such day from 172 to 365, each side on its own (none: no Isha season); Fajr's first is the first day from 32 to 172 whose Fajr is absent or at least 153 min before sunrise, and its last 341 − first.
- Isha on a day n in [first, last]: with h(t) the UTC time of day of t in hours, the target y = h(sunset(172) + the method's Maghrib minutes + 40 min); from (first − 1, h(Isha(first − 1))) to (172, y) when n ≤ 172, or from (172, y) to (last + 1, h(Isha(last + 1))) after it, linearly in n; Isha = max(00:00 UTC of D + that many hours, sunset + Maghrib minutes + 40 min).
- Fajr on a day n in [first, last]: the clock time in Z of the angle Fajr of day first − 1 (through day 172) or last + 1 (from day 173), read on D; then min(that, sunrise − 60 min).
- From 63.56°, H8 sets Fajr and Isha.

**H8. IRN's northern rules** (`IRN_MECCA_CYCLE`, from 63.56°, the zone's or place's point). Day numbers m are those of a common year (H7), counted on from D's: m < 1 and m > 365 are dates of the years before and after. T(m) is the raw transit of m (Dhuhr's evaluation).

- *Real times of m.* Sunrise and sunset: the raw events; with none, T(m) for both in a polar night (the noon sun below the sunrise horizon) and T(m) ∓ 8 h otherwise; *capped*, sunrise no earlier than T(m) − 8 h and sunset no later than T(m) + 8 h. Fajr: the angle Fajr, no earlier than T(m) − 9 h (none: T(m) − 9 h); Isha: the angle Isha, no later than T(m) + 9 h (none: T(m) + 9 h).
- *Mecca's day on m:* Mecca's (21.4225°N 39.8262°E) sunrise, sunset, 18° Fajr and 18° Isha minus Mecca's transit, computed with the method's own E4 evaluations, local noon being the place's (12:00 in Z), no latitude scale, the standard horizon and no polar rule, added to T(m).
- *A ramp* L(m; m₀, y₀, m₁, y₁) is the straight line in UTC time of day: with u(y, d) = y − 00:00 UTC of d, L = 00:00 UTC of m + u(y₀, m₀) + (u(y₁, m₁) − u(y₀, m₀))·(m − m₀)/(m₁ − m₀).
- *Days*, from the zone's `switchDays`: MAGHRIB {first, last} = {a + 1, b − 1}, a the last real day of spring and b the first real day of autumn, or {1, 365}, Mecca's day all year (Svalbard); SUNRISE {first, last} = {s + 1, e − 1}, the Mecca winter, s the last real day of autumn and e the first real day of spring, 0 for a half without it; DHUHR {first, last} = {f, g}, the floor days, f to 365 in autumn and 1 to g in spring, 0 for a half without them. A place without them (no zone): a the day before the first day from 60 to 172 whose raw sunset is absent or more than 8 h 05 after T(m), b the day after the last such day from 172 to 300 (no such day: no summer); north of 66.33° the Mecca winter, s the day before the first day from 260 whose raw sunset is absent or less than 3 h 20 after T(m), e the day after the last such day up to 100; between 63.56° and 66.33° the floor on every day of both halves.
- *Summer* (not Svalbard): sunrise and sunset take L(m; a, real uncapped of a, a + 7, Mecca of a + 7) on a < m < a + 7, Mecca's on a + 7 ≤ m ≤ b − 7, and L(m; b − 7, Mecca of b − 7, b, real uncapped of b) on b − 7 < m < b; Fajr and Isha the same with a′ = a + 7 and b′ = b − 8 and their real values (capped).
- *Winter*, each half on its own (spring: m ≤ 181; autumn: m ≥ 182). The Mecca winter: in autumn, all four times take L(m; s, real of s, s + 30, Mecca of s + 30) on s < m < s + 30 and Mecca's from s + 30; in spring, Mecca's up to e − 30 and L(m; e − 30, Mecca of e − 30, e, real of e) on e − 30 < m < e (real values capped). The floor: sunset = max(sunset, T(m) + 2 h) and sunrise = min(sunrise, T(m) − 2 h) on its days.
- Svalbard: Mecca's day for all four times on every day.
- Then, on every day: sunset no later than T + 8 h and sunrise no earlier than T − 8 h. Sunset is Maghrib's base.
- *Asr* (shadow methods): min(max(the shadow Asr, (T + sunset)/2), sunset − 1 h); none: (T + sunset)/2 under the same cap.
- A time H8 moves from its real value is `ESTIMATED`: `IRN_MECCA_CYCLE`, Asr `POLAR_ASR_MIDPOINT`.

### Arab authorities

Each authority gets its part when its profile is fitted (milestone AM2: SA, OM, QA, JO, EG, AE, YE, DZ, MA, LY, then IQ, SD, SO, DJ and KM; AM10 the rest), with its zones, its fitted values and its fit record. The mechanisms they use are the E-rules above (plan 8.7):

| Authority | Mechanisms |
|---|---|
| Saudi Arabia (`sa_umm_al_qura`) | Fajr 18.5°, Isha = Maghrib + 90 (+ 120 on Ramadan days); E1 UP on Fajr, Dhuhr, Asr and Maghrib, DOWN on sunrise; E2 `Fixed(−0.83)` sunrise, `Standard` sunset; E4 `KACST` for Fajr and Asr; VSOP87 with an E18 constant of + 1 s (fitted, [record](fits/sa_umm_al_qura/README-part.md)) |
| Oman (`om_mara`) | 18°/18°, Dhuhr, Asr and Maghrib + 5; E4 `DAILY_0H_UT` for all six; the ALMANAC sun with an E18 constant of + 0.86 s; 86 zones at whole arc-minutes, named towns within 10 km first (fitted, [record](fits/om_mara/README-part.md)) |
| Qatar (`qa_awqaf`, `qa_qch`) | 18° − 1 min (E9 from 2020-08-20), Maghrib + 2, Isha = Maghrib + 90; E8 Maghrib + 1; zone Fajr + 1 for five towns; QCH Dhuhr and Asr + 1 from 2025-06-26; VSOP87 at reference year 2006; six `LISTED_PLACES` at fitted points (fitted, [record](fits/qa/README-part.md)) |
| Jordan (`jo_awqaf`) | 18°/18°; E2 `Refraction` with morning and evening elevations in standard air; E6; E13 APPARENT; VSOP87; Maghrib + 1 (E7) in three zones; further places by `MONTHLY_DIFFERENCE`; below sea level the governorate's Ghor table (`belowSeaLevel`); the Jerusalem entry within 15 km (fitted, [record](fits/jo_awqaf/README-part.md)) |
| Palestine, West Bank (`ps_dar_al_ifta`) | 18.065°/17.847°, Maghrib + 5; Fajr, Dhuhr and Asr DOWN, sunrise and Isha UP; E2 `Fixed(−1.5559)` sunrise, `Fixed(−1.0643)` sunset; E13 APPARENT; reference year 2016; 16 listed places by Dar al-Ifta's offsets from Al-Aqsa (fitted, [record](fits/ps_dar_al_ifta/README-part.md)) |
| Gaza (`ps_gaza_awqaf`) | 19.4°/17.394°, Maghrib + 3; Fajr DOWN, Isha UP, the rest nearest; Asr on the apparent sun; MEEUS; one `NATIONAL` zone at Gaza City, fitted on the ministry's emailed 2026 table (fitted, [record](fits/ps_gaza_awqaf/README-part.md)) |
| Lebanon (`lb_dar_al_fatwa`) | 19.69°/17.5°, Maghrib + 4; Dhuhr and Asr DOWN, Maghrib UP; E2 `Fixed(−0.9379)` sunrise; E4 `DAILY_0H_UT` for Fajr, `DAILY_10H_UT` for sunrise, Asr, Maghrib and Isha; reference year 1931; one `NATIONAL` zone at Beirut's fitted point (fitted, [record](fits/lb_dar_al_fatwa/README-part.md)) |
| Syria (`sy_awqaf`) | 18°/17°; E4 `KACST` for Fajr, Asr and Isha; UP on all but sunrise; Maghrib + 2 from 2025-03-30 (E9); 14 governorate tables, four at fitted points (fitted, [record](fits/sy_awqaf/README-part.md)) |
| Egypt (`eg_egsa`) | 19.5°/17.5°; E2 `Fixed(−0.8435)` at sunrise and sunset; VSOP87; town points with fitted E6 points (fitted, [record](fits/eg_egsa/README-part.md)) |
| UAE (`ae_awqaf`) | 18°/18°, Dhuhr + 2; E2 `Refraction` (fallback `Fixed(−1.4)`); E6; E13 APPARENT; E4 `SHADOW_AT_DHUHR` for Asr; VSOP87; 41 locations by emirate, their areas as named places; nine fitted on the 2026 tables Awqaf emailed (eight tier A, Dubai tier B; Al Ain's sunrise at a point of its own), the other 29 provisional (fitted, [record](fits/ae_awqaf/README-part.md)) |
| Yemen (`ye_awqaf`) | 18°/16°, Dhuhr + 10; E2 `Pressure` at each location's fitted elevation; E4 `LOCAL_MIDNIGHT_SHADOW` for Asr; E10 (+ 6 / + 10); VSOP87; 31 `NEAREST` locations, by terrain class within 60 km (fitted, [record](fits/ye_awqaf/README-part.md)) |
| Algeria (`dz_marw`) | 18°/17°, Maghrib + 3; all UP; E15 `SignedShadow` in Algeria; attached places by `WINDOW_CEILING`; the ministry's month starts per Hijri year (fitted, [record](fits/dz_marw/README-part.md)) |
| Morocco (`ma_habous`) | 19°/17°, Dhuhr + 5, sunrise − 1, Maghrib + 2; Fajr DOWN; E2 `PerZone`, `SqrtHeight` at exact coordinates; E13 APPARENT (fitted, [record](fits/ma_habous/README-part.md)) |
| Libya (`ly_awqaf`) | 18.357° at the twilight latitude (E17), reference year 2006, Dhuhr + 4, Maghrib + 4; all DOWN; TEXTBOOK with E4 `DAILY_10H_UT`; town points with fitted E6 points (fitted, [record](fits/ly_awqaf/README-part.md)) |
| Bahrain (`bh_calendar`) | 17.975°/18.055° (the stated 18°), `dhuhrAfterTransitMin` 1.07 (the semi-diameter's crossing); DOWN except Maghrib; E2 `Fixed(−0.86)` sunrise, `Fixed(−0.84)` sunset; E4 `SHADOW_AT_EVENT` for Asr; each time at its own fitted point (S6 `eventPoints`); VSOP87 (fitted, [record](fits/bh_calendar/README-part.md)) |
| Kuwait (`kw_awqaf`) | 18°/17.5°; E13 APPARENT; reference year 2011; Kuwait City's computing point, and 11 places by their published whole-minute offsets (fitted, [record](fits/kw_awqaf/README-part.md)) |
| Iraq (`iq_sunni_endowment`) | 18°/17°, Dhuhr + 5, Asr + 3, Maghrib + 2; nearest on all six; E2 `PerZone`, each zone's horizon from Hohenkerk and Sinclair's refraction at its Accurate Times inputs (`SqrtHeight(0.7987, 0.03853)` at exact coordinates); E4 `SHADOW_AT_DHUHR` for Asr; E13 APPARENT; VSOP87; reference year 2021; 28 cities, the Kurdistan Region at the place's own point (`exactAdmin1`) (fitted, [record](fits/iq_sunni_endowment/README-part.md)) |
| Mauritania (`mr_islamic_affairs`) | 19.5°/17.5°, Maghrib + 2 on fixed horizons (E2 `Fixed(−1.066)` sunrise, `Fixed(−1.051)` sunset), the interior capitals' sunrise − 1.5 (E7); E13 APPARENT; E9: the 2021 and 2022 tables' standard horizons as + 1 on sunrise and Maghrib, and Ramadan 2022's 19°/17°; reference year 2023; 13 `NEAREST` capitals by province (fitted, [record](fits/mr_islamic_affairs/README-part.md)) |
| Sudan (`sd_fitted`) | 18°/18°, no offsets; nearest on all six; standard horizon; exact coordinates, no zones (the plan's values, checked, [record](fits/sd_fitted/README-part.md)) |
| Somalia (`so_fitted`) | 18°/17°, no offsets; nearest on all six; exact coordinates, no zones (the plan's values, checked, [record](fits/so_fitted/README-part.md)) |
| Djibouti (`dj_fitted`) | 18°/17°, no offsets; nearest on all six; exact coordinates, no zones (the plan's values, checked, [record](fits/dj_fitted/README-part.md)) |
| Comoros (`km_fitted`) | Fajr = Moroni's sunrise − 73, Isha = Maghrib + 74, Asr `BeforeMaghrib(168)` (E15), Dhuhr + 4, Maghrib + 3; one `NATIONAL` zone at Moroni with `sunriseAtPlace` (the plan's values, checked, [record](fits/km_fitted/README-part.md)); Mayotte's table is in the next section |

Each fitted authority's record, beside its `fit.py`, gives its data, its fit, its held-out scores and what is still open; `python scripts/prayer_formula/fits/<authority>/fit.py` rewrites its asset from the local data, and `fits/validate_asset.py` checks it. The plan values a fit changed: `sa_umm_al_qura` and `eg_egsa` moved to VSOP87, Saudi Arabia with a 1 s shift and Egypt with a −0.8435° horizon; Libya's angle is 18.357°. Oman's tables follow the Almanac's low-precision sun (S1 ALMANAC). The UAE's Asr takes its noon shadow at Dhuhr, Bahrain's at the Asr instant itself (S7), and Al Ain's sunrise has a point of its own (S6); Bahrain's angles are 17.975° and 18.055°, each time at a point of its own (S6) refined on a finer grid, its Dhuhr 64 s after the transit. Qatar moved to VSOP87 and Kuwait's Asr to the apparent sun. Lebanon's angles are 19.69°/17.5°, with a daily sun as an almanac table has it; Syria follows Umm al-Qura's conventions (`KACST`, all up but sunrise) with the MWL angles and no zone fractions; Dar al-Ifta's and Gaza's angles, horizons and roundings are fitted values; Jordan's zones are in standard air. Iraq's horizon is a constant per zone from Hohenkerk and Sinclair's refraction, with Maghrib + 2, nearest rounding and Asr's noon shadow at Dhuhr. Yemen moved to VSOP87. Mauritania's horizons are fixed (−1.066° and −1.051°), with Maghrib + 2 (the ministry's rule, notice of 2024-03-10), the interior capitals' sunrise − 1.5 on the fixed horizon and Asr on the apparent sun, where the plan had standard horizons, Maghrib + 3 and sunrise − 1 or − 2.5; the 2023 change was the horizon, not Maghrib + 3. Sudan, Somalia, Djibouti and the Comoros are not fitted: the plan fixes their values (A3), and their records check them against the owner's mosque dataset (tier C, 9.5), since no official table is at hand. Algeria needs each Hijri year's 12 month starts in its asset before that year begins: with Umm al-Qura's starts, times are up to 4 min off on month-edge days.

### French regional and overseas tables

| Table | Mechanisms |
|---|---|
| Rhône (`rhone_ctir`) | 14.5°/13.5°, Dhuhr + 5, Maghrib + 2; UP on the five prayers, DOWN on sunrise; E4 `KACST` for Fajr, Isha and Asr; one `NATIONAL` zone «الرون» limited to département 69 (`admin2`), the method at the user's point elsewhere. The 2020 table's summer rows hold the previous day's times; the app does not reproduce them ([record](fits/rhone_ctir/README-part.md)) |
| Réunion (`re_cir`) | 18°/18°, Maghrib + 2; UP on the five prayers, DOWN on sunrise; E16 by the place's own elevation; five `NAMED_REGIONS` zones at listed localities with E7 Fajr and Maghrib fractions; Le Tampon's `elevationShiftFloor` of 2 min on Maghrib and Isha ([record](fits/re_cir/README-part.md)) |
| French Guiana (`gf_a2cmgf`) | 18°/17°; UP on the five prayers, DOWN on sunrise ([record](fits/gf_a2cmgf/README-part.md)) |
| Martinique (`mq_ccim`) | 18°/17°, Dhuhr + 1, Maghrib + 1; UP on the five prayers, DOWN on sunrise; E4 `DAILY_0H_UT` for all six ([record](fits/mq_ccim/README-part.md)) |
| Mayotte (`yt_traditional`) | Moroni's table − 3 min from Moroni's point (`NATIONAL`, `sunriseAtPlace`); no local table yet, so no fit record ([record](fits/yt_traditional/README-part.md)) |

### European tables

| Table | Mechanisms |
|---|---|
| Bosnia and Herzegovina (`bosnia_iz`) | 18°/16°, sunrise − 6, Dhuhr + 1, Maghrib + 6; NEAREST; E4 `DAILY_0H_UT` on all six; Sarajevo's table and 130 `TAKVIM` places at their fitted E6 points (Table 1 as `takvimMeans`, printed signs), Montenegro's five towns by their published offsets. The southern table's printed signs swap the morning and evening latitude terms against the sun's (Dubrovnik, Niš, Preševo, Novi Pazar); the app reproduces them as printed ([record](fits/bosnia_iz/README-part.md)) |
| Luxembourg (`luxembourg_shoura`) | 12.8°/12.8°; E4 `DAILY_10H_UT` for Asr, `DAILY_0H_UT` for Maghrib and Isha; Isha UP; one `NATIONAL` zone at its fitted point ([record](fits/luxembourg_shoura/README-part.md)) |
| Austria (`diyanet_eu`, IGGÖ, Diyanet) | Diyanet's values unchanged; IGGÖ's nine state capitals as zones within 25 km (each table equals one of Diyanet's entries), then Diyanet's 191 Austrian entries as tier 1, the nearest whatever the distance; points fitted on a 0.0025° grid (E6). Outside the takdir season 97.7–98.4 % exact held out, every time within 1 min; with H6 the whole of 2027 is 98.0 % (Fajr) and 98.2 % (Isha) exact, every time within 1 min, the takdir days included ([record](fits/diyanet_eu_at/README-part.md)) |
| Diyanet's takdir (`diyanet_eu`, H6 and H2) | The 2021 Prayer Times Congress's criteria as Diyanet's 2027 tables apply them, read from 144 entries (43–78°N) and held out on half of them and on Austria's 190: a third of the sharia night (case A), the frozen share of the night where 18° never forms (case B), 20-minute transitions, 21 June's held night, the 5-hour rule on the published times; from 44.375°N only (Diyanet's southern tables keep the angles, and their Isha is 17°). Held out on 2027: 97.2 % (Fajr) and 97.5 % (Isha) exact, every Isha and 99.6 % of Fajr within 1 min (France's E19 moves some June Fajr earlier) ([record](fits/diyanet_takdir/README-part.md)) |
| Turkey (`diyanet_tr`) | Diyanet's 869 district entries (`DISTRICT`: each lists its GeoNames district codes, a fix taking its district from the nearest catalogue row or entry point), at their fitted points (E6) on a 0.0025° grid; no takdir in Turkey, as in the engine (it starts at 44.375°N); held out 97.4–98.2 % exact, every time within 1 min; fitted on 2027, since the site serves no 2026 year table ([record](fits/diyanet_tr/README-part.md)) |
| Kosovo and the Preševo Valley (`xk_bik`) | BIK's Takvim is perpetual (2025 = 2026 by date), so 2026–2029 are pooled: 18°/17° at 42.5°N 21°E, the sun at the event instant, NEAREST, and 36 E18 values a time; seven towns by their published shifts, each reach the farthest point of its OpenStreetMap boundary, and the Valley's three unshifted zones (15, 13.5 and 13 km); a town wins over its Valley's disc at equal distance by its smaller reach ([record](fits/xk_bik/README-part.md)) |
| Bulgaria (`bg_grand_mufti`) | Every city is Sofia's table plus one whole-minute shift on all six times (October 2026): 18.2°/17.2°, sunrise − 8, Dhuhr + 5, Asr + 4, Maghrib + 8; Fajr and Isha DOWN, sunrise and Asr UP; E4 `DAILY_0H_UT`; Sofia's zone and 47 shifted ones; refitted as the collector's months arrive ([record](fits/bg_grand_mufti/README-part.md)) |
| Slovenia (`si_islamska_skupnost`) | The TEXTBOOK sun at the event instant, E6 point 45.76608°N 14.41841°E: Fajr 19° + 1, sunrise − 5, Dhuhr + 2, Asr − 1 UP, Maghrib + 5. Its Isha is the IZ BiH's former Sarajevo Isha: 17° at Sarajevo's point (`IshaRule.Angle.at`, S5) with the sun at 0 h UT, + 18 (91 of the feed's 92 days within 1 min, against 74 for the best Ljubljana angle); the summer form's 111 and 72 keep the Ramadan 2020 sheet's printed relations; the five places by the feed's offsets ([record](fits/si_islamska_skupnost/README-part.md)) |
| Croatia (`hr_miz_zagreb`) | MIZ Zagreb's Ramadan 2025 sheet: Fajr 19.3° UP, Isha = Maghrib + 80, sunrise − 8 UP, Dhuhr + 1, Maghrib + 5 UP; the TEXTBOOK sun once a day at 10 h UT; E6 point 45.81444°N 15.90158°E; the summer form to 1 September, its 113 counted from the computed sunrise (the printed «sunrise − 105»). Its `NATIONAL` zone covers only the places nearer Zagreb than the Takvim's four other Croatian towns ([record](fits/hr_miz_zagreb/README-part.md)) |
| North Macedonia (`mk_bfi`) | Not fitted: two collected days, on which the preset is within 1 min on all ten times |
| Norway (`irn`) | IRN's 358 tables of 2026: 16°/15°, MEEUS; S4's `DAILY_LOCAL_NOON` for sunrise, Dhuhr, Asr and Maghrib and `EVENT_DECLINATION` for Fajr and Isha; sunrise − 2, Dhuhr + 5 and E18's dated + 5 from 21 March to 20 September, Maghrib + 5; every time DOWN; H7 south of 63.56°N and H8 from there on each municipality's switch days (`ADMIN_AREA`, 358 zones); 354 zones at tier A, all within 1 min but one sunrise ([record](fits/irn/README-part.md)) |
| Ireland (`ie_ifi`) | The IFI's perpetual Dublin table: Fajr = sunrise − 100 and Isha = Maghrib + 90 with 36 E18 values a time (Dhuhr's + 2 among them); its 33 towns by Dublin's intervals at their own sunrise and Maghrib (`NEAREST`) ([record](fits/ie_ifi/README-part.md)) |
| France, APBIF (`apbif`) | APBIF's perpetual Paris table (its sun about 2014): 18°/12°, Fajr, Dhuhr, Asr and Maghrib + 6, E2 `Fixed(−1°)`, sunrise DOWN and Maghrib UP; Asr on the apparent sun with `SHADOW_AT_DHUHR`; within 1 min on every Paris day ([record](fits/apbif/README-part.md)) |
| Russia, Moscow (`dum_rf`) | DUM RF's perpetual Moscow table, computed for 2025: 18°/15°, sunrise − 5, Dhuhr + 5, Maghrib + 5, NEAREST; Asr on the sun at 10 h UT; H4's `DumRf` with N′ = 24 h − the day; one `NATIONAL` zone «موسكو» for Moscow and its oblast ([record](fits/dum_rf/README-part.md)) |

### Astronomy fixtures

`astronomy_fixtures.py` fetched two public-domain references into `test-data/engine-reference/` (2026-10-15), which `AstronomyFixturesTest` checks:

- `horizons_sun.tsv`: JPL Horizons' apparent sun from the Earth's centre (DE441) at 800 TT epochs, every 37 days of 2020–2100. VSOP87 (S1) is within 0.15″ in declination and 0.03 s in the equation of time; INM's MEEUS sun within 10.5″ and 3.9 s (3.0 s up to 2030), its 1900-epoch formulae drifting with the years (ignoring ΔT accounts for under 3″).
- `usno_sun.tsv`: the US Naval Observatory's sunrise, transit, sunset and civil twilight (−6°), to the minute, for 14 places on 6 dates: 409 of the engine's 420 raw events fall on USNO's minute, all within 33 s.

### The reference and the cross-check

`prayer_engine_reference.py` (standard library only) implements S1–S13 from this section and writes UTC instants only. Run it on the shared cases:

```bash
python scripts/prayer_formula/prayer_engine_reference.py --cases test-data/engine-reference/cases.json --out test-data/engine-reference/expected.tsv
```

`cases.json` (written by `EngineReferenceCases`, a recorder in the shared tests) is a list of cases. Each has `id`; `place` (`latE5`, `lngE5` in 1e-5 degrees, `elevationM` or null, `zoneId`, `countryCode` or null); `date`; `method`, the JSON of `PrayerMethod` with every field present (sealed values carry a `type`: `angle`, `before_sunrise`, `after`, `shadow`, `signed_shadow`, `before_maghrib`, `angle_based`, `standard`, `fixed`, `pressure`, `refraction`, `per_zone`, `standard_with_dip`, `sqrt_height`); `ramadanDay`; `zone` and `baseZone` (null, or `authority`, `id`, `point`, `morningPoint`, `eveningPoint`, `elevationM`, `eveningElevationM`, `pressureHpa`, `temperatureC`, `sunriseAltitudeDeg`, `sunsetAltitudeDeg`, `offsets`, `baseZoneId`, `offsetRule`, `terrainClass`, `elevationShiftFloor`, `sunriseAtPlace`); `offsetWindow` (null, or `from` and `to`); `adjustments` (event → minutes); `terrainClass` (null, `LOW` or `HIGH`: E10's class of a place between the limits at its own point); and `zoneFacts`, what the reference cannot look up without time-zone rules: `standardOffsetSeconds` at D's civil noon, `utcOffsetSeconds` (Z's offset then, with any summer time; the reference reads every clock time of H3, H4 and H7 with it, and the cases keep one offset over the dates those rules look at), `localMidnightEpochSeconds` (00:00 of D in Z), `offsetChanges` (Z's UTC offsets from 400 days before D to 400 days after it, as [from epoch seconds, offset seconds] pairs, the first from the start of that span: `DAILY_LOCAL_NOON` and `EVENT_DECLINATION` read each date's 12:00 with them), and `ishaLatestEpochSeconds` and `ishaEarliestEpochSeconds` (the method's clock times as S9 step 8 reads them, or null). A zone may also carry `switchDays` (event → `first`, `last`), H7's and H8's days.

`expected.tsv` has, after `#` comment lines, six rows per case: `id`, `event` (FAJR … ISHA), `status`, `rule` (or `-`), the minute as ISO-8601 UTC (`2026-10-12T16:09:00Z`, or `-` when absent) and the unrounded instant in epoch seconds with three decimals (or `-`), tab-separated. `EngineReferenceCrossCheckTest` runs the engine on every case and checks it against `expected.tsv`: the same status (unless the order guard compared two instants less than 1 s apart), instants within 1 s, and the same minute (except within 1 s of a rounding boundary, or of 60 s from the night's lowest point).

### On the device (plan 9.1)

`scripts/device_checks/run_device_scenarios.py` never uses an existing emulator or AVD: it creates a throwaway AVD from an installed system image in its own folder, boots it on port 5570, installs the debug and test APKs with `adb -s emulator-5570`, and runs `DeviceScenariosInstrumentedTest` with `-e deviceScenarios true` (without it, and on anything but an emulator, the class is skipped, since it moves the phone's zone). The class stores each scenario's place as the picker does (Paris today; Brest on 21 June; Paris across 01:00 UTC on 2026-03-29, 2026-10-25 and 2027-03-28; Tromsø in June and December; Muscat; Riyadh around the first day of Ramadan; Cairo across its October change; Casablanca around its Ramadan changes; Gaza; Moroni; a Dubai Friday; Tunis, 615, every day of 2026), arms the scheduler at Paris with the phone in Paris and on London and for runs just before each change and reads the triggers back from `dumpsys alarm`, moves the phone to Dubai with a mock location and `cmd alarm set-timezone` and checks what the travel check does (with the main screen open the place must move and its windows be armed), finds today's times on the main screen, and times the 365-day scans. Since an instrumented process may count as a foreground one, the script then drives 6.8's background case itself: the app is opened at Paris and left, the emulated GPS moves to Dubai, the zone changes from adb so the broadcast reaches the app with no screen open, and a last phase reads the outcome (a move, or the job's notification that opens the picker) and the location app-ops. On Android 17 (API 37, tzdata 2026c, 2026-10-16), every row matched the JVM and all 2,190 Tunis rows matched `docs/csv/615`; the triggers in `dumpsys alarm` were the planner's to the millisecond for each of the scheduler's four runs (the phone in Paris and on London, and runs just before the changes of 2026-10-25 and 2027-03-28); with the main screen open the place moved to Dubai and its windows were armed; with no screen open the system refused the location (`FINE_LOCATION` rejected, the manifest having no background-location permission) and the job posted the picker notification, as 6.8 expects. The script then runs `DeviceScenariosCheckTest` (`:shared:javaTest`, with `DEVICE_SCENARIOS_DUMP`) on the pulled `device-scenarios.tsv`: every row computed again by the engine the cross-check above ties to this reference, the wall clocks against the JDK's zone rules, and Tunis against `docs/csv/615`. The emulator is stopped and the AVD deleted afterwards (`--keep` leaves it running, `--remove` deletes it later).

**On the owner's phone (plan M9), read-only.** `PhoneScanTimings` (in the test APK) times the 365-day scans in a process of its own: pushed to `/data/local/tmp` and run with `app_process` as the adb shell user, the installed APK on its class path and its assets read out of it, so the app, its data and its alarms are never touched; the pushed file is removed afterwards. On the OnePlus CPH2747 (Android 16, 2026-10-16), each scan alone in a cold process, without the app's installed profile: Tromsø under its default `irn` 222 ms (IRN's 358 zones parsed first), Tromsø under `mwl` with D6's polar gate 45 ms, Paris under its default `mdf_12` 43 ms and with LRE 44 ms, Casablanca's mapped zone with the validity guard 93 ms, Muscat's 59 ms, Hamburg's `diyanet_eu` with H6's year table 37 ms, a Tunisian delegation's INM year 7 ms; in one process, once the code has run, 5–56 ms each.

## Usage

```bash
# Check the formula against the meteo.tn 2026 CSVs in docs/csv
python scripts/prayer_formula/inm_prayer_times.py --verify

# The same for the TV dashboard's JavaScript copy (node, no dependencies); exits 1 on any miss
node scripts/prayer_formula/check_dashboard_formula.js

# Print one month, in the same layout as android-app/.../csv/<id>/<year>/<MM>.csv
python scripts/prayer_formula/inm_prayer_times.py --delegation 615 --year 2027 --month 3

# Without --month, all 12 months are printed one after another, each with its own header
python scripts/prayer_formula/inm_prayer_times.py --delegation 615 --year 2027

# The shared engine's reference on the shared cases, and its unittest
python scripts/prayer_formula/prayer_engine_reference.py --cases test-data/engine-reference/cases.json --out test-data/engine-reference/expected.tsv
python -m unittest scripts/prayer_formula/test_prayer_engine_reference.py
```

Delegation ids are the same as in `gouvernorats.json` (for example 615 = Tunis). The scripts use only the Python standard library.
