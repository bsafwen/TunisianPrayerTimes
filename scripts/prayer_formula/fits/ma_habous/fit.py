#!/usr/bin/env python3
"""
Fits Morocco's `ma_habous` (plan 8.2, 8.7, 8.8, 9.4) from the Ministry of Habous and Islamic Affairs's monthly
locality tables (habous.gov.ma), kept locally under test-data-local/ (official tables stay local, D13, A7).

Data read at run time (nothing is copied):
- research-*/MA/**.html and official/ma/**.html: the Habous monthly pages, dated by their header
  ("ربيع الآخر 1448 | شتنبر / أكتوبر 2026"). Today 26 localities, Rabi II 1448 = 2026-09-13 to 10-12. Every row is
  printed in GMT, those of 13-19 September included although the legal clock was +01 until 2026-09-20 (8.3):
  they are read in GMT (6.7); any other date with Africa/Casablanca's rules on that date (tzdata via zoneinfo).
  Ceuta (24) and Melilla (40) are printed in Moroccan time, so they convert from Africa/Casablanca as well.
- the Arabic names of all 191 localities, from a page's locality list.
- optionally (--archive DIR; by default research-2026-10-11/MA/wayback/ when it exists): the 2024 tables of Rabat
  and Laayoune archived by web.archive.org (Q5), in legal time (+01), scored as a held-out year, never fitted.

Method (fits/README.md, plan 9.4):
1. Windows, not least squares. A published minute bounds the unrounded instant to a half-open minute (shifted by
   the rounding mode and the fixed minutes). Each bound is inverted exactly with the sun at that instant (every
   event is evaluated at its own instant): Dhuhr gives longitude windows, Fajr, Isha and Asr latitude windows,
   sunrise and Maghrib horizon-altitude windows. The value covered by the most windows is kept: for the point,
   the GeoNames town point moved into that interval (at least a quarter of its width from either end); for the
   horizons, the interval's centre.
2. Stages. Asr's sun (apparent or geometric) and rounding, then Fajr's angle and rounding, then Isha's, then Fajr
   again; each candidate is scored by its in-sample exact count with every zone refitted, through common.compute.
   Dhuhr + 5, sunrise - 1 and Maghrib + 2 (nearest) are not re-chosen: within one month they trade exactly with
   the longitude and the horizons; the archived 2024 tables settled them (Q5) and the --archive check re-tests.
3. Held out. A month split (fitted on September, scored on October, and the reverse), so every scored day comes
   from a fit that never saw it; the archived 2024 tables, when present, as another year.
4. The asset. Every locality is a zone (id = its Habous id). Its point and horizons are fitted from its local
   table where one exists; else taken from its own Habous table of Jumada I 1448 (2026-10-13 to 11-11), read once
   on 2026-10-15 to place 20 localities whose GeoNames match was missing or doubtful (LOCATED: the values only,
   no times kept); else its GeoNames point with horizons from the height regression of the fitted zones.

Usage: python scripts/prayer_formula/fits/ma_habous/fit.py [--archive DIR]
"""

from __future__ import annotations

import argparse
import datetime
import math
import re
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common as C  # noqa: E402
import validate_asset  # noqa: E402

ref = C.ref
EVENTS = C.EVENTS
TZ = "Africa/Casablanca"
GMT_ROWS = (datetime.date(2026, 9, 13), datetime.date(2026, 9, 19))
SOURCE, ARCHIVE_SOURCE = "ma_habous_html", "ma_habous_wayback_2024"
ARCHIVE_FILES = {"rabat_202406.txt": ("1", datetime.date(2024, 5, 10)), "laayoune_202406.txt": ("156", datetime.date(2024, 5, 10)),
                 "rabat_202412.txt": ("1", datetime.date(2024, 12, 3))}
MONTHS = {"يناير": 1, "فبراير": 2, "مارس": 3, "أبريل": 4, "ابريل": 4, "إبريل": 4, "ماي": 5, "مايو": 5, "يونيو": 6, "يوليوز": 7,
          "يوليو": 7, "غشت": 8, "أغسطس": 8, "شتنبر": 9, "سبتمبر": 9, "أكتوبر": 10, "اكتوبر": 10, "نونبر": 11, "نوفمبر": 11,
          "دجنبر": 12, "ديسمبر": 12}

# MA_HABOUS as ArabAuthorityProfiles.kt has it: the starting point of the stages and their tie-break.
CURRENT = {"sun": "MEEUS", "fajr": 19.0, "isha": 17.0, "asrSun": "APPARENT",
           "offsets": {"SUNRISE": -1, "DHUHR": 5, "MAGHRIB": 2},
           "rounding": {e: ("DOWN" if e == "FAJR" else "NEAREST") for e in EVENTS},
           "fallback": {"type": "standard"}}
STAGES = [
    ("ASR", [(s, r) for s in ("APPARENT", "GEOMETRIC") for r in ("NEAREST", "DOWN", "UP")], ("ASR",)),
    ("FAJR", [(a, r) for a in (18.8, 18.9, 19.0, 19.1, 19.2) for r in ("DOWN", "NEAREST", "UP")], ("FAJR", "ISHA", "ASR")),
    ("ISHA", [(a, r) for a in (16.8, 16.9, 17.0, 17.1, 17.2) for r in ("NEAREST", "DOWN", "UP")], ("FAJR", "ISHA", "ASR")),
    ("FAJR", [(a, r) for a in (18.9, 19.0, 19.1) for r in ("DOWN", "NEAREST", "UP")], ("FAJR", "ISHA", "ASR")),
]

