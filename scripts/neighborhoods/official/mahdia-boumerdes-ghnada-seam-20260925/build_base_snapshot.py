"""Reproduce the pinned catalog before the Mahdia/Beni Hassan seam correction."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
INPUTS = {
    "neighborhoods.json": {
        "contentSha256": "5bed2d5a3a7f8aef33421e451e47f36a7f656626f536693e6037715a758b64d0",
        "gzipSha256": "bea17edcbccd41ede930363de0fca5381b4c1f53375b7850962679f7fac651bb",
    },
    "neighborhoods.bin": {
        "contentSha256": "2083f237ab9f4a59fc07f003812e283acab869d7598d1895f5436de469feb38e",
        "gzipSha256": "e3db67f701c64679be7615c31eee7bc17cbbf18fd7e935cbe663d32c3ddbed0b",
    },
}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    output = args.output_dir.resolve()
    if output == REPO or output.is_relative_to(REPO):
        raise ValueError("Output must be outside the repository")
    assets = output / "assets"
    assets.mkdir(parents=True, exist_ok=True)
    raw_by_name = {}
    for name, pins in INPUTS.items():
        packed = (HERE / f"{name}.gz").read_bytes()
        if digest(packed) != pins["gzipSha256"]:
            raise ValueError(f"Compressed snapshot changed: {name}")
        raw = gzip.decompress(packed)
        if digest(raw) != pins["contentSha256"]:
            raise ValueError(f"Snapshot content changed: {name}")
        raw_by_name[name] = raw
        (assets / name).write_bytes(raw)
    catalog = json.loads(raw_by_name["neighborhoods.json"])
    binary = raw_by_name["neighborhoods.bin"]
    if binary[:8] != b"NPOL\x00\x00\x00\x01":
        raise ValueError("Unexpected packed geometry format")
    if (len(catalog["features"]) != 3473
            or sum(row.get("kind") == "sector" for row in catalog["features"]) != 2085
            or len(catalog["conflicts"]) != 470
            or catalog["country"]["offset"] + catalog["country"]["length"] != len(binary)):
        raise ValueError("Pinned catalog inventory changed")
    by_id = {row["id"]: row for row in catalog["features"]}
    if (by_id["osm:relation:7152245"]["sourceId"] != "osm"
            or by_id["osm:relation:7114531"]["sourceId"] != "isie-local-sectors-2023-beni-hassan"):
        raise ValueError("Expected Boumerdes/Ghnada source identities changed")
    print(json.dumps({"status": "passed", "contentSha256": {name: pins["contentSha256"] for name, pins in INPUTS.items()},
                      "features": len(catalog["features"]), "conflicts": len(catalog["conflicts"])}))


if __name__ == "__main__":
    main()
