#!/usr/bin/env python3
"""
Collects the Diyanet tables the takdir fit reads (fits/diyanet_takdir/fit.py, README H6), one-off and by hand, from
namazvakitleri.diyanet.gov.tr, with fits/diyanet_tr/diyanet_site.py's requests and page reader and the collectors'
Session (scripts/collectors/common.py: one request every 3 s, the system proxy, TLS verified, a SHA-256 manifest per
folder; test-data-local is never committed, A7):

- official/<cc>/diyanet/lists/country_<CountryId>.json and state_<SehirID>.json: a country's states and entries
  (GetRegList, as diyanet_site.py describes);
- official/<cc>/diyanet/pages/<IlceID>.html.gz: an entry's page (31 days from the day of the request and the whole
  next year), gzip-compressed as served, with the page's own size and SHA-256 in the manifest note;
- research-2026-10-16/diyanet_takdir/selection.tsv: the sample (step `select`): every listed entry matched by name to
  GeoNames' cities500 in its country, then per band of latitude one entry west of 8 E and one east of it where both
  exist (a name GeoNames gives once, the countries asked for first, the most populous town): two per quarter degree
  from 44 to 56 N (one from 43 to 44), two per half degree to 60 N, one per degree beyond (two from 64 to 70 N).

Steps (a run goes on where the last one stopped and stops at once on the site's block page):
  lists [--countries DE,NL,...]   the countries' lists (one request per country, one per state)
  select                          the sample, from the saved lists (no request)
  extra --lo 44.2 --hi 44.6       more entries in a latitude range appended to the sample (no request)
  pages [--budget 560]            the sample's pages not yet saved

Usage: python scripts/prayer_formula/fits/diyanet_takdir/collect.py lists|select|pages [options]
"""

from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import importlib.util
import json
import sys
import time
import urllib.error
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
FITS = HERE.parent
sys.path.insert(0, str(FITS / "diyanet_tr"))
import diyanet_site as site  # noqa: E402  (fits/diyanet_tr: the site's requests and its page reader)
import points  # noqa: E402  (fits/diyanet_tr: fold() and the GeoNames reader; imports fits/common.py as `common`)

# scripts/collectors/common.py under its own name (fits/common.py is `common` here).
_spec = importlib.util.spec_from_file_location("collectors_common", FITS.parents[1] / "collectors" / "common.py")
collectors = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(collectors)

# Diyanet's CountryId (the site's country menu, read from a saved page on 2026-10-16) and the country's clock.
COUNTRIES = {
    "DE": (13, "Europe/Berlin"), "NL": (4, "Europe/Amsterdam"), "BE": (11, "Europe/Brussels"),
    "FR": (21, "Europe/Paris"), "CH": (49, "Europe/Zurich"), "DK": (26, "Europe/Copenhagen"),
    "SE": (12, "Europe/Stockholm"), "NO": (36, "Europe/Oslo"), "GB": (15, "Europe/London"),
    "PL": (39, "Europe/Warsaw"), "FI": (41, "Europe/Helsinki"), "IT": (8, "Europe/Rome"),
    "RO": (37, "Europe/Bucharest"), "IS": (122, "Atlantic/Reykjavik"), "LU": (31, "Europe/Luxembourg"),
    "CZ": (16, "Europe/Prague"), "HU": (7, "Europe/Budapest"), "HR": (30, "Europe/Zagreb"),
    "RS": (27, "Europe/Belgrade"), "LT": (47, "Europe/Vilnius"), "LV": (20, "Europe/Riga"),
    "EE": (6, "Europe/Tallinn"), "IE": (32, "Europe/Dublin"), "SJ": (163, "Arctic/Longyearbyen"),
    # The southern hemisphere's takdir (README H6's mirror), checked on the entries south of 44.375 S.
    "NZ": (193, "Pacific/Auckland"), "CL": (200, "America/Santiago"), "AR": (199, "America/Argentina/Buenos_Aires"),
}
LOCAL = collectors.LOCAL_DATA
SELECTION = LOCAL / "research-2026-10-16" / "diyanet_takdir" / "selection.tsv"
SELECTION_COLUMNS = ("cc", "id", "name", "state", "url", "town", "lat", "lng", "population", "candidates", "band")