# Habous id: (GeoNames id, latitude, longitude, GeoNames dem in m, feature class) of the town the locality names
# (GeoNames MA, EH and cities1000 extracts, CC BY 4.0, 2026-10-11), matched by Arabic and Latin name and checked
# against the region of Habous's list; 30, 131, 307, 309, 311, 317, 318 and 322 have no GeoNames place of their
# name: their located point stands in, with the dem of the nearest GeoNames place. 93, 114 and 116 are communes
# (A) without a populated place. 94 and 303 are both Tahla.
LOCALITIES = {
    "1": (2538475, 34.01325, -6.83255, 46, 'P'), "2": (2558470, 33.82404, -6.06627, 451, 'P'),
    "3": (2528659, 33.89469, -6.30649, 340, 'P'), "4": (2557134, 33.52838, -6.60348, 313, 'P'),
    "5": (2538898, 33.42585, -6.00137, 1175, 'P'), "6": (2553751, 33.78942, -7.15968, 43, 'P'),
    "7": (2544571, 34.26101, -6.58020, 13, 'P'), "8": (2533191, 34.22149, -5.70775, 83, 'P'),
    "9": (2532822, 34.30494, -6.30404, 18, 'P'), "10": (2532945, 34.26479, -5.92598, 37, 'P'),
    "11": (12718688, 34.68473, -6.00344, 38, 'P'), "12": (2557391, 34.90239, -5.94871, 143, 'P'),
    "13": (2541833, 34.88158, -6.29455, 45, 'P'), "14": (2530335, 35.76727, -5.79975, 20, 'P'),
    "15": (2528910, 35.57845, -5.36837, 121, 'P'), "16": (2543549, 35.19321, -6.15572, 38, 'P'),
    "17": (2557055, 35.46522, -6.03415, 17, 'P'), "18": (2553455, 35.16878, -5.26360, 607, 'P'),
    "19": (2542987, 35.61662, -5.27522, 5, 'P'), "20": (2542898, 35.68313, -5.32871, 23, 'P'),
    "21": (2544001, 35.00044, -5.90378, 18, 'P'), "22": (2543998, 35.84214, -5.55427, 18, 'P'),
    "23": (2558545, 35.25165, -3.93723, 74, 'P'), "24": (6362987, 35.88919, -5.32042, 22, 'P'),
    "25": (2548818, 35.84906, -5.35747, 21, 'P'), "26": (2550079, 35.20549, -4.66524, 14, 'P'),
    "27": (2540698, 35.44840, -5.09627, 20, 'P'), "28": (2554130, 34.99827, -4.89297, 1237, 'P'),
    "29": (2540810, 34.79584, -5.57849, 306, 'P'), "30": (None, 34.71350, -3.61160, 659, None),
    "31": (2540483, 34.68139, -1.90858, 549, 'P'), "32": (2555467, 34.92000, -2.32000, 173, 'P'),
    "33": (2548851, 32.10891, -1.22855, 898, 'P'), "34": (2555157, 32.53379, -1.96209, 1169, 'P'),
    "35": (2548489, 34.22568, -3.35361, 367, 'P'), "36": (2545017, 34.31000, -2.16000, 1045, 'P'),
    "37": (2560908, 32.20436, -2.51941, 982, 'P'), "38": (2530048, 34.40731, -2.89732, 394, 'P'),
    "39": (2541479, 35.16813, -2.93352, 19, 'P'), "40": (2513947, 35.29369, -2.93833, 14, 'P'),
    "41": (2552407, 33.98228, -3.04263, 994, 'P'), "42": (2537469, 35.07313, -2.94230, 80, 'P'),
    "43": (2555882, 35.26000, -2.93000, 9, 'P'), "44": (2549014, 35.28776, -2.98533, 56, 'P'),
    "45": (2530923, 32.53599, -3.44143, 1332, 'P'), "46": (2528981, 33.04964, -2.00018, 1442, 'P'),
    "47": (2550806, 34.58319, -2.50612, 604, 'P'), "48": (2555885, 34.86005, -1.98979, 453, 'P'),
    "49": (2537802, 35.08195, -2.22897, 4, 'P'), "50": (2538392, 35.14069, -2.42455, 5, 'P'),
    "51": (2531904, 34.80781, -2.41089, 796, 'P'), "52": (11067730, 34.91781, -2.20402, 249, 'P'),
    "53": (2561124, 34.95368, -2.10027, 252, 'P'), "54": (2526435, 34.94282, -2.73290, 198, 'P'),
    "55": (2550985, 34.97705, -3.37902, 287, 'P'), "56": (2555610, 32.28928, -3.46616, 1104, 'P'),
    "57": (2555182, 32.03738, -3.04978, 861, 'P'), "58": (2553604, 33.58831, -7.61138, 27, 'P'),
    "59": (2542051, 33.68607, -7.38298, 29, 'P'), "60": (2555519, 33.61601, -7.12153, 270, 'P'),
    "61": (2537406, 33.00103, -7.61662, 365, 'P'), "62": (2550419, 33.27732, -7.23236, 349, 'P'),
    "63": (2550547, 32.50467, -7.19238, 413, 'P'), "64": (2556076, 33.06635, -7.24161, 603, 'P'),
    "65": (2556272, 33.26553, -7.58754, 220, 'P'), "66": (2550078, 33.25682, -8.50882, 10, 'P'),
    "67": (2556657, 33.28952, -8.34250, 28, 'P'), "68": (2536074, 32.65236, -8.42769, 182, 'P'),
    "69": (2549888, 32.62287, -8.70227, 165, 'P'), "70": (2544333, 32.93492, -5.66167, 868, 'P'),
    "71": (2541843, 33.22576, -6.19807, 952, 'P'), "72": (2526392, 31.83179, -6.10118, 1651, 'P'),
    "73": (2555745, 32.33725, -6.34983, 538, 'P'), "74": (2556570, 31.96156, -6.57109, 1357, 'P'),
    "75": (2548830, 32.50213, -6.68771, 444, 'P'), "76": (2552317, 31.73443, -7.00505, 927, 'P'),
    "77": (2549750, 32.57286, -6.01947, 1079, 'P'), "78": (2544720, 32.59770, -6.26844, 499, 'P'),
    "79": (2544248, 32.88108, -6.90630, 804, 'P'), "80": (2540689, 32.86270, -6.57359, 783, 'P'),
    "81": (2548885, 34.03313, -5.00028, 406, 'P'), "82": (2537545, 33.83186, -4.82800, 809, 'P'),
    "83": (2541779, 34.08793, -5.18171, 254, 'P'), "84": (2554389, 33.36376, -4.72896, 1712, 'P'),
    "85": (2542147, 33.04893, -3.98947, 883, 'P'), "86": (2596460, 33.81518, -4.40717, 1146, 'P'),
    "87": (2549623, 33.83758, -4.54472, 860, 'P'), "88": (2546028, 33.72871, -5.01092, 1352, 'P'),
    "89": (2529317, 34.21000, -4.01000, 530, 'P'), "90": (2530155, 34.53661, -4.64009, 600, 'P'),
    "91": (2558633, 34.65371, -3.86754, 1021, 'P'), "92": (2527100, 34.76214, -3.79582, 1302, 'P'),
    "93": (6546455, 34.74276, -4.04726, 1251, 'A'), "94": (2531480, 34.04965, -4.42162, 606, 'P'),
    "95": (2527366, 34.28791, -4.66372, 214, 'P'), "96": (2544821, 34.37094, -5.21380, 160, 'P'),
    "97": (12718692, 34.91875, -4.56889, 1254, 'P'), "98": (2540716, 34.20000, -4.28000, 301, 'P'),
    "99": (2542715, 33.89352, -5.54727, 531, 'P'), "100": (2546917, 33.52666, -5.11019, 1648, 'P'),
    "101": (2550252, 33.68786, -5.37100, 1023, 'P'), "102": (2541805, 34.05555, -5.52132, 553, 'P'),
    "103": (2556464, 33.43443, -5.22126, 1277, 'P'), "104": (2542997, 31.63416, -7.99994, 457, 'P'),
    "105": (2549979, 32.05121, -7.41079, 471, 'P'), "106": (2549263, 31.51250, -9.77000, 7, 'P'),
    "107": (2553303, 31.54351, -8.76275, 356, 'P'), "108": (2556018, 32.24088, -7.95397, 474, 'P'),
    "109": (2532646, 32.48244, -7.91533, 473, 'P'), "110": (2530843, 31.00087, -9.67802, 320, 'P'),
    "111": (2537881, 32.29939, -9.23718, 45, 'P'), "112": (2541068, 32.73372, -9.03059, 51, 'P'),
    "113": (2526488, 32.24634, -8.52941, 302, 'P'), "114": (12034990, 31.54766, -7.98218, 544, 'A'),
    "115": (2530641, 31.49472, -8.09772, 563, 'P'), "116": (6545625, 31.87230, -8.17436, 456, 'A'),
    "117": (2561668, 30.42018, -9.59815, 31, 'P'), "118": (2529649, 30.47028, -8.87695, 237, 'P'),
    "119": (2527089, 29.69742, -9.73162, 240, 'P'), "120": (2545797, 30.08928, -8.46023, 1723, 'P'),
    "121": (2531014, 30.52917, -7.91262, 1007, 'P'), "122": (2531877, 29.72449, -8.97470, 993, 'P'),
    "123": (2529362, 29.74409, -7.97300, 684, 'P'), "124": (2558667, 29.38914, -8.25490, 522, 'P'),
    "125": (12718689, 29.01889, -8.89111, 480, 'P'), "126": (13429580, 30.21793, -9.37023, 117, 'P'),
    "127": (2539134, 30.39467, -9.20897, 129, 'P'), "128": (7280528, 31.93140, -4.42663, 1039, 'P'),
    "129": (2538027, 31.28318, -4.26565, 761, 'P'), "130": (2549356, 31.43530, -4.23258, 809, 'P'),
    "131": (None, 33.68570, -3.63610, 818, None), "132": (2548623, 31.69227, -4.95256, 1028, 'P'),
    "133": (2546187, 32.15309, -5.62453, 2162, 'P'), "134": (2527925, 31.50764, -5.03293, 1005, 'P'),
    "135": (2538059, 32.25644, -4.50152, 1319, 'P'), "136": (2542227, 32.68520, -4.74512, 1462, 'P'),
    "137": (2526452, 30.33241, -5.83840, 733, 'P'), "138": (2540850, 30.91894, -6.89341, 1113, 'P'),
    "139": (2527915, 31.51472, -5.53278, 1281, 'P'), "140": (2532628, 31.06262, -6.55250, 1226, 'P'),
    "141": (2549981, 31.24573, -6.13260, 1431, 'P'), "142": (2561539, 30.69356, -6.44628, 940, 'P'),
    "143": (2554322, 31.37986, -5.98819, 1539, 'P'), "144": (2558398, 31.11411, -5.17154, 871, 'P'),
    "145": (2556835, 31.95286, -5.20812, 1641, 'P'), "146": (2541599, 31.70160, -5.80117, 1999, 'P'),
    "147": (2529101, 30.77788, -5.56000, 858, 'P'), "148": (2534515, 29.37975, -10.17299, 44, 'P'),
    "149": (2548526, 28.98696, -10.05738, 301, 'P'), "150": (2556996, 28.60986, -9.42986, 318, 'P'),
    "151": (2526459, 28.02210, -9.29397, 410, 'P'), "152": (2530241, 28.43799, -11.10321, 45, 'P'),
    "153": (2554594, 29.17705, -9.71181, 635, 'P'), "154": (2462123, 27.41434, -9.04785, 465, 'P'),
    "155": (2462188, 26.53914, -13.83876, 60, 'P'), "156": (2462881, 27.14180, -13.18797, 68, 'P'),
    "157": (2461874, 26.73841, -11.67194, 188, 'P'), "158": (2463029, 26.13073, -14.48513, 28, 'P'),
    "159": (2529923, 27.93920, -12.92604, 5, 'P'), "160": (2461667, 26.15790, -10.55889, 486, 'P'),
    "161": (2463090, 26.35389, -12.78389, 199, 'P'), "162": (9645313, 25.14276, -12.37168, 419, 'P'),
    "163": (13608327, 26.28729, -11.13547, 279, 'P'), "164": (9645312, 28.09455, -12.05157, 15, 'P'),
    "165": (2463447, 23.68477, -15.95798, 6, 'P'), "166": (2462651, 20.83228, -17.08988, 9, 'P'),
    "167": (2463161, 22.55400, -14.33000, 306, 'P'), "168": (2463112, 21.60336, -16.45018, 36, 'P'),
    "169": (2463113, 23.89000, -14.53603, 190, 'P'), "301": (2537133, 34.09625, -4.58177, 294, 'P'),
    "302": (2540207, 32.20000, -6.80000, 450, 'P'), "303": (2531480, 34.04965, -4.42162, 606, 'P'),
    "304": (2542957, 34.09914, -4.55219, 306, 'P'), "305": (2546128, 31.17213, -8.84728, 896, 'P'),
    "306": (10924503, 31.31025, -9.45684, 779, 'P'), "307": (None, 31.34750, -7.53770, 1693, None),
    "308": (10950408, 31.22931, -7.81274, 2009, 'P'), "309": (None, 32.04190, -5.49410, 2306, None),
    "310": (2561479, 32.00881, -5.49138, 2352, 'P'), "311": (None, 31.24100, -7.81730, 2009, None),
    "312": (2560939, 33.80333, -6.79461, 237, 'P'), "313": (2557058, 31.32812, -7.75941, 988, 'P'),
    "314": (2559217, 31.56517, -7.66163, 671, 'P'), "315": (2541830, 31.28785, -7.96830, 1239, 'P'),
    "316": (2530160, 31.22218, -9.07943, 1043, 'P'), "317": (None, 30.96010, -7.85750, 1546, None),
    "318": (None, 31.46790, -7.63620, 890, None), "319": (2527307, 29.89826, -7.31619, 560, 'P'),
    "320": (2548769, 30.08792, -6.87119, 715, 'P'), "321": (2547514, 32.51244, -1.00340, 1177, 'P'),
    "322": (None, 31.59710, -6.53130, 1819, None),
}
# Habous id: (latitude, longitude, sunrise altitude, sunset altitude), fitted with this profile on the locality's own
# Habous table of Jumada I 1448 (2026-10-13 to 11-11), read on 2026-10-15 to place it (each reproduces 28-30 of its
# 30 days per time, in-sample; no held-out record). Used only where no local table exists.
LOCATED = {
    "30": (34.71350, -3.61160, -1.523, -1.484), "94": (34.05388, -4.44077, -1.610, -1.557),
    "102": (34.05555, -5.53702, -1.813, -1.744), "109": (32.48244, -7.92241, -1.759, -1.686),
    "114": (31.54766, -7.96282, -1.674, -1.600), "116": (31.87261, -8.17663, -1.667, -1.599),
    "131": (33.68570, -3.63610, -1.753, -1.694), "140": (31.06262, -6.55920, -2.012, -1.885),
    "301": (34.08145, -4.58880, -1.648, -1.599), "303": (34.04965, -4.42235, -1.609, -1.559),
    "306": (31.31025, -9.45983, -1.893, -1.777), "307": (31.34750, -7.53770, -1.533, -1.479),
    "308": (31.22931, -7.78932, -2.509, -2.238), "309": (32.04190, -5.49410, -2.393, -2.178),
    "310": (32.04756, -5.49120, -1.992, -1.878), "311": (31.24100, -7.81730, -1.976, -1.864),
    "316": (31.22111, -9.06953, -1.978, -1.859), "317": (30.96010, -7.85750, -2.487, -2.230),
    "318": (31.46790, -7.63620, -2.284, -2.104), "322": (31.59710, -6.53130, -1.883, -1.788),
}


