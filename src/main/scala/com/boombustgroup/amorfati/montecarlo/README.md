# Monte Carlo

The `montecarlo` package owns the Monte Carlo runner, output schemas,
CSV writers, console progress reporting, and typed result/error
wrappers. It is the only consumer of `McRunConfig` — the engine
pipeline has no dependency on this package.

## Files

| File | Object | Role |
|------|--------|------|
| `McRunner.scala` | `McRunner` | Orchestrates the Monte Carlo run: initializes seeds, streams monthly snapshots, writes per-seed CSVs, collects terminal summaries and optional firm micro exports |
| `McSeedMonth.scala` | `McSeedMonth` | Canonical monthly seed snapshot shared with diagnostics: execution month, timeseries row, opening/closing state, and lightweight monthly tap payloads |
| `McDiagnosticRunner.scala` | `McDiagnosticRunner` | Shared scenario/seed diagnostic runner using the same seed-stream path and bounded `mapZIOPar` parallelism as production runs |
| `McRunConfig.scala` | `McRunConfig` | Runtime config from CLI args: `nSeeds`, `outputPrefix`, `runDurationMonths`, `runId`, `firmSnapshotSchedule`, `householdSnapshotSchedule`, `householdSnapshotSelection`, `firmDecisionTraceSelection` |
| `McFirmSnapshotSchedule.scala` | `McFirmSnapshotSchedule` | Disabled/terminal/cadence/explicit-month firm microdata export schedule |
| `McFirmSnapshotSchema.scala` | `McFirmSnapshotSchema` | Generic per-firm snapshot CSV header and row rendering |
| `McFirmSnapshotCsv.scala` | `McFirmSnapshotCsv` | Optional per-seed firm snapshot chunk writer and combined CSV finalizer |
| `McHouseholdSnapshotSchedule.scala` | `McHouseholdSnapshotSchedule` | Disabled/terminal/cadence/explicit-month household microdata export schedule |
| `McHouseholdSnapshotSelection.scala` | `McHouseholdSnapshotSelection` | Household snapshot row selector: all, negative deposits, liquidity shortfall, or either condition |
| `McHouseholdSnapshotSchema.scala` | `McHouseholdSnapshotSchema` | Generic per-household liquidity snapshot CSV header and row rendering |
| `McHouseholdSnapshotCsv.scala` | `McHouseholdSnapshotCsv` | Optional per-seed household snapshot chunk writer and combined CSV finalizer |
| `McHouseholdShortfallCohortSchema.scala` | `McHouseholdShortfallCohortSchema` | Household shortfall cohort aggregation schema for status/region/contract/burden diagnostics |
| `McHouseholdShortfallCohortCsv.scala` | `McHouseholdShortfallCohortCsv` | Optional per-seed household shortfall cohort writer and combined CSV finalizer |
| `McFirmDecisionTraceSelection.scala` | `McFirmDecisionTraceSelection` | Disabled/all/explicit-id/first-N firm decision trace selector |
| `McFirmDecisionTraceSchema.scala` | `McFirmDecisionTraceSchema` | Generic per-firm decision trace CSV header and row rendering |
| `McFirmDecisionTraceCsv.scala` | `McFirmDecisionTraceCsv` | Optional per-seed firm decision trace chunk writer and combined CSV finalizer |
| `McFirmSizeClass.scala` | `McFirmSizeClass` | Shared worker-count size-class boundary used by terminal counts and firm snapshots |
| `McHouseholdLiquidityDiagnostics.scala` | `McHouseholdLiquidityDiagnostics` | Shared household demand-deposit distribution diagnostics for timeseries and terminal summaries |
| `McTimeseriesSchema.scala` | `McTimeseriesSchema` | Timeseries schema composed from domain column groups, with typed `Col` definitions, `compute`, and shared `csvSchema` |
| `McCsvFile.scala` | `McCsvFile` | Generic streaming CSV sink with parent-dir creation, temp-file finalization, and fold support for diagnostics |
| `McTimeseriesCsv.scala` | `McTimeseriesCsv` | Production per-seed timeseries CSV sink backed by `McCsvFile` |
| `McTerminalSummarySchema.scala` | `McTerminalSummarySchema` | Household/bank/firm terminal summary schemas and terminal-state row extraction; bank stock columns read `LedgerFinancialState` |
| `McTerminalSummaryCsv.scala` | `McTerminalSummaryCsv` | Writes aggregate household/bank/firm terminal summary CSVs |
| `McCsvSchema.scala` | `McCsvSchema` | Shared CSV header/render contract used by output schemas |
| `McOutputFiles.scala` | `McOutputFiles` | Output directory preparation and stable output file naming |
| `McRunnerConsole.scala` | `McRunnerConsole` | Console progress/status rendering for runs, seeds, and saved files |
| `McTypes.scala` | `SimError`, `RunResult`, `TimeSeries` | Typed runtime/output errors plus zero-cost wrappers for simulation output |

