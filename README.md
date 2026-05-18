# 🕌 Tunisian Prayer Times

Fetches daily prayer times for **every delegation in Tunisia** from the [Institut National de la Météorologie](https://www.meteo.tn), stores them as CSV files compatible with [mawaqit.net](https://mawaqit.net), and serves them through a static web app, an Android app, and a cross-platform desktop app. Also includes a Qaloon Quran recitation recognition pipeline.

🔗 **Live site:** hosted on GitHub Pages (`docs/`)
📱 **Android app:** [Download APK from Releases](https://github.com/bsafwen/TunisianPrayerTimes/releases)
🖥️ **Desktop app:** [Download JAR/DMG/MSI from Releases](https://github.com/bsafwen/TunisianPrayerTimes/releases)

---

## Features

| Feature | Description |
|---------|-------------|
| **Data ingestion** | Scrapes Fajr, Sunrise, Dhuhr, Asr, Maghrib & Isha for all 24 gouvernorats and their delegations |
| **CSV export** | Generates per-month CSV files in Mawaqit format, downloadable as a ZIP |
| **Prayer calendar** | Interactive monthly calendar view in the browser |
| **Mawaqit push** | Push prayer times directly to mawaqit.net from the browser via a Cloudflare Worker proxy |
| **Android app** | Auto-silence phone during prayer times with per-delegation schedules, GPS auto-detect, boot reschedule |
| **Desktop app** | Cross-platform (Windows/macOS/Linux) Compose Multiplatform app with the same prayer time & silence features |
| **Qaloon model** | Fine-tuned Whisper ASR model for Qaloon (Nafi' riwaya) Quran recitation |
| **Qaloon app** | Android app for on-device Qaloon recitation recognition with word-level error detection |
| **Voice contributions** | Cloudflare Worker API for recording and storing Qaloon ayah readings (R2 bucket) |
| **CI/CD** | GitHub Actions for automated data ingestion, builds, tests, and multi-platform releases |
| **Incremental updates** | Only fetches missing days; supports partial-year and repair modes |

---

## Project Structure

```
├── scraper/              # Data ingestion (TypeScript / Bun)
│   └── src/              # index.ts, api.ts, csv.ts, config.ts, types.ts
├── docs/                 # GitHub Pages static site
│   ├── index.html        # Arabic RTL web app
│   ├── app.js            # Delegation search, download, calendar logic
│   ├── mawaqit-push.js   # Push-to-mawaqit UI logic
│   ├── style.css         # Styles
│   ├── gouvernorats.json # Delegation list (Arabic + French + English)
│   ├── csv/              # Generated prayer-time CSVs (per delegation/year/month)
│   ├── privacy-policy.html
│   ├── qaloon-data-collection.md
│   └── qaloon-model-plan.md
├── android-app/          # Android prayer times app (Kotlin / Compose)
│   └── app/src/main/     # Auto-silence, GPS locate, boot reschedule
├── multiplatform/        # Desktop app (Compose Multiplatform — Windows/macOS/Linux)
│   ├── shared/           # Shared Kotlin module (models, parsers, theme, strings)
│   └── desktopApp/       # Desktop Compose UI
├── qaloon-model/         # Whisper fine-tuning pipeline (Python)
│   ├── scripts/          # 30+ scripts: download, segment, train, evaluate
│   └── data/             # Audio segments, metadata, text references
├── qaloon-app/           # Qaloon recitation Android app (Kotlin + C++ Whisper)
├── worker/               # Cloudflare Worker — mawaqit proxy + contributions API
├── .github/workflows/    # CI/CD (ingest, release, android-tests, desktop-tests)
├── release.sh            # Local release: bump version, build, tag, publish
└── fix.sh                # RTL/LTR layout fixer for Android XML layouts
```

---

## Getting Started

### Scraper

#### Prerequisites

- [Bun](https://bun.sh/) (recommended) or Node.js ≥ 18

#### Install & Run

```bash
cd scraper
bun install

# Fetch the current year for all delegations
bun src/index.ts
```

#### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `YEAR` | current year | Year to fetch |
| `CONCURRENCY` | `1` | Number of parallel requests |
| `DELAY_MS` | `1000` | Delay between requests (ms) |
| `DOCS_DIR` | `./docs` | Output directory for CSVs |
| `PARTIAL_YEAR` | `0` | Set to `1` to only fetch completed months |
| `REPAIR` | `0` | Set to `1` to re-fetch rows with empty prayer fields |
| `CHECK` | `0` | Set to `1` to scan CSVs and report problems without writing |

Example:

```bash
YEAR=2026 CONCURRENCY=3 DELAY_MS=500 bun src/index.ts
```

---

### Android App

Requires Android SDK and JDK 17+.

```bash
cd android-app
./gradlew assembleRelease    # Build APK
./gradlew bundleRelease      # Build AAB for Play Store
```

**Key capabilities:**
- Auto-silence / Do Not Disturb during prayer times
- GPS-based delegation auto-detection
- Reschedules alarms on device boot
- Battery optimization exemption for reliable scheduling
- Onboarding wizard for first-time setup

---

### Desktop App

Requires JDK 17+. See [multiplatform/README.md](multiplatform/README.md) for full details.

```bash
cd multiplatform
./setup-data.sh              # Symlink prayer CSVs from docs/
./gradlew :desktopApp:run    # Run the desktop app
```

Build native installers:

```bash
./gradlew :desktopApp:packageDmg    # macOS
./gradlew :desktopApp:packageMsi    # Windows
./gradlew :desktopApp:packageDeb    # Linux
```

---

### Qaloon Model

Fine-tunes OpenAI Whisper on Qaloon recitation audio (~110+ hours target). See [qaloon-model/README.md](qaloon-model/README.md) for the full pipeline.

```bash
cd qaloon-model
pip install -r requirements.txt
```

Data sources: EveryAyah.com, MP3Quran.net, Archive.org, user contributions via the worker API.

---

## Web App (GitHub Pages)

The `docs/` folder is a self-contained static site:

- **Autocomplete search** — find any delegation by Arabic or French name
- **Year selector** — pick from available years
- **ZIP download** — downloads all 12 monthly CSVs for the selected delegation/year
- **Prayer calendar** — browse prayer times month by month in a table
- **Mawaqit push** — authenticated push to mawaqit.net via Cloudflare Worker

To serve locally:

```bash
cd docs && python3 -m http.server 8000
```

---

## Cloudflare Worker

The `worker/` directory contains a Cloudflare Worker with two roles:

1. **Mawaqit proxy** — avoids CORS issues for browser-based prayer time uploads to mawaqit.net
2. **Qaloon contributions API** — accepts and stores user-recorded ayah audio in Cloudflare R2
   - `POST /api/contribute` — upload a WAV recording
   - `GET /api/contribute/list` — paginated index of contributions
   - `GET /api/contribute/download?key=…` — stream a single recording
   - `GET /api/contribute/stats` — contribution metrics

See [worker/README.md](worker/README.md) for deployment instructions.

---

## CI/CD

Four GitHub Actions workflows automate the project:

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `ingest.yml` | Jan 1 yearly + manual | Fetch prayer data for the new year, commit CSVs |
| `release.yml` | Tag push (`v*`) | Build Android APK/AAB + desktop installers (macOS/Windows/Linux), publish GitHub Release |
| `android-tests.yml` | Push / PR | Unit tests (Robolectric) + instrumented tests on emulator (API 26/30/33/34) |
| `desktop-tests.yml` | Push / PR | Desktop build verification |

Local releases can also be created with `./release.sh "description"`, which bumps the version, builds all artifacts, tags, and publishes.

---

## Official Islamic Date Corrections

The Tunisian Ministry of Religious Affairs determines the start of Ramadan and Eid al-Fitr by physical moon sighting, announced after Maghreb prayer. This can differ by ±1 day from the algorithmic Hijri calendar (`HijrahDate`). The app supports an override mechanism to use the official dates.

### How it works

1. A JSON file `data/official-islamic-dates/{hijriYear}.json` is served via GitHub Pages at `/data/official-islamic-dates/{hijriYear}.json`
2. All apps (Android, TV, Desktop) poll this file **hourly**, starting **2 days before** the expected event according to `HijrahDate`
3. Once the relevant date is fetched (non-null), polling **stops automatically**
4. `RamadanDetector` checks the override first; if unavailable, falls back to algorithmic `HijrahDate`

The Pages deployment also publishes a generated `ramadan-override-{hijriYear}.json` compatibility copy for already-released app versions.

### When to update

| Event | When to edit | Field to set |
|-------|-------------|--------------|
| Ramadan start | Evening of 29th Sha'ban, after Ministry announcement | `ramadanStart` |
| Eid al-Fitr | Evening of 29th Ramadan, after Ministry announcement | `eidFitrDate` |
| Eid al-Adha | Evening of 29th Dhul Qi'dah, after Ministry announcement | `eidAdhaDate` |

### Steps

```bash
# 1. Edit the file for the current Hijri year (e.g. 1448)
vi data/official-islamic-dates/1448.json

# 2. Set the announced date(s) — use Gregorian (ISO 8601) format
{
  "hijriYear": 1448,
  "ramadanStart": "2027-02-17",
  "eidFitrDate": null,
  "eidAdhaDate": null,
  "lastUpdated": "2027-02-16T20:30:00Z"
}

# 3. Push to GitHub — Pages updates in ~10 minutes
git add data/official-islamic-dates/1448.json
git commit -m "Ramadan 1448 starts 2027-02-17"
git push
```

### Yearly setup

Before each new Hijri year, create a fresh override file:

```bash
cp data/official-islamic-dates/1448.json data/official-islamic-dates/1449.json
# Edit: set hijriYear to 1449, reset all dates to null
```

### Polling windows

The app only polls during these Hijri date windows (to avoid unnecessary network requests):

- **Ramadan start**: 28th–29th Sha'ban (algorithmic ±1 buffer, drift unknown)
- **Eid al-Fitr**: real 29th Ramadan (computed from known `ramadanStart` + 28 days), for 3 days
- **Eid al-Adha**: real 29th Dhul Qi'dah (computed using drift from Eid al-Fitr), for ~13 days through 10th Dhul Hijja

Outside these windows, no network requests are made.

### Moon sighting drift

When the Ministry's announced Eid al-Fitr date differs from the algorithmic Umm al-Qura calendar (e.g. +1 day), the app saves this **drift** and automatically applies it to predict the Eid al-Adha date. This means:

1. Ministry announces Eid al-Fitr → app computes `drift = announced − algorithmic`
2. For Eid al-Adha (if not yet announced), the app uses `algorithmic_10_dhul_hijja + drift`
3. Once the actual Eid al-Adha date is announced and pushed to the JSON, it takes precedence

This way, the app can show the correct Eid al-Adha row even before the official announcement, based on the pattern observed at Eid al-Fitr. The same drift is computed from `ramadanStart` if `eidFitrDate` is not yet available.

---

## Data Source

All prayer times are sourced from the **Institut National de la Météorologie** (meteo.tn) via their public API:

- Prayer times endpoint: `https://www.meteo.tn/horaire_gouvernorat/{date}/{gouvernoratId}/{delegationId}/`
- Sunrise/sunset endpoint: `https://www.meteo.tn/lever_coucher_gouvernorat/{date}/{gouvernoratId}/{delegationId}/`

---

## Privacy

The Android app collects **no analytics and no device identifiers**. The only network request is an hourly check to GitHub Pages for official Ramadan/Eid date overrides, made only during a narrow window around moon-sighting dates (a few days per year). All prayer data is bundled in the APK. See [`docs/privacy-policy.html`](docs/privacy-policy.html) for the full privacy policy.

---

## License

ISC