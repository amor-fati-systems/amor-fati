# Operations

This document is the command-oriented operating guide for Amor Fati. It is not
model documentation; it records how to run, test, inspect, and compare the
current codebase from a local checkout.

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
- `SBT_OPTS=-Xmx4G -XX:+UseG1GC`, matching CI

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

The main runtime entrypoint is:

```bash
sbt "runMain com.boombustgroup.amorfati.Main <nSeeds> <prefix> [--duration <months>] [--run-id <id>] [--firm-snapshots <terminal|every:N|months:M1,M2,...>] [--firm-decision-trace <ids:I1,I2,...|first:N|all|none>]"
```

Example smoke run:

```bash
sbt "runMain com.boombustgroup.amorfati.Main 1 local-smoke --duration 12 --run-id smoke"
```

The main runner writes generated CSV files under `mc/`:

```text
mc/<prefix>_<run-id>_<months>m_seed001.csv
mc/<prefix>_<run-id>_<months>m_hh.csv
mc/<prefix>_<run-id>_<months>m_banks.csv
mc/<prefix>_<run-id>_<months>m_firms.csv
```

Per-seed time-series CSV files emit macro PLN aggregates in Poland scale, ready
for empirical analysis. The internal `gdpRatio` scaling factor is not emitted
as a CSV column; it remains a model-computation boundary. Agent-level prices,
wages, indexes, rates, shares, and counts remain in their native units.

The seed time-series files also include always-on aggregate diagnostic blocks
for household liquidity, firm automation/adoption, bank capital, and bank
failure triggers. The bank capital block uses `BankCapital_*` columns to
reconcile opening capital, retained income, realized credit losses, provisions,
valuation losses, interbank contagion losses, failure-related capital
destruction, reconciliation residuals, and closing capital. The `BankEcl_*`
block attributes IFRS 9 provision changes
to opening/closing allowance, an all-Stage-1 baseline allowance, excess
allowance, and S2/S3 migration shares. The `BankFailure_*` block identifies the
monthly primary trigger for newly failed banks. The `BankCreditLoss_*` block
reports realized loss/default/write-off rates by major exposure class and keeps
them separate from `BankCapital_EclProvisionChange`. The
`BankReconciliation_*` block identifies the bank row that absorbed the exactness
patch, reports capital and CAR before/after that patch, and flags material
residuals or residual-induced failure-threshold crossings. No extra flag is
needed for these aggregate diagnostics.

Firm-level micro snapshots are optional and off by default. Enable them with
`--firm-snapshots terminal`, `--firm-snapshots every:12`, or
`--firm-snapshots months:1,6,12`. When enabled, the runner also writes:

```text
mc/<prefix>_<run-id>_<months>m_firm_snapshots.csv
```

Firm decision traces are optional and off by default. Enable them with an
explicit firm-id selector such as `--firm-decision-trace ids:0,42,99`, with the
documented deterministic low-id sample `--firm-decision-trace first:25`, or with
`--firm-decision-trace all` for tiny/debug runs. The selector is evaluated by
firm id and does not use the model RNG. When enabled, the runner also writes:

```text
mc/<prefix>_<run-id>_<months>m_firm_decision_trace.csv
```

The trace records one selected-firm row per month with opening/closing tech
state, decision type, bankruptcy reason, cash/debt/readiness/workers before and
after, capex, new bank loan, down payment where applicable, relationship bank,
lending rate, available approval/feasibility/probability flags, and separate
adoption, implementation, upgrade-candidate bank approval, investment-credit
bank approval, digital-invest, upgrade-efficiency, and labor-adjustment residual
rolls where those gates were evaluated.

`mc/` is ignored by git. Keep committed research-facing artifacts under `docs/`
only when the command explicitly targets a committed documentation path.

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
one reporting surface.

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

Household liquidity and credit-stress calibration:

```bash
sbt "householdCreditStressCalibration --seeds 5 --months 60 --out target/household-credit-stress --run-id household-credit-stress"
```

This writes terminal household credit-stress ratios, target bands, and a
summary report under `<out>/<run-id>/`. With the command above, the concrete
output directory is
`target/household-credit-stress/household-credit-stress/`. The semantics and
Poland-relevant guardrail bands are documented in
[household-credit-stress-calibration.md](household-credit-stress-calibration.md).

Initial bank balance-sheet benchmark:

