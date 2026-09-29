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

| Tier | What it takes | Boot | After Home / another app | After a crash |
|---|---|---|---|---|
| Home screen | Kiosk page → "جعل التطبيق الشاشة الرئيسية", then pick the app | System starts it | Immediately | Yes |
| Device owner | adb provisioning on a freshly reset box (below) | Yes, pinned with lock task | Home is disabled | Yes |
| Display over other apps | Kiosk page button, or the adb command below | Yes | Within ~8 min (watchdog) | Yes |
| Android 8 / 9 | Nothing | Yes | Within ~8 min | Yes |
| None | — | Stays on the launcher | No | No |

### Device families

- **AOSP boxes** (most cheap Android TV boxes): all tiers work. Recommended.
- **Google TV / Chromecast with Google TV**: a third-party home screen may be refused. Use "display
  over other apps". Energy saver must be set to "Never" (below).
- **Xiaomi Mi Box / TV Stick**: "display over other apps" through adb; Home mode usually works.
- **Amazon Fire TV**: Amazon blocks apps from starting at boot. Not recommended.

## adb one-liners

Connect a computer to the box once (USB debugging or network debugging from Developer options).
Replace the package with `com.tunisianprayertimes.tv` for the Play Store build.

```bash
# Display over other apps (when the box has no page for it)
adb shell appops set com.tunisianprayertimes.tv SYSTEM_ALERT_WINDOW allow

# Never go to sleep: Android TV 11+ "Energy saver"
adb shell settings put secure attentive_timeout -1

# Stay awake while plugged in (Developer options)
adb shell settings put global stay_on_while_plugged_in 7

# Device owner, only on a box reset to factory settings with no Google account yet
adb shell dpm set-device-owner com.tunisianprayertimes.tv/com.tunisianprayertimes.tv.kiosk.TvDeviceAdminReceiver

# Google TV: stop the stock launcher so the app stays the home screen (undo with pm enable)
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
```

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

## Kiosk log

`files/kiosk-events.log` in the app's storage keeps the last 500 events (boot, auto-start result,
crashes, safe mode, watchdog, sleeps, screen off/on, maintenance restarts, home mode). The kiosk
page shows the latest 50. Nothing is sent anywhere.
