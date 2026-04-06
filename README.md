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

## Data Source

All prayer times are sourced from the **Institut National de la Météorologie** (meteo.tn) via their public API:

- Prayer times endpoint: `https://www.meteo.tn/horaire_gouvernorat/{date}/{gouvernoratId}/{delegationId}/`
- Sunrise/sunset endpoint: `https://www.meteo.tn/lever_coucher_gouvernorat/{date}/{gouvernoratId}/{delegationId}/`

---

## Privacy

The Android app collects **no analytics, no device identifiers, and makes no network requests**. All prayer data is bundled in the APK. See [`docs/privacy-policy.html`](docs/privacy-policy.html) for the full privacy policy.

---

## License

ISC