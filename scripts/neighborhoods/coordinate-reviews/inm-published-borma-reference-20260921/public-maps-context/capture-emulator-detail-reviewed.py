#!/usr/bin/env python3
"""Read-only evidence recorder for an already-open public Maps viewport."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

SCRIPT = Path(__file__).resolve()
TASK_NAME = "borma-border-context-20260921"

if SCRIPT.parent.name == TASK_NAME:
    WORK = SCRIPT.parent.parent
    TASK_WORK = SCRIPT.parent
else:
    WORK = SCRIPT.parent
    TASK_WORK = WORK / TASK_NAME

HELPER = WORK / "review_phone_catalog_v4.py"
HELPER_SHA256 = "1f8351c8f9679563dc159834e353f7d92dfac884c4d90021c42399a10e0ec902"

ALLOWED_SERIALS = (
    "emulator-5554",
    "emulator-5556",
    "emulator-5558",
    "emulator-5560",
    "emulator-5562",
)


def _strict_child(parent: Path, child: Path) -> bool:
    parent = parent.resolve()
    child = child.resolve()
    return parent != child and parent in child.parents


def _resolve_out_dir(raw: str) -> Path:
    task = TASK_WORK.resolve()
    if not task.is_dir():
        raise SystemExit(f"task work directory does not exist: {task}")
    candidate = Path(raw).expanduser()
    if not candidate.is_absolute():
        candidate = task / candidate
    out = candidate.resolve()
    if not _strict_child(task, out):
        raise SystemExit(f"--out-dir must be a strict child of {task}")
    if out.exists() and not out.is_dir():
        raise SystemExit(f"--out-dir exists but is not a directory: {out}")
    return out


def _ensure_out_dir(out: Path) -> None:
    if not out.exists():
        out.mkdir(parents=True)
    if not out.is_dir():
        raise SystemExit(f"--out-dir is not a directory: {out}")


def _load_helper():
    if not HELPER.is_file():
        raise SystemExit(f"helper not found: {HELPER}")
    actual = hashlib.sha256(HELPER.read_bytes()).hexdigest()
    if actual != HELPER_SHA256:
        raise SystemExit(f"helper SHA-256 mismatch: {actual}")

    spec = importlib.util.spec_from_file_location("review_phone_catalog_v4", str(HELPER))
    if spec is None or spec.loader is None:
        raise SystemExit("could not import helper")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    # The pinned helper is imported unchanged. These assignments only supply
    # module globals if the local read-only helper file omits its prologue.
    if not hasattr(module, "subprocess"):
        module.subprocess = subprocess
    if not hasattr(module, "ET"):
        module.ET = ET
    if not hasattr(module, "re"):
        module.re = re
    if not hasattr(module, "ADB"):
        adb = shutil.which("adb")
        if not adb:
            raise SystemExit("adb not found on PATH")
        module.ADB = adb
    return module


def _verify_emulator(module, args) -> None:
    state = module.adb(args.serial, "get-state").strip()
    if state != "device":
        raise SystemExit(f"adb get-state for {args.serial} is {state!r}, not device")

    raw = module.adb(args.serial, "emu", "avd", "name")
    lines = [line.strip() for line in raw.splitlines() if line.strip()]
    if not lines or lines[0] != args.avd:
        raise SystemExit(f"emulator AVD name mismatch: expected {args.avd!r}, got {raw!r}")


def _resumed_record(module, serial: str) -> str:
    activity = module.adb(serial, "shell", "dumpsys", "activity", "activities")
    top = None
    mres = None
    for line in activity.splitlines():
        if "topResumedActivity=" in line:
            top = line.split("topResumedActivity=", 1)[1].strip()
            break
        if "mResumedActivity=" in line and mres is None:
            mres = line.split("mResumedActivity=", 1)[1].strip()
    record = top if top is not None else mres
    if not record:
        raise RuntimeError("could not determine topResumedActivity/mResumedActivity")
    return record


def _ensure_maps_foreground(module, serial: str) -> str:
    record = _resumed_record(module, serial)
    match = re.search(r"u[0-9]+ +([A-Za-z0-9_]+(?:[.][A-Za-z0-9_]+)+)/", record)
    if match is None or match.group(1) != "com.google.android.apps.maps":
        raise RuntimeError(f"Maps is not foreground; actual resumed activity: {record}")
    return record


def _valid_png(blob: bytes) -> bool:
    if not blob.startswith(b"\x89PNG\r\n\x1a\n"):
        return False
    pos = 8
    while pos + 12 <= len(blob):
        length = int.from_bytes(blob[pos : pos + 4], "big")
        chunk_type = blob[pos + 4 : pos + 8]
        end = pos + 12 + length
        if end > len(blob):
            return False
        if chunk_type == b"IEND":
            return length == 0 and end == len(blob)
        pos = end
    return False


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Record public Maps viewport evidence read-only."
    )
    parser.add_argument("--serial", required=True, choices=ALLOWED_SERIALS)
    parser.add_argument("--avd", required=True)
    parser.add_argument("--id", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--context", required=True)
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()

    if not re.fullmatch(r"[a-z0-9-]{1,50}", args.label):
        raise SystemExit("--label must match [a-z0-9-]{1,50}")
    safe_id = re.sub(r"[^A-Za-z0-9_-]", "_", args.id)[:80]
    if not safe_id.strip("_"):
        raise SystemExit("--id must contain at least one filename-safe character")
    if not args.avd.strip():
        raise SystemExit("--avd must be non-empty")

    out_dir = _resolve_out_dir(args.out_dir)
    module = _load_helper()
    _verify_emulator(module, args)
    _ensure_out_dir(out_dir)

    _ensure_maps_foreground(module, args.serial)
    pre_ui = module.public_ui(args.serial)

    _ensure_maps_foreground(module, args.serial)
    image = module.adb(args.serial, "exec-out", "screencap", "-p", binary=True)
    if not _valid_png(image):
        raise SystemExit("screencap did not return a well-formed PNG")

    _ensure_maps_foreground(module, args.serial)
    post_ui = module.public_ui(args.serial)
    _ensure_maps_foreground(module, args.serial)

    png_sha = hashlib.sha256(image).hexdigest()
    pre_text = json.dumps(pre_ui, ensure_ascii=False, separators=(",", ":"))
    post_text = json.dumps(post_ui, ensure_ascii=False, separators=(",", ":"))
    pre_sha = hashlib.sha256(pre_text.encode("utf8")).hexdigest()
    post_sha = hashlib.sha256(post_text.encode("utf8")).hexdigest()
    exact_match = pre_ui == post_ui

    stem = safe_id + "-detail-" + args.label
    png_path = out_dir / f"{stem}.png"
    json_path = out_dir / f"{stem}.json"
    if png_path.exists() or json_path.exists():
        raise SystemExit(f"refusing to overwrite existing evidence: {png_path} / {json_path}")

    if hasattr(module, "now"):
        observed_at = module.now()
    else:
        observed_at = datetime.now(timezone.utc).isoformat()
    if not isinstance(observed_at, str):
        observed_at = str(observed_at)

    script_sha = hashlib.sha256(SCRIPT.read_bytes()).hexdigest()

    doc = {
        "schemaVersion": 1,
        "id": args.id,
        "label": args.label,
        "observedAt": observed_at,
        "avd": args.avd,
        "serial": args.serial,
        "interactionContext": args.context,
        "prePublicUiText": pre_ui,
        "postPublicUiText": post_ui,
        "prePublicUiTextSha256": pre_sha,
        "postPublicUiTextSha256": post_sha,
        "uiSignatureExactMatch": exact_match,
        "reviewRequired": not exact_match,
        "boundaryVerified": False,
        "geographicApproval": False,
        "nameApproval": False,
        "approvalClaimed": False,
        "screenshot": png_path.name,
        "screenshotSha256": png_sha,
        "scriptSha256": script_sha,
        "helperSha256": HELPER_SHA256,
        "scope": "Supplementary selected public Maps viewport evidence; no ledger or product writes",
    }

    with png_path.open("xb") as f:
        f.write(image)
    with json_path.open("x", encoding="utf8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")

    result = {
        "record": {
            "file": str(json_path),
            "sha256": hashlib.sha256(json_path.read_bytes()).hexdigest(),
        },
        "screenshot": {
            "file": str(png_path),
            "sha256": png_sha,
        },
        "scriptSha256": script_sha,
        "helperSha256": HELPER_SHA256,
        "uiSignatureExactMatch": exact_match,
        "reviewRequired": doc["reviewRequired"],
    }
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
