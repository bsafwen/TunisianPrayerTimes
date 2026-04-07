# Automated Islamic Date Override Checker

## Problem

The prayer times app relies on override JSON files (`docs/ramadan-override-{hijriYear}.json`) for three critical dates each Hijri year:

| Event | JSON Field | When Announced | Approx. Gregorian (shifts ~11 days/year) |
|-------|-----------|----------------|------------------------------------------|
| Ramadan start | `ramadanStart` | Evening of 29th Sha'ban | Late Jan – mid Feb (2026–2028) |
| Eid al-Fitr | `eidFitrDate` | Evening of 29th Ramadan | Late Feb – mid Mar |
| Eid al-Adha | `eidAdhaDate` | Evening of 29th Dhul Qi'dah | Late May – mid Jun |

These dates depend on **official moon sighting** by the Tunisian Ministry of Religious Affairs — they cannot be computed algorithmically. The Islamic calendar drifts ~11 days earlier each Gregorian year.

Previously, a human had to watch the news, edit the JSON, and push to GitHub.

### Override JSON Format

```json
{
  "hijriYear": 1447,
  "ramadanStart": "2026-02-19",
  "eidFitrDate": "2026-03-20",
  "eidAdhaDate": null,
  "lastUpdated": "2026-04-06T12:00:00Z"
}
```

- All dates are **Gregorian ISO 8601** (not Hijri)
- `null` means "not yet announced"
- `lastUpdated` is informational only (not parsed by apps)
- Files live at `docs/ramadan-override-{hijriYear}.json` and are served via GitHub Pages at `https://bsafwen.github.io/TunisianPrayerTimes/ramadan-override-{hijriYear}.json`

## Solution

A Python script + GitHub Actions workflow that:

1. **Scrapes** three trusted Tunisian news sources
2. **Sends** the scraped content to Alibaba's Qwen LLM
3. **Extracts** only officially confirmed dates (rejects astronomical predictions)
4. **Updates** the override JSON and auto-commits to GitHub

The apps already poll GitHub Pages hourly during announcement windows, so updates propagate to users within ~1 hour.

### End-to-End Data Flow

```
Ministry announces → Script scrapes sources → LLM extracts date
  → JSON updated → git push → GitHub Pages (~10 min)
  → Apps poll hourly during Hijri windows → Cache override locally
  → Prayer table / silence schedule adjusts immediately
```

