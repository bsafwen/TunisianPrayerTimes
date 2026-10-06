"""Profile the unchanged read-only validation reader on a pinned run snapshot."""
import argparse
import cProfile
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import pstats
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest = read(args.manifest)
    run = read(checked(manifest["run"]))
    reader = checked(manifest["reader"])
    spec = importlib.util.spec_from_file_location("unchanged_profiled_reader", reader)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    profile = cProfile.Profile()
    started = time.perf_counter()
    events, issues, summary = profile.runcall(module.current_geometry_validations, run, root=Path(manifest["evidenceRoot"]))
    seconds = time.perf_counter() - started
    if issues:
        raise ValueError({"readerIssues": issues})
    stats = pstats.Stats(profile)
    top = []
    for (file, line, name), (primitive, calls, self_seconds, cumulative, callers) in sorted(stats.stats.items(), key=lambda item: item[1][3], reverse=True)[:35]:
        top.append({"file": file, "line": line, "function": name, "calls": calls,
                    "primitiveCalls": primitive, "selfSeconds": self_seconds, "cumulativeSeconds": cumulative})
    result = {"status": "PASS_UNCHANGED_READER_PROFILE", "payloadPid": os.getpid(),
        "atUtc": datetime.now(timezone.utc).isoformat(), "elapsedProfiledSeconds": seconds,
        "eventCount": len(events), "summary": summary, "issues": issues, "topCumulativeFunctions": top,
        "newCredit": 0, "productionCodeChanged": False, "controlledSpeedupClaimed": False,
        "qualification": "Single instrumented run; cumulative times overlap and profiling adds overhead. Use to locate work, not as a controlled speed comparison.",
        "manifest": pin(args.manifest)}
    with args.output.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"status": result["status"], "seconds": seconds, "events": len(events), "top": top[:8]}))


if __name__ == "__main__":
    main()
