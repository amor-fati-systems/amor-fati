# Nightly Diagnostics

This document defines the diagnostic profiles that should run against `HEAD` of
`main`. It is a profile contract for the nightly validation milestone, not the
implementation of the runner or GitHub Actions workflow.

The goal is to keep long-running validation reproducible, inspectable, and
scientifically honest. The profiles below validate the currently interpretable
operational horizon of the model. They are not long-horizon cycle claims.
The comparison semantics for these profiles are defined in
[nightly-baseline-comparison.md](nightly-baseline-comparison.md).

## Principles

- Run against a clean `main` ref, not a dirty local checkout.
- Prefer the assembled jar under the project Nix environment for scheduled
  runs.
- Use fixed seed ranges and explicit month horizons.
- Write outputs under a profile-scoped root, not under the default local `mc/`
  directory.
- Emit a machine-readable run manifest for every profile run.
- Treat SFC exactness, ledger consistency, missing months, malformed CSV, and
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
- output paths
- start and end timestamps
- Nix, Java, sbt, and project versions where practical

## Jar / Nix Execution

Build the assembled jar from the project Nix environment:

```bash
nix develop --command sbt assembly
```

Run a profile from that jar with the diagnostics profile runner:

```bash
nix develop --command java -cp target/scala-3.8.2/amor-fati.jar \
  com.boombustgroup.amorfati.diagnostics.NightlyDiagnosticsProfileRunner \
  --profile smoke \
  --out target/nightly-diagnostics \
  --run-id smoke-manual-20260526-abcdef0 \
  --jar-path target/scala-3.8.2/amor-fati.jar
```

`java -jar target/scala-3.8.2/amor-fati.jar` remains the normal simulation
entry point (`com.boombustgroup.amorfati.Main`). Diagnostics profiles use
`java -cp ... com.boombustgroup.amorfati.diagnostics.NightlyDiagnosticsProfileRunner`
so they can select the profile runner while still executing from the assembled
jar.

Scheduled `main` runs should require a clean `main` ref:

```bash
nix develop --command java -cp target/scala-3.8.2/amor-fati.jar \
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
```

The manifest records the profile, resolved git ref, dirty-tree status, jar path
and SHA-256 when available, command line, tool versions, step seed/month
settings, step output directories, artifact paths, timestamps, and final
status.

## Scheduled Workflow

The GitHub Actions workflow `.github/workflows/nightly-diagnostics.yml` runs the
same jar/Nix path. It is intentionally not attached to `pull_request`, so long
diagnostics do not block normal PR feedback.

Triggers:

- `schedule`: runs the `nightly` profile against `HEAD` of `main`
- `workflow_dispatch`: lets maintainers manually select `smoke`, `nightly`, or
  `extended`

The workflow checks out `main`, builds the assembled jar with:

```bash
nix develop --command sbt assembly
```

and runs:

```bash
nix develop --command java -cp target/scala-3.8.2/amor-fati.jar \
  com.boombustgroup.amorfati.diagnostics.NightlyDiagnosticsProfileRunner \
  --profile <profile> \
  --out target/nightly-diagnostics \
  --run-id <resolved-run-id> \
  --jar-path target/scala-3.8.2/amor-fati.jar \
  --require-main
```

The scheduled workflow targets 02:00 Europe/Warsaw. GitHub cron only supports
UTC, so the workflow registers both `0 0 * * *` and `0 1 * * *` UTC and then
uses a lightweight gate step to continue only for the intended local-time
occurrence. In ordinary CEST this is `0 0 * * *`; in ordinary CET this is
`0 1 * * *`. On the autumn DST transition, where local 02:00 occurs twice, the
workflow keeps the second occurrence in CET and skips the first one. On the
spring DST transition, where local 02:00 does not exist, the workflow runs at
the first valid post-transition slot, 03:00 CEST. The gate runs before checkout
or Nix setup, so skipped scheduled events are cheap.

The job summary reports the profile, commit SHA, run id, runtime, manifest path,
terminal status, build/runtime exit codes, and failure reason when either the
build or diagnostics runner produces one. Uploading full diagnostic artifacts
and retention policy is tracked separately by #635.

## Profile Matrix

| Profile | Intended Trigger | Horizon | Purpose |
| --- | --- | --- | --- |
| `smoke` | local, manual, possibly PR if runtime stays acceptable | 12 months | Fast sanity check and invariant validation |
| `nightly` | scheduled daily on `main`, plus manual dispatch | 60 months | Daily research validation over the standard five-year horizon |
| `extended` | weekly or manual | 60 months | Wider seed, scenario, and diagnostic coverage over the same five-year horizon |

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
- bank failure ablations: 1 seed, 12 months

Comparison mode:

- hard-fail only on invariants and malformed output
- report research metrics without tight thresholds

Primary artifacts:

- seed time-series CSV
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
- bank failure ablations: 5 seeds, 60 months
- HH-bank lead-lag diagnostics: 5 seeds, 60 months, lag max 6
- loan-origination quality diagnostics: 2 seeds, 60 months, outcome window 12

Comparison mode:

- hard-fail on invariants, missing months, malformed CSV, impossible
  accounting states, SFC violations, and ledger failures
- report or warn on research metrics such as GDP, inflation, unemployment,
  total credit to GDP, debt to GDP, current account to GDP, bank failures,
  credit losses, household bankruptcies, credit rejection rates, and approval
  stochasticity

Primary artifacts:

- baseline Monte Carlo CSV outputs
- empirical validation snapshot and manifest
- scenario registry artifacts and run summary
- robustness envelope and sensitivity reports
- household credit-stress outputs
- bank balance-sheet benchmark outputs
- bank failure ablation outputs
- HH-bank lead-lag outputs
- loan-origination quality outputs
- profile manifest and logs

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
- bank failure ablations: 10 seeds, 60 months
- HH-bank lead-lag diagnostics: 10 seeds, 60 months, lag max 12
- loan-origination quality diagnostics: 5 seeds, 60 months, outcome window 12

Comparison mode:

- same hard invariants as `nightly`
- wider research comparison envelope over a larger seed and scenario surface
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
- malformed CSV files
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
