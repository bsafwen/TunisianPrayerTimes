"""Prepare write-once, pinned offline review plans for the existing engine.

This module never executes a supplied command, imports its modules, reads review
statuses, resumes a workspace, or installs assets. Execution belongs to the
existing ``run-plan`` workflow after the user resumes geographic work.

Spec contract: ``governorate``, ``inputScope`` (``source-only`` or ``live-assets``),
and ``jobs``. Runnable jobs explicitly supply the engine's ``id``, ``kind``
(``offline``), ``argv``, absolute ``cwd``, ``inputs``, ``outputs``, ``deps`` and
``resources``. ``executableDependencies`` declares file/sha256 pins for *all*
locally executed or imported scripts/modules; these are merged into inputs.
The entry script or local -m module must be declared. Manifest completeness for
dynamic/transitive imports is the caller's responsibility, not inferred here.
Python's interpreter/standard library are the ambient runtime, not a local
script dependency. Other direct executables must themselves be declared.

The caller selects live-asset or source-only snapshot files and pins only the
files each job consumes; this adapter does not create a snapshot or inject all
repository assets/helpers. Every input must exist now. For a later produced
input, prepare the next plan once its exact output hash is known. Existing
results use only the engine's explicit ``importedOutputs`` file/sha256 contract.
No report label awards acceptance, verification credit, or cache success.

Optional ``workWindow`` requires ``policyRevision: 1``, RFC3339 ``safeStartUtc``
(latest permitted start), ``hardDeadlineUtc``, numeric ``minimumRemainingSeconds``
at least 120, and a fresh absolute ``executionReceipt`` with an existing parent.
It supports only runnable ``readOnlyOffline: true`` jobs with ``reviewStage``
``preflight`` or ``replay``. These children must not spawn unsupervised descendants.
Guarded jobs cannot import outputs. A required fresh current replay needs a new
job id, state/output locations and receipt; rechecking completed state is not a
new execution or current acceptance.
The engine guards the direct child in process; recorder/refresh mutation remains
an explicit sole-writer operation outside this read-only contract.

``contentKey`` hashes each job's argv, cwd and declared input pins, plus exact
imported-output pins or the complete guarded launch policy/qualification. The
engine still owns dependency/resource scheduling and stale-result checks, and
requires a new job id/revision after a job definition changes. Prepared JSON is
written with exclusive creation; preparing the same output again is an error.
The API returns a detached JSON-compatible value; it does not freeze Python
objects or protect a saved file from external edits.

CLI: python -m scripts.locality_automation.review_jobs --spec SPEC --output PLAN
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
from typing import Any

from . import engine
from .common import pin, read, write


_SPEC_FIELDS = {"schemaVersion", "governorate", "inputScope", "jobs"}
_JOB_FIELDS = {
    "id", "kind", "argv", "cwd", "inputs", "outputs", "deps", "resources",
    "timeoutSeconds", "governorate", "importedOutputs", "executableDependencies",
    "workWindow", "readOnlyOffline", "reviewStage",
}
_REQUIRED = {"id", "kind", "cwd", "inputs", "outputs", "deps", "resources"}
_SCRIPT_SUFFIXES = {".py", ".pyw", ".ps1", ".sh", ".bat", ".cmd", ".js", ".mjs"}


def _fields(value: Any, allowed: set[str], where: str) -> dict:
    if not isinstance(value, dict):
        raise ValueError(f"{where} must be an object")
    extra = set(value) - allowed
    if extra:
        raise ValueError(f"{where} has unsupported fields: {', '.join(sorted(extra))}")
    return value


def _path(raw: Any, base: Path, where: str) -> Path:
    if not isinstance(raw, str) or not raw or "\0" in raw:
        raise ValueError(f"{where} must be a non-empty path string")
    path = Path(raw)
    return (path if path.is_absolute() else base / path).resolve()


def _pins(items: Any, base: Path, where: str, *, imported: bool = False) -> list[dict]:
    if not isinstance(items, list):
        raise ValueError(f"{where} must be a list of file/sha256 pins")
    found = {}
    for index, item in enumerate(items):
        label = f"{where}[{index}]"
        _fields(item, {"file", "sha256"}, label)
        path = _path(item.get("file"), base, label + ".file")
        sha = item.get("sha256")
        if not isinstance(sha, str) or not re.fullmatch(r"[a-fA-F0-9]{64}", sha):
            raise ValueError(f"{label}.sha256 must be an exact SHA-256")
        if imported:
            raw_path = Path(item["file"])
            raw_path = raw_path if raw_path.is_absolute() else base / raw_path
            if raw_path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
                raise ValueError(f"{label} must pin a regular nonempty imported output")
        actual = pin(path)
        if actual["sha256"] != sha.lower():
            raise ValueError(f"File changed or missing: {path}")
        key = os.path.normcase(str(path))
        if key in found and found[key] != actual:
            raise ValueError(f"Conflicting input fingerprints: {path}")
        found[key] = actual
    return sorted(found.values(), key=lambda item: os.path.normcase(item["file"]))


def _merge_verified_pins(*groups: list[dict]) -> list[dict]:
    """Combine this preparation call's verified pins without rereading files."""
    found = {}
    for group in groups:
        for item in group:
            key = os.path.normcase(item["file"])
            if key in found and found[key]["sha256"] != item["sha256"]:
                raise ValueError(f"Conflicting input fingerprints: {item['file']}")
            found[key] = item
    return sorted(found.values(), key=lambda item: os.path.normcase(item["file"]))


