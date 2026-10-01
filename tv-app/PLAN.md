# Tunisian Prayer Times — Mosque TV App Plan

## Core Concept

An **offline-first Android TV app** that mosques install on a TV stick (e.g., Xiaomi Mi TV Stick, Amazon Fire TV). It shows prayer times for the mosque's delegation with zero cloud dependency. Prayer times come bundled from **meteo.tn** (already scraped in this repo). Mosques only configure **iqamah offsets** and optionally the mosque name.

---

## 1. Project Setup

- **Module:** `tv-app/` directory at repo root (standalone Android TV project)
- **Shared code:** Reuse `multiplatform/shared/` models and parsers (`PrayerModels`, `CsvParser`, `GouvernoratJsonParser`, `GouvernoratModels`, `RamadanDetector`)
- **App ID:** `com.tunisianprayertimes.tv`
- **App name:** "Tunisian Prayer Times" (same as android-app)
- **Min SDK:** 26, **Target SDK:** 36; lint's NewApi check is fatal in release builds
- **Language:** Kotlin + Jetpack Compose (plain Compose and material3; no `androidx.tv`, leanback or appcompat)
- **Locale:** Arabic RTL (matching android-app)

## 2. Data Architecture (100% Offline)

| Data | Source | Storage |
|---|---|---|
| Prayer times CSVs | Bundled in `assets/csv/` (same data from `docs/csv/`) | Read-only assets |
| `gouvernorats.json` | Bundled in `assets/` | Read-only assets |
| Iqamah config | User input during setup | `SharedPreferences` / DataStore |
| Mosque name (optional) | User input during setup | `SharedPreferences` / DataStore |
| Selected delegation | User choice during setup | `SharedPreferences` / DataStore |

**No network calls. No cloud. No API.** Data updates ship with app updates via Play Store.

## 3. App Flow

```
Install → First Launch Setup → Main Display (runs 24/7)
                ↕
         Settings (accessible via remote)
```

### 3a. First Launch Setup (Wizard — 3 screens)

1. **Select Gouvernorat** — List of 24 gouvernorats (Arabic names), D-pad navigable
2. **Select Delegation** — List of delegations within chosen gouvernorat
3. **Configure Iqamah** — For each of the 5 daily prayers (Fajr, Dhuhr, Asr, Maghrib, Isha):
   - Iqamah delay in minutes after adhan (e.g., +10min, +15min, +20min)
   - OR fixed iqamah time (HH:MM)
   - Friday/Jomoaa: separate Dhuhr iqamah option
4. **Mosque name** (optional text field) — displayed on screen header

A USB key whose settings file names the mosque's delegation (another TV's copy) is offered on the first step as «الإعداد من مفتاح USB»: its preview, then the whole TV set up at once.

### 3b. Main Display Screen (24/7 Kiosk Mode)

The «أفق» (Horizon) design, chosen from mockups on 2026-09-29. On a 960 × 540 dp canvas, right to left:

- **Header:** the mosque's name in Kufic with its place; in the middle a verse closed by the silver medallion of the Blue Qur'an of Kairouan: twelve verses on the prayer in turn, moving on at each adhan (the Jumu'a verse on Fridays, البقرة 184 in Ramadan; Qaloon, later Madani count), or the next verse that fits when the header is too narrow for it; on the left the Hijri and Gregorian dates and the weather when online. A long mosque name is set smaller, then ellipsized, so the dates are never cut.
- **Hero:** the clock, and the countdown to the next adhan in stone gold with its iqamah; in Ramadan the countdown to iftar or imsak. «قريبًا» in the last five minutes.
- **Arcade:** the six times (Fajr, sunrise, Dhuhr or Jumu'a, Asr, Maghrib, Isha) in pointed arches like the courtyard of the Great Mosque of Kairouan; passed ones dimmed, the next one gold. After Isha, tomorrow's Fajr.
- **Ticker:** one short dhikr of the day at a time, on one line, paged (never scrolling), with the mosque's written announcements between them, and a faint khatam frieze at both ends. A long text is set smaller or on two lines, then shown in turns; its lines never reach the arcade.
- **Sky:** behind it all, the sky of the day's prayer times (night, Fajr, sunrise, day, Asr, Maghrib, Isha), computed on the device; or the plain ground («مداد»), or the mosque's own images.

### 3c. Transition Screens (timed overlays)

| Event | Screen | Duration |
|---|---|---|
| Adhan time reached | The prayer's name in a mihrab, its time, and all at once what the listener says while the muezzin calls, in order (Muslim 385; Fajr's has one line more) | The mosque's minutes, 1-5, default 2; an iqamah set sooner waits for its end |
| Iqamah countdown | mm:ss for the back of the hall, 24 studs going out, «الرجاء إغلاق الهاتف» | Until iqamah |
| Khutba | Black, one dim line | From the Jumu'a adhan to its iqamah; with a khutba length set, only that long before the iqamah (the iqamah countdown before) |
| Iqamah reached | Pure black | The prayer's duration |
| After salah | The adhkar, one text at a time, then the announcements | As long as the texts need |
| Night | A dim clock and the next Fajr on black, moving every 5 minutes; in Ramadan the imsak countdown, before an Eid its prayer's time | Isha + 1 h (2 h in Ramadan) to Fajr − 30 min |
| Eid morning | «عيد مبارك», the greeting with its source and the Eid prayer's time | Until Dhuhr |

A running prayer only moves forward: once its adhan has passed it keeps the times it started with
until its adhkar end, so an iqamah raised during or after it (on the remote, the phone or a key), or
a clock put back a few minutes, never replays its countdown or black screen; before the iqamah (khutba
included), a new iqamah still ahead applies, and a prayer the settings no longer have (an Eid date
moved away, Jumu'a no longer held) ends at once (`ui/display/RunningPrayer.kt`). With an impossible clock no prayer runs.
Admin notices (a USB key, the clock, the Back hint) wait through the adhan, the countdown, the khutba,
the salah and the adhkar after it; only the copy's notice and its outcome show, in the wall's top bar
(in the top mark's place, low enough for an overscanning TV), outside the khutba and the salah. A key's offer hidden by a prayer screen or the clock page keeps its two minutes for the
wall.

A masjid that holds no Jumu'a keeps Dhuhr on Fridays, and one that holds no Eid prayer has none on
Eid (the greeting stays). The Eid prayer's time shows on its own line under the Hijri date from the Maghrib before it.

## 4. Settings Screen

Accessible from main display via remote button:

- **Change delegation** (gouvernorat → delegation)
- **Edit iqamah offsets** per prayer, beside the iqamah the wall uses today (marked when Ramadan's changes or a moved fixed time decide it); switch a rule between minutes and a fixed time; remove a prayer's Ramadan change
- **Edit mosque name**
- **Jomoaa/Friday settings** (fixed iqamah time, khutba length; whether the mosque holds Jumu'a and the Eid prayer)
- **Display preferences:**
  - 12h / 24h time format
  - Show/hide Azkar ticker
  - Azkar speed
  - Theme (dark/light — dark default for mosque screens)
- **Ramadan adjustments** (auto-detected, optional iqamah override)
- **Screen orientation** (landscape default, portrait option)
- **About / Version info**

## 5. Technical Implementation Details

### 5a. Android TV Specifics
- **Compose** with its own focus handling for D-pad navigation; the launcher banner is a vector (`res/drawable/tv_banner.xml`)
- **Kiosk/launcher mode** — Option to set app as default launcher so TV boots directly into the app
- **Wake lock** — Keep screen always on (`FLAG_KEEP_SCREEN_ON`)
- **No touch input** — All navigation via TV remote (D-pad + OK + Back). OK acts on a release whose press the control received, not within half a second of a page opening (no double-press slips); held on a − or + it repeats. An air mouse's clicks work too, and its button held 3 s on the wall opens settings (not during the prayer or the khutba, where only the Menu keys do)
- **Auto-start on boot** via `BroadcastReceiver` (same pattern as android-app's `BootReceiver`)

### 5b. Prayer Time Engine
- Reuse shared module's `CsvParser` to parse bundled CSVs
- Reuse `GouvernoratJsonParser` for location data
- Compute iqamah times: `adhan_time + delay_minutes` or fixed time
- Use `RamadanDetector` for Hijri date and Ramadan detection
- `Handler`/`CoroutineScope` for ticking clock and countdown updates

### 5c. Data Bundling
- Copy `docs/csv/` and `docs/gouvernorats.json` into `tv-app/app/src/main/assets/` at build time
- Add Gradle task or shell script (similar to `multiplatform/setup-data.sh`)

### 5d. Theme & Design
- The «مداد» palette (ink, ivory, stone gold for the next prayer only, silver for the star), dark always; pure black during the prayer
- Three bundled fonts (no network): Readex Pro for the interface and numbers, Reem Kufi for single words, Amiri for sacred text
- Ornament from Kairouan only, and never behind a number: the arcade arch, the mihrab, the verse medallion, the khatam
- Large sizes for the back of the hall, RTL throughout, numbers left to right in fixed-width cells
- Themes: «أفق» (the sky) and «مداد» (plain ground); the admin's own background images replace the sky

## 6. Differences from Mawaqit

| Feature | Mawaqit TV | This App |
|---|---|---|
| Prayer times source | Mosque admin uploads manually | Bundled from meteo.tn (auto) |
| Cloud dependency | Required (fetches from server) | None (fully offline) |
| Setup complexity | Mosque ID + verified account | Just pick delegation + set iqamah |
| Coverage | Global | Tunisia only (24 gouvernorats) |
| Iqamah config | Cloud dashboard | On-device settings |
| Custom announcements | Yes (images/videos from cloud) | Phase 2 (local media) |
| Internet required | Yes | No |

## 7. Build & Distribution

- **Builds:** two flavors, `play` (Google Play, updated by Play) and `github` (boxes without Play Store, updates itself from GitHub releases); release builds are minified by R8 with no keep rule for the app's own code
- **CI:** TV Tests (unit tests of both flavors and of the shared module, the minified releases with lintVital, the Play build's policy checks, no storage permission, the dashboard's scripts parsed by node)
- **Release:** the TV Release workflow, dispatched from `main` only: it checks that the tag is new and the version code above every published TV release, runs TV Tests, builds and signs with no write token, checks the GitHub APK's certificate against `tv-app/release-certificate.sha256` (to commit before the first release: the workflow's error prints the fingerprint), keeps the R8 mappings (the GitHub one as a release asset), then publishes in a job that builds nothing
- **Distribution:** Google Play Store (Android TV category) — same developer account as android-app; privacy policy: `docs/tv-privacy-policy.html` (published by Pages), to link in the listing and match in the Data safety form
- **Signing:** the phone app's upload key (repository secrets), pinned for the GitHub build
- **Backup:** off (`allowBackup="false"` and data extraction rules): a box's clock correction must not reach another box

## 8. Phased Delivery

| Phase | Scope |
|---|---|
| **Phase 1 (MVP)** | Setup wizard + main display screen + iqamah config + adhan/iqamah transition screens + always-on kiosk mode |
| **Phase 2** | After-salah adhkar, Azkar ticker, Ramadan special theme, Friday/Jomoaa mode |
| **Phase 3** | Local media announcements, custom backgrounds |
| **Phase 4** | OTA data updates |
