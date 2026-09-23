"""Static reporting for the locality automation package.

Renders a compact ``report.json`` plus a self-contained, JavaScript-free
``report.html`` dashboard from inputs computed elsewhere. The accepted metric
is copied through unchanged: absent fields render as ``unknown`` (never a
fabricated zero), no overall percentage or ETA is asserted, and no source link
is invented.
"""

import html
import json
import os
import re
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

try:  # package atomic JSON writer; assumed signature write(path, data)
    from .common import write as _write_json
except Exception:  # pragma: no cover - allows standalone import
    _write_json = None

UNKNOWN = "unknown"
MANUAL_TOOL_URL = "http://127.0.0.1:8769/"
ACTIVE_CONTEXT_EN = "Ben Arous"
ACTIVE_CONTEXT_AR = "\u0628\u0646 \u0639\u0631\u0648\u0633"
SCORE_BUCKETS = tuple(range(100, -1, -1))
JOB_ISSUE_STATUSES = ("failed", "stale", "held")
_SCHEME = re.compile(r"^[A-Za-z][A-Za-z0-9+.\-]*:")
_DRIVE = re.compile(r"^[A-Za-z]:[\\/]")

CAVEATS = (
    "Overall progress is provisional and reported as unknown: the provided inputs do not support an honest overall percentage.",
    "ETA is unavailable until throughput has been measured.",
    "100% checklist completeness does not guarantee geographic reliability.",
    "Automation completion and verified geography are separate measures and are never merged.",
    "Missing or absent fields are shown as unknown, never as zero.",
)

CSS = (
    ":root{--ink:#12212e;--mut:#5d6b7a;--line:#d8e0e8;--bg:#f3f6fa;--card:#fff;--accent:#0b6bb5}"
    "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.55 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif}"
    "header{background:#0f2b40;color:#fff;padding:20px 26px}header h1{margin:0 0 6px;font-size:20px}.meta{color:#bcd0e0;font-size:13px}"
    "main{max-width:1120px;margin:0 auto;padding:18px 26px 60px}section{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 18px;margin:14px 0}"
    "h2{margin:0 0 10px;font-size:14px;letter-spacing:.07em;text-transform:uppercase;color:#33506b}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(168px,1fr));gap:10px}"
    ".tile{border:1px solid var(--line);border-radius:8px;padding:9px 11px;background:#fbfdff}.tile .k{font-size:11px;letter-spacing:.05em;text-transform:uppercase;color:var(--mut)}.tile .v{font-size:21px;font-weight:600;margin-top:2px}"
    "table{width:100%;border-collapse:collapse;font-size:14px}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}th{font-size:11px;letter-spacing:.05em;text-transform:uppercase;color:var(--mut)}"
    ".chips{display:flex;flex-wrap:wrap;gap:5px;margin-top:6px}.chip{border:1px solid var(--line);border-radius:6px;padding:2px 7px;font-size:12.5px;background:#fbfdff}.chip b{color:#33506b;font-weight:600;margin-right:4px}.chip-u{color:var(--mut)}"
    ".badge{display:inline-block;border-radius:999px;padding:2px 10px;font-size:12px;font-weight:700}.b-paused{background:#fdeccd;color:#7a4a04}.b-pending{background:#fcdcd7;color:#8c1d12}.b-clear{background:#d7f0e3;color:#0e5c40}.b-unknown{background:#e6ebf0;color:#46586a}"
    ".note{background:#fff8e6;border:1px solid #f0dda8;border-radius:8px;padding:9px 11px;font-size:13.5px;margin:8px 0 0}.caveat{background:#eef5fb;border:1px solid #cfe1f0;border-radius:8px;padding:8px 11px;font-size:13.5px;margin:6px 0 0}"
    ".unknown{color:var(--mut);font-style:italic}.ar{font-size:16px}code{background:#eef2f6;padding:1px 5px;border-radius:4px;font-size:12.5px}"
    ".small{font-size:12.5px;color:var(--mut)}footer{color:var(--mut);font-size:12.5px;margin-top:16px}a{color:var(--accent)}"
)


def _map(value):
    return value if isinstance(value, dict) else {}


