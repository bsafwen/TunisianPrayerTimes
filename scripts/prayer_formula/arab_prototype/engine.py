"""Prototype prayer-time engine following the published method that ICOP's Accurate Times names:
VSOP87 sun (+FK5, nutation, aberration), Delta T, refraction from pressure/temperature, elevation dip
for sunrise/sunset only, and 'city diameter' (Fajr and sunrise at the eastern edge).
Standard library only. A research prototype for the fitting step (AM2), not app code.
Data (VSOP87D, official tables) lives in the git-ignored test-data-local/arab-prototype, or ARAB_PROTOTYPE_DATA."""
import math, os
from functools import lru_cache

DEG = math.pi / 180.0
ASEC = DEG / 3600.0
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
DATA = os.environ.get('ARAB_PROTOTYPE_DATA', os.path.join(REPO, 'test-data-local', 'arab-prototype'))
VSOP_PATH = os.path.join(DATA, 'vsop', 'VSOP87D.ear')


def _load_vsop(path, min_amp=1e-9):
    series = {1: {}, 2: {}, 3: {}}
    cur = None
    for line in open(path, encoding='ascii'):
        if 'VARIABLE' in line:
            var = int(line.split('VARIABLE')[1].split()[0])
            power = int(line.split('*T**')[1].split()[0])
            cur = series[var].setdefault(power, [])
            continue
        parts = line.split()
        a, b, c = float(parts[-3]), float(parts[-2]), float(parts[-1])
        if a >= min_amp:
            cur.append((a, b, c))
    return series


VSOP = _load_vsop(VSOP_PATH)


def _vsop_sum(var, tau):
    total = 0.0
    for power, terms in VSOP[var].items():
        s = 0.0
        for a, b, c in terms:
            s += a * math.cos(b + c * tau)
        total += s * tau ** power
    return total


def julian_day(y, m, d, hour=0.0):
    if m <= 2:
        y -= 1
        m += 12
    a = y // 100
    b = 2 - a + a // 4
    return int(365.25 * (y + 4716)) + int(30.6001 * (m + 1)) + d + b - 1524.5 + hour / 24.0


def delta_t_seconds(year_frac):
    """Espenak & Meeus (2006) polynomial, 2005-2050."""
    t = year_frac - 2000.0
    return 62.92 + 0.32217 * t + 0.005589 * t * t


def nutation(t):
    omega = (125.04452 - 1934.136261 * t + 0.0020708 * t * t + t ** 3 / 450000.0) * DEG
    ls = (280.4665 + 36000.7698 * t) * DEG
    lm = (218.3165 + 481267.8813 * t) * DEG
    dpsi = (-17.20 * math.sin(omega) - 1.32 * math.sin(2 * ls) - 0.23 * math.sin(2 * lm) + 0.21 * math.sin(2 * omega)) * ASEC
    deps = (9.20 * math.cos(omega) + 0.57 * math.cos(2 * ls) + 0.10 * math.cos(2 * lm) - 0.09 * math.cos(2 * omega)) * ASEC
    return dpsi, deps


@lru_cache(maxsize=200000)
def sun_apparent(jde_rounded):
    """Apparent RA, Dec (radians), distance (AU), and nutation terms at JDE (rounded key)."""
    jde = jde_rounded
    tau = (jde - 2451545.0) / 365250.0
    t = tau * 10.0
    l = _vsop_sum(1, tau)
    b = _vsop_sum(2, tau)
    r = _vsop_sum(3, tau)
    theta = l + math.pi
    beta = -b
    # FK5 correction
    lp = theta - (1.397 * t + 0.00031 * t * t) * DEG
    theta += -0.09033 * ASEC
    beta += 0.03916 * (math.cos(lp) - math.sin(lp)) * ASEC
    dpsi, deps = nutation(t)
    lam = theta + dpsi - 20.4898 * ASEC / r
    eps0 = (23 + 26 / 60.0 + 21.448 / 3600.0 - (46.8150 * t + 0.00059 * t * t - 0.001813 * t ** 3) / 3600.0) * DEG
    eps = eps0 + deps
    ra = math.atan2(math.sin(lam) * math.cos(eps) - math.tan(beta) * math.sin(eps), math.cos(lam))
    dec = math.asin(math.sin(beta) * math.cos(eps) + math.cos(beta) * math.sin(eps) * math.sin(lam))
    return ra, dec, r, dpsi, eps


