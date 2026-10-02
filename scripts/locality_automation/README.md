# Locality automation

## Offline review preparation

The current source/GPS review helpers can reuse five focused commands without
launching validation or changing assets. Run these from the repository root
with the configured Python runtime:

- `python -m scripts.locality_automation.review_preflight`: check an explicitly
  chosen frozen source package and its declared pins. Required arguments are
  `--package`, `--base`, one or more `--allow-root`, and a fresh `--output`.
  Use `--reference-root PREFIX=DIR` for a known legacy namespace such as
  `official`; there is no fallback path guessing or recursive opening of
  referenced JSON. Optional `--live-json` and `--live-bin` must appear together;
  add `--live-names` when the bundle pins display names. `--expected-code` can
  repeat to bind the exact assigned cluster. Missing packages and holds return
  exit code 2. A bookkeeping PASS adds zero geographic credit.
- `python -m scripts.locality_automation.review_checkpoint`: inspect current
  `--repo`, `--run`, `--handoff`, and `--report` plus optional repeated
  `--pending-package` paths. Explicit `--validation-state paused|active` is the
  caller's goal state, not a process-liveness probe. Save to a fresh `--output`.
  The command verifies current receipt/report file versions and records the
  existing scoped/full counts without accepting or refreshing anything.
- `python -m scripts.locality_automation.review_jobs --spec SPEC --output PLAN`:
  prepare a write-once plan for the existing engine. The spec contains
  `schemaVersion: 1`, `governorate`, `inputScope: source-only|live-assets`, and
  `jobs`. Each offline job explicitly declares `id`, `kind`, `argv`, absolute
  `cwd`, `inputs`, `outputs`, `deps`, `resources`, and
  `executableDependencies` file/SHA256 pins. Imported results require actual
  `importedOutputs` pins. Declare all imported/local program dependencies;
  this adapter does not infer import closure or inspect result status strings.
- `python -m scripts.locality_automation.review_candidate_queue`: filter an
  explicitly selected `--triage` cache against `--report`, `--live-json` and
  `--live-bin` immediately before dispatch. Repeat `--assigned-code` for active
  cases and save to a fresh `--output`. Already complete source codes are
  excluded even if old triage recommends them. Unchanged packed slices remain
  warm leads requiring fresh source/identity/GPS review; compact metadata's
  absent official codes are disclosed rather than invented. Partial geographic
  validations can still need complete-source review. Report/receipt/file drift
  returns HOLD with no queue. Source alternatives are retained, grouped by full
  path and parent, and no ownership or acceptance is inferred.
- `python -m scripts.locality_automation.scope_references`: create an immutable
  canonical companion for an explicitly pinned complete scope before native/GPS
  review. Supply `--scope`, `--scope-sha`, `--base`, repeated `--allow-root`,
  explicit `--reference-root PREFIX=DIR`, fresh `--output` and `--receipt`.
  Optional `--bindings` plus `--bindings-sha` declares exact JSON-pointer,
  original file/SHA and same-hash target bindings. Every inline explicit pin is
  checked; referenced JSON files are not hydrated. Original facts, hashes,
  geometry, styles, qualification and gates remain unchanged. Conflicts, stale
  references and unequal hashes HOLD; no guessed search or historical-pin
  demotion. Complete scope preflight and final independent QA both remain
  required. Existing outputs reject before work, and failed publication does
  not publish a canonical scope.

Run source directory preparation from an existing repository/evidence parent.
Do not use a new output directory as the command's `cwd` before creating it.
The prepared job adapter validates existing cwd and declared outputs; the new
scope companion validates complete explicit evidence references early. Neither
tool creates geographic credit.

Prepare later stages only after their input files have real hashes. Keep job
input sets narrow, with source-only and current GPS dependencies distinct.
Changed definitions require a new revision/job ID; `contentKey` is a prepared
identity, not an automatically enabled GPS cache. Independent jobs use the
existing dependency/resource scheduler. Execute prepared plans only through
the pause-aware `run-plan` command with its existing workspace lock after
validation has been resumed. Preparation never resumes the workspace, runs
supplied commands or grants source/GPS acceptance. Keep the guarded root
receipt/install helpers as the separate final step.

## Review the completed automatic pass

The full automatic pass has finished. Its recorded cases and issue lines are
investigation inputs, not geographic certifications. The post-run reviewer
builds an ordered queue for **one governorate at a time**, starting with Ben
Arous. It groups explicitly linked boundary cases, puts current user problem
reports first, and sends only concrete high-priority questions to GPT-6 Luna
at **max** reasoning. Boundary links without source-supported diagnostics stay
in the human queue: the first two Luna boundary reviews added no verified
geographic checks. New review requests do not use DeepSeek.

