"""Plan finite artifact work before opening large source/QA packages.

Current acceptance wins over stale indexes. Source authorship is observed,
never reassigned. A preparation, index status or source-only face gives no
independent QA, GPS, full-consumer or geographic acceptance.
"""
from __future__ import annotations
import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from scripts.locality_automation.review_candidate_queue import _Snapshot, _report_epoch, _json


def classify(code, author, full, reviewer):
    if code in full:
        return "ALREADY_FULL_SOURCE_SKIP_PAYLOAD"
    if not isinstance(author, str) or not author.strip():
        return "SOURCE_AUTHOR_UNKNOWN_REQUIRES_PROVENANCE"
    if author == reviewer:
        return "SAME_SOURCE_AUTHOR_REQUIRES_DISJOINT_REVIEW"
    return "DISJOINT_SOURCE_AUTHOR_REQUIRES_REMAINING_REVIEW_GATES"


def indexed_reference(entry):
    if "sourceFinal" in entry:
        if "file" in entry or "sha256" in entry:
            raise ValueError("Conflicting flat/nested source references")
        reference = entry["sourceFinal"]
    else:
        reference = entry
    return {key: reference[key] for key in ("file", "sha256")}


def plan(spec):
    snapshot = _Snapshot()
    raw, control_pin = snapshot.read(spec["control"])
    control = _json(raw)
    if (control.get("phase") != "working" or control.get("iteration") != spec["iteration"]
            or control.get("acceptanceOwner") != spec["reviewer"] or spec["reviewer"] != "/root"
            or control.get("subagentsAllowed") is not False):
        raise ValueError("Current root-only owner/window differs")
    raw, publication_pin = snapshot.reference(spec["publication"])
    report = _json(raw)
    live = {}
    for name, ref in spec["liveAssets"].items():
        _, live[name] = snapshot.reference(ref)
    _, full, sources, receipt = _report_epoch(snapshot, report, live)
    rows, payload_reads = [], 0
    seen = set()
    for reference in spec["sourceIndexes"]:
        raw, index_pin = snapshot.reference(reference)
        index = _json(raw)
        for entry in index["rows"]:
            code, author = entry["officialCode"], entry.get("sourceActor")
            if code in seen:
                raise ValueError("Repeated indexed source code; release one exact version")
            seen.add(code)
            disposition = classify(code, author, full, spec["reviewer"])
            source_ref = indexed_reference(entry)
            if disposition != "ALREADY_FULL_SOURCE_SKIP_PAYLOAD":
                # Only the explicitly released source is opened, never a scan
                # or automatic traversal into immutable historical packages.
                payload, source_ref = snapshot.reference(source_ref)
                source = _json(payload)
                payload_reads += 1
                actual_author = source.get("sourceActor", source.get("sourceAuthor"))
                if actual_author != author:
                    raise ValueError("Indexed and actual source author differ")
            rows.append(dict(officialCode=code, observedSourceAuthor=author,
                originalAssignedQaActor=entry.get("qaActor"), actualNewReviewerClaimed=False,
                source=source_ref, index=index_pin, disposition=disposition,
                geographicCredit=0))
    for reference in spec["preparations"]:
        raw, reference_pin = snapshot.reference(reference)
        observation = _json(raw)
        payload_reads += 1
        code, author = observation["officialCode"], observation.get("sourceActor")
        if code in seen:
            raise ValueError("Repeated preparation/index code")
        seen.add(code)
        rows.append(dict(officialCode=code, observedSourceAuthor=author, source=reference_pin,
            disposition=classify(code, author, full, spec["reviewer"]),
            sourcePreparationStatus=observation["status"],
            originalAssignedQaActor=None, actualNewReviewerClaimed=False, geographicCredit=0))
    validated = {code for row in report["validations"] for code in row["locationCodes"]}
    legacy = sorted({code for row in report["sourceOnlyAcceptances"] for code in row["locationCodes"]} - validated)
    for code in legacy:
        if code not in seen:
            rows.append(dict(officialCode=code, observedSourceAuthor=None,
                disposition="LEGACY_SOURCE_ONLY_REQUIRES_RECONCILIATION_AND_PROVENANCE",
                actualNewReviewerClaimed=False, geographicCredit=0))
    snapshot.verify_unchanged()
    return dict(schemaVersion=1, status="FINITE_ARTIFACT_WORK_PLAN_NO_ACCEPTANCE",
        generatedAtUtc=datetime.now(timezone.utc).isoformat(), control=control_pin,
        publication=publication_pin, currentReportSources=sources, currentPracticalReceipt=receipt,
        liveAssets=live, currentFullSourceCount=len(full), currentGeographicCount=len(validated),
        reviewer=spec["reviewer"], rows=rows, counts=dict(Counter(r["disposition"] for r in rows)),
        largeSourcePayloadsOpened=payload_reads, alreadyAcceptedPayloadsOpened=0,
        newGeographicCredit=0, standardAcceptanceReadyAsserted=False,
        qualification="Dispatch facts only. Preserve source author/assigned reviewer history. All native, identity, legal, neighbor, topology, GPS, prayer, persistence, protected-body, complete-scope and full CAF/A4 gates remain required.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = plan(_json(args.manifest.read_bytes()))
    with args.output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=False, allow_nan=False, indent=2)
        stream.write("\n")
    print(json.dumps({key: result[key] for key in ("status", "counts", "largeSourcePayloadsOpened", "newGeographicCredit")}))


if __name__ == "__main__":
    main()
