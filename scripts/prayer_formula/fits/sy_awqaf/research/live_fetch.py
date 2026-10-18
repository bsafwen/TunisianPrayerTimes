"""Fetches what a live screen at each live town would load: api.aladhan.com/v1/calendar/2024 with the town's catalogue
point and `shafaq=general` only, as masjidi.min.js builds it (`aladhan-api` with latitude, longitude and shafaq). The
live towns are fit.py's (from the screens sample). One request every 1.5 s; files already there are kept. Writes
<out>/<catalogue id>.json and MANIFEST.tsv; the default folder is fit.py's LIVE
(test-data-local/research-2026-10-18/SY/aladhan_live).

Usage: python -I -X utf8 live_fetch.py [out folder]
"""
import hashlib
import json
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fit as F  # noqa: E402

out = Path(sys.argv[1]) if len(sys.argv) > 1 else F.LIVE
out.mkdir(parents=True, exist_ok=True)
towns = F.live_towns(F.load_screens(F.MOSQUES, live=True), F.load_screens(F.MOSQUES), F.load_catalogue())
manifest = ["id\tname\tlat\tlng\tbytes\tsha256\tmethod\tfetched_utc"]
for c, n_live, n_table, name in towns:
    path = out / f"{c['id']}.json"
    if not path.exists():
        url = f"https://api.aladhan.com/v1/calendar/2024?latitude={c['lat']}&longitude={c['lng']}&shafaq=general"
        with urllib.request.urlopen(url, timeout=60) as r:
            path.write_bytes(r.read())
        time.sleep(1.5)
    data = path.read_bytes()
    body = json.loads(data)
    days = [d for month in body["data"].values() for d in month]
    meta = days[0]["meta"]
    manifest.append("\t".join([str(c["id"]), name, str(c["lat"]), str(c["lng"]), str(len(data)), hashlib.sha256(data).hexdigest(),
                               f"{meta['method']['id']} {meta['method']['name']}",
                               time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(path.stat().st_mtime))]))
    print(c["id"], name, len(days), "days, method", meta["method"]["id"], meta["timezone"], flush=True)
(out / "MANIFEST.tsv").write_text("\n".join(manifest) + "\n", encoding="utf-8", newline="\n")
