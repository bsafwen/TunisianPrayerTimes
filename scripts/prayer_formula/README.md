# INM Prayer-Time Formula

A reconstruction of how Tunisia's **Institut National de la Météorologie (INM)**, the source behind [meteo.tn](https://www.meteo.tn), computes the daily prayer times for every delegation. It reproduces INM's published times **to the minute** and runs fully offline, so the app can produce any year without scraping.

It is validated on 2020–2026. One-off quirks in INM's future tables, like Zeriba's 2026 sunrise (see [INM data quirks](#inm-data-quirks)), cannot be anticipated.

| File | Purpose |
|------|---------|
| `inm_prayer_times.py` | Reference implementation: `--verify` against the meteo.tn CSVs in `docs/csv`, and CSV output for any delegation, year or month |
| `../../data/prayer-formula/delegation_params.json` | Per-delegation inputs: INM's coordinates and elevation, plus quirks of INM's own tables. Shared with the Android app, which bundles it as an asset. |
| `multiplatform/shared/.../InmPrayerFormula.kt` | The Kotlin port the Android app computes its prayer times with |

## Validation

Per-delegation parameters were fitted on **2026 only**. Model variants were compared on 2026 plus a 2025 sample, so **2020–2024 are fully held out**. meteo.tn serves 2020–2026. On 2026-09-28, the probed years 2010, 2015, 2018, 2019, 2027 and 2028 returned `data: null`.

| Data | Times checked | Exact match |
|------|---------------|-------------|
| 2026: every delegation and day (`docs/csv`, 258 delegations × 365 days × 6 prayers) | 565,020 | **100 %** |
| 2020–2025: meteo.tn samples (6 random days per delegation per year, plus full years for 2–5 delegations) | 109,042 | **100 %** |
| 2020–2026: every day of every delegation, fetched from meteo.tn | *run in progress* | *pending* |

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
- **Elevation.** Sunrise and sunset shift symmetrically with terrain height, and Fajr and Isha use the same dip. The fitted elevations are realistic (Tunis 9 m, Béja 242 m, Kesra 1005 m).
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

# Print one month, in the same layout as android-app/.../csv/<id>/<year>/<MM>.csv
python scripts/prayer_formula/inm_prayer_times.py --delegation 615 --year 2027 --month 3

# Without --month, all 12 months are printed one after another, each with its own header
python scripts/prayer_formula/inm_prayer_times.py --delegation 615 --year 2027
```

Delegation ids are the same as in `gouvernorats.json` (for example 615 = Tunis). The script uses only the Python standard library.
