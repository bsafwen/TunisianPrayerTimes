#!/usr/bin/env python3
from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import email.utils
import html
import json
import math
import os
import re
import sys
import textwrap
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


@dataclasses.dataclass(frozen=True)
class Source:
    name: str
    domain: str
    tier: str


@dataclasses.dataclass(frozen=True)
class TargetEvent:
    key: str
    override_field: str
    label: str
    hijri_month: int
    hijri_day: int
    queries: tuple[str, ...]


@dataclasses.dataclass(frozen=True)
class ArticleCandidate:
    id: str
    source_name: str
    source_domain: str
    source_tier: str
    provider: str
    title: str
    url: str
    published: str | None
    snippet: str

    def prompt_record(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "sourceName": self.source_name,
            "sourceDomain": self.source_domain,
            "sourceTier": self.source_tier,
            "provider": self.provider,
            "published": self.published,
            "title": self.title,
            "url": self.url,
            "snippet": self.snippet,
        }


KNOWN_SOURCES: tuple[Source, ...] = (
    Source("Dar al-Ifta Tunisia", "mufti.tn", "official"),
    Source("Ministry of Religious Affairs", "affaires-religieuses.tn", "official"),
    Source("Institut National de la Meteorologie", "meteo.tn", "official"),
    Source("TAP", "tap.info.tn", "state_news"),
    Source("La Presse de Tunisie", "lapresse.tn", "trusted_news"),
    Source("Mosaique FM", "mosaiquefm.net", "trusted_news"),
    Source("Jawhara FM", "jawharafm.net", "trusted_news"),
    Source("Shems FM", "shemsfm.net", "trusted_news"),
    Source("Express FM", "radioexpressfm.com", "trusted_news"),
    Source("Tunisie Numerique", "tunisienumerique.com", "trusted_news"),
    Source("Webdo", "webdo.tn", "trusted_news"),
    Source("Business News", "businessnews.com.tn", "trusted_news"),
)

EVENTS: dict[str, TargetEvent] = {
    "ramadan_start": TargetEvent(
        key="ramadan_start",
        override_field="ramadanStart",
        label="Ramadan start",
        hijri_month=9,
        hijri_day=1,
        queries=(
            "غرة رمضان تونس",
            "ثبوت رؤية هلال رمضان تونس",
            "مفتي الجمهورية رمضان تونس",
            "début Ramadan Tunisie mufti",
            "date Ramadan Tunisie mufti",
        ),
    ),
    "eid_fitr": TargetEvent(
        key="eid_fitr",
        override_field="eidFitrDate",
        label="Aid el-Fitr",
        hijri_month=10,
        hijri_day=1,
        queries=(
            "عيد الفطر تونس دار الإفتاء",
            "ثبوت رؤية هلال شوال تونس",
            "مفتي الجمهورية عيد الفطر تونس",
            "Aïd El Fitr Tunisie mufti",
            "date Aïd El Fitr Tunisie mufti",
        ),
    ),
    "eid_adha": TargetEvent(
        key="eid_adha",
        override_field="eidAdhaDate",
        label="Aid el-Adha",
        hijri_month=12,
        hijri_day=10,
        queries=(
            "عيد الأضحى تونس دار الإفتاء",
            "عيد الاضحى تونس مفتي الجمهورية",
            "غرة ذي الحجة تونس",
            "Aïd El Idha Tunisie",
            "date Aïd El Idha Tunisie mufti",
        ),
    ),
}