def _number(value, cast):
    """Coerce a JSON-ish value to cast, or None when it is absent/not numeric."""
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        if cast is int and isinstance(value, float) and not value.is_integer():
            return None
        return cast(value)
    if isinstance(value, str):
        try:
            return cast(value.strip())
        except ValueError:
            return None
    return None


def _int_or_none(value):
    return _number(value, int)


def _num_or_none(value):
    return _number(value, float)


def _count_or_len(value):
    if isinstance(value, (list, tuple, set, dict)):
        return len(value)
    return _int_or_none(value)


def _text(value):
    if value is None:
        return UNKNOWN
    if isinstance(value, bool):
        return "yes" if value else "no"
    return str(value).strip() or UNKNOWN


def _as_text(value):
    """JSON-safe text for a value that may be a str, number, None or object."""
    if value is None or isinstance(value, str):
        return value
    if isinstance(value, bool):
        return "yes" if value else "no"
    if isinstance(value, (int, float)):
        return str(value)
    try:
        return json.dumps(value, ensure_ascii=False, sort_keys=True)
    except (TypeError, ValueError):
        return str(value)


def _href(target, base_dir):
    """Relative link when possible; absolute local paths use Path.as_uri()."""
    if not isinstance(target, str) or not target.strip():
        return None
    raw = target.strip()
    if _SCHEME.match(raw) and not _DRIVE.match(raw):
        return None  # remote or unknown scheme: never linked
    try:
        path = Path(raw)
        if path.is_absolute():
            return path.as_uri()
        rel = os.path.relpath(str(path), str(base_dir))
    except (OSError, ValueError):
        return None
    return rel.replace(os.sep, "/")


def _score_buckets(source):
    """Ordered {score: count|None} for 100..0 plus 'unscored'."""
    src = _map(source)
    buckets = {}
    for score in SCORE_BUCKETS:
        value = None
        for key in (str(score), score, "score_%d" % score):
            if key in src:
                value = _int_or_none(src[key])
                break
        buckets[str(score)] = value
    buckets["unscored"] = None
    for key in ("unscored", "none", "noScore", "no_score"):
        if key in src:
            buckets["unscored"] = _int_or_none(src[key])
            break
    return buckets


def _case_rows(cases):
    raw = cases.get("cases") if isinstance(cases, dict) else cases
    rows = []
    for index, item in enumerate(raw if isinstance(raw, list) else []):
        if not isinstance(item, dict):
            continue
        name = item.get("nameAr")
        parent = item.get("parentAr")
        rows.append({
            "id": _text(item.get("id", "case-%d" % (index + 1))),
            "nameAr": name.strip() if isinstance(name, str) and name.strip() else None,
            "parentAr": parent.strip() if isinstance(parent, str) and parent.strip() else None,
            "role": _as_text(item.get("role")),
            "sourceStatus": _as_text(item.get("sourceStatus")),
            "acceptedChecks": _count_or_len(item.get("acceptedChecks")),
            "missingChecks": _count_or_len(item.get("missingChecks")),
            "reviewRequired": item.get("reviewRequired") if isinstance(item.get("reviewRequired"), bool) else None,
        })
    return rows


def _diag_rows(diagnostics):
    rows = []
    for index, item in enumerate(_map(diagnostics).get("rows") or []):
        if not isinstance(item, dict):
            continue
        raw_flags = item.get("flags")
        raw_flags = raw_flags if isinstance(raw_flags, list) else ([raw_flags] if raw_flags else [])
        flags = []
        for flag in raw_flags:
            if isinstance(flag, dict):
                flags.append({"code": _text(flag.get("code")), "detail": _as_text(flag.get("detail")) or UNKNOWN})
            else:
                flags.append({"code": _text(flag), "detail": UNKNOWN})
        name = item.get("nameAr")
        rows.append({
            "id": _text(item.get("id", "row-%d" % (index + 1))),
            "nameAr": name.strip() if isinstance(name, str) and name.strip() else None,
            "flags": flags,
        })
    return rows


