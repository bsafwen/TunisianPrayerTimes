"""
Builds the bundled place catalogue of plan 6.8 (milestone M5) from GeoNames dumps the owner downloads:

    python scripts/places/build_catalogue.py --geonames <folder> [--wikidata-ar <labels.tsv>]

<folder> holds files from https://download.geonames.org/export/dump/ (CC BY 4.0), each as <name>.txt or as the
<name>.zip GeoNames serves:
    cities1000 and alternateNamesV2 (required);
    cities5000 (optional: without it, cities1000's places of more than 5,000 people and its first-order seats,
        which is how GeoNames cuts cities5000);
    allCountries or cities500 (optional, for the administrative seats: allCountries has every one, cities500 those
        down to PPLA4, and without either cities1000 gives those down to PPLA3);
    BH (optional, Bahrain's dump, for the Hawar Islands row when allCountries is not there);
    admin1CodesASCII (optional, for admin1-names.tsv).
--wikidata-ar is an optional TSV "geonameid<TAB>Arabic label" from Wikidata (CC0), used where GeoNames has no
Arabic name.

Writes, with LF line endings:
    android-app/app/src/main/assets/places/europe-cities.tsv   Europe (D2), Turkey, Cyprus and overseas France
    android-app/app/src/main/assets/places/arab-cities.tsv     the Arab League, and the land neighbours' rows
                                                               within 60 km of an Arab border (country and zone only)
    android-app/app/src/main/assets/places/PLACES-LICENSE.txt

Both files share one format (one parser on the phone), a header line then one row per place, sorted by country
then id, with zone left empty where it repeats the row above's (the phone carries it down):
    id country admin1 admin2 dem name ascii arabic aliases latE5 lngE5 zone population flags
admin1 and admin2 are GeoNames codes (FR's admin2 is the département, "69"); admin2 is kept for FR, TR, NO and
PS rows, admin1 for RU, DZ, MR, AE, JO and IQ rows (the ones the zone mapping reads, 6.5); dem is kept for
Arab and Réunion rows (E16, E10); ascii is left empty where it equals name (the phone reads the name then; it
keeps the two files under the budget); aliases are "|"-separated exonyms; flags holds "n" for a neighbour-only row.
Western Sahara rows take MA (A1) and Svalbard rows NO, Longyearbyen kept (6.8). Arab rows within 2 km of a
larger one are dropped (GeoNames' imported duplicates). The build reports Arabic coverage by population,
checks every zone with Python's zoneinfo, and fails above the 2 MB budget.
"""

from __future__ import annotations

import argparse
import io
import math
import sys
import zipfile
from collections import defaultdict
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "android-app" / "app" / "src" / "main" / "assets" / "places"
BUDGET_BYTES = 2 * 1024 * 1024

EUROPE = {
    "AD", "AL", "AT", "AX", "BA", "BE", "BG", "BY", "CH", "CZ", "DE", "DK", "EE", "ES", "FI", "FO", "FR", "GB", "GG",
    "GI", "GR", "HR", "HU", "IE", "IM", "IS", "IT", "JE", "LI", "LT", "LU", "LV", "MC", "MD", "ME", "MK", "MT", "NL",
    "NO", "PL", "PT", "RO", "RS", "RU", "SE", "SI", "SJ", "SK", "SM", "UA", "VA", "XK", "TR", "CY",
}
OVERSEAS_FRANCE = {"RE", "YT", "GP", "MQ", "GF", "NC", "PF", "PM", "BL", "MF", "WF"}
ARAB_LEAGUE = {
    "DZ", "BH", "KM", "DJ", "EG", "IQ", "JO", "KW", "LB", "LY", "MR", "MA", "OM", "PS", "QA", "SA", "SO", "SD", "SY",
    "AE", "YE",
}
NEIGHBOURS = {"IR", "TD", "NE", "ML", "SN", "CF", "SS", "ET", "ER", "KE"}
ADMIN1_COUNTRIES = {"RU", "DZ", "MR", "AE", "JO", "IQ"}
ADMIN2_COUNTRIES = {"FR", "TR", "NO", "PS"}
SEAT_CODES = {"PPLA", "PPLA2", "PPLA3", "PPLA4", "PPLC"}
EXONYM_LANGUAGES = {"en", "fr", "de", "it", "es"}
LONGYEARBYEN = 2729907
HAWAR = 290243
NEIGHBOUR_KM = 60.0
DUPLICATE_KM = 2.0


