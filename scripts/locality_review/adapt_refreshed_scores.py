"""Adapt a refreshed locality metric to the manual review server's score schema.

The adapter only carries forward checks already present in the supplied
accepted report and current refreshed scorecard. It never creates verification
credit. The generated report is self-pinned and can be checked with
``verification_scores.load_scores`` before passing it to the review server.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import tempfile
from typing import Any

try:
    from .verification_scores import load_scores
except ImportError:  # Running as a script from scripts/locality_review.
    from verification_scores import load_scores


CHECKS = (
    "existence", "locality_type", "arabic_name", "french_search", "governorate",
    "delegation", "duplicates", "boundary", "gps_resolution", "prayer_source",
)
SOURCE_KEYS = ("helper", "metadata", "binary", "governors", "displayNames", "coverage")
INPUT_KEYS = SOURCE_KEYS + ("policy", "claims")
HASH_KEYS = {key: key + "Sha256" for key in SOURCE_KEYS}
HASH_KEYS.update({"policy": "policySha256", "claims": "claimsSha256"})
BUCKETS = tuple(str(value) for value in range(100, -1, -10))


def _read_json(path: str | Path, label: str) -> Any:
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"{label}: cannot read JSON from {path}: {exc}") from exc


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _pin(entry: Any, label: str) -> tuple[Path, str]:
    if not isinstance(entry, dict):
        raise ValueError(f"{label}: expected file and sha256")
    path, digest = entry.get("file"), entry.get("sha256")
    if not isinstance(path, str) or not path:
        raise ValueError(f"{label}.file: expected non-empty path")
    if not isinstance(digest, str) or len(digest) != 64:
        raise ValueError(f"{label}.sha256: expected SHA-256 hex digest")
    try:
        int(digest, 16)
    except ValueError as exc:
        raise ValueError(f"{label}.sha256: invalid hex digest") from exc
    return Path(path), digest.lower()


def _verify_pin(entry: Any, label: str) -> tuple[bytes, str, Path]:
    path, expected = _pin(entry, label)
    try:
        data = path.read_bytes()
    except OSError as exc:
        raise ValueError(f"{label}: cannot read {path}: {exc}") from exc
    actual = _sha256(data)
    if actual != expected:
        raise ValueError(f"{label}: SHA-256 mismatch for {path}")
    return data, actual, path.resolve()


def _read_pin(entry: Any, label: str) -> tuple[Any, str, Path]:
    data, actual, resolved = _verify_pin(entry, label)
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"{label}: invalid UTF-8 JSON: {exc}") from exc
    return value, actual, resolved


def _pin_file(path: str | Path) -> dict[str, str]:
    resolved = Path(path).resolve()
    return {"file": str(resolved), "sha256": _sha256(resolved.read_bytes())}


def _write_json(path: Path, value: Any) -> dict[str, str]:
    path.parent.mkdir(parents=True, exist_ok=True)
    data = (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")
    fd, tmp_name = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp_name, path)
    except BaseException:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise
    return _pin_file(path)


def _assert_int(actual: Any, expected: int, label: str) -> None:
    if isinstance(actual, bool) or not isinstance(actual, int) or actual != expected:
        raise ValueError(f"{label}: expected {expected}, got {actual!r}")


def _validate_old_accepted(metric: dict[str, Any]) -> tuple[dict[str, dict[str, Any]], dict[str, Any]]:
    accepted_summary, _, _ = _read_pin(metric.get("summary"), "accepted.summary")
    accepted_scores, _, _ = _read_pin(metric.get("scorecards"), "accepted.scorecards")
    accepted_inputs, _, _ = _read_pin(metric.get("currentInputPins"), "accepted.currentInputPins")
    if not all(isinstance(obj, dict) for obj in (accepted_summary, accepted_scores, accepted_inputs)):
        raise ValueError("accepted report summary, scorecards, and input pins must be objects")
    if set(accepted_inputs) != set(INPUT_KEYS):
        raise ValueError("accepted.currentInputPins has unexpected pin keys")

    input_hashes = accepted_summary.get("inputHashes")
    if not isinstance(input_hashes, dict) or set(input_hashes) != set(HASH_KEYS.values()):
        raise ValueError("accepted summary.inputHashes has unexpected keys")
    for key in SOURCE_KEYS:
        _, digest = _pin(accepted_inputs[key], f"accepted.currentInputPins.{key}")
        if input_hashes.get(HASH_KEYS[key]) != digest:
            raise ValueError(f"accepted input hash mismatch for {key}")
    for key in ("policy", "claims"):
        _, digest, _ = _verify_pin(accepted_inputs[key], f"accepted.currentInputPins.{key}")
        if input_hashes.get(HASH_KEYS[key]) != digest:
            raise ValueError(f"accepted input hash mismatch for {key}")

    for key in ("metadataSha256", "binarySha256", "policySha256", "claimsSha256"):
        if accepted_scores.get(key) != input_hashes.get(key):
            raise ValueError(f"accepted scorecards.{key} does not match accepted summary")

    rows = accepted_scores.get("rows")
    if not isinstance(rows, list):
        raise ValueError("accepted scorecards.rows must be an array")
    by_id: dict[str, dict[str, Any]] = {}
    old_buckets = {key: 0 for key in BUCKETS}
    scored = verified_total = 0
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("id"), str) or not row["id"]:
            raise ValueError("accepted scorecards contains a row without a valid id")
        ident = row["id"]
        if ident in by_id:
            raise ValueError(f"accepted scorecards contains duplicate id {ident!r}")
        checks = row.get("verifiedChecks")
        if not isinstance(checks, list) or len(checks) != len(set(checks)) or any(c not in CHECKS for c in checks):
            raise ValueError(f"accepted scorecards row {ident!r} has invalid verifiedChecks")
        score = row.get("scorePercent")
        if score is None:
            if checks:
                raise ValueError(f"accepted unscored row {ident!r} contains verified checks")
        else:
            if isinstance(score, bool) or not isinstance(score, (int, float)) or not math.isfinite(score):
                raise ValueError(f"accepted row {ident!r} has invalid score")
            if score != len(checks) * 10:
                raise ValueError(f"accepted row {ident!r} score conflicts with its checks")
            score_key = str(int(score))
            if score_key not in old_buckets:
                raise ValueError(f"accepted row {ident!r} score is outside supported deciles")
            old_buckets[score_key] += 1
            scored += 1
            verified_total += len(checks)
        by_id[ident] = row

    _assert_int(accepted_summary.get("totalLocations"), len(rows), "accepted summary.totalLocations")
    _assert_int(accepted_summary.get("scoredCount"), scored, "accepted summary.scoredCount")
    _assert_int(accepted_summary.get("unscoredCount"), len(rows) - scored, "accepted summary.unscoredCount")
    _assert_int(accepted_summary.get("verifiedCheckCount"), verified_total, "accepted summary.verifiedCheckCount")
    _assert_int(metric.get("totalSelectableLocations"), len(rows), "accepted totalSelectableLocations")
    _assert_int(metric.get("scoredLocations"), scored, "accepted scoredLocations")
    _assert_int(metric.get("awaitingScoring"), len(rows) - scored, "accepted awaitingScoring")
    _assert_int(metric.get("verifiedChecks"), verified_total, "accepted verifiedChecks")
    for source, label in ((metric.get("verificationScoreBuckets"), "accepted metric"),
                          (accepted_summary.get("buckets"), "accepted summary")):
        if not isinstance(source, dict) or set(source) != set(old_buckets):
            raise ValueError(f"{label} buckets are invalid")
        for key, expected in old_buckets.items():
            _assert_int(source.get(key), expected, f"{label} bucket {key}")
    return by_id, accepted_inputs


def build_compatible_report(
    snapshot_path: str | Path,
    accepted_metric_path: str | Path,
    output_dir: str | Path,
    *,
    require_unscored_id: str | None = None,
) -> Path:
    """Create a pinned score report accepted by the manual-review server."""
    snapshot_path = Path(snapshot_path).resolve()
    accepted_metric_path = Path(accepted_metric_path).resolve()
    output_dir = Path(output_dir).resolve()
    snapshot = _read_json(snapshot_path, "snapshot")
    if not isinstance(snapshot, dict):
        raise ValueError("snapshot must be an object")
    catalog, _, _ = _read_pin(snapshot.get("catalog"), "snapshot.catalog")
    metric, _, _ = _read_pin(snapshot.get("metric"), "snapshot.metric")
    refreshed_report, refreshed_report_sha, _ = _read_pin(snapshot.get("report"), "snapshot.report")
    if not isinstance(catalog, dict) or not isinstance(metric, dict) or not isinstance(refreshed_report, dict):
        raise ValueError("refreshed catalog, metric, and report must be objects")
    if refreshed_report.get("status") != "AUTOMATION_INSTALLATION_SNAPSHOT":
        raise ValueError("snapshot report is not an installation refresh")
    if metric.get("status") != "CHANGED_EVIDENCE_INVALIDATED":
        raise ValueError("metric does not declare changed-evidence invalidation")
    refreshed_scorecards, refreshed_scorecards_sha, _ = _read_pin(metric.get("scorecards"), "refreshed metric.scorecards")
    if refreshed_report.get("verificationMetric", {}).get("report", {}).get("sha256") != _pin(snapshot.get("metric"), "snapshot.metric")[1]:
        raise ValueError("refreshed report does not pin the selected metric")
    if not isinstance(refreshed_scorecards, dict):
        raise ValueError("refreshed scorecards must be an object")

    accepted_metric = _read_json(accepted_metric_path, "accepted metric")
    if not isinstance(accepted_metric, dict):
        raise ValueError("accepted metric must be an object")
    accepted_by_id, accepted_inputs = _validate_old_accepted(accepted_metric)

    source_pins = catalog.get("sourcePins")
    if not isinstance(source_pins, dict) or set(source_pins) != set(SOURCE_KEYS):
        raise ValueError("refreshed catalog.sourcePins has unexpected keys")
    input_pins: dict[str, Any] = {}
    input_hashes: dict[str, str] = {}
    for key in SOURCE_KEYS:
        _, digest, resolved = _verify_pin(source_pins[key], f"catalog.sourcePins.{key}")
        input_pins[key] = {"file": str(resolved), "sha256": digest}
        input_hashes[HASH_KEYS[key]] = digest
    for key in ("policy", "claims"):
        _, digest, resolved = _verify_pin(accepted_inputs[key], f"accepted.currentInputPins.{key}")
        input_pins[key] = {"file": str(resolved), "sha256": digest}
        input_hashes[HASH_KEYS[key]] = digest

    locations = catalog.get("locations")
    rows = refreshed_scorecards.get("rows")
    if not isinstance(locations, list) or not isinstance(rows, list):
        raise ValueError("catalog.locations and refreshed scorecards.rows must be arrays")
    loc_by_id: dict[str, dict[str, Any]] = {}
    for loc in locations:
        if not isinstance(loc, dict) or not isinstance(loc.get("id"), str) or not loc["id"]:
            raise ValueError("catalog contains a location without a valid id")
        if loc["id"] in loc_by_id:
            raise ValueError(f"catalog contains duplicate id {loc['id']!r}")
        loc_by_id[loc["id"]] = loc
    if refreshed_scorecards.get("metadataSha256") != input_hashes["metadataSha256"]:
        raise ValueError("refreshed scorecards metadata hash does not match current catalog")
    if refreshed_scorecards.get("binarySha256") != input_hashes["binarySha256"]:
        raise ValueError("refreshed scorecards binary hash does not match current catalog")

    converted_rows: list[dict[str, Any]] = []
    seen: set[str] = set()
    buckets = {key: 0 for key in BUCKETS}
    verified_total = scored_count = 0
    refreshed_by_id: dict[str, dict[str, Any]] = {}
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("id"), str) or not row["id"]:
            raise ValueError("refreshed scorecards contains a row without a valid id")
        ident = row["id"]
        if ident in seen or ident not in loc_by_id:
            raise ValueError(f"refreshed scorecards contains duplicate or unknown id {ident!r}")
        seen.add(ident)
        loc = loc_by_id[ident]
        checks = row.get("verifiedChecks")
        if not isinstance(checks, list) or len(checks) != len(set(checks)) or any(c not in CHECKS for c in checks):
            raise ValueError(f"refreshed scorecards row {ident!r} has invalid verifiedChecks")
        if set(checks) - set(accepted_by_id.get(ident, {}).get("verifiedChecks", [])):
            raise ValueError(f"refreshed row {ident!r} promotes a check absent from accepted evidence")
        score = row.get("scorePercent")
        expected_score = len(checks) * 10 if checks else None
        if score != expected_score:
            raise ValueError(f"refreshed row {ident!r} score conflicts with its verified checks")
        if score is None:
            if row.get("status") != "unscored":
                raise ValueError(f"refreshed row {ident!r} with no accepted checks must remain unscored")
        else:
            if row.get("status") != "scored" or isinstance(score, bool) or not isinstance(score, (int, float)) or not math.isfinite(score):
                raise ValueError(f"refreshed row {ident!r} has an invalid scored status/value")
            buckets[str(int(score))] += 1
            scored_count += 1
            verified_total += len(checks)
        if row.get("displayNameAr") != loc.get("nameAr"):
            raise ValueError(f"refreshed row {ident!r} display name does not match current catalog")
        if row.get("lat") != loc.get("lat") or row.get("lng") != loc.get("lng"):
            raise ValueError(f"refreshed row {ident!r} coordinates do not match current catalog")
        refreshed_by_id[ident] = row
        converted_rows.append({
            "id": ident,
            "displayNameAr": loc.get("nameAr"),
            "lat": loc.get("lat"),
            "lng": loc.get("lng"),
            "verifiedChecks": sorted(checks),
            "scorePercent": score,
        })
    if seen != set(loc_by_id):
        raise ValueError("refreshed scorecards ids do not exactly match the current catalog")
    if require_unscored_id is not None:
        target = refreshed_by_id.get(require_unscored_id)
        if target is None:
            raise ValueError(f"required unscored target {require_unscored_id!r} is absent from current catalog")
        if target.get("scorePercent") is not None or target.get("verifiedChecks"):
            raise ValueError(f"required target {require_unscored_id!r} is not unscored")

    for key, expected in (("totalSelectableLocations", len(locations)),
                          ("scoredLocations", scored_count),
                          ("awaitingScoring", len(locations) - scored_count),
                          ("verifiedChecks", verified_total)):
        _assert_int(metric.get(key), expected, f"refreshed metric.{key}")
    if metric.get("verificationScoreBuckets") != buckets:
        raise ValueError("refreshed metric verificationScoreBuckets do not match refreshed scorecards")
    current_report_counts = refreshed_report.get("verificationMetric", {})
    if current_report_counts.get("total") != len(locations) or current_report_counts.get("scored") != scored_count or current_report_counts.get("unscored") != len(locations) - scored_count or current_report_counts.get("verifiedChecks") != verified_total or current_report_counts.get("verificationScoreBuckets") != buckets:
        raise ValueError("refreshed report verificationMetric counts do not match refreshed scorecards")

    output_dir.mkdir(parents=True, exist_ok=True)
    scorecard_obj = {
        "metadataSha256": input_hashes["metadataSha256"],
        "binarySha256": input_hashes["binarySha256"],
        "policySha256": input_hashes["policySha256"],
        "claimsSha256": input_hashes["claimsSha256"],
        "rows": converted_rows,
    }
    scorecard_pin = _write_json(output_dir / "scorecards.json", scorecard_obj)
    input_pin = _write_json(output_dir / "current-input-pins.json", input_pins)
    summary_obj = {
        "migrationStatus": "REFRESHED_ACCEPTED_EVIDENCE_ONLY",
        "statement": "Checklist coverage only; this report adds no geographic verification claims.",
        "totalLocations": len(locations),
        "scoredCount": scored_count,
        "unscoredCount": len(locations) - scored_count,
        "buckets": buckets,
        "verifiedCheckCount": verified_total,
        "totalPossibleCheckCount": len(locations) * len(CHECKS),
        "certified100Count": buckets["100"],
        "inputHashes": input_hashes,
    }
    summary_pin = _write_json(output_dir / "summary.json", summary_obj)
    report_obj = {
        "schemaVersion": 1,
        "status": "REFRESHED_ACCEPTED_EVIDENCE_ONLY",
        "qualification": "Derived from the refreshed snapshot. Every retained check was already in the accepted report; refresh invalidations are preserved. Unscored locations remain unscored.",
        "targetUnscoredId": require_unscored_id,
        "totalSelectableLocations": len(locations),
        "scoredLocations": scored_count,
        "awaitingScoring": len(locations) - scored_count,
        "verifiedChecks": verified_total,
        "verificationScoreBuckets": buckets,
        "summary": summary_pin,
        "scorecards": scorecard_pin,
        "currentInputPins": input_pin,
        "adapterProvenance": {
            "snapshot": _pin_file(snapshot_path),
            "refreshedReport": {"file": str(Path(snapshot["report"]["file"]).resolve()), "sha256": refreshed_report_sha},
            "refreshedScorecards": {"file": str(Path(metric["scorecards"]["file"]).resolve()), "sha256": refreshed_scorecards_sha},
            "acceptedMetric": _pin_file(accepted_metric_path),
            "acceptedScorecards": accepted_metric["scorecards"],
            "retainedVerifiedChecks": verified_total,
            "newVerifiedChecks": 0,
            "modelOutputEarnsCredit": False,
        },
    }
    report_path = output_dir / "report.json"
    _write_json(report_path, report_obj)
    checked = load_scores(str(report_path.resolve()), catalog)
    if not checked.get("available") or checked.get("total") != len(locations) or checked.get("scoredCount") != scored_count:
        raise ValueError("generated report failed verification_scores.load_scores self-check")
    return report_path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", required=True, help="refreshed snapshot.json")
    parser.add_argument("--accepted-metric", required=True, help="accepted compatible metric report JSON")
    parser.add_argument("--output-dir", required=True, help="directory for derived compatible report files")
    parser.add_argument("--require-unscored-id", help="fail unless this catalog location remains unscored")
    args = parser.parse_args()
    report = build_compatible_report(
        args.snapshot, args.accepted_metric, args.output_dir,
        require_unscored_id=args.require_unscored_id,
    )
    print(report)


if __name__ == "__main__":
    main()
