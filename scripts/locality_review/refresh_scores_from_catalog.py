"""Carry only still-valid manual-review checklist credit into a new catalog.

This updates the review tool's score filter after Android locality assets change.
It never grants a new verified check. A changed location fingerprint loses all
prior credit; source-wide changes conservatively invalidate dependent checks.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

try:
    from .verification_scores import load_scores
except ImportError:
    from verification_scores import load_scores


CHECKS = (
    "existence", "locality_type", "arabic_name", "french_search",
    "governorate", "delegation", "duplicates", "boundary",
    "gps_resolution", "prayer_source",
)
SOURCES = ("helper", "metadata", "binary", "governors", "displayNames", "coverage")
BUCKETS = tuple(str(n) for n in range(0, 101, 10))


def read_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object: {path}")
    return value


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_pin(pin: dict) -> dict:
    path = Path(pin["file"])
    if sha256(path) != pin["sha256"].lower():
        raise ValueError(f"Pinned file changed: {path}")
    return read_json(path)


def write_json(path: Path, value: dict) -> dict:
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")
    return {"file": str(path.resolve()), "sha256": sha256(path)}


def build(prior_snapshot: Path, accepted_report: Path, current_catalog: Path,
          output_dir: Path) -> dict:
    prior = read_json(prior_snapshot)
    old_catalog = read_pin(prior["catalog"])
    current = read_json(current_catalog)
    accepted = read_json(accepted_report)
    accepted_cards = read_pin(accepted["scorecards"])
    accepted_inputs = read_pin(accepted["currentInputPins"])
    if not load_scores(str(accepted_report), old_catalog)["available"]:
        raise ValueError("The prior accepted score report is unavailable")

    old_pins, new_pins = old_catalog["sourcePins"], current["sourcePins"]
    if set(old_pins) != set(SOURCES) or set(new_pins) != set(SOURCES):
        raise ValueError("Unexpected catalog source pins")
    for name, pin in new_pins.items():
        if sha256(Path(pin["file"])) != pin["sha256"].lower():
            raise ValueError(f"Current catalog source changed: {name}")
    if old_pins["helper"]["sha256"] != new_pins["helper"]["sha256"]:
        raise ValueError("The catalog helper changed; review its behavior first")
    if set(accepted_inputs) != set(SOURCES) | {"policy", "claims"}:
        raise ValueError("Unexpected accepted score input pins")

    old_locations = {row["id"]: row for row in old_catalog["locations"]}
    old_cards = {row["id"]: row for row in accepted_cards["rows"]}
    if len(old_locations) != len(old_catalog["locations"]) or len(old_cards) != len(accepted_cards["rows"]):
        raise ValueError("Duplicate prior IDs")
    current_locations = current["locations"]
    if len({row["id"] for row in current_locations}) != len(current_locations):
        raise ValueError("Duplicate current IDs")

    changed = {name for name in SOURCES if old_pins[name]["sha256"] != new_pins[name]["sha256"]}
    globally_invalidated = set()
    if "binary" in changed:
        globally_invalidated.update(("boundary", "gps_resolution", "duplicates"))
    if "metadata" in changed or "displayNames" in changed:
        globally_invalidated.add("duplicates")
    if "governors" in changed or "coverage" in changed:
        globally_invalidated.add("prayer_source")

    rows = []
    buckets = {key: 0 for key in BUCKETS}
    lost_checks = retained_checks = scored = changed_fingerprints = 0
    for loc in current_locations:
        ident = loc["id"]
        old = old_locations.get(ident)
        card = old_cards.get(ident)
        matching = old is not None and old["fingerprint"] == loc["fingerprint"]
        if old is not None and not matching:
            changed_fingerprints += 1
        prior_checks = set(card["verifiedChecks"]) if card else set()
        if not prior_checks <= set(CHECKS):
            raise ValueError(f"Unknown check in prior scorecard: {ident}")
        checks = sorted(prior_checks - globally_invalidated) if matching else []
        lost_checks += len(prior_checks) - len(checks)
        retained_checks += len(checks)
        score = len(checks) * 10 if checks else None
        if score is not None:
            scored += 1
            buckets[str(score)] += 1
        rows.append({
            "id": ident, "displayNameAr": loc["nameAr"],
            "lat": loc["lat"], "lng": loc["lng"],
            "verifiedChecks": checks, "scorePercent": score,
        })

    input_pins = {name: new_pins[name] for name in SOURCES}
    for name in ("policy", "claims"):
        pin = accepted_inputs[name]
        if sha256(Path(pin["file"])) != pin["sha256"].lower():
            raise ValueError(f"Accepted {name} evidence changed")
        input_pins[name] = pin
    hashes = {name + "Sha256": pin["sha256"] for name, pin in input_pins.items()}

    output_dir.mkdir(parents=True, exist_ok=False)
    cards_pin = write_json(output_dir / "scorecards.json", {
        "metadataSha256": hashes["metadataSha256"],
        "binarySha256": hashes["binarySha256"],
        "policySha256": hashes["policySha256"],
        "claimsSha256": hashes["claimsSha256"],
        "rows": rows,
    })
    inputs_pin = write_json(output_dir / "current-input-pins.json", input_pins)
    summary_pin = write_json(output_dir / "summary.json", {
        "migrationStatus": "RETAINED_ACCEPTED_EVIDENCE_ONLY",
        "statement": "Checklist coverage only; no new geographic verification credit.",
        "totalLocations": len(rows), "scoredCount": scored,
        "unscoredCount": len(rows) - scored, "buckets": buckets,
        "verifiedCheckCount": retained_checks,
        "totalPossibleCheckCount": len(rows) * len(CHECKS),
        "certified100Count": buckets["100"], "inputHashes": hashes,
    })
    report_path = output_dir / "report.json"
    write_json(report_path, {
        "schemaVersion": 1, "status": "RETAINED_ACCEPTED_EVIDENCE_ONLY",
        "qualification": "Completed checklist checks, not geographic certainty. Changed identities and source-dependent checks lost prior credit.",
        "totalSelectableLocations": len(rows), "scoredLocations": scored,
        "awaitingScoring": len(rows) - scored, "verifiedChecks": retained_checks,
        "verificationScoreBuckets": buckets, "summary": summary_pin,
        "scorecards": cards_pin, "currentInputPins": inputs_pin,
        "provenance": {
            "priorSnapshot": {"file": str(prior_snapshot.resolve()), "sha256": sha256(prior_snapshot)},
            "acceptedReport": {"file": str(accepted_report.resolve()), "sha256": sha256(accepted_report)},
            "currentCatalog": {"file": str(current_catalog.resolve()), "sha256": sha256(current_catalog)},
            "changedSources": sorted(changed),
            "globallyInvalidatedChecks": sorted(globally_invalidated),
            "changedFingerprints": changed_fingerprints,
            "lostVerifiedChecks": lost_checks,
            "retainedVerifiedChecks": retained_checks,
            "newVerifiedChecks": 0,
        },
    })
    checked = load_scores(str(report_path), current)
    if not checked["available"] or checked["total"] != len(rows) or checked["scoredCount"] != scored:
        raise ValueError("Generated score report failed its self-check")
    return {"report": str(report_path), "total": len(rows), "scored": scored,
            "verifiedChecks": retained_checks, "lostVerifiedChecks": lost_checks,
            "changedFingerprints": changed_fingerprints, "buckets": buckets}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prior-snapshot", required=True, type=Path)
    parser.add_argument("--accepted-report", required=True, type=Path)
    parser.add_argument("--current-catalog", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(build(args.prior_snapshot, args.accepted_report,
                           args.current_catalog, args.output_dir), ensure_ascii=False))


if __name__ == "__main__":
    main()
