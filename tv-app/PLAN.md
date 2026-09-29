# Tunisian Prayer Times — Mosque TV App Plan

## Core Concept

An **offline-first Android TV app** that mosques install on a TV stick (e.g., Xiaomi Mi TV Stick, Amazon Fire TV). It shows prayer times for the mosque's delegation with zero cloud dependency. Prayer times come bundled from **meteo.tn** (already scraped in this repo). Mosques only configure **iqamah offsets** and optionally the mosque name.

---

## 1. Project Setup

- **Module:** `tv-app/` directory at repo root (standalone Android TV project)
- **Shared code:** Reuse `multiplatform/shared/` models and parsers (`PrayerModels`, `CsvParser`, `GouvernoratJsonParser`, `GouvernoratModels`, `RamadanDetector`)
- **App ID:** `com.tunisianprayertimes.tv`
- **App name:** "Tunisian Prayer Times" (same as android-app)
- **Min SDK:** 21 (Android TV Leanback), **Target SDK:** 36
- **Language:** Kotlin + Jetpack Compose for TV (`androidx.tv:tv-compose`)
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

### 3b. Main Display Screen (24/7 Kiosk Mode)

The «أفق» (Horizon) design, chosen from mockups on 2026-09-29. On a 960 × 540 dp canvas, right to left:

- **Header:** the mosque's name in Kufic with its place; in the middle a verse closed by the silver medallion of the Blue Qur'an of Kairouan: twelve verses on the prayer in turn, moving on at each adhan (the Jumu'a verse on Fridays, البقرة 184 in Ramadan; Qaloon, later Madani count); on the left the Hijri and Gregorian dates and the weather when online.
- **Hero:** the clock, and the countdown to the next adhan in stone gold with its iqamah; in Ramadan the countdown to iftar or imsak. «قريبًا» in the last five minutes.
- **Arcade:** the six times (Fajr, sunrise, Dhuhr or Jumu'a, Asr, Maghrib, Isha) in pointed arches like the courtyard of the Great Mosque of Kairouan; passed ones dimmed, the next one gold. After Isha, tomorrow's Fajr.
- **Ticker:** one short dhikr at a time, paged (never scrolling), with the mosque's written announcements between them, and a faint khatam frieze at both ends.
- **Sky:** behind it all, the sky of the day's prayer times (night, Fajr, sunrise, day, Asr, Maghrib, Isha), computed on the device; or the plain ground («مداد»), or the mosque's own images.

### 3c. Transition Screens (timed overlays)

| Event | Screen | Duration |
|---|---|---|
| Adhan time reached | The prayer's name in a mihrab, its time, the texts said with the muezzin | ~3 min |
| Iqamah countdown | mm:ss for the back of the hall, 24 studs going out, «الرجاء إغلاق الهاتف» | Until iqamah |
| Khutba | Black, one dim line | Until the Jumu'a iqamah |
| Iqamah reached | Pure black | The prayer's duration |
| After salah | The adhkar, one text at a time | As long as the texts need |
| Night | A dim clock and the next Fajr on black, moving every 5 minutes | Isha + 1 h (2 h in Ramadan) to Fajr − 30 min |
| Eid morning | «عيد مبارك», the greeting and the Eid prayer's time | Until Dhuhr |

## 4. Settings Screen

Accessible from main display via remote button:

- **Change delegation** (gouvernorat → delegation)
- **Edit iqamah offsets** per prayer
- **Edit mosque name**
- **Jomoaa/Friday settings** (fixed iqamah time, khutba start time)
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
- **Compose for TV** (`androidx.tv:tv-compose`) for D-pad navigation
- **Kiosk/launcher mode** — Option to set app as default launcher so TV boots directly into the app
- **Wake lock** — Keep screen always on (`FLAG_KEEP_SCREEN_ON`)
- **No touch input** — All navigation via TV remote (D-pad + OK + Back)
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

- **Build:** `./gradlew :tv-app:app:assembleRelease`
- **Distribution:** Google Play Store (Android TV category) — same developer account as android-app
- **Signing:** Reuse or create separate keystore
- **Update strategy:** Annual data update (new year's CSVs) shipped as app update

## 8. Phased Delivery

| Phase | Scope |
|---|---|
| **Phase 1 (MVP)** | Setup wizard + main display screen + iqamah config + adhan/iqamah transition screens + always-on kiosk mode |
| **Phase 2** | After-salah adhkar, Azkar ticker, Ramadan special theme, Friday/Jomoaa mode |
| **Phase 3** | Local media announcements, custom backgrounds |
| **Phase 4** | OTA data updates |
