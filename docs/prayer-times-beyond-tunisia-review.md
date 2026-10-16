# Review of the prayer-times expansion plan

Reviewed on 2026-10-13 against [prayer-times-beyond-tunisia-plan.md](prayer-times-beyond-tunisia-plan.md) and the current working tree. Plan references below use its 2,253-line snapshot, SHA-256 `ebaf9b2e57c5183cea2f70efb0a674864d94d1f104cd95c13d1d7d7b3362ee11`.

The plan has a strong foundation: separate INM and international engines, instants throughout the phone, explicit estimation status, and a distinction between published definitions and fitted tables. However, several specified rules cannot simultaneously satisfy its ordering and scheduling guarantees. Resolve the three P1 findings before implementing the engine, and settle the P2 contracts before their corresponding milestones.

This review treats the owner's recorded choices as requirements. It checks internal consistency, implementability, current code and selected numerical counterexamples; it does not revalidate every country's research or adjudicate the religious positions. The original plan and application code were not changed.

## 1. P1 — Anchor the solar cycle to the place's civil date

**References:** §2, line 76; §6.2, lines 350–357 and 470–478.

The solver starts from civil date D and computes `12 − longitude/15 − EoT/60` in UTC, but never specifies which UTC date contains that transit. Interpreting D directly as the UTC date selects the wrong solar cycle in places on the other side of the geographical date line from their civil time zone.

Wallis and Futuna is explicitly in scope. IANA's representative Wallis point is 176°10′W, while its zone is UTC+12. With EoT set to zero just to expose the date arithmetic, the stated expression for 2026-10-13 gives **2026-10-13 23:44:40 UTC = 2026-10-14 11:44:40 locally**. The equation-of-time correction does not repair the one-day error. A literal implementation would label the following day's cycle as the requested prayer day.

**Recommended correction:** select/refine the transit whose date in `place.zone` is D, explicitly allowing its UTC date to differ from D. Define adjacent-day solar evaluations from that selection. Add a Wallis test that checks the local date of transit and the absolute alarm instants, alongside a date-line case on the opposite side.

