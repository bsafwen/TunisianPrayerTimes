# MainScreen.kt Bug Audit

Reviewed: 18 April 2026  
File: `android-app/app/src/main/java/com/tunisianprayertimes/ui/MainScreen.kt`

---

## 1. Stale "next prayer" highlight when navigating dates — HIGH

**Lines:** ~1185–1207

```kotlin
val nextPrayerFromToday = remember(delegationId, jomoaaH, jomoaaM) {
    if (!isToday) return@remember null
    displayTimes?.nextPrayer(...)
}
```

`selectedDate` is **not** in the `remember` key list, but `isToday` and `displayTimes` (both used inside the block) depend on it. Navigate forward a day and back to today, and `nextPrayerFromToday` keeps the value captured on first composition — the wrong prayer row gets the green highlight. Same problem for `tomorrowFajr`.

---

## 2. Tomorrow's Fajr silently shown under today's date header — MEDIUM

**Lines:** ~1284–1291

```kotlin
val prayerTime = if (isToday && prayer == Prayer.FAJR && tomorrowFajr != null)
    tomorrowFajr
else
    displayTimes.allPrayers().find { it.prayer == prayer }
```

After Isha (when `nextPrayer` wraps to tomorrow), the Fajr row in the table is replaced with tomorrow's Fajr time, but the date navigation row still says "today". At 23:00 the user sees a Fajr time that doesn't belong to the displayed day.

---

## 3. Resume-time `scheduleAll` runs even while manual silence is active — HIGH

**Lines:** ~256–277

The `LaunchedEffect(refreshTick)` block re-schedules all auto-silence alarms on every resume with no check for `PrefsManager.isManualSilenceActive(context)`. This races with manual silence: rescheduling near-future prayer alarms while the user is intentionally in manual mode. The manual-silence-expired path also bumps `refreshTick++`, which in turn triggers `scheduleAll`.

---

## 4. Three separate `LaunchedEffect(refreshTick)` blocks — duplicated work — LOW

**Lines:** ~201, ~222–228, ~256–279

Three independent effects all read `manualSilenceActive`, `manualSilenceEndsAtMillis`, `isSilent`. Execution order of same-key effects is undefined per the Compose contract; whichever runs last wins. Easy to introduce a regression where one effect reads stale state another effect just changed.

---

## 5. Duration fields persist `1` whenever temporarily empty — MEDIUM

**Lines:** ~416–424

```kotlin
val totalMinutes = (value.toIntOrNull() ?: 0) * 60 + (manualDurationMinutes.toIntOrNull() ?: 0)
PrefsManager.setManualSilenceDurationMinutes(context, totalMinutes)
```

Combined with `setManualSilenceDurationMinutes(... .coerceAtLeast(1))` in `PrefsManager.kt`, every keystroke writes to prefs, including the transient `0` while the user is mid-edit. A user with 1h30 stored who clears the hours field to type a new value momentarily writes `30` to prefs. If the keyboard dismisses before the user finishes typing, the persisted value is wrong.

---

## 6. `delegationId` change resets all `PrayerRow` state — LOW

**Lines:** ~1295–1305

`key(delegationId) { PrayerRow(...) }` resets the inner `rememberSaveable` state on every delegation change. Since per-prayer prefs are global (not per-delegation), this causes a brief flicker of the "delay" and "duration" controls when switching delegations.

---

## 7. Date picker permits any date; navigation arrows don't — LOW

**Lines:** ~1462–1474 vs ~1265–1280

The arrows enforce `canGoBack`/`canGoForward` (CSV data exists for that month), but tapping the date label opens a `DatePickerDialog` with **no min/max bounds**. The user can pick a date with no CSV and just see "no prayer data".

---

## 8. Cross-validation reads from prefs instead of Compose state — LOW

**Lines:** ~1700–1710, ~1779–1786

The delay time picker validates against the end time by re-reading it from `PrefsManager.getFixedTimeHour(context, prayer)` instead of the local `fixedH`/`fixedM` state. Symmetrically the end-time picker re-reads `delayFixed*` from prefs. Mixing local Compose state with `PrefsManager.getX` for related fields is a footgun. Currently safe only because prefs is written synchronously before the picker opens.

---

## 9. `isFriday` and `hijriDate` are computed but never used — LOW

**Lines:** ~1112–1126

Dead values. `isFriday` was likely meant to gate showing the JOMOAA row (currently always shown regardless of weekday). Either the dead variable is wrong or the row visibility is wrong.

---

## 10. `NumberInput` triple-stacks `background` on the same shape — COSMETIC

**Lines:** ~1843–1903

The outer `Modifier` adds `.background(Color.White).then(Modifier.background(Color.White, ...))`, then the `decorationBox` `Box` adds another `.background(Color.White, ...).then(Modifier.background(GoldLight, ...))`. Four overlapping rectangles per text field — wasted overdraw, copy-paste leftover.

---

## 11. `OutsideTunisia` cancels alarms but doesn't update the auto-silence switch — MEDIUM

**Lines:** ~729–737

`LocationPickerCard.startLocationLookup` sets `disabledOutsideTunisia=true` and cancels alarms, but the `autoSilenceEnabled` switch still shows ON because it reflects `KEY_ENABLED`, not `disabledOutsideTunisia`. The user gets no UI signal that auto-silence is suppressed.

---

## 12. `ensureCallTrackingPermission` requested even when DND not granted — LOW

**Lines:** ~362–373

Toggling auto-silence ON immediately requests `READ_PHONE_STATE` even though `hasDnd && hasAlarm` may be false. The user gets prompted for one permission while the underlying feature can't even function yet.