From the repository folder, double-click **Review locality reports with
Luna.cmd**. It publishes the Ben Arous queue, reviews at most eight eligible
units with up to three concurrent Luna workers, and prints the saved output
paths. Repeating the command reuses content-addressed results; uncertain paid
requests are not silently retried. To inspect the queue without making model
requests, run:

```text
$taskRoot = 'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows'
& "$taskRoot\work\geo\venv\Scripts\python.exe" -B -X utf8 scripts\run_locality_review.py --config "$taskRoot\work\locality-automation-luna-resume.json" prepare
```

Run that PowerShell command from the Git repository. An explicit `--governorate "ولاية"`
before `prepare` or `run` selects the next governorate. The generated JSON
and HTML are in `work/locality-automation/review/`; focused advisory receipts
are in its `advisories/` directory. The HTML links back to the existing manual
locality tool and verified cached official PDFs and map thumbnails. It also
flags exact duplicate picker labels using a read-only point-versus-polygon
audit in `display-collisions-current.json`; that audit does not merge entries.
Model findings never change the app catalog,
award verification, or resolve a case by themselves. A human disposition
applies only to its exact source fingerprint.

## Original automatic-pass launcher (completed)

From the repository folder, double-click **Run locality automation.cmd**.

It configures itself on the first launch, resumes its own work queue, and processes the current governorate (Ben Arous). Leave its window open. It finishes when the finite automatic pass is recorded, including cases reported for later investigation. It opens the report when done.

Send **work/locality-automation/report/run-summary.json** from the Codex task folder back to Codex. The companion **investigations.json** lists unresolved cases. All detailed evidence and logs remain on this computer.

Double-click **Show locality report.cmd** to refresh the report without starting investigations. **Pause locality automation.cmd** stops new dispatches and lets running jobs finish. Starting Run again resumes saved work; failed or uncertain paid requests are not retried automatically.

**Original delivery status:** the full-run program was written and statically
checked before delivery because the user asked not to run it then. The user
later ran the automatic pass to completion; the review process above handles
its resulting reports. Keep the original reports and logs for audit.

## What runs automatically

- Read the latest accepted handoff pointers, preserve the existing scores, and select work within one governorate. New user problem reports take priority; explicit border dependencies are tracked separately.
- Reuse existing map observations and official PDF caches. Collect missing public Maps observations across up to five connected, configured emulators, with one owner per device.
- Download uniquely matched official source maps, extract polygon candidates, produce source-page images and overlays, and report ambiguous source matches without blocking other cases.
- For a unique eligible polygon hypothesis, prepare diagnostic geometry, compare old/new coverage, overlaps and GPS witness points, measure overlap widths, check uniquely matched official centre assignments, and pack a diagnostic catalog using the existing generator routines.
- Check Arabic display names, repeated aliases, duplicate candidates, residential-complex terms, coordinates, current monthly timetable availability, and nearest available prayer references.
- Send bounded evidence packets to DeepSeek V4.1 Flash, retain its advisory findings, and cache responses. Uncertain identities and other issues go into a follow-up report. No model result alone becomes an accepted geographic claim.
- Produce reports with distinct places processed, accepted checklist scores, unresolved issues, saved evidence links, task outcomes, and indicative model costs.
- Install compatible validated packages from `work/locality-automation/ready-to-install`, with fingerprint preconditions, backups, an Android compilation, selectable-catalog refresh, conservative score invalidation, and rollback on failure. Installation runs after the automatic governorate pass. No further approval prompt is required.

Ambiguous identities, competing rings, split-path repairs without a recorded decision, disputed geographic scope, unavailable sources, and incompatible input fingerprints are reported. Independent work continues. A program cannot establish 100% geographic reliability from label matching, polygon validity, or point containment.

The saved 41-boundary proposal is retained as prior diagnostic work. It is **not** automatically relabeled as installation-ready. New per-case proposals compare against the installed catalog; joint reconciliation of competing proposals remains an explicit investigation item.

## Requirements and spending

The launcher uses the existing task-local Python environment, audited helpers, cached evidence, and Windows-encrypted DeepSeek credential. It does not contain API keys. Internet access is needed for new official downloads and model requests. Emulators are only needed for observations that are missing; if none are connected, their cases are reported and other work continues. Existing manual-review links require that server to be running; saved PDF/overlay packets open directly without it.

DeepSeek uses `deepseek-flash` (V4.1 Flash), maximum reasoning, and the existing 393,216-token maximum output allowance. There is no artificial spending cap. Ordinary processing, caching, comparisons and reports run locally. Google API calls are disabled in this program.

Illustrative **runtime** cost: 100 advisory requests producing 30,000–100,000 output tokens each would cost about **$1.80–$12 in output tokens**, plus input tokens. If all 100 responses reached the maximum output allowance, output alone could approach **$47.19 at peak pricing**. These are scenarios, not predictions or caps. Cached responses avoid new calls. Actual provider billing is authoritative. Rates checked September 22, 2026: <https://api-docs.deepseek.com/quick_start/pricing/>.