class Blocked(RuntimeError):
    pass


def log(text: str) -> None:
    print(text, flush=True)


def folder(cc: str) -> Path:
    return collectors.OFFICIAL / cc.lower() / "diyanet"


def country_url(cc: str) -> str:
    return f"{site.BASE}/tr-TR/home/GetRegList?ChangeType=country&CountryId={COUNTRIES[cc][0]}&Culture=tr-TR"


def state_url(cc: str, state_id: str) -> str:
    return (f"{site.BASE}/tr-TR/home/GetRegList?ChangeType=state&CountryId={COUNTRIES[cc][0]}&StateId={state_id}"
            "&Culture=tr-TR")


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


def save(session, cc: str, name: str, body: bytes, url: str, note: str) -> None:
    session.save(f"{cc.lower()}/diyanet", name, body, url, note)


def fetch_lists(session, cc: str, with_states: bool = True) -> int:
    """The country's list and each state's list, those not saved yet; returns the requests made."""
    lists = folder(cc) / "lists"
    name = f"country_{COUNTRIES[cc][0]}.json"
    made = 0
    if (lists / name).exists():
        top = json.loads((lists / name).read_text(encoding="utf-8"))
    else:
        url = country_url(cc)
        body, top = get_json(session, url)
        made += 1
        save(session, cc, f"lists/{name}", body, url, f"Diyanet's {cc} state list (GetRegList, ChangeType=country)")
    states = top.get("StateList") or []
    if (top.get("StateRegionList") and len(states) == 1) or not with_states:
        return made
    for s in states:
        sname = f"state_{s['SehirID']}.json"
        if (lists / sname).exists():
            continue
        url = state_url(cc, s["SehirID"])
        body, _ = get_json(session, url)
        made += 1
        save(session, cc, f"lists/{sname}", body, url,
             f"{s['SehirAdi']} ({s['SehirID']}): Diyanet's entries (GetRegList, ChangeType=state)")
    return made


def load_entries(cc: str) -> list[dict]:
    """The country's entries from its saved lists: {"cc", "id", "name", "url", "stateId", "stateName"}."""
    lists = folder(cc) / "lists"
    path = lists / f"country_{COUNTRIES[cc][0]}.json"
    if not path.exists():
        return []
    top = json.loads(path.read_text(encoding="utf-8"))
    states = {s["SehirID"]: s["SehirAdi"] for s in top.get("StateList") or []}
    out, seen = [], set()

    def add(region_list, state_id):
        for e in region_list or []:
            if e["IlceID"] in seen:
                continue
            seen.add(e["IlceID"])
            out.append({"cc": cc, "id": e["IlceID"], "name": e["IlceAdi"], "url": e["IlceUrl"], "stateId": state_id,
                        "stateName": states.get(state_id, "")})

    if top.get("StateRegionList") and len(states) == 1:
        add(top["StateRegionList"], next(iter(states)))
    for state_id in states:
        p = lists / f"state_{state_id}.json"
        if p.exists():
            add(json.loads(p.read_text(encoding="utf-8")).get("StateRegionList"), state_id)
    return out


def page_file(cc: str, entry_id: str) -> Path:
    return folder(cc) / "pages" / f"{entry_id}.html.gz"


def fetch_page(session, entry: dict) -> str:
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
    note = (f"{entry['name']} ({entry['id']}), {entry['state']}; gzip -9, mtime 0, of the page as served: {len(body)} "
            f"bytes, sha256 {hashlib.sha256(body).hexdigest()}; rows {days[0]}..{days[-1]} ({len(days)} days, the year "
            f"{year[0].year} complete: {len(year)})")
    save(session, entry["cc"], f"pages/{entry['id']}.html.gz", gzip.compress(body, 9, mtime=0), url, note)
    return f"{len(days)} days"


# ---- the sample ---------------------------------------------------------------------------------------------------