## Data flow

```
Main ──→ McRunner.runZIO(rc)
           │
           ├── McOutputFiles.prepareOutputDir
           │
           ├── for seed ← 1..N (parallel):
           │     initSeed
           │       ├── WorldInit.initialize(InitRandomness.Contract.fromSeed(seed))
           │       └── InitCheck.validate
           │
           │     runtimeSteps(...).take(runDurationMonths)
           │       └── MonthDriver.unfoldSteps(...)
           │
           │     stepSnapshot
           │       └── McTimeseriesSchema.compute -> Array[MetricValue]
           │
           │     McTimeseriesCsv.writeStreaming(seed.csv)
           │       └── optional McFirmSnapshotCsv.tapSeedSnapshots(...)
           │     McTerminalSummarySchema.fromTerminalState
           │
           ├── McTerminalSummaryCsv.writeAll(hh.csv, banks.csv, firms.csv)
           ├── optional McFirmSnapshotCsv.combineSeedFiles(firm_snapshots.csv)
           ├── optional McHouseholdSnapshotCsv.combineSeedFiles(household_snapshots.csv)
           ├── optional McHouseholdShortfallCohortCsv.combineSeedFiles(household_shortfall_cohorts.csv)
           ├── optional McFirmDecisionTraceCsv.combineSeedFiles(firm_decision_trace.csv)
           └── McRunnerConsole.emit(...)
```

`McRunner.runZIO` is the production entrypoint. `McRunner.runSingle`
uses the same initialization/runtime path but materializes the whole
run in memory and returns a pure `Either[SimError, RunResult]` for
tests and local callers.

Diagnostics that need Monte Carlo seed execution should use
`McDiagnosticRunner` and `McRunner.seedMonths`, not `runSingle`. That keeps
scenario/seed sweeps on the same initialization and monthly runtime path as
`Main`, preserves bounded seed parallelism through `mapZIOPar`, and lets
diagnostic CSVs stream rows through `McCsvFile` while retaining only the small
fold state needed for summaries.

`runDurationMonths` is a Monte Carlo/runtime concern. It controls how
many monthly snapshots the runner materializes, but it is not part of
`SimParams` and should not leak into economic decision rules.

## Timeseries Fiscal Diagnostics

The timeseries fiscal block keeps the domestic-demand budget and the deficit
closure view separate. `GovDomesticBudgetOutlays` covers central-budget
domestic outlays visible on `GovState`: unemployment benefits, social transfers,
current purchases, domestic capital purchases, debt service, and EU
co-financing. It intentionally excludes social-fund top-ups because ZUS, NFZ,
and earmarked funds are reported in the social block.

`GovSocialFundSubventions` sums `ZusGovSubvention`, `NfzGovSubvention`, and
`EarmarkedGovSubvention`. `GovTotalOutlays` then reconciles to the fiscal
deficit identity:

```text
GovTotalOutlays = GovDomesticBudgetOutlays + GovSocialFundSubventions
GovDeficit      = GovTotalOutlays - GovTotalRevenue
```

`GovPrimaryDeficit` removes `DebtService` from `GovDeficit`, so fiscal drift can
be split into the primary balance and the interest-cost channel. The
`*ToGdp` columns use the monthly flow divided by the current monthly GDP proxy,
matching the annualized flow-ratio convention used by `DeficitToGdp`.

## Timeseries External Diagnostics

The external block exposes both BoP levels and GDP-normalized ratios so current
account drift can be decomposed without reconstructing hidden engine state.
`NfaToGdp` is a stock ratio against annualized GDP; `CurrentAccountToGdp`,
`TradeBalanceToGdp`, `ExportsToGdp`, `ImportsToGdp`,
`CapitalAccountToGdp`, `CurrentAccountPrimaryIncomeToGdp`, and
`CurrentAccountSecondaryIncomeToGdp` are monthly flow ratios against monthly
GDP. `ImportedIntermToImports` isolates the GVC/intermediate-import share of
total imports.

