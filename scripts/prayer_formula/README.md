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
```

Delegation ids are the same as in `gouvernorats.json` (for example 615 = Tunis). The script uses only the Python standard library.
