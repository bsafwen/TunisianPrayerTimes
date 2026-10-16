### Diyanet's takdir from Diyanet's own tables (`diyanet_takdir`, H6)

Re-derived by `fits/diyanet_takdir/fit.py` (2026-10-16; 10 s, 3 min with `--engine --austria`) from Diyanet's 2027
tables across Europe and emulated by `fits/diyanet_takdir/rule.py`; the data come from `fits/diyanet_takdir/collect.py`.
The engine implements it since 2026-10-16 (README H6, and H2's clamp on the published times and held night:
`DiyanetTakdir.kt` and `Season.ends`, the reference's `takdir_table` and `DaySteps._clamp`), replacing the k·D rule
fitted on IGGÖ's nine tables alone (the former `fits/diyanet_takdir.py`); `fit.py --engine` scores the reference, which
the Kotlin engine equals on the cross-check's cases.

**The source.** namazvakitleri.diyanet.gov.tr, as `fits/diyanet_tr/diyanet_site.py` describes it; the country ids come
from the site's country menu, read from a saved page. `collect.py lists` saved the lists of 24 countries (Germany's 16
states: 1,241 German entries, 587 French, 283 Dutch, 221 Belgian, 165 Swedish ...); `select` matched every entry by name
to GeoNames' cities500 and chose 133 (per band of latitude one entry west of 8 E and one east of it where both exist,
the countries asked for first: two per quarter degree from 44 to 56 N, two per half degree to 60 N, one or two per degree
beyond), `extra` 11 more between 44.2 and 44.6 N for the cutoff, and `pages` fetched them: DE 30, FR 27, SE 16, IT 15,
NL 11, DK 11, GB 9, NO 8, CH 6, BE 5, RO 3, FI 1, IS 1, SJ 1 (Svalbard), 43.2-78.2 N. 185 requests at 3 s (41 lists,
144 pages), 4.4 MB gzipped, no block page; saved under `test-data-local/official/<cc>/diyanet/` (`lists/`, `pages/`,
`MANIFEST.tsv`), the selection in `research-2026-10-16/diyanet_takdir/selection.tsv`. Diyanet's Austrian pages
(`official/at/`, collected for `diyanet_eu_at`; 190 without SANKT VEIT AN DER GOLSEN's summer-time slip) are a second,
wholly held-out set, at the asset's fitted points.

**The points.** Each sampled entry's point is fitted as Turkey's are (`fits/diyanet_tr/points.py`, 1/400 degree) on the
sunrise, Dhuhr, Asr and Maghrib of 2027 whose day and night both last more than 6.4 h, never on Fajr or Isha: the fitted
point lies a median 1.2 km from the entry's town (HORSENS (Aalborg) is computed at Aalborg). On November-January every
entry is 18 deg / 16 deg (Isha 16 deg exact on 98 % of winter minutes, 15.5 and 16.5 on none), Svalbard included.

**Diyanet's method.** The International Prayer Times Congress of Diyanet and the European Council for Fatwa and
Research (Istanbul, September 2021; applied in Europe from 2023) set the criteria and Diyanet's awqatsalah service
publishes them («Vakit Tespit Kriterleri»): the real Isha (16 deg) is estimated when it falls after one third of the
sharia night, as Maghrib plus that third; the estimated imsak is sunrise minus the third and two sixteenths of it (the
2 degrees from 16 to 18), used when the real imsak (18 deg) is earlier; where the real imsak does not form, the share of
the night of the last day it formed is kept; the switch is gradual, starting 20 minutes away from the estimated time
(21 June where the imsak forms all year); the takdir starts at about 44.5 N; sunrise and Maghrib keep a day and a night
of at least 5 hours (Dhuhr -/+ 2.5 h); takdir times must not run against the sun's natural movement (imsak and sunrise
never earlier, Maghrib and Isha never later than on 21 June). The tables apply it as follows (`rule.py`); every
quantity is the date's own, the sun once a day at 0 h UT of the date (E4 `DAILY_0H_UT`):

