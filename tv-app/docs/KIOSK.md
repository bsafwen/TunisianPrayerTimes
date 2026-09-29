# Keeping the mosque screen on 24/7

The app starts by itself, stays in front, comes back after a crash and restarts itself once a night.
How much of that a box allows depends on the box. **Settings → التشغيل الدائم للشاشة** (the kiosk
page) shows what this box allows, what went wrong recently, and how to fix it. Everything works
without internet.

Open settings from the display with any remote: **hold OK for 3 seconds**, press **OK five times
quickly**, or press Menu / Settings / Info if the remote has one. Back never leaves the app.

## Auto-start tiers

Android 10 and later block an app from starting itself in the background. The app uses the
strongest tier the box allows:

| Tier | What it takes | Boot | After Home / another app | After standby | After a crash |
|---|---|---|---|---|---|
| Home screen | Kiosk page → "جعل التطبيق الشاشة الرئيسية", then pick the app (not on Fire TV) | System starts it | Immediately | Immediately | Yes |
| Device owner | adb provisioning on a freshly reset box (below; not on Fire TV) | Yes, pinned with lock task | Home is disabled | Immediately | Yes |
| Quick start (GitHub build) | One adb grant, then kiosk page → "تفعيل البدء السريع" | As soon as the system is up | Home: after 1 min (at once in the 3 min after boot); another app or the box's settings: within ~8 min (watchdog) | Immediately | Yes |
| Display over other apps | Kiosk page button, or the adb command below | Yes | Within ~8 min (watchdog) | After 1 s if the app was running, else ≤ 8 min | Yes |
| Android 8 / 9 (Fire OS 7) | Nothing | Yes | Within ~8 min | After 1 s if the app was running, else ≤ 8 min | Yes |
| None | — | Stays on the launcher | No | No | No |

Whatever the tier, open the app once after installing it: until then Android sends it no boot notice.

### Device families

- **AOSP boxes** (most cheap Android TV boxes): all tiers work. Recommended.
- **Google TV / Chromecast with Google TV**: a third-party home screen may be refused. Use "display
  over other apps". Energy saver must be set to "Never" (below).
