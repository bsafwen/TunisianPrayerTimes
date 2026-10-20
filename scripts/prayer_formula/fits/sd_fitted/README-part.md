#### Sudan (`sd_fitted`)

`fits/sd_fitted/fit.py` (2026-10-15, a few seconds) prints the checks below. Nothing is fitted and no asset is
written: the plan fixes every value (A3, owner, 2026-10-11) and runs the method at the user's point.

**Method** (the plan's, unchanged; 8.5, 8.7): Fajr 18°, Isha 18°, Dhuhr = transit, Maghrib = sunset, Asr factor 1,
nearest rounding on all six, standard horizon (ICOP's Sudan preset turns elevation off), MEEUS sun at the event
instant, `AngleBased` rule (it never acts). Evidence `APPROXIMATE`, chip «مبنية على أوقات مساجد محلية».

**Zones and mapping.** None: exact coordinates (`EXACT`), so no asset.

**Data** (read at run time, never copied; all evidence, 5.6, tier C of 9.5). The Islamic Fiqh Academy's printed
tables are not online (8.1, 9.3, 12; one more search on 2026-10-15 found none).

- A. The owner's mosque dataset (`mosque-dataset/mosques.db`, read-only): Sudan's 2 records, one in Khartoum and one
  in Bahri, the plan's own basis for 18° / 18° (5.6).
- B. `test-data-local/research-2026-10-08/SD-SO-DJ-KM/sd/web/mashhad.html`: a Sudanese news site's page of
  17 February 2026, "Ramadan 1447 imsakiya … issued by the Islamic Fiqh Academy", 18 state tables (Fajr, Dhuhr, Asr,
  Maghrib, Isha) for 2026-02-18 to 03-16, Khartoum to 03-19 (489 days).
- C. `…/sd/scripts/dhuhr2002.py`: the Dhuhr column, 1 January to 30 March, of the Khartoum State ministry's 2002
  perpetual table, as transcribed there from the SUST master's thesis of 2016 (`…/sust_pdf/thesis.pdf`, pp. 102–103).
- Not machine-readable, quoted only here: `…/sd/web/img/`, a sudaninet.net Khartoum imsakiya for Ramadan 1444 and
  a mosque-supply company's (Al-Awail) list of 15 cities for 1 Ramadan 1444 (2023-03-23).

**Fit.** None. Windows (9.4) per calendar, as a check of the plan's values (angle most windows allow at the event
instant; whole-minute offsets against the raw events):

| Calendar | Fajr angle | Isha angle | Sunrise | Dhuhr | Asr | Maghrib |
|---|---|---|---|---|---|---|
| Khartoum (365 days) | 18.00 (359) | 18.00 (134) | + 0 (360) | + 0 (123) | − 1 (101; + 0: 76) | − 1 (112) |
| Bahri (365 days) | 18.06 (252; 18: 221) | 17.89 (282; 18: 178) | + 0 (228) | + 3 (247; + 0: 0) | + 0 (226) | + 0 (210) |

The Khartoum calendar holds Maghrib at 18:08 on 259 days and Isha at 19:18 on 233 (clock times, not a formula), so its
Dhuhr, Asr, Maghrib and Isha windows say little; its Fajr and sunrise are 18° and the standard sunrise to the minute.
Bahri's calendar is close to 18° / 18° (every Fajr and Isha within 1 min) with Dhuhr + 3, a local setting the plan does
not adopt.

**Check scores** (A; held out from this script, in-sample for the plan's values, which the plan read from these
calendars): exact / within 1 min (%), both calendars, 730 days.

| Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|
| 79.5 / 100 | 80.5 / 99.9 | 16.8 / 19.0 | 41.4 / 81.4 | 29.5 / 66.8 | 42.3 / 69.5 |

**The news site's 2026 imsakiya (B).** Isha is Maghrib + 90 on all 489 rows, Ramadan included. At approximate state
capitals, `sd_fitted` is exact on 10.8–24.7 % per time (Isha never: 25 min at most), and 18.5° with Isha = Maghrib +
90 on 21.9–24.7 %, within 1 min on 56–60 %. The page's points are not the capitals: its Dhuhr runs from 4.6 min before
transit at Kadugli to 8.1 min after it at Port Sudan. Khartoum's table under 18.5° and Maghrib + 90 is exact on 143 of
150 minutes at 15.651 N 32.535 E, about 17 km north of the centre (128 at the centre). So the page is Umm al-Qura's
rule as online calculators apply it (18.5°, Isha = Maghrib + 90 with no Ramadan change), at geocoded points; not a
table the Academy computed for its capitals. A calculator site found on 2026-10-15 (miqatona.com) states that its
Khartoum imsakiya is computed with Umm al-Qura and may differ from the printed official one by a minute or two. Not
adopted; its Dhuhr and Maghrib, + 0 at its own points, agree with the profile.

**The 2002 ministry table (C).** Computed for 2026 at UTC+3, Sudan's clock in 2002, at Khartoum: Dhuhr = transit + 0
is exact on 52 of 89 days (+ 1: 18, + 2: 2), the best shift + 0.05 min. It supports Dhuhr + 0. No daily rule is exact
on all: the excerpt holds 13:04 for 17 days and steps by a minute a day on 12–14 January.

**The 2023 images.** On 1 Ramadan 1444 (2023-03-23) at Khartoum, sudaninet.net printed Fajr 04:29, Maghrib 18:08 and
Isha 19:26, Al-Awail Fajr 04:39 and Maghrib 18:01, and `sd_fitted` gives 04:41, 18:01 and 19:12. The two tables are
10 min apart on Fajr and 7 on Maghrib, as the thesis found Khartoum's printed tables up to 7 min apart (12);
Al-Awail's two minutes are within 2 min of the profile.

**Tier A.** Not applicable: tier C (9.5), no official data, so no FitStats; chip «مبنية على أوقات مساجد محلية» (A3).

**Quirk days.** None counted. The Khartoum calendar's fixed clock times are its own rule, not slips.

**Open.**
- The Islamic Fiqh Academy's current Khartoum table (9.3; the 2015 edition ran out around 2025) and its other
  states' tables: the only target that would make this profile «مبنية على جداول منشورة».
- Whether the Academy uses Isha = Maghrib + 90 in Ramadan, as the news site's page does, or 18° (ICOP's preset, the
  Bahri mosque).