def km(a: tuple[float, float], b: tuple[float, float]) -> float:
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371.0088 * math.asin(min(1.0, math.sqrt(h)))


def has(folder: Path, stem: str) -> bool:
    return (folder / f"{stem}.txt").exists() or (folder / f"{stem}.zip").exists()


def dump_lines(folder: Path, stem: str):
    """The lines of GeoNames' <stem>.txt, read as it is or from inside <stem>.zip."""
    txt = folder / f"{stem}.txt"
    if txt.exists():
        with txt.open(encoding="utf-8") as f:
            yield from f
        return
    with zipfile.ZipFile(folder / f"{stem}.zip") as z, z.open(f"{stem}.txt") as raw:
        yield from io.TextIOWrapper(raw, encoding="utf-8")


def read_places(lines, keep) -> dict[int, dict]:
    """GeoNames' main format: the rows [keep] accepts, by id."""
    out = {}
    for line in lines:
        c = line.rstrip("\n").split("\t")
        if len(c) < 19:
            continue
        row = {
            "id": int(c[0]), "name": c[1], "ascii": c[2], "lat": float(c[4]), "lng": float(c[5]),
            "fclass": c[6], "fcode": c[7], "cc": c[8], "admin1": c[10], "admin2": c[11],
            "population": int(c[14] or 0), "dem": c[16], "zone": c[17],
        }
        if keep(row):
            out[row["id"]] = row
    return out


def read_alternate_names(lines, ids: set[int]) -> tuple[dict[int, str], dict[int, set[str]]]:
    """Arabic names (the preferred one first) and the exonyms of [ids]."""
    arabic: dict[int, tuple[int, str]] = {}
    aliases: dict[int, set[str]] = defaultdict(set)
    for line in lines:
        c = line.rstrip("\n").split("\t")
        if len(c) < 4:
            continue
        gid = int(c[1])
        if gid not in ids:
            continue
        lang, name = c[2], c[3]
        historic = len(c) > 7 and c[7] == "1"
        if historic or not name:
            continue
        if lang == "ar":
            rank = 0 if len(c) > 4 and c[4] == "1" else 1
            if gid not in arabic or rank < arabic[gid][0]:
                arabic[gid] = (rank, name)
            aliases[gid].add(name)
        elif lang in EXONYM_LANGUAGES:
            aliases[gid].add(name)
    return {k: v[1] for k, v in arabic.items()}, aliases


def read_wikidata(path: Path | None) -> dict[int, str]:
    if path is None:
        return {}
    out = {}
    with path.open(encoding="utf-8") as f:
        for line in f:
            c = line.rstrip("\n").split("\t")
            if len(c) >= 2 and c[0].isdigit() and c[1]:
                out[int(c[0])] = c[1]
    return out


def country_of(row: dict) -> str:
    return {"EH": "MA", "SJ": "NO"}.get(row["cc"], row["cc"])


def clean(text: str) -> str:
    return text.replace("\t", " ").replace("\n", " ").replace("|", "/").strip()


def line_of(row: dict, arabic: str, aliases: set[str], neighbour: bool) -> str:
    cc = country_of(row)
    admin1 = row["admin1"] if cc in ADMIN1_COUNTRIES and not neighbour else ""
    admin2 = row["admin2"] if cc in ADMIN2_COUNTRIES and not neighbour else ""
    dem = row["dem"] if (cc in ARAB_LEAGUE or cc == "RE") and not neighbour else ""
    names = sorted(a for a in aliases if a not in (row["name"], row["ascii"], arabic))
    if row["id"] == HAWAR:
        arabic = "جزر حوار"
    cols = [
        str(row["id"]), cc, admin1, admin2, dem, clean(row["name"]), "" if row["ascii"] == row["name"] else clean(row["ascii"]),
        "" if neighbour else clean(arabic), "" if neighbour else "|".join(clean(a) for a in names[:12]),
        str(round(row["lat"] * 1e5)), str(round(row["lng"] * 1e5)), row["zone"], str(row["population"]),
        "n" if neighbour else "",
    ]
    return "\t".join(cols)


