"""Reproduce the pinned catalog before the Mahdia Zahra/Hiboun seam clip."""

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
        "contentSha256": "fcb79512942c42868ce7e1485b8f57b007f63f9896f413015764eae866db563f",
        "gzipSha256": "99dc7e3355e1552b698d9a33376446c5af27f96d0e5efe844856b5c13bf63056",
    },
    "neighborhoods.bin": {
        "contentSha256": "3e363798e3ca1f9f6606403198a478ba4909a08dd3ac3cc2d583c7319e94b460",
        "gzipSha256": "270e36e9a29cb8c2e61f91b8c42f8a55b91f9831aba4c3855efd7255d0041faf",
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
        compressed = (HERE / f"{name}.gz").read_bytes()
        if digest(compressed) != pins["gzipSha256"]:
            raise ValueError(f"Compressed snapshot changed: {name}")
        raw = gzip.decompress(compressed)
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
    rows = {row["id"]: row for row in catalog["features"]}
    if (rows["osm:relation:7152287"]["sourceId"] != "osm"
            or rows["osm:relation:7152283"]["sourceId"] != "isie-local-sectors-2023-hiboun-review"):
        raise ValueError("Expected Zahra/Hiboun sources changed")
    print(json.dumps({"status": "passed", "contentSha256": {name: pins["contentSha256"] for name, pins in INPUTS.items()},
                      "features": len(catalog["features"]), "conflicts": len(catalog["conflicts"])}))


if __name__ == "__main__":
    main()
