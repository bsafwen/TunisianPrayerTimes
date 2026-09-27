"""Durable, evidence-only boundary progress ledger.

This is deliberately separate from case-ledger.json: a completed automatic pass
does not establish a registered geographic measurement. The caller supplies a
reconciled official inventory and explicit completion records. No legacy report
is promoted to evidence here.
"""
from __future__ import annotations

from collections import defaultdict
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sqlite3

from .common import read_pin


DEFAULT_METHOD_FAMILIES = {
    "isie_native_vector": "ISIE",
    "isie_connected_layers": "ISIE",
    "isie_manual_raster": "ISIE",
    "google_full_outline": "Google",
    "google_partial_outline": "Google",
    "osm_installed_comparison": "other",
    "gray_area_road_inference": "other",
    "isie_google_composite": "joint",
}
EVIDENCE_STAGES = frozenset(("attempted", "inspected", "captured", "measured", "accepted"))
DISPOSITIONS = frozenset(("SUPPORTED", "CORRECTED", "FALLBACK", "UNRESOLVED", "PROVISIONAL", "REJECTED"))
RULE_FOR_STAGE = {"attempted": "evidence", "inspected": "evidence", "captured": "evidence",
                  "measured": "measurement", "accepted": "acceptance"}


def _json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def _required_string(value, label):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} must be a nonempty string")
    return value


def _basis(value):
    if not isinstance(value, dict):
        raise ValueError("basis must be an object")
    for key in ("stateKey", "geometrySha256"):
        _required_string(value.get(key), "basis." + key)
    if not isinstance(value.get("neighborHashes"), dict) or not all(
            isinstance(k, str) and isinstance(v, str) and k and v
            for k, v in value["neighborHashes"].items()):
        raise ValueError("basis.neighborHashes must map official IDs to hashes")
    return {k: value[k] for k in ("stateKey", "geometrySha256", "neighborHashes")}


def _validate_inventory(inventory):
    if inventory.get("schemaVersion") != 1:
        raise ValueError("Inventory schemaVersion must be 1")
    for key in ("fingerprint", "inventoryDate", "catalogRevision"):
        _required_string(inventory.get(key), "inventory." + key)
    rules = inventory.get("ruleVersions")
    if not isinstance(rules, dict):
        raise ValueError("inventory.ruleVersions is required")
    for key in ("evidence", "measurement", "acceptance", "disposition"):
        _required_string(rules.get(key), "inventory.ruleVersions." + key)
    methods = inventory.get("methods")
    if not isinstance(methods, dict) or not methods:
        raise ValueError("inventory.methods must explicitly register source versions")
    missing_methods = set(DEFAULT_METHOD_FAMILIES) - {"isie_google_composite"} - set(methods)
    if missing_methods:
        raise ValueError("Inventory must register the standard methods: " + ", ".join(sorted(missing_methods)))
    for name, method in methods.items():
        _required_string(name, "method name")
        if not isinstance(method, dict) or method.get("family") not in ("ISIE", "Google", "other", "joint"):
            raise ValueError("Every method needs a recognized family")
        _required_string(method.get("sourceVersion"), f"method {name} sourceVersion")
        _required_string(method.get("methodVersion"), f"method {name} methodVersion")
        if name in DEFAULT_METHOD_FAMILIES and method["family"] != DEFAULT_METHOD_FAMILIES[name]:
            raise ValueError(f"Incorrect family for {name}")
    identities = inventory.get("identities")
    if not isinstance(identities, list):
        raise ValueError("inventory.identities must be a list")
    by_id = {}
    for row in identities:
        ident = _required_string(row.get("officialId"), "officialId")
        if ident in by_id:
            raise ValueError(f"Duplicate official identity: {ident}")
        _required_string(row.get("governorate"), "governorate")
        _required_string(row.get("catalogFingerprint"), "catalogFingerprint")
        _basis(row.get("basis"))
        components = row.get("boundaryComponents")
        if not isinstance(components, list) or not components or any(not isinstance(c, str) or not c for c in components) or len(set(components)) != len(components):
            raise ValueError(f"{ident} needs distinct official boundary components")
        by_id[ident] = row
    reconciliation = inventory.get("reconciliation")
    if not isinstance(reconciliation, dict) or not isinstance(reconciliation.get("complete"), bool):
        raise ValueError("inventory.reconciliation.complete must be explicit")
    if not reconciliation["complete"] and not isinstance(reconciliation.get("backlogIds"), list):
        raise ValueError("Unreconciled inventory needs backlogIds")
    return by_id