The final exported current-account identity is:

```text
CurrentAccount =
  TradeBalance_OE
  + CurrentAccountPrimaryIncome
  + CurrentAccountSecondaryIncome
  - ForeignDividendOutflow
  - FdiRepatriation
  + CurrentAccountClosureResidual
```

`TradeBalance_OE` is already net of `FdiProfitShifting`, which is booked as an
imported service in the final BoP adjustment. `FdiGrossOutflow` remains useful
as a total foreign-owned-firm outflow measure, but it must not be subtracted
again when closing `CurrentAccount` from `TradeBalance_OE`.

## Timeseries Automation Diagnostics

The timeseries schema includes generic monthly automation diagnostics:

```text
Automation_TechCapex
Automation_TechImports
Automation_TechLoans
Automation_UpgradeFailures
Automation_AiDebtTrap
Automation_NewFullAi
Automation_NewHybrid
Adoption_MicroShare
Adoption_SmallShare
Adoption_MediumShare
Adoption_LargeShare
Adoption_CashQ1
Adoption_CashQ2
Adoption_CashQ3
Adoption_CashQ4
Adoption_DebtQ1
Adoption_DebtQ2
Adoption_DebtQ3
Adoption_DebtQ4
```

`Automation_TechLoans` is the bank-credit component of technology-upgrade
financing after equity and accepted corporate-bond substitution, plus any
technology-attributed corporate bonds reverted to bank loans by the monthly bond
absorption constraint. It excludes accepted corporate bonds and equity issuance.

`Adoption_*Share` columns are adoption rates within living-firm cohorts, where
adoption means `Hybrid` or `Automated`. Size cohorts use `McFirmSizeClass`.
Cash and debt quartiles sort living firms by closing ledger cash or firm-loan
principal respectively; `Q1` is the lowest cash/debt quartile.

## Bank ECL Diagnostics

The seed timeseries includes IFRS 9 / ECL allowance attribution columns:

```text
EclStage1
EclStage2
EclStage3
BankEcl_OpeningAllowance
BankEcl_ClosingAllowance
BankEcl_BaselineStage1Allowance
BankEcl_ExcessAllowance
BankEcl_ExcessAllowanceShare
BankEcl_ProvisionChangeToOpeningCapital
BankEcl_ProvisionChangeToRealizedLoss
BankEcl_Stage2Share
BankEcl_Stage3Share
BankEcl_MigrationRate
BankEcl_GdpGrowthMonthly
```

`EclStage1`, `EclStage2`, and `EclStage3` are aggregate staged exposure stocks.
At model start the ECL-covered firm and consumer loan book is seeded as
all-performing Stage 1; calibrated opening Stage 2/3 shares are not modeled yet.
`BankEcl_OpeningAllowance` and `BankEcl_ClosingAllowance` are the accounting
allowances implied by those staged stocks and configured ECL rates.
`BankEcl_BaselineStage1Allowance` asks what the closing allowance would have
been if the whole staged ECL book had stayed in Stage 1.
`BankEcl_ExcessAllowance` is the part above that all-performing baseline.
`BankEcl_ProvisionChange*`
columns compare the monthly provision change with opening bank capital and
realized credit losses, so provisioning can be separated from realized default
losses in bank-failure months.

## Bank Capital Diagnostics

The seed timeseries includes aggregate banking-sector capital waterfall columns
without a separate CLI flag:

```text
BankCapital_Opening
BankCapital_Closing
BankCapital_Delta
BankCapital_RetainedIncome
BankCapital_RealizedCreditLoss
BankCapital_FirmNplLoss
BankCapital_MortgageNplLoss
BankCapital_ConsumerNplLoss
BankCapital_CorpBondDefaultLoss
BankCapital_InterbankContagionLoss
BankCapital_BfgLevy
BankCapital_UnrealizedBondLoss
BankCapital_HtmRealizedLoss
BankCapital_EclProvisionChange
BankCapital_CapitalDestruction
BankCapital_ReconciliationResidual
BankCapital_WaterfallResidual
BankCapital_DepositBailInLoss
BankCapital_NewFailures
BankCreditLoss_RealizedToOpeningCapital
BankCreditLoss_FirmDefaultRate
BankCreditLoss_FirmLossRate
BankCreditLoss_MortgageDefaultRate
BankCreditLoss_MortgageLossRate
BankCreditLoss_ConsumerLoanDefaultRate
BankCreditLoss_LiquidityBridgeChargeOffRate
BankCreditLoss_ConsumerLossRate
BankCreditLoss_CorpBondDefaultRate
BankCreditLoss_CorpBondLossRate
```