TAG_RE = re.compile(r"<[^>]+>")
SCRIPT_STYLE_RE = re.compile(r"<(script|style)[^>]*>.*?</\1>", re.IGNORECASE | re.DOTALL)
HREF_RE = re.compile(r"href=[\"']([^\"']+)[\"']", re.IGNORECASE)
SPACE_RE = re.compile(r"\s+")
JSON_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
ISLAMIC_EPOCH = 1_948_439
METEO_LIST_URL = "https://www.meteo.tn/ar/liste-visibilite-croissant-lunaire"
TUNISIAN_MONTHS = {
    "جانفي": 1,
    "فيفري": 2,
    "مارس": 3,
    "أفريل": 4,
    "افريل": 4,
    "ماي": 5,
    "جوان": 6,
    "جويلية": 7,
    "أوت": 8,
    "اوت": 8,
    "سبتمبر": 9,
    "أكتوبر": 10,
    "اكتوبر": 10,
    "نوفمبر": 11,
    "ديسمبر": 12,
}
TUNISIAN_DATE_RE = re.compile(
    r"(\d{1,2})\s*(جانفي|فيفري|مارس|أفريل|افريل|ماي|جوان|جويلية|أوت|اوت|سبتمبر|أكتوبر|اكتوبر|نوفمبر|ديسمبر)\s*(\d{4})",
)


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve()
    today = parse_iso_date(args.today) if args.today else dt.date.today()
    targets = target_events_for(today, args.event)
    report_lines: list[str] = [
        "# Tunisian Ramadan/Aid date detection",
        "",
        f"Run date: `{today.isoformat()}`",
        f"Mode: `{args.event}`",
        "",
    ]

    if not targets:
        report_lines.append("No relevant Ramadan/Aid polling window is active today.")
        write_reports(args, report_lines)
        print("No active Ramadan/Aid polling window; nothing to update.")
        return 0

    deepseek_key = load_deepseek_key(repo_root, args.env_file)
    changed_files: list[Path] = []
    for event, hijri_year in targets:
        report_lines.extend([f"## {event.label} {hijri_year}", ""])
        if args.skip_network:
            report_lines.append("Skipped network and LLM work because `--skip-network` was set.")
            report_lines.append("")
            continue

        candidates = collect_candidates(event, hijri_year, today, args.max_candidates, args.use_gdelt)
        report_lines.append(f"Found `{len(candidates)}` candidate articles/snippets.")
        if not candidates:
            report_lines.append("No candidate source material was found, so no update was made.")
            report_lines.append("")
            continue

        decision = deterministic_decision_from_candidates(event, hijri_year, candidates)
        if decision is None:
            if not deepseek_key:
                raise SystemExit("DEEP secret is required when search fallback needs LLM extraction.")
            decision = ask_deepseek(
                api_key=deepseek_key,
                event=event,
                hijri_year=hijri_year,
                today=today,
                candidates=candidates,
            )
        validation = validate_decision(decision, event, hijri_year, candidates)
        append_validation_report(report_lines, event, validation)
        if validation["accepted"]:
            changed = update_override_file(
                repo_root=repo_root,
                event=event,
                hijri_year=hijri_year,
                gregorian_date=validation["selected_date"],
                dry_run=args.dry_run,
            )
            if changed:
                changed_files.append(repo_root / "docs" / f"ramadan-override-{hijri_year}.json")
                if args.dry_run:
                    report_lines.append("Override JSON would be updated; dry run did not write files.")
                else:
                    report_lines.append("Override JSON was updated.")
            else:
                report_lines.append("Override JSON already contains this date; no file change was needed.")
        else:
            report_lines.append("No JSON update was made.")
        report_lines.append("")

    if changed_files:
        report_lines.extend(["## Files changed", ""])
        for path in changed_files:
            report_lines.append(f"- `{path.relative_to(repo_root)}`")
    else:
        report_lines.extend(["## Files changed", "", "No files changed."])

    write_reports(args, report_lines)
    print("\n".join(report_lines))
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Detect official Tunisian Ramadan/Aid dates and update override JSON files.",
    )
    parser.add_argument("--repo-root", default=".")
    parser.add_argument("--today", help="Override today's date, YYYY-MM-DD, for manual runs.")
    parser.add_argument(
        "--event",
        choices=("auto", "all", "ramadan_start", "eid_fitr", "eid_adha"),
        default="auto",
        help="Which event to check. 'auto' checks only active polling windows.",
    )
    parser.add_argument("--max-candidates", type=int, default=36)
    parser.add_argument("--dry-run", action="store_true", help="Do not write override JSON files.")
    parser.add_argument("--skip-network", action="store_true", help="Skip source fetches and the DeepSeek call.")
    parser.add_argument("--use-gdelt", action="store_true", help="Use GDELT as an additional historical search fallback.")
    parser.add_argument(
        "--env-file",
        default=".env.local",
        help="Local-only env file for DEEP when the environment variable is not set.",
    )
    parser.add_argument("--pr-body", help="Markdown file to write for the generated pull request body.")
    return parser.parse_args()


def load_deepseek_key(repo_root: Path, env_file: str) -> str:
    from_environment = os.environ.get("DEEP", "").strip()
    if from_environment:
        return from_environment
    path = Path(env_file)
    if not path.is_absolute():
        path = repo_root / path
    if not path.exists():
        return ""
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() == "DEEP":
            return unquote_env_value(value.strip())
    return ""


