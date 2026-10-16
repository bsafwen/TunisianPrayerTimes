### Martinique: the CCIM (`mq_ccim`)

Fitted by `fits/mq_ccim/fit.py` (2026-10-15, a few seconds). No asset: the CCIM names a method and links a page that
computes it for any point, so the app computes at the user's point (7.6).

**Method** (from the page the CCIM links): the Muslim World League's 18° / 17°, Asr factor 1, Dhuhr + 1 and Maghrib + 1,
UP on the five prayers and DOWN on sunrise, and **the sun taken once a day at 0h UT** (E4 `DAILY_0H_UT`, for all six
times). One evaluation for all six times is the fit's stage 0: daily 0h UT covers 169 of 186 windows, KACST 153,
daily 10 h UT 152, the event instant 144; both halves of the day-split choose it (88 against at most 79, and 81
against 77). With it, Isha follows the page on 29 days of 31 instead of 12, and Asr on 29 instead of 20. The angle
the windows allow at the event instant (17.13–17.16° for Isha) is that evaluation seen through the wrong sun. The
minute IslamicFinder's live pages add to Dhuhr and Maghrib stays (+ 1 on both, up, in both halves).

**Data** (test-data-local, read at run time, never copied): `research-2026-10-11/FR/mq_islamicfinder_fdf_oct2026.csv`,
IslamicFinder's Fort-de-France page for October 2026 (31 days), at Fort-de-France's GeoNames point (14.604 N 61.074 W,
read 2026-10-15). A point moved up to 17 km does not explain the page with the sun at the event instant (163 windows
at best).

**Held out** (one month, so a day-split: odd days scored with the rules fitted on the even ones, and the reverse;
31 days): exact / within 1 min:

| Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|
| 87.1 / 100 % | 96.8 / 100 % | 80.6 / 100 % | 93.5 / 100 % | 93.5 / 100 % | 93.5 / 100 % | 1 min |

- The preset before the fit (the same rules with the sun at the event instant): 87.1, 90.3, 71.0, 64.5, 38.7 and
  38.7 % exact, all within 1. Plain `mwl`: Maghrib never within 1, up to 3 min off.

**Open.** One month, one place. The Guiana calendars (`gf_a2cmgf`, January) fit the event instant, so the two
IslamicFinder sources differ, or the season matters: another month of the Fort-de-France page would settle the
evaluation. The Friday time (13:00) is in `SilenceDefaults`, not here.