# ---------------------------------------------------------------------------------------------------- the tables

class Table:
    """One locality's published days: [day], and per day {event: the published minute as UTC epoch seconds}."""

    def __init__(self, vid: str, source: str, start: datetime.date, rows: list[list[str]], gmt_rows: bool):
        self.vid, self.source, self.days, self.pub = vid, source, [], []
        for i, row in enumerate(rows):
            day = start + datetime.timedelta(days=i)
            if int(row[2]) != day.day:
                raise ValueError(f"locality {vid}: row {i} is day {row[2]}, expected {day}")
            minutes = {}
            for k, e in enumerate(EVENTS):
                hhmm = row[3 + k]
                h, m = (int(x) for x in hhmm.split(":"))
                epoch = C.local_to_epoch(TZ, day, hhmm)
                if gmt_rows and GMT_ROWS[0] <= day <= GMT_ROWS[1]:
                    epoch += C.utc_offset(TZ, day, h, m)        # printed in GMT, not in the legal +01
                minutes[e] = epoch
            self.days.append(day)
            self.pub.append(minutes)


def table_rows(text: str) -> list[list[str]]:
    body = text[text.find('id="horaire"'):]
    rows = []
    for tr in re.findall(r"(?s)<tr[^>]*>(.*?)</tr>", body):
        cells = [re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", c)).strip() for c in re.findall(r"(?s)<td[^>]*>(.*?)</td>", tr)]
        if len(cells) == 9 and all(re.fullmatch(r"\d\d?:\d\d", c) for c in cells[3:]):
            rows.append(cells)
    return rows


def locality_names(text: str) -> dict[str, str]:
    return {k: re.sub(r"\s+", " ", v).strip()
            for k, v in re.findall(r"<option value='index\.php\?ville=(\d+)'[^>]*>([^<]*)</option>", text)}


def load_tables() -> tuple[dict[str, Table], dict[str, str], list[str]]:
    """Every dated Habous monthly page under the local data, one table per locality and period."""
    roots = sorted(C.LOCAL_DATA.glob("research-*/MA")) + [C.LOCAL_DATA / "official" / "ma"]
    files, periods, tables, names, notes = set(), set(), {}, {}, []
    for root in roots:
        for path in sorted(root.rglob("*.html")) if root.is_dir() else []:
            if path.resolve() in files:
                continue
            files.add(path.resolve())
            text = path.read_text(encoding="utf-8", errors="replace")
            if 'id="horaire"' not in text:
                continue
            names = names or locality_names(text)
            ids = re.findall(r"(\d+)", path.stem)
            plain = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " | ", text))
            header = re.search(r"([^\s|/]+)\s*/\s*([^\s|/]+)\s+(20\d\d)", plain)
            rows = table_rows(text)
            if not ids or not rows or not header or header.group(1) not in MONTHS:
                notes.append(f"{path.name}: undated or not a locality table, skipped")
                continue
            start = datetime.date(int(header.group(3)), MONTHS[header.group(1)], int(rows[0][2]))
            table = Table(ids[-1], SOURCE, start, rows, gmt_rows=True)
            if (table.vid, start) in periods:
                continue
            periods.add((table.vid, start))
            if table.vid in tables:     # a second period of the same locality: keep both as one table
                tables[table.vid].days += table.days
                tables[table.vid].pub += table.pub
            else:
                tables[table.vid] = table
    return tables, names, notes


