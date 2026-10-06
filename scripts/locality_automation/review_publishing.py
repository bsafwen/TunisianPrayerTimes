"""Render and atomically publish canonical locality review queues.

This module deliberately does not select, group, or rank work. The queue
engine owns those decisions; this module preserves its order and statuses,
adds links for the existing local review screen and saved evidence, and writes
one JSON/HTML pair per governorate from a single publisher.
"""
from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
import copy
import hashlib
import html
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit


MANUAL_TOOL_URL = "http://127.0.0.1:8769/"
SCHEMA_VERSION = "locality-review-queue/1"
PUBLICATION_QUALIFICATION = {
    "automaticReview": (
        "Automatic diagnostics and model findings are triage signals and "
        "advisory only; they do not establish geographic reliability."
    ),
    "geographicReliability": "not certified by this queue",
    "modelRequests": 0,
}
_LOCK_NAME = ".review-queue.publish.lock"
_SLUG_RUN = re.compile(r"-+")


def _queue_list(queues):
    if isinstance(queues, dict):
        raw = [queues]
    elif isinstance(queues, (list, tuple)):
        raw = list(queues)
    else:
        raise TypeError("queues must be one queue object or a list of queue objects")
    seen = set()
    for queue in raw:
        if not isinstance(queue, dict):
            raise ValueError("each review queue must be an object")
        if queue.get("schemaVersion") != SCHEMA_VERSION:
            raise ValueError("unsupported review queue schemaVersion")
        governorate = queue.get("governorate")
        if not isinstance(governorate, str) or not governorate.strip():
            raise ValueError("review queue governorate must be a non-empty string")
        if governorate in seen:
            raise ValueError("duplicate governorate review queue: " + governorate)
        seen.add(governorate)
        if not isinstance(queue.get("items"), list):
            raise ValueError("review queue items must be a list")
        if any(not isinstance(item, dict) for item in queue["items"]):
            raise ValueError("review queue items must contain only objects")
    return raw


def _manual_base(queue, override=None):
    raw = override
    if raw is None:
        raw = queue.get("manualToolUrl")
    if raw is None and isinstance(queue.get("manualTool"), dict):
        raw = queue["manualTool"].get("url")
    if not isinstance(raw, str):
        return MANUAL_TOOL_URL
    try:
        parsed = urlsplit(raw.strip())
        host = (parsed.hostname or "").lower()
        # The review UI is intentionally local. Never turn an input field into
        # an arbitrary clickable navigation target.
        if (parsed.scheme not in ("http", "https")
                or host not in ("127.0.0.1", "localhost")
                or parsed.username is not None or parsed.password is not None):
            return MANUAL_TOOL_URL
        _ = parsed.port  # reject malformed ports
    except ValueError:
        return MANUAL_TOOL_URL
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path or "/", "", ""))


def _manual_href(base, query):
    parsed = urlsplit(base)
    return urlunsplit((
        parsed.scheme,
        parsed.netloc,
        parsed.path or "/",
        urlencode({"q": str(query)}),
        "",
    ))


def _safe_evidence_href(record):
    if isinstance(record, str):
        record = {"file": record}
    if not isinstance(record, dict):
        return None
    raw_href = record.get("href")
    raw_url = record.get("url")
    raw_file = record.get("file", record.get("path"))
    for raw in (raw_href, raw_url):
        if not isinstance(raw, str) or not raw.strip():
            continue
        try:
            parsed = urlsplit(raw.strip())
        except ValueError:
            continue
        if parsed.scheme in ("http", "https") and parsed.hostname and not parsed.username and not parsed.password:
            return raw.strip()
        if parsed.scheme == "file" and not parsed.netloc:
            return raw.strip()
    if isinstance(raw_file, str) and raw_file.strip():
        path = Path(raw_file.strip())
        if path.is_absolute():
            try:
                return path.as_uri()
            except (OSError, ValueError):
                return None
    return None


