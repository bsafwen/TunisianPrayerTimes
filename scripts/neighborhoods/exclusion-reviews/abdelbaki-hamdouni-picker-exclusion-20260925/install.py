#!/usr/bin/env python3
"""Apply one reviewed exact-ID picker/GPS-label exclusion; stage by default."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import shutil
import sys
import tempfile
from pathlib import Path


PACKAGE = Path(__file__).resolve().parent
REPO = PACKAGE.parents[3]
WORKSPACE = Path(r"C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows")
FEATURE_ID = "osm:way:744642242"
SOURCE_REVIEW = WORKSPACE / "work/official-imada-evidence-20260924-v2/release-integration/residential-extras-review/review.json"
SOURCE_REVIEW_SHA256 = "f61b2b0ba1dfb7a0f1d556f62a070d7fdb9a3ceeedff3b143d42fa6b6bafffc2"
ASSETS = REPO / "android-app/app/src/main/assets"
JSON_PATH = ASSETS / "neighborhoods.json"
BIN_PATH = ASSETS / "neighborhoods.bin"
RETIRED_PATH = ASSETS / "retired-localities.json"
EXCLUSIONS_PATH = REPO / "scripts/neighborhoods/final-exclusions.json"
REPOSITORY_KT = REPO / "android-app/app/src/main/java/com/tunisianprayertimes/LocalityRepository.kt"
PREFS_KT = REPO / "android-app/app/src/main/java/com/tunisianprayertimes/PrefsManager.kt"
EXPECTED_BEFORE = {
    "neighborhoods.json": "d920c17fe018db9f77cf3bc747ac8b11336ae2c62c74fcb877acaaa4088c4c4f",
    "neighborhoods.bin": "a34bdf230220f2a400aefc693f9f1055ad6d9ad59fd640f04c0418485a02d8cd",
    "retired-localities.json": "32ba6be85e14d9dc749b40c7098091c128baedc8810127585e684d763da8836c",
    "final-exclusions.json": "d240c9da55e1b86ace5473cace1ecb1575ba576bd869264828dbb81ce3508bb2",
}
EXPECTED_CODE = {
    "LocalityRepository.kt": "59dcb3e9a2687f5428e117b72fcb536a6888fba6982a197ca04a7df57a830ed9",
    "PrefsManager.kt": "0e159eba7c557e9da203165d98d6de1e9dff5f9ab76e8b0905e2744f3f297731",
}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def stable_json(value: object, *, compact: bool = False) -> bytes:
    options = {"ensure_ascii": False, "sort_keys": False}
    if compact:
        options["separators"] = (",", ":")
    else:
        options["indent"] = 2
    return (json.dumps(value, **options) + "\n").encode("utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def load_module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    require(spec is not None and spec.loader is not None, f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def write_candidate(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--stage-only", action="store_true", help="write candidate files, do not change app assets")
    mode.add_argument("--install", action="store_true", help="atomically update the pinned manifest and app assets")
    args = parser.parse_args()

    review_bytes = SOURCE_REVIEW.read_bytes()
    require(sha(review_bytes) == SOURCE_REVIEW_SHA256, "pinned residential extras source review changed")
    review = json.loads(review_bytes.decode("utf-8"))
    rows = [row for row in review.get("rows", []) if row.get("id") == FEATURE_ID]
    require(len(rows) == 1, "source review must contain exactly one target row")
    source_row = rows[0]
    require(source_row.get("classification") == "clear_residential_complex_or_building", "review no longer marks the target as a safe exact-ID exclusion")
    require(source_row.get("recommendedPickerAction") == "exclude_from_location_picker_candidate", "review action changed")

    repo_bytes = REPOSITORY_KT.read_bytes()
    prefs_bytes = PREFS_KT.read_bytes()
    require(sha(repo_bytes) == EXPECTED_CODE["LocalityRepository.kt"], "LocalityRepository behavior source changed; re-review before proceeding")
    require(sha(prefs_bytes) == EXPECTED_CODE["PrefsManager.kt"], "PrefsManager behavior source changed; re-review before proceeding")
    repository_text = repo_bytes.decode("utf-8")
    prefs_text = prefs_bytes.decode("utf-8")
    require('groupPickerLocalities(loadAll(context))' in repository_text, "picker loading path changed")
    require('selection.copy(localityId = null, name = null, kind = null)' in prefs_text, "retired saved-selection fallback changed")
    require('id.takeUnless { LocalityRepository.isRetired(context, it) }' in prefs_text, "retired locality ID lookup fallback changed")

    paths = {
        "neighborhoods.json": JSON_PATH,
        "neighborhoods.bin": BIN_PATH,
        "retired-localities.json": RETIRED_PATH,
        "final-exclusions.json": EXCLUSIONS_PATH,
    }
    before = {name: path.read_bytes() for name, path in paths.items()}
    before_hashes = {name: sha(data) for name, data in before.items()}
    for name, expected in EXPECTED_BEFORE.items():
        require(before_hashes[name] == expected, f"pinned {name} changed; stop for review")

    metadata = json.loads(before["neighborhoods.json"].decode("utf-8"))
    binary = before["neighborhoods.bin"]
    retired = json.loads(before["retired-localities.json"].decode("utf-8"))
    exclusion_manifest = json.loads(before["final-exclusions.json"].decode("utf-8"))
    features = metadata["features"]
    targets = [feature for feature in features if feature.get("id") == FEATURE_ID]
    require(len(targets) == 1, "live app catalog must contain exactly one target")
    target = targets[0]
    reviewed_feature_fields = ("id", "sourceId", "name", "kind", "parentName", "governorateId",
                               "delegationId", "hasBoundary", "bbox", "areaKm2", "pickerGroupId")
    require({key: target[key] for key in reviewed_feature_fields}
            == {key: source_row[key] for key in reviewed_feature_fields},
            "reviewed target identity/spatial fields differ from the live catalog")
    require(FEATURE_ID not in metadata["retiredLocalityIds"], "target already retired in neighborhoods metadata")
    require(FEATURE_ID not in retired["retiredLocalityIds"], "target already retired in saved-locality asset")
    require(set(metadata["retiredLocalityIds"]) == set(retired["retiredLocalityIds"]), "retired IDs disagree before change")
    require(not any(row.get("id") == FEATURE_ID for row in exclusion_manifest["exclusions"]), "target already exists in final exclusion manifest")
    group_members = [feature for feature in features if feature.get("pickerGroupId") == target.get("pickerGroupId")]
    require(len(group_members) == 1 and group_members[0]["id"] == FEATURE_ID, "target must be its own picker group singleton")

    geometry_hash = sha(binary[target["offset"]:target["offset"] + target["length"]])
    decision_path = PACKAGE / "decision.json"
    source_copy_path = PACKAGE / "source-review.json"
    decision = json.loads(decision_path.read_text(encoding="utf-8"))
    require(decision["sourceReview"]["sha256"] == SOURCE_REVIEW_SHA256, "decision source-review pin changed")
    exclusion_entry = {
        "id": FEATURE_ID,
        "expectedFeature": target,
        "expectedGeometrySha256": geometry_hash,
        "reason": "Exclude this exact tiny named residential parcel from selectable locality and GPS labels under the reviewed product scope; preserve its original geometry bytes and source evidence.",
        "evidence": [
            {"file": decision_path.relative_to(REPO / "scripts/neighborhoods").as_posix(), "sha256": sha(decision_path.read_bytes())},
            {"file": source_copy_path.relative_to(REPO / "scripts/neighborhoods").as_posix(), "sha256": sha(source_copy_path.read_bytes())},
        ],
    }
    staged_manifest = dict(exclusion_manifest)
    staged_manifest["exclusions"] = exclusion_manifest["exclusions"] + [exclusion_entry]
    require(staged_manifest["exclusions"][:-1] == exclusion_manifest["exclusions"], "existing exclusion entries/order changed")
    stage_manifest_path = REPO / "scripts/neighborhoods/.final-exclusions-abdelbaki-target-stage.json"
    # The live compiled catalog already carries earlier retired IDs, so run the
    # compiler helper with only this new candidate while persisting the full
    # cumulative manifest separately.
    write_candidate(stage_manifest_path, stable_json({"schemaVersion": 1, "exclusions": [exclusion_entry]}))
    try:
        generator = load_module(REPO / "scripts/generate_neighborhoods.py", "generate_neighborhoods_abdelbaki")
        staged_metadata, selection_report, exclusion_report = generator.apply_final_exclusions(
            metadata, binary, None, stage_manifest_path)
    finally:
        stage_manifest_path.unlink(missing_ok=True)
    require(exclusion_report["excludedIds"] == [FEATURE_ID], "exclusion helper returned a different feature set")
    require(exclusion_report["geometryBytesPreserved"] is True, "exclusion helper did not preserve packed geometry")
    require(selection_report is None, "unexpected selection report mutation")

    after_features = staged_metadata["features"]
    before_ids = [feature["id"] for feature in features]
    after_ids = [feature["id"] for feature in after_features]
    require(before_ids.count(FEATURE_ID) == 1 and FEATURE_ID not in after_ids, "target row was not excluded exactly once")
    require(len(after_features) == len(features) - 1, "feature count must change by exactly one")
    require([identifier for identifier in before_ids if identifier != FEATURE_ID] == after_ids, "other feature IDs/order changed")
    before_by_id = {feature["id"]: feature for feature in features}
    after_by_id = {feature["id"]: feature for feature in after_features}
    require(all(before_by_id[identifier] == after_by_id[identifier] for identifier in after_ids), "an unrelated feature record changed")
    require(binary == before["neighborhoods.bin"], "packed geometry bytes changed")
    require(staged_metadata["retiredLocalityIds"] == sorted(set(metadata["retiredLocalityIds"]) | {FEATURE_ID}), "metadata retired IDs changed beyond target")
    retired_after = dict(retired)
    retired_after["retiredLocalityIds"] = sorted(set(retired["retiredLocalityIds"]) | {FEATURE_ID})
    require(set(retired_after["retiredLocalityIds"]) == set(staged_metadata["retiredLocalityIds"]), "retired asset and metadata IDs diverge")
    require({key: value for key, value in retired_after.items() if key != "retiredLocalityIds"} == {key: value for key, value in retired.items() if key != "retiredLocalityIds"}, "retired saved-name/replacement records changed")

    # The picker has one entry per delegated source plus each feature group.
    # Counting the union captures the same representative-key merge behavior.
    governors_path = REPO / "android-app/app/src/main/assets/gouvernorats.json"
    governors = json.loads(governors_path.read_text(encoding="utf-8"))["gouvernorats"]
    delegation_keys = {f"delegation:{d['id']}" for governor in governors for d in governor.get("delegations", [])}
    picker_keys_before = delegation_keys | {f.get("pickerGroupId") or f["id"] for f in features}
    picker_keys_after = delegation_keys | {f.get("pickerGroupId") or f["id"] for f in after_features}
    require(len(picker_keys_before) - len(picker_keys_after) == 1, "static picker count delta must be exactly one")
    require(target["pickerGroupId"] not in delegation_keys, "target picker group unexpectedly aliases a delegation row")

    # The removed label's representative remains within the nested sector.
    locality_auto = load_module(REPO / "scripts/locality_automation/display_collisions.py", "display_collisions_abdelbaki")
    from shapely.geometry import Point
    target_geometry = locality_auto.unpack(target, binary, metadata["coordinateScale"])
    representative = Point(target["lng"], target["lat"])
    require(target_geometry.covers(representative), "target representative is not covered by its own polygon")
    enclosing = []
    for feature in features:
        if feature["id"] == FEATURE_ID or not feature.get("hasBoundary"):
            continue
        geometry = locality_auto.unpack(feature, binary, metadata["coordinateScale"])
        if geometry.covers(representative):
            enclosing.append(feature)
    sector = [feature for feature in enclosing if feature["id"] == "osm:relation:7169639"]
    require(len(sector) == 1, "expected containing Sidi Bouzid Ouest sector is not confirmed")
    require(locality_auto.unpack(sector[0], binary, metadata["coordinateScale"]).covers(target_geometry), "containing sector does not cover the target geometry")
    require(all(feature["id"] != FEATURE_ID for feature in after_features), "GPS label feature remains shipped")

    after = {
        "neighborhoods.json": stable_json(staged_metadata, compact=True),
        "neighborhoods.bin": binary,
        "retired-localities.json": stable_json(retired_after, compact=True),
        "final-exclusions.json": stable_json(staged_manifest),
    }
    candidate_dir = PACKAGE / "candidate"
    for name, data in after.items():
        write_candidate(candidate_dir / name, data)

    receipt = {
        "schemaVersion": 1,
        "package": "abdelbaki-hamdouni-picker-exclusion-20260925",
        "mode": "installed" if args.install else "staged",
        "featureId": FEATURE_ID,
        "sourceReviewSha256": SOURCE_REVIEW_SHA256,
        "expectedGeometrySha256": geometry_hash,
        "beforeSha256": before_hashes,
        "afterSha256": {name: sha(data) for name, data in after.items()},
        "featureCount": {"before": len(features), "after": len(after_features), "delta": -1},
        "retiredLocalityCount": {"before": len(retired["retiredLocalityIds"]), "after": len(retired_after["retiredLocalityIds"]), "delta": 1},
        "finalExclusionEntryCount": {"before": len(exclusion_manifest["exclusions"]), "after": len(staged_manifest["exclusions"]), "delta": 1},
        "allExistingFinalExclusionEntriesPreserved": staged_manifest["exclusions"][:-1] == exclusion_manifest["exclusions"],
        "staticPickerCount": {"before": len(picker_keys_before), "after": len(picker_keys_after), "delta": len(picker_keys_after) - len(picker_keys_before)},
        "gpsLabelImpact": {
            "targetRemovedFromFeatureCatalog": True,
            "representativePoint": {"lat": target["lat"], "lng": target["lng"]},
            "containingSector": {"id": sector[0]["id"], "name": sector[0]["name"], "delegationId": sector[0]["delegationId"]},
            "containingSectorCoversEntireTarget": True,
            "packedGeometryBytesChanged": False,
        },
        "savedSelectionFallback": {
            "verifiedBySource": {"LocalityRepository.kt": sha(repo_bytes), "PrefsManager.kt": sha(prefs_bytes)},
            "savedLocalityIdResolvesTo": None,
            "savedLocalityNameAndKind": None,
            "delegationIdPreserved": target["delegationId"],
            "fromGpsFlagPreserved": True,
            "timetableSourceChanged": False,
        },
        "otherFeatureRowsChanged": 0,
        "displayNameAssetChanged": False,
        "appAssetInstall": "not installed" if args.stage_only else "installed atomically",
    }
    receipt_path = PACKAGE / "receipt.json"
    if not args.install:
        receipt_path.write_bytes(stable_json(receipt))
        print(json.dumps(receipt, indent=2))
        return 0

    backups = PACKAGE / "backups"
    backups.mkdir(parents=True, exist_ok=True)
    for name, path in paths.items():
        backup = backups / name
        if backup.exists():
            require(sha(backup.read_bytes()) == before_hashes[name], f"existing backup hash differs for {name}")
        else:
            write_candidate(backup, before[name])
        require(sha(backup.read_bytes()) == before_hashes[name], f"backup verification failed for {name}")

    written: list[str] = []
    try:
        for name, path in paths.items():
            require(sha(path.read_bytes()) == before_hashes[name], f"live {name} changed during staging")
            fd, temp_name = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
            with os.fdopen(fd, "wb") as stream:
                stream.write(after[name])
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temp_name, path)
            written.append(name)
        for name, path in paths.items():
            require(sha(path.read_bytes()) == receipt["afterSha256"][name], f"post-write hash failed for {name}")
    except Exception:
        for name in reversed(written):
            path = paths[name]
            backup = backups / name
            temp_path = path.with_name(path.name + ".rollback.tmp")
            shutil.copyfile(backup, temp_path)
            os.replace(temp_path, path)
        raise
    receipt_path.write_bytes(stable_json(receipt))
    print(json.dumps(receipt, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
