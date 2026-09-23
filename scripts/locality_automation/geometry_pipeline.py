"""Automatic diagnostic proposals and comparisons, without boundary certification."""
from pathlib import Path
import csv
from .common import read, read_pin, pin, write, verify
from .planning import _strip_admin
from .adapters import helper_job
from . import engine


def diagnose(config, snapshot, root, selected, extracted, packet_paths, run_wave):
    """Ambiguous selections become issues; unique hypotheses receive numeric checks."""
    from .workflow import save_once
    root = Path(root)
    metadata = snapshot["catalog"]["sourcePins"]["metadata"]
    binary = snapshot["catalog"]["sourcePins"]["binary"]
    empty = root / "empty-candidates.json"
    save_once(empty, {"type": "FeatureCollection", "features": []})
    states, issues, jobs, folders = [], [], [], {}
    for _, case in selected:
        ident = case["id"].replace(":", "_")
        packet_file = packet_paths.get(ident)
        if packet_file is None or not packet_file.exists():
            continue
        packet = read(packet_file)
        records = [r for r in packet.get("records", []) if case["id"] in r.get("sourceOsmIds", [])]
        if len(records) != 1:
            issues.append({"id": case["id"], "issue": "Multiple or missing source records; compare hypotheses later."})
            continue
        record = records[0]
        eligible = [r for r in record.get("rings", []) if all(r.get(k) for k in ("valid", "nonEmpty", "supportedType", "finite", "withinLonLat", "clipOutsideZero", "coversPoint")) and r.get("targetLabels")]
        if record.get("issues") or len(eligible) != 1 or record.get("sourceHashBefore") != record.get("sourceHashExpected"):
            issues.append({"id": case["id"], "issue": "Boundary hypothesis is ambiguous, clipped, or excludes the saved point; retained for investigation."})
            continue
        folder = root / (ident + "-geometry")
        folder.mkdir(exist_ok=True)
        cp = folder / "append-config.json"
        save_once(cp, {"metadata": metadata, "binary": binary, "decoder": config["decoder"], "base": pin(empty), "extraction": extracted[ident],
                       "outputDirectory": str(folder / "appended"), "expectedBaseCount": 0, "expectedOutputCount": 1,
                       "targets": [{"id": case["id"], "key": ident, "drawingIndex": eligible[0]["drawingIndex"]}]})
        try:
            jobs.append(helper_job(config, "append", cp, "append-" + ident))
            folders[ident] = (folder, case)
        except (ValueError, KeyError, OSError) as exc:
            issues.append({"id": case["id"], "issue": str(exc)})
    states.append(run_wave("geometry", jobs))
    comparisons = []
    stage_jobs = []
    successful = {}
    verify(config["centreRegister"])
    with Path(config["centreRegister"]["file"]).open(encoding="utf-8-sig", newline="") as fh:
        centre_rows = list(csv.DictReader(fh, delimiter=";"))
    for ident, (folder, case) in folders.items():
        if states[-1]["jobs"].get("append-" + ident, {}).get("status") not in engine.SATISFIED:
            continue
        candidates = pin(folder / "appended/candidates.geojson")
        successful[ident] = candidates
        inputs = {"metadata": metadata, "binary": binary, "decoder": config["decoder"], "candidates": candidates}
        cp = folder / "impact-config.json"
        save_once(cp, {**inputs, "expectedCandidateCount": 1, "output": str(folder / "impact.json")})
        comparisons.append(helper_job(config, "impact", cp, "impact-" + ident))
        codes = {row["code_loc"] for row in centre_rows
                 if _strip_admin(row.get("lib_loc_ar")) == _strip_admin(case["nameAr"])
                 and _strip_admin(row.get("lib_dlg_ar")) == _strip_admin(case["parentAr"])
                 and _strip_admin(row.get("lib_gouv_ar")) == _strip_admin(case["governorateAr"])}
        if len(codes) == 1:
            centre_config = folder / "centres-config.json"
            save_once(centre_config, {**inputs, "csv": config["centreRegister"], "targets": [{"id": case["id"], "code_loc": next(iter(codes))}], "output": str(folder / "centres.json")})
            comparisons.append(helper_job(config, "centres", centre_config, "centres-" + ident))
        else:
            issues.append({"id": case["id"], "issue": "Official centre identity has zero or multiple exact matches; administrative assignment is not assumed."})
        # Stage one hypothesis against the current catalog. This deliberately does not
        # replace the accepted 41-candidate aggregate or approve mixed-state overlaps.
        assembly = folder / "assembly.json"
        save_once(assembly, {"candidateCount": 1, "ids": [case["id"]], "inputHashPins": {"before": {p["file"]: p["sha256"] for p in [metadata, binary, candidates]}}})
        sp = folder / "stage-config.json"
        save_once(sp, {**inputs, "generator": config["generator"], "assemblyReview": pin(assembly), "taskRoot": config["taskRoot"], "outputDirectory": str(folder / "staged")})
        stage_jobs.append(helper_job(config, "stage", sp, "stage-" + ident))
    states.append(run_wave("comparisons-and-packing", comparisons + stage_jobs))
    widths = []
    for ident in successful:
        folder, _ = folders[ident]
        if states[-1]["jobs"].get("impact-" + ident, {}).get("status") not in engine.SATISFIED:
            continue
        cp = folder / "widths-config.json"
        save_once(cp, {"metadata": metadata, "binary": binary, "decoder": config["decoder"], "candidates": successful[ident], "impact": pin(folder / "impact.json"),
                       "thresholdM2": 1000, "output": str(folder / "widths.json")})
        widths.append(helper_job(config, "widths", cp, "widths-" + ident))
    states.append(run_wave("overlap-widths", widths))
    return states, issues, {ident: str(folder / "impact.json") for ident, (folder, _) in folders.items() if ident in successful}