def _validate_event(event):
    if not isinstance(event, dict):
        raise ValueError("Event must be an object")
    for key in ("eventId", "kind", "identityId"):
        _required_string(event.get(key), key)
    if event["kind"] not in ("evidence", "invalidate", "disposition", "install"):
        raise ValueError("Unknown event kind")
    # A completion is only durable after its immutable artifact exists and matches.
    if not isinstance(event.get("artifact"), dict):
        raise ValueError("Event needs an immutable artifact pin")
    read_pin(event["artifact"])
    if event["kind"] == "evidence":
        for key in ("evidenceId", "method", "sourceVersion", "methodVersion", "ruleVersion", "catalogFingerprint"):
            _required_string(event.get(key), key)
        if event.get("stage") not in EVIDENCE_STAGES:
            raise ValueError("Unknown evidence stage")
        _basis(event.get("basis"))
        if not isinstance(event.get("stageRevision"), int) or event["stageRevision"] < 1:
            raise ValueError("stageRevision must be positive")
        if event["stage"] == "measured":
            coverage = event.get("coverage")
            if not isinstance(coverage, dict) or coverage.get("scope") not in ("official_imada", "settlement", "other"):
                raise ValueError("Measurement needs explicit coverage scope")
            if not isinstance(coverage.get("components"), list) or not coverage["components"] or not all(isinstance(c, str) and c for c in coverage["components"]):
                raise ValueError("Measurement needs comparable boundary components")
            _required_string(coverage.get("compatibilityGroup"), "coverage.compatibilityGroup")
            if not isinstance(event.get("registered"), bool) or not event["registered"]:
                raise ValueError("Measured requires completed geographic registration")
            quantitative = event.get("quantitative")
            if not isinstance(quantitative, dict) or not quantitative or not all(
                    isinstance(v, (int, float)) and not isinstance(v, bool) for v in quantitative.values()):
                raise ValueError("Measured requires quantitative results")
            if event.get("verdict") not in ("supports", "rejects", "uncertain"):
                raise ValueError("Measurement verdict must be explicit")
            if not isinstance(event.get("scopeMatch"), bool):
                raise ValueError("Measurement scopeMatch must be explicit")
        if event["stage"] == "accepted":
            if event.get("acceptedCoverage") not in ("whole", "partial"):
                raise ValueError("acceptedCoverage must be whole or partial")
            refs = event.get("measurementEvidenceIds")
            if not isinstance(refs, list) or not refs or any(not isinstance(x, str) or not x for x in refs):
                raise ValueError("Acceptance needs measured evidence IDs")
            _required_string(event.get("supportedGeometrySha256"), "supportedGeometrySha256")
    elif event["kind"] == "invalidate":
        _required_string(event.get("evidenceId"), "evidenceId")
        _required_string(event.get("reason"), "reason")
    elif event["kind"] == "disposition":
        if event.get("disposition") not in DISPOSITIONS:
            raise ValueError("Unknown disposition")
        _basis(event.get("basis"))
        _required_string(event.get("ruleVersion"), "ruleVersion")
        refs = event.get("evidenceIds")
        if not isinstance(refs, list) or not refs or any(not isinstance(x, str) or not x for x in refs):
            raise ValueError("A disposition needs evidence IDs")
    else:
        _required_string(event.get("receiptId"), "receiptId")
        _required_string(event.get("geometrySha256"), "geometrySha256")
        if event.get("status") not in ("INSTALLED", "ROLLED_BACK", "UNCERTAIN"):
            raise ValueError("Unknown installation state")
        receipt = read_pin(event["artifact"])
        if receipt.get("status") != event["status"]:
            raise ValueError("Installation state differs from pinned receipt")