- **Xiaomi Mi Box / TV Stick**: "display over other apps" through adb; Home mode usually works.
- **Amazon Fire TV** (GitHub build; no Play Store). The app can't be Fire TV's home screen (Fire OS puts
  Amazon's back), so:
  - **Fire OS 7** (Android 9: Stick Lite, Stick 3rd gen, 4K Max 1st gen, Cube 2nd/3rd gen) starts the app at boot
    with nothing to set.
  - **Fire OS 8+** (Android 11: Stick 4K / 4K Max 2nd gen 2023, 4K Plus; Fire OS 14/16 TVs) blocks starts from the
    background: grant the two permissions below once with adb, then turn on quick start from the kiosk page.
  - **Quick start** is an accessibility service in the GitHub build only (Google Play forbids it; the Amazon
    Appstore forbids auto-launch too). The system binds it as soon as the box is up, without waiting for the boot
    broadcast (apps at default priority get that one 1-2 minutes after boot on Fire TV; this app's receiver has the
    highest priority, and quick start does not wait for it at all), and lets it start the display. It also puts the
    display back when Amazon's home appears: at once in the first 3 minutes after boot and in the minute after a
    wake from standby, otherwise one minute after Home if Amazon's home is still in front (from another app or the
    box's settings the watchdog brings it back within ~8 min), and after the app's process died on screen. To work
    in Fire TV's settings, use the app's settings → "الخروج إلى إعدادات الجهاز": the display stays away 30 minutes. It reads no screen content. At most one start every 5 s and six in 10 minutes. The kiosk page
    says when it is on but the box does not run it.
  - **Not possible on Fire TV**: being the home screen (Fire OS puts Amazon's back) and device owner (the box
    needs an Amazon account).
  - adb must come from **another device** (Fire OS blocked apps from using the box's own adb in Feb 2024): a
    computer, a phone with an adb app, or a second Fire TV pointed at this one's IP.
  - Fire TV's settings have no page for it: `WRITE_SECURE_SETTINGS` (granted with adb) lets the kiosk page turn it
    on, and turn it back on when the app next opens after a **force stop**, which removes it. A force stop also
    stops boot starts until someone opens the app: never force-stop it.
  - **Vega OS** devices (Fire TV Stick 4K Select 2025, Fire TV Stick HD 2026) run no Android apps at all.

## adb one-liners

Connect a computer to the box once (USB debugging or network debugging from Developer options).
The Play build's package is `com.tunisianprayertimes.tv`; the GitHub build (for boxes without Play
Store, self-updating) is `com.tunisianprayertimes.tv.github`. Install only one of them on a box.

```bash
# Display over other apps (when the box has no page for it)
adb shell appops set com.tunisianprayertimes.tv SYSTEM_ALERT_WINDOW allow

# Never go to sleep: Android TV 11+ "Energy saver"
adb shell settings put secure attentive_timeout -1

# Stay awake while plugged in (Developer options)
adb shell settings put global stay_on_while_plugged_in 7

# Device owner, only on a box reset to factory settings with no Google account yet
adb shell dpm set-device-owner com.tunisianprayertimes.tv/com.tunisianprayertimes.tv.kiosk.TvDeviceAdminReceiver

# GitHub build: let it install its own updates (Android 12+ then installs them without a dialog)
adb shell appops set com.tunisianprayertimes.tv.github REQUEST_INSTALL_PACKAGES allow

# Fire TV (Fire OS 8+): once, from a computer (Developer options: About → click the name 7 times; ADB debugging on)
adb connect <fire-tv-ip>:5555
adb shell pm grant com.tunisianprayertimes.tv.github android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.tunisianprayertimes.tv.github SYSTEM_ALERT_WINDOW allow
# then kiosk page → "تفعيل البدء السريع" (the same button turns it off) and "إيقاف نوم Fire TV".
# The button adds the service to the box's list; the direct command below REPLACES that list, so only use it when
# 'adb shell settings get secure enabled_accessibility_services' prints null (or append after a ':'):
adb shell settings put secure enabled_accessibility_services com.tunisianprayertimes.tv.github/com.tunisianprayertimes.tv.kiosk.KioskAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell settings put secure sleep_timeout 0
adb shell settings put system screen_off_timeout 2147460000    # screensaver: Never
adb shell appops set com.tunisianprayertimes.tv.github REQUEST_INSTALL_PACKAGES allow    # self-updates
adb shell dumpsys deviceidle whitelist +com.tunisianprayertimes.tv.github    # optional
# see which screens the quick-start service notices (Amazon's names are undocumented); also once per boot in the
# kiosk log as QUICK_START homes=[…] seen=[…]
adb logcat -s QuickStart

# Google TV: stop the stock launcher so the app stays the home screen (undo with pm enable)
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
```

## Fire TV habits that fight a wall display

- **Sleep** after 20 minutes without a key press (`secure sleep_timeout`): the kiosk page turns it off. The display's
  keep-screen-on flag also holds it off while the display is in front.
- **"Still Watching?"** after 4 hours without a remote key: turn it off in Settings → Preferences → Data Monitoring.
  Nothing can do it for you.
- **Screensaver** after 5 minutes when the display is not in front: Settings → Display & Sounds → Screensaver →
  Start Time → Never (or the adb line above).
- **Featured Content** autoplays trailers with sound on Amazon's home at every boot: Settings → Preferences →
  Featured Content, both autoplay switches off.
- **Profiles**: use a single profile without a PIN. A "Who's watching?" picker or a Kids profile (another Android
  user) sits in front.
- **"We cannot detect your remote"** can block the screen at boot: keep the paired remote, with batteries, near the TV.
- **Wake from standby** always shows Amazon's home: quick start puts the display back at once; without it, the
  display returns a second after the screen comes on if the app was still running. With HDMI-CEC on, switching the
  TV off may put the stick to standby too.
- **Boot timing**: the kiosk page shows how many seconds the display took after the last boot
  (`BOOT_TIMING screen=… service=… receiver=…` in the kiosk log).

## Sleep and the TV set itself

An app cannot stop Google TV's Energy saver, the 4-hour auto power-off of some TV sets, or eco
timers in the panel's own menu. The app detects sleeps afterwards and lists them on the kiosk
page ("نام الجهاز"). Fix them in the box's settings (Energy saver → Never) and in the TV set's menu
(auto power-off, eco mode, no-signal standby).

## Self-healing

- **Crash**: recorded in the kiosk log and the display is started again after 3 seconds.
  Three crashes within 10 minutes start safe mode (default theme, no custom backgrounds or
  announcements) for 30 minutes; after six the app stops restarting itself and the watchdog tries
  again every few minutes.
- **Watchdog**: every 5 minutes, if the display left the screen more than 3 minutes ago, it is
  brought back. "الخروج إلى إعدادات الجهاز" in settings gives the admin 30 minutes; opening a
  system page from the app gives 10.
- **Nightly restart**: after 20 hours of running, the app restarts itself between 90 minutes after
  the Isha iqamah (so after tarawih) and one hour before Fajr, never during a prayer screen.
- **Burn-in**: the whole screen drifts by a few pixels every 6 minutes.

## The clock

Prayer times depend only on the instant, never on the box's zone or language: every screen shows
Tunisia's time (Africa/Tunis) whatever the box says. What can be wrong is the instant, after a power
cut on a box without a clock battery, or when someone set the clock by hand while the box's zone was
not Tunisia's (the wall is then off by the zones' difference). The app checks the instant, not the zone:

- **Online**, it asks the network for the time at start, when the network comes back, after a clock
  change and every few hours: the system's own network time on Android 13+, otherwise the `Date` of
  one HTTPS `HEAD` request to Open-Meteo (the weather's server; never over plain HTTP, where anyone
  on the network could send a date). A time within 2 minutes confirms the screen's time; further
  off, it corrects it. With a system clock years off, the HTTPS check fails below Android 13 and the
  admin or the phone sets the time instead.
- **Offline**, on a box whose zone reads another time than Tunisia's, the time is shown from the
  instant but not confirmed: a quiet line at the top of the wall says so («الوقت غير مؤكَّد»). The
  admin is asked once, when at the remote (on opening the settings, after onboarding, or after a
  clock or zone change): «كم الساعة الآن في تونس؟» offers the screen's time and the device clock read
  as Tunisia's time (right when someone set it by hand to their watch), another time on steppers,
  the phone, or «لاحقًا». The settings' «الساعة» section shows the same, and the phone sets it from
  the dashboard («اضبط الشاشة على وقت هاتفي»). The answer is kept as a correction of the box's
  clock until that clock is changed. A clock that cannot be right (before September 2026, or hours
  behind the last good time) replaces the prayer times with «ساعة الجهاز غير صحيحة» until it is set.
- **A change of the system clock** (the box's settings, the network, adb) reaches the app even when
  the display is not running (a receiver of the system's time-set broadcast): the correction and the
  confirmation are dropped and the time is checked again. A change of zone moves no instant and is
  only logged.
- **Device-owner boxes** (Android 9+; provisioned with the adb line above) are also aligned after
  each confirmation: the system zone becomes Africa/Tunis (the automatic zone is turned off first:
  Android refuses otherwise), and the system clock is set to the confirmed time when it is more than
  a minute off, so HTTPS, the box's own screens and the logs are right too. The system clock is left
  alone while its automatic time is on: the network keeps it then. Other boxes keep their system
  clock and zone; the app's correction covers the prayer times.
- **Kiosk log**: `CLOCK_SET` when the system clock was set (`tunis=` the new clock in Tunisia's
  time, `zone=`, `autoTime=on|off|?`, and `own` when the app set it), `ZONE_SET` when the zone
  changed (`zone=`, `tunisTime=` whether it reads Tunisia's time), `CLOCK_CONFIRMED` when the time
  was confirmed or corrected (`source=NETWORK|ADMIN|PHONE`, and `moved=+3600s` when it moved).

## Kiosk log

`files/kiosk-events.log` in the app's storage keeps the last 500 events (boot, auto-start result,
crashes, safe mode, watchdog, sleeps, screen off/on, maintenance restarts, home mode, clock and zone
changes). The kiosk page shows the latest 50. Nothing is sent anywhere.