def town_of(entry: dict, rows: list[dict]) -> tuple[dict | None, int]:
    """The GeoNames row of the entry's name in its country (its own name before an alternate one, a seat before a
    village, then the most populous) and how many rows carry that name."""
    key = points.fold(entry["name"].split("(")[0])
    named = [r for r in rows if key in r["keys"]]
    if not named:
        return None, 0
    best = min(named, key=lambda r: (key not in r["primary"], points.fcode_rank(r["fcode"]) > 1, -r["population"]))
    return best, len(named)


def bands() -> list[tuple[float, float, int]]:
    out = [(43.0 + 0.25 * i, 43.25 + 0.25 * i, 1) for i in range(4)]            # 43-44 N: where the takdir starts
    out += [(44.0 + 0.25 * i, 44.25 + 0.25 * i, 2) for i in range(48)]          # 44-56 N, quarter degrees
    out += [(56.0 + 0.5 * i, 56.5 + 0.5 * i, 2) for i in range(8)]              # 56-60 N, half degrees
    out += [(60.0 + i, 61.0 + i, 2 if 64 <= 60 + i < 70 else 1) for i in range(20)]   # 60-80 N, degrees
    return out


# The countries the fit asks for first, in this order, then the others: west of 8 E and east of it.
PREFERRED_WEST = ("NL", "BE", "FR", "CH", "GB", "NO", "DK", "LU", "IE", "DE")
PREFERRED_EAST = ("DE", "CH", "DK", "SE", "PL", "NO", "IT", "FI", "CZ")
WEST_EAST = 8.0                         # degrees east: one entry west of it and one east of it per band where possible


def select(_args) -> int:
    ccs = [cc for cc in COUNTRIES if (folder(cc) / "lists").exists()]
    geo = points.read_geonames(set(ccs))
    by_cc = defaultdict(list)
    for r in geo:
        by_cc[r["cc"]].append(r)
    found = []
    for cc in ccs:
        entries = load_entries(cc)
        matched = 0
        for e in entries:
            town, n = town_of(e, by_cc[cc])
            if town is None:
                continue
            matched += 1
            found.append((e, town, n))
        log(f"{cc}: {len(entries)} entries, {matched} matched to GeoNames")

    def rank(x):
        e, t, n = x
        order = PREFERRED_WEST if t["lng"] < WEST_EAST else PREFERRED_EAST
        pref = order.index(e["cc"]) if e["cc"] in order else len(order)
        return (n != 1, pref, -t["population"])

    chosen = []
    for lo, hi, k in bands():
        inside = [x for x in found if lo <= x[1]["lat"] < hi]
        if not inside:
            continue
        # Per side of 8 E, an unambiguous name (one GeoNames row) first, then the preferred countries, then the most
        # populous town; a band with one side only takes that side's two most distant longitudes.
        west = sorted((x for x in inside if x[1]["lng"] < WEST_EAST), key=rank)
        east = sorted((x for x in inside if x[1]["lng"] >= WEST_EAST), key=rank)
        if k == 1:
            pick = [min(inside, key=rank)]
        elif west and east:
            pick = [west[0], east[0]]
        else:
            side = west or east
            first = side[0]
            far = max(side[1:], key=lambda x: (abs(x[1]["lng"] - first[1]["lng"]) > 1.0, -rank(x)[0], -rank(x)[1]),
                      default=None)
            pick = [first] + ([far] if far is not None else [])
        for e, t, n in pick:
            chosen.append({"cc": e["cc"], "id": e["id"], "name": e["name"], "state": e["stateName"], "url": e["url"],
                           "town": t["name"], "lat": f"{t['lat']:.4f}", "lng": f"{t['lng']:.4f}",
                           "population": t["population"], "candidates": n, "band": f"{lo:.2f}-{hi:.2f}"})
    SELECTION.parent.mkdir(parents=True, exist_ok=True)
    with open(SELECTION, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=SELECTION_COLUMNS, delimiter="\t", lineterminator="\n")
        w.writeheader()
        w.writerows(chosen)
    empty = [f"{lo:.2f}" for lo, hi, _ in bands() if not any(lo <= float(c["lat"]) < hi for c in chosen) and hi <= 72]
    log(f"selected {len(chosen)} entries into {SELECTION}; empty bands: {' '.join(empty)}")
    return 0