def _apply(state, event):
    kind = event["kind"]
    ident = event["identityId"]
    if kind == "evidence":
        key = event["evidenceId"]
        row = state["evidence"].setdefault(key, {"identityId": ident, "method": event["method"],
                                                  "basis": event["basis"], "stages": {}})
        if row["identityId"] != ident or row["method"] != event["method"] or row["basis"] != event["basis"]:
            raise ValueError(f"Evidence ID reused for another identity, method or state: {key}")
        prior = row["stages"].get(event["stage"])
        if prior is None or event["stageRevision"] > prior["stageRevision"]:
            row["stages"][event["stage"]] = event
        elif event["stageRevision"] == prior["stageRevision"] and event["eventId"] != prior["eventId"]:
            raise ValueError(f"Conflicting stage revision for {key}")
    elif kind == "invalidate":
        state["invalidated"][event["evidenceId"]] = event
    elif kind == "disposition":
        state["dispositions"][ident] = event
    else:
        state["installs"].setdefault(ident, {})[event["receiptId"]] = event
        state["latestInstall"][ident] = event


def _event_current(event, row, inventory):
    if event["basis"] != row["basis"]:
        return False
    method = inventory["methods"].get(event["method"])
    if (method is None or event["sourceVersion"] != method["sourceVersion"] or
            event["methodVersion"] != method["methodVersion"]):
        return False
    rule = RULE_FOR_STAGE[event["stage"]]
    if event["ruleVersion"] != inventory["ruleVersions"][rule]:
        return False
    read_pin(event["artifact"])
    return True


def _membership(ids, links):
    return {"count": len(ids), "identityIds": sorted(ids),
            "evidenceEventIds": {ident: sorted(links.get(ident, set())) for ident in sorted(ids)}}


def _full_measurement(events, components):
    groups = defaultdict(set)
    for event in events:
        coverage = event["coverage"]
        if coverage["scope"] == "official_imada" and event["scopeMatch"]:
            groups[coverage["compatibilityGroup"]].update(coverage["components"])
    return any(group >= components for group in groups.values())


