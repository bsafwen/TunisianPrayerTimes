"""Fit per-zone inputs (lon, diameter, lat, elevation) starting from Accurate Times' entries, to maximise
exact minutes against an official table. Engine physics fixed. Usage: python -I fit_zones.py <target> [adds-json]"""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import engine
import compare as C

sys.stdout.reconfigure(encoding='utf-8')
EV = C.EVENTS


def evaluate(loc, table, tz, variant, events=EV, days=None):
    days = days or sorted(table)
    exact = {e: 0 for e in events}
    for day in days:
        ex = engine.day_times(loc, *day, tz, variant)
        for k, e in enumerate(EV):
            if e in events and ex[e] is not None and table[day][k] == engine.round_min(ex[e], variant.get('round', {}).get(e, 'nearest')):
                exact[e] += 1
    return exact


def search(loc, key, lo, hi, steps, table, tz, variant, events):
    events = EV  # always score all six prayers, so no step can break another prayer
    days = sorted(table)[::2]
    best_v, best_s = loc[key], sum(evaluate(loc, table, tz, variant, events, days).values())
    centre = loc[key]
    for step in steps:
        v = max(lo, centre - 8 * step)
        while v <= min(hi, centre + 8 * step) + 1e-12:
            trial = dict(loc, **{key: v})
            s = sum(evaluate(trial, table, tz, variant, events, days).values())
            if s > best_s:
                best_v, best_s = v, s
            v += step
        centre = best_v
    loc[key] = best_v
    return best_s


def scan(loc, key, values, table, tz, variant):
    days = sorted(table)[::3]
    best_v, best_s = loc[key], sum(evaluate(loc, table, tz, variant, EV, days).values())
    for v in values:
        s = sum(evaluate(dict(loc, **{key: v}), table, tz, variant, EV, days).values())
        if s > best_s:
            best_v, best_s = v, s
    loc[key] = best_v


def fit(loc, table, tz, variant):
    loc = dict(loc)
    loc.setdefault('elev_set', loc['elev'])
    for _ in range(2):
        search(loc, 'lon', loc['lon'] - 0.3, loc['lon'] + 0.3, [0.02, 0.004, 0.001], table, tz, variant, None)
        search(loc, 'lat', loc['lat'] - 0.3, loc['lat'] + 0.3, [0.02, 0.004, 0.001], table, tz, variant, None)
        scan(loc, 'diam', [x * 2.0 for x in range(0, 41)], table, tz, variant)
        search(loc, 'diam', 0, 80, [0.5, 0.1], table, tz, variant, None)
        for key in ('elev', 'elev_set'):
            scan(loc, key, list(range(0, 2601, 50)), table, tz, variant)
            search(loc, key, -400, 3000, [10, 2], table, tz, variant, None)
    return loc


def main():
    kind = sys.argv[1]
    variant = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
    targets, tzs = C.load_targets(kind)
    locs = C.load_at(('JORDAN',) if kind in ('jo2023', 'scjo') else ('UAE',))
    tot = {e: [0, 0] for e in EV}
    fitted = {}
    for zone, table in targets.items():
        ranked = sorted(locs.items(), key=lambda kv: -C.dhuhr_score(kv[1], table, tzs[zone], variant))[:6]
        sample = sorted(table)[::4]
        name, start = max(ranked, key=lambda kv: sum(evaluate(kv[1], table, tzs[zone], variant, EV, sample).values()))
        loc = fit(start, table, tzs[zone], variant)
        ex = evaluate(loc, table, tzs[zone], variant)
        n = len(table)
        fitted[zone] = dict(start=name, **{k: round(v, 4) for k, v in loc.items() if k in ('lat', 'lon', 'diam', 'elev', 'elev_set')})
        d = lambda k, s: f"{loc[k]-start[k]:+.3f}" if k in ('lat', 'lon') else f"{loc[k]-start[k]:+.0f}"
        print(f"{zone:20s} {name:28s} dlat {d('lat',0)} dlon {d('lon',0)} diam {start['diam']:.0f}->{loc['diam']:.2f} elev {start['elev']:.0f}->{loc['elev']:.0f}/{loc['elev_set']:.0f} | "
              + ' '.join(f"{e[:3]}:{ex[e]}" for e in EV) + f" /{n}", flush=True)
        for e in EV:
            tot[e][0] += ex[e]
            tot[e][1] += n
    print('TOTAL', ' '.join(f"{e}:{100*a/b:.2f}%" for e, (a, b) in tot.items()))
    json.dump(fitted, open(os.path.join(C.RESULTS, f'fitted_{kind}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