```
F(x), I(x)   the plain 18 deg Fajr and 16 deg Isha of date x (17 deg for diyanet_tr)
R(x), M(x)   the published sunrise and Maghrib: min(max(sunrise - 7, D - 9.5 h), D - 2.5 h) and
             min(max(sunset + 7, D + 2.5 h), D + 9.5 h), D = Dhuhr = transit + 5 min (no sunrise: D -/+ 2.5 h in a
             polar night, D -/+ 9.5 h under a midnight sun)
S(x)         F(x) + 24 h - M(x)       the date's sharia night: its Maghrib to its own imsak, a day later
N(x)         R(x) + 24 h - M(x)       the date's night
TI(x)        M(x) + S(x) / 3          the one-third Isha
TF(x)        R(x) - k S(x) / 3        the one-third imsak, k = 18/16 (Fajr angle / Isha angle)

no takdir    south of 44.375 N, the southern hemisphere included (below)
case A       F on every date (to 48.56 N): anchors A_F = TF and A_I = TI of 21 June, both sides on 21 June
case B       no F on the dates xa..xb: r = S(dl) / 3 / N(dl), dl = xa - 1 the last date with a real imsak;
             on xa..xb  Fajr = R - k r N,  Isha = M + r N  (the same r after 21 June);
             anchors: those values on xa (spring side) and xb (autumn side)
transition   each side of an anchor day a: the n dates next to a whose plain time is past A + 20 min (Fajr earlier
             than A_F + 20, Isha later than A_I - 20; no angle counts as past); on the date i days from a
             (1 <= i <= n): A + 20 i / n (A_I - 20 i / n), the later Fajr (earlier Isha) of that line and the plain
             time; on a, A
held night   where both ends of 21 June's night are held (sunrise - 7 before D - 9.5 h and sunset + 7 after
             D + 9.5 h, from 60.2 N): R >= R(21 June), M <= M(21 June), M never earlier again before 21 June and
             R never earlier again after it; Fajr >= Fajr(21 June), Isha <= Isha(21 June), and in case B the Isha never
             earlier again before 21 June and the Fajr never earlier again after it
```

**How the tables show it.**

- *21 June.* M + S/3 and R − (9/8)·S/3, S measured from the published Maghrib (sunset + 7) to the 18 deg imsak, give the
  published 21 June Isha and Fajr within 0.5 min at 33 and 34 of the 36 entries from 44.42 to 48.40 N (within 0.6 at
  the rest); from sunset instead they are 2-3 min off.
- *The transition.* The lines every published minute allows (linear programming on the rounded minutes) run from
  A -/+ 20 on the first takdir day (the first date whose plain time is past it) to A on the anchor day, on both sides,
  at every entry: the season length is set by the 20 minutes, not by a date or a duration; there is no minimum length
  (Tromsø's autumn Isha takes 16 days, Genova's spring Isha 21).
- *Same-date nights.* With each date's own Fajr, sunrise and Maghrib, the frozen share reproduces 95.6 % of the minutes
  of the no-night periods (48.6-60 N); with the night from the evening to the next morning, 67 %. The share the
  published minutes imply agrees with S/3/N of the last date with a real imsak within 2e-4 at most entries (more where
  D − 18 is under 0.005 deg on that date), before and after 21 June, and it is the same for Fajr and Isha with the
  factor 18/16.
- *The edge day.* Near the edge the 18 deg Fajr is hypersensitive (when D − 18 is 0.01 deg, a 0.001 deg change moves it
  by minutes), so neighbours differ widely (Bastogne r = 0.169, Mainz 0.181) and a point within half a grid step of the
  edge flips the last-imsak day: Harstad's fitted point (D − 18 = 0.0006 deg on 29 March) gives 30 of 245 Fajr minutes
  exact, the next grid cell north (+0.0025 deg) 244, its other times unchanged (825 against 824 exact minutes).