def _scope(snapshot, ids, name, method_families):
    selected = set(ids)
    methods = {}
    measurements = snapshot["measurements"]
    acceptances = snapshot["acceptances"]
    for method in sorted(snapshot["methodNames"]):
        measured = {i for i in selected if measurements.get((i, method))}
        full = {i for i in measured if snapshot["full"].get((i, method), False)}
        whole = {i for i in selected if acceptances.get((i, method), {}).get("whole")}
        accepted = {i for i in selected if acceptances.get((i, method), {}).get("partial") or i in whole}
        links = {i: {e["eventId"] for e in measurements.get((i, method), [])} for i in measured}
        accept_links = {i: set(acceptances[(i, method)].get("whole", []) + acceptances[(i, method)].get("partial", [])) for i in accepted}
        stages = {stage: _membership(selected & snapshot["stageMemberships"][method][stage],
                                     snapshot["stageLinks"][method][stage]) for stage in EVIDENCE_STAGES}
        methods[method] = {"family": method_families[method], "measured": _membership(measured, links),
                           "full": _membership(full, links), "partialOnly": _membership(measured-full, links),
                           "acceptedWhole": _membership(whole, accept_links),
                           "acceptedPartialOnly": _membership(accepted-whole, accept_links), "stages": stages}
    families = {}
    for family in ("ISIE", "Google"):
        names = [m for m in methods if method_families[m] == family]
        measured = set().union(*(set(methods[m]["measured"]["identityIds"]) for m in names)) if names else set()
        full = set().union(*(set(methods[m]["full"]["identityIds"]) for m in names)) if names else set()
        whole = set().union(*(set(methods[m]["acceptedWhole"]["identityIds"]) for m in names)) if names else set()
        accepted = set().union(*(set(methods[m]["acceptedPartialOnly"]["identityIds"]) | set(methods[m]["acceptedWhole"]["identityIds"]) for m in names)) if names else set()
        links = {i: set().union(*(set(methods[m]["measured"]["evidenceEventIds"].get(i, [])) for m in names)) for i in measured}
        accept_links = {i: set().union(*(set(methods[m]["acceptedWhole"]["evidenceEventIds"].get(i, [])) | set(methods[m]["acceptedPartialOnly"]["evidenceEventIds"].get(i, [])) for m in names)) for i in accepted}
        families[family] = {"measured": _membership(measured, links), "full": _membership(full, links),
                            "partialOnly": _membership(measured-full, links), "acceptedWhole": _membership(whole, accept_links),
                            "acceptedPartialOnly": _membership(accepted-whole, accept_links)}
    measured = set().union(*(set(r["measured"]["identityIds"]) for r in methods.values())) if methods else set()
    full = set().union(*(set(r["full"]["identityIds"]) for r in methods.values())) if methods else set()
    whole = set().union(*(set(r["acceptedWhole"]["identityIds"]) for r in methods.values())) if methods else set()
    accepted = set().union(*(set(r["acceptedWhole"]["identityIds"]) | set(r["acceptedPartialOnly"]["identityIds"]) for r in methods.values())) if methods else set()
    links = {i: set().union(*(set(r["measured"]["evidenceEventIds"].get(i, [])) for r in methods.values())) for i in measured}
    accept_links = {i: set().union(*(set(r["acceptedWhole"]["evidenceEventIds"].get(i, [])) | set(r["acceptedPartialOnly"]["evidenceEventIds"].get(i, [])) for r in methods.values())) for i in accepted}
    disposed = selected & snapshot["disposed"]
    supported = disposed & whole & snapshot["supportedDispositions"]
    disposition_links = snapshot["dispositionLinks"]
    supported_links = {i: set(accept_links.get(i, set())) | set(disposition_links.get(i, set())) for i in supported}
    n = len(selected)
    p, m, s = len(disposed), len(measured), len(supported)
    if not (0 <= p <= n and 0 <= m <= n and 0 <= s <= min(m, p)):
        raise ValueError("Invalid P/M/S count invariant")
    dispositions = {key: _membership(selected & value, disposition_links) for key, value in snapshot["dispositionSets"].items()}
    if sum(v["count"] for v in dispositions.values()) != p:
        raise ValueError("Disposition partition does not sum to P")
    all_row = {"measured": _membership(measured, links), "full": _membership(full, links),
               "partialOnly": _membership(measured-full, links), "acceptedWhole": _membership(whole, accept_links),
               "acceptedPartialOnly": _membership(accepted-whole, accept_links)}
    if all_row["full"]["count"] + all_row["partialOnly"]["count"] != m:
        raise ValueError("Full and partial-only do not partition M")
    return {"name": name, "identityIds": sorted(selected), "N": n, "P": p, "M": m, "S": s,
            "firstPassPercent": round(100*p/n, 4) if n else None,
            "remaining": {"firstPass": n-p, "measurement": n-m, "support": n-s},
            "firstPass": _membership(disposed, disposition_links),
            "dispositions": dispositions, "notYetDisposed": _membership(selected-disposed, {}),
            "methods": methods, "families": families, "allMethods": all_row,
            "supportedWhole": _membership(supported, supported_links),
            "processedButUnmeasured": _membership((selected & snapshot["processed"])-measured, snapshot["processedLinks"]),
            "currentCorrections": _membership(selected & snapshot["corrections"], snapshot["correctionLinks"])}


