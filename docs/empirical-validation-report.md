# Empirical Validation Report

This hand-maintained report defines how Amor Fati outputs should be compared
with empirical macro, meso, and micro stylized facts. It is the workflow
companion for the curated source manifest and generated baseline snapshot;
readiness metadata lives in `docs/empirical-validation-source-manifest.csv`,
while pass/fail baseline results live in
`docs/empirical-validation/baseline-validation-snapshot.csv`.

The goal is to keep failed, missing, or weak validation targets visible. A row
with `MISSING_OUTPUT`, `MISSING_DATA_BRIDGE`, `MISSING_SOURCE_DETAIL`, or
`BRIDGE_ASSUMPTION` is part of the report surface, not an omission.
This report is empirical evidence, not calibration governance policy; deeper
source-of-truth, versioning, and parameter lifecycle decisions belong to the
[Calibration Governance milestone](https://github.com/boombustgroup/amor-fati/milestone/27).

## Validation Boundary

Accounting validation and empirical validation answer different questions:

| Surface | Question | Artifact |
| --- | --- | --- |
| Runtime ledger checks | Did emitted monetary flows conserve value through the ledger? | Engine runtime checks and SFC identity validation |
| Paper-facing accounting structure | Are Balance Sheet Matrix, Transactions Flow Matrix, and stock-flow reconciliation rows documented and traceable to runtime mechanisms? | `docs/sfc-matrix-evidence.md` |
| Empirical validation | Do simulated paths reproduce empirical Polish/EU macro, meso, and micro stylized facts? | This report |
| Revaluation and other-change accounting | Do stocks reconcile with transactions plus independent revaluations or other changes? | `docs/sfc-matrix-evidence.md` |

Empirical validation must not be used to relax accounting constraints. A model
run can fit an empirical target and still be invalid if the ledger or SFC
checks fail.

## Status Taxonomy

| Status | Meaning |
| --- | --- |
| `READY` | Output columns exist and the empirical target family is identified; pass/fail snapshots still require source manifest comparator metadata. |
| `PARTIAL` | Output exists but requires a derived metric, normalization, additional aggregation, or partial source bridge. |
| `MISSING_DATA_BRIDGE` | Model output exists, but external source extraction/transformation is not documented yet. |
| `MISSING_OUTPUT` | Target is known, but the Monte Carlo output schema does not yet expose enough model state. |
| `MISSING_SOURCE_DETAIL` | Source family is identified, but the concrete table, vintage, or access path is missing. |
| `BRIDGE_ASSUMPTION` | The target uses a documented empirical-to-model bridge assumption. |

Live status counts and per-row statuses are not duplicated here; read them from
the source manifest and generated snapshot CSVs.

### Snapshot Run-Result Taxonomy

The Baseline Report Snapshot `Status` column uses run-result tokens after a
baseline run. `PASS_BASELINE` means the model value is within the stated
tolerance, `FAIL_BASELINE` means it is outside the stated tolerance, and
`NOT_RUN` means the baseline snapshot has not been generated yet. Rows may
still carry manifest blockers such as `MISSING_DATA_BRIDGE` when comparator
metadata is not ready.

## Reproducible Workflow

Run a small deterministic baseline Monte Carlo batch. For committed
snapshots, choose the stable reference from `main` first and use the `run-id`
only as a technical file key:

```bash
sbt "runMain com.boombustgroup.amorfati.Main 5 validation-baseline --duration 60 --run-id main-0f281ce3"
```

Expected output files:

```text
mc/validation-baseline_main-0f281ce3_60m_seed001.csv
mc/validation-baseline_main-0f281ce3_60m_seed002.csv
mc/validation-baseline_main-0f281ce3_60m_seed003.csv
mc/validation-baseline_main-0f281ce3_60m_seed004.csv
mc/validation-baseline_main-0f281ce3_60m_seed005.csv
mc/validation-baseline_main-0f281ce3_60m_hh.csv
mc/validation-baseline_main-0f281ce3_60m_banks.csv
mc/validation-baseline_main-0f281ce3_60m_firms.csv
```

Use the per-seed CSV files for monthly macro, meso, financial, and mechanism
paths. Use the `_hh.csv`, `_banks.csv`, and `_firms.csv` files for terminal
household, bank, and firm cross-section summaries.

Runnable snapshot procedure after a baseline run:

1. Run the command above from the repository root.
2. Generate the empirical validation snapshot:

   ```bash
   sbt "empiricalValidation --source-manifest docs/empirical-validation-source-manifest.csv --mc-dir mc --run-id main-0f281ce3 --output-prefix validation-baseline --duration 60 --seeds 5 --commit 0f281ce3 --parameter-branch main --out docs/empirical-validation"
   ```

3. Review `docs/empirical-validation/baseline-validation-snapshot.csv`.
4. Keep rows with missing model output or missing empirical data in the table.

The source manifest is intentionally metadata-first. It records source
provider, URL, dataset code, vintage, access date, license/reuse note,
frequency, unit, transformation, model target, empirical value, tolerance, and
status without vendoring raw proprietary or large external datasets. The
generator also writes an effective `model-run-manifest.csv` with run id,
output prefix, duration, seed count, commit, parameter branch, and output
directory.

## Output Mapping

The primary numeric surface is
`src/main/scala/com/boombustgroup/amorfati/montecarlo/McTimeseriesSchema.scala`.
Terminal cross-sections come from
`src/main/scala/com/boombustgroup/amorfati/montecarlo/McTerminalSummarySchema.scala`.
Time-series macro PLN aggregates are emitted in Poland scale. Validation work
should not manually divide CSV values by `gdpRatio`.

This table is an illustrative family-level model-output mapping. The
authoritative per-row mapping lives in
`docs/empirical-validation-source-manifest.csv`, column `model_target`;
readiness and pass/fail status live in the source manifest and generated
snapshot CSVs.

| Validation target | Empirical comparator | Model output mapping | Suggested statistic |
| --- | --- | --- | --- |
| GDP growth | GUS national accounts real GDP growth | `MonthlyGdpProxy`, `AnnualizedGdpProxy` | Annual or quarterly growth of the emitted monthly GDP proxy, with real/nominal source convention stated by the validation manifest |
| Inflation | GUS CPI / HICP, NBP inflation target | `Inflation`, `PriceLevel`, `ExpectedInflation`, `InflationForecastError` | Mean, volatility, target deviation, persistence |
| Unemployment | GUS BAEL / registered unemployment | `Unemployment`, `Unemp_Central`, `Unemp_South`, `Unemp_East`, `Unemp_Northwest`, `Unemp_Southwest`, `Unemp_North` | Mean level, volatility, regional dispersion |
| Wages | GUS average wage, sector wage indices | `MarketWage`, `MeanEmployedWage`, `MinWageLevel`; terminal `_hh.csv` fields `MeanMonthlyIncome`, `MeanEmployedWage`, `WageP10`, `WageP50`, `WageP90` | Mean wage level and growth; minimum/market wage ratio; terminal wage distribution |
| Credit/GDP | NBP credit aggregates to GDP | `TotalCreditToGdp`, `TotalCreditStock`, `BankFirmLoans`, `BankFirmLoansToGdp`, `ConsumerLoans`, `ConsumerLoansToGdp`, `MortgageStock`, `MortgageToGdp`, `NbfiLoanStock`, `NbfiLoansToGdp`, `CreditToGdpGap`; terminal `_banks.csv` field `Loans` | Credit/GDP level, gap, household/firm/mortgage/NBFI split |
| Public debt/GDP | MF public debt, ESA2010 general-government debt | `DebtToGdp`, `Esa2010DebtToGdp`, `GovDebt`, `QfBondsOutstanding`, `BondsOutstanding` | Terminal debt/GDP and path against thresholds |
| Current account | NBP balance of payments | `CurrentAccount`, `CurrentAccountToGdp`, `CurrentAccountPrimaryIncome`, `CurrentAccountSecondaryIncome`, `CurrentAccountClosureResidual`, `TradeBalance_OE`, `TradeBalanceToGdp`, `Exports_OE`, `ExportsToGdp`, `TotalImports_OE`, `ImportsToGdp`, `ImportedIntermToImports`, `NetRemittances`, `NetTourismBalance`, `FDI` | Annualized current-account/GDP, component signs, and exported BoP closure |
| Firm-size distribution | GUS/REGON firm-size distribution | Terminal `_firms.csv` fields `FirmSize_Micro`, `FirmSize_Small`, `FirmSize_Medium`, `FirmSize_Large` and share fields | Terminal firm-size distribution (living firms only) |
| Bankruptcies and household distress | GUS / Ministry of Justice corporate insolvencies, consumer bankruptcy statistics, arrears/default comparators when available | `FirmDeaths`, `FirmBirths`, `NetEntry`, `HouseholdDistress_Bankruptcy`, `HouseholdDistress_*`, `HouseholdDistress_ActiveShare`, legacy status fields `HouseholdBankruptcies` and `HouseholdBankruptcyRate`, `BankFailures`; terminal `_hh.csv` fields `HH_Distress_*`, `HH_Distress_ActiveShare`, legacy `HH_Bankrupt`, `BankruptcyRate`, `MeanMonthsToRuin` | Firm exit rate, personal-insolvency/write-off state share, household distress-state shares, bank failures |
| Bank capital and liquidity | KNF banking-sector CAR, LCR, NSFR, NPL | `AggregateBankCAR`, `AggregateBankCapital`, `AggregateBankRWA`, `AggregateBankRWA_*`, `AggregateBankExposure_*`, `MinBankCAR`, `MinBankLCR`, `MinBankNSFR`, `NPL`, `MaxBankNPL`; terminal `_banks.csv` fields `CAR`, `NPL`, `Capital`, `Deposits`, `Loans` | Sector total-capital-ratio comparator, numerator/denominator attribution, plus minimum and distributional stress indicators |
| Inequality | GUS household surveys, EU-SILC, OECD income/wealth indicators | Terminal `_hh.csv` fields `Gini_Individual`, `Gini_Wealth`, `ConsumptionP10`, `ConsumptionP50`, `ConsumptionP90`, `PovertyRate_50pct`, `PovertyRate_30pct` | Terminal Gini, poverty rates, consumption percentile ratios |
| Sectoral output | GUS national accounts by sector, supply-use tables | `BPO_Output`, `Manuf_Output`, `Retail_Output`, `Health_Output`, `Public_Output`, `Agri_Output` | Sector output shares and growth |
| External prices and FX | NBP exchange rate, ECB/Eurostat external prices | `ExRate`, `ForeignPriceIndex`, `GvcImportCostIndex`, `CommodityPriceIndex`, `FxReserves`, `FxInterventionAmt` | FX level/volatility, reserve path, import-cost shocks |
| Housing and mortgages | NBP housing prices, mortgage stock, KNF mortgage risk | `HousingPriceIndex`, `WawHpi`, `KrkHpi`, `WroHpi`, `GdnHpi`, `LdzHpi`, `PozHpi`, `RestHpi`, `MortgageStock`, `MortgageOrigination`, `MortgageRepayment`, `MortgageDefault`, `MortgageNetStockFlow`, `MortgageToGdp` | HPI path, regional dispersion, mortgage/GDP, stock-flow runoff, defaults |
| Fiscal stance | MF budget execution, Eurostat deficit/GDP | `DeficitToGdp`, `GovCurrentSpend`, `GovCapitalSpendDomestic`, `DebtService`, `FiscalRuleBinding`, `GovSpendingCutRatio` | Deficit/GDP, expenditure mix, fiscal-rule episodes |
| Monetary and financial market conditions | NBP reference rate, WIBOR, bond yields, GPW | `RefRate`, `WIBOR_1M`, `WIBOR_3M`, `WIBOR_6M`, `BondYield`, `GpwIndex`, `GpwMarketCap`, `CorpBondYield`, `CorpBondSpread` | Policy-rate path, spread behavior, market stress |

## Baseline Report Snapshot

The current versioned baseline snapshot is commit-first. The technical
`run-id` is retained only to route Monte Carlo files; the stable model
reference is `main@0f281ce3`.

| Artifact | Path |
| --- | --- |
| Snapshot CSV | [`docs/empirical-validation/baseline-validation-snapshot.csv`](empirical-validation/baseline-validation-snapshot.csv) |
| Model run manifest | [`docs/empirical-validation/model-run-manifest.csv`](empirical-validation/model-run-manifest.csv) |
| Source manifest snapshot (generated copy) | [`docs/empirical-validation/source-manifest.csv`](empirical-validation/source-manifest.csv) |

Run metadata for the current snapshot lives in
[`docs/empirical-validation/model-run-manifest.csv`](empirical-validation/model-run-manifest.csv);
use the snapshot CSV for current status counts and row-level baseline results.
`FAIL_BASELINE` rows are interpreted as calibration evidence, not accounting
failures; the ledger and SFC validation surfaces remain separate from this
empirical fit table.

See [Reproducible Workflow](#reproducible-workflow) for the regeneration
commands for this snapshot.

## Target-Specific Notes

GDP growth should use the emitted `MonthlyGdpProxy` or `AnnualizedGdpProxy`
columns rather than reconstructing GDP from debt ratios. The validation
manifest must state whether the empirical comparator is real or nominal and
whether the statistic is monthly, quarterly, annualized, or year-over-year.

Inflation validation should use both the period inflation column and the price
level path. The report should state whether the statistic is monthly,
annualized, or year-over-year.

Credit/GDP should distinguish bank firm loans, consumer loans, mortgage credit,
and NBFI credit. Current outputs cover several pieces but do not yet provide a
single total-credit-to-GDP series.

Bank total-capital-ratio validation should use `AggregateBankCAR` from the
timeseries surface. That column computes sector capital over the explicit
regulatory RWA perimeter. Terminal `_banks.csv` `CAR` values remain useful for
per-bank dispersion and stress diagnostics, but their arithmetic mean is not a
KNF sector total-capital-ratio comparator and can overstate the sector ratio
when low-exposure bank rows are present.

The pre-#763 committed 5-seed, 60-month snapshot remained `FAIL_BASELINE` after
the metric fix: KNF comparator `0.211`, terminal `AggregateBankCAR = 0.26194`,
tolerance `0.020`. The #763 diagnostic rerun with the RWA attribution columns
classified that result as a credit-exposure calibration gap, not a
unit-conversion, RWA-floor, or per-bank averaging defect.

The #763 fix keeps bank capital calibration unchanged, removes per-loan reserve
utilization from the firm-credit stochastic gate, and adds a target
bank-debt share for physical investment so cash-rich firms do not fully bypass
the bank-loan book. The accepted 5-seed, 60-month diagnostic run
(`car-baseline-763-debttarget7`) uses `investmentDebtTargetShare = 0.07` and
keeps all bank rows alive. Terminal means:

| Metric | Value |
| --- | ---: |
| `AggregateBankCAR` | `0.2265` |
| `AggregateBankCapital` | PLN `171.6bn` |
| `AggregateBankRWA` | PLN `759.0bn` |
| `AggregateBankRWA_WeightedExposure` | PLN `759.0bn` |
| `AggregateBankRWA_OperationalRiskFloor` | PLN `24.8bn` |
| `AggregateBankRWA_CapitalBackstopFloor` | PLN `17.2bn` |
| `AggregateBankRWA_ExplicitAssetBase` | PLN `2,481.6bn` |
| `BankFirmLoans` | PLN `456.7bn` |
| `BankFailures` | `0` |

The weighted-exposure denominator is binding; neither the operational-risk
floor nor the capital-backstop floor determines `AggregateBankRWA`. RWA
composition at month 60 is approximately 60.2% firm loans, 16.6% consumer
loans, 22.4% mortgages, 0.9% corporate bonds, and 0% interbank, sovereign,
or reserve RWA because those latter exposures are zero or zero-risk-weighted
under the current perimeter.

The corrected 60-month path explains why the ratio moves toward the KNF
comparator. Firm-loan exposure rises from the pre-fix terminal PLN `354.1bn` to
PLN `456.7bn`; aggregate RWA rises from PLN `653.4bn` to PLN `759.0bn`; and
cash-financed physical investment falls from 96.6% to 90.3% of gross
investment. A more aggressive 10% target-debt share reached `AggregateBankCAR =
0.2109`, but produced bank failures in one seed and is therefore rejected as a
baseline calibration.

The remaining gap should be treated as credit-exposure and bank-financing
calibration, not as permission to relax the KNF row or widen its tolerance.
Future work should refine a continuous credit-supply rule with soft CAR/LCR/NSFR
cushions and risk-adjusted-return effects before retuning capital levels or RWA
weights.

Firm-size distribution and sectoral output now have direct output surfaces.
Use terminal `_firms.csv` fields for living-firm-only terminal size shares and
per-seed `*_Output` columns for sector output shares or growth.

Inequality validation is terminal-only for now. The household summary already
emits Gini, poverty, and consumption percentile fields, but external source
mapping and frequency conventions are documented in
`docs/data-bridge-national-financial-accounts.md`.

## Follow-Up Links

- [docs/data-bridge-national-financial-accounts.md](data-bridge-national-financial-accounts.md):
  external source mapping,
  source vintages, license/reuse notes, transformations, and prioritized
  empirical gaps.
- [docs/sensitivity-robustness-workflow.md](sensitivity-robustness-workflow.md):
  stochastic uncertainty, parameter
  sensitivity, confidence envelopes, and robustness metrics around this
  baseline report.
- [docs/scenario-registry.md](scenario-registry.md): named scenarios whose
  outputs should reuse the same validation target table.
