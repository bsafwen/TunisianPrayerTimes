"""Append actual guarded phase clocks with one stock activity lock, no refresh.

Only task activity changes. Use the original locked publisher separately once
after this batch. No source, GPS, geometry, acceptance or geographic credit.
"""
from datetime import datetime, timezone
from types import SimpleNamespace
import argparse
import importlib.util
import json
import os
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation.work_window_guard import parse_utc


def phase_tasks(spec, control):
    rows, seen = [], set()
    for phase in spec["phases"]:
        if phase["taskId"] in seen or phase["endAction"] not in ("complete", "block"):
            raise ValueError("Repeated task or unsupported phase result")
        seen.add(phase["taskId"])
        receipt = read(checked(phase["execution"]))
        start, end = parse_utc(receipt["startedAtUtc"]), parse_utc(receipt["finishedAtUtc"])
        if (receipt["status"] != "COMPLETED" or type(receipt["exitCode"]) is not int
                or receipt["exitCode"] != 0 or receipt["timedOut"] is not False
                or type(receipt["processId"]) is not int or receipt["processId"] <= 0
                or not receipt.get("recordOwner")
                or not parse_utc(control["windowStartUtc"]) <= start <= end <= parse_utc(control["deadlineUtc"])
                or parse_utc(receipt["hardDeadlineUtc"]) != parse_utc(control["deadlineUtc"])
                or parse_utc(receipt["safeStartUtc"]) not in tuple(parse_utc(control[key])
                    for key in ("safeSourceQaStartUtc", "safeMapStartUtc"))):
            raise ValueError("Task clock is not a successful guarded phase of this window")
        rows.append(dict(phase, startedAtUtc=start.isoformat(), finishedAtUtc=end.isoformat()))
    return rows


def append_batch(app, rows):
    descriptor = os.open(app.LOCK, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    try:
        os.write(descriptor, str(os.getpid()).encode())
        for row in rows:
            for command, at in (("start", row["startedAtUtc"]), (row["endAction"], row["finishedAtUtc"])):
                app.append_event(SimpleNamespace(task_id=row["taskId"], command=command, at=at,
                    progress=None, title=row["title"], detail=row["detail"], location_code=row["locationCodes"]))
        with app.EVENTS.open("r+b") as stream:
            os.fsync(stream.fileno())
    finally:
        os.close(descriptor)
        app.LOCK.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("Fresh activity receipt required")
    spec = read(args.manifest)
    control = read(spec["control"])
    if (control.get("phase") != "working" or control.get("iteration") != spec["iteration"]
            or control.get("acceptanceOwner") != "/root" or control.get("mapOwner") != "/root"
            or control.get("subagentsAllowed") is not False
            or datetime.now(timezone.utc) >= parse_utc(control["safeMapStartUtc"])):
        raise ValueError("Root-only activity publication release differs")
    app_path = checked(spec["app"])
    events = checked(spec["events"])
    rows = phase_tasks(spec, control)
    sys.path.insert(0, str(app_path.parent))
    module_spec = importlib.util.spec_from_file_location("stock_activity_batch_only", app_path)
    app = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(app)
    if app.EVENTS.resolve() != events.resolve():
        raise ValueError("Declared and actual stock activity path differ")
    before = pin(events)
    backup = args.output.with_suffix(".before-events.jsonl")
    with backup.open("xb") as stream:
        stream.write(events.read_bytes())
    checked(spec["events"])
    append_batch(app, rows)
    proof = dict(status="APPENDED_ORIGINAL_TASK_ACTIVITY_NO_REFRESH", before=before, after=pin(events),
        originalApp=spec["app"], phases=rows, actualLeafPid=os.getpid(),
        sharedClocksNotLabor=True, originalPublicationLockUsed=True, reportRefreshed=False,
        ledgerOrAssetChanges=False, newGeographicCredit=0)
    with args.output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(proof, stream, ensure_ascii=False, allow_nan=False, indent=2)
        stream.write("\n")
    print(json.dumps(dict(status=proof["status"], phaseCount=len(rows), newGeographicCredit=0)))


if __name__ == "__main__":
    main()