def _entry_files(argv: list[str], base: Path) -> list[Path]:
    """Locate declared entry files without importing code or searching PATH."""
    if not argv or any(not isinstance(arg, str) or "\0" in arg for arg in argv):
        raise ValueError("argv must be a non-empty list of strings")
    executable = Path(argv[0])
    python = bool(re.fullmatch(r"(?:python(?:\d+(?:\.\d+)*)?|py)(?:\.exe)?", executable.name, re.I))
    if python:
        index = 1
        while index < len(argv):
            arg = argv[index]
            if arg == "-c":
                raise ValueError("Inline Python commands are not file-backed review jobs")
            if arg == "-m":
                module = argv[index + 1] if index + 1 < len(argv) else ""
                if not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*", module):
                    raise ValueError("-m requires a local dotted module name")
                target = base.joinpath(*module.split("."))
                source = target.with_suffix(".py")
                return [source.resolve() if source.is_file() else (target / "__main__.py").resolve()]
            if arg == "--":
                index += 1
                break
            if arg in {"-X", "-W", "--check-hash-based-pycs"}:
                index += 2
            elif arg.startswith("-"):
                index += 1
            else:
                break
        if index < len(argv) and argv[index] != "-":
            return [_path(argv[index], base, "argv script")]
        raise ValueError("Python review jobs require a declared script or local -m module")
    scripts = [_path(arg, base, "argv script") for arg in argv if Path(arg).suffix.lower() in _SCRIPT_SUFFIXES]
    if scripts:
        return scripts
    return [_path(argv[0], base, "argv executable")]


