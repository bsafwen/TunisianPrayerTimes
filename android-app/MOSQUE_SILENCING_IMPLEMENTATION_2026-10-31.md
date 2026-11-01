# Mosque-aware silencing implementation and test handoff

Date: 2026-10-31. **Owner decision: GO for implementation. Production source is integrated; device, field, battery and release acceptance follow implementation.** The historical Stage 0 NO-GO is preserved as a measurement assessment, rather than an authorization gate. The owner expressly requested implementation first and testing afterward.

## Delivered behavior

The prayer settings sheet now opens **طريقة الإسكات التلقائي**. It offers exactly two modes: **حسب الوقت** and **حسب الوقت ووجودك داخل المسجد**. Missing preferences retain TIME_ONLY; an explicitly invalid value is inactive. No mosque-only mode, preparation lead-in, presence-only DND request or mosque location bridge was added.

TIME_AND_MOSQUE uses the real `SilenceScheduler` planner, enabled prayers and occurrence dismissals. It retains the existing prayer times, offsets, delayed starts, Ramadan/Jumu'a/Eid policy, overnight periods and place time zone. Only a currently containing, non-dismissed existing window authorizes acquisition, fix evaluation or mosque geofences. Existing travel updates stay independent. TIME_ONLY keeps its existing already-active 45-minute bridge and wake/awake-check policy; that bridge never authorizes mosque location.

The production presence engine validates local polygons, holes and multipart topology. Entry requires fresh, distinct, non-mock fixes whose accuracy disk fits inside a saved area, with 15 seconds of strict entry evidence within a 90-second acquisition episode. Provisional bounds remain 30 m strict accuracy, 100 m holding accuracy, 20 seconds of strict outside evidence, 5 minutes without a usable observation and 15 minutes without strict inside support. All deadlines are clipped by actual supporting windows. Confirmed holding uses a 60-second observation cadence, with high-accuracy checks for acquisition, uncertainty/exit and stale support. These are implemented parameters, not newly measured indoor/battery results.

`MosqueObservationService` is a typed location foreground service with a quiet notification and a skip-current-window action. A visible activity, the real prayer start alarm, or a valid in-window GMS transition can request a start. A boot callback, ordinary replan or periodic worker cannot start acquisition. Provider requests and enclosing-circle registrations have finite lifetimes; the service timer, existing window end alarms and one earliest-deadline alarm provide cleanup routes. ENTER/EXIT/DWELL trigger fresh polygon checks; a coarse EXIT never directly proves departure. Tokens, final serialized guards and asynchronous completion checks reject obsolete work. Positive gaps discard presence. Overlapping/adjoining support may continue without authorizing any future interval early.

Failed acquisition does not create an automatic timer retry loop. A later qualifying transition or an explicit in-window recheck may start another bounded episode. A lost/unresolved active visit requires deliberate recheck in the same window; stored active presence is never revived after process/boot recovery. Skip and eligible wake dismissal also record stable prayer/day suppression separately from the legacy place-change pruning.

## DND, manual silence and wake integration

The new explicit-rule adapter is opt-in on API 29+. Default TIME_ONLY keeps the preexisting path until enrollment; API 26–28 retain that existing time/manual behavior. Enrollment requires old app silence and silence-until-alarm to be ended, and the phone's aggregate DND to be ALL for the first enrollment. The screen explains how to finish an old test contribution in Settings. No stored global/ringer baseline is migrated or replayed into the new adapter.

After enrollment, TIME_ONLY, combined automatic silence, manual silence and silence-until-alarm use four separately named app-owned `AutomaticZenRule` contributions. The combined rule is submitted only for **current window AND confirmed live presence**; the prayer rule is not activated alongside it. All new presence/rule operations share one serialization boundary. The controllers branch before their legacy ringer/filter writes, including restore/force-normal and nap takeover paths. Releasing one contribution preserves the other owners and the phone's independent settings.

Rule IDs and exact condition URIs are journaled. Background callbacks never recreate, enable or redefine a missing/disabled rule. Ordinary unchanged TRUE does not produce a FALSE/TRUE cycle. System stop/status events suppress the affected request; an unresolved combined visit needs rearm. Failed effects retain cleanup authority and the settings screen exposes an uncertain pending release. The rule configuration activity is exported with the required action/metadata; the operational receivers/service stay private. API 35-only APIs are guarded.

