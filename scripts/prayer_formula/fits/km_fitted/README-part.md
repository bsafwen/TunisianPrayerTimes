#### The Comoros (`km_fitted`)

`fits/km_fitted/fit.py` (2026-10-15, about 10 s) writes `official-zones/km_fitted.json` and prints the checks below.
Nothing is fitted: the plan fixes every value (A3, owner, 2026-10-11), and no official or printed table is at hand
(Q16).

**Method** (the plan's, unchanged; 8.7, E15). MEEUS sun, standard horizon, `AngleBased` rule (it never acts).

| Time | Rule | Rounding (E1) |
|---|---|---|
| Fajr | the zone's sunrise (Moroni's) − 73 | nearest |
| Sunrise | at the user's own point (`sunriseAtPlace`, 6.5) | nearest |
| Dhuhr | transit + 4 | nearest |
| Asr | Maghrib − 168 (E15 `BeforeMaghrib`) | Maghrib's |
| Maghrib | sunset + 3 | nearest |
| Isha | Maghrib + 74 | Maghrib's |

Maghrib − 168 comes up to 18.7 min before the shadow-factor-1 Asr at Moroni (21 June; before it from 22 March to
20 September) and up to 24.2 min after it (24 October), as E15 and 6.5 state.

**Zone and mapping.** One zone, `moroni`, «موروني», at Moroni's point (−11.70, 43.25; plan 8.7), mapped `NATIONAL`
for every place in the Comoros, with `sunriseAtPlace`: Fajr, Dhuhr, Asr, Maghrib and Isha are Moroni's on all three
islands, the sunrise row the user's own. No named places and no FitStats. The zone line reads «الجدول المعتمد:
موروني» (6.5, 6.11). Validity guard (side check, 2026, at nine approximate island ends): it never acts. The table's
own margin at Moroni is + 0.5 min on every time; the largest lead elsewhere is + 0.9 min (Ngazidja's north tip), and
on Mwali and Nzwani every lead is about 0 or negative (Moroni's times come 2.4–4.7 min after Nzwani's own).

**Data.** `test-data-local/research-2026-10-08/SD-SO-DJ-KM/km` holds `pt.py`, a research script, and no table. The
table is known only from the owner's mosque dataset (`mosque-dataset/mosques.db`, read-only at run time), which is
evidence, never a source (5.6); tier C (9.5) checks the profile against it. Of its 25 KM-coded records, 24 lie in the
Comoros; the one east of 44.8° E lies in Mayotte and is left out of the check.

**The table in the dataset.** A calendar follows the table when its Dhuhr and Asr are within 2 min of the formula, or
of the formula − 3, on at least 85 % of days: 16 of the 24 Comorian records, on 9 distinct calendars, as 5.6 counts:

- the table: 12 records on 7 calendars, on all three islands;
- the table with Maghrib 3 min earlier (at sunset) and Isha 2 min earlier, the rest unchanged: 2 records on 1
  calendar (Nzwani); the KM-coded record in Mayotte follows this variant too;
- the table − 3, Mayotte's version (7.6): 2 records on 1 calendar (Nzwani).

Many calendars carry a sunrise column that is no sunrise (unchanged for weeks); 6 records show one within 3 min of
their own point's sunrise on 90 % of days (5 on Ngazidja, and Fomboni on Mwali, which shares a Ngazidja calendar), and
the sunrise row is scored on those.

**Fit.** None. A windows check (9.4) on the table's 7 calendars confirms the plan's minutes: each is the whole minute
most windows cover, on all days and on each half of a month-split (odd and even months).

| Parameter | Plan | Windows covering it (of 2,555) | Next best | Best plateau |
|---|---|---|---|---|
| Fajr, before sunrise | 73 | 757 | 72 (606) | 72.75 |
| Dhuhr, after transit | 4 | 1,010 | 3 (780) | 3.57 |
| Maghrib, after sunset | 3 | 966 | 4 (558) | 2.99 |
| Isha, after Maghrib | 74 | 878 | 75 (630) | 74.03 |
| Asr, before Maghrib | 168 | 914 | 167 (564) | 167.96 |

Rounding and sun, a check: Dhuhr DOWN would be exact on 40.9 % (+ 1.3 points), UP on 29.6 %; VSOP87 moves each time by
0.8 point at most. The plan's nearest rounding and MEEUS stay.

**Check scores** (tier C). Held out from this script, which fits nothing; for the plan's values they are in-sample,
since the plan read them from these calendars (A3). Exact / within 1 / within 2 min (%), each distinct calendar once,
365 days each (2026, 29 February dropped), quirk days left out:

| Calendars | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| the table, 7 | 29.6 / 70.8 / 92.4 | 43.6 / 80.5 / 95.7 (6 records) | 39.6 / 86.3 / 97.4 | 35.8 / 79.0 / 93.8 | 37.8 / 80.3 / 94.2 | 34.4 / 77.9 / 93.1 |
| Maghrib at sunset, 1 | 30.4 / 70.7 / 91.5 | — | 42.9 / 85.7 / 94.5 | 35.8 / 79.3 / 93.4 | 4.7 / 12.1 / 31.2 | 6.8 / 30.7 / 68.5 |
| the table − 3, 1 | 5.2 / 23.6 / 44.9 | — | 0.3 / 1.9 / 18.9 | 5.2 / 12.6 / 32.6 | 5.2 / 12.6 / 41.4 | 5.2 / 12.9 / 40.8 |

The table holds its values in 5-day blocks (days 1–5, 6–10, …, 26 to the month's end), so the daily formula is exact on
only a third of its days and within 2 min on 92–97 %, as 5.6 and 12 say. The worst is 4 min (Dhuhr 5), on days where
the printed table leaves its own blocks: its Fajr is 4 min early on 3–5 December in most copies, and two copies have
Dhuhr 4–6 min early on 16–20 December. Days per month on the table's calendars: 196–217.

**Tier A.** Not applicable: tier C (9.5), no official data, so no FitStats; the chip stays «مستخلصة من جداول» (A3).

**Quirk days** (a published minute more than 5 min from the formula and from its calendar's own median difference for
that time, counted apart): 8 on the table's calendars. Typing slips: an Asr 4 h off and one 33 min off (31 August and
30 September, in two copies), a Maghrib 10 min off (7 January, in two copies); and the far end of December's
departure (Dhuhr 6 min early on 20 December, in two copies).

**Mayotte.** `yt_traditional` stays consistent with this profile: its asset's zone is at the same point, and its
profile (Fajr 76, Dhuhr + 1, Maghrib + 0, Isha 74, Asr 168) gives exactly `km_fitted` − 3 on all five prayers on all
365 days of 2026. Nothing needs changing there.

**Open.**
- A printed copy of the Moroni table and who prints it (Q16). Its own 5-day values would become the test target
  (9.5) and settle Moroni's point, its rounding (the windows centre Dhuhr at + 3.57: perhaps a truncated + 4) and the
  two variants.
- The sunrise at the user's point is the owner's choice (A3); the dataset can hardly test it. The 6 records scored
  are on Ngazidja, within 0.2° of Moroni's longitude, except Fomboni (Mwali), which shares a Ngazidja calendar whose
  sunrise is Moroni's; the Nzwani calendars carry no real sunrise, except the table − 3 one, whose sunrise is
  Moroni's − 3.
- The reference engine has no `sunriseAtPlace` (the Kotlin engine has it), so the script computes the sunrise row with
  the method at the place's own point, as the Kotlin engine does.
