"""Compare the prototype engine with official tables, using Accurate Times' location inputs.
Usage: python -I compare.py <target> [variant-json]
targets: jo2023 (ministry), scjo (Salat Calendar official Jordan 2026), scae (SC official UAE 2026), awqaf (UAE Awqaf, every 3rd day 2026)."""
import csv, json, math, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import engine

sys.stdout.reconfigure(encoding='utf-8')
DATA = engine.DATA
RESULTS = os.path.join(DATA, 'results')
ACCURATE_TIMES = os.environ.get('ACCURATE_TIMES_DIR', r'C:\Accurate Times')
EVENTS = ['fajr', 'sunrise', 'dhuhr', 'asr', 'maghrib', 'isha']


def load_at(prefixes):
    locs = {}
    for r in csv.reader(open(os.path.join(ACCURATE_TIMES, 'loc.txt'), encoding='latin-1')):
        name = r[0]
        if not name.upper().startswith(prefixes):
            continue
        lat = (int(r[1]) + int(r[2]) / 60 + int(r[3]) / 3600) * (1 if r[7] == '1' else -1)
        lon = (int(r[4]) + int(r[5]) / 60 + int(r[6]) / 3600) * (1 if r[8] == '1' else -1)
        locs[name] = dict(lat=lat, lon=lon, tz=float(r[9]), elev=float(r[10]), diam=float(r[11]), p=float(r[12]), t=float(r[13]))
    return locs


def to_min(s, pm=False):
    h, m = map(int, s.split(':'))
    if pm and h < 12:
        h += 12
    return h * 60 + m


def load_targets(kind):
    """Return {zone_name: {date(y,m,d): [six minutes]}} and the tz per zone."""
    out, tz = {}, {}
    if kind == 'jo2023':
        for r in csv.DictReader(open(os.path.join(DATA, 'jo', 'cal2023.csv'), encoding='utf-8')):
            y, m, d = map(int, r['date'].split('-'))
            v = [to_min(r['fajr']), to_min(r['sunrise']), to_min(r['dhuhr'], pm=r['dhuhr'] < '06'),
                 to_min(r['asr'], True), to_min(r['maghrib'], True), to_min(r['isha'], True)]
            out.setdefault(r['city'], {})[(y, m, d)] = v
            tz[r['city']] = 3.0
    elif kind in ('scjo', 'scae'):
        ids = range(1, 35) if kind == 'scjo' else range(35, 95)
        for i in ids:
            p = os.path.join(DATA, 'salat-calendar-official-2026', f'{i}.csv')
            if not os.path.exists(p):
                continue
            rows = {}
            for r in csv.DictReader(open(p, encoding='utf-8')):
                y, m, d = map(int, r['date'].split('-'))
                rows[(y, m, d)] = [to_min(r[e]) for e in EVENTS]
            out[f'sc{i}'] = rows
            tz[f'sc{i}'] = 3.0 if kind == 'scjo' else 4.0
    elif kind == 'awqaf':
        for f in ('ad36', 'ain37', 'dxb69'):
            rows = {}
            for chunk in open(os.path.join(DATA, 'ae', f + '.txt'), encoding='utf-8').read().strip().split(';'):
                parts = chunk.split()
                md = parts[0]
                vals = [int(x[:2]) * 60 + int(x[2:]) for x in parts[1:7]]
                rows[(2026, int(md[:2]), int(md[2:]))] = vals
            out[f] = rows
            tz[f] = 4.0
    return out, tz


def residuals(loc, table, tz, variant):
    res = {e: [] for e in EVENTS}
    for (y, m, d), off in sorted(table.items()):
        ex = engine.day_times(loc, y, m, d, tz, variant)
        for k, e in enumerate(EVENTS):
            if ex[e] is not None:
                res[e].append((off[k], ex[e]))
    return res


def score(pairs):
    """Best (offset k, rounding mode) -> exact share; also max abs diff for that choice."""
    best = None
    for mode in ('nearest', 'up', 'down'):
        for k in range(-4, 11):
            diffs = [o - (engine.round_min(x + k, mode)) for o, x in pairs]
            exact = sum(1 for z in diffs if z == 0)
            mx = max(abs(z) for z in diffs) if diffs else 0
            cand = (exact, -abs(k), mode, k, mx)
            if best is None or cand > best:
                best = cand
    exact, _, mode, k, mx = best
    return exact, len(pairs), mode, k, mx


def dhuhr_score(loc, table, tz, variant):
    days = sorted(table)[::7]
    pairs = []
    for day in days:
        ex = engine.day_times(loc, *day, tz, variant)
        pairs.append((table[day][2], ex['dhuhr']))
    return sum(1 for o, x in pairs if o == engine.round_min(x, 'nearest'))


def mean_resid(pairs):
    return sum(o - x for o, x in pairs) / len(pairs) if pairs else 0.0


def main():
    kind = sys.argv[1]
    variant = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
    targets, tzs = load_targets(kind)
    prefixes = ('JORDAN',) if kind in ('jo2023', 'scjo') else ('UAE',)
    locs = load_at(prefixes)
    total = {e: [0, 0] for e in EVENTS}
    for zone, table in targets.items():
        ranked = sorted(locs.items(), key=lambda kv: -dhuhr_score(kv[1], table, tzs[zone], variant))[:3]
        best = None
        for name, loc in ranked:
            res = residuals(loc, table, tzs[zone], variant)
            sc = {e: score(res[e]) for e in EVENTS}
            tot = sum(v[0] for v in sc.values())
            if best is None or tot > best[0]:
                best = (tot, name, sc)
        tot, name, sc = best
        res = residuals(locs[name], table, tzs[zone], variant)
        line = ' '.join(f"{e[:3]}:{v[0]}{'' if v[3]==0 else ('%+d' % v[3])}{v[2][0]}({mean_resid(res[e]):+.2f})" for e, v in sc.items())
        print(f'{zone:22s} -> {name:30s} {line}')
        for e in EVENTS:
            total[e][0] += sc[e][0]
            total[e][1] += sc[e][1]
    print('TOTAL', ' '.join(f"{e}:{100*a/b:.1f}%" for e, (a, b) in total.items()))


if __name__ == '__main__':
    main()
