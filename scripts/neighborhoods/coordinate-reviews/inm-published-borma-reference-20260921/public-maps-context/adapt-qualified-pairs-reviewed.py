#!/usr/bin/env python3
"""Offline adapter: qualified INM report -> prepare_reference_stage paired schema.

Target schema: {schemaVersion, records, sourceQualification:{file,sha256}, qualification}.
"""
import argparse
import hashlib
import json
import os
import sys
from pathlib import Path

WORK = Path(__file__).resolve().parent.parent

def die(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def norm_path(p):
    return os.path.normcase(str(Path(p).resolve()))


def under_work(p):
    try:
        Path(p).resolve().relative_to(WORK.resolve())
        return True
    except ValueError:
        return False


def load_json(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        die(f"cannot read JSON {path}: {e}")


def load_jsonl(path):
    rows = []
    try:
        with open(path, "r", encoding="utf-8") as f:
            for lineno, line in enumerate(f, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError as e:
                    die(f"invalid JSONL at {path}:{lineno}: {e}")
                if not isinstance(obj, dict):
                    die(f"JSONL row at {path}:{lineno} is not an object")
                rows.append((lineno, obj))
    except Exception as e:
        die(f"cannot read JSONL {path}: {e}")
    if not rows:
        die(f"manifest is empty: {path}")
    return rows


def verify_file_sha(path, expected, label):
    p = Path(path)
    if not p.is_file():
        die(f"{label}: file not found: {p}")
    actual = sha256_file(p)
    if actual.lower() != str(expected).lower():
        die(f"{label}: sha256 mismatch for {p}: expected {expected}, got {actual}")
    return p.resolve()


def find_receipt_row(rows, did, kind, body_hash, requested_url, final_url, status):
    expected = {'delegationId':did, 'kind':kind, 'sha256':body_hash,
                'requestedUrl':requested_url, 'finalUrl':final_url, 'status':status}
    matches = [row for _, row in rows if all(row.get(k)==v for k,v in expected.items())]
    if not matches:
        die(f'No exact named-field receipt match for {did} {kind}')
    return matches[0]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--qualification", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    out_path = Path(args.out).resolve()
    if not under_work(out_path):
        die(f"refusing output outside WORK ({WORK}): {out_path}")

    qual_path = Path(args.qualification).resolve()
    if norm_path(qual_path) == norm_path(out_path):
        die("output must not overwrite the qualification report")

    if not under_work(qual_path):
        die("Qualification report must be under WORK")
    report = load_json(qual_path)

    if report.get("status") != "QUALIFICATION_ONLY_NO_APPROVAL":
        die("report.status must be QUALIFICATION_ONLY_NO_APPROVAL")
    records = report.get("records")
    if not isinstance(records, list) or not records:
        die("report.records must be a nonempty list")
    summary = report.get("summary")
    if isinstance(summary, dict) and summary.get("failedCount") not in (0, None):
        die("report.summary.failedCount must be 0")

    pins = report.get("inputPins")
    if not isinstance(pins, dict) or not pins:
        die("report.inputPins must be a nonempty object")
    input_norms = {norm_path(qual_path)}
    for name, pin in pins.items():
        if not isinstance(pin, dict) or "file" not in pin or "sha256" not in pin:
            die(f"report.inputPins.{name} must contain file and sha256")
        p = verify_file_sha(pin["file"], pin["sha256"], f"inputPins.{name}")
        input_norms.add(norm_path(p))
    if norm_path(out_path) in input_norms:
        die("output must not overwrite an input pin or the qualification report")

    receipts_pin = pins.get("receipts")
    if not isinstance(receipts_pin, dict):
        die("report.inputPins.receipts is required")
    receipts_file = Path(receipts_pin["file"]).resolve()
    # receipts_file hash was verified in the inputPins loop above
    manifest_rows = load_jsonl(receipts_file)
    report_sha = sha256_file(qual_path)

    seen_ids = set()
    out_records = []

    for idx, rec in enumerate(records):
        if not isinstance(rec, dict):
            die(f"record {idx} is not an object")
        if rec.get("qualified") is not True:
            die(f"record {idx} is not qualified")
        if rec.get("failure"):
            die(f"record {idx} has failure: {rec.get('failure')}")

        did = rec.get("delegationId")
        if not isinstance(did, int) or isinstance(did, bool):
            die(f"record {idx} delegationId must be an integer")
        if did in seen_ids:
            die(f"duplicate delegationId {did}")
        seen_ids.add(did)

        correction = rec.get("correction")
        if not isinstance(correction, dict):
            die(f"record {did} missing correction object")
        for key in ("delegationId", "expectedGovernorateId", "expectedNames",
                    "referenceKind", "original", "proposed", "inmEvidence"):
            if key not in correction:
                die(f"record {did} correction missing {key}")
        if correction["delegationId"] != did:
            die(f"record {did} correction.delegationId mismatch")

        expected_names = correction["expectedNames"]
        if not isinstance(expected_names, dict) or not isinstance(expected_names.get("nomAr"), str) \
                or not expected_names["nomAr"]:
            die(f"record {did} correction.expectedNames.nomAr must be a nonempty string")

        expected_gov = correction["expectedGovernorateId"]
        inm_ev = correction["inmEvidence"]
        if not isinstance(inm_ev, dict):
            die(f"record {did} correction.inmEvidence must be an object")
        service_date = inm_ev.get("serviceDate")
        if not isinstance(service_date, str) or not service_date:
            die(f"record {did} correction.inmEvidence.serviceDate must be a nonempty string")

        evidence = rec.get("originalEvidence")
        if not isinstance(evidence, dict):
            die(f"record {did} missing originalEvidence object")

        inm_out = {}
        transport_out = {}

        for kind in ("prayer", "sun"):
            if kind not in evidence:
                die(f"record {did} originalEvidence missing {kind}")
            ev = evidence[kind]
            if not isinstance(ev, dict):
                die(f"record {did} originalEvidence.{kind} must be an object")

            body = ev.get("body")
            retrieval = ev.get("retrieval")
            if not isinstance(body, dict) or "file" not in body or "sha256" not in body:
                die(f"record {did} originalEvidence.{kind}.body must contain file and sha256")
            if not isinstance(retrieval, dict) or "file" not in retrieval or "sha256" not in retrieval:
                die(f"record {did} originalEvidence.{kind}.retrieval must contain file and sha256")

            body_file = verify_file_sha(body["file"], body["sha256"], f"record {did} {kind} body")
            body_hash = str(body["sha256"])
            ret_file = verify_file_sha(retrieval["file"], retrieval["sha256"],
                                       f"record {did} {kind} retrieval")
            input_norms.add(norm_path(body_file))
            input_norms.add(norm_path(ret_file))
            if norm_path(out_path) in input_norms:
                die("output must not overwrite any evidence file")

            rj = load_json(ret_file)
            if not isinstance(rj, dict):
                die(f"record {did} {kind} retrieval JSON is not an object")
            if rj.get("status") != 200:
                die(f"record {did} {kind} retrieval status must be 200")
            if str(rj.get("sha256", "")).lower() != body_hash.lower():
                die(f"record {did} {kind} retrieval sha256 does not match body hash")

            kind_ev = inm_ev.get(kind)
            if not isinstance(kind_ev, dict) or "url" not in kind_ev or "sha256" not in kind_ev:
                die(f"record {did} correction.inmEvidence.{kind} must contain url and sha256")
            expected_url = kind_ev["url"]
            if not isinstance(expected_url, str) or not expected_url:
                die(f"record {did} correction.inmEvidence.{kind}.url must be a nonempty string")
            if str(kind_ev.get("sha256", "")).lower() != body_hash.lower():
                die(f"record {did} correction.inmEvidence.{kind}.sha256 does not match body hash")

            if rj.get("requestedUrl") != expected_url:
                die(f"record {did} {kind} retrieval requestedUrl mismatch")
            if rj.get("finalUrl") != expected_url:
                die(f"record {did} {kind} retrieval finalUrl mismatch")
            if rj.get("delegationId") != did:
                die(f"record {did} {kind} retrieval delegationId mismatch")
            if rj.get("kind") != kind:
                die(f"record {did} {kind} retrieval kind mismatch")
            if rj.get("governorateId") != expected_gov:
                die(f"record {did} {kind} retrieval governorateId mismatch")

            find_receipt_row(
                manifest_rows,
                did,
                kind,
                body_hash,
                rj["requestedUrl"],
                rj["finalUrl"],
                rj["status"],
            )

            inm_out[kind] = {
                "file": str(body_file),
                "sha256": body_hash,
                "url": expected_url,
                "date": f"{service_date} 00:00",
            }
            transport_out[kind] = {
                "manifestFile": receipts_pin["file"],
                "sha256": body_hash,
                "requestedUrl": rj["requestedUrl"],
                "finalUrl": rj["finalUrl"],
                "status": rj["status"],
            }

        out_records.append({
            "delegationId": did,
            "expectedGovernorateId": expected_gov,
            "expectedNames": expected_names,
            "referenceKind": correction["referenceKind"],
            "original": correction["original"],
            "proposed": correction["proposed"],
            "name": expected_names["nomAr"],
            "qualification": True,
            "INMevidence": inm_out,
            "transport": transport_out,
        })

    output = {
        "schemaVersion": 1,
        "records": out_records,
        "sourceQualification": {
            "file": str(qual_path),
            "sha256": report_sha,
        },
        "qualification": "Mechanical schema adaptation only; geographic approval requires separate root decision.",
    }

    if norm_path(out_path) in input_norms:
        die("output must not overwrite any input file")
    if not out_path.parent.is_dir():
        die(f"output directory does not exist: {out_path.parent}")

    try:
        with open(out_path, "x", encoding="utf-8") as f:
            json.dump(output, f, ensure_ascii=False, indent=2)
            f.write("\n")
    except FileExistsError:
        die(f"output already exists: {out_path}")
    except Exception as e:
        die(f"cannot create output {out_path}: {e}")

    out_hash = sha256_file(out_path)
    print(f"PATH {out_path}")
    print(f"SHA256 {out_hash}")
    print("RECORD_IDS " + ",".join(str(i) for i in sorted(seen_ids)))


if __name__ == "__main__":
    main()
