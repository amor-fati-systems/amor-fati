# Operations

This document is the command-oriented operating guide for Amor Fati. It is not
model documentation; it records how to run, test, inspect, and compare the
current codebase from a local checkout.

## Operational Appendix Index

This document is the entry point for operational appendices. Use the table
below to find the owning page for CI, generated outputs, diagnostics,
profiling, scenarios, and local run artifacts. Detailed appendices should link
back here instead of each maintaining a separate top-level operations map.

| Need | Start here |
| --- | --- |
| Local setup, test commands, Nix shell, heavy tests, integration tests, output paths | This document |
| CI, generated-output guard, validation ownership, failure semantics | [Validation matrix](validation-matrix.md) |
| Nightly diagnostics profiles, health summary, run manifests, artifact retention | [Nightly diagnostics](nightly-diagnostics.md) |
| Nightly baseline comparison and threshold promotion policy | [Nightly baseline comparison](nightly-baseline-comparison.md) |
| Empirical validation snapshot regeneration | [Empirical Validation Snapshot](#empirical-validation-snapshot) |
| Hot-path profiling, JFR artifacts, performance telemetry | [Hot-path profiling](hot-path-profiling.md) and [performance regression budgets](performance-regression-budgets.md) |
| Named scenario runs and robustness envelopes | [Scenario registry](scenario-registry.md) and [sensitivity robustness workflow](sensitivity-robustness-workflow.md) |
| Focused banking/household/credit diagnostics | [Household credit stress calibration](household-credit-stress-calibration.md), [bank balance-sheet benchmark](bank-balance-sheet-benchmark.md), [bank failure ablations](bank-failure-ablations.md), [HH-bank lead-lag diagnostics](hh-bank-lead-lag-diagnostics.md), [loan-origination quality diagnostics](loan-origination-quality-diagnostics.md) |

## Requirements

Use the same toolchain shape as CI:

- JDK 21 as the supported baseline, Temurin in CI
- sbt 1.11.6, pinned in `project/build.properties`
- Scala 3.8.2, pinned in `build.sbt`
- Python 3 for local scripts such as `scripts/complexity.py`

Newer local JDKs may work, but diagnose toolchain failures against JDK 21
first.

No Docker or binary release artifact is required for the current local
workflow. Nix is optional; `flake.nix` provides a reproducible developer shell
for users who want CI-like tooling without installing the stack globally.

The runtime depends on `amor-fati-ledger`, checked out as the Git submodule at
`modules/ledger`. A checkout without that submodule is incomplete.

## First Run

Clone with submodules:

```bash
git clone --recurse-submodules https://github.com/boombustgroup/amor-fati.git
cd amor-fati
```

If the repository was cloned without submodules:

```bash
git submodule update --init --recursive
```

Then validate the checkout from the repository root:

```bash
sbt test
```

The first run may spend most of its time downloading the sbt launcher,
plugins, Scala compiler artifacts, and library dependencies through Coursier.

## Nix Development Shell

The repository includes a flake-based developer shell:

```bash
nix develop
```

The shell provides:

- JDK 21
- the nixpkgs sbt launcher, which respects the versions pinned by
  `project/build.properties` and `modules/ledger/project/build.properties`
- Python 3
- Z3
- Git, Bash, curl, unzip, and standard GNU shell utilities
- `SBT_OPTS=-Xmx8G -XX:+UseG1GC`, matching CI
- `AMOR_FATI_JAVA_OPTS=-Xmx8G -XX:+UseG1GC` and the `amor-fati-java`
  wrapper for assembled-JAR diagnostics

`flake.lock` pins the nixpkgs revision used by both local Nix shells and CI.
Update it intentionally with `nix flake update`.

Validate the shell with the same commands used outside Nix:

```bash
java -version
sbt scalafmtCheckAll
sbt test
z3 --version
```

This is a developer and CI-like validation shell, not a hermetic sbt/Nix
package build. sbt still resolves Scala, plugins, and library dependencies
through its normal Coursier path. Existing non-Nix workflows continue to work.
GitHub Actions runs the project CI commands through `nix develop` so the
checked shell is the same baseline used for formatting, tests, heavy tests,
and integration tests.

Use `amor-fati-java` instead of plain `java` for long assembled-JAR diagnostics
that should inherit the project heap defaults from `flake.nix`.

For `direnv` users, create a local `.envrc` from the checked-in example and
approve it:

```bash
cp .envrc.example .envrc
direnv allow
```

to enter the shell automatically when changing into the repository. `.envrc` is
ignored by git so non-Nix workflows do not see `direnv` warnings by default.

### Ledger Verification Tools

The Nix shell includes Z3 for ledger verification workflows. Stainless remains
controlled by `STAINLESS_DIR` and is not required for normal `sbt test` runs.
With the current `flake.lock`, the `z3` package resolves to Z3 4.15.4 from the
pinned nixos-25.11 snapshot. Treat Z3 version changes as verification-relevant:
Stainless proof search can be solver-version sensitive, so re-run ledger
verification after intentional Nix lock updates.
The ledger verification script defaults to `/tmp/stainless-standalone`:

```bash
nix develop
cd modules/ledger
STAINLESS_DIR=/tmp/stainless-standalone ./verify.sh
```

Install or download the Stainless standalone distribution separately and point
`STAINLESS_DIR` at that directory. When running from `nix develop`, `z3` is on
`PATH`; if a Stainless bundle insists on its bundled solver, replace the
bundle's `z3` binary with the one from `command -v z3`.

## Independent Clone Or Fork Workflow

Use a clone or fork when the work is a private counterfactual branch, a larger
mechanism change, or an experiment that should remain comparable with the
project baseline. This workflow does not assume contribution back to the
canonical repository.

Clone the repository directly:

```bash
git clone --recurse-submodules git@github.com:boombustgroup/amor-fati.git
cd amor-fati
```

Or clone your own fork and keep the canonical repository as `upstream` so you
can refresh the baseline when needed:

```bash
git clone --recurse-submodules git@github.com:<user>/amor-fati.git
cd amor-fati
git remote add upstream git@github.com:boombustgroup/amor-fati.git
git fetch upstream
```

Keep `main` as the baseline branch and do research work on named local
branches. For a direct clone, refresh from `origin` before creating an
experiment branch:

```bash
git checkout main
git pull --ff-only origin main
git submodule update --init --recursive
git checkout -b experiment/<short-name>
```

For a fork, refresh from `upstream` instead:

```bash
git checkout main
git fetch upstream
git merge --ff-only upstream/main
git submodule update --init --recursive
git checkout -b experiment/<short-name>
```

Before changing behavior, record a baseline command and keep the seed, duration,
and scenario selection fixed for the counterfactual run. For example:

```bash
sbt "runMain com.boombustgroup.amorfati.Main 1 baseline --duration 12 --run-id baseline-smoke"
sbt "runMain com.boombustgroup.amorfati.Main 1 counterfactual --duration 12 --run-id <short-name>-smoke"
```

For named scenarios, compare the same scenario set across branches:

```bash
sbt "scenarioRun --scenarios baseline,monetary-tightening --seeds 1 --months 12 --run-id baseline-review --out target/scenarios"
sbt "scenarioRun --scenarios baseline,monetary-tightening --seeds 1 --months 12 --run-id <short-name>-review --out target/scenarios"
```

Commit code, tests, and intentionally refreshed docs. Do not commit generated
local outputs from `mc/` or `target/`.

Before treating an experiment as locally valid:

```bash
sbt scalafmtCheckAll
sbt test
```

Also run the relevant heavy, integration, diagnostic, scenario, or robustness
commands from this document when the change touches shared month execution,
flows, Monte Carlo output, or research-facing scenario behavior. Sharing a
branch or opening a PR is optional and outside the assumed operating path.

## Run The Model

Minimal local run:

```bash
sbt "runMain com.boombustgroup.amorfati.Main 1 local-smoke --duration 12 --run-id smoke"
```

General form:

```bash
sbt "runMain com.boombustgroup.amorfati.Main <nSeeds> <prefix> [--duration <months>] [--run-id <id>] [--firm-snapshots <terminal|every:N|months:M1,M2,...|none>] [--household-snapshots <terminal|every:N|months:M1,M2,...|none>] [--household-snapshot-selector <all|negative|shortfall|negative-or-shortfall>] [--firm-decision-trace <ids:I1,I2,...|first:N|all|none>]"
```

The main runner writes generated TSV files under `mc/`:

```text
mc/<prefix>_<run-id>_<months>m_seed001.tsv
mc/<prefix>_<run-id>_<months>m_hh.tsv
mc/<prefix>_<run-id>_<months>m_banks.tsv
mc/<prefix>_<run-id>_<months>m_firms.tsv
```

Optional exports are disabled by default:

- `--firm-snapshots <terminal|every:N|months:M1,M2,...|none>` writes
  `mc/<prefix>_<run-id>_<months>m_firm_snapshots.tsv`.
- `--household-snapshots <terminal|every:N|months:M1,M2,...|none>` writes
  `mc/<prefix>_<run-id>_<months>m_household_snapshots.tsv` and
  `mc/<prefix>_<run-id>_<months>m_household_shortfall_cohorts.tsv`.
- `--household-snapshot-selector <all|negative|shortfall|negative-or-shortfall>`
  filters household snapshot rows.
- `--firm-decision-trace <ids:I1,I2,...|first:N|all|none>` writes
  `mc/<prefix>_<run-id>_<months>m_firm_decision_trace.tsv`.

Per-seed time-series TSV files emit macro PLN aggregates in Poland scale; do
not divide them by `gdpRatio` during empirical analysis. Output schema and
diagnostic-column semantics are owned by
[montecarlo/README.md](../modules/montecarlo/src/main/scala/com/boombustgroup/amorfati/montecarlo/README.md)
and the schema definitions it indexes.

Output ownership and commit policy are listed in [Output Locations](#output-locations).

## Empirical Validation Snapshot

The interpretation contract for empirical validation lives in
[empirical-validation-report.md](empirical-validation-report.md). This section
records the commands for regenerating the committed snapshot bundle.

Run the current baseline Monte Carlo batch:

```bash
sbt "runMain com.boombustgroup.amorfati.Main 5 validation-baseline --duration 60 --run-id main-0f281ce3"
```

Expected output files:

```text
mc/validation-baseline_main-0f281ce3_60m_seed001.tsv
mc/validation-baseline_main-0f281ce3_60m_seed002.tsv
mc/validation-baseline_main-0f281ce3_60m_seed003.tsv
mc/validation-baseline_main-0f281ce3_60m_seed004.tsv
mc/validation-baseline_main-0f281ce3_60m_seed005.tsv
mc/validation-baseline_main-0f281ce3_60m_hh.tsv
mc/validation-baseline_main-0f281ce3_60m_banks.tsv
mc/validation-baseline_main-0f281ce3_60m_firms.tsv
```

Then regenerate the committed validation bundle:

```bash
sbt "empiricalValidation --source-manifest docs/empirical-validation-source-manifest.tsv --mc-dir mc --run-id main-0f281ce3 --output-prefix validation-baseline --duration 60 --seeds 5 --commit 0f281ce3 --parameter-branch main --out docs/empirical-validation"
```

The generator writes:

```text
docs/empirical-validation/baseline-validation-snapshot.tsv
docs/empirical-validation/model-run-manifest.tsv
docs/empirical-validation/source-manifest.tsv
```

Edit `docs/empirical-validation-source-manifest.tsv` for source, mapping,
comparator, tolerance, and readiness metadata. Do not edit generated TSV files
under `docs/empirical-validation/` by hand.

## Tests

Default local tests:

```bash
sbt test
```

`sbt test` skips suites tagged with `com.boombustgroup.amorfati.tags.Heavy`.
This keeps normal local and coverage runs cheap.

Run one spec:

```bash
sbt "testOnly com.boombustgroup.amorfati.engine.FirmEntrySpec"
```

Run all heavy root tests:

```bash
sbt -DamorFati.includeHeavyTests=true "root / Test / testOnly * -- -n com.boombustgroup.amorfati.tags.Heavy"
```

Run all root tests, including heavy tests:

```bash
sbt -DamorFati.includeHeavyTests=true "root / Test / test"
```

Run integration tests:

```bash
sbt "integrationTests / Test / test"
```

Run the CI-style non-heavy coverage pass:

```bash
sbt coverage "root / Test / test" coverageReport
```

Check formatting:

```bash
sbt scalafmtCheckAll
```

Apply formatting:

```bash
sbt scalafmtAll
```

## Diagnostics

Use diagnostics when a model change needs a narrow view into one mechanism or
one reporting surface. Detailed interpretation belongs to the linked diagnostic
appendices.

The scheduled and manual validation profile contract lives in
[nightly-diagnostics.md](nightly-diagnostics.md). That document defines the
`smoke`, `nightly`, and `extended` diagnostic profiles used by planned
jar/Nix-based validation workflows.

Bankruptcy probe:

```bash
sbt "runMain com.boombustgroup.amorfati.diagnostics.runBankruptcyProbe 1 12"
```

Inflation probe:

```bash
sbt "runMain com.boombustgroup.amorfati.diagnostics.runInflationProbe 1 12"
```

Labor demand probe:

```bash
sbt "runMain com.boombustgroup.amorfati.diagnostics.runLaborDemandProbe 1 2"
```

[Household liquidity and credit-stress calibration](household-credit-stress-calibration.md):

```bash
sbt "householdCreditStressCalibration --seeds 5 --months 60 --out target/household-credit-stress --run-id household-credit-stress"
```

[Initial bank balance-sheet benchmark](bank-balance-sheet-benchmark.md):

```bash
sbt "bankBalanceSheetBenchmark --seeds 10 --out target/bank-balance-sheet-benchmark --run-id bank-balance-sheet-benchmark"
```

[Bank failure ablation diagnostics](bank-failure-ablations.md):

```bash
sbt "bankFailureAblations --seeds 2 --months 24 --parallelism 1 --out target/bank-failure-ablations --run-id bank-failure-ablations"
```

Use larger `--seeds` and `--months` values for heavier research runs.

[HH-to-bank lead-lag diagnostics](hh-bank-lead-lag-diagnostics.md):

```bash
sbt "hhBankLeadLagDiagnostics --seeds 2 --months 24 --lag-max 6 --out target/hh-bank-lead-lag --run-id hh-bank-lead-lag"
```

[Loan-origination quality diagnostics](loan-origination-quality-diagnostics.md):

```bash
sbt "loanOriginationQuality --seeds 2 --months 24 --outcome-window 12 --out target/loan-origination-quality --run-id loan-origination-quality"
```

For scratch and committed-snapshot SFC matrix exports, see
[sfc-matrix-evidence.md](sfc-matrix-evidence.md).

## Scenario And Robustness Runs

Named scenarios come from
`modules/model/src/main/scala/com/boombustgroup/amorfati/config/ScenarioRegistry.scala` and
are documented in [scenario-registry.md](scenario-registry.md).

Run a small scenario smoke check:

```bash
sbt "scenarioRun --scenarios baseline,monetary-tightening,fiscal-expansion --seeds 1 --months 12 --run-id scenario-smoke --out target/scenarios"
```

Run every registered scenario with a small seed envelope:

```bash
sbt "scenarioRun --scenarios all --seeds 2 --months 24 --run-id local-review --out target/scenarios"
```

Robustness scenario sets come from
`modules/model/src/main/scala/com/boombustgroup/amorfati/config/RobustnessScenarios.scala` and
are documented in
[sensitivity-robustness-workflow.md](sensitivity-robustness-workflow.md).

Fast robustness smoke check:

```bash
sbt "robustnessReport --scenario-set smoke --seeds 1 --months 6 --out target/robustness-smoke"
```

Local robustness review:

```bash
sbt "robustnessReport --scenario-set core --seeds 2 --months 24 --out target/robustness"
```

## Seeds And Comparisons

For counterfactual comparisons, keep these fixed unless the seed policy itself
is part of the experiment:

- seed count or seed range
- duration in months
- scenario id or changed parameter set
- run id and output folder naming convention

The main Monte Carlo runner uses seeds `1..nSeeds`. Diagnostic entrypoints
accept explicit seed arguments. The monthly runtime randomness is derived
deterministically from the seed and month boundary, so equal seeds and equal
parameters are the basis for reproducible comparisons.

## Output Locations

Generated local outputs normally belong in ignored paths. Commit only the
explicitly committed documentation artifacts:

| Path | Producer | Commit? |
| --- | --- | --- |
| `mc/` | Main Monte Carlo runner | No |
| `target/sfc-matrices/` | Scratch SFC matrix exports | No |
| `target/scenarios/` | Scenario registry runs | No |
| `target/robustness*` | Robustness reports | No |
| `<out>/<run-id>/` | Focused diagnostics using explicit `--out` and `--run-id` arguments | No |
| `docs/sfc-matrix-artifacts/` | Intentional committed matrix snapshots | Yes, only when refreshed intentionally |
| `docs/empirical-validation/` | Empirical-validation snapshot bundle | Yes, only when refreshed intentionally |

### Documentation Hygiene Guard

Pull-request CI also runs `scripts/check-docs.py` under the same Nix
development shell. The documentation hygiene guard validates local Markdown
links, local Markdown anchors, and the [README.md](README.md)
inventory coverage for every committed `docs/` artifact. It does not lint prose
style or check external URLs.

Run the documentation hygiene check locally with:

```bash
nix develop --command python3 scripts/check-docs.py
```

### Generated Output Guard

Pull-request CI runs `scripts/check-generated-outputs.sh` under the Nix
development shell. The guard regenerates the fast committed artifacts
[calibration-register.md](calibration-register.md) and `docs/sfc-matrix-artifacts/`, then fails if
those paths have a tracked diff or new untracked files.

Run the same check locally with:

```bash
nix develop --command bash scripts/check-generated-outputs.sh
```

`docs/empirical-validation/` is intentionally outside this fast guard because
it depends on a matching baseline Monte Carlo run. Nightly diagnostics own that
longer evidence surface.

## Working Loop

A practical local loop for model changes:

1. Make a focused mechanism, parameter, or accounting change.
2. Execute the most specific affected spec with `sbt "testOnly ..."`.
3. Then run `sbt test`.
4. Perform a narrow diagnostic if the change touches bankruptcy, labor demand,
   inflation, matrices, scenarios, or robustness behavior.
5. Execute heavy and integration tests when the change touches month execution,
   flow simulation, Monte Carlo output, or shared ledger behavior.
6. Compare generated TSVs or reports under `mc/` or `target/` before committing.

## Troubleshooting

If compilation fails with classfile or toolchain errors, check that the active
JDK is 21.

If the first test run is slow, verify whether sbt/Coursier is still downloading
dependencies. Subsequent runs should be much faster.

If `sbt test` is fast but CI-like validation is needed, remember that heavy
tests and integration tests are separate commands.

If a simulation fails with an SFC violation, treat it as an accounting or flow
execution bug first. Behavioral instability is allowed; broken monetary
plumbing is not.

If generated results look stale, check the `--run-id`, `--out`, duration, seed
count, and scenario arguments before comparing files.
