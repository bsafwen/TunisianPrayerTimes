"""Filter cached source-body leads against one current, read-only dispatch epoch.

``filter_candidates(triage, report, live_json, live_bin, assigned_codes=())``
returns HOLD with no candidates if current reporting inputs cannot be verified.
Otherwise only unchanged packed slices remain warm leads requiring fresh source
review. This does not run workers, inspect native sources/GPS, or award credit.
Run the CLI immediately before dispatch; its epoch pins expire on any input or
assignment change. --output must name a fresh file. All dependencies are stdlib.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _json(data):
    return json.loads(data.decode("utf-8-sig"))


def _digest(value):
    return _sha(json.dumps(value, ensure_ascii=False, sort_keys=True,
                           separators=(",", ":")).encode("utf-8"))


def _code(value):
    if isinstance(value, bool) or not isinstance(value, (str, int)) or not str(value).strip():
        raise ValueError("invalid_official_code")
    return str(value).strip()


class _Snapshot:
    def __init__(self):
        self.files = {}

    def read(self, path, expected=None):
        path = Path(path).resolve(strict=True)
        key = os.path.normcase(str(path))
        if key not in self.files:
            before = path.stat()
            data = path.read_bytes()
            after = path.stat()
            if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                raise ValueError("input_changed_during_read:" + str(path))
            self.files[key] = (path, data, after.st_size, after.st_mtime_ns)
        path, data, size, _ = self.files[key]
        pin = {"file": str(path), "sha256": _sha(data), "size": size}
        if expected is not None and expected != pin["sha256"]:
            raise ValueError("input_pin_mismatch:" + str(path))
        return data, pin

    def reference(self, ref):
        if not isinstance(ref, dict) or not re.fullmatch(r"[0-9a-f]{64}", ref.get("sha256", "")):
            raise ValueError("invalid_input_pin")
        return self.read(ref.get("file", ref.get("path", "")), ref["sha256"])

    def verify_unchanged(self):
        for path, data, size, modified in self.files.values():
            stat = path.stat()
            if ((stat.st_size, stat.st_mtime_ns) != (size, modified)
                    or _sha(path.read_bytes()) != _sha(data)):
                raise ValueError("epoch_input_changed:" + str(path))


def _report_epoch(snapshot, report, live_pins):
    summary = report["summary"]
    for key in ("validationIssues", "sourceOnlyAuditIssues", "reportingIssues"):
        if summary.get(key) != []:
            raise ValueError("report_issues_or_missing_list:" + key)
    for key in ("currentAssetPinsVerified", "practicalChainVerified"):
        if summary.get(key) is not True:
            raise ValueError("report_current_verification_missing:" + key)
    raw_codes = summary["explicitFullSourceBoundaryLocalityCodes"]
    if not isinstance(raw_codes, list):
        raise ValueError("full_source_code_list_missing_or_invalid")
    codes = {_code(value) for value in raw_codes}
    count = summary["explicitFullSourceBoundaryLocationCount"]
    if type(count) is not int or count != len(codes):
        raise ValueError("full_source_count_code_list_mismatch")
    sources = report.get("sourceFiles")
    if not isinstance(sources, list) or not sources:
        raise ValueError("report_source_files_missing")
    source_pins, receipt_pins = [], []
    for source in sources:
        raw, pin = snapshot.reference(source)
        source_pins.append({**pin, "kind": source.get("kind")})
        if source.get("kind") == "pointer":
            pointer = _json(raw)
            receipt_raw, receipt_pin = snapshot.reference(pointer["practicalProgress"])
            receipt = _json(receipt_raw)
            for name, live in live_pins.items():
                installed = receipt["installedAssets"][name]
                if (installed.get("sha256") != live["sha256"]
                        or Path(installed["file"]).resolve() != Path(live["file"])):
                    raise ValueError("receipt_live_asset_mismatch:" + name)
            receipt_pins.append(receipt_pin)
    if not receipt_pins or len({p["sha256"] for p in receipt_pins}) != 1:
        raise ValueError("report_current_receipt_pointer_missing_or_conflicting")
    return summary, codes, source_pins, receipt_pins[0]


def filter_candidates(triage, report, live_json, live_bin, assigned_codes=()):
    """Return a new queue only from verified current inputs; never mutate them."""
    result = {"schemaVersion": 1, "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
              "status": "HOLD", "issues": [], "currentPins": {}, "epoch": None,
              "actionableCandidates": [], "groups": [], "skipped": [], "counters": {},
              "newGeographicCredit": 0,
              "qualification": "Cached unchanged slices are warm leads only. Fresh source identity, scope, native geometry and independent GPS review remain required. Refilter immediately before each dispatch if epoch inputs or assignments change."}
    snapshot = _Snapshot()
    try:
        triage_raw, triage_pin = snapshot.read(triage)
        report_raw, report_pin = snapshot.read(report)
        metadata_raw, metadata_pin = snapshot.read(live_json)
        binary, binary_pin = snapshot.read(live_bin)
        cached, current_report, metadata = map(_json, (triage_raw, report_raw, metadata_raw))
        live_pins = {"neighborhoods.json": metadata_pin, "neighborhoods.bin": binary_pin}
        result["currentPins"] = {"triage": triage_pin, "report": report_pin, **live_pins}
        summary, full, source_pins, receipt_pin = _report_epoch(snapshot, current_report, live_pins)
        assigned = {_code(code) for code in assigned_codes}
        rows = cached["exactSourceMatches"]
        features = metadata["features"]
        if not isinstance(rows, list) or not isinstance(features, list):
            raise ValueError("invalid_triage_or_metadata_rows")
        by_id, by_code = defaultdict(list), defaultdict(list)
        for feature in features:
            by_id[feature.get("id")].append(feature)
            if feature.get("officialCode") is not None:
                by_code[_code(feature["officialCode"])].append(feature)
        epoch = {"report": report_pin, "reportSources": source_pins, "currentReceipt": receipt_pin,
                 "liveAssets": live_pins, "triage": triage_pin,
                 "explicitFullSourceBoundaryLocationCount": len(full),
                 "explicitFullSourceBoundaryLocalityCodes": sorted(full),
                 "assignedCodes": sorted(assigned)}
        result["epoch"] = {"sha256": _digest(epoch), **epoch}
        seen, candidates, skipped = {}, [], []
        for index, row in enumerate(rows):
            reasons = []
            try:
                code, ident = _code(row["officialCode"]), row["id"]
                if not isinstance(ident, str) or not ident:
                    raise ValueError("invalid_id")
            except (KeyError, TypeError, ValueError):
                skipped.append({"triageRowIndex": index, "reason": "STALE_INVALID_CODE_OR_ID", "requiresFreshTriage": True})
                continue
            brief = {"triageRowIndex": index, "officialCode": code, "id": ident}
            # Current acceptance always wins over an obsolete cache, including
            # rows whose identity/slice is now missing or different.
            if code in full:
                skipped.append({**brief, "reason": "ALREADY_FULL_SOURCE"})
                continue
            if code in assigned:
                skipped.append({**brief, "reason": "CURRENTLY_ASSIGNED"})
                continue
            matches = by_id.get(ident, [])
            if len(matches) != 1:
                reasons.append("STALE_CURRENT_ID_MISSING_OR_AMBIGUOUS")
            else:
                feature = matches[0]
                if feature.get("officialCode") is not None:
                    if _code(feature["officialCode"]) != code or len(by_code.get(code, [])) != 1:
                        reasons.append("STALE_CURRENT_CODE_ID_BINDING")
                    code_status = "CURRENT_METADATA_CODE_ID_MATCH"
                else:
                    # The shipped compact metadata omits official codes. Such
                    # rows remain leads only with exact known identity fields;
                    # the cached source code still needs independent binding.
                    code_status = "cached_source_code_requires_independent_binding"
                    if (not isinstance(row.get("name"), str)
                            or row["name"] not in [feature.get("name"), *feature.get("aliases", [])]
                            or row.get("parentName") != feature.get("parentName")):
                        reasons.append("STALE_CURRENT_NAME_OR_PARENT_CHANGED")
                offset, length = feature.get("offset"), feature.get("length")
                if (type(offset) is not int or type(length) is not int
                        or offset < 8 or length <= 0 or offset > len(binary) - length):
                    reasons.append("STALE_CURRENT_SLICE_MISSING_OR_INVALID")
                elif _sha(binary[offset:offset + length]) != row.get("sliceSha256"):
                    reasons.append("STALE_CURRENT_SLICE_CHANGED")
            geojson, parent = row.get("geojson"), row.get("parentName")
            if not isinstance(geojson, str) or not geojson or not isinstance(parent, str) or not parent:
                reasons.append("STALE_SOURCE_GROUP_REFERENCE_MISSING")
            if reasons:
                skipped.append({**brief, "reason": reasons[0], "reasons": reasons, "requiresFreshTriage": True})
                continue
            full_path = str(Path(geojson).resolve())
            refs = {"geojson": os.path.normcase(full_path), "parentName": parent,
                    **{key: row.get(key) for key in ("sourcePdfURL", "rawNativePath", "sourceExtraction", "administrativeScope")}}
            key = _digest({"officialCode": code, "id": ident, "sourceRefs": refs})
            if key in seen:
                seen[key]["equivalentTriageRowIndices"].append(index)
                skipped.append({**brief, "reason": "EQUIVALENT_DUPLICATE", "candidateKey": key})
                continue
            candidate = {**row, "officialCode": code, "geojson": full_path,
                         "candidateKey": key, "triageRowIndex": index,
                         "equivalentTriageRowIndices": [index], "currentOffset": offset,
                         "currentLength": length, "currentSliceSha256": _sha(binary[offset:offset + length]),
                         "status": "WARM_LEAD_REQUIRES_FRESH_SOURCE_REVIEW", "freshSourceValidated": False,
                         "codeIdentityStatus": code_status,
                         "warnings": (["Live compact metadata has no officialCode; the cached source code requires independent identity binding."]
                                      if feature.get("officialCode") is None else []),
                         "existingGeographicValidation": code in set(map(_code, summary.get("validatedLocationCodes", []))),
                         "fullSourceUpgradeCandidate": True}
            candidates.append(candidate)
            seen[key] = candidate
        grouped = defaultdict(list)
        for candidate in candidates:
            grouped[(os.path.normcase(candidate["geojson"]), candidate["parentName"])].append(candidate)
        result["groups"] = [{"groupKey": _digest([path, parent]), "geojson": members[0]["geojson"],
                             "parentName": parent, "candidateKeys": [r["candidateKey"] for r in members],
                             "officialCodes": sorted({r["officialCode"] for r in members})}
                            for (path, parent), members in sorted(grouped.items())]
        result["actionableCandidates"], result["skipped"] = candidates, skipped
        result["counters"] = {"cachedRows": len(rows), "actionableCandidates": len(candidates),
                              "actionableUniqueCodes": len({r["officialCode"] for r in candidates}),
                              "groups": len(grouped), "skippedRows": len(skipped),
                              "skipReasons": dict(Counter(r["reason"] for r in skipped)),
                              "currentFullSourceCount": len(full), "newGeographicCredit": 0}
        snapshot.verify_unchanged()
        result["status"] = "READY_FOR_FRESH_SOURCE_REVIEW"
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
        result["issues"].append(str(exc))
        result["actionableCandidates"], result["groups"], result["skipped"] = [], [], []
        result["counters"] = {"actionableCandidates": 0, "newGeographicCredit": 0}
        result["epoch"] = None
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("triage", "report", "live-json", "live-bin", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--assigned-code", action="append", default=[])
    args = parser.parse_args()
    result = filter_candidates(args.triage, args.report, args.live_json, args.live_bin, args.assigned_code)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        with args.output.open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    except FileExistsError:
        parser.error("Use a fresh output file")
    print(json.dumps({key: result[key] for key in ("status", "issues", "counters")}, ensure_ascii=False))
    raise SystemExit(2 if result["status"] == "HOLD" else 0)


if __name__ == "__main__":
    main()
