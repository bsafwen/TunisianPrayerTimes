# Mosque silencing: Stage 0 diagnostic

This is an independent, debug-only Android application (`com.tunisianprayertimes.stage0`). It does not replace or integrate with TunisianPrayerTimes. It prototypes the disputed presence bounds and Android DND/location operations so the [Stage 0 report](../MOSQUE_SILENCING_STAGE0_REPORT_2026-10-28.md) can give an evidence-based go/no-go before later implementation.

The owner's 2026-10-28 direction is that Tunisia polygons will be available later. Three exact existing polygons are test fixtures; catalog readiness and shipping are outside this diagnostic's decision.

## Contents

- `model/`: pure Kotlin classifier, monotonic presence state machine, mode truth tables and retained-owner decisions. Synthetic time exercises long visits without waiting hours. This is not production prayer-scheduler/TIME_ONLY parity wiring.
- `src/test/`: 46 JUnit checks, including three existing Tunisia shapes and 18 centre/accuracy combinations. The JSON polygon fixture is a JVM test resource and is not included in the probe APK.
- `dnd/`: explicit temporary rules with a real configuration activity, per-test ownership journal, merge/release observations and scoped cleanup. It never writes the ringer or global interruption filter. Rule and ringer observations must settle before a baseline is compared.
- `DndSessionProbe`: a separate durable journal for actual Modes UI, permission-loss, Doze and reboot experiments. A DUMP-protected command receiver accepts `PREPARE`, `OBSERVE`, `REPEAT_TRUE` and `FINISH`. It arms a session-specific expiry before activation; the 30–300-second deadline is logical, not a delivery guarantee. Observed capability loss is latched locally, and ordinary TRUE never recreates/re-enables a removed or disabled rule. The post-unlock boot receiver removes stale DND ownership only; it never starts location observation.
- `execution/`: finite location foreground service, actual exact-alarm and Play-services geofence callbacks, per-session immutable alarms, generation checks and redacted local diagnostics. Sessions are at most 120 seconds; a manually registered test geofence expires within five minutes and starts at most one session.
- `ProbeActivity`: deliberate diagnostic controls, with click sounds and haptics disabled. Notifications have no sound/vibration. There is no media playback or vibration test.

Real location sampling here establishes provider/route availability at the phone's current undisclosed position. A temporary initial-inside circle is not mosque geometry or an observed physical arrival/departure. Coordinates are used transiently to register that circle and are not written to the diagnostic logs.

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

The ordinary probe holds no active DND rule after its tests. The Activity cleanup command stops observation, cancels its start/release alarms, removes its geofence, and removes its short-test DND journal. The durable `DndSessionProbe` journal is deliberately separate: send `com.tunisianprayertimes.stage0.dnd.FINISH` explicitly to `.dnd.DndSessionCommandReceiver` and verify its scoped cleanup before uninstalling. `OBSERVE` does not remove platform rules, so it cannot accidentally manufacture a timeout or reboot pass. Uninstalling the temporary diagnostic packages after evidence retrieval removes their permissions and private data. Always compare DND/ringer/vibrator state and unrelated rule definitions with the recorded baseline; never force global state to match a test expectation.

Reports and raw measurements for the 2026-10-28 session are under `work/mosques/silencing-stage0-20261028/` at the repository root. They include failed attempts and the later corrected rerun. Real-mosque field validation is pending at the owner's request; short powered-device samples cannot establish battery cost or a production support envelope.
