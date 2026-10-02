"""Exact ordered complete source-PDF and original-QA binding; no geometry work."""
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
    row = original_qa["decodedCurrentChecks"][code]
    independent = scope.get("independentCheck", {})
    if any("sourcePdf" in item for item in (proof, scope.get("inputs", {}), row, independent)):
        raise ValueError("Ambiguous singular/multipart source evidence")
    if independent != row:
        raise ValueError("Scope must retain the complete exact original independent QA row")
    for key in ("id", "officialCode"):
        if row.get(key) != proof.get(key) or scope.get(key) != proof.get(key):
            raise ValueError("Original QA/scope/proof identity differs")
    if row.get("officialCode") != code:
        raise ValueError("Original QA official code differs")
    for key in ("rawSourceGeometry", "expectedAdoptedGeometry"):
        if row.get(key) != proof.get(key) or scope.get("inputs", {}).get(key) != proof.get(key):
            raise ValueError("Original QA/scope/proof geometry evidence pin differs")
    parts = validate_parts(proof["sourcePdfParts"], row["sourcePdfParts"], checker)
    if scope.get("inputs", {}).get("sourcePdfParts") != parts:
        raise ValueError("Scope/proof/original-QA ordered PDF parts differ")
    return parts
