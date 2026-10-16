### French Guiana: A2CMGF (`gf_a2cmgf`)

Checked by `fits/gf_a2cmgf/fit.py` (2026-10-15, a few seconds). No asset: the method runs at the user's point
anywhere in Guiana (7.6), as A2CMGF's two towns' calendars do (they differ by a minute on some days).

**Method** (published, unchanged): the Muslim World League's 18° / 17° that A2CMGF's calendars state, Asr factor 1,
no offsets, UP on the five prayers and DOWN on sunrise, the sun at the event instant. The fit finds the same rules on
each January calendar alone and on both: one evaluation for all six times (E4; on both calendars the event instant
covers 349 windows, daily 10 h UT 346, KACST 338, daily 0 h UT 307), then each time's rounding and offset. On each
town's calendar the angle the windows allow by itself, rounded up, is 17.97–17.99° for Fajr and 16.97–16.99° for Isha.

**Data** (test-data-local/research-2026-10-11/FR, read at run time, never copied): `a2_cayenne_jan2026.csv` and
`a2_remire_jan2026.csv` (A2CMGF's January 2026 calendars, IslamicFinder pages it posts), `a2_masjidbox_oct2026.csv`
(the Cayenne mosque's Masjidbox page, 10–15 October 2026), `gf_islamicfinder_cayenne_oct2026.csv` (IslamicFinder's live
page, scored apart). Each at its town's GeoNames point (Cayenne 4.938 N 52.335 W, Rémire-Montjoly 4.917 N 52.267 W,
read 2026-10-15). `cayenne_table.csv` (the mosque's 2010 table, no longer followed, Q35) is not used.

**Held out** (each January calendar with the rules fitted on the other town's; the Masjidbox days with the rules
fitted on both; 68 days): exact / within 1 min:

| Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|
| 91.2 / 100 % | 97.1 / 100 % | 100 / 100 % | 85.3 / 100 % | 97.1 / 100 % | 92.6 / 100 % | 1 min |

- January alone: Fajr 90.3 %, sunrise 96.8 %, Dhuhr 100 %, Asr 87.1 %, Maghrib 96.8 %, Isha 91.9 % exact (9.2 asks
  85 %). The Masjidbox page: 6 of 6 on every time but Asr (4).
- The live IslamicFinder page: Dhuhr and Maghrib 1 min later on 6 of 6 days, as 7.6 says; that minute is not adopted.

**Open.** Asr is the weakest time. `KACST` alone on Asr gives both January calendars' Asr on 58 of 62 days and the
Masjidbox page's on 6 of 6 (the event instant: 54 and 4), but on Asr alone January's best is daily 10 h UT at
Cayenne (31 of 31) and `KACST` with the apparent sun at Rémire-Montjoly (30 of 31): one month cannot choose, so it
is not adopted; a summer month of A2CMGF's calendars would settle it. No table was read for Kourou or
Saint-Laurent-du-Maroni (Q35).
