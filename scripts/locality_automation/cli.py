"""User and worker commands. Merely opening the report never resumes work."""
from pathlib import Path
import argparse
import json
import sys
from .common import read, read_pin, write, pin, verify, now
from . import engine, models
from .settings import initialize
from .workflow import inspect, run_batch


def report(config, latest=None):
    from .reporting import render_report
    snap, cases, diagnostics, feedback = inspect(config, 10000)
    workspace = Path(config["workspace"])
    ledger_file = workspace / "case-ledger.json"
    ledger = read(ledger_file).get("cases", {}) if ledger_file.exists() else {}
    states = []
    investigations = []
    batches = sorted({row["batch"] for row in ledger.values()})
    for batch in batches:
        p = Path(batch) / "batch-report.json"
        if p.exists():
            batch_report = read(p)
            states.extend(batch_report.get("jobs", []))
            investigations.extend(batch_report.get("issues", []))
            for advice_pin in batch_report.get("adviceFiles", []):
                advice = read_pin(advice_pin)
                if advice["status"] != "advisory":
                    investigations.append({"id": advice["caseId"], "issue": "Model analysis failed or uncertain; no automatic retry."})
                for attempt in advice.get("attempts", []):
                    for issue in attempt.get("advice", {}).get("unresolved", []):
                        investigations.append({"id": advice["caseId"], "issue": str(issue), "source": "DeepSeek advisory; unverified", "evidence": advice_pin})
    if latest and latest.get("directory") not in batches:
        states.extend(latest.get("jobs", []))
    snap["installState"] = {"historicalInstalledCount": snap["report"]["counts"].get("installedReferenceCorrections"),
                            "thisRun": sum(len(r.get("installed", [])) for r in (latest or {}).get("installations", []) if r.get("status") == "INSTALLED") if latest is not None else None}
    out = workspace / "report"
    active_ids = {r["id"] for r in ledger.values() if r.get("governorate") == config["governorate"]}
    dependency_ids = {r["id"] for r in ledger.values() if r.get("role") == "border_dependency"}
    snap["automationPass"] = {"distinctActiveCasesRecorded": len(active_ids), "activeTotal": cases["totalActiveLocations"],
                              "borderDependenciesRecorded": len(dependency_ids)}
    current_ids = {r["id"] for r in snap["catalog"]["locations"] if r["governorateAr"] == config["governorate"]}
    scored_rows = [r for r in snap["scorecards"]["rows"] if r["id"] in current_ids]
    score_buckets = {str(n): 0 for n in range(0, 101, 10)}
    checks = 0
    for row in scored_rows:
        accepted = sum(v == "verified" for v in row["checks"].values())
        checks += accepted
        if row.get("scorePercent") is not None:
            score_buckets[str(accepted * 10)] += 1
    scored_count = sum(score_buckets.values())
    snap["activeGovernorateAr"] = config["governorate"]
    snap["activeGovernorateCounts"] = {"total": len(current_ids), "scored": scored_count, "unscored": len(current_ids)-scored_count,
        "verifiedChecks": checks, "possibleChecks": len(current_ids)*10, "verificationScoreBuckets": score_buckets, "fullyVerified": score_buckets["100"],
        "checklistCompletionPercent": round(checks*10/len(current_ids), 4) if current_ids else 0}
    packet_links = []
    for batch in batches:
        batch_file = Path(batch) / "batch-report.json"
        for packet_pin in read(batch_file).get("packetFiles", []) if batch_file.exists() else []:
            packet = Path(packet_pin["file"]).parent / "index.html"
            packet_links.append({"name": packet.parent.name[:16], "file": str(packet), "href": packet.as_uri()})
    packet_links = list({p["file"]: p for p in packet_links}.values())
    snap["sourcePackets"] = packet_links
    snap["investigations"] = investigations
    result = render_report(snap, cases, diagnostics, states, feedback, models.usage(workspace), out)
    result["automationPass"] = {**snap["automationPass"], "qualified": "Recorded automatic passes, not verified geography.", "latest": latest}
    write(out / "run-summary.json", result, replace=True)
    write(out / "investigations.json", {"issues": investigations, "qualification": "Follow-up queue only; model findings require evidence review."}, replace=True)
    return {"report": str(out / "report.html"), "summary": str(out / "run-summary.json"), "recordedCases": len(ledger), "paused": snap["investigationPaused"]}


