"""Compare a whole native-source hypothesis with live GPS behavior, without installation.

The original exhaustive and independent formulas remain unchanged. All probes,
all four modes, every disagreement and before/after result are retained.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys

from pyproj import Transformer
from shapely import from_wkb
from shapely.ops import transform

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay
from scripts.locality_automation.run_solo_source_gps_audit import numeric_exhaustive, nearest_source, protected_bodies
from scripts.locality_automation.work_window_guard import parse_utc
from scripts.locality_automation.durable_observation_journal import persist_probe


def preserve_catalog(before, after, before_blob, after_blob, changed_id):
    """Check every unrelated physical body and complete metadata identity."""
    if len(before.features) != 3473 or len(before.boundaries) != 2571:
        raise ValueError("Unexpected complete live catalogue")
    if [r["id"] for r in before.features] != [r["id"] for r in after.features]:
        raise ValueError("Catalogue order or identity differs")
    if len(after.boundaries) != len(before.boundaries):
        raise ValueError("Catalogue body count differs")
    proofs = []
    for old in before.features:
        new = after.by_id[old["id"]]
        if old["id"] == changed_id:
            continue
        old_fields = {k: v for k, v in old.items() if k not in ("offset", "length")}
        new_fields = {k: v for k, v in new.items() if k not in ("offset", "length")}
        if old_fields != new_fields:
            raise ValueError("Unrelated complete metadata changed: " + old["id"])
        a = before_blob[old["offset"]:old["offset"] + old["length"]] if old["hasBoundary"] else b""
        b = after_blob[new["offset"]:new["offset"] + new["length"]] if new["hasBoundary"] else b""
        if a != b:
            raise ValueError("Unrelated physical whole body changed: " + old["id"])
        proofs.append({"id": old["id"], "packedSliceSha256": hashlib.sha256(a).hexdigest()})
    return proofs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    if (datetime.now(timezone.utc) >= parse_utc(ctl["safeSourceQaStartUtc"])
            or spec["code"] not in ctl["approvedReconciliationPool"]
            or args.output.exists() or spec.get("credit") != 0):
        raise ValueError("Fresh finite root shadow phase required")
    check_tree(spec)
    benchmark = read(checked(spec["durableJournalBenchmark"]))
    if (benchmark["status"] != "VERIFIED_COUNTERBALANCED_DURABLE_PROBE_BENCHMARK_NO_AUTOMATIC_ADOPTION"
            or benchmark["byteIdenticalEveryTrial"] is not True
            or benchmark["classificationFactsAndOrderIdentical"] is not True
            or benchmark["allRecordsDurableBeforeClassification"] is not True):
        raise ValueError("Whole-probe durable writing has not been verified")
    proposal = read(checked(spec["proposal"]))
    staged = read(checked(spec["staged"]))
    if (proposal["status"] != "UNACCEPTED_ROOT_ISOLATED_HYPOTHESIS_NO_INSTALLATION"
            or proposal["credit"] != 0 or len(proposal["patches"]) != 1
            or staged["status"] != "STAGED_REQUIRES_GEOGRAPHIC_AND_GPS_REVIEW"):
        raise ValueError("Shadow hypothesis is not explicitly isolated and unaccepted")
    patch = proposal["patches"][0]
    ident, code = patch["id"], spec["code"]
    if patch["officialCode"] != code or staged["changedIds"] != [ident]:
        raise ValueError("Finite changed identity differs")
    before = PackedGpsReplay(checked(spec["beforeMetadata"]), checked(spec["beforeBinary"]))
    after = PackedGpsReplay(checked(staged["stagedMetadata"]), checked(staged["stagedBinary"]))
    old_blob, new_blob = checked(spec["beforeBinary"]).read_bytes(), checked(staged["stagedBinary"]).read_bytes()
    preserved = preserve_catalog(before, after, old_blob, new_blob, ident)
    lineage = read(checked(spec["protectedLineage"]))
    if any(row["id"] == ident for row in lineage["bindings"]):
        raise ValueError("Shadow target is protected")
    protection = protected_bodies(after, new_blob, lineage)
    loader = importlib.util.spec_from_file_location("unchanged_shadow_oracle", checked(spec["oracle"]))
    original = importlib.util.module_from_spec(loader)
    loader.loader.exec_module(original)
    oracles = {"before": original.IndependentCurrentOracle(before), "after": original.IndependentCurrentOracle(after)}
    exhaustive = numeric_exhaustive(spec["numericOriginal"])
    raw = from_wkb(checked(patch["rawSourceGeometry"]).read_bytes())
    expected = from_wkb(checked(patch["geometry"]).read_bytes())
    old, new = before.geometry(ident), after.geometry(ident)
    if not raw.is_valid or not new.is_valid or not expected.equals(new):
        raise ValueError("Shadow does not contain the complete source-grid body")
    to_m = Transformer.from_crs(4326, 32632, always_xy=True).transform
    to_ll = Transformer.from_crs(32632, 4326, always_xy=True).transform
    raw_m, old_m, new_m = (transform(to_m, body) for body in (raw, old, new))
    incidents, peers = [], []
    bounds = old.union(new).union(raw).bounds
    for row in after.features:
        if not row["hasBoundary"] or row["id"] == ident:
            continue
        a, b, c, d = row["bbox"]
        if c < bounds[0] or a > bounds[2] or d < bounds[1] or b > bounds[3]:
            continue
        body = after.geometry(row["id"])
        if not body.is_valid:
            raise ValueError("Invalid neighboring body")
        projected = transform(to_m, body)
        areas = {}
        for label, candidate in (("raw", raw_m), ("before", old_m), ("after", new_m)):
            overlap = candidate.intersection(projected)
            areas[label] = overlap.area
            if not overlap.is_empty:
                incidents.append((overlap, label + "-peer-overlap-supporting-only"))
        peers.append({"id": row["id"], "intersectionSquareMeters": areas})
    points, seen = [], set()
    for label, metric, geographic, replay in (("raw", raw_m, raw, after), ("shadow-grid", new_m, new, after), ("live", old_m, old, before)):
        for point in original.source_probes(metric, to_ll, geographic, replay.by_id[ident], incidents):
            key = point["lat"], point["lng"]
            if key not in seen:
                points.append({**point, "sourceClass": label + "-" + point["sourceClass"]})
                seen.add(key)
    params = read(checked(spec["prayerParams"]))
    eligible = [d for g in read(checked(spec["governorates"]))["gouvernorats"] for d in g["delegations"] if str(d["id"]) in params]
    if len(eligible) != 258 or any(d["id"] == 495 for d in eligible):
        raise ValueError("Original prayer-source universe differs")
    for i, point in enumerate(points):
        point["id"] = point["name"] = code + "-shadow-" + str(i).zfill(6)
        point["expectedSourceId"] = nearest_source(eligible, point["lat"], point["lng"])
    args.output.mkdir()
    probes = write_new(args.output / "probes.json", {"probes": points})
    requests = write_new(args.output / "actual-jvm-requests.json", [{k: p[k] for k in ("name", "lat", "lng", "expectedSourceId")} for p in points])
    gate = write_new(args.output / "input-gate.json", {"manifest": pin(args.manifest), "protected323": protection,
        "unrelatedBodies": preserved, "probes": probes, "requests": requests,
        "independentQaPassed": False, "sourceScopeAccepted": False, "credit": 0})
    started = datetime.now(timezone.utc).isoformat()
    failures, disagreements, changes, counts = [], [], [], Counter()
    journal_path = args.output / "observations.jsonl"
    before_journal = args.output / "before-observations.jsonl"
    with journal_path.open("x", encoding="utf-8") as out, before_journal.open("x", encoding="utf-8") as prior:
        for i, point in enumerate(points):
            grouped = {"before": [], "after": []}
            outcomes = {}
            for ordinal, mode in enumerate((None, 5, 20, 50)):
                for label, replay, stream in (("before", before, prior), ("after", after, out)):
                    indexed = replay.find(point["lat"], point["lng"], mode)
                    winner = exhaustive(replay, point["lat"], point["lng"], mode)
                    predicted, near = oracles[label].find(point["lat"], point["lng"], mode)
                    record = {"probeOrdinal": i, "modeOrdinal": ordinal, "id": point["id"],
                        "lat": point["lat"], "lng": point["lng"], "accuracyMeters": mode,
                        "indexed": indexed, "exhaustiveWinnerId": winner, "oracle": predicted,
                        "oracleNearExactEdge": near, "expectedSourceId": point["expectedSourceId"], "sourceClass": point["sourceClass"]}
                    grouped[label].append(record)
                    outcomes[(label, ordinal)] = indexed
            for label, stream in (("before", prior), ("after", out)):
                def classify(record):
                    indexed, winner, predicted = record["indexed"], record["exhaustiveWinnerId"], record["oracle"]
                    counts[label + "Comparisons"] += 1
                    if indexed["winnerId"] != winner:
                        failures.append({"catalog": label, "probeOrdinal": record["probeOrdinal"], "modeOrdinal": record["modeOrdinal"]})
                    if any(indexed[k] != predicted[k] for k in ("winnerId", "candidateIds", "qualifiedIds", "suppressedIds")):
                        disagreements.append({"catalog": label, "probeOrdinal": record["probeOrdinal"], "modeOrdinal": record["modeOrdinal"], "nearExactEdge": record["oracleNearExactEdge"]})
                persist_probe(stream, grouped[label], classify)
            for ordinal in range(4):
                if outcomes[("before", ordinal)] != outcomes[("after", ordinal)]:
                    changes.append({"probeOrdinal": i, "modeOrdinal": ordinal, "before": outcomes[("before", ordinal)], "after": outcomes[("after", ordinal)]})
            if (i + 1) % 500 == 0:
                print(json.dumps({"code": code, "probesCompleted": i + 1, "probeCount": len(points), "credit": 0}), flush=True)
    check_tree(spec)
    result = {"status": "SOLO_SHADOW_WHOLE_SOURCE_GPS_DIAGNOSTIC_NO_INSTALLATION", "officialCode": code, "id": ident,
        "sourceActor": "/root", "technicalAuditActor": "/root", "independentQaPassed": False,
        "sourceScopeAccepted": False, "credit": 0, "ledgerWrites": False, "liveAssetsChanged": False,
        "probeCount": len(points), "counts": dict(counts), "indexedExhaustiveFailures": failures,
        "independentFormulaDisagreements": disagreements, "behaviorChangeCount": len(changes),
        "behaviorChanges": write_new(args.output / "before-after-changes.json", changes), "currentPeers": peers,
        "inputGate": gate, "probes": probes, "actualJvmRequests": requests, "journal": pin(journal_path),
        "beforeJournal": pin(before_journal), "startedAtUtc": started, "finishedAtUtc": datetime.now(timezone.utc).isoformat(),
        "journalStrategy": "FOUR_ORIGINAL_MODE_RECORDS_DURABLE_BEFORE_ANY_CLASSIFICATION",
        "durableJournalBenchmark": spec["durableJournalBenchmark"], "durabilityBarriers": len(points) * 2,
        "payloadPid": os.getpid(), "actualJvmExecuted": False, "fullConsumerExecuted": False,
        "limits": ["Unaccepted isolated whole native-source hypothesis; current accepted map and assets remain pinned.",
                   "Declared incident conflicts remain explicit; neighboring held faces were not clipped or accepted.",
                   "Solo numeric checks do not provide independent source QA, legal certification or installation authority."]}
    report = write_new(args.output / "report.json", result)
    print(json.dumps({"report": report, "probes": len(points), "comparisons": dict(counts),
        "failures": len(failures), "oracleDisagreements": len(disagreements), "behaviorChanges": len(changes), "credit": 0}), flush=True)


if __name__ == "__main__":
    main()
