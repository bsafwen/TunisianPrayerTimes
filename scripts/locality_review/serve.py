#!/usr/bin/env python3
"""Manual locality review API server (stdlib only).

Append-only JSONL storage, per-process data-dir lock, in-process thread lock,
replay dedupe, pinned-catalog verification and strict input validation.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import math
import os
import re
import secrets
import sys
import threading
import traceback
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from boundary import BoundaryError, BoundaryStore
from verification_scores import load_scores
from maps_config import load_maps_config

MAX_BODY_BYTES = 32 * 1024
VERDICTS = ("looks_correct", "problem", "unsure", "withdrawn")
ISSUES = ("name", "type", "governorate", "delegation", "pin", "boundary",
          "duplicate", "prayer_source", "other")
KNOWLEDGE = ("personal", "maps", "both", "unspecified")
BODY_FIELDS = ("requestId", "id", "fingerprint", "verdict", "issues", "note",
               "evidenceUrl", "knowledge")
LOCATION_KEYS = ("id", "fingerprint", "nameAr", "aliases", "governorateAr",
                 "parentAr", "kind", "lat", "lng", "hasBoundary")
REVIEW_STATUS = "PENDING_AGENT_REVIEW"
REVIEW_SOURCE = "USER_MANUAL_REVIEW"
HEX64 = re.compile(r"^[0-9a-fA-F]{64}$")
_DATA_LOCK = None


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def abort(message):
    raise SystemExit("serve.py: error: " + message)


def sha256_file(path):
    digest = hashlib.sha256()
    try:
        with open(path, "rb") as handle:
            for chunk in iter(lambda: handle.read(1 << 20), b""):
                digest.update(chunk)
    except OSError as exc:
        abort("cannot read %s: %s" % (path, exc))
    return digest.hexdigest()


def is_finite_number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    try:
        return math.isfinite(value)
    except (OverflowError, ValueError):
        return False


def load_catalog(path):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            catalog = json.load(handle)
    except (OSError, UnicodeDecodeError, ValueError) as exc:
        abort("cannot read catalog %s: %s" % (path, exc))
    if not isinstance(catalog, dict):
        abort("catalog must be a JSON object")
    pins = catalog.get("sourcePins")
    if not isinstance(pins, dict) or not pins:
        abort("catalog.sourcePins must be a non-empty object")
    for key in sorted(pins):
        pin = pins[key]
        if not isinstance(pin, dict):
            abort("sourcePins[%s] must be an object" % key)
        target, want = pin.get("file"), pin.get("sha256")
        if not isinstance(target, str) or not os.path.isabs(target):
            abort("sourcePins[%s].file must be an absolute path" % key)
        if not isinstance(want, str) or not HEX64.match(want):
            abort("sourcePins[%s].sha256 must be a sha256 hex digest" % key)
        got = sha256_file(target)
        if got.lower() != want.lower():
            abort("source pin mismatch for %s: expected %s, got %s" % (target, want, got))
    if not isinstance(catalog.get("schemaVersion"), int) or isinstance(catalog.get("schemaVersion"), bool):
        abort("catalog.schemaVersion must be an integer")
    if not isinstance(catalog.get("generatedAtUtc"), str) or not catalog["generatedAtUtc"]:
        abort("catalog.generatedAtUtc must be a non-empty string")
    fingerprint = catalog.get("catalogFingerprint")
    if not isinstance(fingerprint, str) or not HEX64.match(fingerprint):
        abort("catalog.catalogFingerprint must be a sha256 hex digest")
    locations = catalog.get("locations")
    if not isinstance(locations, list) or not locations:
        abort("catalog.locations must be a non-empty list")
    seen_ids, seen_fingerprints = set(), set()
    for index, location in enumerate(locations):
        if not isinstance(location, dict):
            abort("locations[%d] must be an object" % index)
        for key in LOCATION_KEYS:
            if key not in location:
                abort("locations[%d] is missing %r" % (index, key))
        loc_id = location["id"]
        if not isinstance(loc_id, str) or not loc_id.strip() or loc_id in seen_ids:
            abort("locations[%d].id must be a unique non-empty string" % index)
        seen_ids.add(loc_id)
        loc_fp = location["fingerprint"]
        if not isinstance(loc_fp, str) or not HEX64.match(loc_fp) or loc_fp.lower() in seen_fingerprints:
            abort("locations[%d].fingerprint must be a unique sha256 hex digest" % index)
        seen_fingerprints.add(loc_fp.lower())
        for key in ("nameAr", "governorateAr", "parentAr", "kind"):
            if not isinstance(location[key], str):
                abort("locations[%d].%s must be a string" % (index, key))
        if not isinstance(location["aliases"], list) or not all(isinstance(a, str) for a in location["aliases"]):
            abort("locations[%d].aliases must be a list of strings" % index)
        if "searchTerms" in location and (
                not isinstance(location["searchTerms"], list)
                or not all(isinstance(a, str) for a in location["searchTerms"])):
            abort("locations[%d].searchTerms must be a list of strings" % index)
        if not isinstance(location["hasBoundary"], bool):
            abort("locations[%d].hasBoundary must be a boolean" % index)
        if not is_finite_number(location["lat"]) or not -90.0 <= location["lat"] <= 90.0:
            abort("locations[%d].lat must be a finite latitude" % index)
        if not is_finite_number(location["lng"]) or not -180.0 <= location["lng"] <= 180.0:
            abort("locations[%d].lng must be a finite longitude" % index)
        source = location.get("prayerSource")
        if source is not None:
            if not isinstance(source, dict):
                abort("locations[%d].prayerSource must be an object or null" % index)
            if not isinstance(source.get("id"), int) or isinstance(source.get("id"), bool):
                abort("locations[%d].prayerSource.id must be an integer" % index)
            if not isinstance(source.get("nameAr"), str):
                abort("locations[%d].prayerSource.nameAr must be a string" % index)
            if not is_finite_number(source.get("lat")) or not is_finite_number(source.get("lng")):
                abort("locations[%d].prayerSource coordinates must be finite numbers" % index)
    canonical = json.dumps(locations, ensure_ascii=False, sort_keys=True,
                           separators=(",", ":"), allow_nan=False).encode("utf-8")
    if hashlib.sha256(canonical).hexdigest() != fingerprint.lower():
        abort("catalog fingerprint does not match its locations")
    return catalog


def validate_body(body, known_ids):
    """Return (error_message_or_None, normalized_body_or_None)."""
    if not isinstance(body, dict):
        return "Body must be a JSON object.", None
    if set(body.keys()) != set(BODY_FIELDS):
        missing = ", ".join(sorted(set(BODY_FIELDS) - set(body)))
        extra = ", ".join(sorted(set(body) - set(BODY_FIELDS)))
        parts = []
        if missing:
            parts.append("missing: " + missing)
        if extra:
            parts.append("unexpected: " + extra)
        return "Body must contain exactly the documented fields (%s)." % "; ".join(parts), None
    request_id = body["requestId"]
    if not isinstance(request_id, str):
        return "requestId must be a UUID string.", None
    try:
        request_id = str(uuid.UUID(request_id))
    except ValueError:
        return "requestId must be a UUID string.", None
    loc_id = body["id"]
    if not isinstance(loc_id, str) or not loc_id:
        return "id must be a non-empty string.", None
    if known_ids is not None and loc_id not in known_ids:
        return "Unknown location id.", None
    fingerprint = body["fingerprint"]
    if not isinstance(fingerprint, str) or not HEX64.match(fingerprint):
        return "fingerprint must be a sha256 hex digest.", None
    verdict = body["verdict"]
    if verdict not in VERDICTS:
        return "verdict must be one of: %s." % ", ".join(VERDICTS), None
    issues = body["issues"]
    if not isinstance(issues, list) or any(not isinstance(item, str) for item in issues):
        return "issues must be a list of strings.", None
    if len(set(issues)) != len(issues):
        return "issues must not contain duplicates.", None
    unknown = sorted(set(issues) - set(ISSUES))
    if unknown:
        return "Unknown issue value(s): %s." % ", ".join(unknown), None
    note = body["note"]
    if not isinstance(note, str) or len(note) > 3000:
        return "note must be a string of at most 3000 characters.", None
    evidence = body["evidenceUrl"]
    if not isinstance(evidence, str) or len(evidence) > 2048:
        return "evidenceUrl must be a string of at most 2048 characters.", None
    if evidence:
        try:
            parsed = urlsplit(evidence)
        except ValueError:
            return "evidenceUrl must be a valid http/https URL.", None
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            return "evidenceUrl must be empty or an http/https URL with a hostname.", None
    knowledge = body["knowledge"]
    if knowledge not in KNOWLEDGE:
        return "knowledge must be one of: %s." % ", ".join(KNOWLEDGE), None
    if verdict == "problem":
        if not issues and not note.strip():
            return "problem verdicts require at least one issue or a non-blank note.", None
    elif issues:
        return "Only problem verdicts may carry issues.", None
    return None, {
        "requestId": request_id,
        "id": loc_id,
        "fingerprint": fingerprint.lower(),
        "verdict": verdict,
        "issues": list(issues),
        "note": note,
        "evidenceUrl": evidence,
        "knowledge": knowledge,
    }


def canonical_body(source):
    return json.dumps({key: source[key] for key in BODY_FIELDS},
                      sort_keys=True, ensure_ascii=False, separators=(",", ":"))


class Store:
    def __init__(self, data_dir, catalog):
        self.data_dir = data_dir
        self.total = len(catalog["locations"])
        self.catalog_fingerprint = catalog["catalogFingerprint"]
        self.location_fingerprints = {
            loc["id"]: loc["fingerprint"].lower() for loc in catalog["locations"]}
        self.log_path = os.path.join(data_dir, "responses.jsonl")
        self.summary_path = os.path.join(data_dir, "summary.json")
        self.lock = threading.RLock()
        self.events = []
        self.latest = {}
        self.seen = {}
        self.counts = {"reviewed": 0, "looks_correct": 0, "problem": 0,
                       "unsure": 0, "stale": 0}
        self._load_log()
        self._recount()

    def _fail_log(self, where, message):
        abort("corrupted log %s: %s (refusing to drop or repair it)" % (where, message))

    def _validate_record(self, record, where):
        if not isinstance(record, dict):
            self._fail_log(where, "record must be a JSON object")
        for key in BODY_FIELDS + ("savedAtUtc", "status", "catalogFingerprint", "source"):
            if key not in record:
                self._fail_log(where, "missing field %r" % key)
        if record["status"] != REVIEW_STATUS:
            self._fail_log(where, "unexpected status %r" % (record["status"],))
        if record["source"] != REVIEW_SOURCE:
            self._fail_log(where, "unexpected source %r" % (record["source"],))
        if not isinstance(record["savedAtUtc"], str) or not record["savedAtUtc"]:
            self._fail_log(where, "savedAtUtc must be a non-empty string")
        if not isinstance(record["catalogFingerprint"], str) or not HEX64.match(record["catalogFingerprint"]):
            self._fail_log(where, "catalogFingerprint must be a sha256 hex digest")
        error, normalized = validate_body({key: record[key] for key in BODY_FIELDS}, None)
        if error:
            self._fail_log(where, error)
        if normalized != {key: record[key] for key in BODY_FIELDS}:
            self._fail_log(where, "body fields are not in canonical form")

    def _apply(self, record):
        if record["verdict"] == "withdrawn":
            self.latest.pop(record["id"], None)
        else:
            self.latest[record["id"]] = record

    def _load_log(self):
        try:
            with open(self.log_path, "r", encoding="utf-8") as handle:
                text = handle.read()
        except FileNotFoundError:
            return
        except (OSError, UnicodeDecodeError) as exc:
            abort("cannot read %s: %s" % (self.log_path, exc))
        if not text:
            return
        if not text.endswith("\n"):
            abort("corrupted log %s: file does not end with a complete line "
                  "(refusing to drop or repair it)" % self.log_path)
        for lineno, line in enumerate(text.split("\n")[:-1], start=1):
            where = "%s:%d" % (self.log_path, lineno)
            if not line.strip():
                self._fail_log(where, "blank line")
            try:
                record = json.loads(line)
            except ValueError as exc:
                self._fail_log(where, "invalid JSON (%s)" % exc)
            self._validate_record(record, where)
            request_id = record["requestId"]
            if request_id in self.seen:
                self._fail_log(where, "duplicate requestId %s" % request_id)
            self.events.append(record)
            self._apply(record)
            self.seen[request_id] = {"body": canonical_body(record), "record": record}

    def _is_current(self, record):
        return self.location_fingerprints.get(record["id"]) == record["fingerprint"].lower()

    def _recount(self):
        counts = {"reviewed": 0, "looks_correct": 0, "problem": 0,
                  "unsure": 0, "stale": 0}
        for record in self.latest.values():
            if self._is_current(record):
                counts["reviewed"] += 1
                counts[record["verdict"]] += 1
            else:
                counts["stale"] += 1
        self.counts = counts

    def append(self, record):
        line = json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n"
        with open(self.log_path, "a", encoding="utf-8", newline="") as handle:
            handle.write(line)
            handle.flush()
            os.fsync(handle.fileno())
        self.events.append(record)
        self._apply(record)
        self.seen[record["requestId"]] = {"body": canonical_body(record), "record": record}
        self._recount()
        self.write_summary()

    def write_summary(self):
        with self.lock:
            pending = sorted(
                loc_id for loc_id, record in self.latest.items()
                if self._is_current(record))
            payload = {
                "counts": dict(self.counts),
                "pendingIds": pending,
                "updatedAtUtc": utc_now(),
                "catalogFingerprint": self.catalog_fingerprint,
            }
        blob = (json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")
        tmp_path = self.summary_path + ".tmp"
        with open(tmp_path, "wb") as handle:
            handle.write(blob)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp_path, self.summary_path)
        try:
            dir_fd = os.open(self.data_dir, os.O_RDONLY)
            try:
                os.fsync(dir_fd)
            finally:
                os.close(dir_fd)
        except OSError:
            pass

    def state_payload(self):
        with self.lock:
            return {"latest": dict(self.latest), "counts": dict(self.counts),
                    "total": self.total}

    def export_payload(self):
        with self.lock:
            return {"catalogFingerprint": self.catalog_fingerprint,
                    "exportedAtUtc": utc_now(), "count": len(self.events),
                    "events": list(self.events)}


class App:
    def __init__(self, catalog, store, token, index_path, verification=None, maps_config=None):
        self.catalog = catalog
        self.store = store
        self.token = token
        self.index_path = index_path
        self.boundaries = BoundaryStore(catalog)
        self.verification = verification or load_scores(None, catalog)
        self.maps_config = maps_config or {"enabled": False}


class Handler(BaseHTTPRequestHandler):
    server_version = "ManualLocalityReview/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[serve] %s %s\n" % (self.client_address[0], fmt % args))

    def do_GET(self):
        self._dispatch("GET")

    def do_HEAD(self):
        self._dispatch("HEAD")

    def do_POST(self):
        self._dispatch("POST")

    def do_PUT(self):
        self._dispatch("PUT")

    def do_PATCH(self):
        self._dispatch("PATCH")

    def do_DELETE(self):
        self._dispatch("DELETE")

    def do_OPTIONS(self):
        self._dispatch("OPTIONS")

    def _dispatch(self, method):
        try:
            self._route(method)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        except Exception:
            self.close_connection = True
            sys.stderr.write("[serve] unhandled error:\n" + traceback.format_exc())
            try:
                self._send_error(500, "internal_error", "Internal server error.")
            except Exception:
                pass

    def _origin_problem(self):
        host = (self.headers.get("Host") or "").strip()
        if not host:
            return "Missing Host header."
        name, sep, port = host.rpartition(":")
        if not sep:
            name, port = host, ""
        if port and not port.isdigit():
            return "Invalid Host header."
        if name.lower() not in ("127.0.0.1", "localhost"):
            return "Unexpected Host header."
        if port and int(port) != self.server.server_address[1]:
            return "Unexpected Host header."
        origin = self.headers.get("Origin")
        if origin is not None and origin.rstrip("/").lower() != ("http://" + host).lower():
            return "Cross-origin request rejected."
        return None

    def _route(self, method):
        app = self.server.app
        problem = self._origin_problem()
        if problem:
            self.close_connection = True
            return self._send_error(403, "forbidden", problem)
        path = urlsplit(self.path).path
        head = method == "HEAD"
        if path in ("/", "/index.html"):
            if method not in ("GET", "HEAD"):
                return self._method_not_allowed("GET, HEAD")
            return self._serve_index(app, head)
        if path in ("/boundary.js", "/boundary.css", "/google_map.js", "/google_map.css"):
            if method not in ("GET", "HEAD"):
                return self._method_not_allowed("GET, HEAD")
            content_type = "text/javascript; charset=utf-8" if path.endswith(".js") else "text/css; charset=utf-8"
            return self._serve_file(os.path.join(os.path.dirname(app.index_path), path[1:]), content_type, head)
        if path == "/api/maps-config":
            if method not in ("GET", "HEAD"):
                return self._method_not_allowed("GET, HEAD")
            return self._send_json(200, app.maps_config, head)
        if path == "/api/boundary":
            if method not in ("GET", "HEAD"):
                return self._method_not_allowed("GET, HEAD")
            query = parse_qs(urlsplit(self.path).query, keep_blank_values=True)
            ids = query.get("id", [])
            if len(ids) != 1 or not ids[0]:
                return self._send_error(400, "invalid_request", "Select one place to view its boundary.", head)
            try:
                payload = app.boundaries.get(ids[0])
            except KeyError:
                return self._send_error(404, "not_found", "This place is not in the current catalog.", head)
            except BoundaryError as exc:
                return self._send_error(503, "boundary_unavailable", str(exc), head)
            return self._send_json(200, payload, head)
        if path == "/api/catalog":
            if method not in ("GET", "HEAD"):
                return self._method_not_allowed("GET, HEAD")
            payload = dict(app.catalog)
            payload["csrfToken"] = app.token
            payload["verification"] = app.verification
            return self._send_json(200, payload, head)
        if path == "/api/state":
            if method not in ("GET", "HEAD"):
                return self._method_not_allowed("GET, HEAD")
            return self._send_json(200, app.store.state_payload(), head)
        if path == "/api/health":
            if method not in ("GET", "HEAD"):
                return self._method_not_allowed("GET, HEAD")
            with app.store.lock:
                payload = {"ok": True, "app": "manual-locality-review",
                           "catalogFingerprint": app.store.catalog_fingerprint,
                           "scoreReportSha256": app.verification.get("reportSha256"),
                           "mapsEnabled": app.maps_config.get("enabled", False),
                           "total": app.store.total,
                           "reviewed": app.store.counts["reviewed"]}
            return self._send_json(200, payload, head)
        if path == "/api/export":
            if method not in ("GET", "HEAD"):
                return self._method_not_allowed("GET, HEAD")
            payload = app.store.export_payload()
            return self._send_json(
                200, payload, head,
                [("Content-Disposition", 'attachment; filename="manual-locality-review-export.json"')])
        if path == "/api/review":
            if method != "POST":
                return self._method_not_allowed("POST")
            return self._handle_review(app)
        if method == "POST":
            self.close_connection = True
        return self._send_error(404, "not_found", "Unknown route.")

    def _handle_review(self, app):
        supplied = self.headers.get("X-Review-Token") or ""
        try:
            token_ok = secrets.compare_digest(supplied, app.token)
        except TypeError:
            token_ok = False
        if not token_ok:
            self.close_connection = True
            return self._send_error(403, "forbidden", "Missing or invalid X-Review-Token.")
        content_type = (self.headers.get("Content-Type") or "").split(";")[0].strip().lower()
        if content_type != "application/json":
            self.close_connection = True
            return self._send_error(415, "unsupported_media_type",
                                    "Content-Type must be application/json.")
        raw_length = self.headers.get("Content-Length")
        if raw_length is None:
            self.close_connection = True
            return self._send_error(411, "length_required", "Content-Length is required.")
        try:
            length = int(raw_length)
        except ValueError:
            self.close_connection = True
            return self._send_error(400, "invalid_request", "Invalid Content-Length.")
        if length < 0:
            self.close_connection = True
            return self._send_error(400, "invalid_request", "Invalid Content-Length.")
        if length > MAX_BODY_BYTES:
            self.close_connection = True
            return self._send_error(413, "payload_too_large",
                                    "Request body must not exceed 32768 bytes.")
        raw = self.rfile.read(length)
        if len(raw) != length:
            self.close_connection = True
            return self._send_error(400, "invalid_request", "Incomplete request body.")
        try:
            body = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            return self._send_error(400, "invalid_json",
                                    "Request body must be valid UTF-8 JSON.")
        error, normalized = validate_body(body, app.store.location_fingerprints)
        if error:
            return self._send_error(400, "invalid_body", error)
        request_id = normalized["requestId"]
        canonical = canonical_body(normalized)
        with app.store.lock:
            prior = app.store.seen.get(request_id)
            if prior is not None:
                if prior["body"] == canonical:
                    app.store.write_summary()
                    return self._send_json(200, {"ok": True, "review": prior["record"],
                                                 "counts": dict(app.store.counts)})
                return self._send_error(409, "request_id_conflict",
                                        "requestId was already used with a different body.")
            expected = app.store.location_fingerprints.get(normalized["id"])
            if expected is None:
                return self._send_error(400, "invalid_body", "Unknown location id.")
            if normalized["fingerprint"] != expected:
                return self._send_error(409, "stale_fingerprint",
                                        "Location fingerprint is stale; reload the catalog.")
            record = dict(normalized)
            record["savedAtUtc"] = utc_now()
            record["status"] = REVIEW_STATUS
            record["catalogFingerprint"] = app.store.catalog_fingerprint
            record["source"] = REVIEW_SOURCE
            app.store.append(record)
            payload = {"ok": True, "review": record, "counts": dict(app.store.counts)}
        return self._send_json(200, payload)

    def _serve_index(self, app, head):
        return self._serve_file(app.index_path, "text/html; charset=utf-8", head)

    def _serve_file(self, file_path, content_type, head):
        try:
            with open(file_path, "rb") as handle:
                blob = handle.read()
        except OSError:
            return self._send_error(404, "not_found", "The requested page resource is not available.", head)
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(blob)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if not head:
            self.wfile.write(blob)

    def _method_not_allowed(self, allow):
        self.close_connection = True
        return self._send_error(405, "method_not_allowed", "Method not allowed.",
                                headers=[("Allow", allow)])

    def _send_error(self, status, code, message, head=False, headers=None):
        return self._send_json(status, {"ok": False, "error": code, "message": message},
                               head, headers)

    def _send_json(self, status, payload, head=False, headers=None):
        blob = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(blob)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        for key, value in (headers or ()):
            self.send_header(key, value)
        self.end_headers()
        if not head:
            self.wfile.write(blob)


def acquire_data_lock(data_dir):
    path = os.path.join(data_dir, "server.lock")
    handle = open(path, "a+b")
    if handle.tell() == 0:
        handle.write(b"\0")
        handle.flush()
    handle.seek(0)
    try:
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        abort("data directory %s is already in use by another serve.py process" % data_dir)
    return handle


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="serve.py", description="Manual locality review server (stdlib only).")
    parser.add_argument("--catalog", required=True)
    parser.add_argument("--data-dir", required=True, dest="data_dir")
    parser.add_argument("--port", type=int, default=8769)
    parser.add_argument("--scores", help="Absolute path to the accepted verification score report")
    parser.add_argument("--maps-key-file", help="Absolute path to a Windows-encrypted review-only Maps key")
    args = parser.parse_args(argv)
    if not os.path.isabs(args.catalog) or not os.path.isfile(args.catalog):
        parser.error("--catalog must be an absolute path to an existing file")
    if not os.path.isabs(args.data_dir):
        parser.error("--data-dir must be an absolute path")
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    catalog = load_catalog(args.catalog)
    try:
        maps_config = load_maps_config(args.maps_key_file)
    except (ValueError, OSError, UnicodeError) as exc:
        abort("cannot load review Maps configuration: %s" % exc)
    try:
        verification = load_scores(args.scores, catalog)
    except (ValueError, OSError) as exc:
        abort("cannot load verification scores: %s" % exc)
    try:
        os.makedirs(args.data_dir, exist_ok=True)
    except OSError as exc:
        abort("cannot create data directory %s: %s" % (args.data_dir, exc))
    if not os.path.isdir(args.data_dir):
        abort("data directory %s is not a directory" % args.data_dir)
    global _DATA_LOCK
    _DATA_LOCK = acquire_data_lock(args.data_dir)
    store = Store(args.data_dir, catalog)
    store.write_summary()
    app = App(catalog, store, secrets.token_urlsafe(32),
              os.path.join(os.path.dirname(os.path.abspath(__file__)), "index.html"), verification, maps_config)
    try:
        server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    except OSError as exc:
        abort("cannot bind 127.0.0.1:%d: %s" % (args.port, exc))
    server.daemon_threads = True
    server.app = app
    with store.lock:
        reviewed = store.counts["reviewed"]
    sys.stderr.write("[serve] listening on http://127.0.0.1:%d (catalog %s, data-dir %s, "
                     "%d locations, %d reviewed)\n" %
                     (args.port, args.catalog, args.data_dir, store.total, reviewed))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