def extra(args) -> int:
    """Appends to the selection up to --count entries of the saved lists (not selected yet) whose GeoNames town lies
    from --lo to --hi degrees north, spread evenly over the range, one name GeoNames gives once, most populous first."""
    ccs = [cc for cc in COUNTRIES if (folder(cc) / "lists").exists()]
    geo = points.read_geonames(set(ccs))
    by_cc = defaultdict(list)
    for r in geo:
        by_cc[r["cc"]].append(r)
    have = {(r["cc"], r["id"]) for r in read_selection()}
    found = []
    for cc in ccs:
        for e in load_entries(cc):
            town, n = town_of(e, by_cc[cc])
            if town and n == 1 and (cc, e["id"]) not in have and args.lo <= town["lat"] < args.hi:
                found.append((e, town))
    width = (args.hi - args.lo) / args.count
    chosen = []
    for i in range(args.count):
        lo = args.lo + i * width
        inside = [x for x in found if lo <= x[1]["lat"] < lo + width and x not in chosen]
        if inside:
            chosen.append(max(inside, key=lambda x: x[1]["population"]))
    with open(SELECTION, "a", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=SELECTION_COLUMNS, delimiter="\t", lineterminator="\n")
        for e, t in chosen:
            w.writerow({"cc": e["cc"], "id": e["id"], "name": e["name"], "state": e["stateName"], "url": e["url"],
                        "town": t["name"], "lat": f"{t['lat']:.4f}", "lng": f"{t['lng']:.4f}",
                        "population": t["population"], "candidates": 1, "band": f"extra {args.lo:.2f}-{args.hi:.2f}"})
    log(f"appended {len(chosen)} entries: " + ", ".join(f"{e['cc']} {e['name']} {t['lat']:.2f}" for e, t in chosen))
    return 0


def read_selection() -> list[dict]:
    with open(SELECTION, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def lists(args) -> int:
    session = collectors.Session()
    try:
        for cc in args.countries.split(","):
            made = fetch_lists(session, cc, not args.no_states)
            top = json.loads((folder(cc) / "lists" / f"country_{COUNTRIES[cc][0]}.json").read_text(encoding="utf-8"))
            log(f"{cc}: {made} requests, {len(top.get('StateList') or [])} states, {len(load_entries(cc))} entries listed")
    except Blocked as b:
        log(f"the site answered with its block page ({b}); stopped")
        return 1
    return 0


def pages(args) -> int:
    started = time.monotonic()
    session = collectors.Session()
    todo = [e for e in read_selection() if not page_file(e["cc"], e["id"]).exists()]
    log(f"{len(todo)} pages to fetch")
    errors = 0
    try:
        for i, e in enumerate(todo):
            if time.monotonic() - started > args.budget:
                log(f"budget reached after {time.monotonic() - started:.0f} s: run again to go on")
                return 0
            try:
                result = fetch_page(session, e)
                errors = 0
            except Blocked:
                raise
            except Exception as ex:  # noqa: BLE001 - a failed page is fetched by the next run
                errors += 1
                result = f"error {ex!r}"[:200]
                if errors >= 5:
                    log(f"{e['cc']} {e['id']} {e['name']}: {result}; five failures in a row, stopping")
                    return 1
            log(f"{i + 1}/{len(todo)} {e['cc']} {e['id']} {e['name']} ({e['lat']} N): {result}")
    except Blocked as b:
        log(f"the site answered with its block page ({b}); stopped")
        return 1
    log(f"done in {time.monotonic() - started:.0f} s; saved {len(session.saved)} files")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="step", required=True)
    p = sub.add_parser("lists")
    p.add_argument("--countries", default=",".join(COUNTRIES))
    p.add_argument("--no-states", action="store_true", help="the country lists only")
    sub.add_parser("select")
    p = sub.add_parser("extra")
    p.add_argument("--lo", type=float, required=True)
    p.add_argument("--hi", type=float, required=True)
    p.add_argument("--count", type=int, default=10)
    p = sub.add_parser("pages")
    p.add_argument("--budget", type=float, default=560.0, help="seconds before the run stops (default 560)")
    args = parser.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    return {"lists": lists, "select": select, "extra": extra, "pages": pages}[args.step](args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