The app-side polling is handled by `RamadanOverrideChecker` in `multiplatform/shared/`. It only polls during narrow Hijri date windows (28th–29th Sha'ban, 29th Ramadan, etc.) and stops as soon as a non-null date is cached. Outside these windows: zero network requests.

## Architecture

```
┌─────────────────────┐
│  GitHub Actions      │  Cron: 4x/evening during relevant months
│  (ubuntu-latest)     │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  check_islamic_      │  Python script (zero dependencies)
│  dates.py            │
└──────────┬──────────┘
           │
     ┌─────┴──────┐
     ▼            ▼
┌─────────┐  ┌─────────────┐
│ Scrape  │  │   Qwen LLM  │  DashScope API (Alibaba)
│ 3 sites │  │  date extract│  or self-hosted via Ollama
└─────────┘  └──────┬──────┘
                    │
                    ▼
           ┌──────────────┐
           │ Update JSON  │  docs/ramadan-override-{year}.json
           │ git push     │
           └──────────────┘
                    │
                    ▼
           ┌──────────────┐
           │ GitHub Pages │  Apps poll this hourly
           └──────────────┘
```

## Sources

| Source | URL | Content |
|--------|-----|---------|
| Ministry of Religious Affairs | `affaires-religieuses.tn/public/actualites` | Official announcements (Arabic) |
| Mosaique FM | `mosaiquefm.net/ar/recherche?q=...` | News coverage (Arabic) |
| Météo Tunisie (INM) | `meteo.tn/fr/actualites` | Imsakia / calendar dates (French) |

## LLM Strategy

The script uses a strict system prompt to avoid false positives:

```
You are a precise date extraction assistant for Tunisian Islamic calendar events.
You read Arabic and French news content from Tunisian official sources and extract
ONLY officially confirmed dates (not predictions or astronomical forecasts).

Rules:
- Only extract a date if the source clearly states an OFFICIAL ANNOUNCEMENT or
  CONFIRMATION from Tunisia (وزارة الشؤون الدينية، رئاسة الحكومة، بلاغ رسمي).
- Astronomical predictions (فلكيا) alone are NOT sufficient — we need the official
  moon sighting confirmation (تحري الهلال، ثبوت الرؤية).
- Return the date in ISO 8601 format: YYYY-MM-DD
- If no confirmed date is found, return exactly: NOT_FOUND
- Do NOT explain or add commentary. Return ONLY the date or NOT_FOUND.
```

Key distinction: Tunisian media often publish **astronomical predictions** weeks before Ramadan (e.g. "فلكيا رمضان يبدأ يوم الخميس"). These are forecasts, not official — the actual date is only confirmed after the **moon sighting committee** meets on the evening of 29th Sha'ban. The LLM prompt is designed to reject predictions and only accept confirmations.

### Why Qwen (Alibaba)?

- Strong Arabic + French language understanding
- Free tier available via [DashScope](https://dashscope.console.aliyun.com/) (more than enough for 4 calls/day)
- OpenAI-compatible API — easy to swap for any other model
- Can also run locally via Ollama (`qwen3:8b`)

## Schedule

The GitHub Actions workflow runs **4 times per evening** during months when announcements are expected:

| UTC Time | Tunisia Time | Purpose |
|----------|-------------|---------|
| 17:30 | 18:30 | First check after Maghrib |
| 19:30 | 20:30 | Second check |
| 21:30 | 22:30 | Third check |
| 23:30 | 00:30+1 | Late night check |

Active months: **January–March** (Ramadan + Eid al-Fitr) and **May–June** (Eid al-Adha).

The script exits immediately if all dates for the current Hijri year are already filled.

## Setup

### 1. Add DashScope API Key

Get a free key at [dashscope.console.aliyun.com](https://dashscope.console.aliyun.com/).

In your GitHub repo: **Settings → Secrets → Actions → New repository secret**:

```
Name:  DASHSCOPE_API_KEY
Value: sk-xxxxxxxxxxxxxxxx
```

### 2. (Optional) Self-Hosted Model

For a self-hosted Qwen via Ollama or similar, set these secrets instead:

```
LLM_BASE_URL=http://your-server:11434/v1
LLM_API_KEY=ollama
LLM_MODEL=qwen3:8b
```

Then uncomment the env lines in `.github/workflows/check-islamic-dates.yml`.

### 3. Prepare Next Year's Override File

Before each Hijri year, create a template:

```bash
cat > docs/ramadan-override-1449.json << 'EOF'
{
  "hijriYear": 1449,
  "ramadanStart": null,
  "eidFitrDate": null,
  "eidAdhaDate": null,
  "lastUpdated": null
}
EOF
git add docs/ramadan-override-1449.json
git commit -m "Add override template for 1449H"
git push
```

## Usage

### Automatic (Default)

The workflow runs on schedule. No action needed.

### Manual Trigger

Via GitHub Actions UI → "Check Islamic Date Overrides" → "Run workflow":

| Input | Description |
|-------|-------------|
| `hijri_year` | Override auto-detection (e.g. `1448`) |
| `events` | Check specific events: `ramadan_start eid_fitr eid_adha` |
| `dry_run` | Preview without committing |

### Local Testing

```bash
# With DashScope
DASHSCOPE_API_KEY=sk-xxx python3 scripts/check_islamic_dates.py --hijri-year 1448 --dry-run

# With local Ollama
LLM_BASE_URL=http://localhost:11434/v1 LLM_API_KEY=ollama LLM_MODEL=qwen3:8b \
  python3 scripts/check_islamic_dates.py --hijri-year 1448 --dry-run

# Check only Eid al-Adha
DASHSCOPE_API_KEY=sk-xxx python3 scripts/check_islamic_dates.py --hijri-year 1447 --events eid_adha
```

## Safety

- **No false positives**: The LLM prompt explicitly rejects astronomical predictions and requires official confirmation
- **Idempotent**: Running multiple times with the same data produces no extra commits
- **Skip logic**: Already-filled dates are skipped automatically
- **Dry run**: Always test with `--dry-run` first
- **Manual fallback**: You can still edit the JSON by hand anytime — the script never overwrites a non-null date unless explicitly told via `--events`

## Failure Modes & Troubleshooting

| Failure | What Happens | Recovery |
|---------|-------------|----------|
| Source website down (e.g. `affaires-religieuses.tn` unreachable) | That source returns empty; other sources still checked | Automatic — next run retries |
| All 3 sources down | No content to send to LLM; script exits with "No content fetched" | Automatic — next run retries (4 runs/evening) |
| LLM API error (DashScope down, quota exceeded) | Script prints warning, no JSON update | Check `DASHSCOPE_API_KEY` validity; next run retries |
| LLM hallucinates a date | Unlikely due to strict `NOT_FOUND` prompt + date validation, but possible | Review git history; manually revert the JSON if wrong |
| LLM returns `NOT_FOUND` when date is actually announced | Announcement may not yet appear in scraped content (pages cache) | Next run (2h later) will retry; manual update as fallback |
| Override JSON already has all dates filled | Script exits immediately: "All dates already set" | Expected — no action needed |
| Wrong Hijri year auto-detected | Workflow checks all existing override files for null dates | Use `--hijri-year` manual input to force |

### Key Limitation

The scraping approach extracts **text from HTML pages** using basic tag stripping — it does not execute JavaScript. If a source moves to a fully client-rendered SPA, that source will return empty content. The other sources provide redundancy.

## Files

| File | Purpose |
|------|---------|
| [`scripts/check_islamic_dates.py`](check_islamic_dates.py) | Scraper + LLM extractor (zero external dependencies) |
| [`.github/workflows/check-islamic-dates.yml`](../.github/workflows/check-islamic-dates.yml) | GitHub Actions cron workflow |
| [`docs/ramadan-override-{year}.json`](../docs/) | Override files consumed by the apps |
