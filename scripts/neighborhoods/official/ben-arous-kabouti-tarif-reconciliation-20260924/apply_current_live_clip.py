#!/usr/bin/env python3
"""Rebuild, verify and optionally install the pinned Kabouti/Tarif seam clip."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
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
SOURCE_BUILDER = HERE / "build_preservation_source.py"
OVERLAY_BUILDER = REPO / "scripts/neighborhoods/build_best_effort_geometry_overlay.py"
TARGET_ID = "osm:relation:7174569"
CLIPPER_ID = "osm:relation:7104940"
DERIVED_SOURCE_ID = "osm-isie-reviewed-clip-kabouti-tarif-seam-20260924"
BASE_JSON_SHA = "d9e8dd2a9ab5e227afa7d30a61ab5dd25ac383a24d2b97fa03914351134dfa9c"
BASE_BIN_SHA = "4b0727226a5ed143cf9704e0c820b39d70ef21ba28a05b809dc84c12932342a7"
STAGED_JSON_SHA = "63908a83db9b024bddbd8ff75e11806e987a95742cde1571ac7c0286b46460e7"
STAGED_BIN_SHA = "d098fadb5cebac0c8c04d245ca3e9a526064b18c71162d9331c38df6e7b3d347"
MANIFEST_SHA = "09e7c40294dac515747b6d303a1a589247be87a39c62da55fd5f5648ae51c858"
BASE_BUILDER_SHA = "ab0e2a93abecf40483ce0caabfe45aaaa27e6936a33b4f5f1366b675c834aabf"
SOURCE_BUILDER_SHA = "1c248ad079889018e14013d27d22911bf6ef50a99362d3f912849a3cd82af25b"
OVERLAY_BUILDER_SHA = "872cd6b69bd1002f84e1598c97109f2ce8a6ad7e2656772658b5b067744a6f3c"
SOURCE_GEOJSON_SHA = "39170fe05560114a9a9b4e9933e7e232ea1db32b7595e3952eb62a9bae164911"
SOURCE_REVIEW_SHA = "cb8b346797bba3c8f9a258f00d3232e0629525ff85c9cf6cbd2a691646526aa9"
IMPACT_SHA = "ab3fbec8720f45e19a664d578f220cc884ebd0f8dd4d6dd5c831422653d53924"
KABOUTI_PDF_SHA = "f9a2edf4a8f8e07c86c6d8d054f3c98cb296af4d8f5e6630f94ffe1aa01d9a9a"
TARIF_PDF_SHA = "b588a1fcc7910ee29f2c2f5756175c5ddcbebd00de53553ad64e435937203168"
RECEIPT = HERE / "current-live-installation-receipt.json"
ALLOWED_TARGET_FIELDS = {"sourceId", "lat", "lng", "delegationId", "bbox", "areaKm2", "offset", "length"}
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
        raise ValueError(f"Invalid packed geometry range for {feature.get('id')}")
    return packed[start:start + length]


def area_proxy_m2(geometry) -> float:
    if geometry.is_empty:
        return 0.0
    latitude = geometry.representative_point().y
    return geometry.area * 111.32 ** 2 * math.cos(math.radians(latitude)) * 1_000_000


def verify_delta(before: dict, before_bin: bytes, after: dict, after_bin: bytes,
                 report: dict, impact: dict) -> dict:
    before_features, after_features = before["features"], after["features"]
    before_ids = [row["id"] for row in before_features]
    after_ids = [row["id"] for row in after_features]
    if before_ids != after_ids or len(before_ids) != len(set(before_ids)):
        raise ValueError("The clip added, removed, reordered or duplicated selectable rows")
    before_by_id = {row["id"]: row for row in before_features}
    after_by_id = {row["id"]: row for row in after_features}
    if TARGET_ID not in before_by_id or CLIPPER_ID not in before_by_id:
        raise ValueError("Pinned Kabouti/Tarif rows are absent from the current live base")

    changed_rows = []
    for identifier in before_ids:
        old, new = before_by_id[identifier], after_by_id[identifier]
        if identifier == TARGET_ID:
            changed = {key for key in old.keys() | new.keys() if old.get(key) != new.get(key)}
            if not changed or not changed.issubset(ALLOWED_TARGET_FIELDS):
                raise ValueError(f"Target changed unexpected metadata: {sorted(changed)}")
            if new.get("sourceId") != DERIVED_SOURCE_ID:
                raise ValueError("Kabouti target does not use the pinned derived source")
            changed_rows.append(identifier)
        else:
            if {key: value for key, value in old.items() if key != "offset"} != {
                    key: value for key, value in new.items() if key != "offset"}:
                raise ValueError(f"Unrelated catalog metadata changed: {identifier}")
            if old.get("hasBoundary") is True:
                if geometry_payload(old, before_bin) != geometry_payload(new, after_bin):
                    raise ValueError(f"Unrelated polygon payload changed: {identifier}")
    if changed_rows != [TARGET_ID]:
        raise ValueError(f"Expected only one changed row, got {changed_rows}")

    if before.get("retiredLocalityIds") != after.get("retiredLocalityIds"):
        raise ValueError("Retired-locality IDs changed")
    if before.get("sources", {}).keys() | {DERIVED_SOURCE_ID} != after.get("sources", {}).keys():
        raise ValueError("Unexpected source additions/removals")
    if any(after["sources"][key] != value for key, value in before["sources"].items()):
        raise ValueError("An existing source record changed")
    if set(before) != set(after):
        raise ValueError("Top-level catalog keys changed")
    changed_top = {key for key in before if before[key] != after[key]}
    if changed_top - DERIVED_TOP_LEVEL_FIELDS:
        raise ValueError(f"Unexpected top-level fields changed: {sorted(changed_top - DERIVED_TOP_LEVEL_FIELDS)}")

    old_country = before["country"]
    new_country = after["country"]
    if before_bin[old_country["offset"]:old_country["offset"] + old_country["length"]] != \
            after_bin[new_country["offset"]:new_country["offset"] + new_country["length"]]:
        raise ValueError("Country polygon payload changed")

    # Confirm the clipped-away geometry is covered by the unchanged Tarif
    # polygon, with only app-grid rounding slivers within the existing 50 m2
    # geometry tolerance. Also ensure quantization did not materially expand
    # Kabouti or introduce a new sector-conflict pair.
    scripts_dir = REPO / "scripts"
    sys.path.insert(0, str(scripts_dir))
    from generate_neighborhoods import SCALE
    sys.path.insert(0, str(scripts_dir / "neighborhoods"))
    from audit_catalog import GpsLabelAudit
    from build_best_effort_geometry_overlay import unpack

    before_target = unpack(before_by_id[TARGET_ID], before_bin, SCALE)
    after_target = unpack(after_by_id[TARGET_ID], after_bin, SCALE)
    tarif = unpack(before_by_id[CLIPPER_ID], before_bin, SCALE)
    removed = before_target.difference(after_target)
    uncovered = removed.difference(tarif)
    added = after_target.difference(before_target)
    uncovered_m2, added_m2 = area_proxy_m2(uncovered), area_proxy_m2(added)
    if uncovered_m2 > 50 or added_m2 > 50:
        raise ValueError(f"Grid rounding caused a material seam gap/expansion: {uncovered_m2:.3f} m2 / {added_m2:.3f} m2")
    old_pairs = {tuple(sorted(row["ids"])) for row in before["conflicts"]
                 if row.get("reason") == "overlapping_sectors"}
    new_pairs = {tuple(sorted(row["ids"])) for row in after["conflicts"]
                 if row.get("reason") == "overlapping_sectors"}
    added_pairs = sorted(new_pairs - old_pairs)
    if added_pairs:
        raise ValueError(f"The clip introduced new overlapping-sector pairs: {added_pairs}")

    boundaries = [feature for feature in after_features if feature.get("hasBoundary")]
    geometries = [unpack(feature, after_bin, SCALE) for feature in boundaries]
    country = unpack(after["country"], after_bin, SCALE)
    model = GpsLabelAudit(boundaries, geometries, country, after["conflicts"])
    index_by_id = {feature["id"]: index for index, feature in enumerate(boundaries)}
    broad = impact["runtimeSamples"]["broadInterior"]
    broad_eval = model.evaluate(broad["lat"], broad["lng"])
    broad_winner = (boundaries[broad_eval["winner"]]["id"]
                    if broad_eval["winner"] is not None else None)
    if (broad_winner != CLIPPER_ID or index_by_id[TARGET_ID] in broad_eval["candidates"]):
        raise ValueError(f"Broad interior GPS sample did not resolve to Tarif: {broad_winner}")

    sliver = impact["runtimeSamples"]["preservedSliver"]
    sliver_eval = model.evaluate(sliver["lat"], sliver["lng"])
    pair = {index_by_id[TARGET_ID], index_by_id[CLIPPER_ID]}
    if (not pair.issubset(set(sliver_eval["candidates"]))
            or not pair.issubset(set(sliver_eval["excluded"]))
            or sliver_eval["winner"] is not None):
        raise ValueError("Preserved Kabouti/Tarif sliver is not conflict-suppressed")
    active_pair_ids = {tuple(sorted(boundaries[i]["id"] for i in active))
                       for active in sliver_eval["activeConflicts"]}
    if tuple(sorted([TARGET_ID, CLIPPER_ID])) not in active_pair_ids:
        raise ValueError("Preserved sliver does not activate the exact Kabouti/Tarif conflict")

    preservation = report.get("preservation", {})
    if (report.get("status") != "passed" or not report.get("audit", {}).get("passed")
            or not all(preservation.get(key) is True for key in (
                "allUntouchedPolygonBytesIdentical", "allUntouchedPolygonFieldsPreservedExceptOffset",
                "allPointRowsIdentical", "countryPayloadIdentical"))):
        raise ValueError("Generic overlay/audit did not prove preservation")
    clips = report.get("clips", [])
    if len(clips) != 1 or clips[0].get("id") != TARGET_ID or clips[0].get("clipById") != CLIPPER_ID:
        raise ValueError("Overlay report does not describe only the pinned Kabouti/Tarif clip")
    clip = clips[0]
    if (not 0.54 <= clip.get("removedAreaKm2", -1) <= 0.55
            or not 0.00085 <= clip.get("preservedOverlapKm2", -1) <= 0.0011
            or not 0.00085 <= clip.get("remainingOverlapKm2", -1) <= 0.0011
            or clip.get("formerOverlapGpsWinnerId") != CLIPPER_ID
            or clip.get("preservedOverlapGpsWinnerId") is not None
            or clip.get("preservedOverlapGpsConflictSuppressed") is not True):
        raise ValueError("Clip area, sliver retention or generic GPS checks changed")
    if uncovered_m2 > 50 or added_m2 > 50:
        raise ValueError("Seam gap/expansion exceeds the one-grid tolerance")
    return {
        "status": "passed",
        "changedSelectableRows": changed_rows,
        "preservedUntouchedPolygonPayloads": preservation.get("untouchedPolygonPayloads"),
        "pointRowsPreserved": preservation.get("allPointRowsIdentical"),
        "existingSourceRecordsPreserved": True,
        "countryPayloadPreserved": preservation.get("countryPayloadIdentical"),
        "newOverlappingSectorPairs": added_pairs,
        "gridRoundingGapOutsideTarifM2": uncovered_m2,
        "gridRoundingExpansionBeyondOriginalKaboutiM2": added_m2,
        "gpsBroadInterior": {"winnerId": broad_winner,
                             "targetAbsentFromCandidates": index_by_id[TARGET_ID] not in broad_eval["candidates"]},
        "gpsPreservedSliver": {"winnerId": None,
                               "bothPairMembersSuppressed": pair.issubset(set(sliver_eval["excluded"])),
                               "bothPairMembersStrictlyContainSample": pair.issubset(set(sliver_eval["candidates"]))},
        "clip": {key: clip[key] for key in (
            "removedAreaKm2", "remainingAreaKm2", "preservedOverlapKm2", "remainingOverlapKm2",
            "formerOverlapGpsWinnerId", "preservedOverlapGpsWinnerId",
            "preservedOverlapGpsConflictSuppressed")},
    }


def replace_atomically(path: Path, payload: bytes) -> None:
    temporary: Path | None = None
    try:
        fd, name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
        temporary = Path(name)
        with os.fdopen(fd, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="Staging and compact validation output, outside the repository")
    parser.add_argument("--install", action="store_true", help="Install after every pinned check passes")
    args = parser.parse_args()
    output = args.output_dir.resolve()
    if output == REPO or output.is_relative_to(REPO):
        raise ValueError("Output directory must be outside the repository")
    output.mkdir(parents=True, exist_ok=True)

    pins = {
        "manifestSha256": file_sha(MANIFEST),
        "baseBuilderSha256": file_sha(BASE_BUILDER),
        "sourceBuilderSha256": file_sha(SOURCE_BUILDER),
        "overlayBuilderSha256": file_sha(OVERLAY_BUILDER),
        "preservationGeojsonSha256": file_sha(HERE / "kabouti-preservation-ring.geojson"),
        "sourceReviewSha256": file_sha(HERE / "source-review.json"),
        "partialClipImpactSha256": file_sha(HERE / "evidence/partial-clip-impact.json"),
        "kaboutiPdfSha256": file_sha(HERE / "evidence/kabouti.pdf"),
        "tarifPdfSha256": file_sha(HERE / "evidence/jebel-tarif.pdf"),
    }
    expected = {"manifestSha256": MANIFEST_SHA,
                "baseBuilderSha256": BASE_BUILDER_SHA,
                "sourceBuilderSha256": SOURCE_BUILDER_SHA,
                "overlayBuilderSha256": OVERLAY_BUILDER_SHA,
                "preservationGeojsonSha256": SOURCE_GEOJSON_SHA,
                "sourceReviewSha256": SOURCE_REVIEW_SHA,
                "partialClipImpactSha256": IMPACT_SHA,
                "kaboutiPdfSha256": KABOUTI_PDF_SHA,
                "tarifPdfSha256": TARIF_PDF_SHA}
    if pins != expected:
        raise ValueError(f"A pinned source or builder changed; review before rebasing: {pins}")

    command = [sys.executable, str(OVERLAY_BUILDER), "--manifest",
               str(MANIFEST.relative_to(REPO)).replace("\\", "/"), "--output-dir", str(output)]
    process = subprocess.run(command, cwd=REPO, capture_output=True, text=True)
    if process.returncode:
        raise RuntimeError(f"Overlay stage failed:\n{process.stdout}\n{process.stderr}")
    summary = json.loads(process.stdout)
    staged_assets = output / "assets"
    with tempfile.TemporaryDirectory(prefix="kabouti-tarif-base-") as temp_name:
        base_output = Path(temp_name)
        base_run = subprocess.run([sys.executable, str(BASE_BUILDER), "--output-dir", str(base_output)],
                                  cwd=REPO, capture_output=True, text=True)
        if base_run.returncode:
            raise RuntimeError(f"Pinned live snapshot failed:\n{base_run.stdout}\n{base_run.stderr}")
        before, before_json, before_bin = load_assets(base_output / "assets")
    after, after_json, after_bin = load_assets(staged_assets)
    report_path = output / "geometry-overlay-report.json"
    report = json.loads(report_path.read_text(encoding="utf-8"))
    impact = json.loads((HERE / "evidence/partial-clip-impact.json").read_text(encoding="utf-8"))
    if (sha256(before_json) != BASE_JSON_SHA or sha256(before_bin) != BASE_BIN_SHA
            or sha256(after_json) != STAGED_JSON_SHA or sha256(after_bin) != STAGED_BIN_SHA):
        raise ValueError("Base or staged output SHA differs from the pinned result")
    verification = verify_delta(before, before_bin, after, after_bin, report, impact)
    verification.update({"inputPins": pins | {"driverSha256": file_sha(Path(__file__))},
                         "baseAssetSha256": {"neighborhoodsJson": sha256(before_json),
                                              "neighborhoodsBin": sha256(before_bin)},
                         "stagedAssetSha256": {"neighborhoodsJson": sha256(after_json),
                                                "neighborhoodsBin": sha256(after_bin)},
                         "overlayReportSha256": file_sha(report_path),
                         "overlayBuilderOutput": summary})
    verification_path = output / "current-chain-verification.json"
    verification_path.write_text(json.dumps(verification, ensure_ascii=False, indent=2) + "\n",
                                 encoding="utf-8", newline="\n")

    current_pair = (file_sha(ASSETS / "neighborhoods.json"), file_sha(ASSETS / "neighborhoods.bin"))
    base_pair = (BASE_JSON_SHA, BASE_BIN_SHA)
    staged_pair = (STAGED_JSON_SHA, STAGED_BIN_SHA)
    if current_pair == staged_pair:
        install_state = "already_installed"
    elif args.install:
        if current_pair != base_pair:
            raise ValueError(f"Live assets changed since the Kabouti/Tarif snapshot: {current_pair}")
        old_json = (ASSETS / "neighborhoods.json").read_bytes()
        old_bin = (ASSETS / "neighborhoods.bin").read_bytes()
        try:
            replace_atomically(ASSETS / "neighborhoods.json", after_json)
            replace_atomically(ASSETS / "neighborhoods.bin", after_bin)
            installed_pair = (file_sha(ASSETS / "neighborhoods.json"), file_sha(ASSETS / "neighborhoods.bin"))
            if installed_pair != staged_pair:
                raise ValueError(f"Installed catalog SHA mismatch: {installed_pair}")
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
            "method": "Apply one provisional partial clip on top of the pinned post-Oued/Sidi live catalog; preserve the small Kabouti GeoPDF-supported overlap for existing runtime conflict suppression.",
            "inputs": pins | {"driverSha256": file_sha(Path(__file__))},
            "outputs": {"neighborhoodsJsonSha256": STAGED_JSON_SHA,
                         "neighborhoodsBinSha256": STAGED_BIN_SHA},
            "verification": verification,
        }
        RECEIPT.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8", newline="\n")
    print(json.dumps({"status": "passed", "installState": install_state,
                      "verificationFile": str(verification_path),
                      "receiptFile": str(RECEIPT) if install_state in {"installed", "already_installed"} else None,
                      "changedSelectableRows": verification["changedSelectableRows"],
                      "clip": verification["clip"],
                      "gps": {"broadInterior": verification["gpsBroadInterior"],
                              "preservedSliver": verification["gpsPreservedSliver"]},
                      "seamTolerance": {"uncoveredAreaM2": verification["gridRoundingGapOutsideTarifM2"],
                                        "addedKaboutiAreaM2": verification["gridRoundingExpansionBeyondOriginalKaboutiM2"],
                                        "newOverlappingPairs": verification["newOverlappingSectorPairs"]}},
                     ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
