#!/usr/bin/env python3
"""
Fetches Islamic Council Norway's 2026 tables (plan 7.4, 6.5 ADMIN_AREA; milestone M2) into the git-ignored
test-data-local/official/no/ through the collectors' Session (paced at 3 s, system proxy, TLS verified; A7, D13).

bonnetid.info, IRN's national prayer-time project, is a single-page app that reads its tables from its own Supabase
REST endpoint with the public key its script carries. This script makes the same requests the page makes:
- the page and its script, from which it reads the endpoint and the public key (each run, so it follows a new build);
- `location_t`, the entries (municipality number, name, published point, county), saved as irn/location_t.json;
- `prayertime` for each entry, 1 January to 31 December 2026 ordered by date, as the page's period table asks it, with
  the columns the fit reads, saved as irn/2026/<location_iso>.json. The entry's own method row is the only one served
  (method 3 south of IRN's limit, 2 from it), so no method filter is needed; the fit checks one row per date.

Entries already saved are not fetched again, so the run can be split:

    python -I scripts/prayer_formula/fits/irn/fetch.py --locations
    python -I scripts/prayer_formula/fits/irn/fetch.py --tables 0 45      (entries 0-44 of the sorted list)
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "collectors"))
import common as collectors  # noqa: E402

SITE = "https://www.bonnetid.info"
CC = "no"
FOLDER = "irn"
YEAR = 2026
COLUMNS = ("date,location_iso,prayer_method,fajr,fajr_sadiq,fajr_endtime,shuruq_sunrise,istiwa_noon,duhr,asr,shadow_1x,"
           "shadow_2x,maghrib,ghrub_sunset,isha,shafaqal_ahmar_end_redlight,shafaqal_abyadh_end_whitelight,"
           "muntasafallayl_midnight")
COUNTRY = "NO"                   # IRN's one entry in Sweden (SE1486, Strömstad) is not used (plan 6.5)


def site_api(session) -> tuple[str, str]:
    """The REST endpoint and public key the page's own script uses."""
    page = session.fetch(SITE + "/").decode("utf-8", "replace")
    bundle = re.search(r'src="(/assets/index-[A-Za-z0-9_-]+\.js)"', page)
    if not bundle:
        raise SystemExit("irn: the page names no script bundle")
    js = session.fetch(SITE + bundle.group(1)).decode("utf-8", "replace")
    found = re.search(r'="(https://[a-z0-9.-]+)",[A-Za-z0-9_$]+="(eyJ[A-Za-z0-9_.-]+)"', js)
    if not found:
        raise SystemExit("irn: no endpoint and key in the page's script")
    return found.group(1), found.group(2)


def rest(session, base: str, key: str, query: str) -> tuple[bytes, str]:
    url = f"{base}/rest/v1/{query}"
    body = session.fetch(url, headers={"apikey": key, "Authorization": f"Bearer {key}", "Accept": "application/json"})
    return body, url


def locations_path() -> Path:
    return collectors.OFFICIAL / CC / FOLDER / "location_t.json"


def fetch_locations(session, base, key) -> int:
    body, url = rest(session, base, key, "location_t?select=*&order=location_iso")
    rows = json.loads(body)
    if not isinstance(rows, list) or len(rows) < 300:
        raise SystemExit(f"irn: location_t has {len(rows) if isinstance(rows, list) else 'no'} rows")
    session.save(CC, f"{FOLDER}/location_t.json", body, url, f"IRN's entries: {len(rows)} rows (bonnetid.info's own request)")
    print(f"location_t: {len(rows)} rows")
    return len(rows)


def entries() -> list[dict]:
    rows = json.loads(locations_path().read_text(encoding="utf-8"))
    return sorted((r for r in rows if r["location_iso"].startswith(COUNTRY)), key=lambda r: r["location_iso"])


def fetch_tables(session, base, key, start: int, count: int) -> int:
    todo = entries()[start:start + count]
    saved = 0
    for r in todo:
        iso = r["location_iso"]
        path = collectors.OFFICIAL / CC / FOLDER / str(YEAR) / f"{iso}.json"
        if path.exists():
            continue
        query = (f"prayertime?select={COLUMNS}&location_iso=eq.{iso}&date=gte.{YEAR}-01-01&date=lte.{YEAR}-12-31"
                 f"&order=date")
        body, url = rest(session, base, key, query)
        rows = json.loads(body)
        dates = [x["date"] for x in rows]
        note = f"{r['location_name']} ({iso}): {len(rows)} rows, {dates[0] if dates else '-'} to {dates[-1] if dates else '-'}"
        session.save(CC, f"{FOLDER}/{YEAR}/{iso}.json", body, url, note)
        saved += 1
        print(note, flush=True)
    return saved


def main(argv: list[str]) -> int:
    session = collectors.Session()
    base, key = site_api(session)
    if argv and argv[0] == "--locations":
        fetch_locations(session, base, key)
    elif argv and argv[0] == "--tables":
        n = fetch_tables(session, base, key, int(argv[1]), int(argv[2]))
        print(f"saved {n} tables")
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