def load_archive(folder: Path | None) -> list[Table]:
    out = []
    for name, (vid, start) in ARCHIVE_FILES.items():
        path = folder / name if folder else None
        if path and path.exists():
            out.append(Table(vid, ARCHIVE_SOURCE, start, table_rows(path.read_text(encoding="utf-8")), gmt_rows=False))
    return out


# ---------------------------------------------------------------------------------------------------- windows

def sind(x): return math.sin(math.radians(x))
def cosd(x): return math.cos(math.radians(x))
def tand(x): return math.tan(math.radians(x))
def asind(x): return math.degrees(math.asin(max(-1.0, min(1.0, x))))
def atand(x): return math.degrees(math.atan(x))


class Sun:
    """The profile's sun at an instant (EVENT_INSTANT), cached: (declination, equation of time in minutes)."""
    _models: dict[str, "Sun"] = {}

    def __init__(self, model: str):
        self.f, self.cache = ref.SUN_MODELS[model], {}

    @classmethod
    def of(cls, model: str) -> "Sun":
        if model not in cls._models:
            cls._models[model] = Sun(model)
        return cls._models[model]

    def __call__(self, t: float):
        v = self.cache.get(t)
        if v is None:
            decl, eot, _ = self.f(ref.jd_of_instant(t))
            v = self.cache[t] = (decl, eot)
        return v