These columns follow the same pattern as household and firm aggregate
diagnostics: they are cheap monthly sector metrics in each seed CSV, while
heavier per-agent drilldowns remain separate optional outputs. Loss components
are positive when they reduce capital. `BankCapital_RetainedIncome` is positive
when retained bank income raises capital. `BankCapital_RealizedCreditLoss` is
the sum of firm-loan, mortgage, consumer-credit, and bank-held corporate-bond
losses. `BankCapital_InterbankContagionLoss` is the separate counterparty loss
from failed-bank interbank exposures. `BankCapital_WaterfallResidual` reports
the remaining unexplained capital delta after the ordinary waterfall terms and
the exactness patch. It should stay close to zero; non-zero values indicate a
missing diagnostic term.

`BankCapital_ReconciliationResidual` reports the exactness correction applied
to one per-bank capital row after the normal bank update. Positive values mean
the patch added capital, and negative values mean it removed capital. It is a
diagnostic for per-bank allocation artifacts, not a standalone economic loss
channel.
`BankCapital_DepositBailInLoss` mirrors the existing `BailInLoss` column inside
the bank-capital diagnostic block; it is a resolution-adjacent depositor haircut
on newly processed deposits and is not included in the equity-capital waterfall
identity.

`BankCreditLoss_*` columns normalize realized credit losses and gross
default/write-off flows by the relevant closing exposure stock, so bank-failure
months can be read without post-processing. `RealizedToOpeningCapital` measures
the total realized credit-loss hit against opening bank capital. Firm and
corporate-bond default rates reverse the configured recovery rate to estimate
gross default flow from net loss. Consumer diagnostics separate ordinary
consumer-loan default from liquidity-bridge charge-off; `ConsumerLossRate`
tracks the ordinary consumer-loan capital loss used by the bank waterfall.

## Bank Failure Diagnostics

The seed timeseries also includes monthly failure-trigger diagnostics:

```text
BankResolution_ActiveBanks
BankResolution_FailedBanks
BankResolution_NewFailures
BankResolution_BailInEvents
BankResolution_ResolvedBanks
BankResolution_AllFailedFallback
BankResolution_BridgeRecapitalization
BankResolution_InvalidActiveBankInvariant
BankFailure_NewNegativeCapital
BankFailure_NewCarBreach
BankFailure_NewLiquidityBreach
BankFailure_AllFailedFallback
BankFailure_InvariantViolation
BankFailure_FirstNewReasonCode
BankFailure_FirstNewBankId
BankReconciliation_TargetBankId
BankReconciliation_CapitalResidual
BankReconciliation_TargetCapitalBefore
BankReconciliation_TargetCapitalAfter
BankReconciliation_TargetCarBefore
BankReconciliation_TargetCarAfter
BankReconciliation_ResidualToTargetCapital
BankReconciliation_MaterialResidual
BankReconciliation_CrossedFailureThreshold
BankReconciliation_PostResidualReasonCode
```

`BankResolution_*` columns provide the first-pass state counts for explaining
credit-supply collapse: active and failed bank rows after month close, newly
failed banks, distinct event-based bail-in entries, and P&A-resolved bank rows.
`BankResolution_BridgeRecapitalization` remains zero until an explicit bridge or
nationalization mechanism is implemented. `BankResolution_InvalidActiveBankInvariant`
should remain zero; a non-zero value means an active bank ended the month with
negative capital.

`BankFailure_NewNegativeCapital`, `BankFailure_NewCarBreach`, and
`BankFailure_NewLiquidityBreach` count newly failed banks by primary trigger.
If several triggers are true in the same month, the primary reason priority is
negative capital, then CAR breach, then LCR/liquidity breach.
`BankFailure_AllFailedFallback` is retained as a stable diagnostic column and
should remain zero under the current fail-fast all-failed semantics: if every
bank has failed while deposits still need resolution, the run is invalid until
an explicit bridge-bank recapitalization, nationalization, or shutdown
mechanism is implemented.
`BankFailure_InvariantViolation` should stay zero; a non-zero value means the
failure-event diagnostics no longer reconcile to the new-failure count and the
run should be treated as an invalid model state. `BankFailure_FirstNewReasonCode`
uses stable codes: `0` none, `1` negative capital, `2` CAR breach,
`3` LCR/liquidity breach, `4` all-failed fallback, `5` invariant mismatch.