def _install_ready(config, config_file):
    from .installer import install, recover
    workspace = Path(config["workspace"])
    queue, receipts = workspace / "ready-to-install", workspace / "install-receipts"
    queue.mkdir(parents=True, exist_ok=True)
    receipts.mkdir(parents=True, exist_ok=True)
    results = []
    for path in sorted(queue.glob("*.json")):
        fp = pin(path)
        receipt = receipts / (fp["sha256"] + ".json")
        if receipt.exists():
            results.append(read(receipt))
            continue
        tx = workspace / "transactions" / fp["sha256"]
        if tx.exists():
            outcome = recover(tx, config["repo"])
        else:
            # No tests, APK or release tasks. Always compile after an installation.
            compile_script = Path(config["repo"]) / "scripts/locality_automation/Compile-LocalityChanges.ps1"
            argv = ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(compile_script), "-Repo", config["repo"], "-Config", str(config_file), "-SnapshotOutput", str(tx / "current-snapshot")]
            outcome = install(read_pin(fp), config["repo"], tx, argv, Path(config["repo"]) / "android-app")
        if outcome["status"] == "INSTALLED":
            write(workspace / "current-snapshot.json", pin(tx / "current-snapshot/snapshot.json"), replace=True)
        outcome["manifest"] = fp
        write(receipt, outcome)
        results.append(outcome)
    return results


