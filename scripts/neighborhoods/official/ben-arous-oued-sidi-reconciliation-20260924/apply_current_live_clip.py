#!/usr/bin/env python3
"""Rebuild, verify and optionally install the narrowly pinned Oued/Sidi clip."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
ASSETS = REPO / "android-app/app/src/main/assets"
BASE_BUILDER = HERE / "build_current_live_snapshot.py"
MANIFEST = HERE / "geometry-overlay-manifest.json"
OVERLAY_BUILDER = REPO / "scripts/neighborhoods/build_best_effort_geometry_overlay.py"
TARGET_ID = "osm:relation:7174614"
CLIPPER_ID = "osm:relation:7109196"
DERIVED_SOURCE_ID = "osm-isie-reviewed-clip-oued-ezzit-sidi-salem-seam-20260924"
BASE_JSON_SHA = "d5a2ed14e35705414490667bbf161fdd632b8a170d6fd669aaa090b7bdd571f5"
BASE_BIN_SHA = "5993c9d410313045946671970cdba929a61e20f27f24e5c32d0b1ab8fee92d09"
STAGED_JSON_SHA = "d9e8dd2a9ab5e227afa7d30a61ab5dd25ac383a24d2b97fa03914351134dfa9c"
STAGED_BIN_SHA = "4b0727226a5ed143cf9704e0c820b39d70ef21ba28a05b809dc84c12932342a7"
MANIFEST_SHA = "53bdfc234bcb30d5d5e5125876f5dbcde6542aee6d2d8ce7ebc2ee2c836b0146"
BASE_BUILDER_SHA = "cdcdf71d5f9fc0d2dea81537edda26f601cbadbc2098d1246db3f3943971933d"
OVERLAY_BUILDER_SHA = "872cd6b69bd1002f84e1598c97109f2ce8a6ad7e2656772658b5b067744a6f3c"
RECEIPT = HERE / "current-live-installation-receipt.json"
ALLOWED_TARGET_FIELDS = {
    "sourceId", "lat", "lng", "delegationId", "bbox", "areaKm2", "offset", "length",
}
DERIVED_TOP_LEVEL_FIELDS = {"features", "sources", "cells", "conflicts", "country"}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_sha(path: Path) -> str:
    return sha256(path.read_bytes())


def load_assets(directory: Path) -> tuple[dict, bytes, bytes]:
    json_raw = (directory / "neighborhoods.json").read_bytes()
    bin_raw = (directory / "neighborhoods.bin").read_bytes()
    return json.loads(json_raw), json_raw, bin_raw


def geometry_payload(feature: dict, packed: bytes) -> bytes:
    start, length = feature["offset"], feature["length"]
    if type(start) is not int or type(length) is not int or start < 8 or length <= 0:
        raise ValueError(f"Invalid packed range for {feature.get('id')}")
    return packed[start:start + length]


def verify_delta(before: dict, before_bin: bytes, after: dict, after_bin: bytes,
                 report: dict) -> dict:
    before_features = before["features"]
    after_features = after["features"]
    before_ids = [row["id"] for row in before_features]
    after_ids = [row["id"] for row in after_features]
    if before_ids != after_ids or len(before_ids) != len(set(before_ids)):
        raise ValueError("The staged clip added, removed, reordered or duplicated selectable rows")
    before_by_id = {row["id"]: row for row in before_features}
    after_by_id = {row["id"]: row for row in after_features}
    if TARGET_ID not in before_by_id or CLIPPER_ID not in before_by_id:
        raise ValueError("Pinned Oued/Sidi rows are missing from the live base")

    changed_metadata: list[str] = []
    for identifier in before_ids:
        old, new = before_by_id[identifier], after_by_id[identifier]
        if identifier == TARGET_ID:
            changed = {key for key in old.keys() | new.keys() if old.get(key) != new.get(key)}
            if not changed or not changed.issubset(ALLOWED_TARGET_FIELDS):
                raise ValueError(f"Target changed unexpected metadata fields: {sorted(changed)}")
            if new.get("sourceId") != DERIVED_SOURCE_ID:
                raise ValueError("Sidi Salem target does not use the pinned derived source")
            changed_metadata.append(identifier)
        else:
            if {key: value for key, value in old.items() if key != "offset"} != {
                    key: value for key, value in new.items() if key != "offset"}:
                raise ValueError(f"Unrelated catalog metadata changed: {identifier}")
            if old.get("hasBoundary") is True:
                if geometry_payload(old, before_bin) != geometry_payload(new, after_bin):
                    raise ValueError(f"Unrelated polygon payload changed: {identifier}")

    if before.get("retiredLocalityIds") != after.get("retiredLocalityIds"):
        raise ValueError("Retired-locality identities changed during the geometry clip")
    before_sources = before.get("sources", {})
    after_sources = after.get("sources", {})
    if set(after_sources) != set(before_sources) | {DERIVED_SOURCE_ID}:
        raise ValueError("Unexpected source additions or removals")
    if any(after_sources[key] != value for key, value in before_sources.items()):
        raise ValueError("An existing source record changed")
    if set(before) != set(after):
        raise ValueError("Top-level catalog keys changed")
    changed_top = {key for key in before if before[key] != after[key]}
    if changed_top - DERIVED_TOP_LEVEL_FIELDS:
        raise ValueError(f"Unexpected top-level metadata change: {sorted(changed_top - DERIVED_TOP_LEVEL_FIELDS)}")
    if len(changed_metadata) != 1:
        raise ValueError("The geometry operation did not change exactly the target row")

    country_before = before["country"]
    country_after = after["country"]
    if before_bin[country_before["offset"]:country_before["offset"] + country_before["length"]] != \
            after_bin[country_after["offset"]:country_after["offset"] + country_after["length"]]:
        raise ValueError("The country geometry payload changed")

    if report.get("status") != "passed" or not report.get("audit", {}).get("passed"):
        raise ValueError("Generic overlay audit did not pass")
    preservation = report.get("preservation", {})
    if not all(preservation.get(key) is True for key in (
            "allUntouchedPolygonBytesIdentical", "allUntouchedPolygonFieldsPreservedExceptOffset",
            "allPointRowsIdentical", "countryPayloadIdentical")):
        raise ValueError("Generic overlay builder did not prove byte preservation")
    if (report.get("counts", {}).get("features") != 3474
            or report.get("counts", {}).get("boundaryFeatures") != 2572
            or report.get("counts", {}).get("conflictsAfter") != 485):
        raise ValueError("Catalog inventory or conflict count changed unexpectedly")
    clips = report.get("clips", [])
    if len(clips) != 1 or clips[0].get("id") != TARGET_ID or clips[0].get("clipById") != CLIPPER_ID:
        raise ValueError("Overlay report does not describe only the pinned Oued/Sidi clip")
    clip = clips[0]
    if (not 0.46 <= clip.get("removedAreaKm2", -1) <= 0.48
            or not 0.0060 <= clip.get("remainingOverlapKm2", -1) <= 0.0068
            or clip.get("formerOverlapGpsWinnerId") != CLIPPER_ID
            or clip.get("preservedOverlapGpsWinnerId") is not None
            or clip.get("preservedOverlapGpsConflictSuppressed") is not True):
        raise ValueError("Clip area or conflict-aware GPS checks changed")
    return {
        "status": "passed",
        "changedSelectableRows": changed_metadata,
        "preservedUntouchedPolygonPayloads": preservation.get("untouchedPolygonPayloads"),
        "pointRowsPreserved": preservation.get("allPointRowsIdentical"),
        "countryPayloadPreserved": preservation.get("countryPayloadIdentical"),
        "changedDerivedTopLevelFields": sorted(changed_top),
        "clip": {key: clip[key] for key in (
            "removedAreaKm2", "remainingAreaKm2", "preservedOverlapKm2", "remainingOverlapKm2",
            "formerOverlapGpsWinnerId", "preservedOverlapGpsWinnerId",
            "preservedOverlapGpsConflictSuppressed")},
    }


def replace_atomically(path: Path, payload: bytes) -> None:
    temp_path: Path | None = None
    try:
        fd, name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
        temp_path = Path(name)
        with os.fdopen(fd, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp_path, path)
    finally:
        if temp_path is not None and temp_path.exists():
            temp_path.unlink()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="Staging and compact validation output, outside the repository")
    parser.add_argument("--install", action="store_true",
                        help="Install the validated catalog JSON/BIN into app assets")
    args = parser.parse_args()
    output = args.output_dir.resolve()
    if output == REPO or output.is_relative_to(REPO):
        raise ValueError("Output directory must be outside the repository")
    output.mkdir(parents=True, exist_ok=True)

    pins = {
        "manifestSha256": file_sha(MANIFEST),
        "baseBuilderSha256": file_sha(BASE_BUILDER),
        "overlayBuilderSha256": file_sha(OVERLAY_BUILDER),
    }
    expected_pins = {"manifestSha256": MANIFEST_SHA, "baseBuilderSha256": BASE_BUILDER_SHA,
                     "overlayBuilderSha256": OVERLAY_BUILDER_SHA}
    if pins != expected_pins:
        raise ValueError(f"Reproducible build inputs changed; review before rebasing pins: {pins}")

    # The generic overlay command runs the pinned snapshot builder; keep its
    # output and audit next to the calling task for review.
    command = [sys.executable, str(OVERLAY_BUILDER), "--manifest",
               str(MANIFEST.relative_to(REPO)).replace("\\", "/"), "--output-dir", str(output)]
    process = subprocess.run(command, cwd=REPO, capture_output=True, text=True)
    if process.returncode:
        raise RuntimeError(f"Overlay stage failed:\n{process.stdout}\n{process.stderr}")
    summary = json.loads(process.stdout)
    staged_assets = output / "assets"

    # Materialize the pinned live base in a temporary path for a byte-level
    # independent delta audit. The overlay report separately records its hash.
    with tempfile.TemporaryDirectory(prefix="oued-sidi-base-") as temp_name:
        base_output = Path(temp_name)
        base_run = subprocess.run([sys.executable, str(BASE_BUILDER), "--output-dir", str(base_output)],
                                  cwd=REPO, capture_output=True, text=True)
        if base_run.returncode:
            raise RuntimeError(f"Pinned live snapshot failed:\n{base_run.stdout}\n{base_run.stderr}")
        before, before_json, before_bin = load_assets(base_output / "assets")
    after, after_json, after_bin = load_assets(staged_assets)
    overlay_report_path = output / "geometry-overlay-report.json"
    overlay_report = json.loads(overlay_report_path.read_text(encoding="utf-8"))
    if (sha256(before_json) != BASE_JSON_SHA or sha256(before_bin) != BASE_BIN_SHA
            or sha256(after_json) != STAGED_JSON_SHA or sha256(after_bin) != STAGED_BIN_SHA):
        raise ValueError("Base or staged output hash differs from the pinned package result")
    verification = verify_delta(before, before_bin, after, after_bin, overlay_report)
    verification.update({"inputPins": expected_pins | {"driverSha256": file_sha(Path(__file__))},
                         "baseAssetSha256": {"neighborhoodsJson": sha256(before_json),
                                              "neighborhoodsBin": sha256(before_bin)},
                         "stagedAssetSha256": {"neighborhoodsJson": sha256(after_json),
                                                "neighborhoodsBin": sha256(after_bin)},
                         "overlayReportSha256": file_sha(overlay_report_path),
                         "overlayBuilderOutput": summary})
    verification_path = output / "current-chain-verification.json"
    verification_path.write_text(json.dumps(verification, ensure_ascii=False, indent=2) + "\n",
                                 encoding="utf-8", newline="\n")

    current_json = file_sha(ASSETS / "neighborhoods.json")
    current_bin = file_sha(ASSETS / "neighborhoods.bin")
    current_pair = (current_json, current_bin)
    base_pair = (BASE_JSON_SHA, BASE_BIN_SHA)
    staged_pair = (STAGED_JSON_SHA, STAGED_BIN_SHA)
    if current_pair == staged_pair:
        install_state = "already_installed"
    elif args.install:
        if current_pair != base_pair:
            raise ValueError(f"Live catalog changed after the pinned snapshot: {current_pair}")
        old_json, old_bin = (ASSETS / "neighborhoods.json").read_bytes(), (ASSETS / "neighborhoods.bin").read_bytes()
        try:
            replace_atomically(ASSETS / "neighborhoods.json", after_json)
            replace_atomically(ASSETS / "neighborhoods.bin", after_bin)
            if (file_sha(ASSETS / "neighborhoods.json"), file_sha(ASSETS / "neighborhoods.bin")) != staged_pair:
                raise ValueError("Installed catalog hash verification failed")
        except Exception:
            replace_atomically(ASSETS / "neighborhoods.json", old_json)
            replace_atomically(ASSETS / "neighborhoods.bin", old_bin)
            raise
        install_state = "installed"
    else:
        install_state = "staged_only"

    verification["installState"] = install_state
    verification_path.write_text(json.dumps(verification, ensure_ascii=False, indent=2) + "\n",
                                 encoding="utf-8", newline="\n")
    if install_state in {"installed", "already_installed"}:
        receipt = {
            "schemaVersion": 1,
            "status": "installed",
            "installedAtLocalDate": "2026-09-24",
            "targetId": TARGET_ID,
            "clipperId": CLIPPER_ID,
            "method": "Apply one reviewed partial clip on top of a compressed, SHA-pinned snapshot of the then-current live catalog; keep the source-supported junction overlap for existing runtime conflict suppression.",
            "inputs": pins | {"driverSha256": file_sha(Path(__file__))} | {
                "baseNeighborhoodsJsonSha256": BASE_JSON_SHA,
                "baseNeighborhoodsBinSha256": BASE_BIN_SHA,
                "preservationGeojsonSha256": "2f6958dfb7975029f0e7bbe6c24ff6eefdf5182d93f572dc607e879785d3b7fa",
                "sourceReviewSha256": "5a868a04e1879ebcd2ea2c03d099d6f77e5e282ac37df05598fcee9de94dca41",
                "overlayReportSha256": verification["overlayReportSha256"],
            },
            "outputs": {"neighborhoodsJsonSha256": STAGED_JSON_SHA,
                         "neighborhoodsBinSha256": STAGED_BIN_SHA},
            "verification": verification,
        }
        RECEIPT.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8", newline="\n")
    print(json.dumps({"status": "passed", "installState": install_state,
                      "verificationFile": str(verification_path),
                      "receiptFile": str(RECEIPT) if install_state in {"installed", "already_installed"} else None,
                      "stagedAssetSha256": verification["stagedAssetSha256"],
                      "changedSelectableRows": verification["changedSelectableRows"],
                      "clip": verification["clip"]}, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