HEADER = "id\tcountry\tadmin1\tadmin2\tdem\tname\tascii\tarabic\taliases\tlatE5\tlngE5\tzone\tpopulation\tflags"
ZONE_COLUMN = HEADER.split("\t").index("zone")


def compress_zones(lines: list[str]) -> list[str]:
    """Leaves a row's zone empty where it repeats the row above's (rows sorted by country): the phone carries it down."""
    out, previous = [lines[0]], None
    for line in lines[1:]:
        cols = line.split("\t")
        zone = cols[ZONE_COLUMN]
        if zone == previous:
            cols[ZONE_COLUMN] = ""
        previous = zone
        out.append("\t".join(cols))
    return out


def dedupe(rows: list[dict]) -> list[dict]:
    """Drops a row within 2 km of a larger kept row of the same country (a 1° grid keeps it fast)."""
    kept: list[dict] = []
    grid: dict[tuple[int, int], list[dict]] = defaultdict(list)
    for row in sorted(rows, key=lambda r: (-r["population"], r["id"])):
        cell = (math.floor(row["lat"]), math.floor(row["lng"]))
        near = [k for dx in (-1, 0, 1) for dy in (-1, 0, 1) for k in grid[(cell[0] + dx, cell[1] + dy)]]
        if row["id"] != HAWAR and any(k["cc"] == row["cc"] and km((k["lat"], k["lng"]), (row["lat"], row["lng"])) < DUPLICATE_KM for k in near):
            continue
        kept.append(row)
        grid[cell].append(row)
    return kept


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--geonames", type=Path, required=True)
    parser.add_argument("--wikidata-ar", type=Path)
    parser.add_argument("--out", type=Path, default=ASSETS)
    args = parser.parse_args(argv)
    g = args.geonames

    missing = [stem for stem in ("cities1000", "alternateNamesV2") if not has(g, stem)]
    if missing:
        print(f"missing in {g}: " + ", ".join(f"{stem}.txt or .zip" for stem in missing), file=sys.stderr)
        return 1
    cities1000 = read_places(dump_lines(g, "cities1000"), lambda r: r["cc"] in ARAB_LEAGUE | {"EH"} | OVERSEAS_FRANCE | NEIGHBOURS | EUROPE)
    if has(g, "cities5000"):
        europe = read_places(dump_lines(g, "cities5000"), lambda r: r["cc"] in EUROPE or r["id"] == LONGYEARBYEN)
    else:
        # GeoNames cuts cities5000 from the same rows: more than 5,000 people, or a first-order seat.
        europe = {i: r for i, r in cities1000.items() if r["cc"] in EUROPE and (r["population"] > 5000 or r["fcode"] in ("PPLA", "PPLC") or i == LONGYEARBYEN)}

    def is_seat(r: dict) -> bool:
        return (
            r["fclass"] == "P" and r["fcode"] in SEAT_CODES and (r["cc"] in ARAB_LEAGUE | {"EH"} or (r["cc"] == "TR" and r["fcode"] == "PPLA2"))
        ) or r["id"] in (LONGYEARBYEN, HAWAR)

    seat_stem = next((stem for stem in ("allCountries", "cities500") if has(g, stem)), None)
    seats = read_places(dump_lines(g, seat_stem), is_seat) if seat_stem else {i: r for i, r in cities1000.items() if is_seat(r)}
    if HAWAR not in seats and has(g, "BH"):
        seats.update(read_places(dump_lines(g, "BH"), lambda r: r["id"] == HAWAR))
    if seat_stem != "allCountries":
        print(f"seats from {seat_stem or 'cities1000'}: down to {'PPLA4' if seat_stem else 'PPLA3'}")
    if HAWAR not in seats:
        print("no Hawar Islands row (290243): add BH.zip or allCountries", file=sys.stderr)

    overseas = {i: r for i, r in cities1000.items() if r["cc"] in OVERSEAS_FRANCE}
    arab = {i: r for i, r in {**cities1000, **seats}.items() if r["cc"] in ARAB_LEAGUE | {"EH"} or r["id"] == HAWAR}
    for i, r in seats.items():
        if r["cc"] == "TR" and i not in europe:
            europe[i] = r
        if r["id"] == LONGYEARBYEN:
            europe[i] = r
    arab_rows = dedupe(list(arab.values()))
    arab_points = [(r["lat"], r["lng"]) for r in arab_rows]
    neighbours = [r for r in cities1000.values() if r["cc"] in NEIGHBOURS and any(km((r["lat"], r["lng"]), p) <= NEIGHBOUR_KM for p in arab_points)]

    ids = {r["id"] for r in list(europe.values()) + list(overseas.values()) + arab_rows}
    arabic, aliases = read_alternate_names(dump_lines(g, "alternateNamesV2"), ids)
    wikidata = read_wikidata(args.wikidata_ar)

    zones = set()
    europe_lines, arab_lines = [HEADER], [HEADER]
    coverage = defaultdict(lambda: [0, 0])
    for r in sorted(list(europe.values()) + list(overseas.values()), key=lambda r: (country_of(r), r["id"])):
        name = arabic.get(r["id"]) or wikidata.get(r["id"], "")
        europe_lines.append(line_of(r, name, aliases.get(r["id"], set()), False))
        zones.add(r["zone"])
        coverage["europe"][0] += r["population"] * (1 if name else 0)
        coverage["europe"][1] += r["population"]
    for r in sorted(arab_rows, key=lambda r: (country_of(r), r["id"])):
        name = arabic.get(r["id"]) or wikidata.get(r["id"], "")
        arab_lines.append(line_of(r, name, aliases.get(r["id"], set()), False))
        zones.add(r["zone"])
        coverage["arab"][0] += r["population"] * (1 if name else 0)
        coverage["arab"][1] += r["population"]
    for r in sorted(neighbours, key=lambda r: (country_of(r), r["id"])):
        arab_lines.append(line_of(r, "", set(), True))
        zones.add(r["zone"])

    bad = []
    for z in sorted(zones):
        try:
            ZoneInfo(z)
        except Exception:  # noqa: BLE001 - any failure means the zone is unknown
            bad.append(z)
    if bad:
        print("unknown zones:", ", ".join(bad), file=sys.stderr)
        return 1

    args.out.mkdir(parents=True, exist_ok=True)
    europe_lines, arab_lines = compress_zones(europe_lines), compress_zones(arab_lines)
    europe_text = "\n".join(europe_lines) + "\n"
    arab_text = "\n".join(arab_lines) + "\n"
    size = len(europe_text.encode("utf-8")) + len(arab_text.encode("utf-8"))
    if size > BUDGET_BYTES:
        print(f"catalogue is {size} bytes, above the {BUDGET_BYTES}-byte budget", file=sys.stderr)
        return 1
    (args.out / "europe-cities.tsv").write_text(europe_text, encoding="utf-8", newline="\n")
    (args.out / "arab-cities.tsv").write_text(arab_text, encoding="utf-8", newline="\n")
    # The names of the admin1 codes the app reads (Iraq's Kurdistan Region for A6's chip, 6.11; the others for display).
    admin1_lines = ["country\tcode\tname"]
    if has(g, "admin1CodesASCII"):
        for line in dump_lines(g, "admin1CodesASCII"):
            c = line.rstrip("\n").split("\t")
            if len(c) >= 3 and "." in c[0]:
                cc, code = c[0].split(".", 1)
                if cc in ADMIN1_COUNTRIES:
                    admin1_lines.append(f"{cc}\t{code}\t{clean(c[2])}")
    (args.out / "admin1-names.tsv").write_text("\n".join(admin1_lines) + "\n", encoding="utf-8", newline="\n")
    (args.out / "PLACES-LICENSE.txt").write_text(
        "Place names and coordinates: GeoNames (https://www.geonames.org), CC BY 4.0.\n"
        "Arabic labels where GeoNames has none: Wikidata (https://www.wikidata.org), CC0.\n",
        encoding="utf-8", newline="\n",
    )
    for region, (named, total) in coverage.items():
        print(f"{region}: Arabic names for {100 * named / max(total, 1):.1f}% of the population")
    print(f"europe {len(europe_lines) - 1} rows, arab {len(arab_rows)} rows + {len(neighbours)} neighbour rows, {size} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
