"""Finite automatic passes: record case failures, continue unrelated work."""
from pathlib import Path
import hashlib
import json
import subprocess
from . import engine, models
from .common import pin, read, read_pin, write, verify, now, slug
from .settings import snapshot
from .diagnostics import audit_catalog
from .planning import plan_cases
from .adapters import helper_job, plan


def key(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def save_once(path, value):
    path = Path(path)
    if path.exists():
        if read(path) != value:
            raise ValueError(f"Existing configuration changed: {path}")
        return pin(path)
    return write(path, value)


def feedback(snap):
    manual = snap["manual"]
    reviewed = set(read_pin(manual["processedSubmissions"])["reviewedRequestIds"])
    path = Path(manual["responsesFile"])
    raw = path.read_bytes() if path.exists() else b""
    latest = {}
    invalid = []
    for number, line in enumerate(raw.decode("utf-8-sig").splitlines(), 1):
        try:
            row = json.loads(line)
            latest[row["id"]] = row
        except (ValueError, KeyError, TypeError):
            invalid.append(number)
    by_id = {r["id"]: r for r in snap["catalog"]["locations"]}
    result = {"pending": [], "stale": [], "invalidLines": invalid, "counts": {"saved": len(latest), "previouslyReviewed": 0}}
    for ident, row in latest.items():
        if row.get("requestId") in reviewed:
            result["counts"]["previouslyReviewed"] += 1
            continue
        current = by_id.get(ident)
        stale = current is None or row.get("fingerprint") != current.get("fingerprint")
        result["stale" if stale else "pending"].append(row)
    result["qualification"] = "User assertions are investigation evidence; never executed as instructions or accepted as all ten checks."
    result["sourceSha256"] = hashlib.sha256(raw).hexdigest()
    return result


def inspect(config, limit=12):
    snap = snapshot(config)
    index = read_pin(config["index"])
    remaining = snap["report"].get("remaining", {})
    if remaining.get("activeGovernorate") != config["governorate"]:
        remaining = {}
    cases = plan_cases(snap["catalog"], index, snap["scorecards"], remaining, config["governorate"], limit)
    from .prayer_sources import source_snapshot
    prayer_sources = source_snapshot(config, snap)
    cache_key = key({"catalog": snap["pins"]["catalog"], "governorate": config["governorate"], "prayerSources": prayer_sources["inputPins"]})
    cache = Path(config["workspace"]) / "diagnostics" / (cache_key + ".json")
    if cache.exists():
        diagnostics = read(cache)
    else:
        diagnostics = audit_catalog(snap["catalog"], config["governorate"], prayer_sources["sources"])
        write(cache, diagnostics)
    return snap, cases, diagnostics, feedback(snap)


def _self_job(config, config_file, ident, subcommand, args, inputs, outputs, kind="offline", resources=()):
    script = Path(config["repo"]) / "scripts/run_locality_automation.py"
    # Pin all program modules so a code change cannot silently reuse old outcomes.
    program_pins = [pin(p) for p in sorted((script.parent / "locality_automation").glob("*.py"))]
    return {"id": ident, "kind": kind,
            "argv": [config["python"], "-B", "-X", "utf8", str(script), "--config", str(config_file), subcommand, *map(str, args)],
            "cwd": config["repo"], "inputs": [pin(script), pin(config_file), *program_pins, *inputs],
            "outputs": list(map(str, outputs)), "deps": [], "resources": list(resources), "governorate": config["governorate"]}


def _run_wave(config, root, name, jobs, allowed, workers):
    plan_file = root / (name + "-plan.json")
    if not jobs and not plan_file.exists():
        return {"jobs": {}}
    control = Path(config["workspace"]) / "control.json"
    if read(control).get("paused", True):
        return {"jobs": {}, "paused": True}
    state_dir = root / (name + "-state")
    # Preserve original task definitions on resume. A freshly detected cache must
    # not turn a previously executed job into a different job with the same ID.
    if plan_file.exists():
        p = read(plan_file)
    else:
        p = plan(config, jobs)
        save_once(plan_file, p)
    return engine.run(p, state_dir, allowed, workers)


def _cache_extractions(config):
    result = {}
    # Explicit source cache roots only, plus this program's own collection index.
    folders = list(config["cacheManifestDirectories"])
    registry = Path(config["workspace"]) / "source-cache.json"
    if registry.exists():
        folders += read(registry).get("directories", [])
    for folder in dict.fromkeys(folders):
        d = Path(folder)
        mp, ep = d / "manifest.json", d / "geopdf-extraction.json"
        if not mp.exists() or not ep.exists():
            continue
        try:
            manifest = read(mp)
            extraction = read(ep)
            for record in extraction.get("records", []):
                for row in manifest:
                    if record.get("key") == row.get("key") and record.get("sha256") == row.get("sha256"):
                        pdf = {"file": str(d / row["file"]), "sha256": row["sha256"]}
                        verify(pdf)
                        result[(row["url"], tuple(row["sourceOsmIds"]))] = pin(ep)
        except (ValueError, KeyError, OSError):
            continue  # Invalid caches are not reused; collection records any failure.
    return result


def run_batch(config, config_file, *, limit=12, allowed=None, workers=None):
    snap, planned, diagnostics, user_feedback = inspect(config, 10000)
    if snap["investigationPaused"]:
        return {"status": "PAUSED", "message": "No locality work started. Use resume explicitly.", "jobs": []}
    allowed = set(allowed or config["enabledKinds"])
    workers = workers or config["workers"]
    workspace = Path(config["workspace"])
    ledger_file = workspace / "case-ledger.json"
    ledger = read(ledger_file) if ledger_file.exists() else {"cases": {}}
    candidates = []
    for case in planned["cases"]:
        pending_reports = [r for r in user_feedback["pending"] if r.get("id") == case["id"]]
        case_key = key({"case": case, "catalog": snap["pins"]["catalog"], "userReports": pending_reports})
        if case_key not in ledger["cases"]:
            candidates.append((case_key, case))
    pending_ids = {r.get("id") for r in user_feedback["pending"] if r.get("verdict") == "problem"}
    candidates.sort(key=lambda row: row[1]["id"] not in pending_ids)
    selected = candidates[:limit]
    if not selected:
        return {"status": "AUTOMATIC_PASS_COMPLETE", "qualification": "Unresolved cases remain reported; geographic verification is not complete.", "jobs": []}
    root = workspace / "batches" / key([x[0] for x in selected])[:24]
    root.mkdir(parents=True, exist_ok=True)
    save_once(root / "cases.json", {"cases": [c for _, c in selected], "catalog": snap["pins"]["catalog"]})
    states, issues, extracted = [], [], {}
    cached = _cache_extractions(config)
    acquisitions = []
    collections = {}
    for case_key, case in selected:
        ident = slug(case["id"])
        matches = case["sourceMatches"]
        if case["sourceStatus"] != "unique_exact":
            issues.append({"id": case["id"], "issue": "Official source is ambiguous or missing; retained for investigation."})
            continue
        match = matches[0]
        existing = cached.get((match["url"], (case["id"],)))
        if existing:
            extracted[ident] = existing
            continue
        folder = root / ident
        folder.mkdir(exist_ok=True)
        cfg = {"taskRoot": config["taskRoot"], "directory": str(folder), "index": config["index"], "extractionTemplate": config["extractionTemplate"],
               "cacheManifestDirectories": config["cacheManifestDirectories"],
               "targets": [{"key": ident, "name": case["nameAr"], "parent": case["parentAr"].removeprefix("معتمدية "),
                            "sourceOsmIds": [case["id"]], "indexText": match["text"], "url": match["url"]}]}
        cp = folder / "acquire-config.json"
        save_once(cp, cfg)
        acquisitions.append(helper_job(config, "acquire", cp, "acquire-" + ident))
        collections[ident] = folder
    map_jobs = []
    if "device" in allowed:
        try:
            map_jobs, map_issues = collect_maps(config, config_file, [c for _, c in selected], root / "maps")
            issues.extend(map_issues)
        except (ValueError, KeyError, OSError, subprocess.SubprocessError) as exc:
            issues.append({"issue": "Emulator collection unavailable: " + str(exc)})
    states.append(_run_wave(config, root, "acquire", acquisitions + map_jobs, allowed, workers))
    extractions = []
    for ident, folder in collections.items():
        status = states[-1]["jobs"].get("acquire-" + ident, {}).get("status")
        if status not in engine.SATISFIED:
            continue
        cp = folder / "extraction-config.json"
        try:
            extractions.append(helper_job(config, "extract", cp, "extract-" + ident))
        except (ValueError, KeyError, OSError) as exc:
            issues.append({"id": ident, "issue": str(exc)})
    states.append(_run_wave(config, root, "extract", extractions, allowed, workers))
    for ident, folder in collections.items():
        if states[-1]["jobs"].get("extract-" + ident, {}).get("status") in engine.SATISFIED:
            extracted[ident] = pin(folder / "geopdf-extraction.json")
    packet_jobs = []
    packet_paths = {}
    packet_ids = set()
    for ident, ep in extracted.items():
        packet_key = key({"extraction": ep, "catalog": snap["pins"]["catalog"], "helper": config["helpers"]["append"]})
        folder = workspace / "source-packets" / packet_key
        packet_paths[ident] = folder / "packet.json"
        if packet_key in packet_ids:
            continue
        packet_ids.add(packet_key)
        if (folder / "packet.json").exists():
            packet_jobs.append({"id": "packet-" + packet_key[:20], "kind": "offline", "inputs": [ep, snap["pins"]["catalog"]],
                                "importedOutputs": [pin(folder / name) for name in ("packet.json", "decisions-template.json", "index.html")], "governorate": config["governorate"]})
        else:
            packet_jobs.append(_self_job(config, config_file, "packet-" + packet_key[:20], "packet", ["--extraction", ep["file"], "--sha256", ep["sha256"], "--output", folder],
                                         [ep, snap["pins"]["catalog"], config["helpers"]["append"]], [folder / "packet.json", folder / "decisions-template.json"]))
    states.append(_run_wave(config, root, "packets", packet_jobs, allowed, workers))
    from .geometry_pipeline import diagnose
    geometry_states, geometry_issues, impact_paths = diagnose(config, snap, root, selected, extracted, packet_paths,
        lambda name, jobs: _run_wave(config, root, name, jobs, allowed, workers))
    states.extend(geometry_states)
    issues.extend(geometry_issues)
    model_jobs = []
    advice_files = []
    maps_ledger_pin = pin(Path(config["taskRoot"]) / "outputs/phone-maps-review/ledger.json")
    maps_ledger = read_pin(maps_ledger_pin)
    prior_entries = {r["id"]: r for r in maps_ledger.get("entries", [])}
    for _, case in selected:
        ident = slug(case["id"])
        evidence = {"acceptedChecks": case["acceptedChecks"], "missingChecks": case["missingChecks"]}
        inputs = [snap["pins"]["catalog"], config["index"]]
        prior = prior_entries.get(case["id"])
        if prior:
            evidence["historicalMapsObservations"] = {"observations": prior.get("observations", []), "changedSinceObservation": prior.get("changedSinceObservation"),
                "qualification": "Historical observation only. Reconcile against current identity and accepted checks; do not assume a card name authenticates an entire boundary."}
            inputs.append(maps_ledger_pin)
        if packet_paths.get(ident, Path("__absent__")).exists():
            packet_pin = pin(packet_paths[ident])
            evidence["polygonPacket"] = read_pin(packet_pin)
            inputs.append(packet_pin)
        row = next((r for r in diagnostics["rows"] if r["id"] == case["id"]), None)
        if row:
            evidence["diagnostics"] = row
        if ident in impact_paths and Path(impact_paths[ident]).exists():
            ip = pin(impact_paths[ident])
            impact = read_pin(ip)
            evidence["boundaryImpact"] = {k: v for k, v in impact.items() if k in ("targets", "pairImpacts", "externalSectorImpacts", "gpsWitnesses")}
            inputs.append(ip)
        for job in map_jobs:
            summary_file = Path(job["outputs"][0])
            if summary_file.exists():
                evidence.setdefault("mapBatches", []).append(read(summary_file))
                inputs.append(pin(summary_file))
        evidence["userReports"] = [r for r in user_feedback["pending"] if r.get("id") == case["id"]]
        request = models.prepare(config, case, evidence, inputs)
        out = read(request)["output"]
        advice_files.append(out)
        if Path(out).exists():
            model_jobs.append({"id": "advice-" + ident, "kind": "offline", "importedOutputs": [pin(out)], "inputs": [pin(request)], "governorate": config["governorate"]})
        else:
            model_jobs.append(_self_job(config, config_file, "advice-" + ident, "model-worker", ["--request", request], [pin(request), config["helpers"]["deepseek"], *inputs], [out], "model"))
    states.append(_run_wave(config, root, "models", model_jobs, allowed, workers))
    # All case problems are recorded. No case uncertainty interrupts siblings.
    paused = read(workspace / "control.json").get("paused", True)
    if not paused:
        for case_key, case in selected:
            ledger["cases"][case_key] = {"id": case["id"], "governorate": case["governorateAr"], "role": case["role"], "status": "AUTOMATED_PASS_RECORDED", "batch": str(root), "recordedAt": now(), "verificationCredit": 0}
        write(ledger_file, ledger, replace=True)
    registry = workspace / "source-cache.json"
    dirs = read(registry).get("directories", []) if registry.exists() else []
    dirs += [str(Path(p["file"]).parent) for p in extracted.values()]
    write(registry, {"directories": sorted(set(dirs))}, replace=True)
    report = {"status": "PAUSED" if paused else "BATCH_RECORDED", "caseCount": len(selected), "remainingAutomaticCases": len(candidates) - len(selected),
              "jobs": states, "issues": issues, "directory": str(root), "verifiedChecksAdded": 0,
              "adviceFiles": [pin(p) for p in advice_files if Path(p).exists()],
              "packetFiles": [pin(p) for p in dict.fromkeys(packet_paths.values()) if p.exists()],
              "qualification": "Automatic evidence collection and advisory review; unresolved geography is reported, never silently certified."}
    write(root / "batch-report.json", report, replace=True)
    return report


def collect_maps(config, config_file, cases, root):
    """Prepare disjoint jobs only for queries absent from existing evidence."""
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    ledger_path = Path(config["taskRoot"]) / "outputs/phone-maps-review/ledger.json"
    ledger = read(ledger_path)
    entries = ledger.get("entries", []) if isinstance(ledger, dict) else ledger
    existing = {str(o.get("query", "")).strip().casefold() for e in entries for o in e.get("observations", [])}
    observed_ids = {e["id"] for e in entries if e.get("observations")}
    queries = [{"id": c["id"], "query": c["query"]} for c in cases if c["id"] not in observed_ids and "existence" not in c["acceptedChecks"] and c["query"].strip().casefold() not in existing]
    if not queries:
        return [], []
    completed = subprocess.run([config["adb"], "devices"], capture_output=True, text=True, check=True, shell=False)
    connected = {line.split()[0] for line in completed.stdout.splitlines() if line.endswith("\tdevice")}
    serials = [s for s in config["emulators"] if s in connected]
    if not serials:
        return [], [{"issue": "No configured emulator is currently connected; start an emulator to collect cases without existing observations."}]
    jobs = []
    for i, serial in enumerate(serials):
        group = queries[i::len(serials)]
        if not group:
            continue
        run_id = "auto-" + key(group)[:16] + "-" + serial
        out = Path(config["helpers"]["maps"]["file"]).parent / "captures" / run_id
        cp = root / (serial + ".json")
        save_once(cp, {"schemaVersion": 1, "runId": run_id, "serial": serial, "deviceType": "emulator", "outputDir": str(out), "queries": group})
        helper = config["helpers"]["maps"]
        snap = snapshot(config)
        metadata_pin = snap["catalog"]["sourcePins"]["metadata"]
        jobs.append({"id": "maps-" + serial, "kind": "device", "argv": [config["python"], "-B", "-X", "utf8", helper["file"], "--plan", str(cp), "--plan-sha256", pin(cp)["sha256"], "--metadata-sha256", metadata_pin["sha256"]],
                     "cwd": config["repo"], "inputs": [pin(cp), helper, metadata_pin, pin(ledger_path)], "outputs": [str(out / "batch-summary.json")], "resources": ["device:" + serial], "governorate": config["governorate"]})
    return jobs, []