_TABLE = {}


def _table_for_year(year):
    """Hourly apparent sun (TT grid) from 2 days before to 2 days after the UT year, for interpolation."""
    if year in _TABLE:
        return _TABLE[year]
    start = julian_day(year, 1, 1) - 2.0
    n = 24 * 370
    rows = []
    for i in range(n):
        ra, dec, r, dpsi, eps = sun_apparent(start + i / 24.0)
        rows.append((ra, dec, r, dpsi, eps))
    # unwrap RA
    out = []
    prev = None
    off = 0.0
    for ra, dec, r, dpsi, eps in rows:
        if prev is not None and ra + off < prev - math.pi:
            off += 2 * math.pi
        out.append((ra + off, dec, r, dpsi, eps))
        prev = ra + off
    _TABLE[year] = (start, out)
    return _TABLE[year]


def _interp(jde):
    year = int(2000 + (jde - 2451545.0) / 365.25)
    start, rows = _table_for_year(year)
    x = (jde - start) * 24.0
    i = int(x) - 1
    i = max(0, min(len(rows) - 4, i))
    u = x - i  # in [1,2) normally
    res = []
    for k in range(5):
        y0, y1, y2, y3 = rows[i][k], rows[i + 1][k], rows[i + 2][k], rows[i + 3][k]
        # cubic Lagrange through nodes 0,1,2,3 at u
        l0 = -(u - 1) * (u - 2) * (u - 3) / 6.0
        l1 = u * (u - 2) * (u - 3) / 2.0
        l2 = -u * (u - 1) * (u - 3) / 2.0
        l3 = u * (u - 1) * (u - 2) / 6.0
        res.append(y0 * l0 + y1 * l1 + y2 * l2 + y3 * l3)
    return tuple(res)


USE_TABLE = True


def sun_at(jd_ut):
    year = 2000.0 + (jd_ut - 2451545.0) / 365.25
    jde = jd_ut + delta_t_seconds(year) / 86400.0
    if USE_TABLE:
        return _interp(jde)
    return sun_apparent(round(jde, 6))  # ~0.09 s resolution


def gast_deg(jd_ut, dpsi, eps):
    t = (jd_ut - 2451545.0) / 36525.0
    gmst = 280.46061837 + 360.98564736629 * (jd_ut - 2451545.0) + 0.000387933 * t * t - t ** 3 / 38710000.0
    return (gmst + dpsi * math.cos(eps) / DEG) % 360.0


def hour_angle_deg(jd_ut, lon_deg):
    ra, dec, r, dpsi, eps = sun_at(jd_ut)
    h = (gast_deg(jd_ut, dpsi, eps) + lon_deg - ra / DEG) % 360.0
    if h > 180:
        h -= 360
    return h, dec, r


def transit(jd0_ut_local_noon_guess, lon):
    jd = jd0_ut_local_noon_guess
    for _ in range(8):
        h, dec, r = hour_angle_deg(jd, lon)
        step = h / 360.98564736629
        jd -= step
        if abs(step) < 1e-8:
            break
    return jd


def event(jd_transit, lat, lon, alt_deg, side):
    """UT JD when the sun's centre (geometric) reaches alt_deg; side -1 morning, +1 evening. None if never."""
    jd = jd_transit
    for _ in range(12):
        h, dec, r = hour_angle_deg(jd, lon)
        cos_h0 = (math.sin(alt_deg * DEG) - math.sin(lat * DEG) * math.sin(dec)) / (math.cos(lat * DEG) * math.cos(dec))
        if abs(cos_h0) > 1:
            return None
        target = side * math.acos(cos_h0) / DEG
        step = (h - target) / 360.98564736629
        jd -= step
        if abs(step) < 1e-8:
            break
    return jd


def bennett_refraction_deg(apparent_alt_deg, pressure, temp):
    a = apparent_alt_deg
    r_arcmin = 1.0 / math.tan((a + 7.31 / (a + 4.4)) * DEG)
    return r_arcmin / 60.0 * (pressure / 1010.0) * (283.0 / (273.0 + temp))


