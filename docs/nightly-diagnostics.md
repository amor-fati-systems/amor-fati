# Nightly Diagnostics

This document defines the diagnostic profiles that should run against `HEAD` of
`main`. It is a profile contract for the nightly validation milestone, not the
implementation of the runner or GitHub Actions workflow.

The goal is to keep long-running validation reproducible, inspectable, and
scientifically honest. The profiles below validate the currently interpretable
operational horizon of the model. They are not long-horizon cycle claims.
The comparison semantics for these profiles are defined in
[nightly-baseline-comparison.md](nightly-baseline-comparison.md).

Operational appendix entry point:
[operations.md#operational-appendix-index](operations.md#operational-appendix-index).
Use this page for diagnostics profile semantics after starting from the
operations index.

## Principles

- Run against a clean `main` ref, not a dirty local checkout.
- Prefer the assembled jar under the project Nix environment for scheduled
  runs.
- Use fixed seed ranges and explicit month horizons.
- Write outputs under a profile-scoped root, not under the default local `mc/`
  directory.
- Emit a machine-readable run manifest for every profile run.
- Treat SFC exactness, ledger consistency, missing months, malformed TSV, and
  impossible accounting states as hard failures.
- Treat economic research metrics as report or warning signals until their
  thresholds are explicitly justified.

## Output Conventions

Profile outputs should use:

```text
target/nightly-diagnostics/<profile>/<run-id>/
```

Scheduled run ids should use:

```text
<profile>-<yyyyMMdd>-<shortSha>
```

Manual run ids should use:

```text
<profile>-manual-<yyyyMMdd-HHmm>-<shortSha>
```

Every run should include a manifest with at least:

- profile id
- git SHA and branch or ref
- jar path and jar hash when available
- seed policy and seed range
- month horizon
- logical diagnostic steps
- per-step semantic classification and failure policy
- per-step runtime telemetry: duration, normalized seed-month throughput where
  applicable, artifact sizes, TSV row counts, and memory/GC observations
- output paths
- start and end timestamps
- Nix, Java, sbt, and project versions where practical
- JVM and OS runtime metadata where practical

Every non-dry-run profile should also include compact health summaries:

- `health-summary.json`: machine-readable profile verdict, thresholded metrics,
  and hard-failure counts
- `health-summary.md`: reviewer-facing table with the same verdict and metrics

GitHub Actions diagnostics runs additionally write soft performance-budget
reports under `performance/`. These reports compare the current manifest to the
newest same-profile baseline artifact on `main` when one is available. The
policy is documented in
[performance-regression-budgets.md](performance-regression-budgets.md).
Manual workflow dispatch can run against a feature branch for pre-merge
validation; scheduled diagnostics remain pinned to clean `main`.

## Jar / Nix Execution

Build the assembled jar from the project Nix environment:

```bash
nix develop --command sbt assembly
```

Run a profile from that jar with the diagnostics profile runner:

```bash
nix develop --command amor-fati-java -cp target/scala-3.8.2/amor-fati.jar \
  com.boombustgroup.amorfati.diagnostics.NightlyDiagnosticsProfileRunner \
  --profile smoke \
  --out target/nightly-diagnostics \
  --run-id smoke-manual-20260526-abcdef0 \
  --jar-path target/scala-3.8.2/amor-fati.jar
```

`java -jar target/scala-3.8.2/amor-fati.jar` remains the normal simulation
entry point (`com.boombustgroup.amorfati.Main`). Diagnostics profiles use
`amor-fati-java -cp ...
com.boombustgroup.amorfati.diagnostics.NightlyDiagnosticsProfileRunner` so they
can select the profile runner while still executing from the assembled jar with
the project heap defaults from `flake.nix`.

Scheduled `main` runs should require a clean `main` ref:

```bash
nix develop --command amor-fati-java -cp target/scala-3.8.2/amor-fati.jar \
  com.boombustgroup.amorfati.diagnostics.NightlyDiagnosticsProfileRunner \
  --profile nightly \
  --out target/nightly-diagnostics \
  --run-id nightly-20260526-abcdef0 \
  --jar-path target/scala-3.8.2/amor-fati.jar \
  --require-main
```

Local exploratory runs can pass `--allow-dirty` only when the output is not
treated as nightly evidence. `--dry-run` writes the manifest and planned step
contract without executing simulations, which is useful for CI wiring and
profile review.

The runner writes:

```text
target/nightly-diagnostics/<profile>/<run-id>/run-manifest.json
target/nightly-diagnostics/<profile>/<run-id>/health-summary.json
target/nightly-diagnostics/<profile>/<run-id>/health-summary.md
target/nightly-diagnostics/<profile>/<run-id>/performance/performance-regression-report.json
target/nightly-diagnostics/<profile>/<run-id>/performance/performance-regression-report.md
```

The manifest records the profile, resolved git ref, dirty-tree status, jar path
and SHA-256 when available, command line, tool versions, step seed/month
settings, step output directories, artifact paths, timestamps, lightweight
performance telemetry, JVM/OS runtime metadata, and final status.

Per-step telemetry is intentionally orchestration-level evidence, not a
correctness test. It records elapsed step time, `seeds × months` throughput when
both dimensions are meaningful, emitted artifact file counts and bytes, TSV row
counts where available, and before/after memory plus GC deltas when the JVM
exposes them. Missing or partial telemetry is represented in the manifest rather
than launching duplicate simulations or adding assertions inside diagnostic
exporters.

The performance-regression report is also orchestration-level evidence. It is a
soft warning surface, not a diagnostics failure gate.

The health summary reuses artifacts emitted by the configured diagnostics. It
does not launch additional simulations. The first threshold layer reads the
baseline Monte Carlo seed TSVs and manifest step statuses to produce a stable
normal-path verdict over:

- diagnostic completion by step classification
- baseline artifact completeness (`seeds × months` monthly rows)
- normal-path bank failures and bank-resolution invariant counters
- positive GDP proxy direction, with terminal decline reported as a warning
- total credit to GDP blow-up bounds
- default/NPL ratio bounds
- bank-capital and flow-of-funds residual guards relative to annualized GDP
- household negative-deposit diagnostic counts as warnings

Hard threshold breaches in normal-validation evidence fail the runner after the
summary files have been written. Exploratory and stress steps remain visible in
completion metrics, but their economic outcomes are not reclassified as
scheduled normal-path failures.

## Scheduled Workflows

The GitHub Actions workflows below run the same jar/Nix path:

```text
.github/workflows/diagnostics-smoke.yml
.github/workflows/diagnostics-nightly.yml
.github/workflows/diagnostics-extended.yml
```

They delegate to:

```text
.github/workflows/diagnostics-reusable.yml
```

This keeps the Actions UI and README badges profile-specific without copying
the build/run/upload implementation. These workflows are intentionally not
attached to `pull_request`, so long diagnostics do not block normal PR
feedback.

Triggers:

- `schedule`: runs each fixed profile against `HEAD` of `main`
- `workflow_dispatch`: lets maintainers manually launch the selected fixed
  profile

Each workflow checks out `main`, builds the assembled jar with:

```bash
nix develop --command sbt assembly
```

and runs:

```bash
nix develop --command amor-fati-java -cp target/scala-3.8.2/amor-fati.jar \
  com.boombustgroup.amorfati.diagnostics.NightlyDiagnosticsProfileRunner \
  --profile <profile> \
  --out target/nightly-diagnostics \
  --run-id <resolved-run-id> \
  --jar-path target/scala-3.8.2/amor-fati.jar \
  --require-main
```

Scheduled workflows use profile-specific crons:

| Profile | Workflow | Schedule |
| --- | --- | --- |
| `smoke` | `diagnostics-smoke.yml` | daily `23:00` UTC |
| `nightly` | `diagnostics-nightly.yml` | daily `00:00` UTC |
| `extended` | `diagnostics-extended.yml` | weekly, Saturday `01:00` UTC |

During CEST, these correspond to 01:00, 02:00, and 03:00 Europe/Warsaw
respectively. GitHub cron does not track local daylight saving time, so
maintainers should adjust the cron if exact local wall-clock timing is required
during CET months.

The job summary reports the profile, commit SHA, run id, runtime, manifest path,
health-summary status, terminal status, build/runtime exit codes, and failure
reason when either the build or diagnostics runner produces one. The workflow
also uploads a GitHub Actions artifact named
`amor-fati-diagnostics-<run-id>` containing the profile run directory,
`build.log`, and `diagnostics.log` when those files exist. Failed runs still
upload partial logs, manifests, and health summaries when the build has
progressed far enough to create them.

Artifact retention is profile-specific:

- `smoke`: 7 days
- `nightly`: 30 days
- `extended`: 90 days

## Profile Matrix

| Profile | Intended Trigger | Horizon | Primary Semantics | Purpose |
| --- | --- | --- | --- | --- |
| `smoke` | local, manual, possibly PR if runtime stays acceptable | 12 months | normal validation plus benchmark/exploratory evidence | Fast sanity check and invariant validation without stress-only assumptions |
| `nightly` | scheduled daily on `main`, plus manual dispatch | 60 months | normal validation plus benchmark/exploratory evidence | Daily research validation over the standard five-year horizon without stress-only assumptions |
| `extended` | weekly or manual | 60 months | exploratory and stress validation | Wider seed, scenario, and diagnostic coverage over the same five-year horizon |

## Step Classification

Each manifest step carries a `classification` and `failure_policy`:

| Classification | Meaning | Failure Policy |
| --- | --- | --- |
| `normal_validation` | Intended baseline-path evidence for the model's current operational horizon | Hard accounting/runtime failures fail; economic failures are interpreted as normal-path engine or calibration alarms. |
| `stress_validation` | Deliberately adverse assumptions used to exercise stress channels | Hard accounting/runtime failures fail; expected stress outcomes are interpreted through stress-channel semantics. |
| `exploratory` | Research probes or comparison surfaces without stable thresholds | Hard accounting/runtime failures fail; economic metrics are report-only until thresholds are explicitly promoted. |
| `benchmark` | Opening balance-sheet or snapshot evidence for review/calibration | Malformed output and hard accounting errors fail; economic deltas require explicit benchmark thresholds. |

Scheduled `nightly` evidence must not silently include stress-only assumptions.
Stress-channel diagnostics such as bank-failure ablations belong to manual
`extended` evidence until a dedicated stress workflow/profile is introduced.

## Smoke Profile

Purpose: fast sanity validation for local/manual checks and potentially
PR-scoped checks if runtime remains acceptable.

Recommended steps:

- baseline Monte Carlo: 1 seed, 12 months, snapshots disabled, decision traces
  disabled
- SFC matrix evidence: seed 1, 12 months, Markdown output under `target`
- scenario smoke: `baseline,monetary-tightening,fiscal-expansion`, 1 seed,
  12 months
- robustness smoke: `--scenario-set smoke`, 1 seed, 6 months
- bank balance-sheet benchmark: 2 seeds
- household credit-stress calibration: 1 seed, 12 months

Comparison mode:

- hard-fail only on invariants and malformed output
- report research metrics without tight thresholds
- no stress-only assumptions; bank-failure ablations are excluded from this
  normal-validation smoke profile

Primary artifacts:

- seed time-series TSV
- terminal household, bank, and firm summaries
- SFC matrix evidence
- scenario run summary
- robustness smoke summary
- bank and household diagnostic summaries

## Nightly Profile

Purpose: daily scheduled validation against `HEAD` of `main`.

Recommended steps:

- baseline Monte Carlo: 5 seeds, 60 months, snapshots disabled, decision traces
  disabled
- empirical validation post-process over the baseline Monte Carlo outputs and
  run manifest
- scenario run: default scenario registry set, 5 seeds, 60 months
- robustness report: `--scenario-set core`, 2 seeds, 24 months
- bank balance-sheet benchmark: 10 seeds
- household credit-stress calibration: 5 seeds, 60 months
- HH-bank lead-lag diagnostics: 5 seeds, 60 months, lag max 6
- loan-origination quality diagnostics: 2 seeds, 60 months, outcome window 12

Comparison mode:

- hard-fail on invariants, missing months, malformed TSV, impossible
  accounting states, SFC violations, and ledger failures
- report or warn on research metrics such as GDP, inflation, unemployment,
  total credit to GDP, debt to GDP, current account to GDP, bank failures,
  credit losses, household bankruptcies, credit rejection rates, and approval
  stochasticity
- no stress-only assumptions; expected stress-channel failures are not part of
  scheduled normal-validation evidence

Primary artifacts:

- baseline Monte Carlo TSV outputs
- empirical validation snapshot and manifest
- scenario registry artifacts and run summary
- robustness envelope and sensitivity reports
- household credit-stress outputs
- bank balance-sheet benchmark outputs
- HH-bank lead-lag outputs
- loan-origination quality outputs
- profile manifest and logs
- health-summary JSON and Markdown verdicts
- performance-regression JSON and Markdown report

## Extended Profile

Purpose: heavier weekly/manual validation over the same currently interpretable
five-year horizon, with broader seed, scenario, and diagnostic coverage than
`nightly`. This profile is not a 10-year cycle claim.

Recommended steps:

- baseline Monte Carlo: 10 seeds, 60 months, snapshots disabled by default
- optional terminal household and firm snapshots when artifact volume is
  acceptable
- scenario run: all registered scenarios, 5 seeds, 60 months
- robustness report: `--scenario-set core`, 5 seeds, 60 months
- bank balance-sheet benchmark: 10 seeds
- household credit-stress calibration: 10 seeds, 60 months
- bank failure ablations: 10 seeds, 60 months, parallelism 2, classified as
  `stress_validation`
- HH-bank lead-lag diagnostics: 10 seeds, 60 months, lag max 12
- loan-origination quality diagnostics: 5 seeds, 60 months, outcome window 12

Comparison mode:

- same hard invariants as `nightly`
- wider research comparison envelope over a larger seed and scenario surface
- stress-channel findings are interpreted by `stress_validation` semantics and
  do not define scheduled normal-path health
- no long-horizon cycle or regime interpretation

Primary artifacts:

- all `nightly` artifact classes
- larger seed-envelope summaries
- all-scenario comparison summaries
- optional terminal micro snapshots when explicitly enabled

## Comparison Candidates

The baseline comparison layer should be profile-aware. The first comparison
version should start with hard invariants and report-only research metrics.
The detailed status model, threshold contract, and report format are specified
in [nightly-baseline-comparison.md](nightly-baseline-comparison.md).

Hard-fail candidates:

- SFC validation failures
- ledger conservation or survivability failures
- missing monthly rows
- malformed TSV files
- impossible accounting states
- invalid active-bank invariants
- non-finite numeric outputs

Report or warning candidates:

- GDP and GDP growth proxies
- inflation and unemployment
- total credit to GDP
- debt to GDP
- current account to GDP
- bank failures and failure reasons
- realized credit losses and ECL provision changes
- household bankruptcy and liquidity shortfall rates
- consumer-credit approval and rejection rates
- firm-credit approval and rejection rates
- loan-origination bad-outcome cohorts
- scenario deltas versus baseline

## Long Horizon Is Out Of Scope

`120m+` belongs to a future long-horizon or cycle-validation profile, not this
milestone. Cycle, trend, and endogenous regime diagnostics are deliberately not
part of `smoke`, `nightly`, or `extended`.

First the model needs a stable five-year validation horizon and mechanism-level
diagnostics. Ten-year cycle claims should return only after the long-horizon
research milestone defines interpretation rules, diagnostics, and validation
targets.

## Implementation Notes

The scheduled workflow does not encode a long list of ad hoc diagnostic
commands directly in YAML. It builds the assembled jar, invokes the single
jar-runnable diagnostics profile runner, and lets the Scala runner own profile
step selection, manifest emission, output layout, and clean-ref validation.
