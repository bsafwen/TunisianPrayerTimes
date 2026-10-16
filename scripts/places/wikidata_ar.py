"""
Arabic labels from Wikidata (CC0) for the catalogue's places GeoNames names in no Arabic (plan 5.5: alternateNamesV2's
Arabic first, then Wikidata). Reads the built catalogue, asks Wikidata's public SPARQL service for the Arabic label of
each item whose GeoNames ID (P1566) is one of those places, in paced batches, and writes "geonameid<TAB>label" for
build_catalogue.py --wikidata-ar. Read-only, through the system proxy, TLS verified.

    python scripts/places/wikidata_ar.py --out <folder>/wikidata_ar.tsv [--catalogue android-app/app/src/main/assets/places]
"""

from __future__ import annotations

import argparse
import json
import re
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENDPOINT = "https://query.wikidata.org/sparql"
USER_AGENT = "TunisianPrayerTimes-catalogue/1 (one-off build of a bundled city list; Python urllib)"
BATCH = 250
PAUSE_SECONDS = 2.0
ARABIC = re.compile(r"[؀-ۿ]")
LATIN = re.compile(r"[A-Za-z]")


def unnamed_ids(catalogue: Path) -> list[int]:
    """The GeoNames ids of listed rows with no Arabic name (neighbour rows never show one)."""
    ids = []
    for name in ("europe-cities.tsv", "arab-cities.tsv"):
        lines = (catalogue / name).read_text(encoding="utf-8").splitlines()
        header = lines[0].split("\t")
        for line in lines[1:]:
            row = dict(zip(header, line.split("\t")))
            if not row.get("arabic") and "n" not in row.get("flags", ""):
                ids.append(int(row["id"]))
    return sorted(set(ids))


def query(ids: list[int]) -> str:
    values = " ".join(f'"{i}"' for i in ids)
    return ("SELECT ?gid ?label WHERE { VALUES ?gid { " + values + " } "
            "?item wdt:P1566 ?gid ; rdfs:label ?label . FILTER(LANG(?label) = \"ar\") }")


def labels(answer: bytes) -> dict[int, str]:
    """The first Arabic-script label per GeoNames id; a label with Latin letters is not a name to show."""
    out: dict[int, str] = {}
    for binding in json.loads(answer)["results"]["bindings"]:
        gid, label = int(binding["gid"]["value"]), " ".join(binding["label"]["value"].split())
        if gid not in out and ARABIC.search(label) and not LATIN.search(label):
            out[gid] = label
    return out


def fetch(sparql: str, context: ssl.SSLContext) -> bytes:
    body = urllib.parse.urlencode({"query": sparql, "format": "json"}).encode("utf-8")
    request = urllib.request.Request(ENDPOINT, data=body, headers={
        "User-Agent": USER_AGENT, "Accept": "application/sparql-results+json"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=120, context=context) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt == 2:
                raise
            time.sleep(float(error.headers.get("Retry-After") or 30))
    raise RuntimeError("unreachable")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--catalogue", type=Path, default=ROOT / "android-app" / "app" / "src" / "main" / "assets" / "places")
    args = parser.parse_args(argv)
    ids = unnamed_ids(args.catalogue)
    context = ssl.create_default_context()
    found: dict[int, str] = {}
    for start in range(0, len(ids), BATCH):
        found.update(labels(fetch(query(ids[start:start + BATCH]), context)))
        time.sleep(PAUSE_SECONDS)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="") as f:
        for gid in sorted(found):
            f.write(f"{gid}\t{found[gid]}\n")
    print(f"{len(found)} Arabic labels for {len(ids)} places without one -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
