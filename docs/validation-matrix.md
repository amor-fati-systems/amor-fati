# Validation Matrix and Ownership Boundaries

This document is the ownership map for engine validation. It describes the
checks that already exist and the layer each one owns. It is intentionally not a
replacement for CI, integration tests, generated-output checks, or nightly
diagnostics; it is the routing rule that keeps future validation work from
duplicating those layers.

## Principles

- PR feedback should stay fast enough for normal development.
- Accounting and runtime-execution failures are hard correctness failures.
- Macro-financial research metrics are health signals until a threshold is
  explicitly justified.
- Normal-validation profiles and stress/exploratory profiles must be interpreted
  separately.
- Long diagnostics should reuse the existing jar/Nix nightly runner and its
  artifacts instead of adding duplicate long simulations.
- Generated repository outputs are committed evidence; if regeneration changes
  them, the diff must be reviewed and committed.

## Current Matrix

| Layer | Trigger | Owner | Command / workflow | Primary purpose | Failure semantics |
| --- | --- | --- | --- | --- | --- |
| Generated outputs | PR and push | `.github/workflows/ci.yml` `generated-outputs` job | `nix develop --command bash scripts/check-generated-outputs.sh` | Prove generated docs/resources match checked-in sources | Hard fail on stale or missing generated output |
| Nix environment | PR and push | `.github/workflows/ci.yml` `test` job | `nix flake check --print-build-logs`; dev-shell version checks | Ensure the reproducible build shell works | Hard fail on broken Nix flake or missing toolchain |
| Formatting | PR and push | `.github/workflows/ci.yml` `test` job | `nix develop --command sbt scalafmtCheckAll` | Keep Scala formatting deterministic | Hard fail on formatting drift |
| Non-heavy unit tests | PR and push | `.github/workflows/ci.yml` `test` job | `nix develop --command sbt coverage "root / Test / test" coverageReport` | Fast correctness for mechanisms, economics boundaries, flows, SFC, ledger projection, diagnostics helpers, and schemas | Hard fail on broken local invariant or regression |
| Heavy unit tests | PR and push | `.github/workflows/ci.yml` `test` job | `nix develop --command sbt -DamorFati.includeHeavyTests=true "root / Test / testOnly * -- -n com.boombustgroup.amorfati.tags.Heavy"` | Selected expensive unit/property checks that should not run under coverage | Hard fail on broken heavy invariant |
| Integration smoke | PR and push | `.github/workflows/ci.yml` `test` job | `nix develop --command sbt "integrationTests / Test / test"` | End-to-end smoke over the root project and integration-test project | Hard fail on integration-level regression |
| Coverage upload | PR and push | `.github/workflows/ci.yml` `test` job | `codecov/codecov-action` | Publish non-heavy unit coverage | Non-blocking upload; CI does not fail if Codecov upload fails |
| Nightly diagnostics | Scheduled on `main`; manual dispatch | `.github/workflows/diagnostics-{smoke,nightly,extended}.yml` via reusable diagnostics workflow | Build `sbt assembly` under Nix, then run `NightlyDiagnosticsProfileRunner` from the jar | Long validation and diagnostics artifacts over `smoke`, `nightly`, and `extended` profiles | Hard fail on runner/build failure and hard invariants; economic outcomes are interpreted by profile classification |
| Nightly health summary | After a non-dry-run diagnostics profile completes | `NightlyHealthSummary` via `NightlyDiagnosticsProfileRunner` | Reuse `run-manifest.json` and baseline Monte Carlo seed CSVs to write `health-summary.json` and `health-summary.md` | Compact machine/human verdict answering whether `main` stayed normal-path healthy overnight | Hard fail on normal-validation threshold breaches; warn/report for soft research signals; do not turn stress/exploratory outcomes into normal-path failures |
| Nightly performance telemetry | Every diagnostics profile step | `NightlyDiagnosticsProfileRunner` manifest telemetry | Per-step duration, seed-month throughput, artifact size/row counts, and JVM memory/GC observations in `run-manifest.json` | Lightweight regression visibility before heavier profilers exist | Report-only initially; hard performance budgets belong to later baseline/regression work |
| Manual diagnostics | Local or workflow dispatch | Maintainer | `nix develop --command java -cp target/scala-3.8.2/amor-fati.jar com.boombustgroup.amorfati.diagnostics.NightlyDiagnosticsProfileRunner ...` | Reproduce or inspect profile outputs outside PR CI | Evidence only unless explicitly promoted to a CI/nightly gate |
| Hot-path profiling | Manual or weekly on `main` | `.github/workflows/hot-path-profiling-{smoke,nightly,extended}.yml` via reusable profiling workflow | Build `sbt assembly` under Nix, then run a profiled diagnostics workload with JFR | Hot-path timing/allocation visibility for FlowSimulation, banking, firms, households, runtime ledger execution, SFC projection, and diagnostics exports | Report-only; workflow fails on build/profiling/JFR capture failure, but hard performance budgets belong to #688 |

## Existing Integration Ownership

The current integration suite is intentionally small. Its main existing test is
`McRunnerCsvIntegrationSpec`, which validates deterministic Monte Carlo CSV
output, schema headers, snapshot files, and bank-capital waterfall accounting on
a short run. It is not the owner of every macro-financial sanity condition.