def day0(day: datetime.date) -> int:
    return (day - datetime.date(1970, 1, 1)).days * 86400


def altitude(sun: Sun, t: float, day: datetime.date, lat: float, lng: float) -> float:
    """S3's altitude at instant t, with the sun of that instant (the cycle's UT date is the civil date in Morocco)."""
    decl, eot = sun(t)
    hour_angle = 15 * ((t - day0(day)) / 3600 - (12 - lng / 15)) + eot / 4
    return asind(sind(lat) * sind(decl) + cosd(lat) * cosd(decl) * cosd(hour_angle))


def transit_instant(sun: Sun, day: datetime.date, lng: float) -> float:
    mean_noon = 12 - lng / 15
    t = mean_noon
    for _ in range(10):
        nxt = mean_noon - sun(day0(day) + 3600 * t)[1] / 60
        if abs(nxt - t) < 0.1 / 3600:
            return day0(day) + 3600 * nxt
        t = nxt
    return day0(day) + 3600 * t


def longitude_of_transit(sun: Sun, t: float, day: datetime.date) -> float:
    """The longitude whose transit is the instant t: T = 12 - lng/15 - E(T)/60 hours."""
    return 15 * (12 - (t - day0(day)) / 3600 - sun(t)[1] / 60)


def asr_altitude(lat: float, decl_noon: float, apparent: bool) -> float:
    """S7, factor 1: the shadow altitude, on the refracted sun at standard air (E13) when apparent."""
    zenith = abs(lat - decl_noon)
    if not apparent:
        return atand(1 / (1 + tand(zenith)))
    h0 = 90 - zenith
    h0 += ref.bennett(h0, 1010.0, 10.0)
    a = atand(1 / (1 + tand(90 - h0)))
    return a - ref.bennett(a, 1010.0, 10.0)


def minute_window(published: float, offset_min: float, rounding: str) -> tuple[float, float]:
    """E1 inverted: the half-open window of the event's instant (before its fixed minutes) for a published minute."""
    base = published - 60 * offset_min
    return {"NEAREST": (base - 30, base + 30), "DOWN": (base - 0.001, base + 59.999), "UP": (base - 59.999, base + 0.001)}[rounding]


def where_nonnegative(f, a: float, b: float, nonnegative: bool) -> tuple[float, float] | None:
    """The part of [a, b] where f >= 0 (or < 0), for a monotonic f, by bisection."""
    fa, fb = f(a), f(b)
    ok_a, ok_b = (fa >= 0) == nonnegative, (fb >= 0) == nonnegative
    if ok_a and ok_b:
        return a, b
    if not (ok_a or ok_b):
        return None
    lo, hi = a, b
    for _ in range(40):
        mid = (lo + hi) / 2
        if (f(mid) >= 0) == (fa >= 0):
            lo = mid
        else:
            hi = mid
    root = (lo + hi) / 2
    return (a, root) if ok_a else (root, b)


def window_between(f_lo, f_hi, a: float, b: float, rising: bool) -> tuple[float, float] | None:
    """The values of [a, b] whose event falls in [t_lo, t_hi): f_x = altitude(t_x) - target. Rising events
    (Fajr) are after t_lo where f_lo <= 0; setting ones (Asr, Isha) where f_lo >= 0."""
    s = -1 if rising else 1
    first = where_nonnegative(lambda x: s * f_lo(x), a, b, True)
    second = where_nonnegative(lambda x: s * f_hi(x), a, b, False)
    if first is None or second is None:
        return None
    lo, hi = max(first[0], second[0]), min(first[1], second[1])
    return (lo, hi) if lo < hi else None


def most_covered(windows: list[tuple[float, float]], prior: float | None = None) -> tuple[int, float | None, float | None]:
    """The interval covered by the most windows (the one holding, or nearest to, [prior]; else the widest)."""
    edges = sorted([(lo, 0) for lo, _ in windows] + [(hi, 1) for _, hi in windows])
    count, best, segments = 0, 0, []
    for i, (x, kind) in enumerate(edges):
        count += 1 if kind == 0 else -1
        if kind == 0 and i + 1 < len(edges):
            if count > best:
                best, segments = count, [(x, edges[i + 1][0])]
            elif count == best:
                segments.append((x, edges[i + 1][0]))
    if not segments:
        return 0, None, None
    merged = []
    for lo, hi in sorted(segments):
        if merged and lo <= merged[-1][1] + 1e-12:
            merged[-1] = (merged[-1][0], max(hi, merged[-1][1]))
        else:
            merged.append((lo, hi))
    if prior is None:
        lo, hi = max(merged, key=lambda s: s[1] - s[0])
    else:
        lo, hi = min(merged, key=lambda s: 0 if s[0] <= prior <= s[1] else min(abs(prior - s[0]), abs(prior - s[1])))
    return best, lo, hi


def towards(lo: float, hi: float, prior: float) -> float:
    """The prior moved into [lo, hi], at least a quarter of its width from either end."""
    quarter = (hi - lo) / 4
    return min(max(prior, lo + quarter), hi - quarter)