def dip_deg(elev_m, model):
    if elev_m <= 0 or model == 'none':
        return 0.0
    if model == 'geom':
        return math.acos(6378137.0 / (6378137.0 + elev_m)) / DEG
    if model == 'naut':  # 1.76' sqrt(h): dip with terrestrial refraction
        return 1.76 * math.sqrt(elev_m) / 60.0
    raise ValueError(model)


def horizon_alt_deg(loc, variant, r_au):
    sd = 959.63 / 3600.0 / r_au
    dip = dip_deg(loc['elev'], variant.get('dip', 'geom'))
    refr = variant.get('refr', 'bennett_at_horizon')
    p, t = loc.get('p', 1010.0), loc.get('t', 10.0)
    if refr == 'bennett_at_horizon':
        r = bennett_refraction_deg(-dip, p, t)
    elif refr == 'bennett_at_zero':
        r = bennett_refraction_deg(0.0, p, t)
    elif refr == 'std34':
        r = 34 / 60.0
    elif refr == 'std34_pt':
        r = 34 / 60.0 * (p / 1010.0) * (283.0 / (273.0 + t))
    else:
        raise ValueError(refr)
    return -(sd + r + dip)


def day_times(loc, y, m, d, tz, variant=None):
    """Exact local minutes for fajr, sunrise, dhuhr, asr, maghrib, isha."""
    variant = variant or {}
    lat, lon = loc['lat'], loc['lon']
    diam = loc.get('diam', 0.0) if variant.get('use_diam', True) else 0.0
    lon_east = lon + (diam / (6378.137 * math.cos(lat * DEG))) / DEG
    jd0 = julian_day(y, m, d)  # 0h UT of the civil date
    guess = jd0 + (12.0 - tz - lon / 15.0) / 24.0
    jt_w = transit(guess, lon)
    jt_e = transit(guess, lon_east)
    _, dec_noon, r = hour_angle_deg(jt_w, lon)
    h_hor = horizon_alt_deg(loc, variant, r)
    h_set = horizon_alt_deg(dict(loc, elev=loc['elev_set']), variant, r) if 'elev_set' in loc else h_hor
    fajr_angle = variant.get('fajr', 18.0)
    isha_angle = variant.get('isha', 18.0)
    shadow = variant.get('asr', 1)
    pp, tt = loc.get('p', 1010.0), loc.get('t', 10.0)
    if variant.get('asr_mode', 'apparent') == 'apparent':
        # the shadow is cast by the apparent (refracted) sun, at noon and at Asr
        z_true = abs(lat * DEG - dec_noon) / DEG
        noon_app = (90.0 - z_true) + bennett_refraction_deg(90.0 - z_true, pp, tt)
        a_app = math.atan(1.0 / (shadow + math.tan((90.0 - noon_app) * DEG))) / DEG
        asr_alt = a_app - bennett_refraction_deg(a_app, pp, tt)
    else:
        asr_alt = math.atan(1.0 / (shadow + math.tan(abs(lat * DEG - dec_noon)))) / DEG
    j = {
        'fajr': event(jt_e, lat, lon_east, -fajr_angle, -1),
        'sunrise': event(jt_e, lat, lon_east, h_hor, -1),
        'dhuhr': jt_w,
        'asr': event(jt_w, lat, lon, asr_alt, +1),
        'maghrib': event(jt_w, lat, lon, h_set, +1),
        'isha': event(jt_w, lat, lon, -isha_angle, +1),
    }
    out = {}
    for k, v in j.items():
        out[k] = None if v is None else ((v - jd0) * 24.0 + tz) * 60.0
    adds = variant.get('add', {})
    for k, a in adds.items():
        out[k] += a
    return out


def round_min(x, mode):
    if mode == 'nearest':
        return math.floor(x + 0.5)
    if mode == 'up':
        return math.ceil(x - 1e-9)
    if mode == 'down':
        return math.floor(x + 1e-9)
    raise ValueError(mode)


def hhmm(minutes):
    return f'{int(minutes) // 60:02d}:{int(minutes) % 60:02d}'


if __name__ == '__main__':
    amman = dict(lat=32 + 1 / 60 + 13 / 3600, lon=35 + 49 / 60 + 46 / 3600, elev=1100, diam=16, p=891, t=15)
    t = day_times(amman, 2026, 10, 8, 3)
    print({k: hhmm(round_min(v, 'nearest')) for k, v in t.items()})
