# Plan: Whack-a-Mole Alarm Challenge

## Context

The alarm already has an extensible challenge system. The math challenge lives in
`WakeUpCheckGenerator.kt` and is validated in `WakeAlertActivity`. The Stop button is gated behind
`wakeUpCheckPassed`. We plug the game into exactly this gate.

---

## 1. Model: Challenge Type

**File:** `WakeIntents.kt`

Add a `WakeUpCheckType` sealed class (or enum):
- `MATH` — existing behavior
- `WHACK_A_MOLE(killCount: Int)` — new

Extend `WakeTriggerPayload` with a `checkType` field. Add intent extra constants for it.

---

## 2. Settings

**Files:** `PrayerWakeRepository.kt` / persistence model + Wake settings UI

- Add a `wakeUpCheckType` field to the persisted model (default: `MATH` to preserve existing behavior).
- Add a `whackAMoleKillCount` field (default: difficulty-derived).
- In the Wake alarm settings screen, add a segmented/radio selector: `Math Challenge | Whack-a-Mole`.
- When Whack-a-Mole is selected, show a kill-count picker (e.g. 5–20 moles).

---

## 3. Game Composable

**New file:** `wake/WhackAMoleGame.kt`

A self-contained Compose component:
- Grid of N holes (e.g. 3×3).
- Moles pop up randomly using `LaunchedEffect` coroutines with random delays.
- Each successful tap increments a local `killCount`.
- Exposes a callback `onCompleted()` triggered when the target kill count is reached.
- Moles that aren't tapped in time go back down (configurable timeout per mole, shorter on harder difficulties).

Difficulty mapping (reuses existing `MathDifficulty`):

| Difficulty   | Target kills | Mole visibility window |
|--------------|-------------|------------------------|
| Easy         | 5           | 2.0 s                  |
| Intermediate | 10          | 1.5 s                  |
| Hard         | 15          | 1.0 s                  |

---

## 4. Plug into `WakeAlertActivity`

**File:** `WakeAlertActivity.kt`

Where the math challenge composable is currently shown, replace with a `when (checkType)` branch:
- `MATH` → existing math UI (no change)
- `WHACK_A_MOLE` → `WhackAMoleGame(onCompleted = { wakeUpCheckPassed = true })`

No changes to the Stop button logic — it already reads `wakeUpCheckPassed`.

---

## 5. Notification Action

**File:** `WakePlaybackService.kt`

The notification "Stop" action is already hidden when a wake-up check is active. Keep this
behavior — the notification Stop remains hidden for any challenge type.

---

## 6. Extensibility Hook

No framework needed. Future games (Simon Says, Tap-the-pattern, etc.) are added by:
1. Adding a new case to `WakeUpCheckType`.
2. Creating a new `@Composable` game file.
3. Adding one branch in the `when` block in `WakeAlertActivity`.

---

## Files to Create / Modify

| File | Change |
|------|--------|
| `app/src/main/java/…/wake/WakeIntents.kt` | Add `WakeUpCheckType`, extend `WakeTriggerPayload` + extras constants |
| `app/src/main/java/…/wake/PrayerWakeRepository.kt` + persistence | Add `wakeUpCheckType` + `whackAMoleKillCount` fields |
| Wake settings UI (Compose screen) | Add game selector + kill count picker |
| `app/src/main/java/…/wake/WakeAlertActivity.kt` | Branch on `checkType` to show correct challenge UI |
| `app/src/main/java/…/wake/WhackAMoleGame.kt` | **New** — self-contained game composable |
| `app/src/main/java/…/wake/WakePlaybackService.kt` | Pass `checkType` through to payload (minor) |