def _job_rows(job_states):
    rows = []
    for state in job_states if isinstance(job_states, list) else []:
        jobs = state.get("jobs") if isinstance(state, dict) else None
        if not isinstance(jobs, dict):
            continue
        for job_id, info in jobs.items():
            info = info if isinstance(info, dict) else {}
            rows.append({
                "id": _text(job_id),
                "status": _text(info.get("status")),
                "reason": _as_text(info.get("reason")),
                "startedAt": _as_text(info.get("startedAt")),
                "finishedAt": _as_text(info.get("finishedAt")),
            })
    rows.sort(key=lambda row: row["id"])
    return rows


def _feedback_items(items, limit=20):
    out = []
    for entry in (items if isinstance(items, list) else [])[:limit]:
        if isinstance(entry, dict):
            name = entry.get("nameAr")
            out.append({
                "id": _text(entry.get("id", entry.get("caseId", "?"))),
                "nameAr": name.strip() if isinstance(name, str) and name.strip() else None,
            })
        else:
            out.append({"id": _text(entry), "nameAr": None})
    return out


def _manual_base(url):
    if isinstance(url, str):
        raw = url.strip().lower()
        if raw.startswith(("http://127.0.0.1", "http://localhost", "https://127.0.0.1", "https://localhost")):
            return url.strip()
    return MANUAL_TOOL_URL


def _manual_href(name_ar, base):
    return "%s?q=%s" % (str(base).rstrip("/") + "/", quote(str(name_ar).strip(), safe=""))


