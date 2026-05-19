# Monte Carlo

The `montecarlo` package owns the Monte Carlo runner, output schemas,
CSV writers, console progress reporting, and typed result/error
wrappers. It is the only consumer of `McRunConfig` — the engine
pipeline has no dependency on this package.

## Files

| File | Object | Role |
|------|--------|------|
| `McRunner.scala` | `McRunner` | Orchestrates the Monte Carlo run: initializes seeds, streams monthly snapshots, writes per-seed CSVs, collects terminal summaries and optional firm micro exports |
| `McRunConfig.scala` | `McRunConfig` | Runtime config from CLI args: `nSeeds`, `outputPrefix`, `runDurationMonths`, `runId`, `firmSnapshotSchedule`, `firmDecisionTraceSelection` |
| `McFirmSnapshotSchedule.scala` | `McFirmSnapshotSchedule` | Disabled/terminal/cadence/explicit-month firm microdata export schedule |
| `McFirmSnapshotSchema.scala` | `McFirmSnapshotSchema` | Generic per-firm snapshot CSV header and row rendering |
| `McFirmSnapshotCsv.scala` | `McFirmSnapshotCsv` | Optional per-seed firm snapshot chunk writer and combined CSV finalizer |
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

`PositiveDemandDeposits` sums `max(demandDeposit, 0)`;
`ImplicitOverdraft` sums `max(-demandDeposit, 0)`. Runtime household
`demandDeposit` is a non-negative deposit asset. A non-zero `ImplicitOverdraft`
therefore indicates legacy or fixture input rows, while the columns remain useful
as an invariant guard without writing household-level microdata by default.

The monthly timeseries also includes `HouseholdLiquidity_ShortfallFinancing`.
This is the residual liquidity settlement routed into consumer-loan stock after
the household budget has closed. It is separate from
`ConsumerApprovedOrigination`, while `ConsumerOrigination` remains the total
consumer-loan stock origination used by SFC identities.

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