def main():
    parser = argparse.ArgumentParser(description="Locality evidence automation: collect, compare, report, and install validated changes.")
    parser.add_argument("--config", type=Path)
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("init")
    p.add_argument("--task-root", required=True, type=Path)
    p.add_argument("--repo", required=True, type=Path)
    p = commands.add_parser("run")
    p.add_argument("--batch-size", type=int, default=12)
    p.add_argument("--workers", type=int)
    p.add_argument("--once", action="store_true")
    p.add_argument("--offline", action="store_true")
    p.add_argument("--all-governorates", action="store_true", help="After each automatic pass, continue to the next governorate; unresolved cases remain reported.")
    for name in ("status", "report", "pause", "resume", "install-ready"):
        commands.add_parser(name)
    p = commands.add_parser("packet")
    p.add_argument("--extraction", required=True)
    p.add_argument("--sha256", required=True)
    p.add_argument("--output", type=Path, required=True)
    p = commands.add_parser("model-worker")
    p.add_argument("--request", required=True)
    p = commands.add_parser("refresh-installed")
    p.add_argument("--output", type=Path, required=True)
    p = commands.add_parser("helper-plan")
    p.add_argument("--helper", required=True)
    p.add_argument("--helper-config", type=Path, required=True)
    p.add_argument("--id", required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--result", action="append", default=None)
    p = commands.add_parser("run-plan")
    p.add_argument("--plan", type=Path, required=True)
    p.add_argument("--state", type=Path, required=True)
    args = parser.parse_args()
    if args.config is None:
        parser.error("--config is required")
    if args.command == "init":
        initialize(args.task_root, args.repo, args.config)
        print("Configured. Investigation remains paused. No jobs started.")
        return
    config = read(args.config)
    workspace = Path(config["workspace"])
    if args.command == "pause":
        write(workspace / "control.json", {"schemaVersion": 1, "paused": True, "requestedAt": now()}, replace=True)
        # Pause each currently known batch runner too; running jobs drain normally.
        for state in (workspace / "batches").glob("*/*-state"):
            engine.set_paused(state, True)
        result = {"status": "PAUSE_REQUESTED", "message": "Running jobs finish; no new jobs dispatch."}
    elif args.command == "resume":
        write(workspace / "control.json", {"schemaVersion": 1, "paused": False, "requestedAt": now()}, replace=True)
        for state in (workspace / "batches").glob("*/*-state"):
            engine.set_paused(state, False)
        result = {"status": "READY", "message": "Run command starts the finite automatic pass."}
    elif args.command in ("status", "report"):
        result = report(config)
    elif args.command == "packet":
        from .settings import snapshot
        from .source_packets import build_packet
        result = build_packet({"file": args.extraction, "sha256": args.sha256}, snapshot(config)["catalog"], config["helpers"]["append"], args.output)
        result = {"packet": str(args.output / "packet.json"), "records": len(result["records"])}
    elif args.command == "model-worker":
        result = models.execute(config, args.request)
        result = {"status": result["status"], "caseId": result["caseId"]}
    elif args.command == "refresh-installed":
        from .snapshots import refresh
        result = refresh(config, args.output)
    elif args.command == "helper-plan":
        from .adapters import helper_job, plan
        result = plan(config, [helper_job(config, args.helper, args.helper_config, args.id, outputs=args.result)])
        engine.validate_plan(result)
        write(args.output, result)
        result = {"plan": str(args.output), "status": "PREPARED_ONLY"}
    elif args.command == "run-plan":
        if read(workspace / "control.json").get("paused", True):
            result = {"status": "PAUSED"}
        else:
            with engine._RunnerLock(workspace / "workflow.lock"):
                result = engine.run(read(args.plan), args.state, set(config["enabledKinds"]), config["workers"])
    elif args.command == "install-ready":
        if read(workspace / "control.json").get("paused", True):
            result = {"status": "PAUSED"}
        else:
            with engine._RunnerLock(workspace / "workflow.lock"):
                result = {"installations": _install_ready(config, args.config)}
    else:
        if args.batch_size < 1:
            parser.error("--batch-size must be positive")
        allowed = {"offline"} if args.offline else set(config["enabledKinds"])
        if args.offline:
            # An offline inspection must not consume the live case queue.
            result = report(config)
        else:
            with engine._RunnerLock(workspace / "workflow.lock"):
                while True:
                    print("Preparing next batch in " + config["governorate"] + ". Problems will be reported; independent cases continue.", flush=True)
                    latest = run_batch(config, args.config, limit=args.batch_size, allowed=allowed, workers=args.workers)
                    if latest["status"] == "AUTOMATIC_PASS_COMPLETE" and config.get("autoInstallValidatedChanges"):
                        latest["installations"] = _install_ready(config, args.config)
                    result = {"batch": latest, **report(config, latest)}
                    write(workspace / "last-run.json", result, replace=True)
                    print(json.dumps({"status": latest["status"], "recordedCases": result["recordedCases"], "report": result["report"]}, ensure_ascii=False), flush=True)
                    if args.all_governorates and latest["status"] == "AUTOMATIC_PASS_COMPLETE" and not args.once:
                        from .settings import snapshot
                        index = read_pin(snapshot(config)["queueIndex"])
                        order = [r["governorateAr"] for r in index["orderedGovernorates"]]
                        current_index = order.index(config["governorate"])
                        if current_index + 1 < len(order):
                            config["governorate"] = order[current_index + 1]
                            # Per-governorate config is immutable so previous jobs retain their pins.
                            from .workflow import key
                            next_config = workspace / ("config-" + str(current_index + 1) + "-" + key(config)[:12] + ".json")
                            if not next_config.exists():
                                write(next_config, config)
                            args.config = next_config
                            continue
                    if args.once or latest["status"] in ("PAUSED", "AUTOMATIC_PASS_COMPLETE"):
                        break
    print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
