"""Verify and optionally install the provisional Boumerdes/Ghnada seam clip."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from pathlib import Path


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
ASSETS = REPO / "android-app/app/src/main/assets"
TARGET = "osm:relation:7152245"
CLIPPER = "osm:relation:7114531"
DERIVED_SOURCE = "osm-isie-reviewed-clip-boumerdes-ghnada-seam-20260925"
BASE = {
    "neighborhoods.json": "5bed2d5a3a7f8aef33421e451e47f36a7f656626f536693e6037715a758b64d0",
    "neighborhoods.bin": "2083f237ab9f4a59fc07f003812e283acab869d7598d1895f5436de469feb38e",
}
STAGED = {
    "neighborhoods.json": "fcb79512942c42868ce7e1485b8f57b007f63f9896f413015764eae866db563f",
    "neighborhoods.bin": "3e363798e3ca1f9f6606403198a478ba4909a08dd3ac3cc2d583c7319e94b460",
}
MANIFEST_SHA = "b80c59cc6910b765f1ab25fe36444a0c3bd063dd9d25ea4e6420fb0a8a74b9f3"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def payload(row: dict, binary: bytes) -> bytes:
    offset, length = row["offset"], row["length"]
    if not isinstance(offset, int) or not isinstance(length, int) or offset < 8 or length <= 0:
        raise ValueError(f"Invalid packed range: {row.get('id')}")
    return binary[offset:offset + length]


def verify(base_raw: dict[str, bytes], staged_raw: dict[str, bytes], report: dict) -> dict:
    before = json.loads(base_raw["neighborhoods.json"])
    after = json.loads(staged_raw["neighborhoods.json"])
    old_bin, new_bin = base_raw["neighborhoods.bin"], staged_raw["neighborhoods.bin"]
    old_rows, new_rows = before["features"], after["features"]
    ids = [row["id"] for row in old_rows]
    if ids != [row["id"] for row in new_rows] or len(ids) != len(set(ids)):
        raise ValueError("Selectable identities changed")
    changed = []
    allowed = {"sourceId", "lat", "lng", "delegationId", "bbox", "areaKm2", "offset", "length"}
    for old, new in zip(old_rows, new_rows):
        difference = {key for key in old.keys() | new.keys() if old.get(key) != new.get(key)}
        if old["id"] == TARGET:
            if not difference or not difference.issubset(allowed) or new["sourceId"] != DERIVED_SOURCE:
                raise ValueError(f"Unexpected target delta: {sorted(difference)}")
            if payload(old, old_bin) == payload(new, new_bin):
                raise ValueError("Target polygon did not change")
            changed.append(old["id"])
        else:
            if difference - {"offset"}:
                raise ValueError(f"Unrelated metadata changed: {old['id']} {sorted(difference)}")
            if old.get("hasBoundary") and payload(old, old_bin) != payload(new, new_bin):
                raise ValueError(f"Unrelated polygon changed: {old['id']}")
    if changed != [TARGET] or len(old_rows) != 3473 or len(new_rows) != 3473:
        raise ValueError("Wrong changed-row or inventory count")
    if set(after["sources"]) != set(before["sources"]) | {DERIVED_SOURCE}:
        raise ValueError("Unexpected source-record change")
    if any(before["sources"][key] != after["sources"][key] for key in before["sources"]):
        raise ValueError("Existing source record changed")
    if before["retiredLocalityIds"] != after["retiredLocalityIds"]:
        raise ValueError("Retired identities changed")
    if payload(before["country"], old_bin) != payload(after["country"], new_bin):
        raise ValueError("Country polygon changed")
    if report.get("status") != "passed" or report.get("audit", {}).get("passed") is not True:
        raise ValueError("Generic overlay audit did not pass")
    preserved = report.get("preservation", {})
    if not all(preserved.get(key) is True for key in (
            "allUntouchedPolygonBytesIdentical", "allUntouchedPolygonFieldsPreservedExceptOffset",
            "allPointRowsIdentical", "countryPayloadIdentical")):
        raise ValueError("Generic overlay preservation checks failed")
    clips = report.get("clips", [])
    if len(clips) != 1 or clips[0].get("id") != TARGET or clips[0].get("clipById") != CLIPPER:
        raise ValueError("Staged report is not the pinned seam operation")
    clip = clips[0]
    if (not 0.65 < clip["removedAreaKm2"] < 0.70
            or not 0.001 < clip["remainingOverlapKm2"] < 0.006
            or clip.get("formerOverlapGpsWinnerId") != CLIPPER
            or clip.get("preservedOverlapGpsConflictSuppressed") is not True):
        raise ValueError("Area or GPS behavior differs from reviewed candidate")
    return {"status": "passed", "changedSelectableRows": changed,
            "removedKm2": clip["removedAreaKm2"], "remainingOverlapKm2": clip["remainingOverlapKm2"],
            "unchangedPolygonPayloads": preserved["untouchedPolygonPayloads"],
            "gpsFormerWinnerId": clip["formerOverlapGpsWinnerId"],
            "preservedOverlapGpsConflictSuppressed": clip["preservedOverlapGpsConflictSuppressed"]}


def replace(path: Path, raw: bytes) -> None:
    temp = None
    try:
        handle, name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
        temp = Path(name)
        with os.fdopen(handle, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
    finally:
        if temp is not None and temp.exists():
            temp.unlink()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage-dir", type=Path, required=True)
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()
    stage = args.stage_dir.resolve()
    if stage == REPO or stage.is_relative_to(REPO):
        raise ValueError("Stage must be outside the repository")
    if sha((HERE / "geometry-overlay-manifest.json").read_bytes()) != MANIFEST_SHA:
        raise ValueError("Seam manifest changed")
    base_raw = {name: (ASSETS / name).read_bytes() for name in BASE}
    staged_raw = {name: (stage / "assets" / name).read_bytes() for name in STAGED}
    if any(sha(base_raw[name]) != expected for name, expected in BASE.items()):
        raise ValueError("Live base changed; do not install without restaging")
    if any(sha(staged_raw[name]) != expected for name, expected in STAGED.items()):
        raise ValueError("Staged bytes differ from reviewed output")
    report = json.loads((stage / "geometry-overlay-report.json").read_text(encoding="utf-8"))
    result = verify(base_raw, staged_raw, report)
    if args.install:
        backup = stage / "before-install"
        backup.mkdir(exist_ok=True)
        for name, raw in base_raw.items():
            (backup / name).write_bytes(raw)
        try:
            for name in STAGED:
                replace(ASSETS / name, staged_raw[name])
        except Exception:
            for name, raw in base_raw.items():
                replace(ASSETS / name, raw)
            raise
        if any(sha((ASSETS / name).read_bytes()) != expected for name, expected in STAGED.items()):
            for name, raw in base_raw.items():
                replace(ASSETS / name, raw)
            raise ValueError("Installed asset readback mismatch; restored original assets")
        result["status"] = "installed"
        result["baseAssetSha256"] = BASE
        result["installedAssetSha256"] = STAGED
        result["overlayReportSha256"] = sha((stage / "geometry-overlay-report.json").read_bytes())
        (stage / "installation-receipt.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
