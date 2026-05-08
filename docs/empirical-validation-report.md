# Empirical Validation Report Skeleton

This report defines how Amor Fati outputs should be compared with empirical
macro, meso, and micro stylized facts. It is deliberately a skeleton: the
model already writes numeric simulation evidence, while external source
selection, transformation rules, source vintages, and prioritized empirical
gaps are documented in
`docs/data-bridge-national-financial-accounts.md`.

The goal is to keep failed, missing, or weak validation targets visible. A row
with `MISSING_OUTPUT`, `MISSING_DATA_BRIDGE`, `MISSING_SOURCE_DETAIL`, or
`BRIDGE_ASSUMPTION` is part of the report surface, not an omission.

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

### Snapshot Run-Result Taxonomy

The Baseline Report Snapshot `Status` column uses run-result tokens after a
baseline run. `PASS_BASELINE` means the model value is within the stated
tolerance, `FAIL_BASELINE` means it is outside the stated tolerance, and
`NOT_RUN` means the baseline snapshot has not been generated yet. Rows may
still carry manifest blockers such as `MISSING_DATA_BRIDGE` when comparator
metadata is not ready.

## Reproducible Workflow

Run a small deterministic baseline Monte Carlo batch:

```bash
sbt "run 3 validation-baseline --duration 120 --run-id validation-baseline"
```

Expected output files:

```text
mc/validation-baseline_validation-baseline_120m_seed001.csv
mc/validation-baseline_validation-baseline_120m_seed002.csv
mc/validation-baseline_validation-baseline_120m_seed003.csv
mc/validation-baseline_validation-baseline_120m_hh.csv
mc/validation-baseline_validation-baseline_120m_banks.csv
mc/validation-baseline_validation-baseline_120m_firms.csv
```

Use the per-seed CSV files for monthly macro, meso, financial, and mechanism
paths. Use the `_hh.csv`, `_banks.csv`, and `_firms.csv` files for terminal
household, bank, and firm cross-section summaries.

Runnable snapshot procedure after a baseline run:

1. Run the command above from the repository root.
2. Generate the empirical validation snapshot:

   ```bash
   sbt "empiricalValidation --source-manifest docs/empirical-validation-source-manifest.csv --mc-dir mc --run-id validation-baseline --output-prefix validation-baseline --duration 120 --seeds 3 --out target/empirical-validation"
   ```

3. Review `target/empirical-validation/baseline-validation-snapshot.csv` and
   `target/empirical-validation/baseline-validation-snapshot.md`.
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