def unquote_env_value(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
        return value[1:-1]
    return value


def parse_iso_date(value: str) -> dt.date:
    return dt.date.fromisoformat(value)


def target_events_for(today: dt.date, event_arg: str) -> list[tuple[TargetEvent, int]]:
    hijri_year, hijri_month, hijri_day = gregorian_to_hijri(today)
    if event_arg == "all":
        return [(event, hijri_year) for event in EVENTS.values()]
    if event_arg != "auto":
        return [(EVENTS[event_arg], hijri_year)]

    targets: list[tuple[TargetEvent, int]] = []
    if (hijri_month == 8 and hijri_day >= 24) or (hijri_month == 9 and hijri_day <= 3):
        targets.append((EVENTS["ramadan_start"], hijri_year))
    if (hijri_month == 9 and hijri_day >= 25) or (hijri_month == 10 and hijri_day <= 3):
        targets.append((EVENTS["eid_fitr"], hijri_year))
    if (hijri_month == 11 and hijri_day >= 25) or (hijri_month == 12 and hijri_day <= 13):
        targets.append((EVENTS["eid_adha"], hijri_year))
    return targets


def collect_candidates(
    event: TargetEvent,
    hijri_year: int,
    today: dt.date,
    max_candidates: int,
    use_gdelt: bool,
) -> list[ArticleCandidate]:
    candidates: list[ArticleCandidate] = []
    seen: set[str] = set()
    candidate_index = 1

    for item in collect_meteo_direct_candidates(event, hijri_year):
        key = article_key(item)
        if key in seen:
            continue
        seen.add(key)
        candidate = dataclasses.replace(item, id=f"a{candidate_index}")
        candidate_index += 1
        candidates.append(candidate)
        if len(candidates) >= max_candidates:
            return candidates

    for provider, url in search_feed_urls(event, hijri_year, today):
        if len(candidates) >= max_candidates:
            return candidates
        try:
            feed_xml = fetch_text(url)
        except urllib.error.URLError as error:
            print(f"Search fetch failed via {provider}: {error}", file=sys.stderr)
            continue
        for item in parse_feed(feed_xml, provider):
            if not candidate_matches_tunisian_event(event, item):
                continue
            key = article_key(item)
            if key in seen:
                continue
            seen.add(key)
            candidate = dataclasses.replace(item, id=f"a{candidate_index}")
            candidate_index += 1
            candidates.append(candidate)
            if len(candidates) >= max_candidates:
                return candidates

    if not use_gdelt:
        return candidates
    for url in gdelt_search_urls(event, hijri_year):
        if len(candidates) >= max_candidates:
            return candidates
        try:
            gdelt_json = fetch_text(url)
        except urllib.error.URLError as error:
            print(f"GDELT fetch failed: {error}", file=sys.stderr)
            continue
        for item in parse_gdelt(gdelt_json):
            if not candidate_matches_tunisian_event(event, item):
                continue
            key = article_key(item)
            if key in seen:
                continue
            seen.add(key)
            candidate = dataclasses.replace(item, id=f"a{candidate_index}")
            candidate_index += 1
            candidates.append(candidate)
            if len(candidates) >= max_candidates:
                return candidates
    return candidates


def collect_meteo_direct_candidates(event: TargetEvent, hijri_year: int) -> list[ArticleCandidate]:
    source = classify_source("https://www.meteo.tn", "Institut National de la Meteorologie")
    candidates: list[ArticleCandidate] = []
    for url in meteo_direct_urls(event, hijri_year):
        try:
            page_html = fetch_text(url)
        except urllib.error.URLError as error:
            print(f"Source fetch failed for meteo.tn direct page: {error}", file=sys.stderr)
            continue
        plain_text = html_to_text(page_html)
        if not meteo_text_matches_event(event, hijri_year, plain_text):
            continue
        candidates.append(
            ArticleCandidate(
                id="",
                source_name=source.name,
                source_domain=source.domain,
                source_tier=source.tier,
                provider="meteo_direct",
                title=extract_html_title(page_html)[:300],
                url=url,
                published=parse_meteo_published_date(plain_text),
                snippet=plain_text[:6_000],
            ),
        )
        derived_date = derive_meteo_event_date(event, hijri_year, plain_text)
        if derived_date is not None:
            candidates.append(
                ArticleCandidate(
                    id="",
                    source_name=source.name,
                    source_domain=source.domain,
                    source_tier=source.tier,
                    provider="meteo_direct_derived",
                    title=f"Official meteo.tn derived {event.label} date",
                    url=url,
                    published=parse_meteo_published_date(plain_text),
                    snippet=build_meteo_derived_snippet(event, derived_date),
                ),
            )
    return candidates


def meteo_direct_urls(event: TargetEvent, hijri_year: int) -> list[str]:
    urls: list[str] = []
    known_paths = {
        "ramadan_start": (
            f"/ar/ramadan-moon-crescent-{hijri_year}",
            "/ar/ramadan-moon-crescent",
        ),
        "eid_fitr": (
            f"/ar/shaouel-moon-crescent-{hijri_year}",
            "/ar/shaouel-moon-crescent",
        ),
        "eid_adha": (
            f"/ar/dhou-el-hijja-{hijri_year}",
            "/ar/dhou-el-hijja",
        ),
    }
    for path in known_paths[event.key]:
        urls.append(urllib.parse.urljoin("https://www.meteo.tn", path))

    for page_index in range(0, 3):
        list_url = f"{METEO_LIST_URL}?page={page_index}"
        try:
            list_html = fetch_text(list_url)
        except urllib.error.URLError as error:
            print(f"Source fetch failed for meteo.tn list page: {error}", file=sys.stderr)
            continue
        for match in HREF_RE.finditer(list_html):
            href = html.unescape(match.group(1))
            absolute_url = urllib.parse.urljoin("https://www.meteo.tn", href)
            context = html_to_text(list_html[max(0, match.start() - 800) : match.end() + 1_200])
            if "meteo.tn" not in urllib.parse.urlparse(absolute_url).netloc:
                continue
            if str(hijri_year) in context and meteo_text_matches_event(event, hijri_year, context):
                urls.append(absolute_url)

    deduped: list[str] = []
    seen: set[str] = set()
    for url in urls:
        normalized = normalize_url(url)
        if normalized in seen:
            continue
        seen.add(normalized)
        deduped.append(url)
    return deduped


def meteo_text_matches_event(event: TargetEvent, hijri_year: int, text: str) -> bool:
    if str(hijri_year) not in text:
        return False
    lowered = text.lower()
    if event.key == "ramadan_start":
        return "رمضان" in text or "ramadan" in lowered
    if event.key == "eid_fitr":
        return "شوال" in text or "shaouel" in lowered or "shawwal" in lowered
    return "ذو الحجة" in text or "ذي الحجة" in text or "dhou-el-hijja" in lowered


def derive_meteo_event_date(event: TargetEvent, hijri_year: int, text: str) -> dt.date | None:
    if event.key == "ramadan_start":
        return derive_meteo_ramadan_start_date(event, text)
    if event.key == "eid_adha":
        return derive_meteo_eid_adha_date(event, hijri_year, text)
    return None


def derive_meteo_ramadan_start_date(event: TargetEvent, text: str) -> dt.date | None:
    normalized = clean_text(text)
    if event.key != "ramadan_start" or "meteo" not in normalized.lower() and "المعهد الوطني للرصد الجوي" not in normalized:
        return None
    if "رؤية هلال شهر رمضان" not in normalized and "هلال رمضان" not in normalized:
        return None
    for match in re.finditer(r"(?:تصبح\s+)?الرؤية\s+ممكنة\s+يوم.{0,220}", normalized):
        possible_dates = parse_tunisian_dates(match.group(0))
        if possible_dates:
            return possible_dates[0] + dt.timedelta(days=1)
    return None


def derive_meteo_eid_adha_date(event: TargetEvent, hijri_year: int, text: str) -> dt.date | None:
    normalized = clean_text(text)
    if event.key != "eid_adha" or "meteo" not in normalized.lower() and "المعهد الوطني للرصد الجوي" not in normalized:
        return None
    if "ذو الحجة" not in normalized and "ذي الحجة" not in normalized:
        return None

    expected_month_start = hijri_to_gregorian(hijri_year, 12, 1)
    visibility_dates: list[dt.date] = []
    for window in meteo_dhul_hijja_visibility_windows(normalized):
        for parsed_date in parse_tunisian_dates(window):
            if abs((parsed_date - expected_month_start).days) <= 4:
                visibility_dates.append(parsed_date)
    if not visibility_dates:
        return None

    visibility_date = max(visibility_dates)
    first_dhul_hijja = visibility_date + dt.timedelta(days=1)
    return first_dhul_hijja + dt.timedelta(days=9)


def meteo_dhul_hijja_visibility_windows(text: str) -> list[str]:
    terms = (
        "يمكن رؤية هلال شهر ذو الحجة",
        "خريطة إمكانية رؤية هلال شهر ذو الحجة",
        "إمكانية رؤية هلال شهر ذو الحجة",
        "رؤية هلال بداية شهر ذو الحجة",
        "بعد غروب شمس يوم",
        "صورة 3",
    )
    windows: list[str] = []
    for term in terms:
        start = 0
        while True:
            index = text.find(term, start)
            if index < 0:
                break
            windows.append(text[max(0, index - 240) : index + 520])
            start = index + len(term)
    return windows


def build_meteo_derived_snippet(event: TargetEvent, selected_date: dt.date) -> str:
    if event.key == "eid_adha":
        visibility_date = selected_date - dt.timedelta(days=10)
        first_dhul_hijja = selected_date - dt.timedelta(days=9)
        return (
            "Official INM/meteo.tn Dhul Hijja crescent visibility report derived date. "
            f"The report indicates Dhul Hijja crescent visibility after sunset on {visibility_date.isoformat()}. "
            f"Because 1 Dhul Hijja is the Gregorian day after evening visibility, 1 Dhul Hijja is "
            f"{first_dhul_hijja.isoformat()} and Aid el-Adha is {selected_date.isoformat()}. "
            f"Selected date: {selected_date.isoformat()}."
        )
    visibility_date = selected_date - dt.timedelta(days=1)
    return (
        "Official INM/meteo.tn crescent visibility report derived date. "
        f"The report states that Ramadan crescent visibility becomes possible after sunset on "
        f"{visibility_date.isoformat()}. "
        f"Because the first fasting day is the Gregorian day after the evening visibility, "
        f"Ramadan start is {selected_date.isoformat()}. "
        f"Selected date: {selected_date.isoformat()}. "
        "Do not use the imsakiyya publication date as the Ramadan start unless the page explicitly says it is 1 Ramadan."
    )


def parse_tunisian_dates(text: str) -> list[dt.date]:
    dates: list[dt.date] = []
    for match in TUNISIAN_DATE_RE.finditer(text):
        day = int(match.group(1))
        month = TUNISIAN_MONTHS[match.group(2)]
        year = int(match.group(3))
        if not 1900 <= year <= 2200:
            continue
        try:
            dates.append(dt.date(year, month, day))
        except ValueError:
            continue
    return dates


def deterministic_decision_from_candidates(
    event: TargetEvent,
    hijri_year: int,
    candidates: list[ArticleCandidate],
) -> dict[str, Any] | None:
    for candidate in candidates:
        if candidate.provider != "meteo_direct_derived" or candidate.source_domain != "meteo.tn":
            continue
        dates = re.findall(r"\b\d{4}-\d{2}-\d{2}\b", candidate.snippet)
        if not dates:
            continue
        selected_date = extract_selected_date(candidate.snippet) or dates[-1]
        if event.key == "eid_adha":
            reason = (
                "Official INM/meteo.tn Dhul Hijja crescent visibility report clearly implies "
                f"Aid el-Adha on {selected_date}."
            )
            quote = (
                f"Official INM/meteo.tn report: Dhul Hijja visibility is source evidence; "
                f"Aid el-Adha is {selected_date}."
            )
        else:
            reason = (
                "Official INM/meteo.tn crescent visibility report states that the Ramadan crescent "
                f"becomes visible after sunset on {dates[0]}, so the first fasting day is {selected_date}."
            )
            quote = (
                f"Official INM/meteo.tn report: crescent visibility becomes possible after sunset on "
                f"{dates[0]}; first fasting day is {selected_date}."
            )
        return {
            "event": event.key,
            "hijriYear": hijri_year,
            "selectedDate": selected_date,
            "confidence": "high",
            "reason": reason,
            "claims": [
                {
                    "sourceId": candidate.id,
                    "gregorianDate": selected_date,
                    "certainty": "official_astronomical_report",
                    "isOfficialAnnouncement": True,
                    "authorityMentioned": "INM/meteo.tn",
                    "quote": quote,
                },
            ],
            "conflicts": [],
        }
    return None


def extract_selected_date(text: str) -> str | None:
    match = re.search(r"Selected date:\s*(\d{4}-\d{2}-\d{2})", text)
    return match.group(1) if match else None


def search_feed_urls(event: TargetEvent, hijri_year: int, today: dt.date) -> list[tuple[str, str]]:
    urls: list[tuple[str, str]] = []
    year_hint = f"{today.year} {hijri_year}"
    recency_hint = "when:45d" if abs((dt.date.today() - today).days) <= 45 else year_hint
    for query in event.queries:
        search_query = f"{query} {recency_hint} تونس Tunisie"
        urls.append(
            (
                "google_news",
                "https://news.google.com/rss/search?"
                + urllib.parse.urlencode(
                    {
                        "q": search_query,
                        "hl": "ar",
                        "gl": "TN",
                        "ceid": "TN:ar",
                    },
                ),
            ),
        )
        urls.append(
            (
                "bing_news",
                "https://www.bing.com/news/search?"
                + urllib.parse.urlencode(
                    {
                        "q": search_query,
                        "format": "RSS",
                        "cc": "tn",
                        "setlang": "ar",
                    },
                ),
            ),
        )
    return urls


def gdelt_search_urls(event: TargetEvent, hijri_year: int) -> list[str]:
    expected = hijri_to_gregorian(hijri_year, event.hijri_month, event.hijri_day)
    start = expected - dt.timedelta(days=14)
    end = expected + dt.timedelta(days=4)
    urls: list[str] = []
    for query in event.queries[:3]:
        gdelt_query = f'"{query}" Tunisie'
        urls.append(
            "https://api.gdeltproject.org/api/v2/doc/doc?"
            + urllib.parse.urlencode(
                {
                    "query": gdelt_query,
                    "mode": "artlist",
                    "format": "json",
                    "maxrecords": "5",
                    "sort": "datedesc",
                    "startdatetime": start.strftime("%Y%m%d000000"),
                    "enddatetime": end.strftime("%Y%m%d235959"),
                },
            ),
        )
    return urls


def fetch_text(url: str, timeout_seconds: int = 15) -> str:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "TunisianPrayerTimesDateChecker/1.0 (+https://github.com/bsafwen/TunisianPrayerTimes)",
            "Accept": "application/rss+xml, application/xml, text/xml, */*",
        },
    )
    with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
        raw = response.read(2_000_000)
        charset = response.headers.get_content_charset() or "utf-8"
        return raw.decode(charset, errors="replace")


