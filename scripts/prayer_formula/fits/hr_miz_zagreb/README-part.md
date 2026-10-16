### Croatia: MIZ Zagreb's vaktija (`hr_miz_zagreb`)

Fitted by `fits/hr_miz_zagreb/fit.py` (2026-10-16, a few seconds); asset `official-zones/hr_miz_zagreb.json`, one zone
«زغرب», mapping `NATIONAL`, country `HR`.

**The sheets.** MIZ Zagreb's «Zagrebačka vaktija» for the Zagreb area, two monthly sheets saved as images
(islamska-zajednica.hr) and transcribed by eye on 2026-10-16 into `zagreb_sheets.json` (the Ramadan rows equal the
research transcription of 2026-10-11 on all 29 days; every column steps smoothly):

- **Ramadan 1446, 1–29 March 2025** (CET): Isha is Maghrib + 80 on all 29 days. These are today's rules: the
  Islamic Cultural Centre Zagreb's 2026 calendar (the mosque dataset, support) equals the sheet on 28 of 29 days (the
  29th is the summer-time day) and keeps Isha = Maghrib + 80 all year.
- **September 2021** (CEST): older rules. Isha is 86–92 min after Maghrib (a 16.8° angle: 26 of 30 days), and fitted on
  its own the sheet gives Asr − 1 and Maghrib + 6 with the sun at the event instant.

Both sheets are hand-smoothed in places: from 22 to 28 March 2025 the printed sunrise falls 8 min while the sun's
falls about 11 (it lags by up to 4 min on 26–28 March, then drops 3 min on the 29th).

**Method** (stages of 9.4 by windows on the 2025 sheet, from the preset at Zagreb's centre; an alternative must cover
more than 0.5 % and at least 2 more windows; with one month, one evaluation for all six; each sun model of E14):

- The **TEXTBOOK** sun (111 windows over five times, the others 104), once a day at **10 h UT** (E4 `DAILY_10H_UT`,
  82 windows at stage 0; `KACST` 78, the event instant 67, 0 h UT 54).
- Fajr **19.3°**, rounded **up** (17 of 29); sunrise − 8, **up** (19); Dhuhr + 1, nearest (26); Asr factor 1, + 0,
  nearest (22); Maghrib + 5, **up** (27); Isha **Maghrib + 80** (rounded as Maghrib).
- The point: Zagreb's latitude, 15.90158°E from Dhuhr (5.9 km west of the centre), as the E6 morning and evening point.
  The latitude counts both sheets' sunrise, Maghrib and Fajr (each sheet with its own roundings and offsets) but does
  not move: the halves disagree (45.814 and 46.034°N); March alone wanted 45.61°N, which the 2021 sheet and the
  mosque's year both reject (sunrise 53 against 43 % exact on the 2021 sheet).
- **Summer form** (7.4): `SummerForm(3, 25, 9, 1, fajrBeforeSunrise = 113)`. The mosque's calendar (support) prints
  Fajr at its sunrise − 105 from late March and the angle time again from 2 September (106 min), as the September 2021
  sheet does on 1–3 September; under the fitted profile the angle time needs the bound from 3–4 April to 7–8 September
  in 2026–2029, so the window ends on 1 September, and any start from 19 January to 3 April gives the same times: the
  plan's 25 March stays. 113 is the printed 105 in the engine's terms (105 + Fajr's 0 − sunrise's −8).

**Held out** (alternate days of the 2025 sheet: the stages refitted on the odd days score the even ones, and the
reverse; common.compute), exact / within 1 min:

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|---|
| Zagreb, March 2025 | 41.4 / 79.3 % | 58.6 / 79.3 % | 89.7 / 100 % | 69.0 / 100 % | 69.0 / 100 % | 69.0 / 100 % | 5 min |

- In-sample: 48.3 / 65.5 / 89.7 / 75.9 / 93.1 / 93.1 % exact; Fajr and sunrise 79.3 % within 1 (the late-March lag).
- The preset before this fit: Fajr 6.9 % exact and 41.4 % within 1, 5 min at worst.
- The September 2021 sheet with today's rules: sunrise 96.7 % and Maghrib 100 % within 1, Fajr 73.3 %, Dhuhr 90 %, Asr
  and Isha off (the older rules).
- Support (5.6), the mosque's year read as 2026, never counted in the record: Dhuhr 99.2 %, Maghrib and Isha 90.1 %,
  Fajr 78.1 %, sunrise 74.5 %, Asr 55.3 % within 1. MIZ adjusts its vaktija month by month: its Asr runs about 3 min
  later from April to August than in winter, its Maghrib 4.0 to 7.7 min after sunset by month, its July sunrise 1.3 and
  2.8 min later than June's and August's; no single parameter set follows that.
- One month: no record passes 9.5's coverage.

**Open.** 1. MIZ Zagreb's other monthly sheets (its site posts them as images): a year of them would allow seasonal
offsets (E18), as for `xk_bik`, and the summer form's dates from the official sheets. 2. Zagreb's area: `NATIONAL` gives
the zone to every Croatian place that takes `hr_miz_zagreb`; the plan wants it only where Zagreb is the nearest of the
Takvim's five Croatian towns (the default's rule in `CountryMethodDefaults`), the method at the user's point elsewhere.
