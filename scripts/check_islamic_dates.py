#!/usr/bin/env python3
"""
Automated Islamic date override checker for Tunisian Prayer Times.

Scrapes trusted Tunisian sources for official announcements about:
  - Ramadan start date
  - Eid al-Fitr date
  - Eid al-Adha date

Uses Alibaba's Qwen model (via OpenAI-compatible API) to extract
confirmed dates from Arabic/French news content.

Sources:
  1. Ministry of Religious Affairs: https://www.affaires-religieuses.tn/public/actualites
  2. Mosaique FM search: https://www.mosaiquefm.net/ar/recherche?q=...
  3. INM (Météo Tunisie): https://www.meteo.tn/fr/actualites

Usage:
  DASHSCOPE_API_KEY=sk-xxx python scripts/check_islamic_dates.py --hijri-year 1448
  # or with any OpenAI-compatible endpoint:
  LLM_BASE_URL=http://localhost:11434/v1 LLM_MODEL=qwen3:8b python scripts/check_islamic_dates.py --hijri-year 1448
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.request
import urllib.parse
import urllib.error
import ssl
from datetime import datetime, timezone
from pathlib import Path

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

OVERRIDE_DIR = Path(__file__).resolve().parent.parent / "docs"

SOURCES = {
    "ministry": "https://www.affaires-religieuses.tn/public/actualites",
    "mosaique_ramadan": "https://www.mosaiquefm.net/ar/recherche?q={query}",
    "meteo": "https://www.meteo.tn/fr/actualites",
}

SEARCH_QUERIES = {
    "ramadan_start": [
        "غرة رمضان تونس",
        "أول أيام رمضان تونس",
        "هلال رمضان تونس",
    ],
    "eid_fitr": [
        "عيد الفطر تونس",
        "غرة شوال تونس",
        "هلال شوال تونس",
    ],
    "eid_adha": [
        "عيد الأضحى تونس",
        "وقفة عرفات تونس",
    ],
}

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

# Allow unverified SSL for sites with cert issues (common with .tn domains)
_ssl_ctx = ssl.create_default_context()
_ssl_ctx.check_hostname = False
_ssl_ctx.verify_mode = ssl.CERT_NONE


def fetch_url(url: str, timeout: int = 15) -> str:
    """Fetch a URL and return its text content."""
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=_ssl_ctx) as resp:
            charset = resp.headers.get_content_charset() or "utf-8"
            return resp.read().decode(charset, errors="replace")
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as e:
        print(f"  ⚠ Failed to fetch {url}: {e}", file=sys.stderr)
        return ""


def strip_html(html: str) -> str:
    """Crude HTML tag stripper — good enough for extracting text."""
    text = re.sub(r"<script[^>]*>.*?</script>", " ", html, flags=re.S | re.I)
    text = re.sub(r"<style[^>]*>.*?</style>", " ", text, flags=re.S | re.I)
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"&[a-zA-Z]+;", " ", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


# ---------------------------------------------------------------------------
# Source scrapers
# ---------------------------------------------------------------------------


def scrape_ministry() -> str:
    """Scrape latest news from the Ministry of Religious Affairs."""
    print("  → Fetching Ministry of Religious Affairs...")
    html = fetch_url(SOURCES["ministry"])
    if not html:
        return ""
    return strip_html(html)[:8000]


def scrape_mosaique(event_type: str) -> str:
    """Search Mosaique FM for a specific event."""
    queries = SEARCH_QUERIES.get(event_type, [])
    all_text = []
    for q in queries[:2]:  # Limit to 2 queries to stay fast
        url = SOURCES["mosaique_ramadan"].format(query=urllib.parse.quote(q))
        print(f"  → Searching Mosaique FM: {q}")
        html = fetch_url(url)
        if html:
            all_text.append(strip_html(html)[:4000])
    return "\n---\n".join(all_text)


def scrape_meteo() -> str:
    """Scrape INM (Météo Tunisie) news page."""
    print("  → Fetching Météo Tunisie...")
    html = fetch_url(SOURCES["meteo"])
    if not html:
        return ""
    return strip_html(html)[:8000]


def gather_source_content(event_type: str) -> str:
    """Gather content from all sources for a given event type."""
    parts = []

    ministry = scrape_ministry()
    if ministry:
        parts.append(f"=== وزارة الشؤون الدينية (Ministry of Religious Affairs) ===\n{ministry}")

    mosaique = scrape_mosaique(event_type)
    if mosaique:
        parts.append(f"=== موزاييك إف إم (Mosaique FM) ===\n{mosaique}")

    meteo = scrape_meteo()
    if meteo:
        parts.append(f"=== المعهد الوطني للرصد الجوي (Météo Tunisie) ===\n{meteo}")

    return "\n\n".join(parts)


# ---------------------------------------------------------------------------
# LLM interaction (Qwen via DashScope or any OpenAI-compatible API)
# ---------------------------------------------------------------------------


def get_llm_config() -> tuple[str, str, str]:
    """Return (base_url, api_key, model) for the LLM."""
    # Priority: explicit env vars > DashScope defaults
    base_url = os.environ.get("LLM_BASE_URL", "").rstrip("/")
    api_key = os.environ.get("LLM_API_KEY") or os.environ.get("DASHSCOPE_API_KEY", "")
    model = os.environ.get("LLM_MODEL", "")

    if not base_url:
        # Default: Alibaba DashScope (Qwen) — free tier available
        base_url = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
    if not model:
        model = "qwen-plus"

    return base_url, api_key, model


def ask_llm(system_prompt: str, user_prompt: str) -> str:
    """Call the LLM and return the assistant's response text."""
    base_url, api_key, model = get_llm_config()

    if not api_key:
        print("ERROR: Set DASHSCOPE_API_KEY or LLM_API_KEY environment variable.", file=sys.stderr)
        sys.exit(1)

    url = f"{base_url}/chat/completions"

    payload = json.dumps({
        "model": model,
        "temperature": 0.1,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
    }).encode("utf-8")

    req = urllib.request.Request(
        url,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
            "User-Agent": USER_AGENT,
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(req, timeout=60, context=_ssl_ctx) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            return body["choices"][0]["message"]["content"].strip()
    except Exception as e:
        print(f"  ⚠ LLM call failed: {e}", file=sys.stderr)
        return ""


# ---------------------------------------------------------------------------
# Date extraction logic
# ---------------------------------------------------------------------------

SYSTEM_PROMPT = """\
You are a precise date extraction assistant for Tunisian Islamic calendar events.
You read Arabic and French news content from Tunisian official sources and extract
ONLY officially confirmed dates (not predictions or astronomical forecasts).

Rules:
- Only extract a date if the source clearly states an OFFICIAL ANNOUNCEMENT or CONFIRMATION
  from Tunisia (وزارة الشؤون الدينية، رئاسة الحكومة، بلاغ رسمي).
- Astronomical predictions (فلكيا) alone are NOT sufficient — we need the official moon
  sighting confirmation (تحري الهلال، ثبوت الرؤية).
- Return the date in ISO 8601 format: YYYY-MM-DD
- If no confirmed date is found, return exactly: NOT_FOUND
- Do NOT explain or add commentary. Return ONLY the date or NOT_FOUND.
"""


def extract_date_for_event(event_type: str, hijri_year: int) -> str | None:
    """
    Try to extract an officially confirmed date for the given event.
    Returns ISO date string or None.
    """
    event_labels = {
        "ramadan_start": f"أول أيام شهر رمضان {hijri_year} هـ في تونس (the first day of Ramadan {hijri_year}H in Tunisia)",
        "eid_fitr": f"أول أيام عيد الفطر {hijri_year} هـ في تونس (Eid al-Fitr {hijri_year}H in Tunisia)",
        "eid_adha": f"يوم عيد الأضحى {hijri_year} هـ في تونس (Eid al-Adha {hijri_year}H in Tunisia)",
    }

    label = event_labels[event_type]
    print(f"\n🔍 Checking: {label}")

    content = gather_source_content(event_type)
    if not content:
        print("  ⚠ No content fetched from any source.")
        return None

    user_prompt = (
        f"Based on the following news content from Tunisian sources, "
        f"what is the officially confirmed Gregorian date for: {label}?\n\n"
        f"News content:\n{content[:12000]}"
    )

    print("  → Asking LLM to extract date...")
    response = ask_llm(SYSTEM_PROMPT, user_prompt)
    print(f"  ← LLM response: {response}")

    if not response or "NOT_FOUND" in response:
        return None

    # Extract ISO date from response (tolerant parsing)
    match = re.search(r"(\d{4}-\d{2}-\d{2})", response)
    if match:
        date_str = match.group(1)
        # Validate it's a real date
        try:
            datetime.strptime(date_str, "%Y-%m-%d")
            return date_str
        except ValueError:
            print(f"  ⚠ Invalid date in LLM response: {date_str}")
            return None

    return None


# ---------------------------------------------------------------------------
# Override file management
# ---------------------------------------------------------------------------


def load_override(hijri_year: int) -> dict:
    """Load the override JSON for the given Hijri year."""
    path = OVERRIDE_DIR / f"ramadan-override-{hijri_year}.json"
    if path.exists():
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    return {
        "hijriYear": hijri_year,
        "ramadanStart": None,
        "eidFitrDate": None,
        "eidAdhaDate": None,
        "lastUpdated": None,
    }


def save_override(data: dict) -> None:
    """Save the override JSON."""
    path = OVERRIDE_DIR / f"ramadan-override-{data['hijriYear']}.json"
    data["lastUpdated"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"\n✅ Updated {path.name}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(description="Check Tunisian Islamic date announcements")
    parser.add_argument("--hijri-year", type=int, required=True, help="Hijri year (e.g. 1448)")
    parser.add_argument(
        "--events",
        nargs="+",
        choices=["ramadan_start", "eid_fitr", "eid_adha"],
        default=None,
        help="Which events to check (default: all with null dates)",
    )
    parser.add_argument("--dry-run", action="store_true", help="Don't write changes, just print")
    args = parser.parse_args()

    override = load_override(args.hijri_year)
    print(f"Current override for {args.hijri_year}H: {json.dumps(override, indent=2)}")

    # Determine which events to check
    field_map = {
        "ramadan_start": "ramadanStart",
        "eid_fitr": "eidFitrDate",
        "eid_adha": "eidAdhaDate",
    }

    if args.events:
        events_to_check = args.events
    else:
        # Only check events that are still null
        events_to_check = [
            event for event, field in field_map.items() if override.get(field) is None
        ]

    if not events_to_check:
        print("\n✓ All dates already set. Nothing to check.")
        return

    print(f"\nEvents to check: {', '.join(events_to_check)}")

    updated = False
    for event in events_to_check:
        field = field_map[event]
        if override.get(field) is not None and event not in (args.events or []):
            print(f"\n⏭ Skipping {event} — already set to {override[field]}")
            continue

        date = extract_date_for_event(event, args.hijri_year)
        if date:
            print(f"\n📅 Found {event}: {date}")
            if override.get(field) != date:
                override[field] = date
                updated = True
        else:
            print(f"\n❌ No confirmed date found for {event}")

    if updated:
        if args.dry_run:
            print(f"\n[DRY RUN] Would update to:\n{json.dumps(override, indent=2)}")
        else:
            save_override(override)
            # Signal to CI that changes were made
            github_output = os.environ.get("GITHUB_OUTPUT")
            if github_output:
                with open(github_output, "a") as f:
                    f.write("updated=true\n")
    else:
        print("\n— No new dates found. Override unchanged.")
        github_output = os.environ.get("GITHUB_OUTPUT")
        if github_output:
            with open(github_output, "a") as f:
                f.write("updated=false\n")


if __name__ == "__main__":
    main()
