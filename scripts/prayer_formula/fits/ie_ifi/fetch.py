#!/usr/bin/env python3
"""
Fetches the Islamic Foundation of Ireland's tables (plan 7.2, 7.5, Q23; milestone M3) into the git-ignored
test-data-local/official/ie/ through the collectors' Session (paced at 3 s, system proxy, TLS verified; A7, D13):

- ifi/settings.json and ifi/timetable.json: the timetable app's settings (with its `updated` stamp) and its perpetual
  Dublin table (month -> day -> six [hour, minute] pairs, Irish clock time), the two addresses islamicfoundation.ie's
  timetable app reads (https://islamicfoundation.ie/api/settings/ names the second);
- ifi/ramadan-2026.html and ifi/ramadan-2026/<town>.pdf: the Ramadan 2026/1447 page and the 33 town timetables it links.

Files already saved are kept (the Session keeps both copies of a page that changed).

    python -I scripts/prayer_formula/fits/ie_ifi/fetch.py
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "collectors"))
import common as collectors  # noqa: E402

SITE = "https://islamicfoundation.ie"
CC = "ie"
SETTINGS = SITE + "/api/settings/"
RAMADAN_PAGE = SITE + "/timetable/ramadan-2026/"


def main() -> int:
    session = collectors.Session()
    settings = session.fetch(SETTINGS)
    timetable_url = json.loads(settings)["url"]["timetable"]
    session.save(CC, "ifi/settings.json", settings, SETTINGS, f"timetable app settings, updated {json.loads(settings).get('updated')}")
    table = session.fetch(timetable_url)
    days = sum(len(v) for v in json.loads(table).values())
    session.save(CC, "ifi/timetable.json", table, timetable_url, f"perpetual Dublin table, {days} days")
    print(f"timetable: {days} days")
    page = session.fetch(RAMADAN_PAGE)
    session.save(CC, "ifi/ramadan-2026.html", page, RAMADAN_PAGE, "Ramadan 2026/1447 timetables page")
    links = sorted(set(re.findall(r"/site/assets/files/\d+/([a-z_]+_ramadan_timetable_2026\.pdf)", page.decode("utf-8", "replace"))))
    print(f"{len(links)} town tables")
    folder = collectors.OFFICIAL / CC / "ifi" / "ramadan-2026"
    for name in links:
        if (folder / name).exists():
            continue
        url = re.search(r"(/site/assets/files/\d+/" + re.escape(name) + ")", page.decode("utf-8", "replace")).group(1)
        body = session.fetch(SITE + url)
        session.save(CC, f"ifi/ramadan-2026/{name}", body, SITE + url, f"{name.split('_')[0]} Ramadan 2026 timetable ({len(body)} bytes)")
        print(name, len(body), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
