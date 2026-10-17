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

## A year from the mosques (plan 9.5, owner 2026-10-17)

Where the official days at hand cover less than a year, a mosque of the owner's dataset that follows the official
table stands in for it: `mosque_years.py` tries every mosque of the country against its three nearest zones' official
days of 2026 and keeps it where it reproduces one (at least 20 days, 98 % of the minutes exact, none more than 1 min
off); per zone, the follower with the most official days is scored on its other days of 2026, as the app computes the
zone, and its year is added to the zone's record as a held-out body (`source` "mosque_follower", which the explainer
shows as «تقويم مسجد يتّبع الجدول الرسمي»). Days a calendar prints on a wrong clock are quirk days. Each fit's
`mosque_year.py` gives it the official days and the profile; run it after `fit.py` (`--write`; it refuses a record
that holds a follower year already). The dataset is read in place, never copied, and mawaqit.net is never fetched.

| Fit | Followers | Year added | Result |
|---|---|---|---|
| `ma_habous` | 195 mosques, 58 localities | 2026 outside Habous's months, quirk days apart | 95–97 % exact per time, all within 1 min; no tier A |
| `ly_awqaf` | 7, Tripoli | 305 days | as printed (A8) 92–97 %, all within 1 min; the other 21 cities have 3 official days, 20 needed |
| `kw_awqaf` | 11 (Jahra, Abdali, Ahmadi) | the 6 untranscribed months, every zone | as printed (A8) 88–93 %, all within 1 min; tier B; their months also confirm the reference year 2011 |
| `om_mara` | 9 (Muscat, 5 other zones) | none since 2026-10-17 | Izki, Barka, Bahla and Saham reached tier A on their 340 days (98–100 %); every zone's whole 2026 is now official (calendar_page2), so no follower day is left, and all 86 zones pass on the ministry's own tables; Dhank's follower sets its own February Asr |
| `diyanet_eu_at` | 1, Vienna (IGGÖ) | January to March | 97.8–100 %, all within 1 min; summer Fajr and Isha stay with the takdir (9.2) |
| `sy_awqaf` | none: one Eastern Ghouta mosque prints Damascus's Ramadan 97.5 %, 2 min once | | |
| `ie_ifi` | Ramadan followers (Dundalk; three in Dublin) | none | Dublin's keep their own Isha from May to August (up to 86 min off IFI's table), so a Ramadan match does not vouch for the year |
| `luxembourg_shoura` | none (97.2 %: their own December sunrise) | | their August Asr showed the print's one-row slip, now 22 quirk days; February was then read from the Shoura's PDF, so the record has all 12 months (README) |
| `bg_grand_mufti`, `eg_egsa`, `mr_islamic_affairs`, `ye_awqaf`, `si_islamska_skupnost`, `dum_rf` | none: the mosques compute their own times (at best 44 %, 83 %, 55 %, 27 %, 6 % and 72 % of the minutes; Egypt on 5 official days) | | Egypt needs none since 2026-10-17: EGSA's monthly view gives every city's 2026, and all 78 pass |
| `qa`, `ps_gaza_awqaf`, `hr_miz_zagreb`, `jo_awqaf`'s further places, North Macedonia's BFI | not tried: no mosque at the waiting places (Qatar's towns, Gaza), or under 20 official days of 2026 | | |
