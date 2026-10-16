#!/usr/bin/env python3
"""
Collects Diyanet's tables for the fits of `diyanet_tr` (Turkey, plan 6.5 `DISTRICT`) and of `diyanet_eu`'s second
Austrian tier (6.5, 7.5), one-off and by hand (not a scheduled collector; M3, D13), from namazvakitleri.diyanet.gov.tr
(diyanet_site.py describes the requests):

- official/tr/lists/country_2.json and official/tr/lists/state_<SehirID>.json: Turkey's 81 provinces and their entries;
- official/at/lists/country_35.json: Austria's entries;
- official/<cc>/pages/<IlceID>.html.gz: each entry's page (31 days from the day of the request and the whole next year),
  gzip-compressed as served, with the page's own size and SHA-256 in the manifest note.

Through scripts/collectors/common.py: one request every 3 s, the system proxy, TLS verified, a SHA-256 manifest per
folder (test-data-local is never committed, A7). A run stops after --budget seconds and the next run goes on where it
stopped: lists already saved are read back, pages already saved are skipped. It stops at once on the site's block page.

Usage: python scripts/prayer_formula/fits/diyanet_tr/collect.py [--only TR|AT] [--budget 560] [--refresh-lists]
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import sys
import time
import urllib.error
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[2] / "collectors"))
import common as collectors  # noqa: E402  (scripts/collectors/common.py)
import diyanet_site as site  # noqa: E402


def log(text: str) -> None:
    print(text, flush=True)


class Blocked(RuntimeError):
    pass


def fetch(session, url: str, headers: dict) -> bytes:
    """Session.fetch with two retries, 10 and 30 s later, on a dropped connection or a timeout (not on an HTTP error)."""
    for wait in (10, 30, None):
        try:
            return session.fetch(url, headers=headers)
        except urllib.error.HTTPError:
            raise
        except (urllib.error.URLError, OSError) as ex:
            if wait is None:
                raise
            log(f"  {ex!r}"[:160] + f"; again in {wait} s")
            time.sleep(wait)
    raise AssertionError("unreachable")


def get_json(session, url: str) -> tuple[bytes, dict]:
    body = fetch(session, url, {"Accept": "application/json", "X-Requested-With": "XMLHttpRequest"})
    text = body.decode("utf-8", errors="replace")
    if site.is_block_page(text):
        raise Blocked(url)
    return body, json.loads(text)


def lists(session, cc: str, refresh: bool) -> list[dict]:
    """The country's list files, fetched where missing (or all with refresh); returns the entries of the lists saved
    (a province whose list failed is fetched by the next run)."""
    folder = collectors.OFFICIAL / cc.lower() / "lists"
    name = f"country_{site.COUNTRY_IDS[cc]}.json"
    if refresh or not (folder / name).exists():
        url = site.country_list_url(cc)
        body, top = get_json(session, url)
        session.save(cc, f"lists/{name}", body, url, f"Diyanet's {cc} province list (GetRegList, ChangeType=country)")
    else:
        top = json.loads((folder / name).read_text(encoding="utf-8"))
    states = top.get("StateList") or []
    if not (top.get("StateRegionList") and len(states) == 1):
        for s in states:
            sname = f"state_{s['SehirID']}.json"
            if refresh or not (folder / sname).exists():
                url = site.state_list_url(cc, s["SehirID"])
                try:
                    body, _ = get_json(session, url)
                except Blocked:
                    raise
                except Exception as ex:  # noqa: BLE001 - the next run asks again
                    log(f"{cc} list {s['SehirID']} {s['SehirAdi']}: {ex!r}"[:200])
                    continue
                session.save(cc, f"lists/{sname}", body, url, f"{s['SehirAdi']} ({s['SehirID']}): Diyanet's entries (GetRegList, ChangeType=state)")
    return site.load_entries(collectors.LOCAL_DATA, cc)


def page(session, cc: str, entry: dict) -> str:
    url = site.BASE + entry["url"]
    body = fetch(session, url, {"Accept-Language": "tr-TR,tr;q=0.9"})
    text = body.decode("utf-8", errors="replace")
    if site.is_block_page(text):
        raise Blocked(url)
    parsed = site.parse_page(text)
    sizes = [len(t) for _, t in parsed["tables"]]
    if parsed["id"] != entry["id"] or len(sizes) < 3 or max(sizes) < 365:
        return f"unexpected page (id {parsed['id']}, tables {sizes}): not saved"
    days = sorted(parsed["rows"])
    year = [d for d in days if d.year == days[-1].year]
    note = (f"{entry['name']} ({entry['id']}), {entry['stateName']} ({entry['stateId']}); gzip -9, mtime 0, of the page as "
            f"served: {len(body)} bytes, sha256 {hashlib.sha256(body).hexdigest()}; rows {days[0]}..{days[-1]} "
            f"({len(days)} days, the year {year[0].year} complete: {len(year)})")
    session.save(cc, f"pages/{entry['id']}.html.gz", gzip.compress(body, 9, mtime=0), url, note)
    return f"{len(days)} days"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--only", choices=("TR", "AT"))
    parser.add_argument("--budget", type=float, default=560.0, help="seconds before the run stops (default 560)")
    parser.add_argument("--refresh-lists", action="store_true")
    args = parser.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    started = time.monotonic()
    session = collectors.Session()
    countries = [args.only] if args.only else ["AT", "TR"]
    try:
        for cc in countries:
            entries = lists(session, cc, args.refresh_lists)
            todo = [e for e in entries if not site.page_files(collectors.LOCAL_DATA, cc, e["id"])]
            log(f"{cc}: {len(entries)} entries, {len(entries) - len(todo)} pages saved, {len(todo)} to fetch")
            errors = 0
            for i, e in enumerate(todo):
                if time.monotonic() - started > args.budget:
                    log(f"budget reached after {time.monotonic() - started:.0f} s: run again to go on")
                    return 0
                try:
                    result = page(session, cc, e)
                    errors = 0
                except Blocked:
                    raise
                except Exception as ex:  # noqa: BLE001 - a failed page is fetched by the next run
                    errors += 1
                    result = f"error {ex!r}"[:200]
                    if errors >= 5:
                        log(f"{cc} {e['id']} {e['name']}: {result}; five failures in a row, stopping")
                        return 1
                log(f"{cc} {i + 1}/{len(todo)} {e['id']} {e['stateName']} / {e['name']}: {result}")
    except Blocked as b:
        log(f"the site answered with its block page ({b}); stopped")
        return 1
    log(f"done in {time.monotonic() - started:.0f} s; saved {len(session.saved)} files")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