def build_review_plan(spec: dict) -> dict:
    """Validate current pins and return a deterministic existing-engine plan."""
    _fields(spec, _SPEC_FIELDS, "spec")
    schema = spec.get("schemaVersion", 1)
    if type(schema) is not int or schema != 1:
        raise ValueError("spec schemaVersion must be 1")
    scope = spec.get("inputScope")
    if not isinstance(scope, str) or scope not in {"source-only", "live-assets"}:
        raise ValueError("inputScope must explicitly be source-only or live-assets")
    jobs = spec.get("jobs")
    if not isinstance(jobs, list):
        raise ValueError("spec jobs must be a list")
    plan = {
        "schemaVersion": 1, "governorate": spec.get("governorate"),
        "inputScope": scope, "jobs": [],
        "qualification": "Offline review preparation; engine success/import is not geographic acceptance or verification credit.",
    }
    for index, supplied in enumerate(jobs):
        where = f"jobs[{index}]"
        _fields(supplied, _JOB_FIELDS, where)
        missing = _REQUIRED - set(supplied)
        if missing:
            raise ValueError(f"{where} requires: {', '.join(sorted(missing))}")
        if supplied["kind"] != "offline":
            raise ValueError(f"{where}.kind must be offline")
        raw_cwd = supplied["cwd"]
        if not isinstance(raw_cwd, str) or not Path(raw_cwd).is_absolute():
            raise ValueError(f"{where}.cwd must be an absolute directory")
        cwd = _path(raw_cwd, Path.cwd(), where + ".cwd")
        if not cwd.is_dir():
            raise ValueError(f"{where}.cwd is not a directory")
        # Copy only explicit fields; neither prior statuses nor sibling assets enter jobs.
        job = json.loads(json.dumps(supplied, allow_nan=False))
        job["cwd"] = str(cwd)
        executable_pins = _pins(job.pop("executableDependencies", []), cwd, where + ".executableDependencies")
        inputs = _pins(job["inputs"], cwd, where + ".inputs")
        job["inputs"] = _merge_verified_pins(inputs, executable_pins)
        if not isinstance(job["outputs"], list):
            raise ValueError(f"{where}.outputs must be a list")
        job["outputs"] = [str(_path(raw, cwd, where + ".outputs")) for raw in job["outputs"]]
        imported_pins = _pins(job.get("importedOutputs", []), cwd, where + ".importedOutputs", imported=True)
        if imported_pins:
            if job["outputs"]:
                raise ValueError(f"{where}: imported results must pin every output in importedOutputs")
            job["importedOutputs"] = imported_pins
        else:
            argv = job.get("argv")
            if not isinstance(argv, list):
                raise ValueError(f"{where}.argv must be a list")
            if not executable_pins:
                raise ValueError(f"{where} requires explicit executableDependencies pins")
            declared = {os.path.normcase(item["file"]) for item in executable_pins}
            for entry in _entry_files(argv, cwd):
                if os.path.normcase(str(entry)) not in declared:
                    raise ValueError(f"{where} entry executable is not declared and pinned: {entry}")
        if executable_pins:
            job["executableDependencies"] = executable_pins
        if "workWindow" in job:
            engine._validate_work_window(job, where)
            receipt = Path(job["workWindow"]["executionReceipt"])
            if receipt.exists() or receipt.is_symlink():
                raise ValueError(f"{where}.executionReceipt must be fresh: {receipt}")
            job["workWindow"]["executionReceipt"] = str(receipt.resolve())
        identity = {key: job[key] for key in ("cwd", "inputs")}
        identity["argv"] = job.get("argv", [])
        if imported_pins:
            identity["importedOutputs"] = imported_pins
        if "workWindow" in job:
            identity.update({key: job[key] for key in ("workWindow", "readOnlyOffline", "reviewStage")})
        blob = json.dumps(identity, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
        job["contentKey"] = hashlib.sha256(blob.encode("utf-8")).hexdigest()
        plan["jobs"].append(job)
    engine.validate_plan(plan)
    return plan


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Prepare a pinned offline review plan without executing it.")
    parser.add_argument("--spec", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args(argv)
    output = Path(args.output)
    if output.exists() or output.is_symlink():
        parser.error(f"output already exists: {output}")
    try:
        plan = build_review_plan(read(args.spec))
        destination = output.resolve()
        for job in plan["jobs"]:
            for _, result in engine._output_entries(job, Path(job["cwd"])):
                if destination == result or destination.is_relative_to(result) or result.is_relative_to(destination):
                    raise ValueError(f"plan output overlaps a declared job output: {result}")
        receipt = write(output, plan)
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    imported = sum(bool(job.get("importedOutputs")) for job in plan["jobs"])
    print(json.dumps({"jobs": len(plan["jobs"]), "offline": len(plan["jobs"]) - imported,
                      "imported": imported, "plan": receipt}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
