"""Build a deterministic, post-run locality review queue.

The builder is pure: it reads no files, invokes no model, and never mutates its
inputs.  Callers pass the completed automation ledger, flattened batch
``cases.json`` rows, the merged investigation rows, pending feedback, current
catalog locations, and any explicit human review outcomes.

Only structured case links create atomic groups.  Free-text investigations
are retained as compact review context, but their incidental ID mentions are
never interpreted as graph edges.
"""
from __future__ import annotations

import hashlib
import json
import re
import unicodedata
from collections import defaultdict
from collections.abc import Iterable, Mapping, Sequence
from typing import Any


SCHEMA_VERSION = "locality-review-queue/1"
DEFAULT_GOVERNORATE = "بن عروس"

PRIORITY_PROBLEM_REPORT = 0
PRIORITY_PENDING_REPORT = 10
PRIORITY_STALE_PROBLEM_REPORT = 15
PRIORITY_IDENTITY_OR_GPS = 20
PRIORITY_BOUNDARY = 30
PRIORITY_GENERAL = 40

MAX_ISSUES_PER_UNIT = 12
MAX_PRIOR_ADVICE_PER_UNIT = 4
MAX_EVIDENCE_PER_UNIT = 24
MAX_TEXT_CHARS = 800

_IDENTITY_CHECKS = {
    "existence", "locality_type", "arabic_name", "french_search",
    "governorate", "delegation", "duplicates", "identity",
}
_GPS_CHECKS = {"gps_resolution", "gps", "coordinates", "coordinate"}
_BOUNDARY_CHECKS = {"boundary", "shared_boundary", "boundary_conflict", "border"}

# These keys are explicit links when present as structured JSON fields.  Do
# not add prose fields here: investigation text is context, not topology.
_LINK_KEYS = {
    "candidateId", "candidateIds", "relatedCaseId", "relatedCaseIds",
    "neighborCaseId", "neighborCaseIds", "neighborIds", "adjacentCaseId",
    "adjacentCaseIds", "geometryAdjacentCaseIds", "geometryConflictIds",
    "boundaryConflictId", "boundaryConflictIds", "sharedBoundaryCaseIds",
    "overlapCaseIds", "overlapsCaseId", "sharedBoundaryWith",
    "conflictsWithCaseId", "conflictsWithCaseIds", "borderDependencyIds",
}
_GROUP_KEYS = {
    "sharedBoundaryGroupId", "boundaryGroupId", "boundaryConflictGroupId",
    "atomicReviewGroupId", "geometryConflictGroupId",
}
_TERMINAL_OUTCOMES = {
    "ACCEPTED", "HUMAN_ACCEPTED", "REJECTED", "HUMAN_REJECTED",
    "RESOLVED", "HUMAN_RESOLVED",
}


def _canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), allow_nan=False)


def _digest(value: Any) -> str:
    return hashlib.sha256(_canonical(value).encode("utf-8")).hexdigest()


def _clean_text(value: Any, limit: int = MAX_TEXT_CHARS) -> str | None:
    if not isinstance(value, str):
        return None
    text = " ".join(value.split())
    if not text:
        return None
    if len(text) > limit:
        return text[:limit - 1].rstrip() + "…"
    return text


def _normalize_name(value: Any) -> str | None:
    text = _clean_text(value, 240)
    return unicodedata.normalize("NFKC", text).strip() if text else None


def _as_sequence(value: Any, *, field: str) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, (str, bytes)) or not isinstance(value, Sequence):
        raise TypeError(f"{field} must be a JSON array or object")
    return list(value)


def _ledger_rows(value: Any) -> list[dict[str, Any]]:
    if value is None:
        return []
    rows: Any = value.get("cases", value) if isinstance(value, Mapping) else value
    if isinstance(rows, Mapping):
        rows = list(rows.values())
    result = []
    for row in _as_sequence(rows, field="case_ledger"):
        if isinstance(row, Mapping) and isinstance(row.get("id"), str) and row["id"].strip():
            result.append(dict(row))
    return result


