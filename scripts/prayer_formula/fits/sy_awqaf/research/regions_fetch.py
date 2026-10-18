"""Fetches the Syrian Ministry of Awqaf's region prayer tables (the JSON its mosque screens and the app «مسجدك» load:
https://tv.masjidak.mow.gov.sy/upload/json_prayers_times/prayer_times_region_json_<id>.json, 366 rows of six times)
into test-data-local/research-2026-10-18/SY/masjidak (overwriting a previous fetch), with MANIFEST.tsv (id, bytes,
SHA-256, Last-Modified). Ids that answer 404 are listed. On 2026-10-18, ids 761-870 answered (837 did not).

Usage: python -I -X utf8 regions_fetch.py [first last]
"""
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import common  # noqa: E402

root = common.LOCAL_DATA / "research-2026-10-18" / "SY" / "masjidak"
first, last = (int(sys.argv[1]), int(sys.argv[2])) if len(sys.argv) > 2 else (700, 950)
root.mkdir(parents=True, exist_ok=True)
URL = "https://tv.masjidak.mow.gov.sy/upload/json_prayers_times/prayer_times_region_json_{}.json"
rows, missing = [], []
for rid in range(first, last + 1):
    req = urllib.request.Request(URL.format(rid), headers={"User-Agent": "Mozilla/5.0"})
    try:
        r = urllib.request.urlopen(req, timeout=60)
        body = r.read()
    except urllib.error.HTTPError as e:
        missing.append((rid, e.code))
        continue
    data = json.loads(body.decode("utf-8-sig"))
    if not isinstance(data, list) or len(data) != 366:
        raise SystemExit(f"{rid}: unexpected shape")
    (root / f"region_{rid}.json").write_bytes(body)
    rows.append((rid, len(body), hashlib.sha256(body).hexdigest(), r.headers.get("Last-Modified", "")))
    time.sleep(0.3)
with open(root / "MANIFEST.tsv", "w", encoding="utf-8", newline="\n") as fh:
    fh.write("id\tbytes\tsha256\tlast_modified\n")
    for row in rows:
        fh.write("\t".join(map(str, row)) + "\n")
print(f"saved {len(rows)} regions ({rows[0][0] if rows else '-'}..{rows[-1][0] if rows else '-'}); "
      f"missing {len(missing)}: " + ", ".join(f"{i} ({c})" for i, c in missing if first <= i <= last)[:600])