class ZoneFit:
    """A zone's point (latitude, longitude) and horizons from its days [idx] under profile [p]."""

    def __init__(self, table: Table, prior: tuple[float, float], p: dict, idx: list[int]):
        self.t, self.prior, self.p, self.idx, self.sun = table, prior, p, idx, Sun.of(p["sun"])

    def _window(self, i: int, event: str) -> tuple[float, float]:
        return minute_window(self.t.pub[i][event], self.p["offsets"].get(event, 0), self.p["rounding"][event])

    def longitude_windows(self, lat: float) -> list[tuple[float, float]]:
        sun, lng0, out = self.sun, self.prior[1], []
        apparent = self.p["asrSun"] == "APPARENT"
        for i in self.idx:
            day = self.t.days[i]
            lo, hi = self._window(i, "DHUHR")
            out.append((longitude_of_transit(sun, hi, day), longitude_of_transit(sun, lo, day)))
            target = asr_altitude(lat, sun(transit_instant(sun, day, lng0))[0], apparent)
            lo, hi = self._window(i, "ASR")
            w = window_between(lambda x, t=lo: altitude(sun, t, day, lat, x) - target,
                               lambda x, t=hi: altitude(sun, t, day, lat, x) - target, lng0 - 1.5, lng0 + 1.5, rising=False)
            if w:
                out.append(w)
        return out

    def latitude_windows(self, lng: float) -> list[tuple[float, float]]:
        sun, lat0, out = self.sun, self.prior[0], []
        apparent = self.p["asrSun"] == "APPARENT"
        for i in self.idx:
            day = self.t.days[i]
            decl_noon = sun(transit_instant(sun, day, lng))[0]
            for event in ("FAJR", "ISHA", "ASR"):
                lo, hi = self._window(i, event)
                if event == "ASR":
                    def f(x, t, d=decl_noon):
                        return altitude(sun, t, day, x, lng) - asr_altitude(x, d, apparent)
                else:
                    target = -(self.p["fajr"] if event == "FAJR" else self.p["isha"])

                    def f(x, t, target=target):
                        return altitude(sun, t, day, x, lng) - target
                w = window_between(lambda x, t=lo: f(x, t), lambda x, t=hi: f(x, t), lat0 - 3, lat0 + 3, rising=event == "FAJR")
                if w:
                    out.append(w)
        return out

    def horizon_windows(self, lat: float, lng: float, event: str) -> list[tuple[float, float]]:
        out = []
        for i in self.idx:
            lo, hi = self._window(i, event)
            a, b = (altitude(self.sun, t, self.t.days[i], lat, lng) for t in (lo, hi))
            out.append((min(a, b), max(a, b)))
        return out

    def fit(self) -> dict:
        lat0, lng0 = self.prior
        lat, lng = lat0, lng0
        for _ in range(2):
            _, lo, hi = most_covered(self.longitude_windows(lat), lng0)
            lng = towards(lo, hi, lng0)
            _, lo, hi = most_covered(self.latitude_windows(lng), lat0)
            lat = towards(lo, hi, lat0)
        _, slo, shi = most_covered(self.horizon_windows(lat, lng, "SUNRISE"))
        _, mlo, mhi = most_covered(self.horizon_windows(lat, lng, "MAGHRIB"))
        return {"lat": lat, "lng": lng, "sr": (slo + shi) / 2, "ss": (mlo + mhi) / 2}


# ---------------------------------------------------------------------------------------------------- scoring

def method_of(p: dict) -> dict:
    horizon = {"type": "per_zone", "fallback": p["fallback"]}
    return C.method_json(id="ma_habous", fajr={"type": "angle", "degrees": p["fajr"]}, isha={"type": "angle", "degrees": p["isha"]},
                         offsets=dict(p["offsets"]), rounding=dict(p["rounding"]), horizon={"sunrise": horizon, "sunset": horizon},
                         asrSun=p["asrSun"], sunModel=p["sun"],
                         evidence={"kind": "FROM_TABLES", "source": "The Ministry of Habous's locality tables"})


