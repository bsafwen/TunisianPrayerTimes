"""Extend exact accepted-body references without inventing new behavior proofs.

Preserves heterogeneous historical bindings verbatim. New entries are derived
from qualified report receipts and source proofs, never labels or proximity.
This registry is reference/lineage evidence; it is not a GPS or legal survey pass.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read


def build(base_reference, publication_reference, metadata_reference, binary_reference):
    base = read(checked(base_reference))
    publication = read(checked(publication_reference))
    metadata = read(checked(metadata_reference))
    binary = checked(binary_reference).read_bytes()
    bindings = list(base["bindings"])
    by_code = {row["officialCode"]: row for row in bindings}
    if len(by_code) != len(bindings):
        raise ValueError("Historical binding codes are duplicated")
    current = {row["id"]: row for row in metadata["features"]}
    full = set(publication["summary"]["explicitFullSourceBoundaryLocalityCodes"])
    if not set(by_code) <= full:
        raise ValueError("Historical accepted codes were dropped")
    for code in sorted(full - set(by_code)):
        events = [event for event in publication["validations"]
                  if isinstance(event.get("fullSourceBoundaryLocalityCodes"), list)
                  and code in event["fullSourceBoundaryLocalityCodes"]]
        # Multiple historical validations are legitimate. Use the newest explicit
        # current-body event, preserving its original ID/date and receipt.
        events = [event for event in events if event.get("kind") == "current_source_gps_validated"]
        if not events:
            raise ValueError("No current-body full receipt for additional code " + code)
        event = max(events, key=lambda row: datetime.fromisoformat(row["occurredAtUtc"].replace("Z", "+00:00")))
        ref = next(ref for ref in event["evidenceRefs"] if ref["kind"] == "receipt")
        receipt_ref = {"file": ref.get("file") or ref["path"], "sha256": ref["sha256"]}
        receipt = read(checked(receipt_ref))
        if (receipt.get("status") != "VALIDATED_CURRENT_SOURCE_GEOMETRY"
                or receipt.get("boundaryScopeRecorded") is not True
                or code not in receipt.get("fullSourceBoundaryLocalityCodes", [])
                or receipt.get("assetChanges") is not False):
            raise ValueError("Additional accepted receipt scope/action mismatch")
        gps_ref = receipt["gpsEvidence"]
        gps = read(checked(gps_ref))
        if gps.get("status") != "PASS_OFFLINE_CURRENT_SOURCE_GPS" or gps.get("probeFailures") != [] or gps.get("structuralFailures") != []:
            raise ValueError("Accepted GPS wrapper failed")
        for field, expected in (("currentJson", metadata_reference), ("currentBin", binary_reference)):
            snapshot = gps["inputs"][field]
            checked(snapshot)
            if snapshot["sha256"] != expected["sha256"]:
                raise ValueError("Additional accepted proof differs from exact current asset bytes")
        proof_ref = gps["inputs"]["sourceProofs"][code]
        proof = read(checked(proof_ref))
        scope_ref = proof["sourceScopeQa"]
        scope = read(checked(scope_ref))
        ident = proof["id"]
        if (proof.get("officialCode") != code or proof.get("boundaryScope") != "full-source-face"
                or scope.get("officialCode") != code or scope.get("id") != ident
                or scope.get("boundaryScope") != "full-source-face" or ident not in current):
            raise ValueError("Additional source code/id/scope mismatch")
        entry = {"officialCode": code, "id": ident, "group": "acceptedNoOpSuccessor",
                 "acceptedReceipt": receipt_ref, "acceptedGpsEvidence": gps_ref,
                 "authoritativeReference": proof_ref, "sourceScopeQa": scope_ref,
                 "acceptedEventId": event["id"], "acceptedAtUtc": event["occurredAtUtc"],
                 "currentMetadata": current[ident], "assetChanges": False}
        for field in ("rawSourceGeometry", "expectedAdoptedGeometry", "sourceDocument", "sourcePdf", "sourcePdfParts", "nativeAssemblyEvidence"):
            if field in proof:
                entry[field] = proof[field]
                refs = proof[field] if isinstance(proof[field], list) else [proof[field]]
                for reference in refs:
                    # Multipart structures remain unchanged; their nested refs
                    # were validated by the original consumer, never rewritten.
                    if "file" in reference:
                        checked(reference)
        row = current[ident]
        entry["packedSliceSha256"] = hashlib.sha256(binary[row["offset"]:row["offset"] + row["length"]]).hexdigest()
        bindings.append(entry)
    codes = [row["officialCode"] for row in bindings]
    ids = [row["id"] for row in bindings]
    if set(codes) != full or len(codes) != len(set(codes)) or len(ids) != len(set(ids)):
        raise ValueError("Exact current accepted binding union or unique IDs differ")
    return {"schemaVersion": 1, "status": "PASS_EXACT_ACCEPTED_BODY_REFERENCE_LINEAGE",
            "createdAtUtc": datetime.now(timezone.utc).isoformat(), "bindingCount": len(bindings),
            "immutablePredecessor": base_reference, "publication": publication_reference,
            "currentMetadata": metadata_reference, "currentBinary": binary_reference,
            "bindings": bindings, "historicalBindingsPreservedVerbatim": bindings[:len(base["bindings"])] == base["bindings"],
            "additionalCodeCount": len(full - set(by_code)), "freshBodyOrBehaviorPassClaimed": False,
            "historicalCountsOrTimestampsRelabeled": False, "newGeographicCredit": 0,
            "qualification": "Exact accepted receipt/source/current-asset lineage only. Historical304/305 behavioral proofs retain their original scopes. Per-case independent numerical/JVM/CAF proofs remain separate requirements."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest = read(args.manifest)
    result = build(manifest["baseRegistry"], manifest["publication"], manifest["metadata"], manifest["binary"])
    with args.output.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"status": result["status"], "count": result["bindingCount"], "additional": result["additionalCodeCount"], "output": pin(args.output)}))


if __name__ == "__main__":
    main()