New PR-level normal-path engine-health checks belong in integration tests only
when they need a short multi-month engine run and cannot be expressed as a
focused unit or property test. #683 extends this layer with a non-CSV baseline
invariant gate.

## Nightly Profile Ownership

The nightly workflow is the owner for long-running diagnostics, research health
evidence, lightweight performance telemetry, and the compact health verdict
derived from those artifacts. It runs from the assembled jar under the Nix
environment, not from ad hoc local classpaths.

| Profile | Trigger | Current intent | Typical interpretation |
| --- | --- | --- | --- |
| `smoke` | Scheduled daily on `main`, manual dispatch, local reproduction | Fast profile contract and artifact sanity | Hard invariants only; research metrics are informational |
| `nightly` | Scheduled daily on `main`, manual dispatch | Standard 60-month validation horizon | Hard invariants fail; soft calibration guardrails warn unless promoted |
| `extended` | Scheduled weekly on `main`, manual dispatch | Wider seed/scenario/diagnostic surface at the current 60-month horizon | Same hard invariants; broader research envelope reporting |

The profile definitions and per-step semantic classifications live in
`NightlyDiagnosticsProfileRunner.Profiles` and are documented in
[nightly-diagnostics.md](nightly-diagnostics.md). Every nightly manifest records
each step's classification and failure policy. `NightlyHealthSummary` then
post-processes existing artifacts into `health-summary.json` and
`health-summary.md`; it is the current owner of thresholded nightly verdicts.
Comparison semantics live in
[nightly-baseline-comparison.md](nightly-baseline-comparison.md).
The reviewer-facing invariant and economic-semantics index lives in
[engine-invariants-and-semantics.md](engine-invariants-and-semantics.md).

## Normal, Stress, Exploratory, And Benchmark Semantics

Validation results must be interpreted by profile class:

| Class | Meaning | Failure policy |
| --- | --- | --- |
| Normal validation | Intended baseline path for the model's current operational horizon | Hard accounting/runtime failures fail; encoded normal-path bank failures, artifact gaps, impossible ratios, and material accounting residuals fail through integration gates or nightly health summary |
| Stress validation | Deliberately adverse assumptions used to test failure channels | Accounting/runtime failures fail; expected economic stress outcomes are reported with stress semantics |
| Exploratory diagnostics | Research probes without stable thresholds | Report-only unless a hard invariant is breached |
| Benchmark | Snapshot or balance-sheet evidence used for review/calibration | Fail malformed output or hard accounting errors; economic deltas require explicit thresholds |
| Profiling | Runtime and allocation observability | Start as report/warn; fail only after a stable baseline and budget are defined |

Existing nightly steps are explicitly classified into these classes in the
runner manifest. Scheduled `nightly` evidence excludes stress-only diagnostics;
manual `extended` evidence may include stress-validation steps, which must be
interpreted by their stress semantics rather than as normal-path calibration
failures.

## Where New Validation Belongs

Use this routing rule before adding a new check:

| New check type | Put it in | Do not put it in |
| --- | --- | --- |
| Local mechanism invariant, algebraic identity, schema rule, parser behavior | Root unit/property tests | Nightly diagnostics |
| Slow but focused mechanism/property test | Heavy-tagged root test | Integration tests unless it needs end-to-end state |
| Short multi-month normal-path engine health | `integrationTests / Test / test` | CSV determinism tests |
| Generated docs/resources consistency | Existing generated-output script | Unit tests |
| Long Monte Carlo, scenario, robustness, diagnostic export validation | Nightly diagnostics profile | PR unit tests |
| Stress/failure-channel experiments | Stress/exploratory nightly class | Normal-validation gate |
| Hot-path timing or allocation visibility | [Hot-path profiling](hot-path-profiling.md) and telemetry artifacts | Correctness unit tests |
| Thresholded nightly health summary | `NightlyHealthSummary` and docs/nightly comparison contract | Diagnostic exporters themselves |

## Promotion Policy

A check can become a hard gate only when the contract is clear:

- accounting exactness, ledger conservation, malformed output, missing required
  artifacts, and impossible stock states are hard failures by default;
- calibration and research metrics require a written threshold rationale before
  they can fail CI;
- stress-profile findings must not mask normal-profile health;
- health-summary hard thresholds must reuse existing nightly artifacts instead
  of launching another simulation path;
- profiling budgets should start as warnings and become hard gates only for
  low-noise metrics tied to commit/profile metadata.

## Relationship To Milestone #28

This matrix is the base layer for the rest of Engine Validation & Performance
Observatory:

- #683 adds the missing PR-level non-CSV normal-path integration gate.
- #684 separates normal-validation diagnostics from stress and exploratory
  profile semantics.
- #685 adds thresholded nightly health summaries from existing artifacts.
- #686 captures performance telemetry in nightly manifests.
- #687 adds a manual or weekly hot-path profiling workflow under Nix.
- #688 introduces performance baselines and regression budgets.
- #689 creates the canonical invariants and economic-semantics index.
- #690 maps `FlowMechanism` semantics to ledger legs, SFC identities, and tests.