def parse_feed(feed_xml: str, provider: str) -> list[ArticleCandidate]:
    try:
        root = ET.fromstring(feed_xml)
    except ET.ParseError:
        return []

    items = list(root.findall(".//item")) + list(root.findall(".//{*}entry"))
    articles: list[ArticleCandidate] = []
    for item in items:
        title = clean_text(child_text(item, "title"))
        link = child_text(item, "link") or child_attr(item, "link", "href")
        source_url = child_attr(item, "source", "url")
        source_name = clean_text(child_text(item, "source"))
        source = classify_source(source_url or link, source_name)
        description = child_text(item, "description") or child_text(item, "summary") or child_text(item, "content")
        published = parse_feed_date(
            child_text(item, "pubDate") or child_text(item, "published") or child_text(item, "updated"),
        )
        snippet = clean_text(description)
        if not title and not snippet:
            continue
        articles.append(
            ArticleCandidate(
                id="",
                source_name=source.name,
                source_domain=source.domain,
                source_tier=source.tier,
                provider=provider,
                title=title[:300],
                url=(link or source_url).strip(),
                published=published,
                snippet=snippet[:1_000],
            ),
        )
    return articles


def parse_gdelt(gdelt_json: str) -> list[ArticleCandidate]:
    try:
        payload = json.loads(gdelt_json)
    except json.JSONDecodeError:
        return []
    articles: list[ArticleCandidate] = []
    for item in payload.get("articles", []):
        if not isinstance(item, dict):
            continue
        url = str(item.get("url", ""))
        source = classify_source(url, "")
        title = clean_text(str(item.get("title", "")))
        snippet_parts = [title]
        social_image = str(item.get("socialimage", ""))
        if social_image:
            snippet_parts.append(f"Social image: {social_image}")
        articles.append(
            ArticleCandidate(
                id="",
                source_name=source.name,
                source_domain=source.domain,
                source_tier=source.tier,
                provider="gdelt",
                title=title[:300],
                url=url,
                published=parse_gdelt_date(str(item.get("seendate", ""))),
                snippet=clean_text(". ".join(snippet_parts))[:1_000],
            ),
        )
    return articles