def _label(record, fallback="Evidence"):
    if isinstance(record, dict):
        for key in ("name", "label", "title", "file", "path", "url"):
            value = record.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
    if isinstance(record, str) and record.strip():
        return record.strip()
    return fallback


def _case_manual_links(item, base):
    records = item.get("caseRecords")
    rows = []
    if isinstance(records, list):
        for record in records:
            if not isinstance(record, dict):
                continue
            case_id = record.get("id")
            name = record.get("nameAr")
            query = name if isinstance(name, str) and name.strip() else case_id
            if isinstance(query, str) and query:
                rows.append({
                    "caseId": case_id,
                    "nameAr": name if isinstance(name, str) else None,
                    "url": _manual_href(base, query),
                })
    if not rows:
        case_ids = item.get("caseIds")
        case_id = case_ids[0] if isinstance(case_ids, list) and case_ids else item.get("id")
        name = item.get("nameAr")
        query = name if isinstance(name, str) and name.strip() else case_id
        if isinstance(query, str) and query:
            rows.append({"caseId": case_id, "nameAr": name, "url": _manual_href(base, query)})
    return rows


def _item_evidence(item, queue, evidence_by_case):
    candidates = []
    for key in ("evidencePackets", "evidence", "evidenceLinks"):
        value = item.get(key)
        if isinstance(value, list):
            candidates.extend(value)
        elif isinstance(value, dict):
            candidates.append(value)
    by_case = evidence_by_case or {}
    if isinstance(by_case, dict):
        case_ids = item.get("caseIds") if isinstance(item.get("caseIds"), list) else []
        for case_id in [item.get("id"), *case_ids]:
            rows = by_case.get(case_id)
            if isinstance(rows, list):
                candidates.extend(rows)
            elif rows is not None:
                candidates.append(rows)
    packet_by_case = queue.get("evidenceByCase")
    if isinstance(packet_by_case, dict):
        case_ids = item.get("caseIds") if isinstance(item.get("caseIds"), list) else []
        for case_id in [item.get("id"), *case_ids]:
            rows = packet_by_case.get(case_id)
            if isinstance(rows, list):
                candidates.extend(rows)
            elif rows is not None:
                candidates.append(rows)
    seen = set()
    result = []
    for candidate in candidates:
        href = _safe_evidence_href(candidate)
        if href is None or href in seen:
            continue
        seen.add(href)
        result.append({"name": _label(candidate), "href": href})
    return result


def _publication_copy(queue, manual_tool_url=None, evidence_by_case=None):
    result = copy.deepcopy(queue)
    base = _manual_base(queue, manual_tool_url)
    links_by_id = {}
    for item in result["items"]:
        manual = _case_manual_links(item, base)
        evidence = _item_evidence(item, result, evidence_by_case)
        item["manualReviewLinks"] = manual
        item["manualReviewUrl"] = manual[0]["url"] if manual else None
        item["evidenceLinks"] = evidence
        unit_id = item.get("id")
        if isinstance(unit_id, str):
            links_by_id[unit_id] = {"manualReviewLinks": manual, "evidenceLinks": evidence}
    for item in result.get("auditItems", []):
        owner_id = item.get("currentSelectableOwnerId")
        name = item.get("nameAr")
        query = name if isinstance(name, str) and name.strip() else owner_id
        item["manualReviewLinks"] = ([{
            "caseId": owner_id,
            "nameAr": name if isinstance(name, str) else None,
            "url": _manual_href(base, query),
        }] if isinstance(query, str) and query else [])
        item["manualReviewUrl"] = item["manualReviewLinks"][0]["url"] if item["manualReviewLinks"] else None
        item["evidenceLinks"] = _item_evidence(item, result, evidence_by_case)
    top_packets = result.get("evidencePackets")
    if isinstance(top_packets, list):
        linked_packets = []
        seen = set()
        for packet in top_packets:
            href = _safe_evidence_href(packet)
            if href is None or href in seen:
                continue
            seen.add(href)
            linked_packets.append({"name": _label(packet), "href": href})
        result["evidencePacketLinks"] = linked_packets
    result["publication"] = {
        "manualToolUrl": base,
        "modelRequests": 0,
        "qualification": PUBLICATION_QUALIFICATION["automaticReview"],
        "geographicReliability": PUBLICATION_QUALIFICATION["geographicReliability"],
    }
    return result


