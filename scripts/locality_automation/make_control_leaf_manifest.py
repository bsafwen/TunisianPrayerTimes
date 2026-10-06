"""Prepare an exclusive direct-leaf manifest without executing its program.

UTC strings are copied literally from the authorized control. The existing
guarded_control_phase launcher remains responsible for execution and cutoff.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, read
from scripts.locality_automation.work_window_guard import parse_utc


def build(control_path, program_path, phase, arguments):
    control = read(control_path)
    if phase not in ("source", "intake", "map"):
        raise ValueError("Explicit source, intake or map leaf required")
    for field in ("windowStartUtc", "deadlineUtc"):
        if type(control[field]) is not str:
            raise ValueError("Literal aware UTC control strings required")
        parse_utc(control[field])
    program = program_path.resolve(strict=True)
    if not program.is_file() or program.suffix != ".py":
        raise ValueError("Existing Python leaf required")
    if any(type(value) is not str for value in arguments):
        raise ValueError("Literal argument strings required")
    spec = {"control": str(control_path.resolve(strict=True)),
            "iteration": control["iteration"], "owner": "/root", "phase": phase,
            "windowStartUtc": control["windowStartUtc"], "deadlineUtc": control["deadlineUtc"],
            "program": {"file": str(program), "sha256": hashlib.sha256(program.read_bytes()).hexdigest()},
            "arguments": arguments}
    active_control(spec)
    return spec


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--control", required=True, type=Path)
    parser.add_argument("--program", required=True, type=Path)
    parser.add_argument("--phase", required=True, choices=("source", "intake", "map"))
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("arguments", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    values = args.arguments[1:] if args.arguments[:1] == ["--"] else args.arguments
    output = args.output.resolve()
    if output.parent != Path.cwd().resolve():
        raise ValueError("Manifest must be created in the existing producer cwd")
    spec = build(args.control, args.program, args.phase, values)
    with output.open("x", encoding="utf-8") as stream:
        json.dump(spec, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"manifest": str(output), "phase": args.phase, "executionStarted": False}))


if __name__ == "__main__":
    main()
