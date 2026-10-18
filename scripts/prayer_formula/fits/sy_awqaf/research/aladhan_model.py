"""aladhan's PrayerTimes (the PHP port of PrayTimes.js behind api.aladhan.com) as plain functions: its low-precision
sun, one pass from the default times (local mean time), the Asr noon shadow's declination at gregoriantojd + f + 13/24
(f: the API server clock's day fraction when the table was fetched), tune minutes, nearest minute. The research
counterpart of fit.py's closed form, with free angles, horizon, factor, clock and tunes."""
import datetime
import math

D2R = math.pi / 180
DEFAULT = {"FAJR": 5, "SUNRISE": 6, "DHUHR": 12, "ASR": 13, "MAGHRIB": 18, "ISHA": 18}


def sind(x): return math.sin(x * D2R)
def cosd(x): return math.cos(x * D2R)
def fix(a, b): a = a - b * math.floor(a / b); return a + b if a < 0 else a


def julian(y, m, d):
    if m <= 2:
        y -= 1
        m += 12
    a = math.floor(y / 100)
    return math.floor(365.25 * (y + 4716)) + math.floor(30.6001 * (m + 1)) + d + 2 - a + math.floor(a / 4) - 1524.5


def sun(jd):
    d = jd - 2451545.0
    g = fix(357.529 + 0.98560028 * d, 360)
    q = fix(280.459 + 0.98564736 * d, 360)
    l = fix(q + 1.915 * sind(g) + 0.020 * sind(2 * g), 360)
    e = 23.439 - 0.00000036 * d
    ra = math.atan2(cosd(e) * sind(l), cosd(l)) / D2R / 15
    return math.asin(sind(e) * sind(l)) / D2R, q / 15 - fix(ra, 24)


def unrounded(day: datetime.date, lat: float, lng: float, e: str, *, fajr=18.0, isha=17.0, horizon=0.833, factor=1.0,
              f=0.0, tz=3.0, tune=None) -> float:
    """aladhan's time of [e] in local minutes (UTC+tz) before rounding."""
    tune = {"DHUHR": 1, "MAGHRIB": 2} if tune is None else tune
    jd = julian(day.year, day.month, day.day) - lng / 360
    t = DEFAULT[e] / 24
    decl, eqt = sun(jd + t)
    noon = fix(12 - eqt, 24)
    if e == "DHUHR":
        h = noon
    else:
        if e == "ASR":
            jdn = math.floor(julian(day.year, day.month, day.day) + 0.5)
            dz = sun(jdn + f + t)[0]
            angle, side = -math.atan(1 / (factor + math.tan(abs(lat - dz) * D2R))) / D2R, 1
        else:
            angle, side = {"FAJR": (fajr, -1), "SUNRISE": (horizon, -1), "MAGHRIB": (horizon, 1), "ISHA": (isha, 1)}[e]
        c = (-sind(angle) - sind(decl) * sind(lat)) / (cosd(decl) * cosd(lat))
        h = noon + side * math.acos(max(-1.0, min(1.0, c))) / D2R / 15
    return (h + tz - lng / 15 + tune.get(e, 0) / 60) * 60


def minute(day, lat, lng, e, **kw) -> int:
    return math.floor(unrounded(day, lat, lng, e, **kw) + 0.5)