def _esc(value):
    if value is None:
        return ""
    if isinstance(value, (dict, list, tuple)):
        value = json.dumps(value, ensure_ascii=False, sort_keys=True)
    return html.escape(str(value), quote=True)


def _display_status(item):
    disposition = str(item.get("disposition") or "").upper()
    if disposition in ("ACCEPTED", "REJECTED"):
        value = disposition
    else:
        value = item.get("status") or item.get("disposition") or "UNRESOLVED"
    return str(value).replace("_", " ").title()


def _count_value(counts, label):
    aliases = {
        "accepted": ("accepted", "acceptedUnits", "acceptedCases", "acceptedCount"),
        "rejected": ("rejected", "rejectedUnits", "rejectedCases", "rejectedCount"),
        "unresolved": ("unresolved", "unresolvedUnits", "unresolvedCases", "unresolvedCount", "queued"),
    }
    for key in aliases[label]:
        value = counts.get(key)
        if isinstance(value, int) and not isinstance(value, bool):
            return str(value)
    return "unknown"


def _reasons_html(item):
    parts = []
    collision = item.get("displayCollision")
    if isinstance(collision, dict):
        members = collision.get("members")
        count = len(members) if isinstance(members, list) else 0
        if count > 1:
            parts.append("<b>Duplicate picker label:</b> %s entries share this name and parent; identity needs review." % count)
        checks = collision.get("checks")
        if isinstance(checks, list):
            for check in checks:
                if (isinstance(check, dict) and check.get("relation") == "outside"
                        and check.get("pointId") != check.get("polygonId")):
                    parts.append("Point %s is outside namesake polygon %s in the shipped geometry." % (
                        _esc(check.get("pointId")), _esc(check.get("polygonId"))))
                    break
    for key in ("priorityReasons", "categories"):
        values = item.get(key)
        if isinstance(values, list):
            parts.extend(_esc(value) for value in values if value is not None)
        elif values:
            parts.append(_esc(values))
    question = item.get("question")
    if isinstance(question, str) and question.strip():
        parts.append(_esc(question.strip()))
    review_question = item.get("reviewQuestion")
    if isinstance(review_question, str) and review_question.strip():
        parts.append(_esc(review_question.strip()))
    conflicts = item.get("unresolvedConflicts")
    if isinstance(conflicts, list):
        for conflict in conflicts[:3]:
            if isinstance(conflict, dict) and conflict.get("text"):
                parts.append(_esc(conflict["text"]))
        if len(conflicts) > 3:
            parts.append("%s more unresolved conflict(s)" % (len(conflicts) - 3))
    advice = item.get("priorAdvice")
    if isinstance(advice, list):
        for finding in advice[:2]:
            if isinstance(finding, dict):
                text = finding.get("text") or finding.get("assessment") or finding.get("summary")
                if text:
                    parts.append("<b>Advisory, unverified:</b> " + _esc(text))
    latest = item.get("latestAdvisory")
    if isinstance(latest, dict):
        sections = []
        for label, key in (("Still unresolved", "unresolved"), ("Suggested next steps", "recommendedNextSteps")):
            values = latest.get(key)
            if isinstance(values, list):
                entries = "".join("<li>%s</li>" % _esc(value) for value in values[:5] if isinstance(value, str))
                if entries:
                    sections.append("<b>%s</b><ul>%s</ul>" % (label, entries))
        parts.append("<details><summary>Luna review (unverified)</summary>%s</details>" % "".join(sections))
    return "<br>".join(parts) if parts else '<span class="muted">No detail provided</span>'


