"""Small, explicit file/provenance helpers shared by the automation commands."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from datetime import datetime, timezone


def now():
    return datetime.now(timezone.utc).isoformat()


def digest(path):
    with Path(path).open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def pin(path):
    p = Path(path).resolve()
    if not p.is_file():
        raise ValueError(f"Missing file: {p}")
    return {"file": str(p), "sha256": digest(p)}


def verify(item):
    p = Path(item["file"]).resolve()
    if not re.fullmatch(r"[a-f0-9]{64}", item.get("sha256", "")):
        raise ValueError(f"Invalid fingerprint: {p}")
    if not p.is_file() or digest(p) != item["sha256"]:
        raise ValueError(f"File changed or missing: {p}")
    return p


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def read_pin(item):
    p = verify(item)
    raw = p.read_bytes()
    if hashlib.sha256(raw).hexdigest() != item["sha256"]:
        raise ValueError(f"File changed while reading: {p}")
    return json.loads(raw.decode("utf-8-sig"))


def write(path, value, *, replace=False):
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    if not replace:
        with p.open("x", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
        return pin(p)
    fd, tmp = tempfile.mkstemp(prefix=p.name + ".", dir=p.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, p)
    finally:
        if Path(tmp).exists():
            Path(tmp).unlink()
    return pin(p)


def pins_in(value):
    """Collect explicit file/hash objects, not arbitrary paths or old history."""
    found = {}
    def visit(v):
        if isinstance(v, dict):
            if isinstance(v.get("file"), str) and "sha256" in v:
                p = str(Path(v["file"]).resolve())
                if p in found and found[p]["sha256"] != v["sha256"]:
                    raise ValueError(f"Conflicting input fingerprints: {p}")
                found[p] = {"file": p, "sha256": v["sha256"]}
            else:
                for x in v.values():
                    visit(x)
        elif isinstance(v, list):
            for x in v:
                visit(x)
    visit(value)
    return list(found.values())


def inside(path, root):
    p, r = Path(path).resolve(), Path(root).resolve()
    if p == r or not p.is_relative_to(r):
        raise ValueError(f"Output must be below {r}: {p}")
    return p


def slug(value):
    return re.sub(r"[^A-Za-z0-9_-]", "_", str(value))


def module(item, name):
    p = verify(item)
    spec = importlib.util.spec_from_file_location(name, p)
    if spec is None or spec.loader is None:
        raise ValueError(f"Cannot load helper {p}")
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result
