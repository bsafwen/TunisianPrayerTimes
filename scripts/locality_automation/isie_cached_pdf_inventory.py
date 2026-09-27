"""Inventory locally cached ISIE PDFs without making network requests.

The plan step hashes and deduplicates saved PDFs, then marks an existing
per-hash inventory for reuse or a missing one for inspection. The run step
calls :mod:`isie_pdf_inventory` only for missing outputs. Each result is
written atomically, so an interrupted run can be started again safely.

Example::

    python -m scripts.locality_automation.isie_cached_pdf_inventory \
        --plan --source-root cache/batches --inventory-dir cache/inventories \
        --plan-file cache/inventory-plan.json
    python -m scripts.locality_automation.isie_cached_pdf_inventory \
        --run --plan-file cache/inventory-plan.json --workers 2

This records native PDF contents and embedded georeferences only. It does not
establish official identity, accepted registration, or boundary correctness.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ProcessPoolExecutor, as_completed
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from typing import Any


SCHEMA_VERSION = 1
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_INVENTORY_STATUS = "READ_ONLY_SOURCE_ONLY_UNREVIEWED"


def _json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True,
                        allow_nan=False) + "\n").encode("utf-8")


def _atomic_write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(
        prefix=path.name + ".", suffix=".tmp", dir=str(path.parent)
    )
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(_json_bytes(value))
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        if os.name != "nt":
            directory_fd = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
    except BaseException:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _is_unc(path: Path) -> bool:
    return str(path).startswith(("\\\\", "//"))


def _read_existing_inventory(path: Path, source_hash: str) -> tuple[bool, str | None]:
    if not path.exists():
        return False, None
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
    except Exception as exc:
        return False, "existing inventory is unreadable: " + str(exc)
    if not isinstance(value, dict):
        return False, "existing inventory is not a JSON object"
    source = value.get("sourcePdf")
    if (value.get("status") != _INVENTORY_STATUS
            or not isinstance(source, dict)
            or source.get("sha256") != source_hash):
        return False, "existing inventory does not match the source PDF hash and status"
    return True, None


def _scan_roots(source_roots: list[Path]) -> tuple[list[dict], list[dict], int, int]:
    """Hash local PDFs and return deterministic unique-source entries."""
    files_by_path: dict[str, Path] = {}
    scan_failures: list[dict] = []
    roots = sorted({root.resolve() for root in source_roots}, key=lambda p: os.path.normcase(str(p)))
    for root in roots:
        if not root.is_dir():
            scan_failures.append({"path": str(root), "error": "source root is not a directory"})
            continue
        if _is_unc(root):
            scan_failures.append({"path": str(root), "error": "UNC/network source roots are not allowed"})
            continue

        def on_walk_error(exc: OSError, root_path: Path = root) -> None:
            scan_failures.append({"path": str(getattr(exc, "filename", root_path)),
                                  "error": type(exc).__name__ + ": " + str(exc)})

        for directory, dirnames, filenames in os.walk(root, onerror=on_walk_error, followlinks=False):
            dirnames.sort(key=os.path.normcase)
            for filename in sorted(filenames, key=os.path.normcase):
                if Path(filename).suffix.lower() != ".pdf":
                    continue
                candidate = Path(directory) / filename
                try:
                    if candidate.is_symlink():
                        resolved = candidate.resolve(strict=True)
                    else:
                        resolved = candidate.resolve()
                    if _is_unc(resolved):
                        scan_failures.append({"path": str(candidate),
                                              "error": "PDF resolves to a UNC/network path"})
                        continue
                    if not resolved.is_file():
                        scan_failures.append({"path": str(candidate), "error": "PDF is not a regular file"})
                        continue
                    files_by_path.setdefault(os.path.normcase(str(resolved)), resolved)
                except OSError as exc:
                    scan_failures.append({"path": str(candidate),
                                          "error": type(exc).__name__ + ": " + str(exc)})

    by_hash: dict[str, list[Path]] = {}
    for path in sorted(files_by_path.values(), key=lambda p: os.path.normcase(str(p))):
        try:
            source_hash = _sha256(path)
        except OSError as exc:
            scan_failures.append({"path": str(path), "error": type(exc).__name__ + ": " + str(exc)})
            continue
        by_hash.setdefault(source_hash, []).append(path)

    entries = []
    for source_hash in sorted(by_hash):
        paths = sorted(by_hash[source_hash], key=lambda p: os.path.normcase(str(p)))
        entries.append({"sourcePdfSha256": source_hash,
                        "sourcePdf": str(paths[0]),
                        "sourcePaths": [str(path) for path in paths]})
    scan_failures.sort(key=lambda row: (os.path.normcase(row["path"]), row["error"]))
    hashed_path_count = sum(len(paths) for paths in by_hash.values())
    return entries, scan_failures, len(files_by_path), hashed_path_count


def build_plan(source_roots: list[Path], inventory_dir: Path) -> dict:
    roots = sorted({root.resolve() for root in source_roots}, key=lambda p: os.path.normcase(str(p)))
    output_dir = inventory_dir.resolve()
    if _is_unc(output_dir):
        raise ValueError("UNC/network inventory directories are not allowed")
    entries, scan_failures, source_path_count, hashed_path_count = _scan_roots(roots)
    items = []
    for entry in entries:
        source_hash = entry["sourcePdfSha256"]
        output = output_dir / (source_hash + ".json")
        exists, error = _read_existing_inventory(output, source_hash)
        items.append({**entry, "inventoryFile": str(output),
                      "plannedAction": "skip_existing" if exists else "blocked_existing" if error else "inspect",
                      "existingInventoryError": error})
    counts = {
        "sourcePdfPaths": source_path_count,
        "hashedSourcePdfPaths": hashed_path_count,
        "uniqueSourcePdfs": len(items),
        "duplicateSourcePaths": hashed_path_count - len(items),
        "existingInventories": sum(item["plannedAction"] == "skip_existing" for item in items),
        "blockedExistingInventories": sum(item["plannedAction"] == "blocked_existing" for item in items),
        "missingInventories": sum(item["plannedAction"] == "inspect" for item in items),
        "scanFailures": len(scan_failures),
    }
    return {"schemaVersion": SCHEMA_VERSION, "inventoryDirectory": str(output_dir),
            "sourceRoots": [str(root) for root in roots], "counts": counts,
            "items": items, "scanFailures": scan_failures}


def _inspect_one(source_hash: str, source_paths: list[str], inventory_file: str) -> dict:
    output = Path(inventory_file)
    try:
        exists, error = _read_existing_inventory(output, source_hash)
        if exists:
            return {"sourcePdfSha256": source_hash, "status": "skipped_existing"}
        if error:
            return {"sourcePdfSha256": source_hash, "status": "failed",
                    "error": error, "inventoryFile": str(output)}

        source = None
        for raw_path in source_paths:
            candidate = Path(raw_path)
            if not candidate.is_file() or _is_unc(candidate.resolve()):
                continue
            if _sha256(candidate) == source_hash:
                source = candidate.resolve()
                break
        if source is None:
            raise ValueError("no planned source path still matches the PDF hash")

        # Import in the worker process; the inspector and its PDF libraries stay local.
        from scripts.locality_automation.isie_pdf_inventory import inspect

        report = inspect(source)
        if report.get("sourcePdf", {}).get("sha256") != source_hash:
            raise ValueError("inspector returned a different source PDF hash")
        if report.get("status") != _INVENTORY_STATUS:
            raise ValueError("inspector returned an unexpected status")
        _atomic_write_json(output, report)
        return {"sourcePdfSha256": source_hash, "status": "success",
                "pageCount": report.get("pageCount"), "inventoryFile": str(output)}
    except Exception as exc:
        return {"sourcePdfSha256": source_hash, "status": "failed",
                "error": type(exc).__name__ + ": " + str(exc), "inventoryFile": str(output)}


def _validate_plan(plan: Any) -> dict:
    if not isinstance(plan, dict) or plan.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError("unsupported or malformed inventory plan")
    output_dir = Path(plan.get("inventoryDirectory", "")).resolve()
    if not str(plan.get("inventoryDirectory", "")) or _is_unc(output_dir):
        raise ValueError("plan inventory directory is missing or remote")
    if not isinstance(plan.get("items"), list) or not isinstance(plan.get("sourceRoots"), list):
        raise ValueError("plan items and source roots must be lists")
    roots = [Path(root).resolve() for root in plan["sourceRoots"]]
    for item in plan["items"]:
        source_hash = item.get("sourcePdfSha256")
        if not isinstance(source_hash, str) or not _SHA256.fullmatch(source_hash):
            raise ValueError("plan contains an invalid source PDF hash")
        output = Path(item.get("inventoryFile", "")).resolve()
        expected = output_dir / (source_hash + ".json")
        if output != expected:
            raise ValueError("plan inventory output is outside the hash-keyed inventory directory")
        paths = item.get("sourcePaths")
        if not isinstance(paths, list) or not paths:
            raise ValueError("plan item has no source paths")
        for raw_path in paths:
            path = Path(raw_path).resolve()
            if _is_unc(path) or not any(path.is_relative_to(root) for root in roots):
                raise ValueError("plan source PDF is outside its local source roots")
    return plan


def run_plan(plan: dict, workers: int) -> dict:
    if workers < 1:
        raise ValueError("workers must be at least 1")
    plan = _validate_plan(plan)
    output_dir = Path(plan["inventoryDirectory"])
    results = []
    runnable = []
    for item in plan["items"]:
        output = Path(item["inventoryFile"])
        exists, error = _read_existing_inventory(output, item["sourcePdfSha256"])
        if exists:
            results.append({"sourcePdfSha256": item["sourcePdfSha256"], "status": "skipped_existing"})
        elif error:
            results.append({"sourcePdfSha256": item["sourcePdfSha256"], "status": "failed",
                            "error": error, "inventoryFile": str(output)})
        else:
            runnable.append(item)

    if runnable:
        output_dir.mkdir(parents=True, exist_ok=True)
        with ProcessPoolExecutor(max_workers=workers) as executor:
            futures = {
                executor.submit(_inspect_one, item["sourcePdfSha256"], item["sourcePaths"],
                                item["inventoryFile"]): item
                for item in runnable
            }
            for future in as_completed(futures):
                item = futures[future]
                try:
                    results.append(future.result())
                except Exception as exc:
                    results.append({"sourcePdfSha256": item["sourcePdfSha256"], "status": "failed",
                                    "error": type(exc).__name__ + ": " + str(exc),
                                    "inventoryFile": item["inventoryFile"]})

    results.sort(key=lambda row: row["sourcePdfSha256"])
    failures = [{key: row[key] for key in ("sourcePdfSha256", "inventoryFile", "error") if key in row}
                for row in results if row["status"] == "failed"]
    counts = {
        **plan["counts"],
        "inventoriesSkipped": sum(row["status"] == "skipped_existing" for row in results),
        "inventoriesWritten": sum(row["status"] == "success" for row in results),
        "failures": len(failures) + len(plan.get("scanFailures", [])),
        "remainingWithoutInventory": sum(row["status"] == "failed" for row in results),
    }
    manifest = {"schemaVersion": SCHEMA_VERSION,
                "status": "complete_with_failures" if counts["failures"] else "complete",
                "inventoryDirectory": plan["inventoryDirectory"],
                "sourceRoots": plan["sourceRoots"], "counts": counts,
                "failures": failures, "scanFailures": plan.get("scanFailures", [])}
    _atomic_write_json(output_dir / "cached-pdf-inventory-manifest.json", manifest)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--plan", action="store_true", help="scan roots and save a deterministic plan")
    mode.add_argument("--run", action="store_true", help="inspect only missing per-hash inventories")
    parser.add_argument("--source-root", action="append", type=Path, default=[],
                        help="local directory to scan; repeat for additional roots")
    parser.add_argument("--inventory-dir", type=Path,
                        help="directory for <sha256>.json inventories and the run manifest")
    parser.add_argument("--plan-file", type=Path, required=True,
                        help="plan path to write with --plan or read with --run")
    parser.add_argument("--workers", type=int, default=2,
                        help="concurrent local PDF inspectors (default: 2)")
    args = parser.parse_args()

    if args.workers < 1:
        parser.error("--workers must be at least 1")
    plan_path = args.plan_file.resolve()
    if _is_unc(plan_path):
        parser.error("UNC/network plan paths are not allowed")

    if args.plan:
        if not args.source_root:
            parser.error("--plan requires at least one --source-root")
        if args.inventory_dir is None:
            parser.error("--plan requires --inventory-dir")
        if any(_is_unc(root.resolve()) for root in args.source_root):
            parser.error("UNC/network source roots are not allowed")
        plan = build_plan(args.source_root, args.inventory_dir)
        _atomic_write_json(plan_path, plan)
        print(json.dumps({"mode": "plan", "planFile": str(plan_path),
                          "counts": plan["counts"]}, sort_keys=True))
        return

    if args.source_root or args.inventory_dir is not None:
        parser.error("--run reads source roots and inventory directory from --plan-file")
    plan = json.loads(plan_path.read_text(encoding="utf-8-sig"))
    manifest = run_plan(plan, args.workers)
    print(json.dumps({"mode": "run", "manifestFile": str(
        Path(manifest["inventoryDirectory"]) / "cached-pdf-inventory-manifest.json"),
        "status": manifest["status"], "counts": manifest["counts"]}, sort_keys=True))


if __name__ == "__main__":
    main()
