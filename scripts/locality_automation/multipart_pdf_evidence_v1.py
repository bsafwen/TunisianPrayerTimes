"""Exact ordered original-PDF evidence representation; no geometry or GPS work."""
from pathlib import Path

def validate_parts(parts, expected, checker):
    if not isinstance(parts, list) or len(parts) < 2 or parts != expected:
        raise ValueError("Exact complete ordered multipart PDF list required")
    seen = set()
    for part in parts:
        if not isinstance(part, dict) or not isinstance(part.get("file"), str) or not isinstance(part.get("sha256"), str):
            raise ValueError("Each original PDF part requires its exact pin")
        key = (part["file"], part["sha256"])
        if key in seen:
            raise ValueError("Duplicate original PDF part")
        seen.add(key)
        path = Path(checker(part))
        with path.open("rb") as handle:
            if handle.read(5) != b"%PDF-":
                raise ValueError("Multipart evidence must contain actual original PDFs")
    return parts

def validate_recorded_parts(proof, scope, original_qa, code, checker):
    if "sourcePdf" in proof or "sourcePdf" in scope.get("inputs", {}):
        raise ValueError("Ambiguous singular/multipart source evidence")
    expected = original_qa["decodedCurrentChecks"][code]["sourcePdfParts"]
    parts = validate_parts(proof["sourcePdfParts"], expected, checker)
    if scope.get("inputs", {}).get("sourcePdfParts") != parts or scope.get("independentCheck", {}).get("sourcePdfParts") != parts:
        raise ValueError("Scope/proof/original-QA ordered PDF parts differ")
    return parts
