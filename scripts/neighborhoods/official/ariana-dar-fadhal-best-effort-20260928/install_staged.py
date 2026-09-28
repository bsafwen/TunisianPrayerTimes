#!/usr/bin/env python3
"""Guarded one-target installer for the separately approved Ariana 125252 stage.

--check is read-only. --install is intentionally not run during preparation;
it requires the final, separately maintained root decision file to be GO.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import secrets
import shutil
import tempfile
from datetime import date, datetime


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
ASSETS = REPO / "android-app/app/src/main/assets"
TASK = Path(r"C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows")
EXEC = TASK / "work/isie-execution-20260926"
STAGE = EXEC / "ariana-125252-best-effort-stage-20260928-v2"
DECISION = STAGE / "final-install-decision.json"
OFFICIAL_PACKET = EXEC / "ariana-125252-isie-source-candidate-20260928-v2"
SEAM = EXEC / "independent-gates/ariana-125252-seam-install-review-20260928-v4"
PICKER_RECONCILIATION = EXEC / "independent-gates/ariana-125252-runtime-reconciliation-20260928-v4/report.json"
AOUINA = EXEC / "independent-gates/ariana-125252-aouina-page-localization-20260928-v1/report.json"
TARGET = "osm:relation:7114903"
POINT = "osm:node:1205571123"
EXPECTED_SOURCE = "isie-best-effort-imada-ariana-dar-fadhal-125252-20260928"
BASE_LATLNG = (36.865331, 10.242531)
EXPECTED_ADMIN = {"governorateId": 358, "delegationId": 532,
                  "governorateAr": "أريانة", "delegationAr": "سكرة",
                  "parentName": "معتمدية سكرة"}
EXPECTED_MINISTRY_GOVERNORATE_AR = "اريانة"
GO = "GO_BEST_EFFORT_INSTALL_WITH_DOCUMENTED_EDGE_EXCEPTIONS"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_sha(path: Path) -> str:
    return sha(path.read_bytes())


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def need(ok: bool, message: str) -> None:
    if not ok:
        raise ValueError(message)


def canonical(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def payload(row: dict, blob: bytes) -> bytes:
    start, length = row.get("offset"), row.get("length")
    need(type(start) is int and type(length) is int and start >= 8 and length > 0,
         f"Bad NPOL span for {row.get('id')}")
    need(start + length <= len(blob), f"NPOL span exceeds file for {row.get('id')}")
    return blob[start:start + length]


def validate_binary(meta: dict, blob: bytes, label: str) -> None:
    need(blob[:8] == b"NPOL\x00\x00\x00\x01", f"{label}: unexpected NPOL header")
    rows = [r for r in meta["features"] if r.get("hasBoundary") is True]
    country = meta["country"]
    spans = sorted((r["offset"], r["offset"] + r["length"], r["id"]) for r in rows)
    cursor = 8
    for start, end, identifier in spans:
        need(start == cursor and end > start, f"{label}: gap/overlap in packed spans at {identifier}")
        cursor = end
    need(country.get("offset") == cursor and type(country.get("length")) is int,
         f"{label}: country payload does not follow boundary payloads")
    need(country["length"] > 0 and country["offset"] + country["length"] == len(blob),
         f"{label}: invalid country payload span")


def inside_ring(lon: float, lat: float, ring: list[list[float]]) -> bool:
    inside = False
    for a, b in zip(ring, ring[1:]):
        x1, y1 = a[0], a[1]
        x2, y2 = b[0], b[1]
        if (y1 > lat) != (y2 > lat):
            x_cross = (x2 - x1) * (lat - y1) / (y2 - y1) + x1
            if lon < x_cross:
                inside = not inside
    return inside


def face_polygons(geometry: dict) -> list[list[list[float]]]:
    kind, coords = geometry["type"], geometry["coordinates"]
    if kind == "Polygon":
        return coords
    if kind == "MultiPolygon":
        return [ring for poly in coords for ring in poly]
    raise ValueError(f"Unsupported official face geometry: {kind}")


def point_to_segment_m(lat: float, lon: float, a: list[float], b: list[float]) -> float:
    scale_x = 111320.0 * math.cos(math.radians(lat))
    scale_y = 110574.0
    px, py = 0.0, 0.0
    ax, ay = (a[0] - lon) * scale_x, (a[1] - lat) * scale_y
    bx, by = (b[0] - lon) * scale_x, (b[1] - lat) * scale_y
    dx, dy = bx - ax, by - ay
    denom = dx * dx + dy * dy
    t = 0.0 if denom == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / denom))
    return math.hypot(ax + t * dx, ay + t * dy)


def official_face_check(lat: float, lon: float, face_path: Path) -> dict:
    collection = read_json(face_path)
    features = collection.get("features", [])
    need(len(features) == 1, "Pinned source packet must contain exactly one face")
    rings = face_polygons(features[0]["geometry"])
    exterior = rings[0]
    is_inside = inside_ring(lon, lat, exterior) and not any(
        inside_ring(lon, lat, ring) for ring in rings[1:])
    clearance = min(point_to_segment_m(lat, lon, a, b)
                    for a, b in zip(exterior, exterior[1:]))
    need(is_inside and clearance >= 100.0,
         f"Preserved representative is not safely inside official face (inside={is_inside}, clearance={clearance:.1f}m)")
    return {"insideOfficialFace": is_inside, "minimumApproxBoundaryClearanceM": round(clearance, 3),
            "method": "ray-cast in pinned WGS84 face; local metric point-to-segment clearance"}


def prayer_availability(governorates: dict, year: int, month: int) -> tuple[list[dict], dict]:
    import calendar
    import csv as csv_module
    days = calendar.monthrange(year, month)[1]
    available = []
    for gov in governorates["gouvernorats"]:
        for item in gov["delegations"]:
            lat, lon, identifier = item.get("lat", 0), item.get("lng", 0), item.get("id")
            if not identifier or not lat or not lon or not -90 <= lat <= 90 or not -180 <= lon <= 180:
                continue
            path = ASSETS / "csv" / str(identifier) / str(year) / f"{month:02d}.csv"
            try:
                with path.open("r", encoding="utf-8-sig", newline="") as stream:
                    rows = list(csv_module.reader(stream))
            except OSError:
                continue
            parsed = []
            for row in rows[1:]:
                if len(row) < 7:
                    parsed = []
                    break
                try:
                    day = int(row[0].strip())
                    minutes = []
                    valid_row = True
                    for value in row[1:7]:
                        hour_text, minute_text = value.strip().split(":", 1)
                        hour, minute = int(hour_text), int(minute_text)
                        if not (0 <= hour <= 23 and 0 <= minute <= 59):
                            valid_row = False
                            break
                        minutes.append(hour * 60 + minute)
                    if not valid_row or len(minutes) != 6:
                        parsed = []
                        break
                    parsed.append((day, minutes))
                except (ValueError, IndexError):
                    parsed = []
                    break
            if len(parsed) == days and all(
                    day == index + 1 and all(a < b for a, b in zip(times, times[1:]))
                    for index, (day, times) in enumerate(parsed)):
                available.append({"id": identifier, "lat": float(lat), "lng": float(lon),
                                  "nameAr": item.get("nomAr"), "csv": str(path)})
    return available, {"year": year, "month": month, "completeDelegationCount": len(available)}


def nearest_source(lat: float, lon: float, available: list[dict]) -> dict:
    def distance(item):
        p1, p2 = math.radians(lat), math.radians(item["lat"])
        dlat = p2 - p1
        dlon = math.radians(item["lng"] - lon)
        a = math.sin(dlat / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlon / 2) ** 2
        return 6371.0088 * 2 * math.atan2(math.sqrt(a), math.sqrt(max(0.0, 1 - a)))
    choice = min(available, key=lambda item: (distance(item), item["id"]))
    return {"delegationId": choice["id"], "nameAr": choice["nameAr"],
            "distanceKm": round(distance(choice), 6), "availabilityCsv": choice["csv"]}


def check_delta(base: dict, base_bin: bytes, staged: dict, staged_bin: bytes,
                overlay: dict, manifest: dict, source_face: Path) -> dict:
    before, after = base["features"], staged["features"]
    before_ids, after_ids = [r["id"] for r in before], [r["id"] for r in after]
    need(len(before_ids) == 3473 and before_ids == after_ids and len(set(before_ids)) == 3473,
         "Feature IDs/order/count changed")
    old_by_id, new_by_id = {r["id"]: r for r in before}, {r["id"]: r for r in after}
    need(TARGET in old_by_id and POINT in old_by_id, "Pinned Dar Fadhal rows missing")
    need(old_by_id[POINT] == new_by_id[POINT], "Separate Dar Fadhal point changed")
    target_old, target_new = old_by_id[TARGET], new_by_id[TARGET]
    policy = manifest["records"][0]
    expected = policy["expectedCurrent"]
    need(policy.get("representativePolicy") == "preserve_current_inside",
         "Target manifest must require preserve_current_inside")
    need((target_old.get("lat"), target_old.get("lng")) == BASE_LATLNG
         and (target_new.get("lat"), target_new.get("lng")) == BASE_LATLNG,
         "Target representative coordinate must remain exactly unchanged")
    need((expected.get("lat"), expected.get("lng")) == BASE_LATLNG,
         "Manifest base representative pin differs")
    allowed = {"sourceId", "bbox", "areaKm2", "offset", "length"}
    changed_target = {k for k in target_old.keys() | target_new.keys()
                      if target_old.get(k) != target_new.get(k)}
    need(changed_target and changed_target <= allowed,
         f"Unexpected target semantic change: {sorted(changed_target - allowed)}")
    need(target_new.get("sourceId") == EXPECTED_SOURCE, "Target uses unexpected source ID")
    for key, value in EXPECTED_ADMIN.items():
        field = {"governorateAr": None, "delegationAr": None}.get(key, key)
        if field is not None:
            need(target_new.get(field) == value, f"Target administrative field changed: {field}")
    need((target_new.get("governorateId"), target_new.get("delegationId"),
          target_new.get("parentName")) == (358, 532, "معتمدية سكرة"),
         "Target's Ministry parent or prayer delegation changed")
    for identifier in before_ids:
        old, new = old_by_id[identifier], new_by_id[identifier]
        if identifier == TARGET:
            continue
        if old.get("hasBoundary") is True:
            need({k: v for k, v in old.items() if k != "offset"} ==
                 {k: v for k, v in new.items() if k != "offset"},
                 f"Untargeted polygon semantics changed: {identifier}")
            need(payload(old, base_bin) == payload(new, staged_bin),
                 f"Untargeted polygon payload changed: {identifier}")
        else:
            need(old == new, f"Untargeted point row changed: {identifier}")
    need(set(base) == set(staged), "Top-level catalog keys changed")
    for key in base:
        if key in {"features", "sources", "conflicts", "country"}:
            continue
        need(base[key] == staged[key], f"Untargeted top-level semantics changed: {key}")
    need({k: v for k, v in base["country"].items() if k != "offset"} ==
         {k: v for k, v in staged["country"].items() if k != "offset"},
         "Country metadata changed")
    need(payload(base["country"], base_bin) == payload(staged["country"], staged_bin),
         "Country payload changed")
    old_sources, new_sources = base.get("sources", {}), staged.get("sources", {})
    need(set(new_sources) == set(old_sources) | {EXPECTED_SOURCE}, "Unexpected source add/remove")
    need(all(new_sources[k] == v for k, v in old_sources.items()), "Existing source record changed")
    old_conflicts = {canonical(c) for c in base["conflicts"]}
    new_conflicts = {canonical(c) for c in staged["conflicts"]}
    removed, added = old_conflicts - new_conflicts, new_conflicts - old_conflicts
    need(all(TARGET in json.loads(c).get("ids", []) for c in removed | added),
         "A conflict unrelated to the target changed")
    need(base.get("cells") == staged.get("cells"), "Cell index changed unexpectedly")
    need(len(removed) <= 1 and len(added) <= 4, "Conflict delta exceeds the pinned one-target scope")
    report = overlay
    need(report.get("status") == "passed" and report.get("audit", {}).get("passed") is True,
         "Overlay audit did not pass")
    preservation = report.get("preservation", {})
    need(all(preservation.get(k) is True for k in (
        "allUntouchedPolygonBytesIdentical", "allUntouchedPolygonFieldsPreservedExceptOffset",
        "allPointRowsIdentical", "countryPayloadIdentical")), "Overlay report lacks preservation proofs")
    need(report.get("counts", {}).get("features") == 3473, "Overlay report feature count differs")
    replacements = report.get("replacements", [])
    need(len(replacements) == 1 and replacements[0].get("id") == TARGET
         and replacements[0].get("officialCode") == "125252", "Overlay report scope differs")
    replacement = replacements[0]
    need(replacement.get("representativePoint") == {"lat": BASE_LATLNG[0], "lng": BASE_LATLNG[1]},
         "Overlay report does not preserve the representative point")
    face = official_face_check(BASE_LATLNG[0], BASE_LATLNG[1], source_face)
    return {"changedTargetFields": sorted(changed_target), "removedTargetConflicts": len(removed),
            "addedTargetConflicts": len(added), "untargetedPolygonPayloadsPreserved": True,
            "untargetedPointRowsPreserved": True, "cellsIdentical": True,
            "representativeLatLngBeforeAndAfter": [list(BASE_LATLNG), list(BASE_LATLNG)],
            "officialFaceCheck": face}


def preflight(require_go: bool) -> dict:
    need(DECISION.is_file(), f"Separate final root decision is missing: {DECISION}")
    decision = read_json(DECISION)
    need(decision.get("schemaVersion") == "ariana-125252-root-install-decision-v1",
         "Unexpected root decision schema")
    if require_go:
        need(decision.get("decision") == GO, "Final root decision is not explicit GO")
    need(decision.get("S") == 0 and decision.get("noRelease") is True,
         "Root decision must preserve S=0 and no-release")
    credits = decision.get("newCredits", {})
    need(credits.get("S", 0) == 0 and credits.get("P", 0) == 0 and credits.get("M", 0) == 0,
         "Root decision must not grant progress credit")
    pins = decision.get("hashPins", {})
    evidence = decision.get("evidenceSha256", {})
    expected_paths = {
        "baseJson": ASSETS / "neighborhoods.json", "baseBin": ASSETS / "neighborhoods.bin",
        "stagedJson": STAGE / "assets/neighborhoods.json", "stagedBin": STAGE / "assets/neighborhoods.bin",
        "sourceManifest": HERE / "geometry-overlay-manifest.json",
        "seamReviewJson": SEAM / "report.json", "seamReviewMd": SEAM / "report.md",
        "aouinaCoordinateReviewJson": AOUINA, "pickerRuntimeReconciliationJson": PICKER_RECONCILIATION,
        "governoratesJson": ASSETS / "gouvernorats.json",
    }
    actual = {key: file_sha(path) for key, path in expected_paths.items()}
    for key, value in actual.items():
        need(pins.get(key) == value, f"Root decision hash pin mismatch: {key}")
    evidence_paths = {
        "sourceManifest": OFFICIAL_PACKET / "manifest.json",
        "geometryOverlayReport": STAGE / "geometry-overlay-report.json",
        "catalogAudit": STAGE / "audit/neighborhood-reliability-audit.json",
        "packedPickerReplay": STAGE / "contact-strip-packed-picker-replay.json",
        "independentSeamInstallReview": SEAM / "report.json",
        "aouinaPageLocalization": AOUINA,
        "pickerRuntimeReconciliation": PICKER_RECONCILIATION,
    }
    evidence_actual = {key: file_sha(path) for key, path in evidence_paths.items()}
    for key, value in evidence_actual.items():
        need(evidence.get(key) == value, f"Root decision evidence hash mismatch: {key}")
    need(decision.get("baseSha256", {}).get("neighborhoods.json") == actual["baseJson"]
         and decision.get("baseSha256", {}).get("neighborhoods.bin") == actual["baseBin"],
         "Decision base hashes disagree with hashPins")
    need(decision.get("stagedSha256", {}).get("neighborhoods.json") == actual["stagedJson"]
         and decision.get("stagedSha256", {}).get("neighborhoods.bin") == actual["stagedBin"],
         "Decision staged hashes disagree with hashPins")
    exceptions = decision.get("acceptedPickerEdgeEffects", {})
    need(exceptions.get("noWinnerSeamProbes") == 23
         and exceptions.get("aouinaToDarFadhalProbeTransfers") == 3,
         "Decision must explicitly acknowledge the corrected 23 no-winner and 3 Aouina-winner transfers")
    need(exceptions.get("findWithAccuracyNoNeighborhoodAt5m") == 52
         and exceptions.get("findWithAccuracyNoNeighborhoodAt20m") == 64
         and exceptions.get("nullNeighborhoodBehavior") == "FALLBACK_TO_PRAYER_DELEGATION_DISPLAY",
         "Decision must acknowledge the corrected findWithAccuracy() fallback outcomes")
    if require_go:
        need(decision.get("acceptedLocalizationFallback") is True,
             "GO decision must explicitly accept the findWithAccuracy() fallback outcomes")
    need(decision.get("acknowledgedExceptions", {}).get("noWinnerProbeCount") == 23
         and decision.get("acknowledgedExceptions", {}).get("aouinaToDarFadhalTransfers") == 3,
         "Decision exception counts are missing or differ")
    need(not decision.get("targetRepresentativeCoordinateShift", {}).get("authorized", False),
         "Representative-coordinate shift must not be authorized")
    need(decision.get("acceptedRepresentativeCoordinateChange", {}).get("beforeLatLng", list(BASE_LATLNG))
         == decision.get("acceptedRepresentativeCoordinateChange", {}).get("afterLatLng", list(BASE_LATLNG)),
         "Decision permits an unnecessary representative-coordinate shift")

    app_manifest = read_json(HERE / "geometry-overlay-manifest.json")
    need(len(app_manifest.get("records", [])) == 1, "App manifest must target exactly one row")
    record = app_manifest["records"][0]
    need(record.get("id") == TARGET and record.get("officialCode") == "125252"
         and record.get("representativePolicy") == "preserve_current_inside",
         "App manifest target or coordinate policy differs")
    official_manifest = read_json(OFFICIAL_PACKET / "manifest.json")
    need(official_manifest.get("schemaVersion") == "ariana-isie-source-face-packet-manifest-v1",
         "Official source packet manifest schema differs")
    identity = official_manifest.get("officialIdentity", {})
    need((identity.get("officialCode"), identity.get("name"), identity.get("governorate"),
          identity.get("delegation")) == ("125252", "دار فضال", "أريانة", "سكرة"),
         "Official packet identity does not match the target")
    packet_manifest_pin = next((p for p in app_manifest.get("evidencePins", [])
                                if p.get("role") == "sourcePacketManifest"), None)
    need(packet_manifest_pin is not None and packet_manifest_pin.get("sha256") == evidence_actual["sourceManifest"],
         "Official packet manifest pin differs from the app manifest; keep source and app manifests distinct")
    packet_artifacts = official_manifest.get("artifactsSha256", {})
    source_face = OFFICIAL_PACKET / "candidate-source-face.geojson"
    need(packet_artifacts.get(source_face.name) == file_sha(source_face),
         "Official source face does not match its packet manifest")

    seam = read_json(SEAM / "report.json")
    need(seam.get("schema_version") == "ariana-125252-seam-install-review-v4"
         and seam.get("decision") == GO and seam.get("credits", {}).get("SGranted") is False,
         "Pinned seam review is not the expected S=0 best-effort review")
    aouina = read_json(AOUINA)
    need(isinstance(aouina, dict), "Pinned Aouina coordinate review is unreadable")
    overlay = read_json(STAGE / "geometry-overlay-report.json")
    need(overlay.get("manifest", {}).get("sha256") == actual["sourceManifest"],
         "Overlay report does not pin the app source manifest")
    need(overlay.get("baseOverlayPins", {}).get("neighborhoodsJson") == actual["baseJson"]
         and overlay.get("baseOverlayPins", {}).get("neighborhoodsBin") == actual["baseBin"],
         "Overlay report base pins differ")
    need(overlay.get("stagedAssetSha256", {}).get("neighborhoodsJson") == actual["stagedJson"]
         and overlay.get("stagedAssetSha256", {}).get("neighborhoodsBin") == actual["stagedBin"],
         "Overlay report staged pins differ")
    audit = read_json(STAGE / "audit/neighborhood-reliability-audit.json")
    replay = read_json(STAGE / "contact-strip-packed-picker-replay.json")
    replay_summary = replay.get("replaySummary", {})
    need(replay.get("status") == "completed_with_saved_replay_discrepancies"
         and replay_summary.get("probesEvaluated") == 66
         and replay_summary.get("changedWinnerProbeCount") == 35
         and replay_summary.get("noWinnerProbeCount") == 23
         and replay_summary.get("afterPairSuppressionProbeCount") == 27
         and replay_summary.get("replayErrors") == 0,
         "Packed picker replay does not match the corrected exact 66-probe result")
    transfers = replay_summary.get("aouinaWinnerToDarFadhalTransfers", [])
    need(len(transfers) == 3, "Packed picker replay does not show exactly three raw Aouina-winner transfers")
    accuracy = replay.get("findWithAccuracyAssessment", {}).get("radii", {})
    five, twenty = accuracy.get("5", {}), accuracy.get("20", {})
    need(five.get("beforeNoWinnerProbeCount") == 58
         and five.get("afterNoWinnerProbeCount") == 52
         and five.get("winnerChangeKinds") == {
             "noNeighborhoodToNeighborhood": 7,
             "neighborhoodToNoNeighborhood": 1,
             "neighborhoodToDifferentNeighborhood": 1}
         and twenty.get("beforeNoWinnerProbeCount") == 64
         and twenty.get("afterNoWinnerProbeCount") == 64
         and twenty.get("changedWinnerProbeCount") == 0,
         "findWithAccuracy() sample outcomes differ from the corrected 5m/20m replay")
    need(any(p.get("beforeWinner") == "osm:relation:7201235" and p.get("afterWinner") is None
             for p in five.get("changedWinnerProbes", [])),
         "The 5m Eastern-to-null delegation-display fallback is not documented")
    need(five.get("preservedTargetRepresentative", {}).get("result", {}).get("winner", {}).get("id") == TARGET
         and twenty.get("preservedTargetRepresentative", {}).get("result", {}).get("winner", {}).get("id") == TARGET,
         "Target representative does not resolve to Dar Fadhal at both accuracy radii")
    need(audit.get("geometryValidation", {}).get("invalidPolygonCount") == 0,
         "Catalog audit reports invalid polygon payloads")

    base_json = ASSETS / "neighborhoods.json"
    base_bin = ASSETS / "neighborhoods.bin"
    staged_json = STAGE / "assets/neighborhoods.json"
    staged_bin = STAGE / "assets/neighborhoods.bin"
    base, base_raw, base_blob = read_json(base_json), base_json.read_bytes(), base_bin.read_bytes()
    staged, staged_raw, staged_blob = read_json(staged_json), staged_json.read_bytes(), staged_bin.read_bytes()
    validate_binary(base, base_blob, "live base")
    validate_binary(staged, staged_blob, "staged")
    delta = check_delta(base, base_blob, staged, staged_blob, overlay, app_manifest, source_face)

    govs = read_json(ASSETS / "gouvernorats.json")
    gov = next((g for g in govs.get("gouvernorats", []) if g.get("id") == 358), None)
    need(gov is not None and gov.get("nomAr") == EXPECTED_MINISTRY_GOVERNORATE_AR,
         "Target Ministry governorate parent does not match the pinned Ministry spelling")
    delegation = next((d for d in gov["delegations"] if d.get("id") == 532), None)
    need(delegation is not None and delegation.get("nomAr") == EXPECTED_ADMIN["delegationAr"],
         "Target Ministry delegation parent does not match")
    decision_date = date.fromisoformat(decision["date"])
    today = date.today()
    need((decision_date.year, decision_date.month) == (today.year, today.month),
         "Root decision month differs from current prayer-data availability month")
    available, availability_summary = prayer_availability(govs, decision_date.year, decision_date.month)
    need(available, "No complete monthly prayer sources were found")
    old_nearest = nearest_source(BASE_LATLNG[0], BASE_LATLNG[1], available)
    new_nearest = nearest_source(staged["features"][before_index(staged["features"], TARGET)]["lat"],
                                 staged["features"][before_index(staged["features"], TARGET)]["lng"], available)
    need(old_nearest["delegationId"] == new_nearest["delegationId"] == 532,
         "Preserved representative must resolve to prayer delegation 532")
    return {"status": "passed", "decision": decision.get("decision"), "decisionSha256": file_sha(DECISION),
            "targetId": TARGET, "officialCode": "125252", "hashPins": actual,
            "evidenceSha256": evidence_actual, "sourceFaceManifestSha256": evidence_actual["sourceManifest"],
            "appOverlayManifestSha256": actual["sourceManifest"], "delta": delta,
            "administrativeParent": {"governorateId": 358, "governorateAr": gov["nomAr"],
                                      "delegationId": 532, "delegationAr": delegation["nomAr"],
                                      "parentName": "معتمدية سكرة"},
            "representative": {"beforeLatLng": list(BASE_LATLNG),
                               "afterLatLng": [staged["features"][before_index(staged["features"], TARGET)]["lat"],
                                               staged["features"][before_index(staged["features"], TARGET)]["lng"]],
                               "prayerSourceBefore": old_nearest, "prayerSourceAfter": new_nearest,
                               "availability": availability_summary},
            "pickerExceptions": {"rawFindNoWinnerProbes": 23, "rawFindAouinaTransfers": len(transfers),
                                 "findWithAccuracy5mBeforeNull": 58, "findWithAccuracy5mAfterNull": 52,
                                 "findWithAccuracy20mBeforeNull": 64, "findWithAccuracy20mAfterNull": 64,
                                 "fiveMeterEasternFallbacks": 1, "S": 0},
            "mutations": {"checkReadOnly": True, "appAssetsWritten": False, "progressPublished": False}}


def before_index(rows: list[dict], identifier: str) -> int:
    for index, row in enumerate(rows):
        if row.get("id") == identifier:
            return index
    raise ValueError(f"Missing pinned row {identifier}")


def atomic_replace(path: Path, data: bytes) -> None:
    fd, name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    temp = Path(name)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
    finally:
        if temp.exists():
            temp.unlink()


def write_receipt(path: Path, receipt: dict) -> None:
    data = (json.dumps(receipt, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    atomic_replace(path, data)


def install() -> dict:
    check = preflight(require_go=True)
    paths = {"neighborhoods.json": ASSETS / "neighborhoods.json",
             "neighborhoods.bin": ASSETS / "neighborhoods.bin"}
    staged = {name: (STAGE / "assets" / name).read_bytes() for name in paths}
    base_hashes = {name: check["hashPins"]["baseJson" if name.endswith(".json") else "baseBin"] for name in paths}
    staged_hashes = {name: check["hashPins"]["stagedJson" if name.endswith(".json") else "stagedBin"] for name in paths}
    lock = ASSETS / ".ariana-125252-install.lock"
    try:
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        os.write(fd, f"pid={os.getpid()}\n".encode("ascii"))
        os.close(fd)
    except FileExistsError as exc:
        raise ValueError(f"Another 125252 install transaction holds {lock}") from exc
    tx = HERE / "install-transactions" / ("125252-" + datetime.now().strftime("%Y%m%dT%H%M%S")
                                          + "-" + secrets.token_hex(4))
    receipt_path = tx / "install-receipt.json"
    replaced: list[str] = []
    temps: dict[str, Path] = {}
    try:
        current = {name: file_sha(path) for name, path in paths.items()}
        need(current == base_hashes, f"Live assets no longer match pinned base: {current}")
        need(file_sha(DECISION) == check["decisionSha256"], "Root decision changed during preflight")
        tx.mkdir(parents=True, exist_ok=False)
        backup_dir = tx / "backups"
        backup_dir.mkdir()
        for name, path in paths.items():
            shutil.copy2(path, backup_dir / name)
            need(file_sha(backup_dir / name) == base_hashes[name], f"Backup verification failed: {name}")
        receipt = {"schemaVersion": "ariana-125252-install-receipt-v1", "status": "PREPARED",
                   "targetId": TARGET, "officialCode": "125252", "preparedAtLocal": datetime.now().astimezone().isoformat(),
                   "decisionFile": str(DECISION), "decisionSha256": check["decisionSha256"],
                   "inputPins": check["hashPins"], "evidenceSha256": check["evidenceSha256"],
                   "representative": check["representative"], "administrativeParent": check["administrativeParent"],
                   "pickerExceptions": check["pickerExceptions"],
                   "backupSha256": base_hashes, "stagedSha256": staged_hashes,
                   "assetPaths": {name: str(path) for name, path in paths.items()}, "phase": "backups_verified"}
        write_receipt(receipt_path, receipt)
        for name, path in paths.items():
            need(file_sha(path) == base_hashes[name], f"Live asset changed before replacement: {name}")
        need(file_sha(DECISION) == check["decisionSha256"], "Root decision changed before replacement")
        for name, path in paths.items():
            fd, tmpname = tempfile.mkstemp(prefix=f".{path.name}.ariana125252.", suffix=".tmp", dir=path.parent)
            with os.fdopen(fd, "wb") as stream:
                stream.write(staged[name]); stream.flush(); os.fsync(stream.fileno())
            temps[name] = Path(tmpname)
            need(file_sha(temps[name]) == staged_hashes[name], f"Prepared temporary hash differs: {name}")
        for name, path in paths.items():
            need(file_sha(path) == base_hashes[name], f"Live asset changed immediately before replace: {name}")
            os.replace(temps[name], path)
            replaced.append(name)
            receipt["phase"] = "replaced:" + name
            write_receipt(receipt_path, receipt)
        installed = {name: file_sha(path) for name, path in paths.items()}
        need(installed == staged_hashes, f"Post-install asset verification failed: {installed}")
        receipt["status"] = "INSTALLED"
        receipt["phase"] = "verified"
        receipt["installedAtLocal"] = datetime.now().astimezone().isoformat()
        receipt["installedSha256"] = installed
        write_receipt(receipt_path, receipt)
        return {"status": "INSTALLED", "receipt": str(receipt_path), "assetSha256": installed}
    except Exception as original:
        rollback_errors = []
        if tx.exists():
            for name in reversed(list(paths)):
                path = paths[name]
                current_sha = file_sha(path)
                if current_sha == staged_hashes[name]:
                    try:
                        atomic_replace(path, (tx / "backups" / name).read_bytes())
                    except Exception as rollback_error:
                        rollback_errors.append(f"{name}: {rollback_error}")
                elif current_sha != base_hashes[name]:
                    rollback_errors.append(f"{name}: unexpected concurrent hash {current_sha}; not overwritten")
            if receipt_path.exists():
                receipt = read_json(receipt_path)
                receipt["status"] = "ROLLBACK_FAILED" if rollback_errors else "ROLLED_BACK"
                receipt["phase"] = "rollback_complete" if not rollback_errors else "rollback_incomplete"
                receipt["failure"] = str(original)
                receipt["rollbackErrors"] = rollback_errors
                receipt["postRollbackSha256"] = {name: file_sha(path) for name, path in paths.items()}
                write_receipt(receipt_path, receipt)
        if rollback_errors:
            raise RuntimeError(f"Install failed and rollback needs attention; receipt={receipt_path}; {rollback_errors}") from original
        raise
    finally:
        for temp in temps.values():
            try:
                temp.unlink()
            except FileNotFoundError:
                pass
        try:
            lock.unlink()
        except FileNotFoundError:
            pass


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--check", action="store_true", help="Run read-only pinned preflight")
    modes.add_argument("--install", action="store_true", help="Install only after explicit root GO decision")
    parser.add_argument("--decision-file", type=Path, help="Alternate decision file for --check only (for example a pinned HOLD draft)")
    args = parser.parse_args()
    global DECISION
    if args.install:
        need(args.decision_file is None, "--decision-file is supported only with --check")
    elif args.decision_file is not None:
        DECISION = args.decision_file.resolve()
    result = preflight(require_go=args.install)
    if args.install:
        result["installation"] = install()
    else:
        result["installation"] = "NOT_RUN"
        result["mutations"]["checkReadOnly"] = True
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
