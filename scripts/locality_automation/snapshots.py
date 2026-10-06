"""Refresh selectable data after installation and invalidate changed evidence."""
from copy import deepcopy
from pathlib import Path
import subprocess
from .common import read, read_pin, write, pin, verify, now
from .planning import CHECKS


def refresh(config, output):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    handoff = read(config["handoff"])
    override = Path(config["workspace"]) / "current-snapshot.json"
    base = read_pin(read(override)) if override.exists() else {
        "catalog": handoff["manualReviewTool"]["catalog"], "metric": handoff["currentVerificationMetric"], "report": handoff["currentProgressReport"]}
    old_catalog = read_pin(base["catalog"])
    old_metric = read_pin(base["metric"])
    old_scores = read_pin(old_metric["scorecards"])
    old_report = read_pin(base["report"])
    new_pins = {name: pin(item["file"]) for name, item in old_catalog["sourcePins"].items()}
    # Executable helper changes are outside catalog installation scope.
    if new_pins["helper"] != old_catalog["sourcePins"]["helper"]:
        raise ValueError("Catalog helper changed; investigate before exporting")
    exporter = config["exporter"]
    verify(exporter)
    pp = output / "export-pins.json"
    write(pp, new_pins)
    catalog_path = output / "catalog.json"
    subprocess.run([config["python"], "-B", "-X", "utf8", exporter["file"], "--pins", str(pp), "--output", str(catalog_path)], shell=False, check=True)
    new_catalog = read(catalog_path)
    previous = {r["id"]: r for r in old_catalog["locations"]}
    scores_by_id = {r["id"]: r for r in old_scores["rows"]}
    changed_sources = new_pins["governors"] != old_catalog["sourcePins"]["governors"]
    changed_geometry = new_pins["binary"] != old_catalog["sourcePins"]["binary"]
    changed_labels = any(new_pins[k] != old_catalog["sourcePins"][k] for k in ("metadata", "displayNames"))
    rows, invalidated = [], []
    for loc in new_catalog["locations"]:
        row = deepcopy(scores_by_id.get(loc["id"], {"id": loc["id"], "checks": {c: "unverified" for c in CHECKS}}))
        invalidate = set()
        if previous.get(loc["id"], {}).get("fingerprint") != loc["fingerprint"]:
            invalidate.update(CHECKS)
        if changed_sources:
            invalidate.add("prayer_source")
        if changed_geometry:
            invalidate.update(("gps_resolution", "duplicates"))
        if changed_labels:
            invalidate.add("duplicates")
        lost = [c for c in invalidate if row["checks"].get(c) == "verified"]
        for c in invalidate:
            row["checks"][c] = "unverified"
        if lost:
            invalidated.append({"id": loc["id"], "checks": sorted(lost)})
        verified = [c for c in CHECKS if row["checks"].get(c) == "verified"]
        row.update({"verifiedChecks": verified, "missingChecks": [c for c in CHECKS if c not in verified],
                    "scorePercent": len(verified) * 10 if verified else None, "status": "scored" if verified else "unscored"})
        rows.append(row)
    score_pin = write(output / "scorecards.json", {"rows": rows, "metadataSha256": new_pins["metadata"]["sha256"], "binarySha256": new_pins["binary"]["sha256"], "qualification": "Conservative invalidation after installation; no automatic verification credit."})
    def counts(selected):
        buckets = {str(n): 0 for n in range(0, 101, 10)}
        scored = [r for r in selected if r["scorePercent"] is not None]
        for row in scored:
            buckets[str(row["scorePercent"])] += 1
        checks = sum(len(r["verifiedChecks"]) for r in selected)
        return {"total": len(selected), "scored": len(scored), "unscored": len(selected)-len(scored), "verifiedChecks": checks,
                "possibleChecks": len(selected)*10, "verificationScoreBuckets": buckets, "fullyVerified": buckets["100"],
                "checklistCompletionPercent": round(checks / (len(selected)*10) * 100, 4) if selected else 0}
    global_counts = counts(rows)
    metric = deepcopy(old_metric)
    metric.update({"scorecards": score_pin, "totalSelectableLocations": len(rows), "scoredLocations": global_counts["scored"], "awaitingScoring": global_counts["unscored"],
                   "verifiedChecks": global_counts["verifiedChecks"], "possibleChecks": global_counts["possibleChecks"], "verificationScoreBuckets": global_counts["verificationScoreBuckets"],
                   "fullyVerifiedAgainstChecklist": global_counts["fullyVerified"], "status": "CHANGED_EVIDENCE_INVALIDATED", "previousMetric": base["metric"],
                   "qualification": "Updated catalog; affected prior checks invalidated. No installation or model output earns geographic verification credit."})
    metric_pin = write(output / "metric.json", metric)
    report = deepcopy(old_report)
    ids = {r["id"] for r in new_catalog["locations"] if r["governorateAr"] == "بن عروس"}
    report["benArous"] = counts([r for r in rows if r["id"] in ids])
    report["counts"]["currentSelectableLocations"] = len(rows)
    # New/changed identities are no longer supported by the old first-pass observation.
    report["counts"]["currentLocationsWithFirstPassEvidence"] = sum(previous.get(r["id"], {}).get("fingerprint") == r["fingerprint"] for r in new_catalog["locations"])
    report["verificationMetric"] = {**global_counts, "report": metric_pin}
    report["updatedAtUtc"] = now()
    report["status"] = "AUTOMATION_INSTALLATION_SNAPSHOT"
    report["qualification"] = metric["qualification"]
    report_pin = write(output / "report.json", report)
    return write(output / "snapshot.json", {"catalog": pin(catalog_path), "metric": metric_pin, "report": report_pin, "invalidated": invalidated,
                "manualToolRefreshNeeded": True, "qualification": "Manual review server retains its previous snapshot until refreshed; all answers preserved."})
