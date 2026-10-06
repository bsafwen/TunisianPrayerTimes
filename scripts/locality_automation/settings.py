"""Local setup and refresh from authoritative, accepted report pointers."""
from pathlib import Path
from .common import pin, read, read_pin, verify, write, now


HELPERS = {
    "acquire": ("ben-arous-partition-20260921/acquire.py", "network"),
    "extract": ("neighbor-boundary-reconciliation-20260921/extract_neighbors-reviewed.py", "offline"),
    "append": ("ben-arous-outer-overlaps-20260921/append-single-rings.py", "offline"),
    "impact": ("ben-arous-rades-outer-20260921/impact.py", "offline"),
    "widths": ("ben-arous-outer-overlaps-20260921/measure-overlaps.py", "offline"),
    "matrix": ("ben-arous-hammam-lif-boundaries-20260921/douar-neighbors/matrix.py", "offline"),
    "centres": ("ben-arous-fouchana-neighbors-20260921/check-centres.py", "offline"),
    "chain": ("ben-arous-three-outer-boundaries-20260921/extract-configured-chain.py", "offline"),
    "retrace": ("ben-arous-southern-border-20260921/extract-reviewed-spur.py", "offline"),
    "stage": ("ben-arous-chebda-pin-20260921/stage-full-provenance.py", "offline"),
    "prayer": ("ben-arous-boundary-relations-20260921/prayer-check.py", "offline"),
    "metric": ("ben-arous-rades-neighbors-20260921/publish-metric.py", "offline"),
    "queues": ("governorate-review-queues-20260921/planner.py", "offline"),
    "maps": ("maps-evidence-automation-20260921/collector.py", "device"),
    "deepseek": ("deepseek_worker.py", "model"),
}


def initialize(task_root, repo, output):
    task, repo = Path(task_root).resolve(), Path(repo).resolve()
    work = task / "work"
    handoff = work / "saved-review-controller-20260920/running-handoff.json"
    h = read(handoff)
    config = {
        "schemaVersion": 1, "createdAt": now(), "taskRoot": str(task), "repo": str(repo),
        "workspace": str(work / "locality-automation"),
        "python": str(work / "geo/venv/Scripts/python.exe"),
        "handoff": str(handoff),
        "governorate": h["governorateWorkflow"]["activeGovernorateAr"],
        "workers": 5, "enabledKinds": ["offline", "network", "model", "device", "compile"],
        "googleApiEnabled": False, "autoInstallValidatedChanges": True,
        "helpers": {name: {**pin(work / rel), "kind": kind} for name, (rel, kind) in HELPERS.items()},
        "index": pin(work / "current-official-catalog/isie-local-boundary-index-links.json"),
        "extractionTemplate": pin(work / "ben-arous-southern-border-20260921/extraction-config.json"),
        "cacheManifestDirectories": read(work / "ben-arous-southern-border-20260921/acquire-config.json")["cacheManifestDirectories"]
            + [str(work / "ben-arous-southern-border-20260921"), str(work / "ben-arous-southern-border-20260921/mohammedia"), str(work / "ben-arous-southern-border-20260921/mongi-slim")],
        "prayerSnapshot": pin(work / "ben-arous-boundary-relations-20260921/prayer-selection-corrected-report.json"),
        "decoder": pin(work / "catalog-successor-integration-20260921/assess_catalog_update-fixed.py"),
        "generator": pin(repo / "scripts/generate_neighborhoods.py"),
        "exporter": pin(repo / "scripts/locality_review/export_catalog.py"),
        "centreRegister": pin(work / "zaouia-neighbor-reconciliation-20260921/isie-district-tunisie-2025.csv"),
        "adb": str(Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"),
        "emulators": [f"emulator-{n}" for n in (5554, 5556, 5558, 5560, 5562)],
        "model": {"provider": "deepseek", "name": "deepseek-flash", "reasoning": "max", "maxOutputTokens": 393216,
                  "fallbacks": [], "ratesChecked": "2026-09-22", "uncachedInputPerMillionUsd": [0.15, 0.30], "outputPerMillionUsd": [0.60, 1.20]},
    }
    verify(config["index"])
    write(output, config)
    workspace = Path(config["workspace"])
    workspace.mkdir(parents=True, exist_ok=True)
    # Initializing tooling never resumes a paused geographic investigation.
    control = workspace / "control.json"
    if not control.exists():
        write(control, {"schemaVersion": 1, "paused": True, "reason": "Locality investigation paused by user; development only."})
    return config


def snapshot(config):
    h = read(config["handoff"])
    override_file = Path(config["workspace"]) / "current-snapshot.json"
    override = read_pin(read(override_file)) if override_file.exists() else None
    report_pin = override["report"] if override else h["currentProgressReport"]
    report = read_pin(report_pin)
    metric_pin = report["verificationMetric"]["report"]
    metric = read_pin(metric_pin)
    manual = dict(h["manualReviewTool"])
    if override:
        manual["catalog"] = override["catalog"]
        manual["refreshNeeded"] = override.get("manualToolRefreshNeeded", False)
        if isinstance(override.get("manualToolUrl"), str):
            manual["url"] = override["manualToolUrl"]
    catalog = read_pin(manual["catalog"])
    # Current authoritative catalog source fingerprints, never old controller totals.
    for p in catalog["sourcePins"].values():
        verify(p)
    scores = read_pin(metric["scorecards"])
    pins = {"report": report_pin, "metric": metric_pin, "catalog": manual["catalog"], "scorecards": metric["scorecards"]}
    control = Path(config["workspace"]) / "control.json"
    return {"createdAt": now(), "report": report, "metric": metric, "catalog": catalog,
            "scorecards": scores, "manual": manual, "pins": pins,
            "investigationPaused": read(control).get("paused", True) if control.exists() else True,
            "sourceGoalStatus": h.get("goalStatus", "unknown"),
            "queueIndex": h["governorateWorkflow"]["index"]}
