"""UTC intake gate and direct-child launcher; no source/evidence writes.

safe_start is the *latest* permitted start, not the beginning of the window.
CLI checks use the actual UTC clock, with no --now override. To launch an
existing engine command, run from its existing cwd and append -- EXE ARGS:
  python /path/work_window_guard.py --safe-start RFC3339 --hard-deadline RFC3339
    --minimum-remaining-seconds 60 --record /existing/dir/execution.json -- EXE ARGS
Do not check separately and then call Start-Process: that creates a stale gate.
Each --record path must be new; exclusive reservation prevents receipt reuse.
Child output goes to stderr; stdout contains one JSON result. The environment
(including proxy settings) is inherited unchanged. Only the direct Popen child
is supervised; commands which detach or create unsupervised children are unsuitable.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import uuid

UTC = timezone.utc
RFC3339 = re.compile(
    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(Z|[+-]\d{2}:\d{2})\Z"
)


def parse_utc(value: str) -> datetime:
    """Require a numeric, known timezone; return an aware UTC datetime."""
    match = RFC3339.fullmatch(value) if isinstance(value, str) else None
    if not match:
        raise ValueError("timestamp must be RFC3339 with Z or an explicit offset")
    offset = match.group(1)
    if offset == "-00:00":  # RFC3339's unknown local offset is not an actual zone.
        raise ValueError("unknown timezone offset -00:00 is forbidden")
    if offset != "Z" and (int(offset[1:3]) > 23 or int(offset[4:6]) > 59):
        raise ValueError("invalid timezone offset")
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)
    except (ValueError, OverflowError) as exc:
        raise ValueError("invalid RFC3339 timestamp") from exc


def _utc_now() -> datetime:
    return datetime.now(UTC)


def _stamp(value: datetime) -> str:
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _config(safe_start, hard_deadline, minimum_remaining_seconds):
    safe, hard = parse_utc(safe_start), parse_utc(hard_deadline)
    if safe >= hard:
        raise ValueError("safe_start must precede hard_deadline")
    if isinstance(minimum_remaining_seconds, bool):
        raise ValueError("minimum remaining seconds must be a finite nonnegative number")
    try:
        minimum = float(minimum_remaining_seconds)
    except (TypeError, ValueError, OverflowError) as exc:
        raise ValueError("minimum remaining seconds must be a finite nonnegative number") from exc
    if not math.isfinite(minimum) or minimum < 0:
        raise ValueError("minimum remaining seconds must be a finite nonnegative number")
    return safe, hard, minimum


def _evaluate(safe, hard, minimum, now):
    """Pure boundary calculation; production entry points supply the real clock."""
    if not isinstance(now, datetime) or now.tzinfo is None or now.utcoffset() is None:
        raise ValueError("clock must be an aware datetime")
    now = now.astimezone(UTC)
    remaining = (hard - now).total_seconds()
    reason = ("hard_deadline_reached" if now >= hard else
              "safe_start_reached" if now >= safe else
              "insufficient_remaining_time" if remaining <= minimum else None)
    return {"status": "DENIED" if reason else "ALLOWED", "reason": reason,
            "checkedAtUtc": _stamp(now), "safeStartUtc": _stamp(safe),
            "hardDeadlineUtc": _stamp(hard), "remainingSeconds": remaining,
            "minimumRemainingSeconds": minimum}


def check_window(safe_start, hard_deadline, minimum_remaining_seconds=0):
    """Check actual current UTC; equal start/deadline/estimate boundaries deny."""
    return _evaluate(*_config(safe_start, hard_deadline, minimum_remaining_seconds), _utc_now())


def _reserve_record(path, result):
    """Exclusive creation: a reused path can never become this run's receipt."""
    if path is None:
        return None
    target, owner = Path(path), uuid.uuid4().hex
    result["recordOwner"] = owner
    with target.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(result, indent=2) + "\n")
    return target, owner


def _write_record(receipt, result):
    if receipt is not None:
        target, owner = receipt
        if json.loads(target.read_text(encoding="utf-8")).get("recordOwner") != owner:
            raise ValueError("receipt ownership changed")
        result["recordOwner"] = owner
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=target.parent,
                                             prefix=target.name + ".", suffix=".tmp", delete=False) as stream:
                temporary = Path(stream.name)
                stream.write(json.dumps(result, indent=2) + "\n")
            os.replace(temporary, target)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)