`BankReconciliation_*` columns quantify the exactness patch applied after the
normal bank update. The patch is distributed across live bank rows;
`BankReconciliation_TargetBankId` identifies the most impacted row, not the
sole absorber. The capital before/after and CAR before/after fields show whether
that row's allocation is just fixed-point cleanup or a material balance-sheet
transfer. `BankReconciliation_MaterialResidual` is `1` when the most impacted
allocation is at least 1 bp of that bank's pre-patch capital.
`BankReconciliation_CrossedFailureThreshold` is `1` when the patch alone moves
any bank from no failure trigger to a failure trigger; the post-patch
reason code uses the same reason-code mapping as `BankFailure_FirstNewReasonCode`.
If the patch creates post-residual failure reasons, the banking stage runs a
final failure, bail-in, and P&A resolution pass before month close.

## Firm Credit Diagnostics

The monthly timeseries firm-credit block relevant to loan-book runoff is:

```text
FirmCredit_NewLoans
FirmCredit_PrincipalRepaid
FirmCredit_GrossDefault
FirmCredit_NplRecovery
FirmCredit_NplLoss
FirmCredit_NetStockFlow
FirmCredit_CreditDemand
FirmCredit_CreditApproved
FirmCredit_BankRejected
FirmCredit_RejectedFailedBank
FirmCredit_RejectedCarGate
FirmCredit_RejectedLcrGate
FirmCredit_RejectedNsfrGate
FirmCredit_RejectedStochastic
FirmCredit_RejectedUnclassified
FirmCredit_ApprovalRate
FirmCredit_InvestmentDemand
FirmCredit_InvestmentApproved
FirmCredit_InvestmentBankRejected
FirmCredit_CashFinancedInvestment
FirmCredit_CashFinancedInvestmentToGrossInvestment
FirmCredit_TechDemand
FirmCredit_TechApproved
FirmCredit_TechBankRejected
FirmCredit_TechSelectedDemand
FirmCredit_TechSelectedApproved
FirmCredit_TechSelectedBankRejected
FirmCredit_TechCandidateDemand
FirmCredit_TechCandidateApproved
FirmCredit_TechCandidateBankRejected
FirmCredit_TechCandidateApprovalRate
```

`FirmCredit_NetStockFlow` reports the bank-book flow currently applied to the
firm-loan stock: new bank loans minus scheduled principal repayment minus the
recovered part of newly defaulted firm debt. `FirmCredit_GrossDefault` and
`FirmCredit_NplLoss` expose the gross default volume and after-recovery capital
loss separately. Comparing month-over-month `BankFirmLoans` deltas to
`FirmCredit_NetStockFlow` isolates residual effects from clipping, routing, or
resolution.

`FirmCredit_CreditDemand`, `FirmCredit_CreditApproved`, and
`FirmCredit_BankRejected` aggregate the always-on investment and technology
credit decision surfaces before equity and corporate-bond channel substitution;
final bank-loan origination is `FirmCredit_NewLoans`. `FirmCredit_Rejected*`
splits bank-supply rejections by the primary approval gate: failed bank, CAR,
LCR, NSFR, stochastic approval roll, or unclassified legacy/boolean paths. The
`Investment*` columns explain physical-capital financing;
`FirmCredit_CashFinancedInvestment` is gross investment not financed by approved
investment credit. `FirmCredit_TechDemand`, `FirmCredit_TechApproved`, and
`FirmCredit_TechBankRejected` remain the aggregate technology-credit surface.
The `TechSelected*` columns isolate the actually selected automation or hybrid
upgrade path. The `TechCandidate*` columns expose the full otherwise-feasible
upgrade candidate surface before adoption choice, including the candidate
approval rate used to calibrate bank-side stochastic credit gating.

## NBFI Credit Diagnostics

The monthly timeseries NBFI block relevant to non-bank credit runoff is:

```text
NbfiLoanStock
NbfiOrigination
NbfiRepayment
NbfiDefaults
NbfiNetStockFlow
NbfiOriginationToStock
NbfiRepaymentToStock
NbfiDefaultsToStock
NbfiBankTightness
NbfiDepositDrain
NbfiDepositDrainToAum
```

