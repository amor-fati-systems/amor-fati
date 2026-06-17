# Empirical Validation Report

This hand-maintained report defines how Amor Fati outputs should be compared
with empirical macro, meso, and micro stylized facts. It is the workflow
companion for the curated source manifest and generated baseline snapshot;
readiness metadata lives in `docs/empirical-validation-source-manifest.tsv`,
while pass/fail baseline results live in
`docs/empirical-validation/baseline-validation-snapshot.tsv`.

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
the source manifest and generated snapshot TSVs.

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
mc/validation-baseline_main-0f281ce3_60m_seed001.tsv
mc/validation-baseline_main-0f281ce3_60m_seed002.tsv
mc/validation-baseline_main-0f281ce3_60m_seed003.tsv
mc/validation-baseline_main-0f281ce3_60m_seed004.tsv
mc/validation-baseline_main-0f281ce3_60m_seed005.tsv
mc/validation-baseline_main-0f281ce3_60m_hh.tsv
mc/validation-baseline_main-0f281ce3_60m_banks.tsv
mc/validation-baseline_main-0f281ce3_60m_firms.tsv
```

Use the per-seed TSV files for monthly macro, meso, financial, and mechanism
paths. Use the `_hh.tsv`, `_banks.tsv`, and `_firms.tsv` files for terminal
household, bank, and firm cross-section summaries.

Runnable snapshot procedure after a baseline run:

1. Run the command above from the repository root.
2. Generate the empirical validation snapshot:

   ```bash
   sbt "empiricalValidation --source-manifest docs/empirical-validation-source-manifest.tsv --mc-dir mc --run-id main-0f281ce3 --output-prefix validation-baseline --duration 60 --seeds 5 --commit 0f281ce3 --parameter-branch main --out docs/empirical-validation"
   ```

3. Review `docs/empirical-validation/baseline-validation-snapshot.tsv`.
4. Keep rows with missing model output or missing empirical data in the table.

The source manifest is intentionally metadata-first. It records source
provider, URL, dataset code, vintage, access date, license/reuse note,
frequency, unit, transformation, model target, empirical value, tolerance, and
status without vendoring raw proprietary or large external datasets. The
generator also writes an effective `model-run-manifest.tsv` with run id,
output prefix, duration, seed count, commit, parameter branch, and output
directory.

## Output Mapping

The primary numeric surface is
`src/main/scala/com/boombustgroup/amorfati/montecarlo/McTimeseriesSchema.scala`.
Terminal cross-sections come from
`src/main/scala/com/boombustgroup/amorfati/montecarlo/McTerminalSummarySchema.scala`.
Time-series macro PLN aggregates are emitted in Poland scale. Validation work
should not manually divide TSV values by `gdpRatio`.

This table is an illustrative family-level model-output mapping. The
authoritative per-row mapping lives in
`docs/empirical-validation-source-manifest.tsv`, column `model_target`;
readiness and pass/fail status live in the source manifest and generated
snapshot TSVs.

| Validation target | Empirical comparator | Model output mapping | Suggested statistic |
| --- | --- | --- | --- |
| GDP growth | GUS national accounts real GDP growth | `MonthlyGdpProxy`, `AnnualizedGdpProxy` | Annual or quarterly growth of the emitted monthly GDP proxy, with real/nominal source convention stated by the validation manifest |
| Inflation | GUS CPI / HICP, NBP inflation target | `Inflation`, `PriceLevel`, `ExpectedInflation`, `InflationForecastError` | Mean, volatility, target deviation, persistence |
| Unemployment | GUS BAEL / registered unemployment | `Unemployment`, `Unemp_Central`, `Unemp_South`, `Unemp_East`, `Unemp_Northwest`, `Unemp_Southwest`, `Unemp_North` | Mean level, volatility, regional dispersion |
| Wages | GUS average wage, sector wage indices | `MarketWage`, `MeanEmployedWage`, `MinWageLevel`; terminal `_hh.tsv` fields `MeanMonthlyIncome`, `MeanEmployedWage`, `WageP10`, `WageP50`, `WageP90` | Mean wage level and growth; minimum/market wage ratio; terminal wage distribution |
| Credit/GDP | NBP credit aggregates to GDP | `TotalCreditToGdp`, `TotalCreditStock`, `BankFirmLoans`, `BankFirmLoansToGdp`, `ConsumerLoans`, `ConsumerLoansToGdp`, `MortgageStock`, `MortgageToGdp`, `NbfiLoanStock`, `NbfiLoansToGdp`, `CreditToGdpGap`; terminal `_banks.tsv` field `Loans` | Credit/GDP level, gap, household/firm/mortgage/NBFI split |
| Credit-supply standards | NBP `Sytuacja na rynku kredytowym` senior loan officer survey | `FirmCredit_ApprovalRate`, `ConsumerCredit_ApprovedToDemand`, `MortgageOriginationSupplyConstrained`, and model-only rejection decomposition columns | Directional change in approval or supply-constraint proxies after the SLOOS bridge is extracted; never direct level comparison |
| Credit demand | NBP `Sytuacja na rynku kredytowym` borrower-demand questions | `FirmCredit_CreditDemand`, `ConsumerCreditDemand`, and demand-normalized decomposition columns such as `ConsumerCredit_ApprovedToDemand` and `ConsumerCredit_RejectedToDemand` | Directional change in borrower-demand proxies after the SLOOS demand bridge is extracted; never evidence of bank-side tightening |
| Public debt/GDP | MF public debt, ESA2010 general-government debt | `DebtToGdp`, `Esa2010DebtToGdp`, `GovDebt`, `QfBondsOutstanding`, `BondsOutstanding` | Terminal debt/GDP and path against thresholds |
| Current account | NBP balance of payments | `CurrentAccount`, `CurrentAccountToGdp`, `CurrentAccountPrimaryIncome`, `CurrentAccountSecondaryIncome`, `CurrentAccountClosureResidual`, `TradeBalance_OE`, `TradeBalanceToGdp`, `Exports_OE`, `ExportsToGdp`, `TotalImports_OE`, `ImportsToGdp`, `ImportedIntermToImports`, `NetRemittances`, `NetTourismBalance`, `FDI` | Annualized current-account/GDP, component signs, and exported BoP closure |
| Firm-size distribution | GUS/REGON firm-size distribution | Terminal `_firms.tsv` fields `FirmSize_Micro`, `FirmSize_Small`, `FirmSize_Medium`, `FirmSize_Large` and share fields | Terminal firm-size distribution (living firms only) |
| Bankruptcies and household distress | GUS / Ministry of Justice corporate insolvencies, consumer bankruptcy statistics, arrears/default comparators when available | `FirmDeaths`, `FirmBirths`, `NetEntry`, `HouseholdDistress_Bankruptcy`, `HouseholdDistress_*`, `HouseholdDistress_ActiveShare`, legacy status fields `HouseholdBankruptcies` and `HouseholdBankruptcyRate`, `BankFailures`; terminal `_hh.tsv` fields `HH_Distress_*`, `HH_Distress_ActiveShare`, legacy `HH_Bankrupt`, `BankruptcyRate`, `MeanMonthsToRuin` | Firm exit rate, personal-insolvency/write-off state share, household distress-state shares, bank failures |
| Bank capital and liquidity | KNF banking-sector CAR, LCR, NSFR, NPL | `AggregateBankCAR`, `AggregateBankCapital`, `AggregateBankRWA`, `AggregateBankRWA_*`, `AggregateBankExposure_*`, `MinBankCAR`, `MinBankLCR`, `MinBankNSFR`, `NPL`, `MaxBankNPL`; terminal `_banks.tsv` fields `CAR`, `NPL`, `Capital`, `Deposits`, `Loans` | Sector total-capital-ratio comparator, numerator/denominator attribution, plus minimum and distributional stress indicators |
| Inequality | GUS household surveys, EU-SILC, OECD income/wealth indicators | Terminal `_hh.tsv` fields `Gini_Individual`, `Gini_Wealth`, `ConsumptionP10`, `ConsumptionP50`, `ConsumptionP90`, `PovertyRate_50pct`, `PovertyRate_30pct` | Terminal Gini, poverty rates, consumption percentile ratios |
| Sectoral output | GUS national accounts by sector, supply-use tables | `BPO_Output`, `Manuf_Output`, `Retail_Output`, `Health_Output`, `Public_Output`, `Agri_Output` | Sector output shares and growth |
| External prices and FX | NBP exchange rate, ECB/Eurostat external prices | `ExRate`, `ForeignPriceIndex`, `GvcImportCostIndex`, `CommodityPriceIndex`, `FxReserves`, `FxInterventionAmt` | FX level/volatility, reserve path, import-cost shocks |
| Housing and mortgages | NBP housing prices, mortgage stock, KNF mortgage risk | `HousingPriceIndex`, `WawHpi`, `KrkHpi`, `WroHpi`, `GdnHpi`, `LdzHpi`, `PozHpi`, `RestHpi`, `MortgageStock`, `MortgageOrigination`, `MortgageOriginationSupplyConstrained`, `MortgageRepayment`, `MortgageDefault`, `MortgageNetStockFlow`, `MortgageToGdp`, `AvgMortgageRate` | HPI path, regional dispersion, mortgage/GDP, stock-flow runoff, secured-credit supply constraints, defaults |
| Fiscal stance | MF budget execution, Eurostat deficit/GDP | `DeficitToGdp`, `GovCurrentSpend`, `GovCapitalSpendDomestic`, `DebtService`, `FiscalRuleBinding`, `GovSpendingCutRatio` | Deficit/GDP, expenditure mix, fiscal-rule episodes |
| Monetary and financial market conditions | NBP reference rate, WIBOR, bond yields, GPW | `RefRate`, `WIBOR_1M`, `WIBOR_3M`, `WIBOR_6M`, `BondYield`, `GpwIndex`, `GpwMarketCap`, `CorpBondYield`, `CorpBondSpread` | Policy-rate path, spread behavior, market stress |

## Baseline Report Snapshot

The current versioned baseline snapshot is commit-first. The technical
`run-id` is retained only to route Monte Carlo files; the stable model
reference is `main@0f281ce3`.

| Artifact | Path |
| --- | --- |
| Snapshot TSV | [`docs/empirical-validation/baseline-validation-snapshot.tsv`](empirical-validation/baseline-validation-snapshot.tsv) |
| Model run manifest | [`docs/empirical-validation/model-run-manifest.tsv`](empirical-validation/model-run-manifest.tsv) |
| Source manifest snapshot (generated copy) | [`docs/empirical-validation/source-manifest.tsv`](empirical-validation/source-manifest.tsv) |

Run metadata for the current snapshot lives in
[`docs/empirical-validation/model-run-manifest.tsv`](empirical-validation/model-run-manifest.tsv);
use the snapshot TSV for current status counts and row-level baseline results.
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
regulatory RWA perimeter. Terminal `_banks.tsv` `CAR` values remain useful for
per-bank dispersion and stress diagnostics, but their arithmetic mean is not a
KNF sector total-capital-ratio comparator and can overstate the sector ratio
when low-exposure bank rows are present.

The pre-#763 committed 5-seed, 60-month snapshot remained `FAIL_BASELINE` after
the metric fix: KNF comparator `0.211`, terminal `AggregateBankCAR = 0.26194`,
tolerance `0.020`. The #763 diagnostic rerun with the RWA attribution columns
classified that result as a credit-exposure calibration gap, not a
unit-conversion, RWA-floor, or per-bank averaging defect.

The #763 fix keeps bank capital calibration unchanged, removes per-loan reserve
utilization from the firm-credit approval gate, and adds a target
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

### Dynamic Credit Validation

Terminal prudential ratios and dynamic credit validation answer different
questions. `AggregateBankCAR` checks whether the model lands near the KNF
banking-sector capital-ratio comparator at the end of the run. Dynamic credit
validation asks whether the path of private credit, approval rates, borrower
demand, and NPL pressure is empirically plausible over the run horizon.

The source manifest therefore carries separate partial bridge rows for:

| Bridge family | Model target | Current status |
| --- | --- | --- |
| Aggregate credit-stock growth | `timeseries:TotalCreditStock:pct_change` | NBP MFI stock-series extraction and horizon alignment remain open. |
| Firm-loan growth | `timeseries:BankFirmLoans:pct_change` | NBP enterprise/NFC credit stock extraction and sector-definition mapping remain open. |
| Consumer-loan growth | `timeseries:ConsumerLoans:pct_change` | NBP consumer-credit stock extraction and product-definition mapping remain open. |
| Firm approval proxy | `timeseries:FirmCredit_ApprovalRate:delta` | SLOOS source-window extraction and directional criterion remain open. |
| Consumer approval proxy | `timeseries:ConsumerCredit_ApprovedToDemand:delta` | SLOOS source-window extraction and directional criterion remain open. |
| Mortgage supply-constraint proxy | `timeseries:MortgageOriginationSupplyConstrained:delta` | SLOOS housing-credit standards extraction and secured-credit directional criterion remain open. |
| Firm borrower-demand proxy | `timeseries:FirmCredit_CreditDemand:delta` | SLOOS demand-question extraction, sign convention, and directional criterion remain open. |
| Consumer borrower-demand proxy | `timeseries:ConsumerCreditDemand:delta` | SLOOS consumer-demand extraction, sign convention, and directional criterion remain open. |
| Aggregate NPL trajectory | `timeseries:MaxBankNPL:max` | KNF NPL trajectory extraction and regulatory-definition mapping remain open. |
| Consumer NPL trajectory | `timeseries:ConsumerCredit_NplRatioGross:max` | Product-level KNF/NBP NPL extraction and definition mapping remain open. |

The `pct_change` statistic is computed per seed as `last / first - 1`, then
averaged across seeds. This makes loan-growth rows comparable to external stock
growth series once the NBP monetary-statistics bridge is extracted. It must not
be read as a path-distance statistic; slope, RMSE, dynamic time warping, and
posterior-calibration metrics belong to later calibration-governance work after
source series are ingested.

### SLOOS Credit-Supply Bridge

SLOOS rows are not numeric validation rows yet. The NBP
`Sytuacja na rynku kredytowym` survey is a quarterly balance-of-opinion survey
of bank credit standards and demand. It is not an approval-rate level series.
Issue #790 therefore defines the bridge as a directional supply-tightening
mapping, not as a direct comparison between survey percentages and model
approval probabilities.

The baseline vintage rule is strict: for the `2026-04-30` Amor Fati calibration
snapshot, use the latest NBP SLOOS release that was publicly available on or
before `2026-04-30`. Later releases may be used only after a deliberate
baseline refresh or in a clearly labelled ex-post validation exercise. This
prevents future survey information from leaking into the model-start evidence
surface.

The source side should use realized credit-standard changes, not expectation
questions, unless the row is explicitly labelled as a forward-looking scenario
or expectations validation row. The product mapping is:

| NBP SLOOS segment | Amor Fati model proxy | Scope |
| --- | --- | --- |
| Enterprise / corporate credit standards | `FirmCredit_ApprovalRate` | Bank-side supply for firm credit. SME/large-firm splits require a later model segment bridge. |
| Consumer credit standards | `ConsumerCredit_ApprovedToDemand` | Bank-side and borrower-side approved-to-demand proxy for unsecured household consumer credit. |
| Housing credit standards | `MortgageOriginationSupplyConstrained` | Secured-credit standards belong to the mortgage bridge below, not to unsecured consumer-credit validation. |
| Credit demand questions | Not mapped by #790 | Demand belongs to separate credit-demand validation rows, not supply standards. |

If the NBP release reports net tightening as `tightened - eased`, convert it to
a net-easing sign by multiplying by `-1`. If it reports net easing directly,
keep the sign. The source-side object is a directional change over a declared
window, normally a calendar-quarter change in realized standards. The model-side
object must use the same window. Current manifest rows expose
`timeseries:*:delta`, a first-to-last model summary for the cited run, and must
not be promoted until the source window is recorded explicitly.

```text
A_model(q)      = mean approval proxy over months in quarter q and all cited seeds
Delta_model(q)  = A_model(q) - A_model(q - 1)
SLOOS_easing(q) = eased_share(q) - tightened_share(q)
```

Directional interpretation:

| Source signal | Expected model signal |
| --- | --- |
| `SLOOS_easing(q) > 0` | `Delta_model(q) > 0`: easier standards, higher approval proxy |
| `SLOOS_easing(q) < 0` | `Delta_model(q) < 0`: tighter standards, lower approval proxy |
| near zero | no material directional claim unless a deadband is documented |

Before a SLOOS row can move out of `MISSING_DATA_BRIDGE`, the source manifest
must record the exact NBP release, table or chart identifier, whether the
question is realized or expected, segment, sign convention, source deadband if
any, model aggregation window, empirical value, tolerance or directional
criterion, and the model run metadata. Until then the manifest keeps SLOOS rows
as `MISSING_DATA_BRIDGE` with no empirical value and no tolerance.

Firm credit-rejection decomposition columns such as
`FirmCredit_RejectedCapitalBuffer` and
`FirmCredit_RejectedPortfolioPreference` are model-only diagnostics. They are
useful for explaining why the model tightened credit supply, but they do not
have direct public NBP or KNF comparators and should not be promoted to
empirical-validation rows without a source bridge.

### SLOOS Mortgage-Credit Standards Bridge

Mortgage credit standards are secured-credit evidence, not unsecured consumer
credit evidence. They must remain separate because mortgage origination depends
on collateral values, LTV/DSTI constraints, housing prices, mortgage rates, and
secured default mechanics.

The same baseline vintage rule applies: for the `2026-04-30` Amor Fati
calibration snapshot, use the latest NBP SLOOS release that was publicly
available on or before `2026-04-30`. Later releases are ex-post validation
unless the baseline itself is deliberately refreshed.

The source side should use realized housing-credit standard changes, not demand
or expectation questions, unless a row is explicitly labelled as expectations
or demand validation. The initial secured-credit standards bridge is:

| NBP SLOOS segment | Amor Fati model proxy | Scope |
| --- | --- | --- |
| Housing / mortgage credit standards | `MortgageOriginationSupplyConstrained` | Supply-side secured-credit constraint proxy. A tightening episode should raise the constrained-origination incidence, all else equal. |
| Mortgage origination volume | `MortgageOrigination`, `MortgageOriginationToStock` | Supporting diagnostics only, because realized origination mixes standards, borrower demand, housing prices, and interest rates. |
| Mortgage price channel | `AvgMortgageRate` | Supporting diagnostic for secured-credit pricing; not a direct SLOOS standards comparator. |
| Housing / mortgage credit demand | Not mapped by #792 | Mortgage demand needs a separate secured-credit demand bridge if it is promoted later. |

If the NBP release reports net tightening as `tightened - eased`, convert it to
a net-easing sign by multiplying by `-1`. If it reports net easing directly,
keep the sign. Current manifest rows use
`model_target = timeseries:MortgageOriginationSupplyConstrained:delta`, a
first-to-last summary over the cited model run. The quarterly `M_model(q)` and
`Delta_model(q)` formulation below is the intended SLOOS-aligned aggregation and
requires the remaining bridge work before promotion out of
`MISSING_DATA_BRIDGE`.

```text
M_model(q)      = mean MortgageOriginationSupplyConstrained over months in quarter q and all cited seeds
Delta_model(q)  = M_model(q) - M_model(q - 1)
SLOOS_easing(q) = eased_share(q) - tightened_share(q)
```

Directional interpretation:

| Source signal | Expected model signal |
| --- | --- |
| `SLOOS_easing(q) > 0` | `Delta_model(q) < 0`: easier standards, lower constrained-origination incidence |
| `SLOOS_easing(q) < 0` | `Delta_model(q) > 0`: tighter standards, higher constrained-origination incidence |
| near zero | no material directional claim unless a deadband is documented |

Before a mortgage-standards row can move out of `MISSING_DATA_BRIDGE`, the
source manifest must record the exact NBP release, table or chart identifier,
whether the question is realized or expected, segment, sign convention, source
deadband if any, model aggregation window, empirical value, tolerance or
directional criterion, and the model run metadata.

### SLOOS Credit-Demand Bridge

SLOOS demand questions are borrower-demand evidence, not credit-supply
evidence. They must remain separate from the credit-standard and approval
bridge above so a falling private-credit path can be decomposed into weak
borrower demand versus tighter bank supply.

The same baseline vintage rule applies: for the `2026-04-30` Amor Fati
calibration snapshot, use the latest NBP SLOOS release that was publicly
available on or before `2026-04-30`. Later releases are ex-post validation
unless the baseline itself is deliberately refreshed.

The source side should use realized demand questions, not expectation questions,
unless a row is explicitly labelled as expectations validation. The initial
bridge maps only unsecured consumer-credit and enterprise borrower demand:

| NBP SLOOS segment | Amor Fati model proxy | Scope |
| --- | --- | --- |
| Enterprise / corporate credit demand | `FirmCredit_CreditDemand` | Borrower-side firm-credit demand before bank approval. `FirmCredit_InvestmentDemand` and `FirmCredit_TechDemand` are supporting decomposition columns until the source bridge names a matching product split. |
| Consumer credit demand | `ConsumerCreditDemand` | Borrower-side unsecured household consumer-credit demand before approval. Approved/rejected-to-demand ratios are decomposition diagnostics, not direct demand comparators. |
| Housing credit demand | Not mapped by #793 | Mortgage demand belongs to the secured-credit bridge because collateral, DSTI/LTV, and product maturity differ from unsecured consumer credit. |
| Credit-standard questions | Not mapped by #793 | Standards and approval belong to the SLOOS credit-supply bridge above. |

If the NBP release reports net demand increase as
`increased_share - decreased_share`, keep the sign. If it reports net demand
decrease directly, multiply by `-1` before comparison. The model side must use
the same calendar window as the cited source question.

Current manifest rows use `model_target = timeseries:*:delta`, a first-to-last
summary over the cited model run. The quarterly `D_model(q)` and
`Delta_model(q)` formulation below is the intended SLOOS-aligned aggregation and
requires the remaining bridge work before promotion out of
`MISSING_DATA_BRIDGE`.

```text
D_model(q)      = mean borrower-demand proxy over months in quarter q and all cited seeds
Delta_model(q)  = D_model(q) - D_model(q - 1)
SLOOS_demand(q) = increased_share(q) - decreased_share(q)
```

Directional interpretation:

| Source signal | Expected model signal |
| --- | --- |
| `SLOOS_demand(q) > 0` | `Delta_model(q) > 0`: stronger borrower demand |
| `SLOOS_demand(q) < 0` | `Delta_model(q) < 0`: weaker borrower demand |
| near zero | no material directional claim unless a deadband is documented |

Before a demand row can move out of `MISSING_DATA_BRIDGE`, the source manifest
must record the exact NBP release, table or chart identifier, whether the
question is realized or expected, segment, sign convention, source deadband if
any, model aggregation window, empirical value, tolerance or directional
criterion, and the model run metadata. Demand rows must not be used as evidence
of bank-side tightening.

### Seed-Count Policy

Empirical validation snapshots report Monte Carlo model values using the
`seed_count`, horizon, commit, branch, and statistic recorded in
`model-run-manifest.tsv`. Paper-facing credit-supply claims must cite that
metadata explicitly.

| Use | Minimum seed count | Interpretation |
| --- | ---: | --- |
| Operational snapshot or smoke evidence | 5 | Suitable for keeping validation rows visible and reproducible. Not enough for paper-facing credit-supply claims. |
| Mechanism diagnostics | 10 | Suitable for inspecting direction and decomposition of credit-supply channels. |
| Paper-facing credit-stock growth, approval-proxy, or NPL-path claims | 30 | Minimum for cited baseline figures over the current 60-month horizon. |
| Tail-risk, bank-failure probability, or stress-frequency claims | 100+ | Required before interpreting low-frequency events probabilistically. |

No empirical validation row should invent a tolerance solely from this seed
policy. Tolerances require a concrete source vintage, transformation, and
reviewable comparator value.

Firm-size distribution and sectoral output now have direct output surfaces.
Use terminal `_firms.tsv` fields for living-firm-only terminal size shares and
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
