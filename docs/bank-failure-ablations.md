# Bank Failure Ablation Diagnostics

Issue #560 adds a repeatable diagnostic layer for banking-sector failure root
cause analysis. The export does not change production behavior. It runs the
baseline and targeted neutralization scenarios, then compares first failure
timing, terminal failures, cumulative credit losses, ECL provisions, bail-in
losses, capital destruction, and reconciliation residuals.

Operational appendix entry point:
[operations.md#operational-appendix-index](operations.md#operational-appendix-index).
Use this page for bank-failure diagnostic details after starting from the
operations index.

Run the fast smoke fixture with:

```bash
sbt "bankFailureAblations --seeds 1 --months 12 --parallelism 1 --out target/bank-failure-ablations --run-id bank-failure-ablations-smoke"
```

Run the standard review fixture with:

```bash
sbt "bankFailureAblations --seeds 2 --months 24 --parallelism 1 --out target/bank-failure-ablations --run-id bank-failure-ablations"
```

The task writes:

- `bank-failure-ablation-seeds.tsv`: one row per scenario and seed.
- `bank-failure-ablation-summary.tsv`: mean/min/max style scenario summary.
- `bank-failure-ablation-scenarios.tsv`: scenario definitions and economic
  interpretation.
- `bank-failure-ablation-report.md`: human-readable summary.

Use larger `--seeds` and `--months` values for slower research or profiling
runs. A 5 seed / 60 month or larger run should be treated as heavy mode and run
from `nix develop` so it inherits the 8 GB sbt heap from `flake.nix`:

```bash
nix develop --command sbt "bankFailureAblations --seeds 5 --months 60 --parallelism 2 --out target/bank-failure-ablations --run-id bank-failure-ablations-heavy"
```

The standard review fixture is intentionally smaller because each scenario
runs a full model simulation. `--parallelism` bounds concurrent scenario/seed
jobs; keep it at `1` or `2` unless the host has enough heap headroom.

## Scenario Semantics

- `baseline`: current production configuration after the opening bank
  calibration fixes.
- `no-ecl-stress-migration`: keeps realized defaults, but removes
  macro-driven Stage 1 to Stage 2 ECL migration.
- `no-realized-credit-loss-capital-hit`: sets firm, mortgage, consumer-credit,
  and corporate-bond recoveries to 100%, while leaving ECL provisioning active.
- `no-resolution-feedback`: neutralizes bail-in haircuts, failure-panic
  switching, CAR-driven deposit flight, and interbank contagion losses.

These scenarios are diagnostics, not candidate policy settings. A scenario that
materially delays or prevents failures identifies a channel that needs deeper
economic or accounting work before an explicit bridge-bank recapitalization,
nationalization, or shutdown mechanism can replace the current all-failed
fail-fast boundary.
