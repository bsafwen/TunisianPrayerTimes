# Arab authorities prototype (Jordan, UAE)

A research prototype, not app code. It reproduces the official prayer-time tables of Jordan and the UAE from the method that ICOP's Accurate Times documents, implemented from published sources only. The binary was never decompiled. Background and results: `docs/prayer-times-beyond-tunisia-plan.md`, sections 8.5 and 8.6.

## Method (`engine.py`)

- **Sun.** VSOP87D Earth series (CDS archive VI/81), with the FK5 correction, nutation, aberration, and Delta T by the Espenak–Meeus polynomial. An hourly table with cubic interpolation keeps it fast.
- **Fajr, Isha.** 18° below the horizon, geometric, with no dip.
- **Sunrise, Maghrib.** The sun's upper limb on the apparent horizon, lowered by the geometric dip of the zone's elevation. Refraction is Bennett's formula scaled by pressure and temperature, standing in for Hohenkerk & Sinclair. Morning and evening elevations can differ (`elev`, `elev_set`).
- **City diameter.** Fajr and sunrise are computed at a point `diam` km east of the zone's western point; Dhuhr, Asr, Maghrib and Isha at the western point.
- **Asr.** Shadow factor 1 measured on the apparent, refracted sun, at noon and at Asr (`asr_mode: apparent`, the default).
- **Rounding.** Nearest minute. Offsets per prayer through the variant's `add` map (the UAE: `{"add": {"dhuhr": 2}}`).

## Data

Everything lives in the git-ignored `test-data-local/arab-prototype/`, or the folder named by `ARAB_PROTOTYPE_DATA`:

| Path | Content | Source |
|---|---|---|
| `vsop/VSOP87D.ear` | VSOP87D Earth series | https://cdsarc.cds.unistra.fr/ftp/VI/81/VSOP87D.ear (fetched with Windows `curl.exe`; Python's TLS check fails behind the proxy, and verification is never turned off) |
| `jo/cal2023.xls`, `jo/cal2023.csv` | Jordanian ministry calendar 2023, 15 zones × 365 days | opendata.gov.jo, dataset "Calendar 2023" |
| `jo/cal2025.pdf` | Ministry calendar 2025 (scanned, about the first 40 days) | opendata.gov.jo, dataset "Annual calendar 2025" |
| `jo/cal2025_pages/` | PNG renders and crops of the PDF pages the sample was read from | rendered from `cal2025.pdf` |
| `jo/jo2025_sample.txt` | 31 zone-days transcribed by hand from `cal2025.pdf` | held-out test |
| `ae/ad36.txt`, `ain37.txt`, `dxb69.txt` | Awqaf 2026 tables for Abu Dhabi, Al Ain, Dubai (every third day) | awqaf.gov.ae |
| `salat-calendar-official-2026/<id>.csv` | Salat Calendar "official" 2026 tables, Jordan ids 1–34, UAE ids 35–94 | `collect_sc.py`; supportive evidence only, not a validation target |
| `results/` | Fitted zone inputs and fit logs | `fit_zones.py` |

Zone inputs start from `loc.txt` of the owner's local Accurate Times install (`ACCURATE_TIMES_DIR`, default `C:\Accurate Times`). It is read in place and never copied into the repository.

## Commands

Run from the repository root with `python -I`:

| Command | What it does | Result on 2026-10-09 |
|---|---|---|
| `scripts/prayer_formula/arab_prototype/engine.py` | Amman on 2026-10-08 | 05:13 06:29 12:24 15:43 18:19 19:34, the ministry's row |
| `compare.py jo2023` | Shipped inputs against the 2023 calendar, best entry per zone | Fajr 88.0 %, sunrise 84.5 %, Dhuhr 97.6 %, Asr 92.7 %, Maghrib 85.8 %, Isha 93.3 % |
| `compare.py awqaf '{"add":{"dhuhr":2}}'` | Same for Awqaf's three tables | Abu Dhabi: Fajr 120/121, Dhuhr 121, Isha 121 |
| `fit_zones.py jo2023` | Fit lat, lon, diameter and morning/evening elevations per zone (about 10 min) | Fajr 94.1 %, sunrise 99.3 %, Dhuhr 99.6 %, Asr 98.7 %, Maghrib 98.7 %, Isha 99.5 % |
| `fit_zones.py awqaf '{"add":{"dhuhr":2}}'` | Same for the UAE | Abu Dhabi 717/732, Al Ain 711/732, Dubai not reproduced |
| `heldout2025.py` | 2023-fitted inputs on the ministry's 2025 sample | **185/186** (shipped inputs: 143/186) |
| `heldout.py` | 2023-fitted inputs on Salat Calendar 2026 | 51–61 % on sunrise and Maghrib: Salat Calendar differs from the ministry |
| `collect_sc.py <out-dir>` | Re-collect Salat Calendar's official yearly tables (one POST per location, 1.5 s apart) | 93 × 365 rows |

## Known limits

- `fit_zones.py` fits one parameter at a time. It sometimes widens the city diameter to fix sunrise and so loses Fajr. A joint fit of the eastern point and the morning elevation is the next step.
- Bennett's formula approximates Hohenkerk & Sinclair; implementing the published method itself may close the last sunrise and Maghrib misses.
- Dubai's official zone resembles no shipped entry, and the other UAE locations are not fitted yet.
- The 2025 held-out sample was transcribed by hand from scanned pages.