def _reports_html(item):
    reports = item.get("manualReports")
    if not isinstance(reports, list) or not reports:
        return '<span class="muted">None</span>'
    visible = []
    for report in reports[:3]:
        if not isinstance(report, dict):
            continue
        verdict = report.get("verdict", "user report")
        assertion = report.get("userAssertion")
        if assertion is None:
            assertion = report.get("assertion")
        if assertion is None:
            assertion = report.get("note")
        if assertion is None:
            assertion = report.get("issues")
        detail = _esc(assertion) if assertion else ""
        stale = " (needs re-check after catalog change)" if report.get("stale") is True else ""
        visible.append("<div><b>%s%s</b>%s</div>" % (_esc(verdict), stale, (": " + detail) if detail else ""))
    if len(reports) > 3:
        visible.append('<div class="muted">and %s more</div>' % (len(reports) - 3))
    return "".join(visible) or '<span class="muted">None</span>'


def _evidence_html(item):
    links = item.get("evidenceLinks")
    if not isinstance(links, list) or not links:
        return '<span class="muted">None</span>'
    return "<br>".join(
        '<a href="%s" rel="noopener noreferrer">%s</a>' % (
            _esc(link.get("href")), _esc(link.get("name"))
        )
        for link in links if isinstance(link, dict) and link.get("href")
    ) or '<span class="muted">None</span>'


def _manual_html(item):
    links = item.get("manualReviewLinks")
    if not isinstance(links, list) or not links:
        return '<span class="muted">Unavailable</span>'
    return "<br>".join(
        '<a href="%s">%s</a>' % (
            _esc(link.get("url")), _esc(link.get("nameAr") or link.get("caseId") or "manual review")
        )
        for link in links if isinstance(link, dict) and link.get("url")
    ) or '<span class="muted">Unavailable</span>'