def _delta(current, baseline, added_scope=None, removed_scope=None):
    added_scope = added_scope or set()
    removed_scope = removed_scope or set()
    result = {}
    for key in ("measured", "full", "partialOnly", "acceptedWhole", "acceptedPartialOnly"):
        old = set(baseline.get(key, {}).get("identityIds", [])) if baseline else set()
        new = set(current[key]["identityIds"])
        added, invalidated = new-old, old-new
        result[key] = {"newOrRevalidatedIds": sorted(added), "invalidatedIds": sorted(invalidated),
                       "newOrRevalidated": len(added), "invalidated": len(invalidated), "net": len(new)-len(old),
                       "newValidationIds": sorted(added-added_scope),
                       "scopeAddedMembershipIds": sorted(added & added_scope),
                       "invalidatedWorkIds": sorted(invalidated-removed_scope),
                       "scopeRemovedMembershipIds": sorted(invalidated & removed_scope)}
    return result


class BoundaryLedger:
    """One reporting writer; SQLite commits events and projection watermarks atomically."""

    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS events (seq INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT UNIQUE NOT NULL, payload TEXT NOT NULL, payload_sha256 TEXT NOT NULL, committed_at TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS projection (id INTEGER PRIMARY KEY CHECK(id=1), watermark INTEGER NOT NULL, state TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS reports (report_id TEXT PRIMARY KEY, snapshot TEXT NOT NULL, watermark INTEGER NOT NULL, baseline_report_id TEXT, state TEXT NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execute("INSERT OR IGNORE INTO projection VALUES (1, 0, ?)", (_json(self._empty_state()),))

    @staticmethod
    def _empty_state():
        return {"evidence": {}, "invalidated": {}, "dispositions": {}, "installs": {}, "latestInstall": {}}

    @contextmanager
    def _connect(self):
        db = sqlite3.connect(self.path, timeout=30)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def record(self, event):
        """Idempotently commit one completion after checking its pinned artifact."""
        _validate_event(event)
        payload = _json(event)
        digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()
        with self._connect() as db:
            prior = db.execute("SELECT seq,payload_sha256 FROM events WHERE event_id=?", (event["eventId"],)).fetchone()
            if prior:
                if prior["payload_sha256"] != digest:
                    raise ValueError("Event ID reused with different content")
                return prior["seq"]
            db.execute("INSERT INTO events(event_id,payload,payload_sha256,committed_at) VALUES (?,?,?,?)",
                       (event["eventId"], payload, digest, datetime.now(timezone.utc).isoformat()))
            return db.execute("SELECT last_insert_rowid()").fetchone()[0]

    def ingest_completion(self, path):
        """A worker may leave a completed JSON record; replaying it is harmless."""
        return self.record(json.loads(Path(path).read_text(encoding="utf-8-sig")))

    def ingest_completions(self, directory):
        """Recover only explicit worker completion files, never historical reports."""
        return [self.ingest_completion(path) for path in sorted(Path(directory).glob("*.json"))]

    def get_event(self, event_id):
        """Resolve a report membership link to its saved hashes and artifact pin."""
        with self._connect() as db:
            row = db.execute("SELECT payload,committed_at,seq FROM events WHERE event_id=?", (event_id,)).fetchone()
        if not row:
            raise KeyError(event_id)
        return {**json.loads(row["payload"]), "committedAt": row["committed_at"], "sequence": row["seq"]}

    def _project(self, db):
        db.execute("BEGIN IMMEDIATE")
        row = db.execute("SELECT watermark,state FROM projection WHERE id=1").fetchone()
        state = json.loads(row["state"])
        watermark = row["watermark"]
        for item in db.execute("SELECT seq,payload FROM events WHERE seq>? ORDER BY seq", (watermark,)):
            _apply(state, json.loads(item["payload"]))
            watermark = item["seq"]
        db.execute("UPDATE projection SET watermark=?,state=? WHERE id=1", (watermark, _json(state)))
        db.commit()
        return state, watermark

    def snapshot(self, inventory, governorate=None):
        try:
            result = self._snapshot(inventory, governorate)
            if result["status"] == "CURRENT":
                with self._connect() as db:
                    db.execute("INSERT INTO meta(key,value) VALUES ('last_trusted_snapshot',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                               (_json(result),))
            return result
        except (ValueError, OSError, sqlite3.DatabaseError) as exc:
            try:
                with self._connect() as db:
                    row = db.execute("SELECT value FROM meta WHERE key='last_trusted_snapshot'").fetchone()
                trusted = json.loads(row["value"]) if row else None
            except (ValueError, OSError, sqlite3.DatabaseError):
                trusted = None
            return {"schemaVersion": 1, "status": "TRACKING_ERROR", "snapshotAt": datetime.now(timezone.utc).isoformat(),
                    "error": type(exc).__name__ + ": " + str(exc), "lastTrustedSnapshot": trusted}

    def _snapshot(self, inventory, governorate=None):
        by_id = _validate_inventory(inventory)
        with self._connect() as db:
            state, watermark = self._project(db)
            last = db.execute("SELECT committed_at FROM events WHERE seq=?", (watermark,)).fetchone()
        method_names = set(inventory["methods"])
        measured = defaultdict(list)
        processed = set()
        processed_links = defaultdict(set)
        current_stages = defaultdict(lambda: defaultdict(set))
        stage_links = defaultdict(lambda: defaultdict(lambda: defaultdict(set)))
        current_evidence = {}
        historical = defaultdict(list)
        pending_installs = defaultdict(list)
        for evidence_id, entry in state["evidence"].items():
            row = by_id.get(entry["identityId"])
            for stage, event in entry["stages"].items():
                if evidence_id in state["invalidated"] or row is None or not _event_current(event, row, inventory):
                    historical[entry["identityId"]].append(event["eventId"])
                    continue
                if stage == "measured" and set(event["coverage"]["components"]) - set(row["boundaryComponents"]):
                    historical[entry["identityId"]].append(event["eventId"])
                    continue
                current_evidence[(evidence_id, stage)] = event
                current_stages[entry["method"]][stage].add(entry["identityId"])
                stage_links[entry["method"]][stage][entry["identityId"]].add(event["eventId"])
                if stage in ("attempted", "inspected", "captured"):
                    processed.add(entry["identityId"])
                    processed_links[entry["identityId"]].add(event["eventId"])
                if stage == "measured":
                    measured[(entry["identityId"], entry["method"])].append(event)
        full = {(ident, method): _full_measurement(events, set(by_id[ident]["boundaryComponents"]))
                for (ident, method), events in measured.items()}
        acceptances = defaultdict(lambda: {"whole": [], "partial": []})
        corrections = set()
        correction_links = defaultdict(set)
        for ident, receipt in state["latestInstall"].items():
            row = by_id.get(ident)
            if row and receipt and receipt["status"] == "INSTALLED" and receipt["geometrySha256"] == row["basis"]["geometrySha256"]:
                read_pin(receipt["artifact"])
                corrections.add(ident)
                correction_links[ident].add(receipt["eventId"])
        for evidence_id, entry in state["evidence"].items():
            event = current_evidence.get((evidence_id, "accepted"))
            if event is None:
                continue
            ident, method = entry["identityId"], entry["method"]
            row = by_id[ident]
            if method in ("osm_installed_comparison", "gray_area_road_inference"):
                historical[ident].append(event["eventId"])
                continue
            refs = [current_evidence.get((ref, "measured")) for ref in event["measurementEvidenceIds"]]
            if any(ref is None or ref["method"] != method or ref["identityId"] != ident or
                   ref["verdict"] != "supports" or not ref["scopeMatch"] for ref in refs):
                historical[ident].append(event["eventId"])
                continue
            if event["supportedGeometrySha256"] != row["basis"]["geometrySha256"]:
                if event.get("proposalSha256"):
                    pending_installs[ident].append(event["eventId"])
                else:
                    historical[ident].append(event["eventId"])
                continue
            if event.get("proposalSha256"):
                receipt_id = event.get("installReceiptId")
                receipt = state["installs"].get(ident, {}).get(receipt_id)
                if (not receipt or receipt["status"] != "INSTALLED" or
                        receipt["geometrySha256"] != row["basis"]["geometrySha256"] or
                        state["latestInstall"].get(ident) != receipt):
                    pending_installs[ident].append(event["eventId"])
                    continue
            is_full = _full_measurement(refs, set(row["boundaryComponents"]))
            if event["acceptedCoverage"] == "whole" and not is_full:
                historical[ident].append(event["eventId"])
                continue
            acceptances[(ident, method)][event["acceptedCoverage"]].append(event["eventId"])
        disposed = set()
        supported_dispositions = set()
        disposition_sets = {d: set() for d in DISPOSITIONS}
        disposition_links = defaultdict(set)
        accepted_whole = {ident for (ident, _method), value in acceptances.items() if value["whole"]}
        for ident, event in state["dispositions"].items():
            row = by_id.get(ident)
            if not row or event["basis"] != row["basis"] or event["ruleVersion"] != inventory["ruleVersions"]["disposition"]:
                historical[ident].append(event["eventId"])
                continue
            if not any((ref, stage) in current_evidence for ref in event["evidenceIds"] for stage in ("inspected", "captured", "measured")):
                historical[ident].append(event["eventId"])
                continue
            read_pin(event["artifact"])
            disposition = event["disposition"]
            if disposition in ("SUPPORTED", "CORRECTED") and ident not in accepted_whole:
                historical[ident].append(event["eventId"])
                continue
            if disposition == "CORRECTED" and ident not in corrections:
                historical[ident].append(event["eventId"])
                continue
            disposed.add(ident)
            disposition_sets[disposition].add(ident)
            disposition_links[ident].add(event["eventId"])
            for ref in event["evidenceIds"]:
                disposition_links[ident].update(current_evidence[(ref, stage)]["eventId"] for stage in ("inspected", "captured", "measured") if (ref, stage) in current_evidence)
            if disposition in ("SUPPORTED", "CORRECTED"):
                supported_dispositions.add(ident)
        view = {"methodNames": method_names, "measurements": measured, "full": full, "acceptances": acceptances,
                "processed": processed, "processedLinks": processed_links, "disposed": disposed,
                "supportedDispositions": supported_dispositions, "dispositionSets": disposition_sets,
                "dispositionLinks": disposition_links,
                "corrections": corrections, "correctionLinks": correction_links,
                "stageMemberships": current_stages, "stageLinks": stage_links}
        all_ids = set(by_id)
        scope_ids = {i for i, row in by_id.items() if row["governorate"] == governorate} if governorate else all_ids
        method_families = {m: x["family"] for m, x in inventory["methods"].items()}
        result = {"schemaVersion": 1, "status": "CURRENT" if inventory["reconciliation"]["complete"] else "UNRECONCILED",
                  "snapshotAt": datetime.now(timezone.utc).isoformat(), "lastCommittedResultAt": last["committed_at"] if last else None,
                  "watermark": watermark, "inventoryFingerprint": inventory["fingerprint"],
                  "inventoryDate": inventory["inventoryDate"], "catalogRevision": inventory["catalogRevision"],
                  "ruleVersions": inventory["ruleVersions"], "reconciliation": inventory["reconciliation"],
                  "governorate": governorate, "wholeInventory": _scope(view, all_ids, "wholeInventory", method_families),
                  "currentGovernorate": _scope(view, scope_ids, governorate or "wholeInventory", method_families),
                  "historicalEvidence": {k: sorted(set(v)) for k, v in sorted(historical.items())},
                  "pendingInstallEvidence": {k: sorted(set(v)) for k, v in sorted(pending_installs.items())}}
        if not inventory["reconciliation"]["complete"]:
            result["publishableCounts"] = None
        return result

    def prepare_report(self, inventory, governorate=None):
        """Save one retryable report; publication alone advances the delta baseline."""
        with self._connect() as db:
            pending = db.execute("SELECT report_id,snapshot FROM reports WHERE state='UNCONFIRMED' ORDER BY rowid LIMIT 1").fetchone()
            if pending:
                return json.loads(pending["snapshot"])
        snap = self.snapshot(inventory, governorate)
        if snap["status"] != "CURRENT":
            return snap
        with self._connect() as db:
            baseline_id = db.execute("SELECT value FROM meta WHERE key='published_report_id'").fetchone()
            baseline_id = baseline_id["value"] if baseline_id else None
            baseline = json.loads(db.execute("SELECT snapshot FROM reports WHERE report_id=?", (baseline_id,)).fetchone()["snapshot"]) if baseline_id else None
            if baseline and baseline["inventoryFingerprint"] != snap["inventoryFingerprint"]:
                old_ids = set(baseline["wholeInventory"]["identityIds"])
                new_ids = set(snap["wholeInventory"]["identityIds"])
                removed, added = old_ids-new_ids, new_ids-old_ids
                if removed or added:
                    crosswalk = inventory.get("crosswalk")
                    if not isinstance(crosswalk, list):
                        raise ValueError("Changed official inventory membership needs an explicit old-to-new crosswalk")
                    covered_old = set()
                    covered_new = set()
                    for change in crosswalk:
                        if not isinstance(change, dict) or not isinstance(change.get("oldIds"), list) or not isinstance(change.get("newIds"), list):
                            raise ValueError("Invalid inventory crosswalk entry")
                        _required_string(change.get("reason"), "crosswalk reason")
                        covered_old.update(change["oldIds"])
                        covered_new.update(change["newIds"])
                    if not removed <= covered_old or not added <= covered_new:
                        raise ValueError("Crosswalk omits added or removed official identities")
                    snap["inventoryCrosswalk"] = crosswalk
            for scope_name in ("wholeInventory", "currentGovernorate"):
                current = snap[scope_name]
                prior = baseline.get(scope_name) if baseline else None
                added_scope = set(current["identityIds"]) - set(prior["identityIds"]) if prior else set()
                removed_scope = set(prior["identityIds"]) - set(current["identityIds"]) if prior else set()
                current["delta"] = {"allMethods": _delta(current["allMethods"], prior.get("allMethods") if prior else None, added_scope, removed_scope),
                                    "families": {family: _delta(row, prior.get("families", {}).get(family) if prior else None, added_scope, removed_scope)
                                                 for family, row in current["families"].items()},
                                    "methods": {method: _delta(row, prior.get("methods", {}).get(method) if prior else None, added_scope, removed_scope)
                                                for method, row in current["methods"].items()}}
                current["previousN"] = prior["N"] if prior else None
                current["scopeChange"] = current["N"] - prior["N"] if prior else None
                current["scopeAddedIds"] = sorted(added_scope)
                current["scopeRemovedIds"] = sorted(removed_scope)
            snap["baselineReportId"] = baseline_id
            snap["baselineInventoryFingerprint"] = baseline["inventoryFingerprint"] if baseline else None
            snap["publicationState"] = "UNCONFIRMED"
            report_id = hashlib.sha256(_json({"watermark": snap["watermark"], "inventory": snap["inventoryFingerprint"],
                                              "governorate": governorate, "baseline": baseline_id, "snapshotAt": snap["snapshotAt"]}).encode()).hexdigest()[:24]
            snap["reportId"] = report_id
            db.execute("INSERT INTO reports VALUES (?,?,?,?,?)", (report_id, _json(snap), snap["watermark"], baseline_id, "UNCONFIRMED"))
        return snap

    def confirm_published(self, report_id):
        with self._connect() as db:
            row = db.execute("SELECT state FROM reports WHERE report_id=?", (report_id,)).fetchone()
            if not row:
                raise ValueError("Unknown report ID")
            if row["state"] == "PUBLISHED":
                return
            db.execute("UPDATE reports SET state='PUBLISHED' WHERE report_id=?", (report_id,))
            db.execute("INSERT INTO meta(key,value) VALUES ('published_report_id',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (report_id,))
