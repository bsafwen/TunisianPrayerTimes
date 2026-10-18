"""Syria's masjidak tables against aladhan (MWL 18/17, PrayTimes' one pass, Dhuhr + 1, Maghrib + 2, nearest minute),
per region and per layout, with every source year of 2000-2032: the tool that found the table years and the north-east's
layout (2026-10-18; fit.py does the same with the screens' row rules).
  leap    366 rows in a leap year's day order; for a common source year row 59 (29 February) copies 1 March;
  nofeb29 rows 0-364 are the source year's days without 29 February, row 365 copies 31 December.
For each region: the year and layout by Dhuhr's windows, the point (latitude scanned, longitude by windows over Fajr,
sunrise, Dhuhr, Maghrib and Isha) for the best six, then Asr's server fraction f. Prints and writes a TSV line per
region (the best year and layout).

Usage: python -I -X utf8 layout_fit.py <out.tsv> <rid,...> [tz] [lat0] [workers]
"""
import datetime
import json
import sys
from multiprocessing import Pool
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1]))
import aladhan_model as A  # noqa: E402
import common  # noqa: E402

MJ = common.LOCAL_DATA / "research-2026-10-18" / "SY" / "masjidak"
KEYS = ("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")
EV = ("FAJR", "SUNRISE", "DHUHR", "ASR", "MAGHRIB", "ISHA")
TZ = float(sys.argv[3]) if len(sys.argv) > 3 else 3.0
LAT0 = float(sys.argv[4]) if len(sys.argv) > 4 else 35.5


def cover(wins):
    marks = sorted([(a, 1) for a, _ in wins] + [(b, -1) for _, b in wins])
    best = cur = 0
    at = None
    for i, (x, s) in enumerate(marks):
        cur += s
        nxt = marks[i + 1][0] if i + 1 < len(marks) else x
        if nxt > x and cur > best:
            best, at = cur, (x + nxt) / 2
    return best, at


def dates(year, layout):
    leap = year % 4 == 0
    days = [datetime.date(year, 1, 1) + datetime.timedelta(days=i) for i in range(366 if leap else 365)]
    if layout == "leap":
        if leap:
            return days
        return days[:59] + [None] + days[59:]
    no29 = [d for d in days if (d.month, d.day) != (2, 29)]
    return no29 + [None]


def rows_of(raw, year, layout):
    out = []
    for r, d in zip(raw, dates(year, layout)):
        if d is not None:
            out.append((d, {e: int(r[k].split(":")[0]) * 60 + int(r[k].split(":")[1]) for e, k in zip(EV, KEYS)}))
    return out


def point_fit(rows, lat0):
    best = None
    for span, step, centre in ((2.5, 0.05, lat0), (0.06, 0.002, None), (0.003, 0.0001, None)):
        centre = best[1] if centre is None else centre
        for k in range(-int(round(span / step)), int(round(span / step)) + 1):
            lat = round(centre + k * step, 5)
            wins = []
            for day, t in rows:
                for e in ("FAJR", "SUNRISE", "DHUHR", "MAGHRIB", "ISHA"):
                    u0 = A.unrounded(day, lat, 38.0, e, tz=TZ)
                    wins.append((38.0 + (u0 - (t[e] + 0.5)) / 4, 38.0 + (u0 - (t[e] - 0.5)) / 4))
            n, lng = cover(wins)
            if best is None or n > best[0]:
                best = (n, lat, lng)
    return best


def exact(rows, lat, lng, e, f=0.0):
    return sum(A.minute(d, lat, lng, e, f=f, tz=TZ) == t[e] for d, t in rows)


def solve(rid):
    raw = json.loads((MJ / f"region_{rid}.json").read_text(encoding="utf-8-sig"))
    cands = []
    for layout in ("leap", "nofeb29"):
        for year in range(2000, 2033):
            rows = rows_of(raw, year, layout)
            wins = []
            for d, t in rows:
                u = A.unrounded(d, LAT0, 38.0, "DHUHR", tz=TZ)
                wins.append((38.0 + (u - (t["DHUHR"] + 0.5)) / 4, 38.0 + (u - (t["DHUHR"] - 0.5)) / 4))
            cands.append((cover(wins)[0], year, layout))
    cands.sort(reverse=True)
    best = None
    for n_dh, year, layout in cands[:6]:
        rows = rows_of(raw, year, layout)
        n, lat, lng = point_fit(rows, LAT0)
        if best is None or n > best[0]:
            best = (n, year, layout, lat, lng, rows)
    n, year, layout, lat, lng, rows = best
    asr = max((exact(rows, lat, lng, "ASR", k / 288), k / 288) for k in range(288))
    per = {e: exact(rows, lat, lng, e) for e in ("FAJR", "SUNRISE", "DHUHR", "MAGHRIB", "ISHA")}
    top = " ".join(f"{c[1]}{c[2][0]}:{c[0]}" for c in cands[:6])
    return "\t".join(map(str, [rid, year, layout, f"{lat:.5f}", f"{lng:.5f}", f"{asr[1]:.4f}", per["FAJR"], per["SUNRISE"],
                              per["DHUHR"], asr[0], per["MAGHRIB"], per["ISHA"], len(rows), top]))


if __name__ == "__main__":
    ids = [int(x) for x in sys.argv[2].split(",")]
    with Pool(int(sys.argv[5]) if len(sys.argv) > 5 else 10) as pool:
        lines = pool.map(solve, ids)
    with open(sys.argv[1], "w", encoding="utf-8", newline="\n") as fh:
        fh.write("rid\tyear\tlayout\tlat\tlng\tasr_f\tfajr\tsunrise\tdhuhr\tasr\tmaghrib\tisha\tn\tdhuhr_top\n")
        fh.write("\n".join(lines) + "\n")
    for line in lines:
        print(line)