```bash
sbt "bankBalanceSheetBenchmark --seeds 10 --out target/bank-balance-sheet-benchmark --run-id bank-balance-sheet-benchmark"
```

This writes opening bank balance-sheet ratios, per-bank rows, target bands, and
a summary report under `<out>/<run-id>/`. With the command above, the concrete
output directory is
`target/bank-balance-sheet-benchmark/bank-balance-sheet-benchmark/`. The
semantics and Poland-relevant guardrail bands are documented in
[bank-balance-sheet-benchmark.md](bank-balance-sheet-benchmark.md).

Bank failure ablation diagnostics:

```bash
sbt "bankFailureAblations --seeds 2 --months 24 --out target/bank-failure-ablations --run-id bank-failure-ablations"
```

This writes baseline-versus-ablation seed rows, scenario definitions, and a
summary report under `<out>/<run-id>/`. With the command above, the concrete
output directory is `target/bank-failure-ablations/bank-failure-ablations/`.
The semantics and scenario set are documented in
[bank-failure-ablations.md](bank-failure-ablations.md). Use larger
`--seeds`/`--months` values for heavier research runs.

HH-to-bank lead-lag diagnostics:

```bash
sbt "hhBankLeadLagDiagnostics --seeds 2 --months 24 --lag-max 6 --out target/hh-bank-lead-lag --run-id hh-bank-lead-lag"
```

This writes per-bank/month household-stress routing rows, lead-lag correlation
tables, consumer-credit counterfactual rows, and a summary report under
`<out>/<run-id>/`. With the command above, the concrete output directory is
`target/hh-bank-lead-lag/hh-bank-lead-lag/`. The export is documented in
[hh-bank-lead-lag-diagnostics.md](hh-bank-lead-lag-diagnostics.md). It uses
the shared Monte Carlo diagnostic runner, so scenario/seed jobs run with the
same bounded parallelism model as `Main`.

Loan-origination quality diagnostics:

```bash
sbt "loanOriginationQuality --seeds 2 --months 24 --outcome-window 12 --out target/loan-origination-quality --run-id loan-origination-quality"
```

This writes household and firm credit-decision rows, cohort summaries, and a
summary report under `<out>/<run-id>/`. With the command above, the concrete
output directory is
`target/loan-origination-quality/loan-origination-quality/`. The export is
documented in
[loan-origination-quality-diagnostics.md](loan-origination-quality-diagnostics.md).

For scratch and committed-snapshot SFC matrix exports, see
[sfc-matrix-evidence.md](sfc-matrix-evidence.md).

## Scenario And Robustness Runs

Named scenarios come from
`src/main/scala/com/boombustgroup/amorfati/config/ScenarioRegistry.scala` and
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
`src/main/scala/com/boombustgroup/amorfati/config/RobustnessScenarios.scala` and
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

Generated local outputs normally belong in ignored paths:

| Path | Producer | Commit? |
| --- | --- | --- |
| `mc/` | Main Monte Carlo runner | No |
| `target/sfc-matrices/` | Scratch SFC matrix exports | No |
| `target/scenarios/` | Scenario registry runs | No |
| `target/robustness*` | Robustness reports | No |
| `<out>/<run-id>/`, for example `target/household-credit-stress/household-credit-stress/` | Household credit-stress calibration | No |
| `<out>/<run-id>/`, for example `target/bank-balance-sheet-benchmark/bank-balance-sheet-benchmark/` | Initial bank balance-sheet benchmark | No |
| `<out>/<run-id>/`, for example `target/loan-origination-quality/loan-origination-quality/` | Loan-origination quality diagnostics | No |
| `docs/sfc-matrix-artifacts/` | Intentional committed matrix snapshots | Yes, only when refreshed intentionally |
| `docs/empirical-validation/` | Empirical-validation snapshot bundle | Yes, only when refreshed intentionally |

## Working Loop

A practical local loop for model changes:

1. Make a focused mechanism, parameter, or accounting change.
2. Execute the most specific affected spec with `sbt "testOnly ..."`.
3. Then run `sbt test`.
4. Perform a narrow diagnostic if the change touches bankruptcy, labor demand,
   inflation, matrices, scenarios, or robustness behavior.
5. Execute heavy and integration tests when the change touches month execution,
   flow simulation, Monte Carlo output, or shared ledger behavior.
6. Compare generated CSVs or reports under `mc/` or `target/` before committing.

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