def render_report(snapshot: dict, cases: dict, diagnostics: dict, job_states: list, feedback: dict, model_cost: dict, output_dir: Path) -> dict:
    """Render report.json and report.html atomically; return the compact report dict."""
    out_dir = Path(output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    snap = _map(snapshot)
    report_src = _map(snap.get("report"))
    counts_src = _map(report_src.get("counts"))
    remaining_src = _map(report_src.get("remaining"))
    ben_src = _map(snap.get("activeGovernorateCounts")) or _map(report_src.get("benArous"))
    metric_src = _map(snap.get("metric"))
    pins_src = _map(snap.get("pins"))
    manual_src = _map(snap.get("manual"))
    fb_src, cost_src = _map(feedback), _map(model_cost)
    paused = snap.get("investigationPaused") if isinstance(snap.get("investigationPaused"), bool) else None
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")

    case_rows = _case_rows(cases)
    case_notes = [_as_text(n) or UNKNOWN for n in (_map(cases).get("notes") if isinstance(_map(cases).get("notes"), list) else [])]
    diag_rows = _diag_rows(diagnostics)
    job_rows = _job_rows(job_states)
    jobs_by_status = {status: [r for r in job_rows if r["status"].lower() == status] for status in JOB_ISSUE_STATUSES}
    ambiguous = [c for c in case_rows if c["reviewRequired"] is True or (c["missingChecks"] or 0) > 0]
    flagged = [r for r in diag_rows if r["flags"]]
    pending_items = fb_src.get("pending") if isinstance(fb_src.get("pending"), list) else None
    stale_items = fb_src.get("stale") if isinstance(fb_src.get("stale"), list) else None
    pending_count = len(pending_items) if pending_items is not None else None
    stale_count = len(stale_items) if stale_items is not None else None

    counts_out = {
        "currentSelectableLocations": _int_or_none(counts_src.get("currentSelectableLocations")),
        "currentLocationsWithFirstPassEvidence": _int_or_none(counts_src.get("currentLocationsWithFirstPassEvidence")),
        "appCorrectionsInstalledInThisBatch": _int_or_none(counts_src.get("appCorrectionsInstalledInThisBatch")),
        "diagnosticCandidates": _int_or_none(counts_src.get("diagnosticCandidates")),
        "remainingPriorityBoundaryCases": len(remaining_src["priorityBoundaryCases"]) if isinstance(remaining_src.get("priorityBoundaryCases"), list) else None,
    }
    ben_out = {
        "total": _int_or_none(ben_src.get("total")),
        "scored": _int_or_none(ben_src.get("scored")),
        "unscored": _int_or_none(ben_src.get("unscored")),
        "verifiedChecks": _int_or_none(ben_src.get("verifiedChecks")),
        "possibleChecks": _int_or_none(ben_src.get("possibleChecks")),
        "checklistCompletionPercent": _num_or_none(ben_src.get("checklistCompletionPercent")),
        "verificationScoreBuckets": _score_buckets(ben_src.get("verificationScoreBuckets")),
        "fullyVerified": _int_or_none(ben_src.get("fullyVerified")),
        "source": "Current governorate accepted scorecards",
    }
    metric_out = {
        "totalSelectableLocations": _int_or_none(metric_src.get("totalSelectableLocations")),
        "scoredLocations": _int_or_none(metric_src.get("scoredLocations")),
        "awaitingScoring": _int_or_none(metric_src.get("awaitingScoring")),
        "verifiedChecks": _int_or_none(metric_src.get("verifiedChecks")),
        "possibleChecks": _int_or_none(metric_src.get("possibleChecks")),
        "verificationScoreBuckets": _score_buckets(metric_src.get("verificationScoreBuckets")),
        "fullyVerifiedAgainstChecklist": _int_or_none(metric_src.get("fullyVerifiedAgainstChecklist")),
        "qualification": _as_text(metric_src.get("qualification")),
    }
    install_src = _map(snap.get("installState")) or _map(report_src.get("installState"))
    install_out = {
        "historicalTotal": _int_or_none(install_src.get("historicalInstalledCount", install_src.get("installedCount"))),
        "thisRun": _int_or_none(install_src.get("thisRun")),
        "note": "The accepted report's batch counts are historical. This-run installations are reported separately when available.",
    }
    manual_base_url = _manual_base(manual_src.get("url"))
    links = []
    for name in sorted(pins_src):
        raw = pins_src[name]
        entry = _map(raw)
        target = entry.get("file", entry.get("path")) if entry else (raw if isinstance(raw, str) else None)
        links.append({
            "name": str(name),
            "file": target if isinstance(target, str) else None,
            "sha256": _as_text(entry.get("sha256")) if entry else None,
            "href": _href(target, out_dir),
        })

    issue_counts = {
        "failedJobs": len(jobs_by_status["failed"]),
        "staleJobs": len(jobs_by_status["stale"]),
        "heldJobs": len(jobs_by_status["held"]),
        "ambiguousCases": len(ambiguous),
        "flaggedDiagnosticRows": len(flagged),
    }
    if pending_count is not None:
        issue_counts["pendingManualSubmissions"] = pending_count
    if stale_count is not None:
        issue_counts["staleManualSubmissions"] = stale_count
    open_issues = sum(value for value in issue_counts.values() if value)
    if paused is True:
        status = "paused"
        status_detail = "Investigation paused with %d open item(s) reported." % open_issues
    elif open_issues:
        status = "pending issues"
        status_detail = "%d open item(s) need investigation; the batch is not paused." % open_issues
    elif paused is False:
        status = "clear"
        status_detail = "No open issues found in the provided inputs and the batch is not paused."
    else:
        status = "unknown"
        status_detail = "Paused flag absent and no open issues found; running state is unknown."

    groups = []
    for job_status in JOB_ISSUE_STATUSES:
        items = [{"id": r["id"], "reason": r["reason"], "finishedAt": r["finishedAt"]} for r in jobs_by_status[job_status]]
        if items:
            groups.append({"group": "jobs.%s" % job_status, "count": len(items), "items": items})
    if ambiguous:
        groups.append({"group": "cases.ambiguous", "count": len(ambiguous), "items": [{"id": c["id"], "nameAr": c["nameAr"], "missingChecks": c["missingChecks"], "reviewRequired": c["reviewRequired"]} for c in ambiguous]})
    if flagged:
        groups.append({"group": "catalog.flagged", "count": len(flagged), "items": [{"id": r["id"], "nameAr": r["nameAr"], "flags": r["flags"]} for r in flagged]})
    if pending_items:
        groups.append({"group": "feedback.pending", "count": pending_count, "items": _feedback_items(pending_items)})
    if stale_items:
        groups.append({"group": "feedback.stale", "count": stale_count, "items": _feedback_items(stale_items)})
    issues = {
        "status": status,
        "openCount": open_issues,
        "counts": issue_counts,
        "groups": groups,
        "independenceNote": "Issues are reported per case and per job; one blocked or ambiguous case does not block the remaining cases.",
    }

    report = {
        "schemaVersion": "locality-report/1",
        "generatedAtUtc": now,
        "status": status,
        "statusDetail": status_detail,
        "investigationPaused": paused,
        "activeGovernmentContext": {"nameAr": snap.get("activeGovernorateAr", ACTIVE_CONTEXT_AR)},
        "counts": counts_out,
        "benArous": ben_out,
        "metric": metric_out,
        "qualification": {
            "overallProvisional": None,
            "overallProvisionalNote": "No honest overall percentage is derivable from the current inputs.",
            "eta": None,
            "etaNote": "ETA unavailable without measured throughput.",
            "checklistNote": "100% checklist completeness does not guarantee geographic reliability.",
            "metricQualification": metric_out["qualification"],
        },
        "cases": case_rows,
        "automationPass": snap.get("automationPass", {}),
        "evidencePackets": snap.get("sourcePackets", []),
        "investigations": snap.get("investigations", []),
        "caseCount": len(case_rows),
        "caseNotes": case_notes,
        "jobs": {"total": len(job_rows), "rows": job_rows, "issueCounts": {s: len(jobs_by_status[s]) for s in JOB_ISSUE_STATUSES}},
        "diagnostics": {"summary": _as_text(_map(diagnostics).get("summary")), "rows": diag_rows, "flaggedCount": len(flagged)},
        "feedback": {
            "pendingCount": pending_count,
            "pendingCountMethod": "len(feedback.pending)",
            "staleCount": stale_count,
            "staleCountMethod": "len(feedback.stale)",
            "counts": {str(k): _int_or_none(v) for k, v in _map(fb_src.get("counts")).items()} or None,
            "pending": _feedback_items(pending_items),
            "stale": _feedback_items(stale_items),
        },
        "modelCost": {
            "requests": _int_or_none(cost_src.get("requests")),
            "deepseekRequests": _int_or_none(cost_src.get("deepseekRequests")),
            "promptTokens": _int_or_none(cost_src.get("promptTokens")),
            "completionTokens": _int_or_none(cost_src.get("completionTokens")),
            "codexRequests": _int_or_none(cost_src.get("codexRequests")),
            "codexInputTokens": _int_or_none(cost_src.get("codexInputTokens")),
            "codexOutputTokens": _int_or_none(cost_src.get("codexOutputTokens")),
            "estimatedUsdLow": _num_or_none(cost_src.get("estimatedUsdLow")),
            "estimatedUsdHigh": _num_or_none(cost_src.get("estimatedUsdHigh")),
            "qualification": cost_src.get("qualification"),
        },
        "installedFixes": install_out,
        "issues": issues,
        "links": links,
        "manualTool": {"url": manual_base_url, "queryParam": "q", "note": "Local manual-review tool; one link per case using the Arabic name."},
        "sourcePacketLinks": {"available": bool(snap.get("sourcePackets")), "note": "Saved map pages and polygon overlays; proposals are not geographic certification."},
        "caveats": list(CAVEATS),
    }

    _dump_json(out_dir / "report.json", report)
    _atomic_text(out_dir / "report.html", _render_html(report))
    return report


def _esc(value):
    return html.escape(_text(value), quote=True)


def _value_html(value):
    if value is None:
        return '<span class="unknown">unknown</span>'
    if isinstance(value, bool):
        return "yes" if value else "no"
    return _esc(value)


def _ar_cell(value):
    if isinstance(value, str) and value.strip():
        return '<span class="ar" dir="auto">%s</span>' % _esc(value)
    return _value_html(value)


def _tile(label, value):
    return '<div class="tile"><div class="k">%s</div><div class="v">%s</div></div>' % (_esc(label), _value_html(value))


def _tiles(pairs):
    return '<div class="grid">%s</div>' % "".join(_tile(label, value) for label, value in pairs)


def _section(title, body):
    return "<section><h2>%s</h2>%s</section>" % (_esc(title), body)


def _table(headers, rows):
    head = "".join("<th>%s</th>" % _esc(h) for h in headers)
    body = "".join("<tr>%s</tr>" % "".join("<td>%s</td>" % cell for cell in row) for row in rows)
    return "<table><thead><tr>%s</tr></thead><tbody>%s</tbody></table>" % (head, body)


def _chips(buckets):
    cells = []
    for key, value in _map(buckets).items():
        label = "unscored" if key == "unscored" else key
        cells.append('<span class="chip%s"><b>%s</b>%s</span>' % (" chip-u" if value is None else "", _esc(label), "?" if value is None else _esc(value)))
    return '<div class="chips">%s</div>' % "".join(cells)


def _score_block(title, buckets):
    return '<p class="small"><strong>%s</strong></p>%s<p class="small">? = field absent (unknown), not zero.</p>' % (_esc(title), _chips(buckets))


def _manual_anchor(name_ar, base):
    if not isinstance(name_ar, str) or not name_ar.strip():
        return '<span class="unknown">no Arabic name</span>'
    return '<a href="%s">manual review</a>' % html.escape(_manual_href(name_ar, base), quote=True)


def _items_summary(items):
    if not isinstance(items, list) or not items:
        return '<span class="unknown">none</span>'
    parts = []
    for item in items[:6]:
        if not isinstance(item, dict):
            parts.append("<div>%s</div>" % _value_html(item))
            continue
        name = item.get("nameAr")
        name_cell = _ar_cell(name) if isinstance(name, str) and name.strip() else ""
        detail = item.get("reason")
        if detail is None and isinstance(item.get("flags"), list):
            detail = ", ".join(_text(f.get("code")) for f in item["flags"] if isinstance(f, dict)) or None
        suffix = (" &mdash; " + _esc(detail)) if detail not in (None, UNKNOWN) else ""
        parts.append("<div>%s %s%s</div>" % (_value_html(item.get("id")), name_cell, suffix))
    if len(items) > 6:
        parts.append('<div class="small">+%d more (see report.json)</div>' % (len(items) - 6))
    return "".join(parts)


def _render_html(report):
    counts, metric, ben = _map(report.get("counts")), _map(report.get("metric")), _map(report.get("benArous"))
    issues, feedback = _map(report.get("issues")), _map(report.get("feedback"))
    issues_counts = _map(issues.get("counts"))
    diag, install, cost = _map(report.get("diagnostics")), _map(report.get("installedFixes")), _map(report.get("modelCost"))
    qual = _map(report.get("qualification"))
    manual_base = _map(report.get("manualTool")).get("url")
    rows = report.get("cases") if isinstance(report.get("cases"), list) else []
    diag_rows = diag.get("rows") if isinstance(diag.get("rows"), list) else []
    links = report.get("links") if isinstance(report.get("links"), list) else []
    style = {"paused": "b-paused", "pending issues": "b-pending", "clear": "b-clear"}.get(_text(report.get("status")), "b-unknown")
    out = []
    add = out.append
    add("<!DOCTYPE html>")
    add('<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">')
    add("<title>Locality Automation Report</title><style>%s</style></head><body>" % CSS)
    add('<header><h1>Locality Automation &ndash; Batch Report</h1><div class="meta">Generated %s &middot; Active context: %s &middot; Status: <span class="badge %s">%s</span></div></header><main>'
        % (_esc(report.get("generatedAtUtc")), _ar_cell(report["activeGovernmentContext"]["nameAr"]), style, _esc(report.get("status"))))
    add(_section("Reading this report", "".join('<div class="caveat">%s</div>' % _esc(c) for c in report.get("caveats") or [])))
    auto = _map(report.get("automationPass"))
    add(_section("Automatic pass", _tiles([("Distinct active places processed", auto.get("distinctActiveCasesRecorded")), ("Active governorate total", auto.get("activeTotal")), ("Border dependencies processed", auto.get("borderDependenciesRecorded"))])))
    packet_rows = [['<a href="%s">%s</a>' % (html.escape(p['href'], quote=True), _esc(p['name']))] for p in report.get('evidencePackets', [])]
    if packet_rows:
        add(_section("Saved maps and polygon overlays", _table(["Evidence packet"], packet_rows)))
    investigation_rows = [[_value_html(i.get('id')), _value_html(i.get('issue')), _value_html(i.get('source', 'Automatic diagnostic'))] for i in report.get('investigations', [])]
    if investigation_rows:
        add(_section("Investigate later", _table(["Place", "Issue", "Source"], investigation_rows)))
    add(_section("State and batch counts", _tiles([
        ("Investigation paused", report.get("investigationPaused")), ("Report status", report.get("status")),
        ("Selectable locations (total)", counts.get("currentSelectableLocations")),
        ("First-pass evidence (reviewed)", counts.get("currentLocationsWithFirstPassEvidence")),
        ("Installed in prior accepted batch", counts.get("appCorrectionsInstalledInThisBatch")),
        ("Diagnostic candidates", counts.get("diagnosticCandidates")),
        ("Priority boundary cases (remaining)", counts.get("remainingPriorityBoundaryCases"))])
        + '<p class="small">%s</p><p class="small">The remaining-case detail object is omitted from this compact report on purpose.</p>' % _esc(report.get("statusDetail"))))
    add(_section("Accepted metric (not altered)", _tiles([
        ("Total selectable locations", metric.get("totalSelectableLocations")), ("Scored locations", metric.get("scoredLocations")),
        ("Awaiting scoring", metric.get("awaitingScoring")), ("Verified checks", metric.get("verifiedChecks")),
        ("Possible checks", metric.get("possibleChecks")), ("Fully verified against checklist", metric.get("fullyVerifiedAgainstChecklist"))])
        + _score_block("Verification score buckets (100 \u2192 0, plus unscored)", metric.get("verificationScoreBuckets"))
        + '<p class="small">Qualification (verbatim from snapshot): %s</p>' % _value_html(qual.get("metricQualification"))
        + '<div class="note">Overall provisional: %s. ETA: %s. %s %s</div>' % (_value_html(qual.get("overallProvisional")), _value_html(qual.get("eta")), _esc(qual.get("overallProvisionalNote")), _esc(qual.get("etaNote")))))
    add(_section("Current governorate", _tiles([
        ("Total", ben.get("total")), ("Scored", ben.get("scored")), ("Unscored", ben.get("unscored")),
        ("Fully verified", ben.get("fullyVerified")), ("Verified checks", ben.get("verifiedChecks")),
        ("Possible checks", ben.get("possibleChecks")), ("Checklist completion percent", ben.get("checklistCompletionPercent"))])
        + _score_block("Verification score buckets (100 \u2192 0, plus unscored)", ben.get("verificationScoreBuckets"))
        + '<div class="note">Checklist completeness, even at 100%, is not a guarantee of geographic reliability.</div>'))
    add(_section("Automation done vs verified geography", _table(["Measure", "Value", "Meaning"], [
        ["First-pass evidence locations", _value_html(counts.get("currentLocationsWithFirstPassEvidence")), "Automation produced first-pass evidence."],
        ["Fully verified against checklist", _value_html(metric.get("fullyVerifiedAgainstChecklist")), "Verified geography (accepted metric)."],
        ["Current governorate scored", _value_html(ben.get("scored")), "Accepted evidence checklist coverage."],
        ["All checklist checks accepted", _value_html(ben.get("fullyVerified")), "Checklist completeness does not guarantee geographic accuracy."]])
        + '<div class="note">These measures stay separate and are never merged into a single percentage.</div>'))
    add(_section("Installed fixes", _tiles([("Historical total", install.get("historicalTotal")), ("Installed in this batch", install.get("thisRun"))])
        + '<div class="note">%s</div>' % _esc(install.get("note"))))
    case_body = _table(["ID", "Name (Arabic)", "Parent (Arabic)", "Role", "Source status", "Accepted", "Missing", "Review", "Manual"],
        [[_value_html(r.get("id")), _ar_cell(r.get("nameAr")), _ar_cell(r.get("parentAr")), _value_html(r.get("role")), _value_html(r.get("sourceStatus")),
          _value_html(r.get("acceptedChecks")), _value_html(r.get("missingChecks")), _value_html(r.get("reviewRequired")), _manual_anchor(r.get("nameAr"), manual_base)] for r in rows]) if rows else '<p class="small">No cases provided.</p>'
    case_body += "".join('<div class="caveat">%s</div>' % _esc(n) for n in (report.get("caseNotes") if isinstance(report.get("caseNotes"), list) else []))
    add(_section("Cases", case_body))
    flag_rows = [[_value_html(r.get("id")), _ar_cell(r.get("nameAr")),
        " &middot; ".join('<code>%s</code> %s' % (_esc(f.get("code")), _esc(f.get("detail"))) for f in (r.get("flags") if isinstance(r.get("flags"), list) else [])) or '<span class="unknown">none</span>'] for r in diag_rows]
    issue_rows = [[_value_html(g.get("group")), _value_html(g.get("count")), _items_summary(g.get("items"))] for g in (issues.get("groups") if isinstance(issues.get("groups"), list) else [])]
    add(_section("Diagnostics and needs investigation", ("<p>%s</p>" % _value_html(diag.get("summary")) if diag.get("summary") is not None else "")
        + (_table(["ID", "Name (Arabic)", "Flags"], flag_rows) if flag_rows else '<p class="small">No diagnostic rows provided.</p>')
        + _tiles([("Open items", issues.get("openCount")), ("Failed jobs", issues_counts.get("failedJobs")), ("Stale jobs", issues_counts.get("staleJobs")),
                  ("Held jobs", issues_counts.get("heldJobs")), ("Ambiguous cases", issues_counts.get("ambiguousCases")),
                  ("Flagged catalog rows", issues_counts.get("flaggedDiagnosticRows")), ("Manual pending", feedback.get("pendingCount")), ("Manual stale", feedback.get("staleCount"))])
        + (_table(["Issue group", "Count", "Examples"], issue_rows) if issue_rows else '<p class="small">No open issues found in the provided inputs.</p>')
        + '<p class="small">Manual submissions pending is computed as <code>len(feedback.pending)</code>; stale as <code>len(feedback.stale)</code>.</p>'
        + '<div class="note">%s</div>' % _esc(issues.get("independenceNote"))))
    link_rows = []
    for entry in links:
        href = entry.get("href") if isinstance(entry, dict) else None
        target = _value_html(entry.get("file")) if isinstance(entry, dict) else _value_html(None)
        link_rows.append([_value_html(entry.get("name")) if isinstance(entry, dict) else _value_html(None),
                          '<a href="%s">%s</a>' % (html.escape(href, quote=True), target) if href else target,
                          _value_html(entry.get("sha256")) if isinstance(entry, dict) else _value_html(None)])
    add(_section("Outputs and links", (_table(["Pin", "File", "SHA-256"], link_rows) if link_rows else '<p class="small">No pins provided.</p>')
        + '<div class="note">Saved evidence packets are linked separately above when available.</div>'))
    add(_section("Model usage (as calculated by the caller)", _tiles([("All requests", cost.get("requests")),
        ("DeepSeek requests", cost.get("deepseekRequests")), ("DeepSeek prompt tokens", cost.get("promptTokens")),
        ("DeepSeek completion tokens", cost.get("completionTokens")), ("DeepSeek estimated USD low", cost.get("estimatedUsdLow")),
        ("DeepSeek estimated USD high", cost.get("estimatedUsdHigh")), ("Codex Luna requests", cost.get("codexRequests")),
        ("Codex input tokens", cost.get("codexInputTokens")), ("Codex output tokens", cost.get("codexOutputTokens"))])
        + '<div class="note">%s</div>' % _esc(cost.get("qualification"))))
    add('<footer>Generated %s from the provided inputs only &mdash; no JavaScript, no network fetches, no re-computation of the accepted metric.</footer>' % _esc(report.get("generatedAtUtc")))
    add("</main></body></html>")
    return "".join(out)


def _atomic_text(path, text):
    """Write text via a temp file in the target directory plus os.replace."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    handle_fd, tmp_name = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(handle_fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp_name, str(path))
    except BaseException:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


def _dump_json(path, payload):
    """Atomic JSON write via the package helper when available."""
    if _write_json is not None:
        _write_json(path, payload, replace=True)
        return
    _atomic_text(path, json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