- *The 5-hour rule.* It acts per event on the published times (Uppsala's sunrise is held at Dhuhr − 9.5 h from 10 June
  while its Maghrib is not), and where both ends of 21 June's night are held the sunrise is kept at 21 June's from
  1 June (Gävle), the Maghrib at 21 June's after it, and the takdir Fajr at 21 June's (Gävle's 02:25 from 28 May to
  23 June, where the frozen share alone gives 02:19); at Longyearbyen (78 N) Maghrib and Isha also hold their late
  April values and the Fajr its late July one as Dhuhr drifts with the equation of time. Neither bound applies where
  21 June's night is longer (Paris keeps its 15-18 June sunrise, a minute earlier than 21 June's).
- *The cutoff.* Rodez (44.365 N) and Imola (44.360) have no takdir, Cuneo (44.385) and Genova (44.418) do; the real
  21 June Isha passes the one-third Isha only from 44.39 N (Cuneo's falls 0.06 min short), so the cutoff is a latitude,
  between 44.365 and 44.385 N, which the criteria round to 44.5.

**Held out.** Fajr and Isha of 2027, exact / within 1 min / worst (min), the former engine (the k·D rule, before
2026-10-16) against the rule; the sample's odd latitude ranks (72 entries) and Diyanet's 190 Austrian tables:

| Held out | Engine, takdir days | Rule, takdir days | Engine, all 2027 | Rule, all 2027 |
|---|---|---|---|---|
| 43.0-44.4 N | - | - | 98.3/100 % (1) | 98.3/100 % (1) |
| 44.4-46.0 | 17/37 %, 9/32 % (14) | 100/100 %, 99.4/100 % (1) | 83.1/88.5 %, 84.1/89.3 % | 98.8/100 %, 98.6/100 % (1) |
| 46.0-48.56 | 8/26 %, 7/23 % (11) | 97.6/100 %, 99.1/100 % (1) | 73.9/80.0 %, 77.3/82.4 % | 98.1/100 %, 98.4/100 % (1) |
| 48.56-52 | 1-12 / 3-43 % (14) | 97.5-98.8/100 % (1) | 65-71 / 70-82 % | 98.1-98.5/100 % (1) |
| 52-56 | 8-18 / 30-54 % (16) | 95.2-97.3/100 % (1) | 38-71 / 51-84 % | 97.0-97.9/100 % (1) |
| 56-60 | 7-17 / 21-28 % (27) | 94.8-96.3/100 % (1) | 3-61 / 10-69 % | 96.2-97.1/100 % (1) |
| 60-66.6 | 4-9 / 11-20 % (77) | 87.3-98.3/100 % (1) | 2-54 / 6-59 % | 92.4-98.5/100 % (1) |
| 66.6-80 | 2-3 / 7-9 % (160) | 90.3/98.1 %, 93.1/99.3 % (6) | 3.2/5.9 %, 39.9/44.8 % (181) | 92.4/98.4 %, 94.6/99.5 % (36) |
| all 72 | 9.7/26.2 %, 8.3/27.3 % (160) | 96.2/99.8 %, 96.1/99.9 % (6) | 53.8/61.0 %, 70.5/77.8 % (181) | 97.5/99.9 %, 97.5/100 % (36) |
| Austria (190) | 6.6/22.7 %, 4.8/15.5 % (16) | 96.8/100 %, 97.9/100 % (1) | 72.8/78.6 %, 75.6/79.7 % (16) | 98.0/100 %, 98.2/100 % (1) |

(two figures: Fajr, Isha; takdir days: the published minute is not the plain angle time's.) On March-October the rule
is 97.4 / 97.3 % exact and 99.9 / 100 % within 1 min (the engine 47.4 / 57.1 %). The held-out misses over 1 min are
Longyearbyen's autumn (below) and one Narvik Isha (2 min); Harstad, in the fit half, needs its point refined as above.
The only constant taken from the data is the cutoff, placed between Rodez (held out) and Cuneo (fit half). On the fit
half (72 entries, March-October) a cutoff by the 21 June Isha costs 3 points at 43-44.4 N (Cuneo), the 21 June caps
everywhere 1-3 points from 48.6 to 60 N, no caps 14-23 points north of 60 N, a 20-day minimum transition 2-8 points
north of 58 N; dropping the holds changes the far north only.

**The 5-hour rule (H2).** Sunrise and Maghrib of 2027 from 58 N (exact / within 1 min, worst): the former H2
(c = T + 5 min on the raw events, the offsets added after) 72.7 / 74.3 % and 73.2 / 74.7 % (13 and 12 min; 48 % north
of the Arctic circle), the rule's R and M 98.7 / 100 % and 98.6 / 100 % (1 min), polar nights included.

**The engine now** (`fit.py --engine`, 2026-10-16) gives the rule's figures in every band (Austria 96.8 / 97.9 % exact on
the takdir days and 98.0 / 98.2 % on the year, all within 1 min; the held-out half 97.2 / 97.5 % on the year, Isha
100 % within 1 min; sunrise and Maghrib from 58 N 98.7 / 98.6 %, all within 1 min), but for mainland France, where E19
(S10) keeps every Fajr no later than the 12° instant: in the held-out half's 48.56–50 N band its Fajr is 86.6 % exact on
the takdir days (the rule 98.8 %), up to 11 min earlier than Diyanet's, which leaves that bound in June.

**The southern hemisphere.** `collect.py extra` added Diyanet's four entries south of 44.375 S (its lists of New
Zealand, Chile and Argentina, 2026-10-16): Ushuaia, Punta Arenas, Rio Gallegos and Dunedin (45.9–54.8 S, points
fitted as above, 0.9–2.4 km from their towns). From 16 October 2026 to the end of 2027 their tables keep the plain
angles all year, with no takdir: the 18° Fajr is within 1 min on every printed day (97.3–99.4 % exact), and the Isha
is 17°, not 16° (97.5–98.9 % exact, all within 1 min; 16° matches no minute): Diyanet's setting outside Europe, which
`diyanet_tr` carries. Where the angle never forms, Diyanet
prints 00:00 (Ushuaia 164 times, from 11 November to early February), with no time; the engine keeps the angle times
there and gives an absent one D5's α/60 of the night (H6). `fit.py` checks this apart (step 4).

