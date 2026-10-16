#### Algeria (`dz_marw`)

Fitted 2026-10-15 by `fits/dz_marw/fit.py` (standard library, about 2 minutes), which writes
`official-zones/dz_marw.json`. Data, read locally and never copied (D13): `test-data-local/research-2026-10-11/DZ/`
`refs_ocr.csv` (the reference tables of the ministry's `Alger.pdf` 1447 and 1448, `Djelfa.pdf` and `Adrar.pdf` 1448;
only the printed minute is read), `offsets/*.csv` (the 48 monthly offset grids) and `places.csv` (names, town points);
the PDFs are in `research-2026-10-08/DZ/dz_data/`. Wilaya codes: GeoNames `admin1CodesASCII.txt` (read 2026-10-15).

**Method** (8.2, unchanged): 18° / 17°, Dhuhr at transit, Asr factor 1 on the signed noon shadow (E15) with δ at
transit, Maghrib = sunset + 3, all six rounded up (E1), the MEEUS sun at each event's instant, standard horizons, no dip.
AngleBased never acts in Algeria. The windows, fitted on each half of the year apart:

- Dhuhr: with MEEUS at the event instant one longitude holds 353–354 of 355 days per reference (354 of 354 in 1447);
  VSOP87 347–351, the daily evaluations at most 351, TEXTBOOK at most 306.
- Pooled over the three references: Fajr 17.996–18.000°, Isha 17.000–17.009°, Asr factor 0.9995–1.0000, Maghrib
  2.965–3.000 min after sunset, sunrise horizon −0.840° to −0.833°; under NEAREST every time needs +0.45 to +0.55 min
  (Maghrib +3.46 to +3.50): rounded up. Asr exact on 992 of 1,065 days with δ at transit, against 984 (10:00 UT), 964
  (KACST), 791 (0:00 UT) and 643 (00:00 local). Djelfa (1,140 m): 336 of 355 sunrises exact at sea level, none with the
  dip. Every missed reference minute lies within 10 s of the printed one.

**Zones** (70): Algiers, Djelfa and Adrar (Adrar with `halfMonthWindows`), and the 25, 26 and 16 places attached to them,
each with `baseZoneId` its reference and `offsetRule` `WINDOW_CEILING`. Ids are slugs of the calendar's Latin names;
`nameAr` is its spelling, with «ب.ب. مختار» and «اولادجلال» written out. `namedPlaces`: the town point of `places.csv`
(GeoNames, fr.wikipedia for Algiers), catalogue ids left to M5. `admin1`: the GeoNames admin1 code of the place's wilaya
(58 wilayas, the ten of 2019 under letter codes, e.g. `TM` Timimoun); the 70 places cover all 58. `hijriMonthStarts`:
1447 and 1448, the first date of each monthly table.

**Points** (ours, not the ministry's): the region of points that the most windows cover, and in it the cell nearest the
town among those whose eight neighbours are in the region too (off its edge, where a window's maximum sits a hair from a
whole minute). References: all six times, a 0.001° × 0.0005° grid: Algiers 36.76523 N 3.05897 E, Djelfa 34.67378 N
3.25878 E, Adrar 27.87417 N 0.27789 W, 0.3–0.5′ east of the plan's whole arc-minutes (as if the ministry's times were
1–2 s earlier than MEEUS gives there); held out they score 94.1–94.6 % against 93.1–94.0 % at the whole arc-minutes
(Algiers 1447: 93.6 against 93.9 %). Attached places: their own offsets, a 0.0005° grid: median 0.8 km from the town,
65 of 67 within 2 km, 7 at the town itself. (Held out, the derived offsets score 98.24 % on the 1448 split and 98.78 %
on 1447; the region's centroid, or the town where inside it, 97.82 and 98.94 %; its plain nearest cell 98.13 and
98.50 %.)

**Mapping**: `NEAREST` with `byAdmin1`: the listed place of the place's wilaya, the nearest where it has several (Tlemcen
3, M'Sila 3 across two references, eight wilayas 2), otherwise the nearest of the 70, with the validity guard (6.5).

**Window rule** (A16, S11): offset = ceil(max over the window of (own − reference) − 1e-9), on the unrounded times; the
window is the ministry's Hijri month; in the Adrar zone days 1–15, then 17 to the end, which also serves day 16. Fitted
on each half apart, a second window from day 17 reproduces all 1,152 cells of each half (from day 16: 1,141 and 1,136;
15: 1,120 and 1,117; 18: 1,137). The signed shadow reproduces all 216 cells of In Guezzam, Tamanrasset and Bordj Badji
Mokhtar, the unsigned 211 and 213. In-sample, 5,967 of 1448's 5,976 printed cells (99.85 %); missed: Tizi Ouzou's Dhuhr
in 4 months and Relizane's in 1 (whole-minute longitude differences from Algiers, knife-edge), Guelma's Fajr in 1, In
Amenas's Fajr in 2 half-months, In Guezzam's Asr in 1.

**Held-out scores**: the 1448 Hijri months split odd/even (each half scored with values and points fitted on the other),
and 1447 Algiers with the shipped points. Exact % per time, misprints apart:

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| The 3 reference tables | 93.3 | 94.9 | 98.6 | 92.0 | 94.5 | 91.9 |
| All 70 zones (the profile's record) | 91.8 | 93.5 | 97.4 | 90.2 | 93.4 | 90.2 |

Every reference minute is within 1. All 70 zones: 99.94 % (1448) and 99.96 % (1447) within 1 minute, the rest 2 min
off, where a derived-offset miss meets a reference miss of the same sign. Derived offsets: 98.24 % exact on the 1448
month split, 98.78 % on 1447, never more than 1 min off. Tier B (9.5).

**Quirk days**: the 1447 Algiers calendar prints Isha an hour late on 11 days (2026-02-14 to 02-18, 2026-04-13 to
04-18), so also at its 25 places; counted apart (`quirkDays` 11, `maxDiffMin` 61, which FitStats counts with them).

**Open issues**: (1) The reference tables stay near 94 %: the ministry's sun differs from MEEUS by seconds and no engine
model does better (VSOP87 fits Dhuhr worse, and refitted at its own points scores 93.8–93.9 % on the month split).
(2) 100 % within 1 minute (9.5) holds neither held out (99.94 %) nor in-sample (99.997 %: Tizi Ouzou's Dhuhr).
(3) Each Hijri year's 12 month starts must ship before the year begins: with Umm al-Qura's, offsets match on 98.0 % of
place-days but times only 99.78 % within 1 minute, up to 4 min off on month-edge days (8.8 expected 1). (4) Only
Algiers has a second year; Djelfa's and Adrar's zones are held out by the month split alone.