def _stop_child(process):
    # No PID enumeration, taskkill, process-name match, or unrelated process kill.
    if process.poll() is None:
        try:
            process.kill()  # Hard stop of the process object we created ourselves.
        except ProcessLookupError:
            pass
    return process.wait(timeout=5)


def launch_guarded(safe_start, hard_deadline, argv, minimum_remaining_seconds=0, record=None):
    """Launch shell=False from the current cwd, and enforce the hard deadline."""
    config = _config(safe_start, hard_deadline, minimum_remaining_seconds)
    command = list(argv)
    if not command or not isinstance(command[0], str) or not command[0] or any(
            not isinstance(arg, str) for arg in command):
        raise ValueError("command must contain a nonempty executable and string arguments")
    if os.name == "nt" and Path(command[0]).suffix.lower() in (".bat", ".cmd"):
        raise ValueError("batch executables require a shell and are unsupported")
    if record is not None and not Path(record).parent.is_dir():
        raise ValueError("record parent directory must already exist")
    result = _evaluate(*config, _utc_now())
    result.update(argv=command, cwd=os.getcwd(), processId=None, startedAtUtc=None,
                  finishedAtUtc=None, exitCode=None, timedOut=False)
    receipt = _reserve_record(record, result)  # All setup precedes the final gate.
    if result["status"] == "DENIED":
        return result
    # Anchor before spawning: OS creation and a wall-clock rollback consume budget.
    monotonic_anchor, gate_time = time.monotonic(), _utc_now()
    result.update(_evaluate(*config, gate_time))
    monotonic_deadline = monotonic_anchor + max(0, (config[1] - gate_time).total_seconds())
    if result["status"] == "DENIED":
        _write_record(receipt, result)
        return result
    try:
        process = subprocess.Popen(command, shell=False, stdin=subprocess.DEVNULL,
                                   stdout=sys.stderr, stderr=sys.stderr)
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        result.update(status="DENIED", reason="launch_failed", error=str(exc),
                      finishedAtUtc=_stamp(_utc_now()))
        _write_record(receipt, result)
        return result
    try:
        # Ownership cleanup covers every operation after Popen succeeds.
        result.update(status="RUNNING", processId=process.pid)
        started = _utc_now()
        result["startedAtUtc"] = _stamp(started)
        # Recheck after OS creation too: scheduling delay must not become a free run.
        post_spawn = _evaluate(*config, started)
        if post_spawn["status"] == "DENIED":
            result.update(status="DENIED", reason="gate_expired_during_spawn")
            _stop_child(process)
        else:
            _write_record(receipt, result)
            while process.poll() is None:
                remaining = min((config[1] - _utc_now()).total_seconds(),
                                monotonic_deadline - time.monotonic())
                if remaining <= 0:
                    result.update(status="DENIED", reason="hard_deadline_reached", timedOut=True)
                    _stop_child(process)
                    break
                try:
                    process.wait(timeout=min(remaining, 0.05))
                except subprocess.TimeoutExpired:
                    pass
            if result["status"] == "RUNNING":
                result.update(status="COMPLETED", reason=None)
    finally:
        # Includes record-write failures and interruption; never leave our child live.
        if process.poll() is None:
            _stop_child(process)
        ended = _utc_now()
        if result["status"] == "COMPLETED" and (ended >= config[1] or time.monotonic() >= monotonic_deadline):
            result.update(status="DENIED", reason="hard_deadline_reached", timedOut=True)
        if result["status"] == "RUNNING":
            result.update(status="DENIED", reason="launcher_interrupted")
        result.update(finishedAtUtc=_stamp(ended), exitCode=process.returncode)
        _write_record(receipt, result)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--safe-start", required=True)
    parser.add_argument("--hard-deadline", required=True)
    parser.add_argument("--minimum-remaining-seconds", type=float, default=0)
    parser.add_argument("--record", type=Path)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    try:
        if command:
            result = launch_guarded(args.safe_start, args.hard_deadline, command,
                                    args.minimum_remaining_seconds, args.record)
        else:
            result = check_window(args.safe_start, args.hard_deadline, args.minimum_remaining_seconds)
            _reserve_record(args.record, result)
    except (ValueError, TypeError, OSError, subprocess.SubprocessError) as exc:
        result = {"status": "DENIED", "reason": "invalid_input_or_launch_error", "error": str(exc)}
    print(json.dumps(result))
    if result["status"] == "DENIED":
        return 2
    return 0 if result.get("exitCode") in (None, 0) else 1


if __name__ == "__main__":
    raise SystemExit(main())