Manual expiry withdraws only manual ownership, leaving valid combined/wake support intact. TIME_ONLY retains its old manual hand-off and does not invent an automatic bridge at manual expiry. An explicit restore also dismisses automatic support. Matching alarm release removes its wake contribution even if prayer/manual silence also exists, avoiding an orphaned wake rule. Eligible wake/awake-check exceptions dismiss and invalidate automatic presence before playback; combined-mode awake-check decisions require actual active ownership, rather than TIME_ONLY's historical planned-window cancellation. A system-stopped wake contribution is not automatically revived by another pending alarm's resume.

The phone status and Quran silence explanation distinguish the mosque condition. The owned path does not promise a restored global baseline or guaranteed audible output while another mode still applies.

## Private areas, permissions and privacy

The offline editor supports a simple polygon from ordered latitude/longitude vertices, a rectangle from opposite corners, outline preview, coordinate editing, undo, draft recovery, cancellation, and explicit save with/without enable. It obtains no GPS fix merely to edit or preview an area. Complex shapes are preserved read-only. Names, finite coordinates, topology, local extent, counts and file sizes are validated at storage boundaries. Up to 80 private areas can be saved; more than 100 enabled polygon components blocks monitoring instead of silently losing geofences.

Area data and drafts use atomic files in `noBackupFilesDir`. Export is a deliberate GeoJSON document-picker action with a preview of exact names and boundaries. No raw fix, coordinate trail, visit history or mosque activation analytics were added. Shared background-location disclosure names both independently opted-in uses: travel updates and in-window mosque checks. Precise foreground permission is requested before background access. Readiness includes rule/access status, enabled areas, location/Play services, precise/background permission, disclosure, exact alarms, notification visibility and background restrictions. TIME_ONLY readiness does not require mosque location.

Cloud backup and device-transfer exclusions now cover the ownership/presence journals, nap state, wake scheduler state and the legacy prayer preferences. **The legacy preference file mixes configuration with active flags/baselines, so the entire file is conservatively excluded; its general settings also stop transferring.** New mode/disclosure configuration remains separate. Private geometry/drafts are excluded by their `noBackupFilesDir` placement. Splitting legacy configuration from runtime state can restore selective configuration backup in later work.

The forthcoming Tunisia catalog was not shipped or substituted with the research catalog. No research asset, provenance record, collection output or Git history was removed/rewritten. Current effective input is deliberately saved private areas; national runtime ingestion can be attached once its clean geometry input is ready. The existing research asset's packaging/source-rights review remains release work. The outline editor's usability/accessibility and larger compound handling also need owner acceptance; compilation does not demonstrate them.

## Validation in this implementation pass

- Production `:app:compileDebugKotlin` passed, including Android manifest/resource processing. The final build log is in [implementation evidence](../work/mosques/silencing-implementation-20261031/compile-final.log).
- `git diff --check` and a focused source/manifest/backup audit are recorded in the same evidence directory.
- No production APK was installed, no Pixel settings were changed and no new device/field test was run in this pass. No new production unit-test pass is claimed. Earlier standalone model/device passes remain historical Stage 0 evidence and are not production end-to-end results.
- Preexisting Android Auto/media-browser edits were preserved. The initial dirty patch and source hashes are in the evidence directory; concurrent changes are not presented as part of this feature.

## Next owner testing pass

1. On the intended test phone, finish any old app DND contribution, grant DND/exact access, prepare the app's rules, and select the combined mode. Add/enable a few known private shapes, grant precise/background location and visible notifications. Validate all denial paths and system rule stop/disable/remove behavior.
2. Check zero mosque provider/evaluation/registration work outside a real enabled window. Cover master/mode/permission changes, stale callbacks and the time-only bridge/travel pre-check.
3. During a window, test already-inside opening, later arrival, pass-by/uncertain boundary, confirmed departure, no-fix/ambiguity expiry, explicit rearm and a window ending without departure. Separate logical invalidation, native cancellation completion and actual DND release observations.
4. Test overlap/adjoining versus a positive gap, skipped/dismissed periods, place/time changes, long/overnight periods and late delivery. Check Friday/Eid/Ramadan parity against TIME_ONLY.
5. Exercise manual/timed/wake ownership overlaps, matching and nonmatching alarms, inherited awake-check eligibility, TIME_ONLY's bridge conflict, combined active/absent presence and no automatic return after a wake dismissal.
6. Perform the previously requested silent device checks, process/reboot/permission recovery and controlled window-limited battery comparison. Real-mosque field validation stays pending until the owner supplies that opportunity. Review RTL, large font, TalkBack, editor accuracy and export content.

These checks are acceptance work after implementation. No finite physical sound-restoration guarantee is claimed under force-stop, revoked exact-alarm access or delayed Android execution. Google Play declarations, public packaging/source review and release distribution remain separate from building the feature.
