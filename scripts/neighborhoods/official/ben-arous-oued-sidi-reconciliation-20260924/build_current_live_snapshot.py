#!/usr/bin/env python3
"""Emit the pinned live neighborhood catalog that preceded the Oued/Sidi clip."""
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
        "file": HERE / "neighborhoods.json.gz",
        "gzipSha256": "44cf8c6c0a153abb6a1af6048772c709449ef80b9a7b17a24d64c0b7e352f2e8",
        "contentSha256": "d5a2ed14e35705414490667bbf161fdd632b8a170d6fd669aaa090b7bdd571f5",
    },
    "neighborhoods.bin": {
        "file": HERE / "neighborhoods.bin.gz",
        "gzipSha256": "088cc43e4f6cb5ec0533ba1a93fbecd14ea92f08cf124fd9b86cd2778fbbed9a",
        "contentSha256": "5993c9d410313045946671970cdba929a61e20f27f24e5c32d0b1ab8fee92d09",
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
    if metadata.get("country", {}).get("offset", -1) + metadata.get("country", {}).get("length", -1) != len(packed):
        raise ValueError("Pinned live catalog country geometry does not end at the binary boundary")
    features = metadata.get("features")
    if (not isinstance(features, list) or len(features) != 3474
            or sum(feature.get("hasBoundary") is True for feature in features) != 2572
            or len(metadata.get("conflicts", [])) != 485
            or len(metadata.get("sources", {})) != 43):
        raise ValueError("Pinned catalog inventory does not match the reviewed live base")

    target = next((feature for feature in features if feature.get("id") == "osm:relation:7174614"), None)
    clipper = next((feature for feature in features if feature.get("id") == "osm:relation:7109196"), None)
    if (not target or not clipper or target.get("sourceId") != "osm"
            or target.get("name") != "سيدي سالم القارصي"
            or target.get("governorateId") != 349 or target.get("hasBoundary") is not True
            or clipper.get("sourceId") != "isie-local-sectors-2023-hammamet-nine-neighbours"
            or clipper.get("name") != "وادي الزيت" or clipper.get("governorateId") != 343):
        raise ValueError("Pinned Oued/Sidi target identities changed")

    assets = output / "assets"
    assets.mkdir(parents=True, exist_ok=True)
    for name, raw in raw_by_name.items():
        (assets / name).write_bytes(raw)
    print(json.dumps({
        "status": "passed",
        "inputs": {name: {"contentSha256": INPUTS[name]["contentSha256"],
                           "gzipSha256": INPUTS[name]["gzipSha256"]}
                   for name in INPUTS},
        "features": len(features), "boundaries": 2572,
        "conflicts": len(metadata["conflicts"]), "sources": len(metadata["sources"]),
    }, ensure_ascii=True, sort_keys=True))


if __name__ == "__main__":
    main()