`NbfiNetStockFlow` reports the expected monthly NBFI credit-book flow as
origination minus repayment minus defaults. Comparing month-over-month
`NbfiLoanStock` deltas to `NbfiNetStockFlow` isolates any residual stock
movement. The three `*ToStock` rates identify whether runoff is driven by low
origination, scheduled repayment, or default. `NbfiBankTightness` is the
bank-NPL-driven counter-cyclical origination signal, while
`NbfiDepositDrainToAum` shows whether TFI fund-flow pressure is material
relative to TFI AUM. TFI deposit drain affects banking-system deposits and AUM;
it is not a direct term in the NBFI loan-stock identity.

## Mortgage Credit Diagnostics

The monthly timeseries mortgage-credit block relevant to housing-credit drift is:

```text
MortgageStock
MortgageOrigination
MortgageRepayment
MortgageDefault
MortgageNetStockFlow
MortgageOriginationToStock
MortgageRepaymentToStock
MortgageDefaultToStock
MortgageNetStockFlowToStock
MortgageOriginationSupplyConstrained
MortgageToGdp
AnnualizedGdpProxy
```

`MortgageNetStockFlow` reports the expected monthly mortgage-book flow as
origination minus scheduled principal repayment minus gross mortgage default.
Comparing month-over-month `MortgageStock` deltas to `MortgageNetStockFlow`
isolates residual ledger or clipping effects. The three component `*ToStock`
rates identify whether mortgage runoff is driven by insufficient origination,
ordinary amortization, or default. `MortgageNetStockFlowToStock` is the compact
monthly growth/runoff rate of the mortgage book.

`MortgageToGdp` uses the closing ledger-owned `MortgageStock` divided by
`AnnualizedGdpProxy`, so a declining ratio can come from negative
`MortgageNetStockFlow`, a growing GDP denominator, or both. In the current
baseline, mortgage origination is not wired to a binding bank-supply gate:
`MortgageOriginationSupplyConstrained` remains false unless
`HousingMarket.processOrigination` is called with `bankCapacity = false`. If a
60-month baseline falls below the mortgage/GDP calibration band while this flag
is false, the calibration mechanism is `housing.originationRate` relative to
scheduled amortization, gross default, and GDP growth, not bank-resolution
supply. NBFI credit renewal is likewise stock based: `NbfiOriginationToStock`
should be compared with `NbfiRepaymentToStock` and `NbfiDefaultsToStock` to
separate insufficient origination from ordinary maturity/default runoff.

## Household Liquidity Diagnostics

The timeseries schema and terminal `_hh.csv` summary include generic household
liquidity diagnostics from ledger-owned household demand-deposit balances:

```text
HouseholdLiquidity_NetDemandDeposit
HouseholdLiquidity_PositiveDemandDeposits
HouseholdLiquidity_ImplicitOverdraft
HouseholdLiquidity_NegativeDepositCount
HouseholdLiquidity_NegativeDepositShare
HouseholdLiquidity_MinDemandDeposit
HouseholdLiquidity_DepositP01
HouseholdLiquidity_DepositP05
HouseholdLiquidity_DepositP10
HouseholdLiquidity_DepositP25
HouseholdLiquidity_DepositP50
HouseholdLiquidity_DepositP75
HouseholdLiquidity_DepositP90
HouseholdLiquidity_DepositP95
HouseholdLiquidity_DepositP99
```

The monthly timeseries consumer-credit block relevant to this reconciliation is:

```text
ConsumerOrigination
ConsumerApprovedOrigination
ConsumerCreditDemand
ConsumerRejectedOrigination
ConsumerBankRejectedOrigination
ConsumerDebtService
ConsumerPrincipal
ConsumerDefault
ConsumerLoanDefault
LiquidityBridgeChargeOff
ConsumerCredit_NetStockFlow
ConsumerCredit_UnderwrittenNetFlow
ConsumerCredit_BridgeNetFlow
ConsumerCredit_NplStock
ConsumerCredit_NplRatioGross
ConsumerCredit_ApprovedToDemand
ConsumerCredit_RejectedToDemand
ConsumerCredit_BankRejectedToDemand
ConsumerCredit_ShortfallToApprovedOrigination
```

The same mechanisms block exposes the household financial-distress state machine
as counts and shares:

```text
HouseholdDistress_Current
HouseholdDistress_LiquidityStress
HouseholdDistress_Arrears
HouseholdDistress_Restructuring
HouseholdDistress_Defaulted
HouseholdDistress_Bankruptcy
HouseholdDistress_CurrentShare
HouseholdDistress_LiquidityStressShare
HouseholdDistress_ArrearsShare
HouseholdDistress_RestructuringShare
HouseholdDistress_DefaultedShare
HouseholdDistress_BankruptcyShare
HouseholdDistress_ActiveShare
```