def classify_source(url: str, source_name: str) -> Source:
    domain = domain_from_url(url)
    for known_source in KNOWN_SOURCES:
        if domain == known_source.domain or domain.endswith(f".{known_source.domain}"):
            return known_source
    if domain:
        return Source(clean_text(source_name) or domain, domain, "search_result")
    return Source(clean_text(source_name) or "Search result", "unknown", "search_result")


def domain_from_url(url: str) -> str:
    if not url:
        return ""
    parsed = urllib.parse.urlparse(url if "://" in url else f"https://{url}")
    domain = parsed.netloc.lower()
    if domain.startswith("www."):
        domain = domain[4:]
    return domain


def candidate_matches_tunisian_event(event: TargetEvent, article: ArticleCandidate) -> bool:
    content = f"{article.source_name} {article.source_domain} {article.title} {article.snippet}".lower()
    tunisian_terms = (
        "tunisie",
        "tunisia",
        "tunisian",
        "tunisienne",
        "tunisien",
        "tunis",
        "تونس",
        "التونسية",
        "التونسي",
        "مفتي الجمهورية",
        "دار الإفتاء",
    )
    if not any(term in content for term in tunisian_terms):
        return False
    if event.key == "ramadan_start":
        return "ramadan" in content or "رمضان" in content
    if event.key == "eid_fitr":
        return any(term in content for term in ("fitr", "الفطر", "شوال", "shawwal", "shaouel"))
    return any(term in content for term in ("adha", "idha", "الأضحى", "الاضحى", "الحجة", "hijja"))


