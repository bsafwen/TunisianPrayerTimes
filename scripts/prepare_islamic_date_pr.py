#!/usr/bin/env python3
"""Merge pending official-date data without checking out or executing PR code."""

from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Any

DATA_DIRECTORY = "data/official-islamic-dates"
YEAR_PATH = re.compile(r"data/official-islamic-dates/([0-9]+)\.json\Z")
EVENT_TIMESTAMPS = {
    "ramadanStart": "ramadanStartUpdated",
    "eidFitrDate": "eidFitrUpdated",
    "eidAdhaDate": "eidAdhaUpdated",
}
MISSING = object()


def git(root: Path, *args: str) -> bytes:
    return subprocess.run(["git", "-C", str(root), *args], check=True, stdout=subprocess.PIPE).stdout


def commit(root: Path, ref: str) -> str:
    return git(root, "rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}").decode().strip()


def parse_record(content: bytes, path: str) -> dict[str, Any]:
    match = YEAR_PATH.fullmatch(path)
    if not match:
        raise ValueError(f"Unsupported official-date path: {path}")
    record = json.loads(content)
    year = int(match.group(1))
    if not isinstance(record, dict) or type(record.get("hijriYear")) is not int or record["hijriYear"] != year:
        raise ValueError(f"{path}: hijriYear must match the filename.")
    return record


def tree_records(root: Path, ref: str) -> dict[str, dict[str, Any]]:
    records = {}
    for raw_path in git(root, "ls-tree", "-rz", "--name-only", ref, "--", DATA_DIRECTORY).split(b"\0"):
        if not raw_path:
            continue
        path = raw_path.decode("utf-8")
        if not path.endswith(".json"):
            continue
        records[path] = parse_record(git(root, "show", f"{ref}:{path}"), path)
    return records


def working_records(root: Path) -> dict[str, dict[str, Any]]:
    directory = root / DATA_DIRECTORY
    return {
        path.relative_to(root).as_posix(): parse_record(path.read_bytes(), path.relative_to(root).as_posix())
        for path in directory.rglob("*.json")
    } if directory.exists() else {}


def timestamp(value: Any) -> dt.datetime | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError(f"Invalid announcement timestamp: {value!r}")
    parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError(f"Announcement timestamp needs a timezone: {value!r}")
    return parsed.astimezone(dt.timezone.utc)


def newest_timestamp(values: list[Any]) -> str | None:
    dates = [parsed for value in values if (parsed := timestamp(value)) is not None]
    return max(dates).isoformat().replace("+00:00", "Z") if dates else None


def field_value(record: dict[str, Any], key: str) -> Any:
    # A missing event and an explicit null both mean that no date is known.
    return record.get(key) if key in EVENT_TIMESTAMPS else record.get(key, MISSING)


def merge_record(path: str, ancestor: dict[str, Any] | None,
                 base: dict[str, Any] | None, pending: dict[str, Any] | None) -> dict[str, Any] | None:
    if pending == ancestor or pending == base:
        return base
    if base == ancestor:
        return pending
    if base is None or pending is None:
        raise ValueError(f"{path}: one branch deleted the year while the other changed it.")

    ancestor = ancestor or {}
    merged = {}
    special_keys = {"lastUpdated", *EVENT_TIMESTAMPS.values()}
    for key in sorted(set(ancestor) | set(base) | set(pending)):
        if key in special_keys:
            continue
        old, current, proposed = (field_value(record, key) for record in (ancestor, base, pending))
        if proposed == old or current == proposed:
            selected = current
        elif current == old:
            selected = proposed
        else:
            raise ValueError(f"{path}: conflicting edits to {key}: base={current!r}, pending={proposed!r}. Review both before retrying.")
        if selected is not MISSING:
            merged[key] = selected

    for field, updated_field in EVENT_TIMESTAMPS.items():
        selected = merged.get(field)
        if selected is None:
            if updated_field in base or updated_field in pending:
                merged[updated_field] = None
            continue
        # A timestamp belongs to its event value, not to another changed field
        # in the same JSON document. Materialize old-format provenance on merge.
        contributing = []
        for record in (base, pending):
            if record.get(field) != selected:
                continue
            if updated_field in record:
                contributing.append(record[updated_field])
            elif ancestor.get(field) == selected:
                # Old-format branches only have a document timestamp. An
                # unrelated edit must not re-date an unchanged announcement.
                contributing.append(ancestor.get(updated_field, ancestor.get("lastUpdated")))
            else:
                contributing.append(record.get("lastUpdated"))
        updated = newest_timestamp(contributing)
        if updated is not None or updated_field in base or updated_field in pending:
            merged[updated_field] = updated
    merged["lastUpdated"] = newest_timestamp([base.get("lastUpdated"), pending.get("lastUpdated")])
    return merged


def prepare(root: Path, base_ref: str, pending_ref: str) -> list[str]:
    base_sha, pending_sha = commit(root, base_ref), commit(root, pending_ref)
    ancestor_sha = git(root, "merge-base", base_sha, pending_sha).decode().strip()
    ancestor, base, pending = (tree_records(root, ref) for ref in (ancestor_sha, base_sha, pending_sha))
    if working_records(root) != base:
        raise ValueError("Official-date working files differ from the trusted base; refusing to replace local edits.")
    merged = {
        path: merge_record(path, ancestor.get(path), base.get(path), pending.get(path))
        for path in sorted(set(ancestor) | set(base) | set(pending))
    }
    # Validation is imported from the checked-out trusted base, never the fetched
    # PR tree. Validate everything before writing anything, so conflicts are atomic.
    from detect_tunisian_lunar_dates import EVENTS, validate_anchor_spacing, validate_override_record
    anchors = []
    for path, record in merged.items():
        if record is not None:
            year = int(YEAR_PATH.fullmatch(path).group(1))
            validate_override_record(record, year)
            for event in EVENTS.values():
                if record.get(event.override_field) is not None:
                    day = dt.date.fromisoformat(record[event.override_field]) - dt.timedelta(days=event.hijri_day - 1)
                    anchors.append((year * 12 + event.hijri_month, day, f"{year}.{event.override_field}"))
    validate_anchor_spacing(anchors)

    changed = []
    for path, record in merged.items():
        if record == base.get(path):
            continue
        target = root / path
        if record is None:
            target.unlink(missing_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        changed.append(path)
    return changed


def differs(root: Path, ref: str) -> bool:
    return working_records(root) != tree_records(root, commit(root, ref))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=".")
    commands = parser.add_subparsers(dest="command", required=True)
    merge = commands.add_parser("prepare")
    merge.add_argument("--base-ref", default="HEAD")
    merge.add_argument("--pending-ref", required=True)
    changed = commands.add_parser("changed")
    changed.add_argument("--ref", required=True)
    args = parser.parse_args()
    root = Path(args.repo_root).resolve()
    try:
        if args.command == "prepare":
            paths = prepare(root, args.base_ref, args.pending_ref)
            print(f"Preserved pending official-date edits in {len(paths)} file(s).")
        else:
            print("true" if differs(root, args.ref) else "false")
    except (ValueError, subprocess.CalledProcessError) as error:
        print(f"Official-date PR preparation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