`HouseholdDistress_Bankruptcy` is the personal-insolvency/write-off state. The
older `HouseholdBankruptcies`, `HouseholdBankruptcyRate`, and terminal
`HH_Bankrupt` fields remain activity-status counts for the legacy absorbing
`HhStatus.Bankrupt` path.

The timeseries also includes residual shortfall settlement and its component
attribution. `ConsumerDefault` is the matching same-month default/write-off
diagnostic for the combined consumer-credit stock identity. `ConsumerPrincipal`
reports principal repayment and is not part of the default/write-off flow;
`ConsumerLoanDefault` and `LiquidityBridgeChargeOff` split the write-off flow
into ordinary consumer-loan default and same-month bridge charge-off:

```text
HouseholdLiquidity_ShortfallFinancing
HouseholdLiquidity_UnmetBasicConsumption
HouseholdLiquidity_DiscretionaryConsumptionCompression
HouseholdLiquidity_ConsumptionShortfall
HouseholdLiquidity_RentArrears
HouseholdLiquidity_MortgageArrears
HouseholdLiquidity_ConsumerDebtArrears
HouseholdLiquidity_TemporaryOverdraft
```

`PositiveDemandDeposits` sums `max(demandDeposit, 0)`;
`ImplicitOverdraft` sums `max(-demandDeposit, 0)`. Runtime household
`demandDeposit` is a non-negative deposit asset. A non-zero `ImplicitOverdraft`
therefore indicates legacy or fixture input rows, while the columns remain useful
as an invariant guard without writing household-level microdata by default.

`HouseholdLiquidity_ShortfallFinancing` is the residual liquidity bridge needed
to keep runtime household demand deposits non-negative after the household
budget has closed. Before this bridge is created, the household budget waterfall
compresses discretionary consumption. `HouseholdLiquidity_UnmetBasicConsumption`
records basic consumption need that was not covered by cash, and
`HouseholdLiquidity_DiscretionaryConsumptionCompression` records spending that
was cut before arrears/default settlement. The bridge is separate from
`ConsumerApprovedOrigination` and is
charged off through `ConsumerDefault` in the same month, so it does not survive
as ordinary household consumer-loan stock and does not bypass DTI underwriting.
`ConsumerOrigination` remains the gross SFC bridge plus approved-origination
flow, while `ConsumerApprovedOrigination` is the underwritten credit channel.
`ConsumerCreditDemand` records stressed households' DTI-based requested
principal before eligibility denial, and `ConsumerRejectedOrigination` records
the requested principal not approved by borrower-side or bank-side rules.
`ConsumerBankRejectedOrigination` is the subset rejected after household
eligibility because the household's routed bank fails the bank-side supply gate
or is already failed.
`ConsumerLoanDefault` reports only default of ordinary outstanding consumer-loan
principal; `LiquidityBridgeChargeOff` reports the same-month bridge write-off.
For the bridge component, the stock effect is zero because
`HouseholdLiquidity_ShortfallFinancing` is offset by `ConsumerDefault`.
`ConsumerCredit_NetStockFlow` reconciles the total monthly consumer-loan stock
flow as origination minus principal repayment minus default. The
`ConsumerCredit_UnderwrittenNetFlow` and `ConsumerCredit_BridgeNetFlow` columns
split that identity into the approved-loan channel and same-month bridge
channel. `ConsumerCredit_NplStock` reports the ordinary consumer-loan NPL stock.
The legacy `ConsumerNplRatio` uses performing `ConsumerLoans` as denominator;
`ConsumerCredit_NplRatioGross` uses `ConsumerLoans + ConsumerCredit_NplStock`
for a gross-book denominator. The `ConsumerCredit_*ToDemand` columns separate
borrower-side and bank-side rejection, while
`ConsumerCredit_ShortfallToApprovedOrigination` compares residual liquidity
bridge pressure to underwritten consumer-credit origination.

The `HouseholdLiquidity_ConsumptionShortfall`, `RentArrears`,
`MortgageArrears`, `ConsumerDebtArrears`, and `TemporaryOverdraft` columns split
that bridge amount by a diagnostic payment-priority attribution. They do not
create a separate monetary arrears stock; persistent household distress is
carried by `FinancialDistressState`. `TemporaryOverdraft` is not carried as a
separate household liability; any positive value is part of the same-month
bridge/default path visible in `HouseholdLiquidity_ShortfallFinancing` and
`ConsumerDefault`.