**Open.**
1. Longyearbyen (78.2 N), the only entry so far north: its autumn transitions last 20 days (1 min a day) where the
   20 minutes give 27 (Fajr) and 22 (Isha), and the plain times resume on 30 October although still 36 and 5 min
   past the line (Fajr 2-6 min, Isha up to 10 min off from 13 to 29 October). Unexplained; the DYK's 2009 decision
   («62. enlemin ötesinde 62. enlemin vakitleri») is not what the tables do.
2. The edge-day sensitivity: north of 48.56 N a zone's point should be refined with its summer Fajr and Isha, which pin
   the latitude far better than the other times (Harstad). `diyanet_eu_at`'s entries from 48.56 N, fitted without
   their takdir days, are 73.4 % (Fajr) and 80.7 % (Isha) exact on those days, all within 1 min.
3. One year (2027): the 2028 tables (from about September 2027) to hold it out, a leap year included (21 June is taken
   as the calendar date).
4. `diyanet_tr` (17 deg): k = 18/17 by the criteria («the two-degree difference» is the Fajr minus the Isha angle), not
   observed (Turkey lies south of 42.1 N); `bg_grand_mufti` (18.2 / 17.2) lies south of the cutoff.

The southern hemisphere, open in the first version of this record, is settled above: Diyanet's tables there keep the
plain angles.
