# Monte Carlo

The `montecarlo` package owns the Monte Carlo runner, output schemas,
CSV writers, console progress reporting, and typed result/error
wrappers. It is the only consumer of `McRunConfig` — the engine
pipeline has no dependency on this package.

## Files

| File | Object | Role |
|------|--------|------|
| `McRunner.scala` | `McRunner` | Orchestrates the Monte Carlo run: initializes seeds, streams monthly snapshots, writes per-seed CSVs, collects terminal summaries and optional firm micro exports |
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
| `McTimeseriesSchema.scala` | `McTimeseriesSchema` | Timeseries schema with typed `Col` definitions, `compute`, and shared `csvSchema` |
| `McTimeseriesCsv.scala` | `McTimeseriesCsv` | Streaming per-seed timeseries CSV sink with temp-file finalization |
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

`runDurationMonths` is a Monte Carlo/runtime concern. It controls how
many monthly snapshots the runner materializes, but it is not part of
`SimParams` and should not leak into economic decision rules.

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
losses. `BankCapital_WaterfallResidual` reports
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
and is not included in the equity-capital waterfall identity.

`BankCreditLoss_*` columns normalize realized credit losses and gross
default/write-off flows by the relevant closing exposure stock, so bank-failure
months can be read without post-processing. `RealizedToOpeningCapital` measures
the total realized credit-loss hit against opening bank capital. Firm and
corporate-bond default rates reverse the configured recovery rate to estimate
gross default flow from net loss. Consumer diagnostics separate ordinary
consumer-loan default from liquidity-bridge charge-off; `ConsumerLossRate`
keeps the combined capital-loss view used by the bank waterfall.

## Bank Failure Diagnostics

The seed timeseries also includes monthly failure-trigger diagnostics:

```text
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

`BankFailure_NewNegativeCapital`, `BankFailure_NewCarBreach`, and
`BankFailure_NewLiquidityBreach` count newly failed banks by primary trigger.
If several triggers are true in the same month, the primary reason priority is
negative capital, then CAR breach, then LCR/liquidity breach.
`BankFailure_AllFailedFallback` is `1` when purchase-and-assumption resolution
had to choose an absorber from an all-failed bank set. This is a bridge-bank /
resolution-semantics warning, not an ordinary failure trigger.
`BankFailure_InvariantViolation` should stay zero; a non-zero value means the
failure-event diagnostics no longer reconcile to the new-failure count and the
run should be treated as an invalid model state. `BankFailure_FirstNewReasonCode`
uses stable codes: `0` none, `1` negative capital, `2` CAR breach,
`3` LCR/liquidity breach, `4` all-failed fallback, `5` invariant mismatch.

`BankReconciliation_*` columns quantify the exactness patch applied after the
normal bank update. `BankReconciliation_TargetBankId` identifies the bank row
receiving the patch. The capital before/after and CAR before/after fields show
whether the patch is just fixed-point cleanup or a material balance-sheet
transfer. `BankReconciliation_MaterialResidual` is `1` when the absolute
capital residual is at least 1 bp of the target bank's pre-patch capital.
`BankReconciliation_CrossedFailureThreshold` is `1` when the patch alone moves
the target bank from no failure trigger to a failure trigger; the post-patch
reason code uses the same reason-code mapping as `BankFailure_FirstNewReasonCode`.

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
ConsumerDebtService
ConsumerDefault
ConsumerLoanDefault
LiquidityBridgeChargeOff
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
diagnostic for the combined consumer-credit stock identity; `ConsumerLoanDefault`
and `LiquidityBridgeChargeOff` split that flow for interpretation:

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
the requested principal not approved by the consumer-credit rule.
`ConsumerLoanDefault` reports only default of ordinary outstanding consumer-loan
principal; `LiquidityBridgeChargeOff` reports the same-month bridge write-off.
For the bridge component, the stock effect is zero because
`HouseholdLiquidity_ShortfallFinancing` is offset by `ConsumerDefault`.

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
post-month assembly reshuffling. This keeps per-household
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
down payment, bank id, lending rate, available approval/feasibility/probability
flags, plus separate adoption, implementation, upgrade-candidate bank approval,
investment-credit bank approval, digital-invest, upgrade-efficiency, and
labor-adjustment residual rolls where those gates were evaluated.