def render_review_queue_html(queue, *, manual_tool_url=None, evidence_by_case=None):
    """Render one canonical governorate queue without changing its item order."""
    [source] = _queue_list(queue)
    report = _publication_copy(source, manual_tool_url, evidence_by_case)
    counts = report.get("counts") if isinstance(report.get("counts"), dict) else {}
    style = (
        ":root{--ink:#142a38;--muted:#526777;--line:#d9e2e8;--bg:#f2f6f8;--card:#fff;--blue:#0b638b}"
        "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.5 Segoe UI,Arial,sans-serif}"
        "header{background:#123246;color:white;padding:18px 24px}header h1{margin:0;font-size:21px}.sub{color:#c5d8e2;margin-top:4px}"
        "main{max-width:1400px;margin:auto;padding:16px 22px 40px}.tiles{display:flex;gap:10px;flex-wrap:wrap;margin:12px 0}"
        ".tile{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:8px 14px;min-width:120px}.k{font-size:11px;text-transform:uppercase;color:var(--muted)}.v{font-size:21px;font-weight:700}"
        ".note{background:#fff8e4;border:1px solid #eadcae;border-radius:8px;padding:9px 12px;margin:10px 0 14px}"
        ".table-wrap{overflow-x:auto;background:white;border:1px solid var(--line);border-radius:8px}table{width:100%;border-collapse:collapse;min-width:900px}"
        "th,td{text-align:left;vertical-align:top;padding:8px 9px;border-bottom:1px solid var(--line)}th{font-size:11px;text-transform:uppercase;color:var(--muted);background:#f8fafb}"
        "td.place{min-width:180px}td.reason{min-width:240px}td.reports{min-width:200px}td.link{min-width:120px}.muted{color:var(--muted)}a{color:var(--blue);overflow-wrap:anywhere}"
        "code{font-size:12px;overflow-wrap:anywhere}footer{color:var(--muted);font-size:12px;margin-top:14px}"
    )
    rows = []
    for item in report["items"]:
        case_ids = item.get("caseIds") if isinstance(item.get("caseIds"), list) else []
        labels = [x for x in (item.get("nameAr"), item.get("parentAr")) if isinstance(x, str) and x]
        if case_ids:
            labels.append(", ".join(str(value) for value in case_ids))
        title = "<br>".join([_esc(item.get("id", "")), *(_esc(value) for value in labels)])
        priority = item.get("priority")
        priority_text = _esc(priority) if priority is not None else '<span class="muted">unknown</span>'
        rows.append(
            "<tr><td>%s</td><td class=\"place\">%s</td><td>%s</td><td class=\"reason\">%s</td>"
            "<td class=\"reports\">%s</td><td class=\"link\">%s</td><td class=\"link\">%s</td></tr>" % (
                priority_text,
                title,
                _esc(_display_status(item)),
                _reasons_html(item),
                _reports_html(item),
                _manual_html(item),
                _evidence_html(item),
            )
        )
    audit_rows = []
    for item in report.get("auditItems", []):
        if not isinstance(item, dict):
            continue
        historical_id = item.get("historicalMemberId")
        owner_id = item.get("currentSelectableOwnerId")
        owner_label = "<br>".join(_esc(value) for value in (
            item.get("nameAr"), item.get("parentAr"), owner_id,
        ) if isinstance(value, str) and value)
        historical_label = "<br>".join(_esc(value) for value in (
            "Historical picker member", historical_id,
        ) if isinstance(value, str) and value)
        audit_rows.append(
            "<tr><td>%s</td><td class=\"place\">%s</td><td>%s</td>"
            "<td class=\"reports\">%s</td><td class=\"link\">%s</td><td class=\"link\">%s</td></tr>" % (
                historical_label, owner_label,
                _esc(_display_status(item)),
                _reports_html(item), _manual_html(item), _evidence_html(item),
            )
        )
    if not rows:
        rows.append('<tr><td colspan="7" class="muted">No unresolved review units.</td></tr>')
    generated = report.get("generatedAtUtc") or datetime.now(timezone.utc).isoformat()
    gov = report.get("governorate")
    title = "Locality review queue — " + str(gov)
    packet_links = report.get("evidencePacketLinks")
    packet_note = ""
    if isinstance(packet_links, list) and packet_links:
        packet_note = " Saved packets: " + "; ".join(
            '<a href="%s" rel="noopener noreferrer">%s</a>' % (_esc(row["href"]), _esc(row["name"]))
            for row in packet_links if isinstance(row, dict) and row.get("href")
        ) + "."
    out = [
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">",
        '<meta name="viewport" content="width=device-width, initial-scale=1">',
        "<title>%s</title><style>%s</style></head><body>" % (_esc(title), style),
        "<header><h1>%s</h1><div class=\"sub\">Generated %s · %s review units</div></header><main>" % (
            _esc(title), _esc(generated), len(report["items"])
        ),
        '<div class="tiles">',
    ]
    for label in ("accepted", "rejected", "unresolved"):
        out.append('<div class="tile"><div class="k">%s</div><div class="v">%s</div></div>' % (
            _esc(label), _esc(_count_value(counts, label))
        ))
    for label, value in counts.items():
        if label in ("accepted", "rejected", "unresolved", "acceptedUnits", "rejectedUnits", "unresolvedUnits", "acceptedCases", "rejectedCases", "unresolvedCases", "acceptedCount", "rejectedCount", "unresolvedCount", "queued"):
            continue
        out.append('<div class="tile"><div class="k">%s</div><div class="v">%s</div></div>' % (_esc(label), _esc(value)))
    out.extend([
        "</div>",
        '<div class="note">Automatic checks and model advice are triage signals. They do not confirm names, GPS points, or boundaries; geographic reliability remains unverified until supported by geographic evidence.%s</div>' % packet_note,
        '<div class="table-wrap"><table><thead><tr><th>Priority</th><th>Place / unit</th><th>Disposition</th><th>Review focus</th><th>User reports</th><th>Manual tool</th><th>Evidence</th></tr></thead><tbody>',
        "".join(rows),
        "</tbody></table></div>",
        ('<h2>Historical grouped-member audits</h2><p class="note">These records are excluded from active selectable review counts. Their old reports remain open for review against the current owner; grouping does not verify identity, GPS, boundaries, or reliability.</p>'
         '<div class="table-wrap"><table><thead><tr><th>Historical member</th><th>Current selectable owner</th><th>Audit state</th><th>Retained user reports</th><th>Open owner in manual tool</th><th>Evidence</th></tr></thead><tbody>%s</tbody></table></div>' % "".join(audit_rows)
         if audit_rows else ""),
        '<footer>Queue publication made 0 model requests. Items retain the canonical queue-engine order.</footer>',
        "</main></body></html>",
    ])
    return "".join(out)