def parse_gdelt_date(value: str) -> str | None:
    if not value:
        return None
    for pattern in ("%Y%m%dT%H%M%SZ", "%Y%m%dT%H%M%S"):
        try:
            return dt.datetime.strptime(value, pattern).date().isoformat()
        except ValueError:
            continue
    return None


def child_text(element: ET.Element, local_name: str) -> str:
    for child in list(element):
        if child.tag.rsplit("}", 1)[-1] == local_name:
            return child.text or ""
    return ""


def child_attr(element: ET.Element, local_name: str, attr_name: str) -> str:
    for child in list(element):
        if child.tag.rsplit("}", 1)[-1] == local_name:
            return child.attrib.get(attr_name, "")
    return ""


def parse_feed_date(value: str) -> str | None:
    if not value:
        return None
    try:
        parsed = email.utils.parsedate_to_datetime(value)
        return parsed.date().isoformat()
    except (TypeError, ValueError):
        return None


def article_key(article: ArticleCandidate) -> str:
    normalized_url = normalize_url(article.url)
    if normalized_url:
        if article.provider.endswith("_derived"):
            return f"{normalized_url}:{article.provider}:{article.title.lower()}"
        return normalized_url
    return f"{article.source_domain}:{article.title.lower()}"


def normalize_url(url: str) -> str:
    if not url:
        return ""
    parsed = urllib.parse.urlparse(url)
    if not parsed.netloc:
        return ""
    query = urllib.parse.parse_qsl(parsed.query, keep_blank_values=False)
    filtered_query = urllib.parse.urlencode(
        [(key, value) for key, value in query if not key.lower().startswith("utm_")],
    )
    return urllib.parse.urlunparse(
        (parsed.scheme, parsed.netloc.lower(), parsed.path.rstrip("/"), "", filtered_query, ""),
    )


def clean_text(value: str) -> str:
    unescaped = html.unescape(value or "")
    without_tags = TAG_RE.sub(" ", unescaped)
    return SPACE_RE.sub(" ", without_tags).strip()


def html_to_text(value: str) -> str:
    without_scripts = SCRIPT_STYLE_RE.sub(" ", value or "")
    return clean_text(without_scripts)


def extract_html_title(value: str) -> str:
    for tag_name in ("h1", "h2", "title"):
        match = re.search(rf"<{tag_name}[^>]*>(.*?)</{tag_name}>", value or "", re.IGNORECASE | re.DOTALL)
        if match:
            title = clean_text(match.group(1))
            if title:
                return title
    return "Official meteo.tn crescent report"


def parse_meteo_published_date(text: str) -> str | None:
    for match in re.finditer(r"\b(\d{2})/(\d{2})/(\d{4})\b", text):
        first, second, year = (int(match.group(1)), int(match.group(2)), int(match.group(3)))
        for month, day in ((first, second), (second, first)):
            try:
                parsed = dt.date(year, month, day)
            except ValueError:
                continue
            if parsed <= dt.date.today() + dt.timedelta(days=400):
                return parsed.isoformat()
    return None