def score(table: Table, idx: list[int], p: dict, z: dict, *scores: C.Score, exact: bool = False, elevation: int | None = None) -> None:
    """common.compute (the reference) on the days [idx] at zone [z], or at its point with no zone ([exact])."""
    method = method_of(p)
    zone = None if exact else C.zone_json(table.vid, z["lat"], z["lng"], authority="ma_habous",
                                          sunriseAltitudeDeg=round(z["sr"], 3), sunsetAltitudeDeg=round(z["ss"], 3))
    for i in idx:
        day = table.days[i]
        out = C.compute(C.case(method, z["lat"], z["lng"], TZ, day, zone=zone, country="MA", elevation_m=elevation))
        for e in EVENTS:
            for s in scores:
                s.add(e, day, out[e][1] // 60, table.pub[i][e] // 60)


def days_of(table: Table, which) -> list[int]:
    return [i for i, d in enumerate(table.days) if which(d)]


def prior_of(vid: str) -> tuple[float, float]:
    return LOCALITIES[vid][1], LOCALITIES[vid][2]


def fit_zones(tables: dict[str, Table], p: dict, which) -> dict[str, dict]:
    return {vid: ZoneFit(t, prior_of(vid), p, days_of(t, which)).fit() for vid, t in tables.items()}


def in_sample(tables: dict[str, Table], p: dict, zones: dict[str, dict], which, events) -> int:
    s = C.Score()
    for vid, t in tables.items():
        score(t, days_of(t, which), p, zones[vid], s)
    return sum(s.exact[e] for e in events)


def with_choice(p: dict, key: str, choice: tuple) -> dict:
    q = {**p, "rounding": dict(p["rounding"])}
    if key == "ASR":
        q["asrSun"] = choice[0]
    else:
        q[key.lower()] = choice[0]
    q["rounding"][key] = choice[1]
    return q


def choice_of(p: dict, key: str) -> tuple:
    return (p["asrSun"] if key == "ASR" else p[key.lower()], p["rounding"][key])


def select(tables: dict[str, Table], which, label: str) -> dict:
    """The stages on the days [which], each candidate with every zone refitted; ties keep the current profile."""
    p = CURRENT
    for key, candidates, events in STAGES:
        ranked = []
        for c in candidates:
            q = with_choice(p, key, c)
            ranked.append((in_sample(tables, q, fit_zones(tables, q, which), which, events), c == choice_of(CURRENT, key), c))
        ranked.sort(key=lambda r: (-r[0], not r[1]))
        p = with_choice(p, key, ranked[0][2])
        print(f"   {label} {key}: {ranked[0][2]} ({ranked[0][0]} exact); next {[(r[2], r[0]) for r in ranked[1:4]]}")
    return p


# ---------------------------------------------------------------------------------------------------- report helpers

def shares(s: C.Score) -> str:
    return ", ".join(f"{e[:2]} {s.exact[e]}/{s.total[e]} ({100 * s.exact[e] / s.total[e]:.1f} %, w1 {100 * s.within1[e] / s.total[e]:.0f} %)"
                     for e in EVENTS if s.total[e])


def km(lat1, lng1, lat2, lng2) -> float:
    a = math.sin(math.radians(lat2 - lat1) / 2) ** 2 + cosd(lat1) * cosd(lat2) * math.sin(math.radians(lng2 - lng1) / 2) ** 2
    return 12742 * math.asin(math.sqrt(a))


def regression(points: list[tuple[float, float]]) -> tuple[float, float, float]:
    """y = a + b x by least squares (a descriptive height law, not a fit of a time), with its rms."""
    n = len(points)
    mx, my = sum(x for x, _ in points) / n, sum(y for _, y in points) / n
    b = sum((x - mx) * (y - my) for x, y in points) / sum((x - mx) ** 2 for x, _ in points)
    a = my - b * mx
    return a, b, math.sqrt(sum((y - a - b * x) ** 2 for x, y in points) / n)


def horizon_law(zones: dict[str, dict], vids) -> dict[str, tuple[float, float, float]]:
    """Sunrise and sunset altitude = -(a + b sqrt(h)), h the GeoNames dem of the locality's town."""
    return {key: regression([(math.sqrt(max(LOCALITIES[v][3], 0)), -zones[v][key]) for v in vids]) for key in ("sr", "ss")}


def by_law(law: dict, dem: int) -> tuple[float, float]:
    return tuple(-(law[k][0] + law[k][1] * math.sqrt(max(dem, 0))) for k in ("sr", "ss"))


# ---------------------------------------------------------------------------------------------------- main

def main(argv: list[str]) -> int:
    started = time.time()
    args = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    args.add_argument("--archive", type=Path, default=None, help="folder of the archived 2024 tables (held-out year)")
    opts = args.parse_args(argv)
    tables, names, notes = load_tables()
    archive_dir = opts.archive or C.LOCAL_DATA / "research-2026-10-11" / "MA" / "wayback"
    archive = load_archive(archive_dir)
    days = sorted({d for t in tables.values() for d in t.days})
    print(f"ma_habous: {len(tables)} local tables ({days[0]} to {days[-1]}), {len(names)} localities named; "
          f"archive: {len(archive)} tables" + ("" if archive else f" (none at {archive_dir})"))
    for n in notes:
        print("   " + n)
    if len(names) != len(LOCALITIES) or set(names) != set(LOCALITIES):
        raise SystemExit(f"the Habous list ({len(names)}) no longer matches LOCALITIES ({len(LOCALITIES)})")
    quirk = tables["1"]
    i = quirk.days.index(GMT_ROWS[0])
    noon = transit_instant(Sun.of("MEEUS"), quirk.days[i], prior_of("1")[1]) + 300
    print(f"   GMT rows (8.3): Rabat's Dhuhr on {quirk.days[i]} is {(quirk.pub[i]['DHUHR'] - noon) / 60:+.1f} min from transit + 5 "
          f"read in GMT, {(quirk.pub[i]['DHUHR'] - 3600 - noon) / 60:+.1f} min read in the legal +01")

    # Held out: the month split (fitted on the odd months, scored on the even ones, and the reverse; today
    # September against October), with the profile and the zones refitted on each half.
    print("Month split (stages on each half):")
    split, per_zone = C.Score(), {vid: C.Score() for vid in tables}
    folds = [(parity, lambda d, r=parity: d.month % 2 == r, lambda d, r=parity: d.month % 2 != r) for parity in (1, 0)]
    folds = [f for f in folds if any(f[1](d) for d in days) and any(f[2](d) for d in days)]
    chosen = {}
    for parity, fit_days, held in folds:
        p = chosen[parity] = select(tables, fit_days, "fit odd" if parity else "fit even")
        zones = fit_zones(tables, p, fit_days)
        for vid, t in tables.items():
            score(t, days_of(t, held), p, zones[vid], split, per_zone[vid])
    print(f"   held out: {shares(split)}; max {split.worst} min")

    # The shipped values: stages and zones on every day.
    print("All days:")
    final = select(tables, lambda d: True, "all")
    zones = fit_zones(tables, final, lambda d: True)
    fitted = C.Score()
    for vid, t in tables.items():
        score(t, list(range(len(t.days))), final, zones[vid], fitted)
    print(f"   in-sample: {shares(fitted)}")
    offsets_km = sorted((km(*prior_of(v), zones[v]["lat"], zones[v]["lng"]), v) for v in tables)
    print(f"   fitted points from the GeoNames towns: median {statistics.median(d for d, _ in offsets_km):.1f} km, "
          f"max {offsets_km[-1][0]:.1f} km ({offsets_km[-1][1]})")

    # Dhuhr: + 5 nearest is the variant whose fitted longitudes centre on the towns (each variant moves every point).
    for offset, rounding in ((5, "NEAREST"), (5, "DOWN"), (5, "UP"), (4, "NEAREST"), (6, "NEAREST")):
        shift = []
        for vid, t in tables.items():
            q = {**final, "offsets": {**final["offsets"], "DHUHR": offset}, "rounding": {**final["rounding"], "DHUHR": rounding}}
            zf = ZoneFit(t, prior_of(vid), q, list(range(len(t.days))))
            ws = []
            for i in zf.idx:
                a, b = zf._window(i, "DHUHR")
                ws.append((longitude_of_transit(zf.sun, b, t.days[i]), longitude_of_transit(zf.sun, a, t.days[i])))
            _, lo, hi = most_covered(ws, prior_of(vid)[1])
            shift.append(((lo + hi) / 2 - prior_of(vid)[1]) * 111.32 * cosd(prior_of(vid)[0]))
        print(f"   Dhuhr + {offset} {rounding}: fitted longitude - town, median {statistics.median(shift):+.1f} km "
              f"(range {min(shift):+.1f} to {max(shift):+.1f})")

    # The height law for localities with no table, and what it gives at the fitted localities (leave one out).
    law = horizon_law(zones, list(tables))
    print(f"   horizons: sunrise -({law['sr'][0]:.3f} + {law['sr'][1]:.4f} sqrt h), rms {law['sr'][2]:.3f}; "
          f"sunset -({law['ss'][0]:.3f} + {law['ss'][1]:.4f} sqrt h), rms {law['ss'][2]:.3f} (h: GeoNames dem)")
    unfitted, at_point = C.Score(), C.Score()
    for vid, t in tables.items():
        sr, ss = by_law(horizon_law(zones, [v for v in tables if v != vid]), LOCALITIES[vid][3])
        score(t, list(range(len(t.days))), final, {"lat": prior_of(vid)[0], "lng": prior_of(vid)[1], "sr": sr, "ss": ss}, unfitted)
        score(t, list(range(len(t.days))), final, {**zones[vid], "sr": sr, "ss": ss}, at_point)
    print(f"   a locality with no table (GeoNames point, height law; each fitted locality left out): {shares(unfitted)}; max {unfitted.worst}")
    print(f"   the height law at the fitted points (exact coordinates with that law as fallback): {shares(at_point)}; max {at_point.worst}")
    for fallback in ({"type": "standard"}, {"type": "refraction", "fallback": None}, {"type": "standard_with_dip"}):
        s = C.Score()
        q = {**final, "fallback": fallback}
        for vid, t in tables.items():
            score(t, list(range(len(t.days))), q, zones[vid], s, exact=True, elevation=LOCALITIES[vid][3])
        print(f"   exact coordinates, PerZone fallback {fallback['type']}: sunrise {s.exact['SUNRISE']}/{s.total['SUNRISE']} "
              f"(w1 {s.within1['SUNRISE']}), Maghrib {s.exact['MAGHRIB']}/{s.total['MAGHRIB']} (w1 {s.within1['MAGHRIB']}); max {s.worst} min")

    # VSOP87 against MEEUS on the month split, the final profile otherwise (no re-selection).
    for model in ("MEEUS", "VSOP87"):
        s = C.Score()
        q = {**final, "sun": model}
        for _, fit_days, held in folds:
            zz = fit_zones(tables, q, fit_days)
            for vid, t in tables.items():
                score(t, days_of(t, held), q, zz[vid], s)
        print(f"   sun {model}, month split with the final stages: {sum(s.exact.values())}/{sum(s.total.values())} exact")

    # The archived 2024 tables: another year, with the zones fitted on every 2026 day.
    archived = C.Score()
    by_zone_archive = {}
    for t in archive:
        s = by_zone_archive.setdefault(t.vid, C.Score())
        score(t, list(range(len(t.days))), final, zones[t.vid], archived, s, per_zone[t.vid])
    if archive:
        print(f"   archive (held-out year 2024): {shares(archived)}; max {archived.worst} min")
        for vid, s in by_zone_archive.items():
            print(f"      zone {vid}: {shares(s)}")

    # Fit records (6.3): held-out shares; the month split, and the archive when present.
    first, last = days[0], days[-1]
    total_days = len(days)
    fitted_on = [C.sample(SOURCE, first, last, total_days, len(tables))]
    held_out = [C.sample(SOURCE, first, last, total_days, len(tables), month_split=True)]
    profile_score = split
    if archive:
        held_out.append(C.sample(ARCHIVE_SOURCE, min(t.days[0] for t in archive), max(t.days[-1] for t in archive),
                                 len({d for t in archive for d in t.days}), len(by_zone_archive)))
        profile_score = merge(split, archived)
    profile_fit = C.fit_stats(fitted_on, held_out, profile_score)

    out_zones = []
    kinds = {"fitted": 0, "located": 0, "geonames": 0}
    for vid in sorted(LOCALITIES, key=int):
        gid, lat, lng, dem, feature = LOCALITIES[vid]
        record = None
        if vid in zones:
            z, kind = zones[vid], "fitted"
            t = tables[vid]
            samples = [C.sample(SOURCE, t.days[0], t.days[-1], len(t.days), 1, month_split=True)]
            if vid in by_zone_archive:
                arch = [a for a in archive if a.vid == vid]
                samples.append(C.sample(ARCHIVE_SOURCE, min(a.days[0] for a in arch), max(a.days[-1] for a in arch),
                                        sum(len(a.days) for a in arch), 1))
            record = C.fit_stats([C.sample(SOURCE, t.days[0], t.days[-1], len(t.days), 1)], samples, per_zone[vid])
        elif vid in LOCATED:
            z, kind = dict(zip(("lat", "lng", "sr", "ss"), LOCATED[vid])), "located"
        else:
            sr, ss = by_law(law, dem)
            z, kind = {"lat": lat, "lng": lng, "sr": sr, "ss": ss}, "geonames"
        kinds[kind] += 1
        named = {"centre": C.point(lat, lng)}
        if feature == "P":
            named["catalogueIds"] = [gid]
        zone = {"id": vid, "nameAr": names[vid], "point": C.point(z["lat"], z["lng"]), "elevationM": dem,
                "sunriseAltitudeDeg": round(z["sr"], 3), "sunsetAltitudeDeg": round(z["ss"], 3), "namedPlaces": [named], "fit": record}
        if vid in ("24", "40"):
            zone["country"] = "ES"
        out_zones.append(zone)
    path = C.write_asset("ma_habous", "ma_habous", "MA", "NEAREST", out_zones, fit=profile_fit,
                         source=f"Ministry of Habous and Islamic Affairs (habous.gov.ma), {len(LOCALITIES)} locality tables; "
                                f"points and horizons fitted on {len(tables)} localities' tables of {first} to {last}")
    problems = validate_asset.problems_of(path)
    print(f"Profile (all days): Fajr {final['fajr']} {final['rounding']['FAJR']}, Isha {final['isha']} {final['rounding']['ISHA']}, "
          f"Asr {final['asrSun']} {final['rounding']['ASR']}, offsets {final['offsets']}, sun {final['sun']}; "
          f"the halves chose " + "; ".join(f"{'odd' if r else 'even'} months: Fajr {p['fajr']} {p['rounding']['FAJR']}, "
                                           f"Isha {p['isha']} {p['rounding']['ISHA']}" for r, p in chosen.items()))
    print(f"Asset {path.name}: {len(out_zones)} zones ({kinds}); validate_asset: {'OK' if not problems else problems}")
    print(f"Done in {time.time() - started:.0f} s")
    return 1 if problems else 0


def merge(*scores: C.Score) -> C.Score:
    out = C.Score()
    for s in scores:
        for e in EVENTS:
            out.exact[e] += s.exact[e]
            out.within1[e] += s.within1[e]
            out.total[e] += s.total[e]
            out.months[e] = [a + b for a, b in zip(out.months[e], s.months[e])]
        out.worst = max(out.worst, s.worst)
        out.misses += s.misses
    return out


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