def _slug(governorate):
    safe = "".join(ch.lower() if ch.isascii() and ch.isalnum() else "-" if ch.isspace() else ch
                   for ch in governorate.strip())
    safe = _SLUG_RUN.sub("-", safe).strip("-_")[:48]
    if not safe:
        safe = "governorate"
    suffix = hashlib.sha256(governorate.encode("utf-8")).hexdigest()[:10]
    return "gov-" + safe + "-" + suffix


@contextmanager
def _publisher_lock(directory):
    lock_path = directory / _LOCK_NAME
    try:
        fd = os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError as exc:
        raise RuntimeError("another review queue publisher holds " + str(lock_path)) from exc
    try:
        with os.fdopen(fd, "w", encoding="ascii", newline="\n") as handle:
            handle.write("pid=%s\n" % os.getpid())
            handle.flush()
            os.fsync(handle.fileno())
        yield
    finally:
        try:
            lock_path.unlink()
        except FileNotFoundError:
            pass


def _stage_text(target, text):
    fd, temp_name = tempfile.mkstemp(prefix=target.name + ".", suffix=".tmp", dir=str(target.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
    except BaseException:
        try:
            os.unlink(temp_name)
        except OSError:
            pass
        raise
    return Path(temp_name)


def publish_review_queue(queues, output_dir, *, manual_tool_url=None, evidence_by_case=None):
    """Publish canonical queue(s) as per-governorate JSON and HTML files.

    All output is staged in the destination directory, then each file is
    installed with ``os.replace`` while one cross-process publisher lock is
    held. This is a local, data-only operation and does not call a model.
    """
    queue_rows = _queue_list(queues)
    out_dir = Path(output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    records = []
    seen_targets = set()
    for queue in queue_rows:
        gov = queue["governorate"].strip()
        stem = _slug(gov)
        json_path = out_dir / (stem + ".json")
        html_path = out_dir / (stem + ".html")
        if json_path in seen_targets or html_path in seen_targets:
            raise ValueError("governorates resolve to duplicate output filenames")
        seen_targets.update((json_path, html_path))
        payload = _publication_copy(queue, manual_tool_url, evidence_by_case)
        payload_text = json.dumps(payload, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
        html_text = render_review_queue_html(queue, manual_tool_url=manual_tool_url, evidence_by_case=evidence_by_case)
        records.append((gov, json_path, payload_text, html_path, html_text))

    staged = []
    with _publisher_lock(out_dir):
        try:
            for gov, json_path, json_text, html_path, html_text in records:
                staged.append((json_path, _stage_text(json_path, json_text)))
                staged.append((html_path, _stage_text(html_path, html_text)))
            for target, temporary in staged:
                os.replace(str(temporary), str(target))
        finally:
            for _target, temporary in staged:
                try:
                    temporary.unlink()
                except FileNotFoundError:
                    pass
    return {
        "files": [
            {"governorate": gov, "json": str(json_path.resolve()), "html": str(html_path.resolve())}
            for gov, json_path, _json_text, html_path, _html_text in records
        ]
    }