def ask_deepseek(
    api_key: str,
    event: TargetEvent,
    hijri_year: int,
    today: dt.date,
    candidates: list[ArticleCandidate],
) -> dict[str, Any]:
    expected = hijri_to_gregorian(hijri_year, event.hijri_month, event.hijri_day)
    prompt = {
        "today": today.isoformat(),
        "event": event.key,
        "eventLabel": event.label,
        "hijriYear": hijri_year,
        "roughCalendarWindowCenter": expected.isoformat(),
        "roughCalendarWindowNote": "Arithmetic approximation for search and validation only; not evidence and not a source.",
        "allowedDateDriftDays": 4,
        "sourceTiers": {
            "official": "Tunisian government or official authority website",
            "state_news": "Tunisian state news agency; acceptable only when it reports an official authority announcement",
            "trusted_news": "Popular Tunisian news website; needs corroboration from another distinct trusted source",
        },
        "articles": [candidate.prompt_record() for candidate in candidates],
        "requiredOutput": {
            "event": event.key,
            "hijriYear": hijri_year,
            "selectedDate": "YYYY-MM-DD or null",
            "confidence": "high | medium | low",
            "reason": "short explanation",
            "claims": [
                {
                    "sourceId": "article id from input",
                    "gregorianDate": "YYYY-MM-DD or null",
                    "certainty": "announced | official_astronomical_report | expected | unclear",
                    "isOfficialAnnouncement": True,
                    "authorityMentioned": "Dar al-Ifta, INM/meteo.tn, ministry, or null",
                    "quote": "short quote from title/snippet only",
                },
            ],
            "conflicts": ["short conflict descriptions"],
        },
    }
    body = {
        "model": os.environ.get("DEEPSEEK_MODEL", "deepseek-chat"),
        "temperature": 0,
        "response_format": {"type": "json_object"},
        "messages": [
            {
                "role": "system",
                "content": textwrap.dedent(
                    """
                    You extract Tunisian official Ramadan and Aid date announcements from provided article records.
                    Use only the provided records. Do not browse. Do not infer a date from the rough calendar window.
                    Treat expected or predicted dates as low confidence. The rough calendar window is not evidence and must
                    never be used as selectedDate unless one or more provided article records announce that date. A
                    high-confidence date needs an announced official Tunisian authority decision, an official meteo.tn/INM
                    crescent visibility report that clearly implies the first day, or at least two distinct trusted Tunisian
                    sources that report the same official authority announcement. For meteo.tn Ramadan crescent reports,
                    never treat the report publication date or imsakiyya publication date as the start date unless the text
                    explicitly says it is 1 Ramadan. If the report says the crescent is not visible after sunset on 29 Shaaban
                    on date D, do not select D+1 as the first fasting day; Shaaban completes on D+1. If the report says
                    visibility becomes possible after sunset on date V, select V+1 as the first fasting day and mark the claim
                    certainty as official_astronomical_report. You may resolve relative phrases such as tomorrow, Saturday,
                    or مساء اليوم only when the article's published date is available in the provided record. Return strict
                    JSON only.
                    """,
                ).strip(),
            },
            {
                "role": "user",
                "content": json.dumps(prompt, ensure_ascii=False, indent=2),
            },
        ],
    }
    request = urllib.request.Request(
        "https://api.deepseek.com/chat/completions",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            response_body = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:1_000]
        raise RuntimeError(f"DeepSeek request failed with HTTP {error.code}: {detail}") from error

    payload = json.loads(response_body)
    content = payload["choices"][0]["message"]["content"]
    return parse_json_object(content)


def parse_json_object(content: str) -> dict[str, Any]:
    try:
        value = json.loads(content)
    except json.JSONDecodeError:
        start = content.find("{")
        end = content.rfind("}")
        if start < 0 or end <= start:
            raise
        value = json.loads(content[start : end + 1])
    if not isinstance(value, dict):
        raise ValueError("DeepSeek response was not a JSON object.")
    return value


def validate_decision(
    decision: dict[str, Any],
    event: TargetEvent,
    hijri_year: int,
    candidates: list[ArticleCandidate],
) -> dict[str, Any]:
    candidate_by_id = {candidate.id: candidate for candidate in candidates}
    selected_date = decision.get("selectedDate")
    result: dict[str, Any] = {
        "accepted": False,
        "selected_date": selected_date,
        "confidence": decision.get("confidence", "low"),
        "reason": decision.get("reason", ""),
        "evidence": [],
        "rejection_reason": "",
    }
    if not isinstance(selected_date, str) or not JSON_DATE_RE.match(selected_date):
        result["rejection_reason"] = "No ISO selected date was returned."
        return result
    if decision.get("event") != event.key or int(decision.get("hijriYear", -1)) != hijri_year:
        result["rejection_reason"] = "The response event or Hijri year did not match the request."
        return result
    if decision.get("confidence") != "high":
        result["rejection_reason"] = "The model did not mark the result as high confidence."
        return result

    parsed_selected = parse_iso_date(selected_date)
    expected = hijri_to_gregorian(hijri_year, event.hijri_month, event.hijri_day)
    if abs((parsed_selected - expected).days) > 4:
        result["rejection_reason"] = (
            f"Selected date {selected_date} is too far from rough calendar window center {expected.isoformat()}."
        )
        return result

    claims = decision.get("claims") if isinstance(decision.get("claims"), list) else []
    matching_announced_claims = []
    conflicting_dates: set[str] = set()
    for claim in claims:
        if not isinstance(claim, dict):
            continue
        claim_date = claim.get("gregorianDate")
        if not isinstance(claim_date, str) or not JSON_DATE_RE.match(claim_date):
            continue
        candidate = candidate_by_id.get(str(claim.get("sourceId")))
        authoritative_candidate = candidate is not None and candidate.source_tier in {
            "official",
            "state_news",
            "trusted_news",
        }
        claim_is_announced = (
            claim.get("certainty") == "announced"
            and claim.get("isOfficialAnnouncement") is True
            and authoritative_candidate
        )
        claim_is_meteo_report = (
            claim.get("certainty") == "official_astronomical_report"
            and candidate is not None
            and candidate.source_domain == "meteo.tn"
        )
        if claim_is_announced or claim_is_meteo_report:
            if claim_date == selected_date:
                matching_announced_claims.append(claim)
            else:
                conflicting_dates.add(claim_date)
    if conflicting_dates:
        result["rejection_reason"] = "Conflicting announced dates were found."
        return result

    source_domains: dict[str, str] = {}
    official_evidence = False
    for claim in matching_announced_claims:
        candidate = candidate_by_id.get(str(claim.get("sourceId")))
        if not candidate:
            continue
        source_domains[candidate.source_domain] = candidate.source_tier
        quote = clean_text(str(claim.get("quote", "")))[:240]
        result["evidence"].append(
            {
                "source": candidate.source_name,
                "domain": candidate.source_domain,
                "tier": candidate.source_tier,
                "url": candidate.url,
                "quote": quote,
            },
        )
        if (
            candidate.source_domain == "meteo.tn"
            and claim.get("certainty") == "official_astronomical_report"
        ) or candidate.source_tier == "official" or (
            candidate.source_tier == "state_news" and claim.get("authorityMentioned")
        ):
            official_evidence = True

    corroborated_news_count = sum(1 for tier in source_domains.values() if tier in {"state_news", "trusted_news"})
    if not official_evidence and corroborated_news_count < 2:
        result["rejection_reason"] = "The date was not confirmed by an official source or two distinct trusted sources."
        return result

    result["accepted"] = True
    return result


