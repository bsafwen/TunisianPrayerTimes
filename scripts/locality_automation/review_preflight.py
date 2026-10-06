"""Check a frozen review package before spending time on independent QA.

This checks declared files and bookkeeping only. It does not reconstruct source
geometry, judge identity/ink/scope, run GPS, award credit, or install anything.
Only explicit pins in the supplied package are read; referenced JSON is not
recursively opened. Relative references require an explicit evidence base.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import time

from .common import digest, now, write

HEX = re.compile(r"^[a-fA-F0-9]{64}$")
REQUIRED_SOURCE_PINS = ("sourcePdf", "rawSourceGeometry", "expectedAdoptedGeometry")
LIVE_KEYS = ("currentJson", "currentBin", "displayNames")
PRIVATE_CONFIG_NAMES = {"google-maps.local.js", ".env"}


def _private(path):
    return path.name.casefold() in PRIVATE_CONFIG_NAMES or path.name.casefold().startswith(".env.")


def explicit_pins(value, base, location="$", found=None, reference_roots=None):
    """Yield references with their JSON location; do not follow their contents."""
    if found is None:
        found = []
    if isinstance(value, dict):
        if "file" in value and "sha256" in value:
            raw, fingerprint = value["file"], value["sha256"]
            path = None
            if isinstance(raw, str) and raw:
                try:
                    path = Path(raw)
                    if not path.is_absolute():
                        mappings = reference_roots or {}
                        prefix = path.parts[0] if path.parts else ""
                        if prefix in mappings:
                            mapped = mappings[prefix]
                            path = (mapped / Path(*path.parts[1:])).resolve()
                            if not path.is_relative_to(mapped):
                                raise ValueError("Reference escaped its namespace")
                        else:
                            path = base / path
                    path = path.resolve()
                except (ValueError, OSError):
                    path = None
            found.append((location, path, fingerprint))
            # A pin is an atomic reference, not another manifest to hydrate.
        else:
            for key, item in value.items():
                explicit_pins(item, base, f"{location}.{key}", found, reference_roots)
    elif isinstance(value, list):
        for index, item in enumerate(value):
            explicit_pins(item, base, f"{location}[{index}]", found, reference_roots)
    return found


def _allowed(path, roots):
    return any(path == root or path.is_relative_to(root) for root in roots)


def _fingerprint(path):
    """Check stability during hashing; callers still recheck before QA/install."""
    try:
        before = path.stat()
        value = digest(path)
        after = path.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            return {"error": "changed_while_hashing"}
        return {"sha256": value, "bytes": after.st_size}
    except OSError:
        return {"error": "missing_or_unreadable"}


def inspect_package(package, *, base, roots, expected_codes=None, live_assets=None, workers=4, reference_roots=None):
    started = time.monotonic()
    if isinstance(workers, bool) or not isinstance(workers, int) or not 1 <= workers <= 16:
        raise ValueError("workers must be an integer from 1 to 16")
    base = Path(base).resolve()
    roots = [Path(root).resolve() for root in roots]
    if not roots:
        raise ValueError("Declare at least one allowed input root")
    mappings = {prefix: Path(root).resolve() for prefix, root in (reference_roots or {}).items()}
    if len({prefix.casefold() for prefix in mappings}) != len(mappings):
        raise ValueError("Reference prefixes must be unique ignoring case")
    if any(not prefix or Path(prefix).anchor or len(Path(prefix).parts) != 1 or prefix in {".", ".."} or not _allowed(root, roots)
           for prefix, root in mappings.items()):
        raise ValueError("Reference roots require a simple relative prefix and a directory under allowed roots")
    package = Path(package).resolve()
    if not _allowed(package, roots):
        raise ValueError("Package is outside declared input roots")
    if _private(package):
        raise ValueError("Private config is not review evidence")
    result = {
        "schemaVersion": 1, "generatedAtUtc": now(), "packageFile": str(package),
        "status": "WAITING_FOR_PACKAGE", "issues": [], "warnings": [],
        "qualification": "Bookkeeping preflight only; independent source/identity/neighbor/adoption/GPS QA remains required. No geographic credit or changes.",
        "geographicCreditAdded": 0, "assetsChanged": False, "ledgersChanged": False,
    }
    if not package.exists():
        result["issues"].append({"code": "package_not_present"})
        return result
    try:
        raw = package.read_bytes()
    except OSError:
        result["status"] = "PREFLIGHT_HOLD"
        result["issues"].append({"code": "package_unreadable"})
        return result
    result["packagePin"] = {"file": str(package), "sha256": hashlib.sha256(raw).hexdigest()}
    try:
        data = json.loads(raw.decode("utf-8-sig"))
    except (ValueError, UnicodeError):
        result["status"] = "PREFLIGHT_HOLD"
        result["issues"].append({"code": "invalid_package_json"})
        return result
    if not isinstance(data, dict):
        result["status"] = "PREFLIGHT_HOLD"
        result["issues"].append({"code": "package_not_object"})
        return result
    issues = result["issues"]
    if type(data.get("schemaVersion")) is not int or data["schemaVersion"] != 1:
        issues.append({"code": "unsupported_package_schema"})
    if not isinstance(data.get("status"), str) or not data["status"].strip():
        issues.append({"code": "missing_declared_package_status"})
    selected = data.get("selectedSourceCodes")
    records = data.get("sourceRecords")
    if not isinstance(selected, list) or not selected or not all(isinstance(c, str) and c for c in selected):
        issues.append({"code": "invalid_selected_codes"})
        selected = []
    if len(set(selected)) != len(selected):
        issues.append({"code": "duplicate_selected_codes"})
    if expected_codes is not None and sorted(selected) != sorted(expected_codes):
        issues.append({"code": "selected_codes_differ_from_assignment"})
    if not isinstance(records, list) or not records:
        issues.append({"code": "missing_source_records"})
        records = []
    codes, ids, qualifications = [], [], []
    for index, record in enumerate(records):
        at = f"$.sourceRecords[{index}]"
        if not isinstance(record, dict):
            issues.append({"code": "source_record_not_object", "at": at})
            continue
        code, ident = record.get("officialCode"), record.get("id")
        if not isinstance(code, str) or not code or not isinstance(ident, str) or not ident:
            issues.append({"code": "missing_source_identity", "at": at})
        else:
            codes.append(code)
            ids.append(ident)
        for key in REQUIRED_SOURCE_PINS:
            item = record.get(key)
            if not isinstance(item, dict) or not isinstance(item.get("file"), str) or not HEX.fullmatch(str(item.get("sha256", ""))):
                issues.append({"code": "missing_required_source_pin", "at": at + "." + key})
        # Distinct raw/adopted files may represent intentional source adoption;
        # equality, numeric area thresholds and scope are not preflight gates.
        qualifications.append({"officialCode": code, "id": ident, "scope": record.get("scope"),
                               "sourceQualification": record.get("sourceQualification"),
                               "specialAdoptionQualification": record.get("specialAdoptionQualification"),
                               "sourceGridPacksExactCurrentBytes": record.get("sourceGridPacksExactCurrentBytes")})
    if sorted(codes) != sorted(selected):
        issues.append({"code": "source_records_differ_from_selected_codes"})
    if len(set(codes)) != len(codes) or len(set(ids)) != len(ids):
        issues.append({"code": "duplicate_source_record_identity"})
    supporting = data.get("supportingOnlyCodes", [])
    if not isinstance(supporting, list) or not all(isinstance(c, str) for c in supporting):
        issues.append({"code": "invalid_supporting_codes"})
        supporting = []
    if set(selected).intersection(supporting):
        issues.append({"code": "supporting_code_selected_for_credit"})
    if data.get("structuralSourceHolds") != []:
        issues.append({"code": "declared_source_holds_or_missing_hold_list"})
    obligations = data.get("requiredIndependentQa")
    if not isinstance(obligations, list) or not obligations or not all(isinstance(item, str) and item.strip() for item in obligations):
        issues.append({"code": "missing_independent_qa_obligations"})
    references = explicit_pins(data, base, reference_roots=mappings)
    unique = {}
    for at, path, expected in references:
        if path is None or not isinstance(expected, str) or not HEX.fullmatch(expected):
            issues.append({"code": "invalid_pin", "at": at})
        elif not _allowed(path, roots):
            issues.append({"code": "pin_outside_declared_roots", "at": at})
        elif _private(path):
            issues.append({"code": "private_config_is_not_review_evidence", "at": at})
        else:
            unique.setdefault(path, set()).add(expected.lower())
    # Bounded parallel hashing, once per file in this invocation. No broad scan,
    # persistent stat-only cache or trusting old report status strings.
    with ThreadPoolExecutor(max_workers=workers) as pool:
        hashes = dict(zip(unique, pool.map(_fingerprint, unique)))
    for path, expected in unique.items():
        fact = hashes[path]
        if "error" in fact:
            issues.append({"code": fact["error"], "file": str(path)})
        elif len(expected) != 1 or fact["sha256"] not in expected:
            issues.append({"code": "pin_hash_mismatch_or_conflict", "file": str(path)})
    if live_assets is not None:
        if not {"currentJson", "currentBin"}.issubset(live_assets) or not set(live_assets).issubset(LIVE_KEYS):
            raise ValueError("Live asset checks require currentJson/currentBin together; displayNames is optional")
        inputs = data.get("inputs")
        inputs = inputs if isinstance(inputs, dict) else {}
        live_checks = {}
        for key in live_assets:
            path = Path(live_assets[key]).resolve()
            if not _allowed(path, roots):
                raise ValueError("Live asset is outside declared input roots")
            if _private(path):
                raise ValueError("Private config is not a live asset")
            actual = _fingerprint(path)
            expected = inputs.get(key, {})
            expected_sha = expected.get("sha256") if isinstance(expected, dict) else None
            equal = isinstance(expected_sha, str) and actual.get("sha256") is not None and actual["sha256"] == expected_sha.lower()
            live_checks[key] = {"file": str(path), "sha256": actual.get("sha256"), "matchesPackageEpoch": equal}
            if not equal:
                issues.append({"code": "live_asset_epoch_differs", "asset": key})
        result["liveAssetChecks"] = live_checks
        if "displayNames" not in live_assets:
            result["warnings"].append({"code": "live_names_epoch_not_checked"})
    else:
        result["warnings"].append({"code": "live_epoch_not_checked"})
    try:
        stable = hashlib.sha256(package.read_bytes()).hexdigest() == result["packagePin"]["sha256"]
    except OSError:
        stable = False
    if not stable:
        issues.append({"code": "package_changed_during_preflight"})
    result.update({"status": "PREFLIGHT_HOLD" if issues else "PREFLIGHT_OK_PENDING_INDEPENDENT_QA",
                   "declaredPackageStatus": data.get("status"), "selectedSourceCodes": selected,
                   "sourceRecordCount": len(records), "supportingOnlyCodes": supporting,
                   "sourceQualifications": qualifications, "requiredIndependentQa": data.get("requiredIndependentQa"),
                   "explicitPinReferences": len(references), "distinctFilesHashed": len(unique),
                   "referenceRoots": {prefix: str(root) for prefix, root in mappings.items()},
                   "elapsedSeconds": round(time.monotonic() - started, 3)})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--allow-root", type=Path, action="append", required=True)
    parser.add_argument("--reference-root", action="append", default=[], metavar="PREFIX=DIR",
                        help="Explicit namespace for legacy relative pins; no fallback path guessing")
    parser.add_argument("--expected-code", action="append")
    parser.add_argument("--live-json", type=Path)
    parser.add_argument("--live-bin", type=Path)
    parser.add_argument("--live-names", type=Path)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Use a fresh preflight output file")
    if bool(args.live_json) != bool(args.live_bin) or (args.live_names and not args.live_json):
        parser.error("Provide live JSON and binary together, optionally with names")
    mappings = {}
    for assignment in args.reference_root:
        prefix, separator, directory = assignment.partition("=")
        if not separator or not directory or prefix.casefold() in {key.casefold() for key in mappings}:
            parser.error("Reference roots must be unique PREFIX=DIR assignments")
        mappings[prefix] = Path(directory)
    live = {key: path for key, path in zip(LIVE_KEYS, (args.live_json, args.live_bin, args.live_names)) if path}
    result = inspect_package(args.package, base=args.base, roots=args.allow_root,
                             expected_codes=args.expected_code,
                             live_assets=live or None, workers=args.workers, reference_roots=mappings)
    write(args.output, result)
    print(json.dumps({key: result[key] for key in ("status", "issues", "warnings", "geographicCreditAdded")}, ensure_ascii=False))
    raise SystemExit(0 if result["status"] == "PREFLIGHT_OK_PENDING_INDEPENDENT_QA" else 2)


if __name__ == "__main__":
    main()