| Validation target | Empirical comparator | Model output mapping | Suggested statistic | Current status |
| --- | --- | --- | --- | --- |
| GDP growth | GUS national accounts real GDP growth | `MonthlyGdpProxy`, `AnnualizedGdpProxy` | Annual or quarterly growth of the emitted monthly GDP proxy, with real/nominal source convention stated by the validation manifest | `READY` |
| Inflation | GUS CPI / HICP, NBP inflation target | `Inflation`, `PriceLevel`, `ExpectedInflation`, `InflationForecastError` | Mean, volatility, target deviation, persistence | `READY` |
| Unemployment | GUS BAEL / registered unemployment | `Unemployment`, `Unemp_Central`, `Unemp_South`, `Unemp_East`, `Unemp_Northwest`, `Unemp_Southwest`, `Unemp_North` | Mean level, volatility, regional dispersion | `READY` |
| Wages | GUS average wage, sector wage indices | `MarketWage`, `MeanEmployedWage`, `MinWageLevel`; terminal `_hh.csv` fields `MeanMonthlyIncome`, `MeanEmployedWage`, `WageP10`, `WageP50`, `WageP90` | Mean wage level and growth; minimum/market wage ratio; terminal wage distribution | `READY` |
| Credit/GDP | NBP credit aggregates to GDP | `TotalCreditToGdp`, `TotalCreditStock`, `BankFirmLoans`, `BankFirmLoansToGdp`, `ConsumerLoans`, `ConsumerLoansToGdp`, `MortgageStock`, `MortgageToGdp`, `NbfiLoanStock`, `NbfiLoansToGdp`, `CreditToGdpGap`; terminal `_banks.csv` field `Loans` | Credit/GDP level, gap, household/firm/mortgage/NBFI split | `READY` |
| Public debt/GDP | MF public debt, ESA2010 general-government debt | `DebtToGdp`, `Esa2010DebtToGdp`, `GovDebt`, `QfBondsOutstanding`, `BondsOutstanding` | Terminal debt/GDP and path against thresholds | `READY` |
| Current account | NBP balance of payments | `CurrentAccount`, `CurrentAccountToGdp`, `TradeBalance_OE`, `Exports_OE`, `TotalImports_OE`, `NetRemittances`, `NetTourismBalance`, `FDI` | Annualized current-account/GDP and component signs | `READY` |
| Firm-size distribution | GUS/REGON firm-size distribution | Terminal `_firms.csv` fields `FirmSize_Micro`, `FirmSize_Small`, `FirmSize_Medium`, `FirmSize_Large` and share fields | Terminal firm-size distribution (living firms only) | `READY` |
| Bankruptcies | GUS / Ministry of Justice corporate insolvencies, consumer bankruptcy statistics | `FirmDeaths`, `FirmBirths`, `NetEntry`, `HouseholdBankruptcies`, `HouseholdBankruptcyRate`, `BankFailures`; terminal `_hh.csv` fields `HH_Bankrupt`, `BankruptcyRate`, `MeanMonthsToRuin` | Firm exit rate, household bankruptcy rate, bank failures | `READY` |
| Bank capital and liquidity | KNF banking-sector CAR, LCR, NSFR, NPL | `MinBankCAR`, `MinBankLCR`, `MinBankNSFR`, `NPL`, `MaxBankNPL`; terminal `_banks.csv` fields `CAR`, `NPL`, `Capital`, `Deposits`, `Loans` | Minimum and distributional stress indicators | `READY` |
| Inequality | GUS household surveys, EU-SILC, OECD income/wealth indicators | Terminal `_hh.csv` fields `Gini_Individual`, `Gini_Wealth`, `ConsumptionP10`, `ConsumptionP50`, `ConsumptionP90`, `PovertyRate_50pct`, `PovertyRate_30pct` | Terminal Gini, poverty rates, consumption percentile ratios | `MISSING_DATA_BRIDGE` |
| Sectoral output | GUS national accounts by sector, supply-use tables | `BPO_Output`, `Manuf_Output`, `Retail_Output`, `Health_Output`, `Public_Output`, `Agri_Output` | Sector output shares and growth | `READY` |
| External prices and FX | NBP exchange rate, ECB/Eurostat external prices | `ExRate`, `ForeignPriceIndex`, `GvcImportCostIndex`, `CommodityPriceIndex`, `FxReserves`, `FxInterventionAmt` | FX level/volatility, reserve path, import-cost shocks | `READY` |
| Housing and mortgages | NBP housing prices, mortgage stock, KNF mortgage risk | `HousingPriceIndex`, `WawHpi`, `KrkHpi`, `WroHpi`, `GdnHpi`, `LdzHpi`, `PozHpi`, `RestHpi`, `MortgageToGdp`, `MortgageDefault` | HPI path, regional dispersion, mortgage/GDP, defaults | `READY` |
| Fiscal stance | MF budget execution, Eurostat deficit/GDP | `DeficitToGdp`, `GovCurrentSpend`, `GovCapitalSpendDomestic`, `DebtService`, `FiscalRuleBinding`, `GovSpendingCutRatio` | Deficit/GDP, expenditure mix, fiscal-rule episodes | `READY` |
| Monetary and financial market conditions | NBP reference rate, WIBOR, bond yields, GPW | `RefRate`, `WIBOR_1M`, `WIBOR_3M`, `WIBOR_6M`, `BondYield`, `GpwIndex`, `GpwMarketCap`, `CorpBondYield`, `CorpBondSpread` | Policy-rate path, spread behavior, market stress | `READY` |

## Baseline Report Snapshot

This table is the publication-facing slot that should be filled after a
baseline run. Placeholder values remain explicit until the empirical data
bridge and baseline analysis have been completed.