Household micro snapshots are disabled by default. When enabled, the runner
writes two combined files:

```text
mc/<prefix>_<run-id>_<months>m_household_snapshots.csv
mc/<prefix>_<run-id>_<months>m_household_shortfall_cohorts.csv
```

The schedule flag mirrors firm snapshots:

```bash
--household-snapshots terminal
--household-snapshots every:12
--household-snapshots months:1,6,12
--household-snapshots none
```

The row selector is separate from the schedule:

```bash
--household-snapshot-selector all
--household-snapshot-selector negative
--household-snapshot-selector shortfall
--household-snapshot-selector negative-or-shortfall
```

`negative` selects rows whose closing `DemandDeposit` is below zero and is
primarily a legacy/invariant guard after non-negative household deposits became
the runtime contract. `shortfall` selects rows with positive
`LiquidityShortfallFinancing`, which is the useful mode for diagnosing the
residual household liquidity gaps that are now bridged and charged off instead
of being capitalized into ordinary consumer-loan stock.

Snapshot rows are taken at the household-income/liquidity settlement boundary
for the selected execution month, before later firm-entry, migration, and
month-closing lifecycle reshuffling. This keeps per-household
`LiquidityShortfallFinancing` aligned with the monthly aggregate
`HouseholdLiquidity_ShortfallFinancing` before the standard Monte Carlo
Poland-scale multiplier is applied to macro timeseries PLN columns. Raw
household snapshot values are sample-level micro amounts, so reconcile them to
the seed timeseries by applying the same scaling used by `McTimeseriesSchema`.
Rows include run id, seed, month, household id, status, region, contract type,
bank id, wage, rent, MPC, skill, health penalty, financial distress months and
state, ledger-owned financial stocks, positive-deposit and implicit overdraft
decompositions, net liquid and financial positions, opening demand deposit,
opening/closing consumer-loan stock, monthly income, consumption, rent, mortgage
debt service, monthly consumer-credit demand/approval/rejection flow components, and the split
shortfall-settlement components.

The companion `household_shortfall_cohorts.csv` is always computed from the full
household snapshot boundary, regardless of the micro row selector. This keeps
cohort shares meaningful even when `--household-snapshot-selector shortfall`
writes only shortfalling micro rows. Cohort dimensions include `All`, `Status`,
`FinancialDistressState`, `Region`, `ContractType`, `IncomeDecile`,
`RentBurden`, `MortgageDebtServiceBurden`, `ConsumerDebtServiceBurden`, and
`ClosingConsumerLoanBurden`. The file reports counts, shortfall counts,
shortfall shares, monthly flow sums, split shortfall-settlement components, and
consumer-credit demand/approval/rejection sums plus burden ratios needed to diagnose which household cohorts drive
`HouseholdLiquidity_ShortfallFinancing`.

## Firm Snapshots

Firm microdata is disabled by default. When enabled, the runner writes one
combined file:

```text
mc/<prefix>_<run-id>_<months>m_firm_snapshots.csv
```

The CLI flag is:

```bash
--firm-snapshots terminal
--firm-snapshots every:12
--firm-snapshots months:1,6,12
```

Snapshot rows are one firm per selected execution month and include run id,
seed, month, firm id, sector, region, size class, workers, tech state,
bankruptcy reason, digital readiness, cash, firm loan, equity, bank id, risk
profile, initial size, capital stock, inventory, green capital, and ownership
flags. The existing terminal `_firms.csv` aggregate remains unchanged.

## Firm Decision Traces

Firm decision traces are disabled by default. When enabled, the runner writes
one combined file:

```text
mc/<prefix>_<run-id>_<months>m_firm_decision_trace.csv
```

The CLI flag is:

```bash
--firm-decision-trace ids:0,42,99
--firm-decision-trace first:25
--firm-decision-trace all
--firm-decision-trace none
```

Trace selection is by firm id and does not use model RNG. Rows are one selected
firm per execution month and include opening/closing tech state, decision type,
bankruptcy reason, cash, loan, digital readiness, workers, capex, new loan,
down payment, bank id, lending rate, technology credit type/source/need/amount,
available approval/feasibility/probability flags, plus separate adoption,
implementation, upgrade-candidate bank approval, investment-credit bank
approval, digital-invest, upgrade-efficiency, and labor-adjustment residual
rolls where those gates were evaluated.
