"""Samples the ministry's mosque screens (tv.masjidak.mow.gov.sy/<mosque id>) for their region file and place: one page
every [step] ids from [start] to [stop], a pause between requests, appending to a TSV (mosque id, region, city,
address, name; region `-` where the screen runs aladhan's API live). Already fetched ids are skipped, so passes with
other starts add to the same file. The 2026-10-18 sample (every 10th id from 3, every 29th from 1 and 14, up to 23,500)
is test-data-local/research-2026-10-18/SY/masjidak/mosques.tsv, which fit.py reads.

Usage: python -I -X utf8 screens_sample.py <out.tsv> <start> <stop> <step> [pause_s]
"""
import html
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

out = Path(sys.argv[1])
start, stop, step = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
pause = float(sys.argv[5]) if len(sys.argv) > 5 else 0.4
done = set()
if out.exists():
    for line in out.read_text(encoding="utf-8").splitlines()[1:]:
        done.add(int(line.split("\t")[0]))
else:
    out.write_text("id\tregion\tcity\taddress\tname\n", encoding="utf-8", newline="\n")

REGION = re.compile(r"prayer_times_region_json_(\d+)\.json")
TEMPLATE = re.compile(r'"templateData":(\{[^{}]*\})')
misses = 0
with out.open("a", encoding="utf-8", newline="\n") as fh:
    for mid in range(start, stop, step):
        if mid in done:
            continue
        try:
            with urllib.request.urlopen(f"https://tv.masjidak.mow.gov.sy/{mid}", timeout=30) as r:
                text = html.unescape(r.read().decode("utf-8", "replace"))
        except urllib.error.HTTPError:
            misses += 1
            time.sleep(pause)
            continue
        except Exception as e:  # noqa: BLE001 - a network hiccup: note it and go on
            print(mid, "error", e, flush=True)
            time.sleep(5)
            continue
        region = REGION.search(text)
        tmpl = TEMPLATE.search(text)
        data = {}
        if tmpl:
            try:
                data = json.loads(tmpl.group(1))
            except ValueError:
                data = {}
        clean = lambda s: (s or "").replace("\t", " ").replace("\n", " ").strip()  # noqa: E731
        fh.write("\t".join([str(mid), region.group(1) if region else "-", clean(data.get("masjidCity")),
                            clean(data.get("masjidAddress")), clean(data.get("masjidName"))]) + "\n")
        fh.flush()
        time.sleep(pause)
print("done; http errors", misses)
