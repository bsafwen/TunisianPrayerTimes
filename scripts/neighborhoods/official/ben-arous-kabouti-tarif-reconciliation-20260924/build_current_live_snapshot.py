#!/usr/bin/env python3
"""Emit the pinned post-Oued/Sidi catalog that precedes the Kabouti clip."""
from __future__ import annotations

import gzip
import hashlib
import json
from pathlib import Path
import argparse


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
INPUTS = {
    "neighborhoods.json": {
        "file": HERE / "neighborhoods.json.gz",
        "gzipSha256": "a1f41e49ef2595849e484c4c3adcb06dfea3540c4e7eccf47617e5488ad2d83d",
        "contentSha256": "d9e8dd2a9ab5e227afa7d30a61ab5dd25ac383a24d2b97fa03914351134dfa9c",
    },
    "neighborhoods.bin": {
        "file": HERE / "neighborhoods.bin.gz",
        "gzipSha256": "a76cd838e9458b01e751de07ac6db6ffe1128d091962726c4897bbe310a27d62",
        "contentSha256": "4b0727226a5ed143cf9704e0c820b39d70ef21ba28a05b809dc84c12932342a7",
    },
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    output = args.output_dir.resolve()
    if output == REPO or output.is_relative_to(REPO):
        raise ValueError("Staging output must be outside the repository")
    raw_by_name: dict[str, bytes] = {}
    for name, pins in INPUTS.items():
        compressed = pins["file"].read_bytes()
        if sha256(compressed) != pins["gzipSha256"]:
            raise ValueError(f"Pinned snapshot bytes changed: {pins['file'].name}")
        raw = gzip.decompress(compressed)
        if sha256(raw) != pins["contentSha256"]:
            raise ValueError(f"Pinned catalog content changed: {name}")
        raw_by_name[name] = raw

    metadata = json.loads(raw_by_name["neighborhoods.json"])
    packed = raw_by_name["neighborhoods.bin"]
    if packed[:8] != b"NPOL\x00\x00\x00\x01":
        raise ValueError("Pinned live catalog has an unexpected NPOL header")
    if metadata["country"]["offset"] + metadata["country"]["length"] != len(packed):
        raise ValueError("Pinned live country geometry does not end at the binary boundary")
    features = metadata.get("features")
    if (not isinstance(features, list) or len(features) != 3474
            or sum(row.get("hasBoundary") is True for row in features) != 2572
            or len(metadata.get("conflicts", [])) != 485
            or len(metadata.get("sources", {})) != 44):
        raise ValueError("Pinned catalog inventory differs from the accepted post-Oued/Sidi base")
    by_id = {row["id"]: row for row in features}
    kabouti, tarif = by_id.get("osm:relation:7174569"), by_id.get("osm:relation:7104940")
    if (not kabouti or not tarif or kabouti.get("sourceId") != "osm"
            or kabouti.get("name") != "الكبوطي" or kabouti.get("governorateId") != 349
            or kabouti.get("hasBoundary") is not True
            or tarif.get("sourceId") != "isie-local-sectors-2023-hammamet-nine-neighbours"
            or tarif.get("name") != "جبل طريف" or tarif.get("governorateId") != 350):
        raise ValueError("Pinned Kabouti/Tarif identities changed")

    assets = output / "assets"
    assets.mkdir(parents=True, exist_ok=True)
    for name, raw in raw_by_name.items():
        (assets / name).write_bytes(raw)
    print(json.dumps({"status": "passed",
                      "contentSha256": {name: INPUTS[name]["contentSha256"] for name in INPUTS},
                      "features": len(features), "boundaries": 2572,
                      "conflicts": len(metadata["conflicts"]), "sources": len(metadata["sources"])},
                     ensure_ascii=True, sort_keys=True))


if __name__ == "__main__":
    main()