def _batch_case_rows(value: Any) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Return case rows and batch-level catalog pins from common artifact shapes."""
    rows: list[dict[str, Any]] = []
    pins: list[dict[str, Any]] = []

    def visit(node: Any, batch: str | None = None) -> None:
        if node is None:
            return
        if isinstance(node, Mapping):
            if isinstance(node.get("cases"), list):
                batch_hint = next((node.get(k) for k in ("batch", "directory", "batchPath")
                                   if isinstance(node.get(k), str)), batch)
                catalog = node.get("catalog")
                if isinstance(catalog, Mapping) and isinstance(catalog.get("sha256"), str):
                    pins.append({k: catalog[k] for k in ("file", "sha256") if k in catalog})
                for case in node["cases"]:
                    if isinstance(case, Mapping) and isinstance(case.get("id"), str):
                        item = dict(case)
                        if batch_hint:
                            item["_queueBatchPath"] = batch_hint
                        rows.append(item)
                return
            if isinstance(node.get("id"), str):
                item = dict(node)
                batch_hint = next((node.get(k) for k in ("batchPath", "batch", "directory")
                                   if isinstance(node.get(k), str)), batch)
                if batch_hint:
                    item["_queueBatchPath"] = batch_hint
                rows.append(item)
                return
            for key in ("batches", "batchCases", "cases"):
                child = node.get(key)
                if child is not None:
                    visit(child, batch)
                    return
        elif isinstance(node, Sequence) and not isinstance(node, (str, bytes)):
            for child in node:
                visit(child, batch)

    visit(value)
    return rows, pins


def _issue_rows(value: Any) -> list[dict[str, Any]]:
    if value is None:
        return []
    if isinstance(value, Mapping):
        if isinstance(value.get("issues"), list):
            value = value["issues"]
        elif isinstance(value.get("investigations"), list):
            value = value["investigations"]
        elif isinstance(value.get("report"), Mapping):
            return _issue_rows(value["report"])
        else:
            return []
    rows = []
    for row in _as_sequence(value, field="investigations"):
        if isinstance(row, Mapping) and isinstance(row.get("id"), str) and row["id"].strip():
            if isinstance(row.get("issue"), str):
                rows.append(dict(row))
    return rows


def _feedback_rows(value: Any) -> list[dict[str, Any]]:
    if value is None:
        return []
    if isinstance(value, Mapping):
        if isinstance(value.get("feedback"), Mapping):
            value = value["feedback"]
        rows: list[dict[str, Any]] = []
        found_groups = False
        for key, state in (("pending", "pending"), ("stale", "stale")):
            group = value.get(key)
            if isinstance(group, list):
                found_groups = True
                for row in group:
                    if isinstance(row, Mapping) and isinstance(row.get("id", row.get("caseId")), str):
                        item = dict(row)
                        item["_queueReportState"] = state
                        rows.append(item)
        if found_groups:
            return rows
        if isinstance(value.get("id", value.get("caseId")), str):
            return [dict(value)]
        return []
    rows = []
    for row in _as_sequence(value, field="feedback"):
        if isinstance(row, Mapping) and isinstance(row.get("id", row.get("caseId")), str):
            rows.append(dict(row))
    return rows


def _prior_rows(value: Any) -> list[dict[str, Any]]:
    if value is None:
        return []
    if isinstance(value, Mapping):
        rows: list[dict[str, Any]] = []
        for key in ("dispositions", "items", "reviews"):
            group = value.get(key)
            if isinstance(group, list):
                rows.extend(dict(row) for row in group if isinstance(row, Mapping))
        if rows:
            return rows
        if value.get("sourceFingerprint"):
            return [dict(value)]
        # Accept a case/unit keyed review map while retaining its key as an ID.
        for key, row in value.items():
            if isinstance(row, Mapping):
                item = dict(row)
                item.setdefault("unitId", str(key))
                rows.append(item)
        return rows
    return [dict(row) for row in _as_sequence(value, field="reviewed") if isinstance(row, Mapping)]


def _catalog_rows(value: Any) -> tuple[dict[str, str], str | None, dict[str, dict[str, Any]]]:
    """Map place IDs to current fingerprints, labels, and an optional catalog hash."""
    if value is None:
        return {}, None, {}
    if isinstance(value, Mapping):
        if isinstance(value.get("sha256"), str):
            return {}, value["sha256"], {}
        if isinstance(value.get("locations"), list):
            value = value["locations"]
        elif isinstance(value.get("catalog"), Mapping):
            return _catalog_rows(value["catalog"])
        elif isinstance(value.get("id"), str):
            value = [value]
        else:
            # An ID -> fingerprint (or ID -> location row) map is also useful
            # to pure callers and simple command-line adapters.
            output: dict[str, str] = {}
            location_rows: dict[str, dict[str, Any]] = {}
            for ident, raw in value.items():
                if isinstance(raw, str):
                    output[str(ident)] = raw
                elif isinstance(raw, Mapping):
                    fp = raw.get("fingerprint")
                    output[str(ident)] = fp if isinstance(fp, str) else _digest(dict(raw))
                    location_rows[str(ident)] = dict(raw)
            return output, None, location_rows
    result = {}
    location_rows = {}
    for row in _as_sequence(value, field="catalog_locations"):
        if not isinstance(row, Mapping) or not isinstance(row.get("id"), str):
            continue
        fp = row.get("fingerprint")
        result[row["id"]] = fp if isinstance(fp, str) and fp else _digest(dict(row))
        location_rows[row["id"]] = dict(row)
    return result, None, location_rows


def _catalog_pin_sha(value: Any) -> str | None:
    if isinstance(value, str) and value:
        return value
    if isinstance(value, Mapping) and isinstance(value.get("sha256"), str):
        return value["sha256"]
    return None


def _grouped_historical_owners(
    ledger_by_id: Mapping[str, Mapping[str, Any]],
    catalog_fingerprints: Mapping[str, str],
    catalog_rows: Mapping[str, Mapping[str, Any]],
    picker_group_features: Any,
    governorate: str,
) -> dict[str, str]:
    """Find exact historical members of a currently selectable picker group.

    This deliberately uses only exact feature IDs and ``pickerGroupId`` values.
    A member is recognized only when the group owner is itself present in the
    current catalog and has a self-owned picker group. Name similarity and
    missing catalog IDs alone never suppress a ledger case.
    """
    if isinstance(picker_group_features, Mapping):
        picker_group_features = picker_group_features.get("features", [])
    rows = _as_sequence(picker_group_features, field="picker_group_features")
    by_id: dict[str, Mapping[str, Any]] = {}
    ambiguous_ids: set[str] = set()
    for row in rows:
        if not isinstance(row, Mapping):
            continue
        ident, group_id = row.get("id"), row.get("pickerGroupId")
        if not isinstance(ident, str) or not ident or not isinstance(group_id, str) or not group_id:
            continue
        if ident in by_id:
            by_id.pop(ident, None)
            ambiguous_ids.add(ident)
        elif ident not in ambiguous_ids:
            by_id[ident] = row

    members_by_owner: dict[str, list[str]] = defaultdict(list)
    for ident, row in by_id.items():
        members_by_owner[row["pickerGroupId"]].append(ident)

    result: dict[str, str] = {}
    for owner_id, member_ids in members_by_owner.items():
        owner_row = catalog_rows.get(owner_id)
        owner_feature = by_id.get(owner_id)
        if (owner_id not in catalog_fingerprints or owner_row is None or owner_feature is None
                or owner_feature.get("pickerGroupId") != owner_id
                or _normalize_name(owner_row.get("governorateAr")) != governorate):
            continue
        for member_id in member_ids:
            ledger_row = ledger_by_id.get(member_id)
            if (member_id == owner_id or member_id in catalog_fingerprints or ledger_row is None
                    or _normalize_name(ledger_row.get("governorate")) != governorate):
                continue
            result[member_id] = owner_id
    return result


def _select_case(ident: str, ledger: Mapping[str, Any], options: Sequence[Mapping[str, Any]]) -> tuple[dict[str, Any], dict[str, Any]]:
    """Return the deterministic selected raw batch row and public case record."""
    target_batch = ledger.get("batch")
    selected_options = list(options)
    if isinstance(target_batch, str):
        norm_target = target_batch.replace("/", "\\").casefold()
        batch_matches = [row for row in selected_options if isinstance(row.get("_queueBatchPath"), str)
                         and row["_queueBatchPath"].replace("/", "\\").casefold() == norm_target]
        if batch_matches:
            selected_options = batch_matches
    selected_case = max(selected_options, key=lambda row: _canonical({k: v for k, v in row.items() if not k.startswith("_queue")}), default={})
    case_aux = dict(selected_case)
    clean_case = {k: v for k, v in selected_case.items()
                  if not k.startswith("_queue") and k not in {
                      "batchPath", "batch", "directory", "catalogPin", "advicePin",
                      "priorAdvicePin", "priorAdvicePins", "packetPin", "priorPacketPin",
                      "priorPacketPins", "priorAdvice",
                  }}
    if not clean_case:
        clean_case = {"id": ident, "governorateAr": ledger.get("governorate"), "role": ledger.get("role")}
    clean_case.setdefault("id", ident)
    clean_case.setdefault("governorateAr", ledger.get("governorate"))
    clean_case.setdefault("role", ledger.get("role"))
    return case_aux, clean_case


def _source_files(value: Any) -> dict[str, list[str]]:
    if value is None:
        return {}
    if not isinstance(value, Mapping):
        raise TypeError("source_files must be a JSON object")
    result = {}
    for key, raw in sorted(value.items(), key=lambda pair: str(pair[0])):
        values = [raw] if isinstance(raw, str) else list(raw) if isinstance(raw, Sequence) and not isinstance(raw, (str, bytes)) else []
        result[str(key)] = sorted({item for item in values if isinstance(item, str) and item})
    return result


def _entity_links(value: Any) -> tuple[set[str], set[str]]:
    """Extract only explicitly structured case links and atomic group keys."""
    links: set[str] = set()
    groups: set[str] = set()

    def walk(node: Any) -> None:
        if isinstance(node, Mapping):
            for key, child in node.items():
                if key in _LINK_KEYS:
                    values = child if isinstance(child, list) else [child]
                    links.update(v.strip() for v in values if isinstance(v, str) and v.strip())
                elif key in _GROUP_KEYS and isinstance(child, str) and child.strip():
                    groups.add(child.strip())
                if isinstance(child, (Mapping, list)):
                    walk(child)
        elif isinstance(node, list):
            for child in node:
                walk(child)

    walk(value)
    return links, groups


def _union_find(ids: Iterable[str]):
    parent = {ident: ident for ident in ids}

    def find(ident: str) -> str:
        while parent[ident] != ident:
            parent[ident] = parent[parent[ident]]
            ident = parent[ident]
        return ident

    def union(left: str, right: str) -> None:
        a, b = find(left), find(right)
        if a != b:
            parent[max(a, b)] = min(a, b)

    return find, union


def _check_tokens(case: Mapping[str, Any]) -> set[str]:
    tokens: set[str] = set()
    for field in ("missingChecks", "reviewRequired"):
        value = case.get(field)
        if isinstance(value, str):
            values = [value]
        elif isinstance(value, Sequence) and not isinstance(value, (str, bytes)):
            values = [v for v in value if isinstance(v, str)]
        else:
            values = []
        for token in values:
            tokens.add(re.sub(r"[^a-z0-9_]+", "_", token.lower()).strip("_"))
    return tokens


def _issue_text(row: Mapping[str, Any]) -> str:
    return " ".join(str(row.get("issue", "")).split())


def _classify(case: Mapping[str, Any], issues: Sequence[Mapping[str, Any]], reports: Sequence[Mapping[str, Any]]) -> set[str]:
    categories: set[str] = set()
    tokens = _check_tokens(case)
    if tokens & _IDENTITY_CHECKS:
        categories.add("identity")
    if tokens & _GPS_CHECKS:
        categories.add("gps")
    if tokens & _BOUNDARY_CHECKS or case.get("candidateId"):
        categories.add("boundary")

    for raw in [case.get("reason"), case.get("sourceStatus"), *(_issue_text(row) for row in issues)]:
        text = raw.lower() if isinstance(raw, str) else ""
        if any(word in text for word in ("identity", "locality_type", "arabic_name", "french_search", "governorate", "delegation", "duplicate", "building", "exists as", "locality", "neighborhood", "neighbourhood", "settlement")):
            categories.add("identity")
        if any(word in text for word in ("gps", "coordinate", "latitude", "longitude", "lat =", "lng =", "gps_resolution", "catalog point", "catalog coordinate")):
            categories.add("gps")
        if any(word in text for word in ("boundary", "border", "neighbor", "neighbour", "overlap", "adjacent", "clipped", "ring ")):
            categories.add("boundary")
    if reports:
        categories.add("user_report")
    if not categories:
        categories.add("general_validation")
    return categories


def _report_summary(row: Mapping[str, Any], stale: bool) -> dict[str, Any]:
    assertion = next((row.get(key) for key in ("userAssertion", "assertion", "problem", "note", "issue", "comment", "notes") if row.get(key) is not None), None)
    if isinstance(assertion, Mapping):
        assertion = {str(k): v for k, v in assertion.items() if isinstance(v, (str, int, float, bool)) or v is None}
    if not isinstance(assertion, (str, Mapping, int, float, bool)) and assertion is not None:
        assertion = str(assertion)
    if isinstance(assertion, str):
        assertion = _clean_text(assertion, 1200)
    request_id = row.get("requestId")
    fingerprint = row.get("fingerprint")
    return {
        "requestId": request_id if isinstance(request_id, str) else None,
        "verdict": row.get("verdict") if isinstance(row.get("verdict"), str) else None,
        "userAssertion": assertion,
        "fingerprint": fingerprint if isinstance(fingerprint, str) else None,
        "submittedAt": next((row.get(key) for key in ("submittedAt", "savedAtUtc", "createdAt") if isinstance(row.get(key), str)), None),
        "stale": bool(stale),
    }


def _is_problem(row: Mapping[str, Any]) -> bool:
    state = str(row.get("verdict", row.get("type", ""))).strip().lower()
    assertion = row.get("userAssertion", row.get("assertion", row.get("problem")))
    return state == "problem" or bool(row.get("problem") is True) or (isinstance(assertion, Mapping) and assertion.get("verdict") == "problem")


def _is_stale(row: Mapping[str, Any], current_fingerprint: str | None = None) -> bool:
    state = str(row.get("_queueReportState", row.get("status", ""))).strip().lower()
    if bool(row.get("stale") is True) or state == "stale":
        return True
    report_fingerprint = row.get("fingerprint")
    return bool(isinstance(report_fingerprint, str) and current_fingerprint
                and report_fingerprint.lower() != current_fingerprint.lower())


def _issue_signature(row: Mapping[str, Any]) -> str:
    evidence = row.get("evidence")
    if isinstance(evidence, Mapping):
        evidence_id = {key: evidence.get(key) for key in ("file", "sha256") if isinstance(evidence.get(key), str)}
    else:
        evidence_id = evidence if isinstance(evidence, str) else None
    return _digest({"issue": _issue_text(row), "source": row.get("source"), "evidence": evidence_id})


def _issue_priority(row: Mapping[str, Any]) -> int:
    text = _issue_text(row).lower()
    if any(word in text for word in ("identity", "locality_type", "arabic_name", "french_search", "governorate", "delegation", "duplicate", "building", "existence")):
        return PRIORITY_IDENTITY_OR_GPS
    if any(word in text for word in ("gps", "coordinate", "latitude", "longitude", "gps_resolution", "catalog point")):
        return PRIORITY_IDENTITY_OR_GPS
    if any(word in text for word in ("boundary", "border", "neighbor", "neighbour", "overlap", "adjacent", "clipped", "ring ")):
        return PRIORITY_BOUNDARY
    return PRIORITY_GENERAL


def _concrete_diagnostic_categories(rows: Sequence[Mapping[str, Any]]) -> set[str]:
    """Recognize specific reported conflicts, not routine missing checks."""
    signals: set[str] = set()
    identity_patterns = (
        "identity mismatch", "same entity", "different entity", "different locality",
        "duplicate", "not a locality", "not a distinct", "does not exist",
        "wrong locality", "name conflict", "building/poi",
    )
    gps_patterns = (
        "point outside", "outside the polygon", "outside polygon", "coordinate mismatch",
        "gps mismatch", "wrong coordinate", "wrong gps", "catalog point is wrong",
        "catalog coordinate is wrong", "excludes the saved point", "point is outside",
    )
    boundary_patterns = (
        "boundary conflict", "overlapping boundary", "overlap with", "shared border",
        "boundary hypothesis is ambiguous", "clipped boundary", "different boundary",
        "competing boundary", "candidate geometry conflicts", "excludes the saved point",
    )
    generic_boilerplate = (
        "official source is ambiguous or missing; retained for investigation",
        "model analysis failed or uncertain; no automatic retry",
        "boundary hypothesis is ambiguous, clipped, or excludes the saved point; retained for investigation",
    )
    for row in rows:
        if "advisory" in str(row.get("source", "")).lower():
            continue
        text = _issue_text(row).lower()
        if any(phrase in text for phrase in generic_boilerplate):
            continue
        if any(phrase in text for phrase in identity_patterns):
            signals.add("identity")
        if any(phrase in text for phrase in gps_patterns):
            signals.add("gps")
        if any(phrase in text for phrase in boundary_patterns):
            signals.add("boundary")
        # Some diagnostics carry a typed finding rather than prose.
        kind = row.get("diagnosticType", row.get("issueType", row.get("category")))
        if isinstance(kind, str):
            normalized = kind.strip().lower().replace("-", "_")
            if normalized in {"identity_conflict", "name_conflict", "duplicate_conflict"}:
                signals.add("identity")
            elif normalized in {"gps_conflict", "coordinate_conflict", "point_mismatch"}:
                signals.add("gps")
            elif normalized in {"boundary_conflict", "overlap_conflict", "neighbor_conflict"}:
                signals.add("boundary")
    return signals


def _evidence_for_issue(row: Mapping[str, Any]) -> dict[str, Any] | None:
    evidence = row.get("evidence")
    if isinstance(evidence, Mapping):
        file = evidence.get("file")
        sha = evidence.get("sha256")
        if isinstance(file, str) and isinstance(sha, str):
            return {"kind": "investigation", "file": file, "sha256": sha}
    if isinstance(evidence, str) and evidence:
        return {"kind": "investigation", "file": evidence}
    return None


def _attached_pins(case: Mapping[str, Any], case_id: str, fields: Sequence[str], kind: str) -> list[dict[str, str]]:
    result = []
    for field in fields:
        raw = case.get(field)
        values = raw if isinstance(raw, list) else [raw]
        for value in values:
            if isinstance(value, Mapping):
                pin = _normalize_pin(value)
                if pin:
                    result.append({"kind": kind, "caseId": case_id, **pin})
    unique = {_canonical(row): row for row in result}
    return [unique[key] for key in sorted(unique)]


def _evidence_packet_rows(value: Any, in_scope_ids: set[str]) -> list[dict[str, Any]]:
    if value is None:
        return []
    if isinstance(value, Mapping):
        if isinstance(value.get("evidencePackets"), list):
            value = value["evidencePackets"]
        elif isinstance(value.get("packetFiles"), list):
            value = value["packetFiles"]
        elif isinstance(value.get("caseId"), str) or isinstance(value.get("caseIds"), list):
            value = [value]
        else:
            value = []
    rows = []
    for raw in _as_sequence(value, field="evidence_packets"):
        if not isinstance(raw, Mapping):
            continue
        ids = raw.get("caseIds")
        if not isinstance(ids, list):
            ids = [raw.get("caseId")] if isinstance(raw.get("caseId"), str) else []
        ids = sorted({ident for ident in ids if isinstance(ident, str) and ident in in_scope_ids})
        pin = _normalize_pin(raw.get("pin")) if isinstance(raw.get("pin"), Mapping) else _normalize_pin(raw)
        if not ids or not pin:
            continue
        row = {"caseIds": ids, **pin}
        for key in ("name", "href", "indexFile", "indexSha256"):
            if isinstance(raw.get(key), str):
                row[key] = raw[key]
        rows.append(row)
    unique = {_canonical(row): row for row in rows}
    return [unique[key] for key in sorted(unique)]


def _human_outcome(row: Mapping[str, Any]) -> str | None:
    raw = str(row.get("disposition", row.get("outcome", row.get("status", "")))).strip().upper()
    if raw not in _TERMINAL_OUTCOMES:
        return None
    explicit_human = (
        row.get("humanReviewed") is True
        or row.get("humanDisposition") is True
        or isinstance(row.get("reviewedBy"), str) and bool(row["reviewedBy"].strip())
        or isinstance(row.get("reviewer"), str) and bool(row["reviewer"].strip())
        or raw.startswith("HUMAN_")
    )
    if not explicit_human:
        return None
    if raw.endswith("ACCEPTED"):
        return "ACCEPTED"
    if raw.endswith("REJECTED"):
        return "REJECTED"
    return "RESOLVED"


def _normalize_pin(pin: Mapping[str, Any]) -> dict[str, str] | None:
    file, sha = pin.get("file"), pin.get("sha256")
    if isinstance(file, str) and isinstance(sha, str):
        return {"file": file, "sha256": sha}
    return None


def _stable_unit_id(governorate: str, case_ids: Sequence[str]) -> str:
    return "review:" + _digest({"governorate": governorate, "caseIds": sorted(set(case_ids))})


def _review_index(value: Any, governorate: str) -> dict[tuple[str, str], tuple[str, dict[str, Any]]]:
    result: dict[tuple[str, str], tuple[str, dict[str, Any]]] = {}
    for row in _prior_rows(value):
        outcome = _human_outcome(row)
        fingerprint = row.get("sourceFingerprint")
        if outcome is None or not isinstance(fingerprint, str) or not fingerprint:
            continue
        case_ids = row.get("caseIds")
        if isinstance(case_ids, Sequence) and not isinstance(case_ids, (str, bytes)):
            ids = sorted({x for x in case_ids if isinstance(x, str) and x})
        else:
            single = row.get("caseId")
            ids = [single] if isinstance(single, str) and single else []
        unit_id = row.get("unitId", row.get("reviewUnitId", row.get("id")))
        if (not isinstance(unit_id, str) or not unit_id.startswith("review:")) and ids:
            unit_id = _stable_unit_id(governorate, ids)
        if not isinstance(unit_id, str):
            continue
        review = {
            "unitId": unit_id,
            "caseIds": ids,
            "sourceFingerprint": fingerprint,
            "outcome": outcome,
            "reviewedBy": row.get("reviewedBy", row.get("reviewer")),
            "reviewedAt": row.get("reviewedAt", row.get("recordedAt")),
        }
        result[(unit_id, fingerprint)] = (outcome, review)
    return result


def _short_advice(rows: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    advice = []
    seen = set()
    for row in rows:
        source = str(row.get("source", ""))
        if "advisory" not in source.lower():
            continue
        text = _clean_text(row.get("issue"), 500)
        pin = _evidence_for_issue(row)
        identity = _digest({"text": text, "pin": pin})
        if not text or identity in seen:
            continue
        seen.add(identity)
        advice.append({
            "text": text,
            "source": source,
            "qualification": "UNVERIFIED_ADVISORY_ONLY",
            "evidence": pin,
        })
        if len(advice) >= MAX_PRIOR_ADVICE_PER_UNIT:
            break
    return advice


def _make_question(categories: set[str], case_ids: Sequence[str], external: Sequence[Mapping[str, Any]], reports: Sequence[Mapping[str, Any]]) -> str:
    if external:
        return "Keep the linked boundary decision unresolved until the neighboring governorate can be reviewed with this case as one unit."
    if "boundary" in categories and len(case_ids) > 1:
        return "Which locality identities and shared boundary relationships are supported for these linked records when reviewed as one unit?"
    if reports:
        return "Does the user report identify a concrete identity or GPS problem for this locality, and what supplied evidence resolves it?"
    if "identity" in categories and "gps" in categories:
        return "Is this the distinct locality named in the record, in the stated administrative area, and does the catalog GPS point identify that same place?"
    if "identity" in categories:
        return "What locality identity, Arabic name, and administrative parent are supported by the supplied evidence?"
    if "gps" in categories:
        return "What source-supported coordinate identifies this locality, and does the supplied point refer to the same place?"
    if "boundary" in categories:
        return "What evidence resolves this locality's boundary and its relationship to nearby records?"
    return "Which locality claims remain unresolved, and what authoritative evidence is needed to decide them?"


def build_review_queue(
    governorate: str = DEFAULT_GOVERNORATE,
    *,
    case_ledger: Any,
    batch_cases: Any,
    investigations: Any = (),
    feedback: Any = (),
    reviewed: Any = (),
    catalog_locations: Any = (),
    catalog_pin: Any = None,
    picker_group_features: Any = (),
    source_files: Mapping[str, Any] | None = None,
    evidence_packets: Any = (),
) -> dict[str, Any]:
    """Build a JSON-serializable review queue for exactly one governorate.

    ``case_ledger`` accepts the existing ``{"cases": {key: row}}`` shape (or
    its row list). ``batch_cases`` accepts flattened rows or one or more
    ``cases.json`` objects; callers should include all ledger-referenced
    batches. ``investigations`` accepts merged issue rows or the existing
    ``{"issues": [...]}`` / run-summary shapes. ``feedback`` accepts full
    pending event rows or the report's ``feedback`` object. ``reviewed`` may
    contain prior queue items/dispositions, but suppresses work only for an
    exact fingerprint match with an explicit human outcome.

    ``catalog_locations`` should contain current catalog rows (including each
    row's ``fingerprint``). ``picker_group_features`` may contain pinned helper
    metadata features. Exact historical members grouped under a current
    selectable owner are retained in ``auditItems`` and excluded from active
    case counts; no other missing ID is suppressed. ``catalog_pin`` is a
    conservative fallback SHA-256 for callers that only have a pinned catalog
    file. The queue is advisory review work; automatic passes and model outputs
    never become acceptance.
    """
    selected_governorate = _normalize_name(governorate)
    if not selected_governorate:
        raise ValueError("governorate must be a non-empty string")

    ledger_rows = _ledger_rows(case_ledger)
    all_ledger_by_id: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in ledger_rows:
        all_ledger_by_id[row["id"]].append(row)

    latest_ledger: dict[str, dict[str, Any]] = {}
    for ident, versions in all_ledger_by_id.items():
        versions.sort(key=lambda row: (
            str(row.get("recordedAt", "")),
            str(row.get("batch", "")),
            str(row.get("status", "")),
        ))
        latest_ledger[ident] = versions[-1]

    case_rows, batch_catalog_pins = _batch_case_rows(batch_cases)
    issues_raw = _issue_rows(investigations)
    feedback_raw = _feedback_rows(feedback)
    prior_reviews = _review_index(reviewed, selected_governorate)
    catalog_fingerprints, embedded_catalog_pin, catalog_rows_by_id = _catalog_rows(catalog_locations)

    current_catalog_pin_sha = _catalog_pin_sha(catalog_pin) or embedded_catalog_pin
    fallback_catalog_sha = current_catalog_pin_sha
    if not fallback_catalog_sha:
        # The task CLI may annotate flattened rows with the batch's original
        # catalog pin. This is a conservative fallback when no current
        # per-location catalog fingerprints were supplied.
        for case in case_rows:
            fallback_catalog_sha = _catalog_pin_sha(case.get("catalogPin"))
            if fallback_catalog_sha:
                break
    if not fallback_catalog_sha:
        for pin in batch_catalog_pins:
            if isinstance(pin.get("sha256"), str):
                fallback_catalog_sha = pin["sha256"]
                break

    # Cases come from the ledger, which is the durable cross-governorate index.
    all_in_scope_ledger: dict[str, dict[str, Any]] = {
        ident: row for ident, row in latest_ledger.items()
        if _normalize_name(row.get("governorate")) == selected_governorate
    }
    grouped_historical_owners = _grouped_historical_owners(
        all_in_scope_ledger, catalog_fingerprints, catalog_rows_by_id,
        picker_group_features, selected_governorate,
    )
    # These historical IDs remain visible with their original evidence, but
    # are no longer counted as current selectable cases or review units.
    in_scope_ledger = {
        ident: row for ident, row in all_in_scope_ledger.items()
        if ident not in grouped_historical_owners
    }
    all_governorates = {
        ident: _normalize_name(row.get("governorate"))
        for ident, row in latest_ledger.items()
    }
    catalog_fingerprints_for_scope = {
        ident: catalog_fingerprints.get(ident, fallback_catalog_sha)
        for ident in sorted(in_scope_ledger)
    }
    if current_catalog_pin_sha:
        catalog_fingerprint_source = "current_catalog_pin"
    elif catalog_fingerprints:
        catalog_fingerprint_source = "current_location_fingerprints"
    elif fallback_catalog_sha:
        catalog_fingerprint_source = "batch_catalog_pin_fallback"
    else:
        catalog_fingerprint_source = "not_supplied"

    all_cases_by_id: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in case_rows:
        ident = row.get("id")
        if ident in all_in_scope_ledger:
            all_cases_by_id[ident].append(row)

    # Pick the case payload from the ledger's latest batch where possible.
    current_cases: dict[str, dict[str, Any]] = {}
    case_aux: dict[str, dict[str, Any]] = {}
    for ident, ledger in in_scope_ledger.items():
        case_aux[ident], clean_case = _select_case(ident, ledger, all_cases_by_id.get(ident, []))
        current_cases[ident] = clean_case

    historical_cases: dict[str, dict[str, Any]] = {}
    for ident, owner_id in grouped_historical_owners.items():
        _aux, historical_cases[ident] = _select_case(
            ident, all_in_scope_ledger[ident], all_cases_by_id.get(ident, []),
        )

    all_issues_by_id: dict[str, dict[str, dict[str, Any]]] = defaultdict(dict)
    raw_issue_counts: dict[str, int] = defaultdict(int)
    scoped_issue_count = 0
    for issue in issues_raw:
        ident = issue["id"]
        if ident not in all_in_scope_ledger:
            continue
        scoped_issue_count += 1
        raw_issue_counts[ident] += 1
        signature = _issue_signature(issue)
        all_issues_by_id[ident].setdefault(signature, dict(issue))
    all_issues_by_id_list = {
        ident: sorted(rows.values(), key=lambda row: (
            _issue_priority(row), str(row.get("source", "")), _issue_text(row), _issue_signature(row)
        ))
        for ident, rows in all_issues_by_id.items()
    }
    issues_by_id_list = {ident: rows for ident, rows in all_issues_by_id_list.items()
                         if ident in in_scope_ledger}

    all_reports_by_id: dict[str, list[tuple[dict[str, Any], bool]]] = defaultdict(list)
    for report in feedback_raw:
        ident = report.get("id", report.get("caseId"))
        if ident not in all_in_scope_ledger:
            continue
        stale = _is_stale(report, catalog_fingerprints.get(ident))
        if ident in grouped_historical_owners:
            # A verdict filed against a removed picker row must be rechecked
            # against its current owner, while its original assertion remains.
            stale = True
        all_reports_by_id[ident].append((report, stale))
    for ident, rows in all_reports_by_id.items():
        rows.sort(key=lambda pair: (
            pair[1],
            str(pair[0].get("submittedAt", pair[0].get("createdAt", ""))),
            str(pair[0].get("requestId", "")),
            _canonical(pair[0]),
        ))
    reports_by_id = {ident: rows for ident, rows in all_reports_by_id.items()
                     if ident in in_scope_ledger}

    audit_items: list[dict[str, Any]] = []
    for ident, owner_id in sorted(grouped_historical_owners.items()):
        historical_case = historical_cases[ident]
        owner = catalog_rows_by_id[owner_id]
        audit_issues = all_issues_by_id_list.get(ident, [])
        audit_reports = []
        for report, _stale in all_reports_by_id.get(ident, []):
            summary = _report_summary(report, True)
            summary["caseId"] = ident
            summary["reconciliationRequired"] = True
            summary["stalenessReason"] = "The submitted verdict refers to a picker row now grouped under the current selectable owner. Recheck the assertion against that owner."
            audit_reports.append(summary)
        compact_issues = []
        audit_evidence = []
        for row in audit_issues[:MAX_ISSUES_PER_UNIT]:
            compact_issues.append({
                "caseId": ident,
                "text": _clean_text(row.get("issue"), MAX_TEXT_CHARS),
                "source": _clean_text(row.get("source"), 160),
                "evidence": _evidence_for_issue(row),
            })
            pin = _evidence_for_issue(row)
            if pin:
                audit_evidence.append(pin)
        audit_packets = _evidence_packet_rows(evidence_packets, {ident})
        audit_fingerprint = _digest({
            "schemaVersion": SCHEMA_VERSION,
            "governorate": selected_governorate,
            "historicalMemberId": ident,
            "currentSelectableOwnerId": owner_id,
            "historicalLedger": all_in_scope_ledger[ident],
            "historicalCase": historical_case,
            "issues": sorted([_issue_signature(row) for row in audit_issues]),
            "reports": [_report_summary(row, True) for row, _ in all_reports_by_id.get(ident, [])],
            "pickerGroupOwnerId": owner_id,
            "currentOwnerFingerprint": catalog_fingerprints.get(owner_id),
        })
        audit_items.append({
            "id": "historical-group-member:" + _digest({"member": ident, "owner": owner_id})[:20],
            "historicalMemberId": ident,
            "currentSelectableOwnerId": owner_id,
            "governorate": selected_governorate,
            "nameAr": owner.get("nameAr"),
            "parentAr": owner.get("parentAr"),
            "ownerKind": owner.get("kind"),
            "historicalCaseRecord": historical_case,
            "historicalLedgerRecord": all_in_scope_ledger[ident],
            "reviewScope": "AUDIT_ONLY_HISTORICAL_GROUP_MEMBER",
            "status": "RECONCILIATION_REQUIRED",
            "disposition": "NOT_RESOLVED",
            "reviewGate": "HISTORICAL_GROUP_MEMBER_AUDIT_ONLY",
            "reviewQuestion": "Does the retained evidence for this historical member apply to the current selectable owner? Its grouping does not verify locality identity, GPS, polygon, or prayer-time reliability.",
            "manualReports": audit_reports,
            "unresolvedConflicts": compact_issues,
            "issueCount": len(audit_issues),
            "issuesTruncated": len(audit_issues) > len(compact_issues),
            "evidence": audit_evidence,
            "evidencePackets": audit_packets,
            "sourceFingerprint": audit_fingerprint,
            "currentOwnerFingerprint": catalog_fingerprints.get(owner_id),
            "modelEligible": False,
            "verificationCredit": 0,
            "geographicReliability": "NOT_ASSESSED",
            "qualification": "The historical ID is grouped under a current selectable owner for display only. Original concerns remain open; no identity, coordinate, boundary, or reliability conclusion is implied.",
        })

    case_ids = sorted(in_scope_ledger)
    packet_rows_all = _evidence_packet_rows(evidence_packets, set(all_in_scope_ledger))
    packet_rows_by_scope = [row for row in packet_rows_all
                            if set(row.get("caseIds", [])) & set(case_ids)]
    find, union = _union_find(case_ids)
    structured_links: dict[str, set[str]] = defaultdict(set)
    grouped_ids: dict[str, set[str]] = defaultdict(set)
    for ident in case_ids:
        values = [in_scope_ledger[ident], current_cases[ident], *issues_by_id_list.get(ident, [])]
        for value in values:
            links, group_keys = _entity_links(value)
            structured_links[ident].update(links - {ident})
            for group_key in group_keys:
                grouped_ids[group_key].add(ident)
    for ident, links in structured_links.items():
        for linked in links:
            if linked in in_scope_ledger:
                union(ident, linked)
    for members in grouped_ids.values():
        ordered = sorted(members)
        for linked in ordered[1:]:
            union(ordered[0], linked)

    components: dict[str, list[str]] = defaultdict(list)
    for ident in case_ids:
        components[find(ident)].append(ident)
    component_rows = sorted((sorted(members) for members in components.values()), key=lambda ids: tuple(ids))
    unit_rows: list[dict[str, Any]] = []
    completed_dispositions: list[dict[str, Any]] = []
    case_dispositions: list[dict[str, Any]] = []

    for members in component_rows:
        unit_id = _stable_unit_id(selected_governorate, members)
        unit_issues = [row for ident in members for row in issues_by_id_list.get(ident, [])]
        unit_reports_pairs = [(ident, row, stale) for ident in members for row, stale in reports_by_id.get(ident, [])]
        unit_reports_pairs.sort(key=lambda part: (part[2], part[0], _canonical(part[1])))
        unit_reports = [pair[1] for pair in unit_reports_pairs]
        categories: set[str] = set()
        for ident in members:
            one_reports = [row for row, _ in reports_by_id.get(ident, [])]
            categories.update(_classify(current_cases[ident], issues_by_id_list.get(ident, []), one_reports))
        concrete_signals = _concrete_diagnostic_categories(unit_issues)
        external_links = sorted({
            linked for ident in members for linked in structured_links.get(ident, set())
            if linked not in all_in_scope_ledger
        })
        external = [{"caseId": linked, "governorate": all_governorates.get(linked), "inCurrentQueue": False}
                    for linked in external_links]

        problem_reports = [row for ident, row, stale in unit_reports_pairs if not stale and _is_problem(row)]
        stale_problem_reports = [row for ident, row, stale in unit_reports_pairs if stale and _is_problem(row)]
        fresh_reports = [row for _, row, stale in unit_reports_pairs if not stale]
        if problem_reports:
            priority = PRIORITY_PROBLEM_REPORT
            priority_reasons = ["user_problem_report"]
        elif fresh_reports:
            priority = PRIORITY_PENDING_REPORT
            priority_reasons = ["pending_user_report"]
        elif stale_problem_reports:
            # A changed catalog makes the submitted verdict stale, but its
            # unresolved concern still deserves a prompt human re-check.
            priority = PRIORITY_STALE_PROBLEM_REPORT
            priority_reasons = ["stale_user_problem_needs_reconciliation"]
        elif concrete_signals & {"identity", "gps"}:
            priority = PRIORITY_IDENTITY_OR_GPS
            priority_reasons = sorted(("identity_diagnostic" if c == "identity" else "gps_diagnostic")
                                      for c in concrete_signals & {"identity", "gps"})
        elif "boundary" in concrete_signals or (len(members) > 1 and "boundary" in categories):
            priority = PRIORITY_BOUNDARY
            priority_reasons = ["shared_boundary_or_neighbor_issue"]
        else:
            priority = PRIORITY_GENERAL
            priority_reasons = ["general_unresolved_validation"]

        # Every recorded automatic pass remains unresolved until a human
        # explicitly accepts/rejects/resolves this exact source fingerprint.
        if not unit_issues and not unit_reports and not categories:
            categories.add("general_validation")
        elif "user_report" not in categories and not categories:
            categories.add("general_validation")

        catalog_by_case = {
            ident: catalog_fingerprints_for_scope.get(ident) for ident in members
        }

        issue_source = []
        for row in unit_issues:
            issue_source.append({
                "caseId": row["id"],
                "signature": _issue_signature(row),
                "source": row.get("source"),
                "evidence": row.get("evidence"),
            })
        report_source = []
        for ident, row, stale in unit_reports_pairs:
            report_source.append({"caseId": ident, "report": _report_summary(row, stale),
                                  "raw": {k: row.get(k) for k in ("verdict", "userAssertion", "assertion", "problem", "note", "issue", "fingerprint", "requestId", "submittedAt", "savedAtUtc", "createdAt") if k in row}})
        prior_packet_pins = [
            pin for ident in members
            for pin in _attached_pins(case_aux[ident], ident,
                ("priorPacketPin", "priorPacketPins", "packetPin"), "prior_packet")
        ]
        prior_advice_pins = [
            pin for ident in members
            for pin in _attached_pins(case_aux[ident], ident,
                ("priorAdvicePin", "priorAdvicePins", "advicePin"), "prior_advice")
        ]
        for row in unit_issues:
            pin = _evidence_for_issue(row)
            if pin and "advisory" in str(row.get("source", "")).lower():
                prior_advice_pins.append({"kind": "prior_advice", "caseId": row["id"], **pin})
        prior_packet_pins = sorted({_canonical(row): row for row in prior_packet_pins}.values(), key=_canonical)
        prior_advice_pins = sorted({_canonical(row): row for row in prior_advice_pins}.values(), key=_canonical)
        unit_packets = [row for row in packet_rows_by_scope if set(row["caseIds"]) & set(members)]
        fingerprint_payload = {
            "schemaVersion": SCHEMA_VERSION,
            "governorate": selected_governorate,
            "caseIds": members,
            "ledger": [{"id": ident, "governorate": in_scope_ledger[ident].get("governorate"),
                        "role": in_scope_ledger[ident].get("role"), "status": in_scope_ledger[ident].get("status"),
                        "verificationCredit": in_scope_ledger[ident].get("verificationCredit")}
                       for ident in members],
            "cases": [current_cases[ident] for ident in members],
            "catalogFingerprints": catalog_by_case,
            "priorPacketPins": prior_packet_pins,
            "priorAdvicePins": prior_advice_pins,
            "evidencePackets": unit_packets,
            "issues": sorted(issue_source, key=_canonical),
            "feedback": sorted(report_source, key=_canonical),
            "structuredLinks": {ident: sorted(structured_links.get(ident, set())) for ident in members},
            "externalDependencies": external_links,
        }
        source_fingerprint = _digest(fingerprint_payload)

        previous = prior_reviews.get((unit_id, source_fingerprint))
        if previous:
            outcome, review = previous
            completed_dispositions.append({
                "unitId": unit_id,
                "caseIds": members,
                "disposition": outcome,
                "sourceFingerprint": source_fingerprint,
                "reviewedBy": review.get("reviewedBy"),
                "reviewedAt": review.get("reviewedAt"),
                "qualification": "Explicit human disposition for this exact source fingerprint.",
            })
            for ident in members:
                case_dispositions.append({"caseId": ident, "unitId": unit_id,
                                          "disposition": outcome, "sourceFingerprint": source_fingerprint})
            continue

        # Collect compact issue context while retaining all deduplicated issue
        # counts and digests in the source fingerprint above.
        compact_issues = []
        for row in unit_issues[:MAX_ISSUES_PER_UNIT]:
            pin = _evidence_for_issue(row)
            compact_issues.append({
                "caseId": row["id"],
                "text": _clean_text(row.get("issue"), MAX_TEXT_CHARS),
                "source": _clean_text(row.get("source"), 160),
                "evidence": pin,
            })
        evidence: list[dict[str, Any]] = []
        evidence_seen: set[str] = set()
        for row in unit_issues:
            ref = _evidence_for_issue(row)
            if ref:
                signature = _digest(ref)
                if signature not in evidence_seen:
                    evidence_seen.add(signature)
                    evidence.append(ref)
        evidence.extend(prior_packet_pins)
        evidence.extend(prior_advice_pins)
        evidence.extend({"kind": "evidence_packet", **{k: v for k, v in row.items() if k != "caseIds"}, "caseIds": row["caseIds"]}
                        for row in unit_packets)
        source_matches = []
        for ident in members:
            matches = current_cases[ident].get("sourceMatches")
            if isinstance(matches, list):
                for match in matches:
                    if isinstance(match, Mapping):
                        text, url = match.get("text"), match.get("url")
                        if isinstance(url, str) or isinstance(text, str):
                            ref = {"kind": "official_source_candidate"}
                            if isinstance(text, str):
                                ref["text"] = _clean_text(text, 240)
                            if isinstance(url, str):
                                ref["url"] = url
                            source_matches.append(ref)
        for ref in source_matches:
            signature = _digest(ref)
            if signature not in evidence_seen:
                evidence_seen.add(signature)
                evidence.append(ref)
        evidence = sorted(evidence, key=_canonical)[:MAX_EVIDENCE_PER_UNIT]

        report_summaries = []
        for ident, row, stale in unit_reports_pairs:
            summary = _report_summary(row, stale)
            summary["caseId"] = ident
            report_summaries.append(summary)

        prior_advice = _short_advice(unit_issues)
        if not prior_advice:
            for ident in members:
                compact = case_aux[ident].get("priorAdvice")
                values = compact if isinstance(compact, list) else [compact]
                for value in values:
                    if isinstance(value, str):
                        text = _clean_text(value, 500)
                        if text:
                            prior_advice.append({"text": text, "source": "prior advisory", "qualification": "UNVERIFIED_ADVISORY_ONLY", "evidence": None})
                    elif isinstance(value, Mapping):
                        text = _clean_text(value.get("text", value.get("issue")), 500)
                        if text:
                            prior_advice.append({"text": text, "source": value.get("source", "prior advisory"),
                                                 "qualification": "UNVERIFIED_ADVISORY_ONLY", "evidence": _normalize_pin(value.get("evidence", {})) if isinstance(value.get("evidence"), Mapping) else None})
                    if len(prior_advice) >= MAX_PRIOR_ADVICE_PER_UNIT:
                        break
                if len(prior_advice) >= MAX_PRIOR_ADVICE_PER_UNIT:
                    break
        display_case = current_cases[members[0]]
        catalog_display_case = catalog_rows_by_id.get(members[0], {})
        display_name = catalog_display_case.get("nameAr", display_case.get("nameAr"))
        display_parent = catalog_display_case.get("parentAr", display_case.get("parentAr"))
        display_name = display_name if isinstance(display_name, str) else None
        display_parent = display_parent if isinstance(display_parent, str) else None
        case_records = [current_cases[ident] for ident in members]
        non_stale_assertions = any(
            not stale and bool(_report_summary(row, stale).get("userAssertion"))
            for _, row, stale in unit_reports_pairs
        )
        # The first Luna pilot found that a structured boundary link plus a
        # pinned PDF/ring summary still lacks the map image and complete paths
        # needed to decide geometry. Keep these pairs in the human queue; do
        # not spend another model call merely to restate that limitation.
        model_eligible = bool(
            not external and (
                (problem_reports and non_stale_assertions)
                or bool(concrete_signals)
            )
        )
        review_gate = "WAIT_FOR_OTHER_GOVERNORATE" if external else "HUMAN_EVIDENCE_REVIEW_REQUIRED"

        item = {
            "id": unit_id,
            "caseIds": members,
            "governorate": selected_governorate,
            "nameAr": display_name,
            "parentAr": display_parent,
            "role": display_case.get("role", in_scope_ledger[members[0]].get("role")),
            "priority": priority,
            "priorityReasons": priority_reasons,
            "categories": sorted(categories),
            "concreteSignals": sorted(concrete_signals),
            "question": _make_question(categories, members, external, report_summaries),
            "unresolvedConflicts": compact_issues,
            "issueCount": len(unit_issues),
            "issuesTruncated": len(unit_issues) > len(compact_issues),
            "manualReports": report_summaries,
            "evidence": evidence,
            "priorAdvice": prior_advice,
            "priorPacketPins": prior_packet_pins,
            "priorAdvicePins": prior_advice_pins,
            "evidencePackets": unit_packets,
            "caseRecords": case_records,
            "externalDependencies": external,
            "currentCatalogFingerprint": catalog_by_case[members[0]] if len(members) == 1 else catalog_by_case,
            "catalogFingerprintSource": catalog_fingerprint_source,
            "sourceFingerprint": source_fingerprint,
            "modelEligible": model_eligible,
            "reviewGate": review_gate,
            "status": "UNRESOLVED",
            "disposition": "REVIEW_REQUIRED",
        }
        unit_rows.append(item)
        for ident in members:
            case_dispositions.append({"caseId": ident, "unitId": unit_id,
                                      "disposition": "UNRESOLVED", "sourceFingerprint": source_fingerprint})

    unit_rows.sort(key=lambda item: (item["priority"], item["caseIds"], item["id"]))
    completed_dispositions.sort(key=lambda item: (item["caseIds"], item["disposition"], item["unitId"]))
    case_dispositions.sort(key=lambda item: item["caseId"])

    review_topic_counts = {
        key: sum(key in item["categories"] for item in unit_rows)
        for key in ("identity", "gps", "boundary", "user_report")
    }
    concrete_diagnostic_counts = {
        key: sum(key in item["concreteSignals"] for item in unit_rows)
        for key in ("identity", "gps", "boundary")
    }
    accepted_count = sum(row["disposition"] == "ACCEPTED" for row in completed_dispositions)
    rejected_count = sum(row["disposition"] == "REJECTED" for row in completed_dispositions)
    resolved_count = sum(row["disposition"] == "RESOLVED" for row in completed_dispositions)
    scoped_raw_issue_count = sum(raw_issue_counts.values())
    unique_issue_count = sum(len(rows) for rows in all_issues_by_id_list.values())
    raw_problem_reports = sum(1 for ident, rows in reports_by_id.items() for row, stale in rows if not stale and _is_problem(row))
    raw_fresh_reports = sum(1 for rows in reports_by_id.values() for _, stale in rows if not stale)
    historical_problem_reports = sum(1 for ident in grouped_historical_owners
                                     for row, _ in all_reports_by_id.get(ident, []) if _is_problem(row))
    historical_report_count = sum(len(all_reports_by_id.get(ident, [])) for ident in grouped_historical_owners)
    raw_stale_reports = (sum(1 for rows in reports_by_id.values() for _, stale in rows if stale)
                         + historical_report_count)
    all_case_count = len(latest_ledger)
    unresolved_count = len(unit_rows)
    source_file_rows = _source_files(source_files)
    packet_rows = packet_rows_by_scope

    return {
        "schemaVersion": SCHEMA_VERSION,
        "governorate": selected_governorate,
        "currentCatalogFingerprint": catalog_fingerprints_for_scope,
        "currentCatalogPin": current_catalog_pin_sha,
        "catalogFingerprintSource": catalog_fingerprint_source,
        "counts": {
            "ledgerRowsRead": len(ledger_rows),
            "caseCount": len(in_scope_ledger),
            "ledgerCaseCount": len(all_in_scope_ledger),
            "selectableCatalogCaseCount": sum(ident in catalog_fingerprints for ident in in_scope_ledger),
            "unmappedMissingCatalogCaseCount": sum(ident not in catalog_fingerprints for ident in in_scope_ledger),
            "historicalGroupedMemberAuditCount": len(audit_items),
            "historicalGroupedMemberReportCount": historical_report_count,
            "historicalGroupedMemberProblemReportCount": historical_problem_reports,
            "outOfScopeCaseCount": max(0, all_case_count - len(all_in_scope_ledger)),
            "reviewUnitCount": len(component_rows),
            "accepted": accepted_count,
            "rejected": rejected_count,
            "resolved": resolved_count,
            "unresolved": unresolved_count,
            "queued": sum(item["reviewGate"] != "WAIT_FOR_OTHER_GOVERNORATE" for item in unit_rows),
            "deferredExternalDependency": sum(item["reviewGate"] == "WAIT_FOR_OTHER_GOVERNORATE" for item in unit_rows),
            "rawInvestigationRowsRead": len(issues_raw),
            "scopedInvestigationRows": scoped_raw_issue_count,
            "uniqueInvestigationRows": unique_issue_count,
            "manualProblemReports": raw_problem_reports,
            "pendingManualReports": raw_fresh_reports,
            "staleManualReports": raw_stale_reports,
            "modelEligible": sum(bool(item["modelEligible"]) for item in unit_rows),
            "identityReviewUnits": review_topic_counts["identity"],
            "gpsReviewUnits": review_topic_counts["gps"],
            "boundaryReviewUnits": review_topic_counts["boundary"],
            "userReportUnits": review_topic_counts["user_report"],
            "identityDiagnosticUnits": concrete_diagnostic_counts["identity"],
            "gpsDiagnosticUnits": concrete_diagnostic_counts["gps"],
            "boundaryDiagnosticUnits": concrete_diagnostic_counts["boundary"],
        },
        "sourceFiles": source_file_rows,
        "evidencePackets": packet_rows,
        "items": unit_rows,
        "auditItems": audit_items,
        "dispositions": completed_dispositions,
        "caseDispositions": case_dispositions,
        "qualification": (
            "Automatic passes and model advice are not geographic validation. "
            "Unresolved units require evidence-based human review; accepted, rejected, "
            "or resolved outcomes apply only to an exact matching source fingerprint."
        ),
    }


__all__ = [
    "DEFAULT_GOVERNORATE",
    "PRIORITY_PROBLEM_REPORT",
    "PRIORITY_PENDING_REPORT",
    "PRIORITY_IDENTITY_OR_GPS",
    "PRIORITY_BOUNDARY",
    "PRIORITY_GENERAL",
    "build_review_queue",
]