| Target | Empirical source and vintage | Empirical value | Model run | Model value | Tolerance / criterion | Status | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| GDP growth | Poland 2026 projection band tracked by #461; final source manifest still TBD via `docs/data-bridge-national-financial-accounts.md` | 3.3%-3.7% real YoY | `main-baseline-clean_seeds10-48m`, 10 seeds, 48 months | m24 real proxy +3.56% YoY; nominal +6.69% YoY | m24 real proxy inside 3.3%-3.7% band | `PASS_BASELINE` | Uses `MonthlyGdpProxy / PriceLevel`; CSV values are already Poland-scale. |
| Inflation | TBD via `docs/data-bridge-national-financial-accounts.md` | TBD | `validation-baseline` | TBD | TBD | `MISSING_DATA_BRIDGE` | Use `Inflation` and `PriceLevel`. |
| Unemployment | TBD via `docs/data-bridge-national-financial-accounts.md` | TBD | `validation-baseline` | TBD | TBD | `MISSING_DATA_BRIDGE` | Include regional dispersion. |
| Wages | TBD via `docs/data-bridge-national-financial-accounts.md` | TBD | `validation-baseline` | TBD | TBD | `MISSING_DATA_BRIDGE` | Current output is aggregate market wage. |
| Credit/GDP | TBD via `docs/data-bridge-national-financial-accounts.md` | TBD | `validation-baseline` | TBD | TBD | `MISSING_DATA_BRIDGE` | Firm-loan split depends partly on terminal bank summary. |
| Public debt/GDP | TBD via `docs/data-bridge-national-financial-accounts.md` | TBD | `validation-baseline` | TBD | TBD | `MISSING_DATA_BRIDGE` | Compare `DebtToGdp` and `Esa2010DebtToGdp`. |
| Current account | TBD via `docs/data-bridge-national-financial-accounts.md` | TBD | `validation-baseline` | TBD | TBD | `MISSING_DATA_BRIDGE` | External-balance calibration remains a follow-up surface. |
| Firm-size distribution | TBD via `docs/data-bridge-national-financial-accounts.md` | TBD | `validation-baseline` | TBD | TBD | `MISSING_DATA_BRIDGE` | Use terminal `_firms.csv` firm-size counts and shares. |
| Bankruptcies | TBD via `docs/data-bridge-national-financial-accounts.md` | TBD | `validation-baseline` | TBD | TBD | `MISSING_DATA_BRIDGE` | Use firm deaths and household bankruptcy separately. |
| Bank capital/liquidity | TBD via `docs/data-bridge-national-financial-accounts.md` | TBD | `validation-baseline` | TBD | TBD | `MISSING_DATA_BRIDGE` | Use minima and terminal bank distribution. |
| Inequality | TBD via `docs/data-bridge-national-financial-accounts.md` | TBD | `validation-baseline` | TBD | TBD | `MISSING_DATA_BRIDGE` | Terminal household summary has first-pass measures. |
| Sectoral output | TBD via `docs/data-bridge-national-financial-accounts.md` | TBD | `validation-baseline` | TBD | TBD | `MISSING_DATA_BRIDGE` | Use emitted `*_Output` sector columns. |
| External prices and FX | TBD via `docs/data-bridge-national-financial-accounts.md` | TBD | `validation-baseline` | TBD | TBD | `MISSING_DATA_BRIDGE` | Use `ExRate`, external price indices, FX reserves, and intervention columns. |
| Housing and mortgages | TBD via `docs/data-bridge-national-financial-accounts.md` | TBD | `validation-baseline` | TBD | TBD | `MISSING_DATA_BRIDGE` | Use HPI, regional HPI, mortgage/GDP, and mortgage-default columns. |
| Fiscal stance | TBD via `docs/data-bridge-national-financial-accounts.md` | TBD | `validation-baseline` | TBD | TBD | `MISSING_DATA_BRIDGE` | Use deficit/GDP, expenditure mix, debt service, and fiscal-rule columns. |
| Monetary and financial market conditions | TBD via `docs/data-bridge-national-financial-accounts.md` | TBD | `validation-baseline` | TBD | TBD | `MISSING_DATA_BRIDGE` | Use reference rate, WIBOR, bond-yield, GPW, and corporate-spread columns. |

### Current main baseline sanity run

Run: `main-baseline-clean_seeds10-48m`, commit `68f6e534`, 10 seeds, 48
months. Real proxy is computed from emitted CSV columns as
`MonthlyGdpProxy / PriceLevel`; no manual `gdpRatio` rescaling is applied.

| Month | Nominal GDP YoY | Real proxy YoY | Inflation YoY | Unemployment |
| --- | ---: | ---: | ---: | ---: |
| 13 | 8.40% | 3.92% | 4.31% | 6.77% |
| 24 | 6.69% | 3.56% | 3.02% | 6.58% |
| 36 | 5.78% | 3.00% | 2.70% | 6.41% |
| 48 | 3.80% | 0.95% | 2.83% | 6.73% |

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