def append_validation_report(report_lines: list[str], event: TargetEvent, validation: dict[str, Any]) -> None:
    report_lines.append(f"Selected date: `{validation.get('selected_date')}`")
    report_lines.append(f"Confidence: `{validation.get('confidence')}`")
    if validation.get("reason"):
        report_lines.append(f"Reason: {validation['reason']}")
    if validation["accepted"]:
        report_lines.append("Validation: accepted.")
        evidence = validation.get("evidence", [])
        if evidence:
            report_lines.append("")
            report_lines.append("Evidence:")
            for item in evidence:
                report_lines.append(
                    f"- {item['source']} (`{item['domain']}`, {item['tier']}): {item['quote']}",
                )
    else:
        report_lines.append(f"Validation: rejected. {validation['rejection_reason']}")


def update_override_file(
    repo_root: Path,
    event: TargetEvent,
    hijri_year: int,
    gregorian_date: str,
    dry_run: bool,
) -> bool:
    docs_dir = repo_root / "docs"
    path = docs_dir / f"ramadan-override-{hijri_year}.json"
    if path.exists():
        data = json.loads(path.read_text(encoding="utf-8"))
    else:
        data = {
            "hijriYear": hijri_year,
            "ramadanStart": None,
            "eidFitrDate": None,
            "eidAdhaDate": None,
            "lastUpdated": None,
        }
    if int(data.get("hijriYear", -1)) != hijri_year:
        raise ValueError(f"{path} has hijriYear={data.get('hijriYear')}, expected {hijri_year}.")
    existing_date = data.get(event.override_field)
    if existing_date == gregorian_date:
        return False
    if existing_date not in (None, ""):
        raise ValueError(
            f"{path} already has {event.override_field}={existing_date}; refusing to overwrite with {gregorian_date}.",
        )
    data[event.override_field] = gregorian_date
    data["lastUpdated"] = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    if dry_run:
        return True
    docs_dir.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return True


def write_reports(args: argparse.Namespace, report_lines: list[str]) -> None:
    content = "\n".join(report_lines).strip() + "\n"
    if args.pr_body:
        path = Path(args.pr_body)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(content)
            summary.write("\n")


def gregorian_to_jd(date: dt.date) -> int:
    month = date.month
    year = date.year
    day = date.day
    a = (14 - month) // 12
    y = year + 4800 - a
    m = month + 12 * a - 3
    return day + ((153 * m + 2) // 5) + 365 * y + y // 4 - y // 100 + y // 400 - 32045


def jd_to_gregorian(jd: int) -> dt.date:
    a = jd + 32044
    b = (4 * a + 3) // 146097
    c = a - (146097 * b) // 4
    d = (4 * c + 3) // 1461
    e = c - (1461 * d) // 4
    m = (5 * e + 2) // 153
    day = e - (153 * m + 2) // 5 + 1
    month = m + 3 - 12 * (m // 10)
    year = 100 * b + d - 4800 + (m // 10)
    return dt.date(year, month, day)


def hijri_to_jd(year: int, month: int, day: int) -> int:
    return (
        day
        + math.ceil(29.5 * (month - 1))
        + (year - 1) * 354
        + math.floor((3 + 11 * year) / 30)
        + ISLAMIC_EPOCH
        - 1
    )


def hijri_to_gregorian(year: int, month: int, day: int) -> dt.date:
    return jd_to_gregorian(hijri_to_jd(year, month, day))


def gregorian_to_hijri(date: dt.date) -> tuple[int, int, int]:
    jd = gregorian_to_jd(date)
    year = math.floor((30 * (jd - ISLAMIC_EPOCH) + 10646) / 10631)
    month = min(12, math.ceil((jd - (29 + hijri_to_jd(year, 1, 1))) / 29.5) + 1)
    day = jd - hijri_to_jd(year, month, 1) + 1
    return year, month, day


if __name__ == "__main__":
    raise SystemExit(main())