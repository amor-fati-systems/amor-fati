# Bank Balance-Sheet Benchmark

Issue #559 adds a repeatable diagnostic layer for opening bank balance sheets.
The export does not change bank behavior. It initializes the model for a fixed
seed range, computes banking-sector and per-bank balance-sheet ratios, and
compares them with the same stylized `2026-04-30` Poland baseline used by the
rest of the calibration surface.

Run the standard fixture with:

```bash
sbt "bankBalanceSheetBenchmark --seeds 10 --out target/bank-balance-sheet-benchmark --run-id bank-balance-sheet-benchmark"
```

The task writes:

- `bank-balance-sheet-seed-metrics.csv`: one opening ratio per seed and metric.
- `bank-balance-sheet-summary.csv`: mean/min/max across seeds, with status.
- `bank-balance-sheet-targets.csv`: documented target bands, vintage, and
  semantics.
- `bank-balance-sheet-bank-rows.csv`: per-bank capital, funding, credit,
  liquidity, and concentration rows.
- `bank-balance-sheet-report.md`: human-readable summary.

## Guardrail Classes

`HARD_INVARIANT` covers opening accounting or prudential boundaries that should
not fail. In this layer, examples include a bank starting below effective
minimum CAR, or total deposits not being decomposed into demand and term deposit
buckets.

`SOFT_CALIBRATION_WARNING` covers Poland-relevant opening balance-sheet ranges.
A breach is not a runtime failure, but it means the initial bank sector should
be reviewed before interpreting later bank failures as macro-shock results.

`EXPLORATORY_DIAGNOSTIC` covers useful bank-sector decompositions where the
model surface exists but the empirical acceptance band is still provisional.
These ratios guide follow-up calibration and source-bridge work.

## Target Bands

| Metric | Class | Vintage | Band | Unit | Interpretation |
| --- | --- | --- | --- | --- | --- |
| `CapitalToAssets` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.06` to `0.12` | ratio | Checks whether the opening balance sheet is thinly capitalised before any macro shock. |
| `AggregateCar` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.191` to `0.231` | ratio | Aggregate CAR should sit near the KNF February 2026 total-capital-ratio bridge, mapped to the model's simplified RWA perimeter. |
| `MinimumCar` | `HARD_INVARIANT` | `2026-04-30 model-start baseline` | `0.08` to n/a | ratio | No bank should start below the base Basel III CRR 8% capital requirement. |
| `MinimumEffectiveCarBuffer` | `HARD_INVARIANT` | `2026-04-30 model-start baseline` | `0.00` to n/a | ratio | No bank should start below its effective minimum CAR after O-SII and P2R add-ons. |
| `BanksBelowEffectiveCar` | `HARD_INVARIANT` | `2026-04-30 model-start baseline` | `0` to `0` | count | No bank should require resolution before the first simulated month. |
| `FirmLoansToGdp` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.10` to `0.18` | ratio | Business loans should be visible but not dominate the Polish credit stock. |
| `ConsumerLoansToGdp` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.03` to `0.08` | ratio | Consumer credit should be material but much smaller than GDP. |
| `MortgageLoansToGdp` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.09` to `0.16` | ratio | Mortgage assets mirrored onto banks should sit near the Polish housing-credit scale. |
| `TotalBankLoansToGdp` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.25` to `0.40` | ratio | The combined bank loan book should not start in the old 80%+ credit-to-GDP red-flag regime. |
| `DepositsToLoans` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `1.50` to `3.00` | ratio | Deposits should comfortably fund credit without implying an unrealistic liability/asset mix. |
| `LiquidAssetsToDeposits` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.10` to `0.30` | ratio | The sector should have visible liquid assets against deposits before stress starts. |
| `ReserveToDeposits` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.02` to `0.06` | ratio | NBP reserve balances should roughly match the configured reserve-requirement bridge. |
| `DepositSplitCoverage` | `HARD_INVARIANT` | `2026-04-30 model-start baseline` | `1.00` to `1.00` | ratio | Total deposits should be decomposed into demand and term buckets. |
| `DemandDepositShare` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.55` to `0.65` | share | Deposit composition should match the configured term-deposit split used by liquidity metrics. |
| `InitialEclAllowanceToLoans` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.005` to `0.03` | ratio | Opening IFRS 9 allowance should be seeded, not created as a first-month catch-up. |
| `EclStagedShareOfCoveredLoans` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.95` to `1.05` | share | The ECL-covered firm and consumer loan book should already be assigned to stages. |
| `AggregateNplRatio` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to `0.06` | ratio | Opening credit quality should not by itself explain early capital pressure. |
| `LargestBankCreditShare` | `SOFT_CALIBRATION_WARNING` | `2026-04-30 model-start baseline` | `0.00` to `0.25` | share | No single bank row should dominate the opening credit book. |
| `BankCreditHhi` | `EXPLORATORY_DIAGNOSTIC` | `2026-04-30 model-start baseline` | `0.00` to `0.20` | index | Compact concentration diagnostic for the seven-row banking sector. |

## Source Status

The current bands are guardrails for the `2026-04-30` Poland model-start
baseline, not final empirical validation. `AggregateCar` is anchored to the KNF
February 2026 total-capital-ratio bridge already recorded in calibration
provenance. `ConsumerLoansToGdp` and `MortgageLoansToGdp` reuse the household
credit-stress calibration ranges. ECL bands are model-semantics checks: they ask
whether the opening loan book is already staged and provisioned before monthly
ECL dynamics begin. The current opening ECL bridge assigns the covered firm and
consumer loan book to Stage 1; calibrated opening Stage 2/3 shares remain a
separate banking-risk calibration step. `banking.initDeposits` is the aggregate
opening customer-deposit stock: firm cash receives the corporate split, household
demand deposits are normalized to the residual, and `BankInit` derives bank
deposit liabilities from those holder-side balances. Deposit split, reserves,
and liquidity ratios are especially important because they determine whether
LCR, NSFR and monetary aggregates are measuring an economically meaningful
opening bank balance sheet.
