"""Bind a complete, explicitly supplied scope to unchanged evidence bytes.

Bookkeeping only: no referenced JSON hydration, source/geometry interpretation,
history demotion, geographic credit, or source/GPS acceptance.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import copy
import hashlib
import json
import math
import os
from pathlib import Path, PureWindowsPath
import re
import tempfile
import time

from .common import now, write
from .review_preflight import _allowed, _fingerprint, _private

HASH = re.compile(r"^[a-f0-9]{64}$")
AUDIT_KEY = "_scopeReferenceCompanion"
OK = "PREFLIGHT_OK_PENDING_INDEPENDENT_QA"
HOLD = "PREFLIGHT_HOLD"


def _path(raw, base, mappings, roots):
    if not isinstance(raw, str) or not raw or "\0" in raw:
        raise ValueError("invalid_path")
    windows = PureWindowsPath(raw)
    if windows.drive.startswith("\\"):
        raise ValueError("network_or_device_path")
    parts = windows.parts[1:] if windows.anchor else windows.parts
    if windows.drive and not windows.is_absolute():
        raise ValueError("drive_relative_path")
    if windows.anchor and not windows.drive and not Path(raw).is_absolute():
        raise ValueError("root_relative_path")
    if any(":" in part for part in parts):
        raise ValueError("alternate_stream_path")
    if ".." in parts:
        raise ValueError("path_traversal")
    if _private(Path(windows.name.rstrip(" ."))):
        raise ValueError("private_config_is_not_review_evidence")
    path = Path(raw)
    if not path.is_absolute():
        prefix = path.parts[0]
        namespace = mappings.get(prefix, base)
        path = namespace / (Path(*path.parts[1:]) if prefix in mappings else path)
        path = path.resolve()
        if not _allowed(path, [namespace]):
            raise ValueError("reference_escaped_namespace")
    else:
        path = path.resolve()
    if _private(Path(path.name.rstrip(" ."))):
        raise ValueError("private_config_is_not_review_evidence")
    if roots is not None and not _allowed(path, roots):
        raise ValueError("pin_outside_declared_roots")
    return path


def _references(value, pointer=""):
    """Yield atomic pins with unambiguous RFC 6901 pointers."""
    if isinstance(value, dict):
        atomic = "file" in value and "sha256" in value
        if atomic:
            yield pointer, value
        for key, item in value.items():
            if not atomic or key not in {"file", "sha256"}:
                escaped = key.replace("~", "~0").replace("/", "~1")
                yield from _references(item, pointer + "/" + escaped)
    elif isinstance(value, list):
        for index, item in enumerate(value):
            yield from _references(item, pointer + "/" + str(index))


def _finite_json(raw):
    def reject(value):
        raise ValueError("nonfinite_json_number")
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate_json_key")
            result[key] = value
        return result
    value = json.loads(raw.decode("utf-8-sig"), parse_constant=reject, object_pairs_hook=pairs)
    def check(item):
        if isinstance(item, float) and not math.isfinite(item):
            reject(item)
        if isinstance(item, dict):
            for nested in item.values():
                check(nested)
        elif isinstance(item, list):
            for nested in item:
                check(nested)
    check(value)
    return value


def _unchanged(scope_pin):
    try:
        return hashlib.sha256(Path(scope_pin["file"]).read_bytes()).hexdigest() == scope_pin["sha256"]
    except OSError:
        return False


def canonical_scope(scope_pin, *, base, roots, reference_roots=None, bindings=(), workers=4):
    """Return {scope: copied object or None, receipt: complete pin audit}.

    Bindings contain pointer, declaredFile, sha256 and target {file, sha256}.
    Lowercase hashes match the existing final check_tree/verify contract.
    """
    started = time.monotonic()
    if type(workers) is not int or not 1 <= workers <= 16:
        raise ValueError("workers must be an integer from 1 to 16")
    roots = [_path(str(root), Path.cwd(), {}, None) for root in roots]
    if not roots:
        raise ValueError("Declare at least one allowed input root")
    base = _path(str(base), Path.cwd(), {}, roots)
    mappings = {}
    for prefix, root in (reference_roots or {}).items():
        if (not isinstance(prefix, str) or not prefix or prefix in {".", ".."}
                or PureWindowsPath(prefix).anchor or len(PureWindowsPath(prefix).parts) != 1
                or ":" in prefix or "/" in prefix or "\\" in prefix
                or prefix.casefold() in {key.casefold() for key in mappings}):
            raise ValueError("Reference prefixes must be simple and unique ignoring case")
        mappings[prefix] = _path(str(root), base, {}, roots)
    receipt = {"schemaVersion": 1, "generatedAtUtc": now(), "status": HOLD,
               "issues": [], "references": [], "referenceRoots": {k: str(v) for k, v in mappings.items()},
               "base": str(base), "allowedRoots": [str(root) for root in roots],
               "geographicCreditAdded": 0, "assetsChanged": False,
               "ledgersChanged": False, "qualification": "Complete explicit-pin bookkeeping only; independent source/GPS QA and final sealing remain required."}
    issues = receipt["issues"]

    def finish(scope=None):
        receipt["elapsedSeconds"] = round(time.monotonic() - started, 3)
        return {"scope": scope, "receipt": receipt}

    try:
        if not isinstance(scope_pin, dict) or not isinstance(scope_pin.get("sha256"), str) or not HASH.fullmatch(scope_pin["sha256"]):
            raise ValueError("invalid_scope_pin")
        source = _path(scope_pin.get("file"), base, mappings, roots)
        before = source.stat()
        raw = source.read_bytes()
        after = source.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise ValueError("scope_changed_while_reading")
        if hashlib.sha256(raw).hexdigest() != scope_pin["sha256"]:
            raise ValueError("scope_hash_mismatch")
        for key in ("bytes", "size"):
            if key in scope_pin and (type(scope_pin[key]) is not int or scope_pin[key] != len(raw)):
                raise ValueError("scope_declared_size_mismatch")
        original = _finite_json(raw)
        if not isinstance(original, dict) or AUDIT_KEY in original:
            raise ValueError("scope_not_object_or_reserved_audit_field_present")
        receipt["originalScope"] = {"file": str(source), "sha256": scope_pin["sha256"]}
    except (ValueError, OSError, UnicodeError) as error:
        issues.append({"code": str(error)})
        return finish()
    copied = copy.deepcopy(original)
    explicit = dict(_references(copied))
    overrides = {}
    if not isinstance(bindings, (list, tuple)):
        issues.append({"code": "invalid_binding_list"})
        return finish()
    for binding in bindings:
        if not isinstance(binding, dict) or not isinstance(binding.get("pointer"), str):
            issues.append({"code": "invalid_binding"})
            continue
        pointer = binding["pointer"]
        if pointer in overrides:
            issues.append({"code": "duplicate_binding", "pointer": pointer})
        overrides[pointer] = binding
        declared = explicit.get(pointer)
        target = binding.get("target")
        if (declared is None or binding.get("declaredFile") != declared.get("file")
                or binding.get("sha256") != declared.get("sha256")
                or not isinstance(target, dict) or target.get("sha256") != declared.get("sha256")):
            issues.append({"code": "binding_does_not_match_exact_original_pin", "pointer": pointer})
    if issues:
        receipt.update({"explicitPinReferences": len(explicit), "distinctFilesHashed": 0})
        return finish()
    unique = {}
    audit = []
    for pointer, item in explicit.items():
        try:
            expected = item["sha256"]
            if not isinstance(expected, str) or not HASH.fullmatch(expected):
                raise ValueError("invalid_pin_hash")
            declared = item["file"]
            resolved = _path(declared, base, mappings, roots)
            if pointer in overrides:
                target = overrides[pointer].get("target", {})
                if target.get("sha256") != expected:
                    raise ValueError("binding_target_hash_differs")
                resolved = _path(target.get("file"), base, mappings, roots)
            unique.setdefault(resolved, set()).add(expected)
            audit.append({"pointer": pointer, "declaredFile": declared, "resolvedFile": str(resolved),
                          "expectedSha256": expected, "explicitBinding": pointer in overrides})
            item["file"] = str(resolved)
        except ValueError as error:
            issues.append({"code": str(error), "pointer": pointer})
    # Rejected paths are never hashed. No file contents are parsed here.
    with ThreadPoolExecutor(max_workers=workers) as pool:
        facts = dict(zip(unique, pool.map(_fingerprint, unique)))
    for path, expected in unique.items():
        fact = facts[path]
        if "error" in fact or len(expected) != 1 or fact.get("sha256") not in expected:
            issues.append({"code": fact.get("error", "pin_hash_mismatch_or_conflict"), "file": str(path)})
    for row in audit:
        item = explicit[row["pointer"]]
        fact = facts[Path(row["resolvedFile"])]
        items = [item]
        if row["pointer"] in overrides:
            items.append(overrides[row["pointer"]]["target"])
        for declared in items:
            for key in ("bytes", "size"):
                if key in declared and (type(declared[key]) is not int or declared[key] < 0 or declared[key] != fact.get("bytes")):
                    issues.append({"code": "declared_size_mismatch", "pointer": row["pointer"], "field": key})
    receipt.update({"references": audit, "explicitPinReferences": len(explicit), "distinctFilesHashed": len(unique)})
    if not _unchanged(receipt["originalScope"]):
        issues.append({"code": "scope_changed_during_preflight"})
    if issues:
        return finish()
    receipt["status"] = OK
    copied[AUDIT_KEY] = {"schemaVersion": 1, "originalScope": receipt["originalScope"],
                         "base": receipt["base"], "referenceRoots": receipt["referenceRoots"],
                         "references": audit, "qualification": receipt["qualification"]}
    return finish(copied)


def publish(result, *, output, receipt):
    """Stage both documents, then exclusively publish a stable scope snapshot.

    Only this invocation's staged/output file can be removed on failure.
    """
    output, receipt = Path(output), Path(receipt)
    roots = [Path(root) for root in result["receipt"]["allowedRoots"]]
    base = Path(result["receipt"]["base"])
    output = _path(str(output), base, {}, roots)
    receipt = _path(str(receipt), base, {}, roots)
    if output == receipt or output.exists() or receipt.exists():
        raise ValueError("Use distinct fresh scope and receipt outputs")
    scope = result["scope"]
    if scope is None:
        write(receipt, result["receipt"])
        return None
    output.parent.mkdir(parents=True, exist_ok=True)
    receipt.parent.mkdir(parents=True, exist_ok=True)
    serialized = json.dumps(scope, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    fd, stage_name = tempfile.mkstemp(prefix=output.name + ".", dir=output.parent)
    stage = Path(stage_name)
    published = receipt_published = False
    receipt_stage = None
    def remove_owned(stage, destination):
        try:
            if destination.samefile(stage):
                destination.unlink()
        except FileNotFoundError:
            pass
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(serialized)
            handle.flush()
            os.fsync(handle.fileno())
        with tempfile.TemporaryDirectory(prefix=receipt.name + ".", dir=receipt.parent) as directory:
            receipt_stage = Path(directory) / "receipt.json"
            try:
                write(receipt_stage, result["receipt"])
                origin = result["receipt"]["originalScope"]
                if not _unchanged(origin):
                    raise ValueError("scope_changed_before_publication")
                os.link(stage, output)  # Atomic exclusive creation; never replaces a racing file.
                published = True
                if not _unchanged(origin):
                    raise ValueError("scope_changed_during_publication")
                os.link(receipt_stage, receipt)
                receipt_published = True
                if not _unchanged(origin):
                    raise ValueError("scope_changed_during_publication")
                return {"file": str(output.resolve()), "sha256": hashlib.sha256(serialized.encode("utf-8")).hexdigest()}
            except BaseException as error:
                if published:
                    remove_owned(stage, output)
                if receipt_published:
                    remove_owned(receipt_stage, receipt)
                if isinstance(error, ValueError) and str(error).startswith("scope_changed") and not receipt.exists():
                    held = copy.deepcopy(result["receipt"])
                    held["status"] = HOLD
                    held["issues"].append({"code": str(error)})
                    write(receipt, held)
                raise
    finally:
        stage.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("scope", "base", "output", "receipt"):
        parser.add_argument("--" + flag, type=Path, required=True)
    parser.add_argument("--scope-sha", required=True)
    parser.add_argument("--allow-root", type=Path, action="append", required=True)
    parser.add_argument("--reference-root", action="append", default=[], metavar="PREFIX=DIR")
    parser.add_argument("--bindings", type=Path)
    parser.add_argument("--bindings-sha")
    parser.add_argument("--workers", type=int, default=4)
    args = parser.parse_args()
    if bool(args.bindings) != bool(args.bindings_sha):
        parser.error("Provide bindings file and its SHA together")
    mappings = {}
    for assignment in args.reference_root:
        prefix, separator, directory = assignment.partition("=")
        if not separator or not directory or prefix.casefold() in {key.casefold() for key in mappings}:
            parser.error("Reference roots must be unique PREFIX=DIR assignments")
        mappings[prefix] = Path(directory)
    bindings = []
    roots = [_path(str(root), Path.cwd(), {}, None) for root in args.allow_root]
    try:
        output = _path(str(args.output), args.base, {}, roots)
        receipt = _path(str(args.receipt), args.base, {}, roots)
        if output == receipt or output.exists() or receipt.exists():
            raise ValueError("Use distinct fresh scope and receipt outputs")
        if args.bindings:
            path = _path(str(args.bindings), args.base, {}, roots)
            raw = path.read_bytes()
            if not HASH.fullmatch(args.bindings_sha) or hashlib.sha256(raw).hexdigest() != args.bindings_sha:
                raise ValueError("Binding manifest hash differs")
            bindings = _finite_json(raw)
            if not isinstance(bindings, list):
                raise ValueError("Binding manifest must be a list of exact bindings")
        result = canonical_scope({"file": str(args.scope), "sha256": args.scope_sha},
                                 base=args.base, roots=roots, reference_roots=mappings,
                                 bindings=bindings, workers=args.workers)
        published = publish(result, output=output, receipt=receipt)
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(json.dumps({"status": result["receipt"]["status"], "issues": result["receipt"]["issues"],
                      "scope": published, "geographicCreditAdded": 0}))
    raise SystemExit(0 if published is not None else 2)


if __name__ == "__main__":
    main()
