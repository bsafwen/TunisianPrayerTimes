#!/usr/bin/env python3
"""Build and run focused, parallel locality reviews after the automatic pass."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

from locality_automation import engine
from locality_automation.common import now, pin, read, read_pin, write
from locality_automation.display_collisions import (
    DEFAULT_BINARY, DEFAULT_METADATA, audit_display_collisions,
)
from locality_automation.review_publishing import publish_review_queue
from locality_automation.review_queue import build_review_queue
from locality_automation.review_workers import run_review_queue
from locality_automation.settings import snapshot
from locality_automation.workflow import feedback


_SHA256 = re.compile(r"[a-f0-9]{64}\Z")


def _batch_paths(workspace: Path, ledger: dict) -> list[Path]:
    batch_root = (workspace / "batches").resolve()
    paths = set()
    for row in ledger.get("cases", {}).values():
        path = Path(row["batch"]).resolve()
        if not path.is_relative_to(batch_root) or path == batch_root:
            raise ValueError(f"Case ledger references a batch outside {batch_root}: {path}")
        paths.add(path)
    return sorted(paths)


def _records(workspace: Path, ledger: dict) -> tuple[list[dict], list[dict]]:
    cases, packets = [], []
    for batch in _batch_paths(workspace, ledger):
        source = read(batch / "cases.json")
        report = read(batch / "batch-report.json")
        prior = {}
        for advice in report.get("adviceFiles", []):
            request_file = Path(advice["file"]).parent / "request.json"
            if request_file.is_file():
                request = read(request_file)
                prior[request["caseId"]] = (request, advice)
        for case in source["cases"]:
            row = dict(case)
            row["batchPath"] = str(batch)
            row["_queueBatchPath"] = str(batch)
            row["catalogPin"] = source.get("catalog")
            if case["id"] in prior:
                request, advice = prior[case["id"]]
                row["priorPacketPin"] = request["packet"]
                row["advicePin"] = advice
            cases.append(row)
        # Packet/advice pins are associated with their case via request.json.
        # A batch-level packetFiles list has no case IDs, so passing it through
        # would inflate the queue without establishing usable evidence links.
    return cases, packets


def _governorate(snap: dict, requested: str | None) -> str:
    order = _ordered_governorates(snap)
    if not order:
        raise ValueError("The accepted governorate order is empty")
    governorate = requested or order[0]
    if governorate not in order:
        raise ValueError(f"Unknown governorate: {governorate}")
    return governorate


def _ordered_governorates(snap: dict) -> list[str]:
    """Read and validate the governorate sequence from the accepted queue index."""
    rows = read_pin(snap["queueIndex"]).get("orderedGovernorates")
    if not isinstance(rows, list):
        raise ValueError("The accepted queue index has no ordered governorate list")
    order = []
    for row in rows:
        governorate = row.get("governorateAr") if isinstance(row, dict) else None
        if not isinstance(governorate, str) or not governorate.strip():
            raise ValueError("The accepted queue index contains an invalid governorate")
        if governorate in order:
            raise ValueError(f"The accepted queue index repeats governorate: {governorate}")
        order.append(governorate)
    return order


def _prepare_all_summary_row(governorate: str, queue: dict, publication: dict) -> dict:
    counts = queue.get("counts", {})
    files = publication.get("files", [])
    published = next((row for row in files if row.get("governorate") == governorate), {})
    return {
        "governorate": governorate,
        "activeCaseCount": counts.get("caseCount", 0),
        "selectableCatalogCaseCount": counts.get("selectableCatalogCaseCount", 0),
        "unmappedMissingCatalogCaseCount": counts.get("unmappedMissingCatalogCaseCount", 0),
        "activeUnitCount": counts.get("reviewUnitCount", 0),
        "modelEligibleUnitCount": counts.get("modelEligible", 0),
        "currentCollisionGroupCount": counts.get("displayCollisionGroups", 0),
        "currentCollisionReviewUnitCount": counts.get("displayCollisionReviewUnits", 0),
        "historicalAuditCount": counts.get("historicalGroupedMemberAuditCount", 0),
        "publicationPaths": {
            "json": published.get("json"),
            "html": published.get("html"),
        },
    }


def prepare_all(config: dict) -> dict:
    """Publish every accepted governorate queue, checkpointing after each one.

    This command deliberately calls only ``prepare``; it does not dispatch
    model workers. The caller must hold the controller lock for the entire run.
    """
    workspace = Path(config["workspace"]).resolve()
    review_root = workspace / "review"
    review_root.mkdir(parents=True, exist_ok=True)
    summary_path = review_root / "prepare-all-summary.json"
    accepted_snapshot = snapshot(config)
    order = _ordered_governorates(accepted_snapshot)
    if not order:
        raise ValueError("The accepted governorate order is empty")

    summary = {
        "schemaVersion": 1,
        "status": "RUNNING",
        "startedAt": now(),
        "acceptedQueueIndex": accepted_snapshot["queueIndex"],
        "governorateOrder": order,
        "totalGovernorates": len(order),
        "completedGovernorates": 0,
        "currentlyPreparing": order[0],
        "results": [],
    }
    write(summary_path, summary, replace=True)

    for index, governorate in enumerate(order):
        summary["currentlyPreparing"] = governorate
        write(summary_path, summary, replace=True)
        try:
            queue, publication, _output_dir = prepare(config, governorate)
        except Exception as exc:
            summary["status"] = "FAILED"
            summary["failedGovernorate"] = governorate
            summary["failure"] = f"{type(exc).__name__}: {exc}"
            summary["failedAt"] = now()
            write(summary_path, summary, replace=True)
            raise

        summary["results"].append(_prepare_all_summary_row(governorate, queue, publication))
        summary["completedGovernorates"] = index + 1
        summary["currentlyPreparing"] = None
        summary["nextGovernorate"] = order[index + 1] if index + 1 < len(order) else None
        summary["updatedAt"] = now()
        write(summary_path, summary, replace=True)

    summary["status"] = "COMPLETE"
    summary["completedAt"] = now()
    summary["summaryPath"] = str(summary_path.resolve())
    write(summary_path, summary, replace=True)
    return summary


def _attach_advisories(queue: dict, review_root: Path) -> int:
    """Show only current, advisory-only Luna results beside their queue units."""
    cases_root = review_root / "advisories" / queue["governorate"] / "cases"
    attached = 0
    for item in queue["items"]:
        case_id = item["caseIds"][0]
        path = cases_root / (hashlib.sha256(case_id.encode("utf-8")).hexdigest() + ".json")
        if not path.is_file():
            continue
        try:
            saved = read(path)
        except (OSError, ValueError, TypeError):
            continue
        if not isinstance(saved, dict):
            continue
        if (saved.get("status") != "advisory"
                or saved.get("reviewUnitId") != item["id"]
                or saved.get("sourceFingerprint") != item["sourceFingerprint"]
                or saved.get("acceptance") != "ADVISORY_ONLY"):
            continue
        result = saved.get("advice")
        attempts = result.get("attempts", []) if isinstance(result, dict) else []
        advice = next((row.get("advice") for row in attempts
                       if isinstance(row, dict) and row.get("provider") == "gpt-6-luna"
                       and row.get("status") == "advisory"), None)
        if isinstance(advice, dict):
            item["latestAdvisory"] = advice
            attached += 1
    return attached


def _workspace_file(workspace: Path, raw_file: object) -> tuple[Path | None, str]:
    """Resolve a referenced file and reject anything outside this task workspace."""
    if not isinstance(raw_file, str) or not raw_file.strip():
        return None, "invalid_path"
    try:
        root = workspace.resolve(strict=True)
        path = Path(raw_file).resolve(strict=True)
    except (OSError, RuntimeError, ValueError):
        return None, "missing"
    if path == root or not path.is_relative_to(root):
        return None, "outside_workspace"
    if not path.is_file():
        return None, "not_file"
    return path, "contained"


def _read_pinned_packet(workspace: Path, packet_pin: object) -> tuple[dict | None, str]:
    """Read only an explicitly pinned packet after containment and hash checks."""
    if not isinstance(packet_pin, dict):
        return None, "invalid_pin"
    expected = packet_pin.get("sha256")
    if not isinstance(expected, str) or not _SHA256.fullmatch(expected):
        return None, "invalid_sha256"
    path, state = _workspace_file(workspace, packet_pin.get("file"))
    if path is None:
        return None, state
    try:
        raw = path.read_bytes()
    except OSError:
        return None, "missing"
    if hashlib.sha256(raw).hexdigest() != expected:
        return None, "hash_mismatch"
    try:
        packet = json.loads(raw.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None, "invalid_json"
    if not isinstance(packet, dict):
        return None, "invalid_packet"
    return packet, "verified"


def _verify_cached_asset(workspace: Path, reference: object) -> tuple[Path | None, str]:
    """Verify one declared cached asset without parsing or copying its contents."""
    if not isinstance(reference, dict):
        return None, "invalid_pin"
    path, state = _workspace_file(workspace, reference.get("file"))
    if path is None:
        return None, state
    expected = reference.get("sha256")
    if not isinstance(expected, str) or not _SHA256.fullmatch(expected):
        return None, "invalid_sha256"
    try:
        with path.open("rb") as handle:
            actual = hashlib.file_digest(handle, "sha256").hexdigest()
    except OSError:
        return None, "missing"
    if actual != expected:
        return None, "hash_mismatch"
    return path, "verified"


def _attach_cached_official_sources(queue: dict, workspace: Path) -> int:
    """Attach verified official PDFs and thumbnails from each item's pinned packets."""
    attached = 0
    for item in queue.get("items", []):
        if not isinstance(item, dict):
            continue
        case_ids = set(case_id for case_id in item.get("caseIds", []) if isinstance(case_id, str))
        packet_pins = item.get("priorPacketPins")
        if not case_ids or not isinstance(packet_pins, list) or not packet_pins:
            continue

        source_states = []
        links = []
        seen_hrefs = set()
        for packet_pin in packet_pins:
            if not isinstance(packet_pin, dict) or packet_pin.get("kind") != "prior_packet":
                continue
            case_id = packet_pin.get("caseId")
            if not isinstance(case_id, str) or case_id not in case_ids:
                continue
            packet, packet_state = _read_pinned_packet(workspace, packet_pin)
            source_states.append({
                "caseId": case_id,
                "packetSha256": packet_pin.get("sha256"),
                "status": packet_state,
            })
            if packet is None:
                continue

            evidence = packet.get("evidence")
            polygon = evidence.get("polygonPacket") if isinstance(evidence, dict) else None
            records = polygon.get("records") if isinstance(polygon, dict) else None
            if not isinstance(records, list):
                continue
            for record in records:
                record_id = record.get("id") if isinstance(record, dict) else None
                if not isinstance(record_id, str) or record_id not in case_ids:
                    continue
                for field, label in (("sourcePDF", "Official source PDF"), ("thumbnail", "Official source map thumbnail")):
                    reference = record.get(field)
                    if not isinstance(reference, dict) or not ("file" in reference or "sha256" in reference):
                        continue
                    path, asset_state = _verify_cached_asset(workspace, reference)
                    expected = reference.get("sha256")
                    source_states.append({
                        "packetCaseId": case_id,
                        "recordId": record_id,
                        "asset": field,
                        "sha256": expected if isinstance(expected, str) else None,
                        "status": asset_state,
                    })
                    if path is None:
                        continue
                    href = path.as_uri()
                    if href in seen_hrefs:
                        continue
                    seen_hrefs.add(href)
                    links.append({
                        "kind": "cached_official_source_pdf" if field == "sourcePDF" else "cached_official_source_thumbnail",
                        "caseId": record_id,
                        "name": f"{label} ({record_id})",
                        "href": href,
                        "sha256": expected,
                    })

        if not source_states:
            continue
        source_states.sort(key=lambda row: json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
        source_fingerprint = item.get("sourceFingerprint")
        if isinstance(source_fingerprint, str):
            fingerprint_payload = json.dumps(
                {"sourceFingerprint": source_fingerprint, "cachedOfficialSourceStates": source_states},
                ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False,
            ).encode("utf-8")
            item["sourceFingerprint"] = hashlib.sha256(fingerprint_payload).hexdigest()
            for disposition in queue.get("caseDispositions", []):
                if (isinstance(disposition, dict)
                        and disposition.get("unitId") == item.get("id")
                        and disposition.get("caseId") in case_ids):
                    disposition["sourceFingerprint"] = item["sourceFingerprint"]

        evidence = item.setdefault("evidence", [])
        if not isinstance(evidence, list):
            evidence = item["evidence"] = []
        existing = {row.get("href") for row in evidence if isinstance(row, dict)}
        for link in links:
            if link["href"] not in existing:
                evidence.append(link)
                existing.add(link["href"])
                attached += 1
    return attached


def _attach_display_collisions(queue: dict, report: dict, report_pin: dict) -> int:
    """Prioritize exact duplicate picker labels without claiming a merge."""
    scoped = [group for group in report["groups"]
              if group["governorateAr"] == queue["governorate"]]
    by_id = {member["id"]: group for group in scoped for member in group["members"]}
    affected = 0
    for item in queue["items"]:
        group = next((by_id[case_id] for case_id in item["caseIds"] if case_id in by_id), None)
        if group is None:
            continue
        item["displayCollision"] = group
        item["priority"] = min(item["priority"], 20)
        if "exact_duplicate_picker_label" not in item["priorityReasons"]:
            item["priorityReasons"].append("exact_duplicate_picker_label")
        item["evidence"].append({"kind": "display_collision_audit", "name": "Current duplicate-label audit", **report_pin})
        affected += 1
    queue["items"].sort(key=lambda item: (item["priority"], item["caseIds"], item["id"]))
    queue["counts"]["displayCollisionGroups"] = len(scoped)
    queue["counts"]["displayCollisionReviewUnits"] = affected
    return affected


def prepare(config: dict, requested_governorate: str | None = None) -> tuple[dict, dict, Path]:
    """Publish one governorate's queue without making any model requests."""
    workspace = Path(config["workspace"]).resolve()
    last = read(workspace / "last-run.json")
    if last.get("batch", {}).get("status") != "AUTOMATIC_PASS_COMPLETE":
        raise RuntimeError("The automatic pass is not complete; do not duplicate a live run")
    snap = snapshot(config)
    governorate = _governorate(snap, requested_governorate)
    ledger = read(workspace / "case-ledger.json")
    cases, packets = _records(workspace, ledger)
    issues = read(workspace / "report" / "investigations.json").get("issues", [])
    manual = feedback(snap)
    output_dir = workspace / "review"
    decisions_file = output_dir / "decisions.json"
    reviewed = read(decisions_file).get("decisions", []) if decisions_file.exists() else []
    metadata_pin, binary_pin = pin(DEFAULT_METADATA), pin(DEFAULT_BINARY)
    metadata = read_pin(metadata_pin)
    binary = DEFAULT_BINARY.read_bytes()
    if hashlib.sha256(binary).hexdigest() != binary_pin["sha256"]:
        raise ValueError("Packed locality geometry changed during collision audit")
    queue = build_review_queue(
        governorate,
        case_ledger=ledger,
        batch_cases=cases,
        investigations=issues,
        feedback=manual,
        reviewed=reviewed,
        catalog_locations=snap["catalog"]["locations"],
        catalog_pin=snap["pins"]["catalog"],
        picker_group_features=metadata.get("features", []),
        evidence_packets=packets,
    )
    queue["inputPins"] = {
        "ledger": pin(workspace / "case-ledger.json"),
        "investigations": pin(workspace / "report" / "investigations.json"),
        "catalog": snap["pins"]["catalog"],
        "pickerGroupFeatures": metadata_pin,
    }
    collision_report = audit_display_collisions(
        snap["catalog"]["locations"], metadata, binary,
    )
    collision_report["inputPins"] = {
        "catalog": snap["pins"]["catalog"],
        "metadata": metadata_pin,
        "binary": binary_pin,
    }
    collision_pin = write(output_dir / "display-collisions-current.json", collision_report, replace=True)
    queue["inputPins"]["displayCollisions"] = collision_pin
    _attach_display_collisions(queue, collision_report, collision_pin)
    _attach_cached_official_sources(queue, workspace)
    queue["attachedAdvisories"] = _attach_advisories(queue, output_dir)
    publication = publish_review_queue(
        queue, output_dir,
        manual_tool_url=config.get("manualReviewUrl") or snap["manual"].get("url"),
    )
    return queue, publication, output_dir


def main() -> None:
    parser = argparse.ArgumentParser(description="Review completed locality reports one governorate at a time.")
    parser.add_argument("--config", required=True, type=Path, help="The task-local GPT-6 Luna max configuration")
    parser.add_argument("--governorate", help="Arabic governorate name; defaults to the first accepted governorate")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("prepare", help="Build the review queue and HTML; no model requests")
    commands.add_parser("prepare-all", help="Build and publish queues for every accepted governorate; no model requests")
    run = commands.add_parser("run", help="Review only focused, high-priority cases with GPT-6 Luna max")
    run.add_argument("--limit", type=int, default=8, help="Maximum eligible atomic review units (default: 8)")
    run.add_argument("--workers", type=int, default=3, help="Concurrent Luna workers (default: 3)")
    args = parser.parse_args()
    config = read(args.config)
    workspace = Path(config["workspace"]).resolve()
    review_root = workspace / "review"
    review_root.mkdir(parents=True, exist_ok=True)
    if args.command == "prepare-all" and args.governorate:
        parser.error("--governorate cannot be used with prepare-all")
    if args.command == "run":
        model = config.get("model", {})
        if (model.get("provider"), model.get("name"), model.get("reasoning"), model.get("fallbacks")) != (
            "codex_cli", "gpt-6-luna", "max", []
        ):
            raise ValueError("Review execution requires GPT-6 Luna at max reasoning with no fallback")
        if args.limit < 1 or args.workers < 1:
            parser.error("--limit and --workers must be positive")
    # A single controller writes the active governorate queue and dispatches
    # its workers; unrelated readers can inspect previously published files.
    with engine._RunnerLock(review_root / "controller.lock"):
        if args.command == "prepare-all":
            result = prepare_all(config)
            print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))
            return
        queue, publication, output_dir = prepare(config, args.governorate)
        result = {"governorate": queue["governorate"], "counts": queue["counts"],
                  "publication": publication, "attachedAdvisories": queue["attachedAdvisories"]}
        if args.command == "run":
            eligible = [
                item for item in queue["items"]
                if item.get("modelEligible") is True
                and item.get("status") == "UNRESOLVED"
                and item.get("disposition") == "REVIEW_REQUIRED"
                and type(item.get("priority")) is int and item["priority"] <= 30
            ]
            selected = dict(queue)
            selected["items"] = eligible[:args.limit]
            model_result = run_review_queue(
                config, selected, output_dir / "advisories" / str(queue["governorate"]),
                max_workers=args.workers,
            )
            result["selectedUnits"] = len(selected["items"])
            result["modelReview"] = model_result
            result["attachedAdvisories"] = queue["attachedAdvisories"] = _attach_advisories(queue, output_dir)
            result["publication"] = publish_review_queue(
                queue, output_dir,
                manual_tool_url=config.get("manualReviewUrl") or snapshot(config)["manual"].get("url"),
            )
    print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
