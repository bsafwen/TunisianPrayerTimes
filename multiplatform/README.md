# Tunisian Prayer Times — Compose Multiplatform (Desktop)

This module contains the Compose Multiplatform port targeting **Windows/macOS/Linux desktop**.

## Project Structure

```
multiplatform/
├── shared/                          # Shared Kotlin Multiplatform module
│   └── src/
│       ├── commonMain/kotlin/       # Pure Kotlin: models, parsers, compute logic, theme, strings
│       └── desktopMain/kotlin/      # Desktop actual implementations (prefs, file I/O, silence)
├── desktopApp/                      # Desktop application module
│   └── src/desktopMain/kotlin/      # Desktop Compose UI (Window, main screen)
├── build.gradle.kts                 # Root build
├── settings.gradle.kts              # Module includes
└── setup-data.sh                    # Links prayer time CSV data
```

## Quick Start

```bash
cd multiplatform

# Link the prayer data (CSV files + gouvernorats.json) from docs/
chmod +x setup-data.sh
./setup-data.sh

# Run the desktop app
./gradlew :desktopApp:run
```

## Building a Windows Installer

```bash
./gradlew :desktopApp:packageMsi    # .msi installer
./gradlew :desktopApp:packageExe    # .exe installer
```

## Architecture

### Common Code (`shared/commonMain`)
- **PrayerModels.kt** — `Prayer`, `PrayerTime`, `DayPrayerTimes`, config enums
- **SilenceAlarmComputer.kt** — Pure alarm time computation
- **RamadanDetector.kt** — Hijri calendar Ramadan detection
- **CsvParser.kt** — Platform-independent CSV parsing
- **GouvernoratJsonParser.kt** — Platform-independent JSON parsing
- **GouvernoratModels.kt** — `Gouvernorat`, `Delegation`, Haversine
- **ui/theme/Theme.kt** — Islamic teal/gold theme
- **ui/Strings.kt** — All Arabic UI strings

### Platform Abstractions (`expect`/`actual`)
- **PrayerDataLoader** — CSV file loading (desktop: filesystem)
- **GouvernoratLoader** — JSON loading (desktop: filesystem)
- **Preferences** — Settings persistence (desktop: `java.util.prefs`)
- **SilenceController** — Volume mute (desktop: OS commands)
- **TimerScheduler** — Timed callbacks (desktop: `ScheduledExecutorService`)

### Desktop App (`desktopApp`)
- Full Compose Desktop window with the same UI as the Android app
- RTL Arabic layout
- Inline delegation search (replaces Android bottom sheet)
- System volume mute/unmute via platform commands

## Adding Android Back

To keep the Android app using the shared module, add an `androidMain` source set
to `shared/build.gradle.kts` with `actual` implementations using Android APIs
(Context, SharedPreferences, AlarmManager, etc.), then update the existing
`android-app` to depend on `:shared`.