The coordinates and zone were verified through the machine's proxy using IANA's [zone.tab](https://data.iana.org/time-zones/tzdb/zone.tab), [zone aliases](https://data.iana.org/time-zones/tzdb/backward) and [Pacific zone rules](https://data.iana.org/time-zones/tzdb/australasia).

## 2. P1 — Fixed intervals do not guarantee an ordered night

**References:** §6.2, line 493; §7.4, line 1174; §7.5, line 1208; §9.1, lines 1599–1601; §9.2, line 1698.

`ecfr_90` uses Fajr = sunrise −90 minutes and Isha = Maghrib +90 minutes. Its high-latitude rule is described as unnecessary, while the polar gate deliberately preserves Luleå's real sunrise and sunset. Those intervals overlap whenever the night is shorter than three hours.

A local calculation with the repository's Meeus sun and the plan's event equations at **65.5848°N, 22.1547°E, 2026-06-21** gives sunset near **22:06:49 UTC** and the following sunrise near **22:59:48 UTC**: a night of about 53 minutes. The rounded interval times are then **Isha 01:37 CEST on June 22** and **the next Fajr 23:30 CEST on June 21**. Isha is 127 minutes after the next Fajr, contradicting the invariant.

**Recommended correction:** define what happens when interval durations cannot fit into the night. Specify which rule takes precedence, how any replacement is marked, or which combinations are unavailable. Test interval presets and custom intervals explicitly at short-night places; testing that all events exist is insufficient.

## 3. P1 — Final Asr ordering conflicts with offsets and minute rounding

**References:** §6.2, lines 448–451 and 489–493; §6.3, line 567; §7.4, lines 1175–1176; §9.2, lines 1698–1700.

The plan preserves a real Asr near the polar boundary even when it occurs only seconds after transit. It subsequently adds method offsets and rounds, while requiring strict final ordering except for Diyanet's documented tie.

At **66.5039°N, 25.7294°E, 2026-12-21**, the same local calculation gives transit near **10:15:07 UTC** and real Asr near **10:15:40 UTC**. `tunisia_18_18` adds seven minutes to Dhuhr and none to Asr, producing **Dhuhr 10:22, Asr 10:16**. Even without offsets, at **66.53°N** both transit and Asr round to **10:15**, which violates the non-Diyanet strict-order rule.

**Recommended correction:** specify precedence between preserving a real event, applying method offsets and guaranteeing final order. Distinguish unrounded astronomical order from rounded display/event order, and define permitted ties. Add cases immediately around the winter Asr boundary with both zero-offset and Dhuhr-offset presets. This requires an explicit contract; silently replacing real Asr would contradict the current preservation requirement.

## 4. P2 — Adhkar validation rejects an explicitly permitted Diyanet day

**References:** §6.2, line 493; §6.9, line 907. Current code: [DhikrReminderScheduler.kt](../android-app/app/src/main/java/com/tunisianprayertimes/adhkar/DhikrReminderScheduler.kt), lines 395–402.

The engine permits Diyanet's Asr to equal Dhuhr, but the reminder migration retains strict ordering, merely changing its operands to instants. The current check rejects the entire day whenever any adjacent pair is equal, before selecting the requested prayer. Consequently even a Fajr-relative reminder would disappear on an otherwise valid polar Diyanet day.

**Recommended correction:** make consumers share the engine's event-order contract, including permitted ties. Alternatively, validate the requested event and its actual window instead of rejecting unrelated events. Define zero-width reminder windows separately. Test a day with `Asr == Dhuhr`, including a reminder unrelated to those prayers.

## 5. P2 — Apply the travel threshold only after semantic place changes

**References:** §6.8, lines 831–839. Current code: [DelegationLocator.kt](../android-app/app/src/main/java/com/tunisianprayertimes/DelegationLocator.kt), lines 198–201.

The proposed persistence rule triggers only when the place kind changes or the rounded point moves more than 3 km. Two `Coordinates` fixes can cross a country, time-zone or administrative boundary within that distance. Even if the resolver correctly identifies the new country, its method and zone would not reach `onPlaceChanged`. Likewise, two Tunisian places have the same `Inm` kind but can have different delegation IDs; the current code detects delegation changes explicitly.

**Recommended correction:** use the distance threshold for coordinate-only movement. Always process a changed country-choice key, zone, delegation identity or mapping-relevant administrative value, and refresh locality labels appropriately. Test opposite sides of a boundary less than 3 km apart and an unchanged point whose resolved zone changes. This is distinct from the accepted limitation of nearest-city country detection.

## 6. P2 — Three prayer days do not cover the supported silence offsets

**References:** §6.9, lines 880–881. Current code: [PrayerEndpointControls.kt](../android-app/app/src/main/java/com/tunisianprayertimes/ui/PrayerEndpointControls.kt), line 61; [PrayerSilenceEditorSheet.kt](../android-app/app/src/main/java/com/tunisianprayertimes/ui/PrayerSilenceEditorSheet.kt), lines 119–120 and 217–220.

The new planner scans only D−1..D+1, with alarm identities reusing a slot every three prayer days. The existing editor supports relative endpoint offsets up to **10,080 minutes, or seven days**. A window ending at Isha +3,000 minutes can still be active after its originating prayer day has left that scan. A reboot or refresh cannot reconstruct it, and extending the scan alone introduces alarm-ID collisions between occurrences three days apart.

**Recommended correction:** define the supported endpoint/duration domain first. Derive the look-back, look-ahead and alarm identity scheme from it, or explicitly specify tighter validated editor limits and their effect on existing settings. Test recovery after reboot with a multi-day window and overlapping occurrences of the same prayer. This is an existing domain mismatch that the proposed replacement leaves unresolved.

## 7. P2 — Define the validity guard for interval and estimated events

**References:** §6.5, lines 700–705; §8.7, line 1516; §9.6, line 1812.

The guard compares a mapped table against the place's own dawn angle, Asr shadow and dusk angle, across every attached area/profile. Those references are not defined for all supported methods: Comoros and Mayotte use sunrise/Maghrib intervals, while high-latitude profiles have days with no real twilight or shadow event. The plan does not specify whether the guard substitutes the profile's estimate, skips that comparison, rejects the zone or uses another baseline.

**Recommended correction:** define a pure calculation of the guard's reference at the user's own point, bypassing zone mapping. Specify the comparator for each event/rule family, the treatment of absent events, and whether offsets, rounding and estimates enter that reference. Avoid comparing a mapped table against itself. Add an interval-profile case and an absent-twilight zone case without changing the owner's agreed tolerance or exemptions.

## 8. P2 — Tie the official-match badge to demonstrated coverage

**References:** §6.11, line 972; §8.6, lines 1472–1475 and 1487; §9.5, lines 1784 and 1789–1791.

The tier-A gate sets accuracy thresholds on held-out data, but no minimum seasonal or per-zone coverage. Jordan's cited official holdout is 31 zone-days in January–February, and the proposed expansion still ends on 9 February. Meanwhile, the plan's full-year fitted data reports 94.1% exact Fajr, illustrating why the small winter result cannot establish year-round performance.

The badge is granted at profile level wherever a place maps to an authority zone. Qatar's Mesaieed/Halul and Jordan's further places are explicitly reported separately from the main gate, but there is no corresponding badge restriction for those zones.

**Recommended correction:** specify the required event, season and zone coverage and store it with the fit result. Require the applicable coverage before showing the official-match badge at a place; retain the existing lower chip for unvalidated coverage. Report percentages with their actual sample period and population. This preserves the decision to ship imperfect profiles while preventing a narrow holdout from implying a broader guarantee.

## 9. P2 — Separate named-city exemptions from attachment reach

**References:** §6.5, lines 663–687 and 705; §8.8, line 1533; §9.6, line 1811.

Egypt requires a per-city area, initially a 15 km radius and refined in AM2, within which a GPS point counts as the named city and bypasses the guard. Its nearest-zone attachment must remain unlimited. The proposed schema has `reachKm` for attachment reach and `namedPlaces` containing only coordinates; it does not represent the independent exemption area or how catalogue identity establishes membership.

**Recommended correction:** add a separate named-area representation, including its centre and radius or another explicit membership rule. Do not reuse `reachKm`: that would turn a guard exemption into a forbidden attachment cutoff. Test just inside and outside the named area while retaining unlimited nearest-zone mapping.

## 10. P2 — Refresh the current-code baseline before M0

**References:** §5.1, lines 202–205; §6.10, line 934; §9.1, line 1615; §11, lines 1895–1896.

Some descriptions of today's behavior have already drifted:

- The plan says UNSILENCE restores without checking another active window. [SilenceReceiver.kt](../android-app/app/src/main/java/com/tunisianprayertimes/SilenceReceiver.kt), lines 76–81, already checks `isOtherSilenceWindowActive`; [SilenceScheduler.kt](../android-app/app/src/main/java/com/tunisianprayertimes/SilenceScheduler.kt), lines 177–184 and 318–328, includes overlap and carryover handling.
- The Ramadan paragraph treats the automatic Isha bump as part of the user's setting once written. [PrefsManager.kt](../android-app/app/src/main/java/com/tunisianprayertimes/PrefsManager.kt), lines 398–477, now distinguishes the temporary automatic override from an explicit edit: it saves the old values and restores them after Ramadan unless the user edits Isha.

**Recommended correction:** refresh the code inventory and expected-difference list against the exact tree used for M0. Preserve the current overlap handling and restoration behavior in the baseline, then specify how restoration interacts with travel and the Tunisia-only gate. In particular, avoid making an early return for a foreign place accidentally bypass cleanup of an override previously applied in Tunisia. This observation does not reopen the owner's calendar or travel decisions.

## 11. P3 — Resolve the duplicate `switchDays` field

**Reference:** §6.5, lines 684–685.

`OfficialZone` declares `switchDays` twice, as both `Map<TimeEvent, IntRange>?` and `List<Int>`. The shown Kotlin class cannot compile, and the two forms encode the southern IRN transition dates differently.

**Recommended correction:** choose one representation, document each transition's meaning and update the engine, asset and fitting descriptions consistently.

## Verification and scope

The review used parallel checks of the engine, Android integration and authority/zone design, followed by cross-checking the findings against the plan's other sections. Numerical counterexamples used the existing solar functions in [inm_prayer_times.py](../scripts/prayer_formula/inm_prayer_times.py) and the proposed event equations; they demonstrate specification conflicts, not failures of an already implemented international engine. The date-line example deliberately sets EoT to zero to isolate the calendar-date error.

No build or application tests were run for the original document review. The France extension below adds a fresh dataset analysis and primary-source checks; other countries' research remains outside this review's verification scope.

## 12. France: calculation and religious criteria first, mosque calendars as an audit

Added at the owner's request on 2026-10-13. **The main result is that method disagreement is much more common than a clear prayer-window conflict.** Of 1,668 metropolitan-France calendars retained for Fajr/Maghrib analysis, 846 fit the broader Fajr angle-comparison set and 1,654 fit a simple Maghrib sunset-offset rule, within five minutes on at least 90% of days. Twenty calendars have repeated, stronger window flags: 15 list Fajr after modelled sunrise and five list Maghrib before modelled sunset. The names and sizes of those discrepancies are below.

These are findings about the supplied calendar records, interpreted as 2026. They do not establish what worshippers actually did, or that a mosque's current timetable is the same as the stored one.

### 12.1 Correct the meaning of “wrong” in the plan

The existing §7.7 calls some Fajr entries after the 12° instant wrong under every view. That overstates what the data establishes. **A prayer-start estimate, a fasting cutoff, an adhan and an iqama are different quantities.** Fajr prayer runs from true dawn until sunrise; a deliberately later congregational prayer can therefore be valid. Diyanet expressly describes both that window and the Hanafi preference for praying later. A time unsuitable as an unqualified dawn/fasting cutoff is not automatically an invalid prayer time. [Diyanet's Fajr ruling](https://kurul.diyanet.gov.tr/tr/fetva/sabah-namazi-imsak-vaktinin-girmesiyle-kilinabilir-mi/0193c42d-4d4a-71dc-1298-7a265eeddb83).

Similarly, sunset +10 minutes is an audit convention, not a universal end of Maghrib's validity. Its beginning is sunset; the religious discussion of its endpoint concerns twilight and school differences. A +16-minute or +25-minute congregation must not be marked invalid merely because it fails the plan's +10 bound. [Diyanet's Maghrib ruling](https://kurul.diyanet.gov.tr/tr/fetva/aksam-namazi-ne-zamana-kadar-kilinir/0193c42d-4d90-75f3-cc3a-3111c6896da2).

Use four separate outcomes: **matches a tested start rule**, **does not match those rules**, **possible delayed adhan/iqama**, and **potential prayer-window/calendar error**. Only the last warrants an error investigation; failure to match a finite rule set does not prove failure to match every Islamically valid rule.

### 12.2 Proposed approach for France

1. **Keep the astronomy independent of mosque popularity.** Compute solar position at the actual instant and coordinates, with correct civil-date and time-zone handling. For standard sunrise/sunset, use the upper-limb convention, solar centre −0.8333°. Record whether elevation and the visible horizon are known. USNO explains that atmosphere, terrain and observer height can move the observed event; five minutes is a screening threshold here, not a universal physical uncertainty bound. [USNO definitions](https://aa.usno.navy.mil/faq/RST_defs).
2. **Attach each Fajr interpretation to its evidence and complete procedure.** The 12° position has a published religious defense associated with Musulmans de France. APBIF publishes a different French procedure: 18°, a six-minute precaution, and solar-midnight estimation when the 18° sign is absent. Offer named procedures with their actual conditions, not an unlabeled “scientific” angle selected because it matches the most mosques. [12° rationale](https://www.havredesavoir.fr/les-fondements-juridiques-du-calendrier-de-prieres-selon-langle-12-degres/), [APBIF methodology](https://apbif.fr/methodologie-determiner-horaires-priere-apbif).
3. **Give local observations priority when establishing physical dawn, while retaining the religious interpretation explicitly.** A France-wide observational dawn model was not established by this review. OpenFajr's Birmingham work cautions against geographic transfer, and the CMR's explicit 14° observation concerns evening twilight, not a national 14° Fajr rule. A stronger future model needs dated observations across French latitudes and seasons, recorded weather/horizon conditions, reproducible measurements, and qualified review of which observed sign defines dawn. [OpenFajr paper and addendum](https://openfajr.org/docs/research_paper.pdf), [CMR observations](https://cmrofficiel.fr/actions/harmonisation-des-horaires-de-priere/).
4. **Separate an actual angle event from an estimate.** The main comparison below preserves real angle events and estimates only when they are absent. Every-night caps are reported separately because they can move a real event. Estimation disagreements are not automatically errors: Diyanet's high-latitude resolution itself recognises qualified alternative approaches. [Diyanet resolution](https://kurul.diyanet.gov.tr/tr/kurul/karar/45-enlemin-otesinde-namaz-vakitleri/d681fdc5-924c-4f02-bb74-08dd1c135350).
5. **Use the dataset to test the chosen rules, not define their truth.** Fit one declared procedure for the whole calendar, inspect each anomalous day, and retain its signed error and source. Keep a calculated start distinct from a mosque's reported congregational time. A final physical-window check must still run after user adjustments. An annual fit score must never suppress a serious individual-day flag.

This approach does not require bundling mosque records into the application. The dataset remains an offline validation resource. It also does not identify one angle as universally safe for both praying and fasting: an earlier estimate is precautionary for stopping food, but can be too early for prayer under another dawn interpretation.

### 12.3 Dataset and reproducible measurement

The SQLite database was opened read-only. It contains **1,679 FR records**, all with 366 month/day rows, but **no calendar-year, method, source-date or adhan/iqama field**. The plan's stated 2026 interpretation was used, with February 29 omitted.

- Six records are in Mayotte; one has coordinates (0,0). They are outside this metropolitan-France analysis.
- Four further records are excluded from the main counts: **2647** (Creil, ambiguous clock and fixed/clamped values), **3323** (Niort, incomplete), **3433** (marked closed, many all-zero days), and **3925** (Étampes, severely defective calendar). They remain identified in the output.
- This leaves **1,668 records and 608,820 mosque-days**. One Fajr cell is `ERROR` (key 2791, October 3), leaving 608,819 numeric Fajr cells; it does not count as a match.
- Fourteen time-zone labels conflict with the calendars' winter/summer solar alignment. Those series were interpreted as Paris clocks and flagged as inferred; **2366** genuinely stores UTC and remains UTC. Creil's ambiguous series was excluded rather than forced into Paris time.
- The 1,668 records contain **1,544 distinct six-time annual calendars**. These are counts of mosque records, not independent observing stations or a representative census of all French mosques.

The solar calculations use event-time evaluation of the repository's Meeus sun. Independent scalar root checks found no event-existence disagreement, including 871 cases close to the 18° disappearance boundary. That verifies numerical implementation, not whether a given angle equals observed true dawn.

**Fit criterion:** one fixed annual rule must match at least **329 of 365 dates** (90%), within ±5 minutes; ±2-minute results are also given. Missing cells fail that day's comparison. The rules cannot switch freely from one angle to another each day. Rule selection maximises the number of matching days, with mean absolute error breaking ties. These are descriptive fits to this same dataset, not held-out prediction claims.

### 12.4 How many calendars fit the different rule sets?

All percentages below use the same **1,668-record** denominator.

| Comparison | Within ±5 min on ≥90% of days | Within ±2 min on ≥90% of days |
|---|---:|---:|
| Fajr: either of the two directly verified French procedures, 12° without extra minutes or APBIF's 18°/+6/absent-midnight procedure | **463 (27.8%)** | **16 (1.0%)** |
| Fajr: broader angle-comparison set described below | **846 (50.7%)** | **224 (13.4%)** |
| Same Fajr set, additionally permitting every-night angle-fraction caps | **1,066 (63.9%)** | **345 (20.7%)** |
| Wider numerical sensitivity set, including other angles and sunrise −90 | **1,212 (72.7%)** | **450 (27.0%)** |
| Maghrib: one fixed offset from standard sunset, 0–10 whole minutes | **1,654 (99.2%)** | **1,589 (95.3%)** |
| Both Fajr under the broader angle set and Maghrib pass their individual annual tests | **844 (50.6%)** | **216 (12.9%)** |

The **broader angle-comparison set** uses 12°, 13°, 15° and 18°, preserving actual events. Where an angle is absent, it tests one fixed choice of angle/60 of the night, half-night, or one-seventh, plus the separately implemented APBIF procedure. At 12°, 13° and 15°, the absent-only variants coincide in this dataset; duplicate variants do not count as additional independent methods. **184 records fit at least two distinct angle families at ±5 minutes**, so a close fit does not uniquely identify the mosque's method.

The 13°/15° comparisons have institutional precedent, but geographic scope matters: FCNA's own published summary recommends them for Canada/the USA, not France. Its canonical article could not be fetched directly; the statement was verified in the council's own site excerpt. The fallback variants are comparison options, not a claim that every combination is a source-approved French procedure. [FCNA article](https://fiqhcouncil.org/the-suggested-calculation-method-for-fajr-and-isha/).

The wider sensitivity set adds 14°, 14.5°, 16°, 17°, 18.5°, 19.5°, 20° and sunrise −90 minutes. **Its higher fit count is not a count of newly certified valid methods.** Nor does the every-night-cap row prove that the shifted time is the physical dawn. No arbitrary fitted Fajr minute offsets are added; APBIF's published +6 is the explicit exception. The two verified French procedures individually match 459 and four records at ±5 minutes.

For Maghrib, the screen measures distance from unrounded standard sunset plus the selected integer offset. The six-time experiment below rounds predictions before comparing, so its stricter ±2-minute Maghrib subtotal differs slightly. This rounding choice does not change the ±5-minute count.

**All six displayed times together:** a separate exhaustive comparison of **26,136 fixed configurations per record** finds **270/1,668 (16.2%)** within ±5 minutes on the same ≥329 days, and **64 (3.8%)** within ±2 minutes. This smaller bank uses Fajr 12°/15°/18°, Isha 12°/15°/17°/18°, angle-fraction estimation only when absent, shadow-factor 1 or 2 for Asr, Dhuhr +2…10, Maghrib +0…10 and sunrise −0…10 minutes. The offsets are comparison ranges, not religious validity boundaries. Morning Isha clock values preceding Maghrib are interpreted as following-day times. This is a screen of the stored fields: some records usable for Fajr/Maghrib have defective other fields, notably key 2747's sunrise. The bank also omits regional tables, Diyanet's full summer procedure and other seasonal models; **the other 1,398 records cannot therefore be called religiously wrong**. Their best configurations and per-prayer results are in the linked six-time file.

### 12.5 For calendars that do not fit, how large are the differences?

For the **822 Fajr calendars failing the broader angle-comparison set**, the median of their individual median absolute discrepancies is **4 minutes**, and the median of their individual 95th-percentile discrepancies is **28.4 minutes**. A small annual median can coexist with a badly mismatched summer. The largest single discrepancy from the selected best annual rule is **274 minutes**, in a clearly problematic series at Montgeron on June 15; this is a calendar/field investigation, not evidence of a plausible four-hour astronomical disagreement.

Even the wider numerical sensitivity set leaves **456 records unmatched**. Their median mosque-level median discrepancy is **4 minutes**, median mosque-level 95th percentile **29 minutes**, and largest selected-rule discrepancy **195 minutes**. “Unmatched” means unmatched by this finite bank, not unmatched by every legitimate local practice.

For the **14 Maghrib calendars failing the 0–10-minute offset screen**, the corresponding median discrepancy is **3.84 minutes**, median 95th percentile **47.01 minutes**, and largest discrepancy **156.87 minutes**. The large upper errors are concentrated in calendar anomalies. A consistent delay is a different issue: Ferrières is approximately sunset +16 minutes and Alghadir approximately +22…30; those differences alone do not establish invalid Maghrib.

For the **1,398 six-time nonmatches**, take each day's largest absolute discrepancy among the six times, then summarise each mosque's year. The median mosque has a typical daily maximum of **7 minutes**, a 95th percentile of **41 minutes**, and an annual maximum of **53 minutes**. The largest raw discrepancy, **954 minutes**, is traced to key 2747 (Bilal–Fontaine), whose sunrise field contains midnight/23:00 placeholders. It is a data defect, not a fifteen-hour prayer deviation. These residuals therefore measure both method differences and the quality of the stored fields.

Other useful Fajr diagnostics, each requiring at least ten affected days:

- **336 records** are more than five minutes later than the 12° dawn estimate; **244** exceed ten minutes and **172** exceed twenty. Among the 336, the median worst-day difference is **22.2 minutes**. These are start-estimate/fasting-cutoff questions, not automatically invalid prayers.
- **12 records** precede the real 18° event by more than five minutes, on dates when that event exists. The median of their worst-day differences is **39.0 minutes**, maximum **60.8**. This is disagreement with 18°, not proof that every deeper-angle interpretation is invalid.
- **56 records** have persistent late-Fajr patterns compatible with delayed adhan/iqama or configuration problems. The schema cannot distinguish those explanations; no mosque is declared to use iqama solely from that pattern.

### 12.6 Calendars marked for repeated prayer-window conflicts

The following **20 records** have at least ten dates with a discrepancy greater than five minutes beyond a modelled prayer-window boundary. **If the year, clock, location and field meaning are confirmed, and the local physical sunrise/sunset boundary is corroborated with relevant horizon/elevation effects, these entries are wrong as ordinary scheduled prayer times.** Until then, the correct status is `REPEATED_PRAYER_WINDOW_FLAG_VERIFY_SOURCE`, not a verdict against the mosque or its worshippers. The final column is the largest difference from sunrise/sunset, not from an arbitrary angle profile.

| Dataset key | Calendar label / town | Flag | Affected days | Maximum difference |
|---|---|---|---:|---:|
| 2257 | Mimar Sinan, Altkirch | Fajr after sunrise | 91 | 42.4 min late |
| 2315 | Merkez Camii, Annecy | Fajr after sunrise | 129 | 60.4 min late |
| 2487 | Al Nour, Carvin | Fajr after sunrise | 96 | 51.0 min late |
| 2783 | Ar-Rahma, Fumay | Fajr after sunrise | 45 | 16.5 min late |
| 2818 | Falah-E-Daraine, Garges-lès-Gonesse | Fajr after sunrise | 91 | 44.3 min late |
| 3113 | Muftija Muamer Zukorlić, Metz | Fajr after sunrise | 112 | 60.2 min late |
| 3221 | Mosquée Montgeron | Fajr after sunrise | 89 | 43.2 min late |
| 3280 | Montréal-la-Cluse / Nantua | Fajr after sunrise | 73 | 29.4 min late |
| 3316 | As-Salam, Nice | Fajr after sunrise | 99 | 40.1 min late |
| 3557 | Ditib Saint-Louis | Fajr after sunrise | 144 | 73.5 min late |
| 3571 | ACFT Sarreguemines | Fajr after sunrise | 169 | 93.7 min late |
| 3662 | ACMV, Sarcelles | Fajr after sunrise | 137 | 74.3 min late |
| 3663 | J.M.S, Sarcelles | Fajr after sunrise | 112 | 59.3 min late |
| 3693 | Association des Jeunes, Saint-Rambert-d'Albon | Fajr after sunrise | 88 | 37.8 min late |
| 3750 | Mimarsinan, Thyez | Fajr after sunrise | 133 | 62.9 min late |
| 3123 | Mosquée des Louanges, Montfermeil | Maghrib before sunset | 68 | 57.2 min early |
| 3589 | Grande Mosquée de Strasbourg | Maghrib before sunset | 68 | 54.1 min early |
| 3617 | Mosquée Saint-Germain-en-Laye | Maghrib before sunset | 238 | 29.1 min early |
| 3729 | Ditib Torcy | Maghrib before sunset | 31 | 54.6 min early |
| 3887 | Grande mosquée VLB, Villiers-le-Bel | Maghrib before sunset | 68 | 58.3 min early |

The three 68-day Maghrib blocks run from October 25 through December 31 under the 2026 interpretation, suggesting a daylight-saving/calendar problem. Torcy's 31 anomalous days are in March. **Torcy still passes the annual 90% matching criterion**, demonstrating why every-day window checks must remain independent of fit scores. Fixed Fajr values in several rows likewise suggest a stored congregational setting or stale configuration; that does not make an after-sunrise entry a suitable ordinary Fajr schedule if its meaning is confirmed.

Across all included records, **47** have at least one >5-minute Fajr/Maghrib window flag, covering **1,611 Fajr entries after sunrise** and **528 Maghrib entries before sunset**. The 20 above are the repeated cases; isolated cases are also retained in the output. Unknown elevation and horizon conditions should be checked before treating near-boundary differences as confirmed physical violations.

### 12.7 Files identifying each mosque and each flagged date

The detailed results remain in the project's ignored local research folder, alongside the reproducible scripts. The original database and plan were not modified.

- [Assessment for every metropolitan record](../work/france-prayer-review/mosque_assessments.csv): name, key, inclusion status, fitted rule, matched-day count, residual sizes, time-zone inference and Fajr/Maghrib flags.
- [The 20 repeatedly flagged calendars](../work/france-prayer-review/repeated_window_flags.csv).
- [Every >5-minute prayer-window flag](../work/france-prayer-review/window_flag_days.csv): mosque, assumed date, stored clock, modelled boundary clock and signed direction/size.
- [Detailed Fajr fits](../work/france-prayer-review/fajr/mosques.csv) and [Maghrib fits](../work/france-prayer-review/maghrib/mosque_maghrib_audit.csv).
- [Six-time configurations and residuals](../work/france-prayer-review/all_six/mosques.csv).
- [Verified primary sources and their limits](../work/france-prayer-review/sources/RESEARCH-NOTES.md), [data-quality audit](../work/france-prayer-review/quality/summary.md), and [dataset fingerprint](../work/france-prayer-review/dataset_manifest.json).

Reproduction entry points: [Fajr analysis](../work/france-prayer-review/fajr/analyze.py), [Maghrib analysis](../work/france-prayer-review/maghrib/analyze_maghrib.py), [six-time analysis](../work/france-prayer-review/all_six/analyze.py), and [combined report](../work/france-prayer-review/consolidate.py). Their cached solar arrays belong to this dataset/order/year snapshot; regenerate them before using a different input or changed solar assumptions. All external source checks used the machine's required proxy.
