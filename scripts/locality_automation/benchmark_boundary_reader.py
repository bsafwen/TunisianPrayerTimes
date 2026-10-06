"""One direct read-only phase for counterbalanced unchanged-reader experiments."""
import argparse
from contextlib import nullcontext
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation.packed_decode_cache import reuse_reader_packed_slices


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--mode", choices=("baseline", "cache"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--compare", type=Path)
    args = parser.parse_args()
    manifest = read(args.manifest)
    reader = checked(manifest["reader"])
    run = read(checked(manifest["run"]))
    for reference in manifest.get("assets", []):
        checked(reference)
    cache_source = Path(__file__).with_name("packed_decode_cache.py")
    if manifest.get("decodeCache"):
        checked(manifest["decodeCache"])
    spec = importlib.util.spec_from_file_location("unchanged_measured_reader", reader)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    context = reuse_reader_packed_slices(module, manifest["reader"], manifest["decoder"]) if args.mode == "cache" else nullcontext(None)
    started = time.perf_counter()
    with context as cache_statistics:
        events, issues, summary = module.current_geometry_validations(run, root=Path(manifest["evidenceRoot"]))
    seconds = time.perf_counter() - started
    actual = {"events": events, "issues": issues, "summary": summary}
    if issues:
        raise ValueError({"readerIssues": issues})
    if args.compare and actual != read(args.compare)["actualReaderOutput"]:
        raise ValueError("Entire unchanged reader output differs from baseline")
    for reference in manifest.get("assets", []):
        checked(reference)
    result = {"status": "PASS_UNCHANGED_READER_MEASUREMENT", "mode": args.mode,
        "payloadPid": os.getpid(), "atUtc": datetime.now(timezone.utc).isoformat(),
        "elapsedSeconds": seconds, "cacheStatistics": cache_statistics, "actualReaderOutput": actual,
        "entireReaderOutputCompared": bool(args.compare), "manifest": pin(args.manifest),
        "cacheSource": pin(cache_source), "readerUnmodified": True, "freshReadsAndHashesRetained": True,
        "liveWrites": False, "sourceScopeOrGpsPolicyChanged": False, "newCredit": 0}
    with args.output.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"status": result["status"], "mode": args.mode, "seconds": seconds,
                      "events": len(events), "cacheStatistics": cache_statistics, "entireOutputCompared": bool(args.compare)}))


if __name__ == "__main__":
    main()
