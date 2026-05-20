# Household Credit Stress Calibration

Issue #529 adds a repeatable diagnostic layer for household liquidity and credit
stress. The diagnostic export itself does not change household behavior. It
converts existing Monte Carlo outputs and terminal household aggregates into
Poland-relevant guardrail ratios for the same `2026-04-30` model-start baseline
used by the production calibration surface.

Run the standard fixture with:

```bash
sbt "householdCreditStressCalibration --seeds 5 --months 60 --out target/household-credit-stress --run-id household-credit-stress"
```

The task writes:

- `household-credit-stress-seed-metrics.csv`: one terminal ratio per seed and
  metric.
- `household-credit-stress-summary.csv`: mean/min/max across seeds, with status.
- `household-credit-stress-targets.csv`: documented target bands, vintage, and
  semantics.
- `household-credit-stress-report.md`: human-readable summary.

## Guardrail Classes

`HARD_INVARIANT` covers accounting or reporting boundaries that should never
fail. In this layer, negative demand deposits are treated as invalid because
demand deposits are non-negative bank liabilities; household stress must appear
in explicit shortfall/default channels.

`SOFT_CALIBRATION_WARNING` covers Poland-relevant target ranges. A breach is not
a runtime failure, but it means the baseline should be reviewed before using the
run as a stylized Poland scenario.

`EXPLORATORY_DIAGNOSTIC` covers useful stress decompositions where the model
surface exists but the empirical acceptance band is not settled yet. These
ratios are meant to guide follow-up calibration and source-bridge work. A value
outside the reference band is still reported as `WARN`, but the warning means
"investigate this channel", not "empirical validation failed".

## Target Bands

| Metric | Class | Vintage | Band | Unit | Interpretation |
| --- | --- | --- | --- | --- | --- |
| `ConsumerLoansToGdp` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.03` to `0.08` | ratio | Consumer credit should be material, but far below mortgage credit as a share of GDP. |
| `MortgageLoansToGdp` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.09` to `0.16` | ratio | Mortgage stock should sit near the Polish housing-credit scale, not EU high-mortgage economies. |
| `ConsumerDebtServiceToIncome` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.00` to `0.08` | ratio | Consumer instalments should not dominate regular household income in the baseline. |
| `MortgageDebtServiceToIncome` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.00` to `0.12` | ratio | Mortgage payments can be larger than consumer instalments, but should remain a minority of monthly income. |
| `MortgagePrincipalToIncome` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to n/a | ratio | Separates amortization pressure from interest-rate pass-through in the secured debt-service burden. |
| `MortgageInterestToIncome` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to n/a | ratio | Shows the rate-sensitive part of mortgage debt service separately from scheduled principal repayment. |
| `ConsumerDefaultToConsumerLoans` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to `0.03` | monthly ratio | Flow/stock stress ratio for spotting liquidity bridges leaking into consumer-credit losses. |
| `MortgageDefaultToMortgageLoans` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to `0.01` | monthly ratio | Flow/stock stress ratio for mortgage stress; this should later be mapped to arrears/default definitions. |
| `PositiveDepositsToMonthlyIncome` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `1.00` to `8.00` | months of income | Aggregate liquid buffers should be neither exhausted nor implausibly huge relative to household income. |
| `MedianDepositToMeanMonthlyIncome` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.20` to `6.00` | months of mean income | The median household should have some liquidity, but not years of income in demand deposits. |
| `NegativeDepositShare` | `HARD_INVARIANT` | `2026-04-30 model-start baseline` | `0.00` to `0.00` | share | Demand deposits are non-negative bank liabilities. |
| `DebtArrearsToShortfall` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to `1.00` | share | Shows whether shortfall pressure comes mainly from debt/rent service or from consumption/liquidity residuals. |
| `ShortfallToIncome` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.00` to `0.05` | ratio | Shortfall financing should be a stress channel, not a large routine substitute for income. |
| `ShortfallToApprovedOrigination` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.00` to `2.00` | ratio | The non-underwritten liquidity bridge should not structurally dominate normal approved consumer credit. |

## Source Status

The current bands are stylized for the `2026-04-30` Poland model-start
baseline. `MortgageLoansToGdp` is anchored to the existing empirical-validation
manifest bridge for KNF housing loans relative to model GDP. Mortgage principal
and interest ratios decompose `MortgageDebtServiceToIncome`; they are internal
diagnostics, not standalone empirical acceptance bands. The consumer-credit,
household DSR, arrears/default and liquidity-buffer ranges are deliberately
documented as guardrails, not final empirical pass/fail tests. They should be
replaced or narrowed when NBP, KNF, GUS or household microdata bridges are added.
