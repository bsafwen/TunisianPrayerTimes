#### Djibouti (`dj_fitted`)

`fits/dj_fitted/fit.py` (2026-10-15, a few seconds) prints the checks below. Nothing is fitted and no asset is
written: the plan fixes every value (A3, owner, 2026-10-11) and runs the method at the user's point.

**Method** (the plan's, unchanged; 8.7): MWL, Fajr 18°, Isha 17°, no offsets (Dhuhr at transit, Maghrib at sunset),
Asr factor 1, nearest rounding on all six, standard horizon, MEEUS sun at the event instant, `AngleBased` rule (it
never acts). Evidence `APPROXIMATE`, chip «تقريبية».

**Zones and mapping.** None: exact coordinates (`EXACT`), so no asset.

**Data** (read at run time, never copied). No official table was found (8.1, 12, Q16). For Djibouti,
`test-data-local/research-2026-10-08/SD-SO-DJ-KM` holds `dj.py` (a research script), four Al-Qarn articles
(`alqarn/`, `alqarn25/`: the Directorate of Islamic Affairs' Ramadan 2026 circular unifying the Maghrib and suhur
adhan across the capital and the regions, with no times; the site's prayer-times box is a third-party widget) and the
presidency's page of ministry remits (`attr_fr.txt`): no times to check. The only Djiboutian times are the owner's
mosque dataset (`mosque-dataset/mosques.db`, read-only), evidence and never a source (5.6), which tier C (9.5) checks
the profile against: 6 records on 6 calendars, all around Djibouti City.

**Fit.** None. Windows (9.4) per calendar, as a check of the plan's values: five calendars are on the profile (Fajr
and Isha angle windows at 18.00° and 17.00°, sunrise, Dhuhr and Maghrib at + 0, 355–364 of 365 windows each). The
sixth, filed about 33 km west of the city, is not: at its own point its windows ask for sunrise − 2, Dhuhr, Asr and
Maghrib − 1 and angles of 18.16° and 16.81°, each on only 51–65 % of days, and it carries slips of up to 16 min.

**Check scores** (held out from this script, in-sample for the plan's values, which the plan read from these
calendars, A3): exact / within 1 min (%), worst minute.

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| all six (2,190 days) | 87.6 / 98.4, 3 | 82.2 / 89.8, 8 | 86.5 / 99.7, 4 | 46.0 / 96.4, 16 | 86.9 / 97.5, 11 | 86.5 / 97.4, 5 |
| the five on the profile (1,825 days) | 98.3 / 100, 1 | 98.2 / 100, 1 | 98.0 / 100, 1 | 50.5 / 99.3, 2 | 99.1 / 100, 1 | 98.3 / 100, 1 |

The calendars' Asr strays up to 2 min from the shadow rule, by season; the declination taken late in the day (E4
`KACST`) is exact on 58.3 %, against 46.0 % at the event instant: an app's convention, not a published rule, so not
adopted.

**Tier A.** Not applicable: tier C (9.5), no official data, so no FitStats; chip «تقريبية» (A3).

**Quirk days.** None counted; the sixth calendar's slips stay in its scores.

**Open.** The unified adhan schedule of the Ramadan 2026 circular (Directorate of Islamic Affairs, or the Supreme
Islamic Council, 9.3): a published Maghrib and suhur time would be the first official Djiboutian target.
