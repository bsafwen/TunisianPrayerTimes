"""Held-out check: zone inputs fitted on the Jordanian ministry's 2023 calendar, applied unchanged to 2026
(Salat Calendar's 'official' Jordan tables, which reproduce the ministry's within about a minute)."""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import engine
import compare as C
from fit_zones import evaluate

sys.stdout.reconfigure(encoding='utf-8')
EV = C.EVENTS
SC_ID = {'عمان': 1, 'اربد': 9, 'اﻷغوار الشمالية': 19, 'جرش وعجلون': 12, 'المفرق': 16, 'أم القطين': 34, 'ذيبان': 5,
         'اﻷزرق': 3, 'اﻷغوار الوسطى': 32, 'الكرك': 13, 'الطفيلة': 33, 'الشوبك والبتراء': 30, 'معان': 15, 'العقبة': 2,
         'الضليل والهاشمية': 6}


def main():
    fitted = json.load(open(os.path.join(C.RESULTS, 'fitted_jo2023.json'), encoding='utf-8'))
    sc, _ = C.load_targets('scjo')
    locs = C.load_at(('JORDAN',))
    tot = {e: [0, 0] for e in EV}
    for zone, f in fitted.items():
        table = sc.get(f'sc{SC_ID[zone]}')
        if not table:
            continue
        loc = dict(locs[f['start']])
        loc.update({k: f[k] for k in ('lat', 'lon', 'diam', 'elev', 'elev_set') if k in f})
        ex = evaluate(loc, table, 3.0, {})
        n = len(table)
        print(f"{zone:20s} sc{SC_ID[zone]:<3d} " + ' '.join(f"{e[:3]}:{ex[e]}" for e in EV) + f" /{n}")
        for e in EV:
            tot[e][0] += ex[e]
            tot[e][1] += n
    print('TOTAL 2026 (held out)', ' '.join(f"{e}:{100*a/b:.2f}%" for e, (a, b) in tot.items()))


if __name__ == '__main__':
    main()
