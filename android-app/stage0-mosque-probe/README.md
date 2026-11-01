# Mosque silencing: Stage 0 diagnostic

This is an independent, debug-only Android application (`com.tunisianprayertimes.stage0`). It does not replace or integrate with TunisianPrayerTimes. The 2026-10-31 revision prototypes **TIME_ONLY** and **TIME_AND_MOSQUE**, with mosque location work gated to supplied existing-window intervals. The [current Stage 0 report](../MOSQUE_SILENCING_STAGE0_REPORT_2026-10-31.md) distinguishes model tests, actual Android effects and pending field evidence. The [October 28 report](../MOSQUE_SILENCING_STAGE0_REPORT_2026-10-28.md) describes the earlier broader prototype.

The owner's 2026-10-28 direction is that Tunisia polygons will be available later. Three exact existing polygons are test fixtures; catalog readiness and shipping are outside this diagnostic's decision.

## Contents

- `model/`: classifier, presence accumulator, two-mode policy and `WindowPresence`/`WindowScope`. The window layer rejects out-of-window evaluation, clips deadlines, handles overlap/adjoining intervals, clears presence across gaps and retains occurrence dismissals. The accumulator is a subordinate logical component, not an independently authorized location mode. The time-only bridge is an explicit input; the production scheduler/wake policy is not wired into this probe.
- `src/test/`: 63 JUnit checks, including three existing Tunisia shapes, 18 centre/accuracy combinations and 17 window-policy cases. The polygon fixture is a JVM test resource outside the runtime APK.
- `dnd/`: explicit temporary rules with a real configuration activity, per-test ownership journal, merge/release observations and scoped cleanup. It never writes the ringer or global interruption filter. Rule and ringer observations must settle before a baseline is compared.
- `DndSessionProbe`: a separate durable journal for actual Modes UI, permission-loss, Doze and reboot experiments. A DUMP-protected command receiver accepts `PREPARE`, `OBSERVE`, `REPEAT_TRUE` and `FINISH`. It arms a session-specific expiry before activation; the 30–300-second deadline is logical, not a delivery guarantee. Observed capability loss is latched locally, and ordinary TRUE never recreates/re-enables a removed or disabled rule. The post-unlock boot receiver removes stale DND ownership only; it never starts location observation.
- `execution/`: `WindowAuthority` supplies finite accelerated schedule fixtures without reading/changing production prayer preferences. Every service/provider/registration/fix route requires an active combined-mode window and current token. Provider durations, geofence lifetimes and release deadlines are clipped to its end. Real boundary alarms, cleanup-only in-process end timers, immutable identities and generation guards handle native effects. One fixture lasts at most five minutes; a service segment is at most 120 seconds and can continue only through currently overlapping/adjoining eligible windows.
- `ProbeActivity`: deliberate diagnostic controls, with click sounds and haptics disabled. Notifications have no sound/vibration. There is no media playback or vibration test.

Real location sampling here establishes provider/route availability at the phone's current undisclosed position. A temporary initial-inside circle is not mosque geometry or an observed physical arrival/departure. Coordinates are used transiently to register that circle and are not written to the diagnostic logs.

The model and native execution components remain separate: real GPS is not connected to a production prayer policy or mosque DND decision. The optional `window-dnd` command deliberately assumes synthetic presence in one active test window and verifies scoped DND expiry; it is not an indoor-detection pass. Overlap/gap native checks measure resource lifetime, while presence continuity/dismissal are tested by the pure model.

## Reproduce the build

Use JDK 21 and the installed Android SDK. From the repository root on this machine:

```powershell
$env:JAVA_HOME='C:\Users\barou\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2'
$env:ANDROID_HOME='C:\Users\barou\AppData\Local\Android\Sdk'
.\android-app\gradlew.bat -p android-app\stage0-mosque-probe --offline --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --console=plain
```

The standalone project pins cached dependency versions used by the checkout. No release variant is enabled. HTTP/HTTPS Gradle proxy properties point to the required machine proxy; the recorded builds ran offline. Do not copy the production application's credentials or local-properties file into this project.

## Physical checks and cleanup

Rediscover the intended device and use an explicit serial for each command. Install only this independent probe and its test APK. Grant its location/DND/exact-alarm access for the authorized check. Leave the production app, its data, existing Android Studio emulators and unrelated rules untouched.

Run the AndroidJUnitRunner for ordinary checks. The `prepareJournalForNextLaunchRecovery` setup test is skipped unless explicitly invoked with `-e leaveDndRecoveryFixture true`; it deliberately leaves one probe-owned NONE rule to measure force-stop and next-launch cleanup. It must be followed promptly by launching the probe's recovery command and verifying the journal/rule removal. Synthetic `SOURCE_USER_ACTION` observations do not prove real system Modes UI snooze behavior.

The focused window suite selects `execution.WindowAuthorityTest`, `execution.ExecutionJournalTest` and `execution.ExecutionServiceTest` (14 checks). Some explicitly install state fixtures without arming a system alarm; those guard tests are labelled separately from host-recorded real boundary deliveries. The visible-service check asserts actual window-end completion/provider removal rather than requiring one particular cleanup actor.

Deliberate Activity commands are `window` (extras: `delay_ms`, `window_ms`, optional `second_ms`, `gap_ms`, `mode`), `window-stop`, `window-dnd` and `window-cleanup`. `mode` accepts exactly `TIME_ONLY` or `TIME_AND_MOSQUE`; unsupported input invalidates the old schedule. Recheck/temporary-circle commands reject outside the active window. Scheduling future intervals does not itself request location. The quiet notification's stop action dismisses current supporting occurrences. After a gap, old service/geofence/fix tokens cannot supply new authority.

The ordinary probe holds no active DND rule after its tests. `window-cleanup` invalidates the test schedule first, cancels its boundary alarms/end timers and location effects, and finishes only the matching synthetic DND session. `cleanup` also wipes the bounded execution diagnostics and recovers the historical short-test journal. A separately created raw `DndSessionProbe` journal requires explicit `com.tunisianprayertimes.stage0.dnd.FINISH` to `.dnd.DndSessionCommandReceiver`; `OBSERVE` does not remove platform rules. Show/status launches do not manufacture recovery results; read private journals before explicit recovery/cleanup. Uninstalling both temporary packages removes their permissions/private data. Compare current DND/ringer/vibrator state and unrelated rule definitions with baseline; never force global state to pass.

Earlier evidence is under `work/mosques/silencing-stage0-20261028/` and `work/mosques/silencing-stage0-device-followup-20261028/`. The pre-revision source archive, builds, failed assertions, final checks and native traces for this revision are under `work/mosques/silencing-stage0-window-20261031/`. Real-mosque field validation remains pending at the owner's request; short samples cannot establish sustained battery cost or a production support envelope.
