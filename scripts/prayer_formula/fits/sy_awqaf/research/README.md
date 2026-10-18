# Syria: research tools (2026-10-18)

The tools behind `fits/sy_awqaf` ([record](../README-part.md)), kept to refresh its data when the ministry changes its
screens. They read and write the git-ignored `test-data-local/research-2026-10-18/SY/` (never committed); the rest of
that day's exploration is archived in `test-data-local/sessions/2026-10-09-to-18-prayer-beyond-tunisia/`.

| Tool | What it does |
|---|---|
| `regions_fetch.py` | Fetches the region files (`masjidak/region_<id>.json`, 761–870 on 2026-10-18) with `MANIFEST.tsv` (SHA-256, Last-Modified): a changed hash or date means the ministry rewrote a table. |
| `screens_sample.py` | Samples the mosque screens' configuration pages (`tv.masjidak.mow.gov.sy/<mosque id>`): each screen's region file (or `-` for aladhan's live API), town, address and name, into `masjidak/mosques.tsv`, which names fit.py's zones and finds its live towns. |
| `live_fetch.py` | Fetches what a live screen at each live town loads (`api.aladhan.com/v1/calendar/2024` at the town, no method) into `aladhan_live/`, which fit.py scores the live towns on. |
| `aladhan_model.py` | aladhan's PrayerTimes as plain functions with free settings (angles, horizon, Asr factor and server fraction, clock, tunes). |
| `layout_fit.py` | Per region file, every source year 2000–2032 in both row layouts against `aladhan_model`, with the point and Asr's fraction: how the table years and the north-east's dropped 29 February were found. |

After a refresh, run `fit.py`, then `scripts/places/match_named_places.py`.
