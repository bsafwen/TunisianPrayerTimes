"""Held-out check on the Jordanian ministry's own 2025 calendar: 31 zone-days transcribed by hand from the scanned
PDF (test-data-local/arab-prototype/jo/jo2025_sample.txt), scored with the zone inputs fitted on 2023 and with the
shipped Accurate Times inputs."""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import engine
import compare as C

sys.stdout.reconfigure(encoding='utf-8')
EV = C.EVENTS


def main():
    fitted = json.load(open(os.path.join(C.RESULTS, 'fitted_jo2023.json'), encoding='utf-8'))
    locs = C.load_at(('JORDAN',))
    rows = [l.rstrip().split('|') for l in open(os.path.join(C.DATA, 'jo', 'jo2025_sample.txt'), encoding='utf-8')
            if l.strip() and not l.startswith('#')]
    for label in ('fitted on 2023', 'shipped inputs'):
        exact = {e: 0 for e in EV}
        misses = []
        for zone, date, times in rows:
            f = fitted[zone]
            loc = dict(locs[f['start']])
            if label == 'fitted on 2023':
                loc.update({k: f[k] for k in ('lat', 'lon', 'diam', 'elev', 'elev_set') if k in f})
            y, m, d = map(int, date.split('-'))
            ex = engine.day_times(loc, y, m, d, 3.0, {})
            off = [C.to_min(t) for t in times.split()]
            for k, e in enumerate(EV):
                got = engine.round_min(ex[e], 'nearest')
                if got == off[k]:
                    exact[e] += 1
                else:
                    misses.append(f"{zone} {date} {e}: official {times.split()[k]}, model {engine.hhmm(got)}")
        print(f"{label:16s} " + ' '.join(f"{e}:{exact[e]}" for e in EV) + f"  total {sum(exact.values())}/{6 * len(rows)}")
        for m in misses[:10]:
            print('   miss', m)


if __name__ == '__main__':
    main()
