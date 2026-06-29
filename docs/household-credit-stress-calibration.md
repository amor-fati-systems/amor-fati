# Household Credit Stress Calibration

Issue #529 adds a repeatable diagnostic layer for household liquidity and credit
stress. The diagnostic export itself does not change household behavior. It
converts existing Monte Carlo outputs and terminal household aggregates into
Poland-relevant guardrail ratios for the same `2026-04-30` model-start baseline
used by the production calibration surface.

Operational appendix entry point:
[operations.md#operational-appendix-index](operations.md#operational-appendix-index).
Use this page for household credit-stress diagnostic details after starting
from the operations index.

Run the standard fixture with:

```bash
sbt "householdCreditStressCalibration --seeds 5 --months 60 --out target/household-credit-stress --run-id household-credit-stress"
```

The task writes:

- `household-credit-stress-seed-metrics.tsv`: one terminal ratio per seed and
  metric.
- `household-credit-stress-summary.tsv`: mean/min/max across seeds, with status.
- `household-credit-stress-targets.tsv`: documented target bands, vintage, and
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
| `ConsumerDebtServiceToIncome` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.00` to `0.08` | ratio | Consumer instalments, principal plus interest, should not dominate regular household income in the baseline. |
| `MortgageDebtServiceToIncome` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.00` to `0.12` | ratio | Mortgage payments can be larger than consumer instalments, but should remain a minority of monthly income. |
| `MortgagePrincipalToIncome` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to n/a | ratio | Separates amortization pressure from interest-rate pass-through in the secured debt-service burden. |
| `MortgageInterestToIncome` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to n/a | ratio | Shows the rate-sensitive part of mortgage debt service separately from scheduled principal repayment. |
| `ConsumerDefaultToConsumerLoans` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to `0.03` | monthly ratio | Ordinary consumer-loan default flow relative to consumer-loan stock, excluding same-month liquidity bridge write-offs. |
| `LiquidityBridgeChargeOffToConsumerLoans` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to n/a | monthly ratio | Same-month liquidity bridge write-offs relative to consumer-loan stock. |
| `LiquidityBridgeChargeOffShareOfHouseholdCreditWriteOff` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to `1.00` | share | Share of household credit write-offs that is same-month liquidity bridge charge-off rather than ordinary consumer-loan default. |
| `MortgageDefaultToMortgageLoans` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to `0.01` | monthly ratio | Flow/stock stress ratio for mortgage stress; this should later be mapped to arrears/default definitions. |
| `PositiveDepositsToMonthlyIncome` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `1.00` to `8.00` | months of income | Aggregate liquid buffers should be neither exhausted nor implausibly huge relative to household income. |
| `MedianDepositToMeanMonthlyIncome` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.20` to `6.00` | months of mean income | The median household should have some liquidity, but not years of income in demand deposits. |
| `NegativeDepositShare` | `HARD_INVARIANT` | `2026-04-30 model-start baseline` | `0.00` to `0.00` | share | Demand deposits are non-negative bank liabilities. |
| `DebtArrearsToShortfall` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to `1.00` | share | Shows whether shortfall pressure comes mainly from debt/rent service or from consumption/liquidity residuals. |
| `UnmetBasicConsumptionToIncome` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to n/a | ratio | Basic consumption need not covered by cash before bridge/default settlement. |
| `DiscretionaryConsumptionCompressionToIncome` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to n/a | ratio | Discretionary consumption pressure absorbed before creating bridge charge-offs. |
| `ShortfallToIncome` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.00` to `0.05` | ratio | Shortfall financing should be a stress channel, not a large routine substitute for income. |
| `ShortfallToApprovedOrigination` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.00` to `2.00` | ratio | The non-underwritten liquidity bridge should not structurally dominate normal approved consumer credit. |
| `RejectedConsumerCreditDemandToApprovedOrigination` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to n/a | ratio | Shows whether approved credit is suppressed by borrower-side denial or bank-side supply rejection rather than by lack of borrower demand. |
| `RejectedConsumerCreditDemandToShortfall` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to n/a | ratio | Compares denied normal-credit demand with residual emergency bridge/write-off flow. |

## Source Status

The current bands are stylized for the `2026-04-30` Poland model-start
baseline. `MortgageLoansToGdp` is anchored to the existing empirical-validation
manifest bridge for KNF housing loans relative to model GDP and should be
checked along the simulated path, including the 60-month terminal value, not
only at initialization. `MortgageNetStockFlow` and its component flow-to-stock
rates identify whether any drift is numerator runoff from origination,
repayment, or default; `AnnualizedGdpProxy` separates denominator growth.
If the 60-month baseline falls below the band while
`MortgageOriginationSupplyConstrained` is false, the relevant calibration lever
is `housing.originationRate` relative to scheduled amortization, gross default,
and GDP growth rather than a bank-resolution supply cap.
For the non-bank credit slice, `NbfiOriginationToStock` is a stock-renewal rate;
compare it directly with `NbfiRepaymentToStock` and `NbfiDefaultsToStock` before
attributing aggregate private-credit compression to household stress.
Mortgage principal and interest ratios decompose `MortgageDebtServiceToIncome`;
they are internal diagnostics, not standalone empirical acceptance bands.
`ConsumerDebtServiceToIncome` is also a household cash-flow burden: principal
reduces the consumer-loan stock, while only the interest component enters bank
income. The consumer-credit,
household DSR, arrears/default and liquidity-buffer ranges are deliberately
documented as guardrails, not final empirical pass/fail tests. `ConsumerDefault`
now reports ordinary consumer-loan principal default only; liquidity bridge
write-offs are reported separately and no longer enter bank consumer NPL loss
recognition. The #528 budget
waterfall additionally reports unmet basic consumption and discretionary
consumption compression so shortfall financing is not the only visible stress
absorber. The #534 consumer-credit diagnostics report stressed borrower demand
and rejected origination separately from approved origination. The #527
financial-distress state machine adds `HouseholdDistress_*`,
`HouseholdDistress_ActiveShare`, `HH_Distress_*`, and household-snapshot
`FinancialDistressState` diagnostics, but these remain descriptive until the
arrears/default calibration bridge is narrowed. These bands should be replaced
or narrowed when NBP, KNF, GUS or household microdata bridges are added.