No OpenAI model is called by default. Optional OpenAI-compatible fallbacks can be configured in `model.fallbacks`, with `name`, `kind: "openai_compatible"`, `baseUrl`, `model`, `apiKeyEnv`, and optional `reasoningEffort` / `timeoutSeconds`. Credentials stay in environment variables. Each configured provider gets at most one attempt for a packet; failures are recorded. Do not add providers unless their extra spending is intended.

## Advanced commands

The task folder is currently:

`C:/Users/barou/Documents/Codex/2026-09-06/the-android-app-app-currently-allows`

The first launcher run creates `work/locality-automation-config.json`. The CLI is `scripts/run_locality_automation.py`, executed using `work/geo/venv/Scripts/python.exe`.

```text
python scripts/run_locality_automation.py --config CONFIG report
python scripts/run_locality_automation.py --config CONFIG pause
python scripts/run_locality_automation.py --config CONFIG resume
python scripts/run_locality_automation.py --config CONFIG run --once
python scripts/run_locality_automation.py --config CONFIG run --all-governorates
```

`--all-governorates` continues one governorate at a time after each automatic pass, keeping unresolved cases for investigation. It can incur substantially more API usage than the default Ben Arous run. A completed automatic pass does not mark the geographic validation goal complete.

### Resume the saved full run with GPT-6 Luna

The task-local config can select `model.provider: "codex_cli"`, `model.name: "gpt-6-luna"`, and `model.reasoning: "max"`. This uses the signed-in Codex CLI in a read-only, ephemeral session for each new evidence packet; it does not require an OpenAI API key. Pause the running queue before changing the config. The completed case ledger is preserved. A partially finished DeepSeek batch receives a separate Luna revision, so a few unrecorded cases may receive a second advisory review. Paid/usage-uncertain requests are not silently retried.

After the config is changed, resume with the commands above and use `run --all-governorates`. Luna usage consumes the signed-in Codex account's allowance. The report keeps the historical DeepSeek dollar estimate separate from Luna token counts; it does not infer a dollar cost for Codex usage. If the Codex backend becomes unavailable, new dispatches pause and the current batch remains unrecorded for review.

For this saved task, double-click **Run remaining locality automation with Luna.cmd** in the repository folder. It uses `work/locality-automation-luna-resume.json` and continues from the paused Gabes governorate through the remaining governorates.

`helper-plan` prepares a pinned plan for existing acquisition, extraction, exact-chain/retrace, append, impact, overlap-width, centre, matrix, diagnostic-stage, prayer, metric-publication and queue helpers. It does not execute it. `run-plan` executes such a plan after resume. Supply explicit result paths where a helper has several outputs. Configured exact-path repairs and metric publication require their existing evidence/review inputs; model advice is never converted into those inputs automatically.

## Installation package contract

The installation queue takes an exact manifest with `schemaVersion: 1`, `files`, and a pinned `validation` JSON. Every file entry supplies a pinned staged `source`, repository-relative `destination`, and `beforeSha256` (null only for a new file). The validation receipt must have `status: "READY_TO_INSTALL"`, a qualification, an exact matching list of `{sourceSha256,destination,beforeSha256}`, and empty `issues` / `unresolvedCaseIds` for the included changes. Unrelated unresolved localities do not prevent an independently validated package from installing.

Only the locality assets and JSON/GeoJSON data under `scripts/neighborhoods` are permitted destinations. Model advice and `DIAGNOSTIC_NOT_INSTALLABLE` geometry outputs cannot enter this queue on their own. There is no automatic promotion of a proposal merely because it compiles.

Backups and journals live under `work/locality-automation/transactions`. Conflicting external edits are reported rather than force-restored. Locks coordinate this program's runners; avoid editing the same catalog files during installation. A refreshed local snapshot invalidates affected checklist evidence; the existing manual-review server may need a later refresh, and its saved answers are preserved.

## Durable outputs

```text
work/locality-automation/
  control.json                  pause state
  case-ledger.json               completed automatic passes, not certifications
  batches/                      plans, process logs, source packets and comparisons
  models/                       cached requests, attempt markers and advisory results
  prayer-sources/                monthly source availability cache
  ready-to-install/              validated packages only
  install-receipts/              installation outcomes
  transactions/                 backups and recovery journals
  report/report.html            readable dashboard
  report/run-summary.json       report to send back
  report/investigations.json     unresolved issues
  review/gov-*.json              ordered one-governorate review queues
  review/gov-*.html              readable review queues and evidence links
  review/advisories/             focused Luna advisory receipts
```

The program has no recurring scheduler and does not use Codex while running. Reports update after each batch. It does not commit, push, create releases, run Android tests, or publish data.
