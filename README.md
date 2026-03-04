# 🕌 Tunisian Prayer Times

Fetches daily prayer times for **every delegation in Tunisia** from the [Institut National de la Météorologie](https://www.meteo.tn), stores them as CSV files compatible with [mawaqit.net](https://mawaqit.net), and serves them through a static web app.

🔗 **Live site:** hosted on GitHub Pages (`docs/`)

---

## Features

| Feature | Description |
|---------|-------------|
| **Data ingestion** | Scrapes Fajr, Sunrise, Dhuhr, Asr, Maghrib & Isha for all 24 gouvernorats and their delegations |
| **CSV export** | Generates per-month CSV files in Mawaqit format, downloadable as a ZIP |
| **Prayer calendar** | Interactive monthly calendar view in the browser |
| **Mawaqit push** | Push prayer times directly to mawaqit.net from the browser via a Cloudflare Worker proxy |
| **Incremental updates** | Only fetches missing days; supports partial-year and repair modes |

---

## Project Structure

```
├── src/                  # Ingestion scripts (TypeScript / Bun)
│   ├── index.ts          # Main entry — orchestrates fetching & CSV generation
│   ├── api.ts            # meteo.tn API client
│   ├── csv.ts            # CSV read/write helpers
│   ├── config.ts         # Runtime configuration (env vars)
│   └── types.ts          # Shared type definitions
├── docs/                 # GitHub Pages static site
│   ├── index.html        # Arabic RTL web app
│   ├── app.js            # Delegation search, download, calendar logic
│   ├── mawaqit-push.js   # Push-to-mawaqit UI logic
│   ├── style.css         # Styles
│   ├── gouvernorats.json # Delegation list (Arabic + French + English)
│   └── csv/              # Generated prayer-time CSVs (per delegation/year/month)
├── worker/               # Cloudflare Worker — CORS proxy for mawaqit.net
│   ├── index.js          # Worker source
│   ├── wrangler.toml     # Wrangler config
│   └── README.md         # Worker-specific docs
├── gouvernorats.json     # Master list of gouvernorats & delegations
├── package.json
└── tsconfig.json
```

---

## Getting Started

### Prerequisites

- [Bun](https://bun.sh/) (recommended) or Node.js ≥ 18
- npm or bun for dependency management

### Install

```bash
npm install
# or
bun install
```

### Ingest Prayer Times

```bash
# Fetch the current year for all delegations
bun src/index.ts

# Or use the npm script
npm run ingest
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

### Clean Generated Data

```bash
npm run clean
```

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

## Cloudflare Worker (Mawaqit Proxy)

The `worker/` directory contains a Cloudflare Worker that proxies requests to mawaqit.net, avoiding CORS issues for browser-based uploads. See [worker/README.md](worker/README.md) for deployment instructions.

---

## Data Source

All prayer times are sourced from the **Institut National de la Météorologie** (meteo.tn) via their public API:

- Prayer times endpoint: `https://www.meteo.tn/horaire_gouvernorat/{date}/{gouvernoratId}/{delegationId}/`
- Sunrise/sunset endpoint: `https://www.meteo.tn/lever_coucher_gouvernorat/{date}/{gouvernoratId}/{delegationId}/`

---

## License

ISC