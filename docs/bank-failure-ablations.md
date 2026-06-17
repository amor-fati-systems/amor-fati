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

Run the standard review fixture with:

```bash
sbt "bankFailureAblations --seeds 2 --months 24 --out target/bank-failure-ablations --run-id bank-failure-ablations"
```

The task writes:

- `bank-failure-ablation-seeds.tsv`: one row per scenario and seed.
- `bank-failure-ablation-summary.tsv`: mean/min/max style scenario summary.
- `bank-failure-ablation-scenarios.tsv`: scenario definitions and economic
  interpretation.
- `bank-failure-ablation-report.md`: human-readable summary.

Use larger `--seeds` and `--months` values for slower research runs. The
standard review fixture is intentionally smaller because each scenario runs a
full model simulation.

## Scenario Semantics

- `baseline`: current production configuration after the opening bank
  calibration fixes.
- `no-ecl-stress-migration`: keeps realized defaults, but removes
  macro-driven Stage 1 to Stage 2 ECL migration.
- `no-realized-credit-loss-capital-hit`: sets firm, mortgage, consumer-credit,
  and corporate-bond recoveries to 100%, while leaving ECL provisioning active.
- `no-resolution-feedback`: neutralizes bail-in haircuts, failure-panic
  switching, CAR-driven deposit flight, and interbank contagion losses.
- `initial-capital-150pct`: raises opening regulatory capital by 50%.

These scenarios are diagnostics, not candidate policy settings. A scenario that
materially delays or prevents failures identifies a channel that needs deeper
economic or accounting work before an explicit bridge-bank recapitalization,
nationalization, or shutdown mechanism can replace the current all-failed
fail-fast boundary.
