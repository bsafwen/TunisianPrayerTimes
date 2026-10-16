# Authority fits

The fits of plan 9.4 (Arab authorities, milestone AM2) and of the European tables (M3). Each fit turns an
authority's official tables, kept locally in `test-data-local/` (D13, A7: never committed), into:

1. **Profile values** for the authority's `PrayerMethod` (angles, offsets, rounding per time, horizon model,
   sun model, evaluation instants, revisions), reported in the fit's report; they are copied into
   `multiplatform/shared/.../method/ArabAuthorityProfiles.kt` or `EuropeanPresets.kt`.
2. **An official-zone asset**, `android-app/app/src/main/assets/official-zones/<authority>.json`, schema 1
   below, written with `common.write_asset`.
3. **Fit records** (`FitStats`, plan 6.3) for the profile and for each zone, from held-out data only (9.5).
4. **A README part**, `fits/<authority>/README-part.md`, which goes into the "Arab authorities" part of
   `scripts/prayer_formula/README.md`: the method, its zones and mapping, the data, the scores.

## How a fit works (plan 9.4)

- **Windows, not least squares.** A published minute bounds the unrounded time to a half-open minute
  (shifted by the rounding mode). Invert it into a window per parameter and keep the value most windows cover.
- **Stages.** Dhuhr first (sun model, evaluation, offset, longitude), then sunrise and sunset (horizon, dip,
  latitude), then Fajr and Isha (angle or interval), then Asr (factor, declination instant, geometric or apparent).
- **Grid.** Sun model (MEEUS, VSOP87, TEXTBOOK), evaluation (EVENT_INSTANT, DAILY_0H_UT, DAILY_10H_UT, KACST,
  LOCAL_MIDNIGHT_SHADOW), rounding per time, horizon model; a perpetual table is fitted at its reference year
  (E5) and scored as the app shows it, for the actual year (A8).
- **Held out.** Never score a parameter on the data it was fitted on: another year, or a month-split (fit on
  odd months, score even, and the reverse) where only one year exists.
- **Score with the reference.** Search loops may use fast special-purpose code; the final parameters are
  scored with `common.compute` (the independent reference, which the Kotlin engine matches to the millisecond).
- **Quirk days** (documented anomalies of a table, plan 8.3) are counted apart and never lower a score.

## The asset (schema 1)

```json
{
 "schema": 1,
 "authority": "om_mara",
 "methodId": "om_mara",
 "country": "OM",
 "mapping": "NEAREST",
 "byAdmin1": false,
 "namedFirst": true,
 "source": "Oman's Ministry of Awqaf, 86 zone tables (2026)",
 "fit": { FitStats of the profile },
 "zones": [
  {
   "id": "0",
   "nameAr": "مسقط",
   "point": {"latE5": 2358800, "lngE5": 5838290},
   "morningPoint": null, "eveningPoint": null,
   "elevationM": null, "eveningElevationM": null,
   "pressureHpa": null, "temperatureC": null,
   "sunriseAltitudeDeg": null, "sunsetAltitudeDeg": null,
   "offsets": {"FAJR": 1.0},
   "baseZoneId": null, "offsetRule": "PUBLISHED", "halfMonthWindows": false,
   "terrainClass": null, "admin1": [], "admin2": [],
   "namedPlaces": [{"centre": {"latE5": 2358800, "lngE5": 5838290}, "catalogueIds": [], "areaKm": 10.0}],
   "reachKm": null, "mappingTier": 0, "switchDays": {},
   "fit": { FitStats of the zone }
  }
 ]
}
```

Fields left at their defaults may be omitted. `mapping` is one of `NEAREST`, `NEAREST_LOCAL`,
`LISTED_PLACES`, `DISTRICT`, `NATIONAL`, `NAMED_REGIONS`, `ADMIN_AREA`, `EXACT` (plan 6.5); `byAdmin1` limits
the candidates to the place's province (MR, DZ, AE, JO), `namedFirst` gives a place that is one of a zone's
named places that zone first (EG, OM, QA), `terrainClassKm` is Yemen's 60 km of E10. `offsetRule` is
`PUBLISHED`, `MONTHLY_DIFFERENCE` (Jordan), `WINDOW_CEILING` (Algeria) or `TAKVIM` (Bosnia). The catalogue ids
of named places are filled by `scripts/places/match_named_places.py` once the place catalogue is built (M5): a fit
writes none, so run it again after any fit that rewrites an asset.

## FitStats

`common.fit_stats` builds it: `fittedOn` and `heldOut` samples (`source`, `from`, `to`, `days`, `zones`,
`monthSplit`), per time the held-out share exact and within 1 minute, the held-out days per calendar month,
`heldOutRamadanDays`, `quirkDays`, `maxDiffMin`, and, for `sa_umm_al_qura`, the held-out `regions`. The
phone decides the chip from it (`FitStats.passesTierA`, 9.5).
