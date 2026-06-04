# Institutional Sector Equations

This document consolidates the implemented institutional-sector model into a
publication-facing equation section. It covers central government, social
funds, local government, NBP monetary operations, external-sector/BOP dynamics,
insurance, NBFI/TFI, and quasi-fiscal BGK/PFR-style vehicles.

It describes current executable behavior. It does not introduce new fiscal,
monetary, external, or non-bank financial rules and does not replace the
implementation anchors listed below.

## Source Anchors

| Source | Role |
| --- | --- |
| [Model specification](model-specification.md#reviewer-reading-path) | Canonical reviewer path and model overview. |
| [Model notation and state vector](model-notation-and-state-vector.md) | Public, NBP, external, social-fund, insurance, NBFI, and quasi-fiscal state notation. |
| [Monthly transition function](monthly-transition-function.md) | `X_t -> X_tau` timing, same-month economics, flow emission, SFC validation, and next-pre boundary. |
| [Banking and financial-sector equations](banking-and-financial-sector-equations.md) | Commercial-bank, financial-stability, and bank-facing financial-sector interface equations. |
| [Fiscal, monetary, bond-market, and external rules](behavioral-equations-and-decision-rules.md#fiscal-monetary-bond-market-and-external-rules) and [insurance, NBFI, quasi-fiscal, and JST rules](behavioral-equations-and-decision-rules.md#insurance-nbfi-quasi-fiscal-and-jst-rules) | Implementation-oriented institutional-sector rule catalogs. |
| [SFC matrix evidence](sfc-matrix-evidence.md) | Accounting matrix evidence, generated BSM/TFM artifacts, mechanism semantics, and stock-flow reconciliation. |
| [Calibration register](calibration-register.md) and [data bridge](data-bridge-national-financial-accounts.md) | Parameter provenance, official source bridge, empirical transformation rules, and visible calibration gaps. |
| [`engine/markets/FiscalBudget.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/markets/FiscalBudget.scala) and [`engine/markets/FiscalRules.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/markets/FiscalRules.scala) | Central-government budget, debt metric, public capital, spending constraints, and fiscal-rule diagnostics. |
| [`agents/SocialSecurity.scala`](../src/main/scala/com/boombustgroup/amorfati/agents/SocialSecurity.scala), [`agents/EarmarkedFunds.scala`](../src/main/scala/com/boombustgroup/amorfati/agents/EarmarkedFunds.scala), and [`agents/Jst.scala`](../src/main/scala/com/boombustgroup/amorfati/agents/Jst.scala) | ZUS/FUS, NFZ, PPK, FP/PFRON/FGSP, demographics, and local-government fiscal aggregates. |
| [`agents/Nbp.scala`](../src/main/scala/com/boombustgroup/amorfati/agents/Nbp.scala), [`engine/economics/OpenEconEconomics.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/economics/OpenEconEconomics.scala), and [`engine/markets/OpenEconomy.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/markets/OpenEconomy.scala) | NBP policy rule, sovereign yield, QE request, FX intervention, BOP, exchange-rate, NFA, and external-trade dynamics. |
| [`agents/Insurance.scala`](../src/main/scala/com/boombustgroup/amorfati/agents/Insurance.scala), [`agents/Nbfi.scala`](../src/main/scala/com/boombustgroup/amorfati/agents/Nbfi.scala), and [`agents/QuasiFiscal.scala`](../src/main/scala/com/boombustgroup/amorfati/agents/QuasiFiscal.scala) | Insurance reserves and flows, TFI/NBFI assets and credit, and quasi-fiscal bonds/lending. |
| [`engine/flows/*`](../src/main/scala/com/boombustgroup/amorfati/engine/flows) | Runtime flow emitters for government, social funds, NBP/banking plumbing, external sector, insurance, NBFI, JST, and quasi-fiscal mechanisms. |
| [`montecarlo/McTimeseriesSchema.scala`](../src/main/scala/com/boombustgroup/amorfati/montecarlo/McTimeseriesSchema.scala) | Public output columns for fiscal, monetary, external, insurance, NBFI, JST, and quasi-fiscal surfaces. |

## Institutional State And Ownership Boundary

The institutional model uses both behavioral/state memory and ledger-owned
financial stocks. The main world-state families are:

```text
W^{gov}_t   central-government fiscal state
W^{nbp}_t   NBP policy state, QE regime memory, FX-operation memory
W^{bop}_t   balance-of-payments and NFA macro memory
W^{fx}_t    exchange rate, exports, imports, trade-balance memory
W^{soc}_t   ZUS, NFZ, PPK, JST, demographics, FP/PFRON/FGSP state
W^{fin}_t   insurance, NBFI/TFI, quasi-fiscal, bond/equity market memory
W^{ext}_t   GVC, immigration, remittance, and tourism state
```

Ledger-owned institutional balances are stored in:

```text
L^{G}_t      government bond issuer stock
L^{ROW}_t    foreign government-bond, foreign-asset, and equity holdings
L^{NBP}_t    NBP government-bond holdings, foreign assets, reserve liability
L^{INS}_t    insurance reserves, bond holdings, equity holdings
L^{FUNDS}_t  ZUS/NFZ/PPK/FP/PFRON/FGSP/JST cash, TFI/NBFI, quasi-fiscal balances
```

The ownership boundary matters:

- `GovState.cumulativeDebt` is the domestic fiscal-rule debt metric. The
  holder-resolved tradable government-bond stock is ledger-owned.
- `Jst.State.debt` is a local-government cumulative fiscal metric. JST deposits
  are ledger-owned; JST debt is not yet holder-resolved as a tradable
  instrument.
- `OpenEconomy.BopState` is BOP/IIP macro memory. NBP FX reserve ownership is
  ledger-owned in `L^{NBP}`.
- `Nbp.qeCumulative` tracks QE-regime purchases, not total NBP government-bond
  holdings.
- Insurance and TFI corporate-bond holdings are settled by the corporate-bond
  ledger path. Insurance/NBFI receive opening views for income and return
  closing non-corporate-bond balances.
- Quasi-fiscal bonds and subsidized loan stocks are ledger-owned fund balances;
  ESA 2010 debt is a diagnostic aggregation over central-government and
  quasi-fiscal stocks.

## Central Government Budget

Central-government budget revenue uses the fiscal net NBP remittance:

```text
NbpBondIncome_tau =
  NbpGovBondHoldings_t * BondYield_tau / 12

NbpFiscalRemittance_tau =
  NbpBondIncome_tau
  - ReserveInterest_tau
  - StandingFacilityIncome_tau

TaxRevenue_tau =
  CIT_tau + VAT_tau + NbpFiscalRemittance_tau
  + Excise_tau + Customs_tau

TotalRevenue_tau = TaxRevenue_tau + GovDividendRevenue_tau
```

Government purchases are either supplied by the fiscal-demand stage or fall
back to price-indexed base spending:

```text
GovPurchasesRaw_tau =
  GovPurchasesActual_tau                       if supplied and positive
  GovBaseSpending * PriceLevel_tau             otherwise

GovCurrentSpend_tau =
  GovPurchasesRaw_tau * (1 - govInvestShare)

GovCapitalSpend_tau =
  GovPurchasesRaw_tau * govInvestShare
```

Central-government spending is:

```text
TotalSpend_tau =
  UnemploymentBenefits_tau
  + SocialTransfers_tau
  + GovCurrentSpend_tau
  + GovCapitalSpend_tau
  + DebtService_tau
  + ZUSSubvention_tau
  + NFZSubvention_tau
  + EarmarkedFundSubvention_tau
  + EuCofinancing_tau
```

The monthly deficit and domestic fiscal-rule debt metric are:

```text
Deficit_tau = TotalSpend_tau - TotalRevenue_tau
GovDebtMetric_tau = GovDebtMetric_t + Deficit_tau
```

Public capital is a stock updated by depreciation, domestic public investment,
and EU project capital:

```text
PublicCapital_tau =
  PublicCapital_t * (1 - govDepreciationRate / 12)
  + GovCapitalSpend_tau
  + EuProjectCapital_tau
```

These are accounting identities and stock recursions conditional on the
current-month fiscal inputs. They do not by themselves determine the realism of
the fiscal path.

## Government Purchases And Fiscal Rules

Before the budget update, unconstrained government purchases are computed from
price-indexed base spending and an unemployment stabilizer:

```text
UnempGap_tau = max(UnemploymentRate_tau - NAIRU, 0)

RawGovPurchases_tau =
  GovBaseSpending * max(PriceLevel_tau, 1)
  + GovBaseSpending * UnempGap_tau * govAutoStabMult
```

Fiscal rules constrain the discretionary purchase envelope. The debt and
deficit diagnostics are:

```text
AnnualGDP_tau = 12 * GDP_tau
DebtToGdp_tau = GovDebtMetric_t / AnnualGDP_tau
DeficitToGdp_tau = PrevDeficit_t / GDP_tau
```

The stabilizing expenditure rule (SRW) ceiling is:

```text
SrwCeiling_tau =
  PrevGovSpend_t *
  (1 + MonthlyInflation_tau + MonthlyRealCap
     + max(OutputGap_tau * srwOutputGapSensitivity, 0) / 12)
```

If the SRW ceiling is below raw purchases, spending blends toward the ceiling
at the configured monthly correction speed:

```text
AfterSrw_tau =
  RawGovPurchases_tau * (1 - srwCorrectionSpeed/12)
  + SrwCeiling_tau * (srwCorrectionSpeed/12)
```

The SGP branch applies only when the annualized deficit ratio exceeds the
configured 3 percent limit. It estimates lagged non-purchase spending and
gradually moves discretionary spending toward the revenue-plus-allowable
deficit path:

```text
PrevTotalSpend_t = PrevRevenue_t + PrevDeficit_t
PrevNonPurchaseSpend_t = max(PrevTotalSpend_t - PrevGovSpend_t, 0)
AllowableDeficit_tau = GDP_tau * sgpDeficitLimit
MaxDiscretionarySpend_tau =
  max(PrevRevenue_t + AllowableDeficit_tau - PrevNonPurchaseSpend_t, 0)
```

Debt-threshold branches then apply consolidation paths above the 55 percent
and 60 percent debt/GDP thresholds. The binding rule code is an observability
surface:

```text
0 = no rule
1 = SRW
2 = SGP
3 = 55 percent caution threshold
4 = 60 percent constitutional ceiling
```

These rules are policy heuristics and fiscal-stabilization constraints. The
budget identity above is the accounting object that receives their realized
spending output.

## Social Funds And Local Government

The payroll base for social funds is computed from employed household
contracts:

```text
Payroll_tau =
  (Employed_tau, GrossWages_tau,
   ZUSContrib_tau, NFZContrib_tau, PPKContrib_tau,
   FPContrib_tau, FGSPContrib_tau)
```

ZUS/FUS flows are:

```text
ZUSContrib_tau = PayrollZUS_tau
PensionPayments_tau = Retirees_tau * zusBasePension
ZUSSubvention_tau =
  max(PensionPayments_tau - ZUSContrib_tau, 0)
```

NFZ flows are:

```text
NFZContrib_tau = PayrollNFZ_tau

NFZSpending_tau =
  WorkingAgePopulation_tau * nfzPerCapitaCost
  + Retirees_tau * nfzPerCapitaCost * nfzAgingElasticity

NFZSubvention_tau =
  max(NFZSpending_tau - NFZContrib_tau, 0)
```

PPK contributions are payroll contributions. PPK government-bond purchases are
a configured allocation of those contributions:

```text
PPKContrib_tau = PayrollPPK_tau
PPKBondPurchase_tau = PPKContrib_tau * ppkBondAlloc
```

Earmarked funds use dedicated contribution and spending identities:

```text
FPContrib_tau = PayrollFP_tau
FPSpending_tau =
  UnemploymentBenefits_tau + Employed_tau * fpAlmpSpendPerWorker
FPSubvention_tau = max(FPSpending_tau - FPContrib_tau, 0)

PFRONContrib_tau = pfronMonthlyRevenue
PFRONSpending_tau = pfronMonthlySpending
PFRONSubvention_tau = max(PFRONSpending_tau - PFRONContrib_tau, 0)

FGSPContrib_tau = PayrollFGSP_tau
FGSPSpending_tau =
  BankruptFirms_tau * AvgWorkersPerBankruptFirm_tau * fgspPayoutPerWorker
FGSPSubvention_tau = max(FGSPSpending_tau - FGSPContrib_tau, 0)
```

Local-government own revenue is:

```text
JstOwnRevenue_tau =
  PitRevenue_tau * jstPitShare
  + CentralCitRevenue_tau * jstCitShare
  + Firms_tau * jstPropertyTax / 12

JstGovTransfers_tau =
  GDP_tau * jstSubventionShare / 12
  + GDP_tau * jstDotacjeShare / 12

JstTotalRevenue_tau =
  JstOwnRevenue_tau + JstGovTransfers_tau
```

If explicit PIT revenue is unavailable, the JST PIT share falls back to a wage
income proxy. JST spending, deficit, deposit change, and debt metric are:

```text
JstSpending_tau = JstTotalRevenue_tau * jstSpendingMultiplier
JstDeficit_tau = JstSpending_tau - JstTotalRevenue_tau
JstDepositChange_tau = JstTotalRevenue_tau - JstSpending_tau
JstDebtMetric_tau = JstDebtMetric_t + JstDeficit_tau
```

In runtime flow evidence, `JstRevenue` denotes the own-revenue mechanism and
`JstGovSubvention` denotes central-budget transfers. The public `JstRevenue`
CSV column reports `JstTotalRevenue_tau` from `Jst.State.revenue`.

Social-fund cash balances and JST deposits are ledger-owned. ZUS, NFZ,
earmarked-fund, PPK, and JST state objects are monthly flow and fiscal metric
surfaces.

## NBP Policy, Sovereign Yield, QE, And FX

The NBP reference rate follows a smoothed Taylor-type rule. Policy inflation
combines realized and expected inflation:

```text
PolicyInflation_tau =
  ExpectedInflationWeight * ExpectedInflation_t
  + (1 - ExpectedInflationWeight) * Inflation_tau
```

The raw policy-rate target is:

```text
TaylorTarget_tau =
  NeutralRate
  + taylorAlpha * (PolicyInflation_tau - TargetInflation)
  - taylorDelta * OutputGap_tau
  + taylorBeta * ExchangeRateChange_tau
```

The implemented rate then applies inertia, a maximum monthly move, and the
configured floor/ceiling:

```text
SmoothedRate_tau =
  ReferenceRate_t * taylorInertia
  + TaylorTarget_tau * (1 - taylorInertia)

ReferenceRate_tau =
  clamp(SmoothedRate_tau, rateFloor, rateCeiling, maxMonthlyChange)
```

The sovereign yield is:

```text
BondYield_tau =
  max(ReferenceRate_tau + TermPremium, BundYield + TermPremium)
  + FiscalRisk(DebtToGdp_tau)
  - QeCompression(NbpQeCumulative_t / AnnualGDP_tau)
  - ForeignDemandDiscount(if NFA_t > 0)
  + CredibilityPremium_tau
```

The weighted coupon on government debt is a rolling weighted-average-maturity
rule:

```text
FreshShare_tau =
  min(1 / govAvgMaturityMonths
      + max(Deficit_t, 0) / GovBondOutstanding_t,
      1)

WeightedCoupon_tau =
  WeightedCoupon_t * (1 - FreshShare_tau)
  + BondYield_tau * FreshShare_tau
```

Government debt service is computed from ledger-owned government-bond
outstanding and the updated weighted coupon, with a protective cap relative to
same-month GDP:

```text
DebtService_tau =
  min(GovBondOutstanding_t * WeightedCoupon_tau / 12,
      GDP_tau * maxDebtServiceGdpShare)
```

QE is a policy request, not a direct stock mutation. QE activates when the
reference rate is near the lower bound and realized or expected inflation is
materially below target. The requested purchase is bounded by the GDP-share
ceiling, bank-held bond supply, and QE pace.

Current executable behavior passes the same-month GDP surface into the QE cap
helper. #728 tracks whether this should be changed to the annualized GDP basis
used by bond-market ratios. Until that follow-up lands, the implemented request
is:

```text
QeRequest_tau =
  min(max(qeMaxGdpShare * GDP_tau - NbpGovBondHoldings_t, 0),
      BankGovBondHoldings_t,
      QePace_tau)
```

Actual QE settlement occurs in the government-bond waterfall. This keeps the
asset transfer exact: bonds sold by banks equal bonds acquired by NBP.

FX intervention is an NBP reserve operation. If the exchange rate leaves the
configured band around the base exchange rate, NBP buys or sells EUR:

```text
ExchangeRateDeviation_t =
  ExchangeRate_t / BaseExchangeRate - 1

FxTrade_tau = 0
  if abs(ExchangeRateDeviation_t) <= fxBand

FxTrade_tau = signed bounded reserve trade
  otherwise
```

EUR purchases increase NBP foreign assets and inject PLN reserves into banks.
EUR sales reduce foreign assets and drain bank reserves. The same operation
also contributes an exchange-rate shock term to the external-sector update.

## External Sector And BOP

Exports are either supplied by the GVC module or computed from an opening
export base, foreign GDP growth, real exchange-rate competitiveness, and an
automation-driven unit-labor-cost effect:

```text
Exports_tau =
  GvcExports_tau
  if supplied

Exports_tau =
  exportBase * RealExchangeRateEffect_tau
  * ForeignGdpGrowthFactor_tau
  * AutomationUlcEffect_tau
  otherwise
```

Imports include household consumption imports, technology and investment
imports, imported intermediates, and tourism imports:

```text
Imports_tau =
  HouseholdImportCons_tau
  + TechnologyAndInvestmentImports_tau
  + ImportedIntermediates_tau
  + TourismImport_tau

TradeBalance_tau =
  Exports_tau + TourismExport_tau - Imports_tau
```

When GVC intermediate imports are not supplied, sectoral intermediate imports
are:

```text
ImportedIntermediates_{s,tau} =
  RealSectorOutput_{s,tau}
  * importContent_s
  * NominalExchangeRateEffect_tau
```

The current account before firm/equity owner adjustments is:

```text
PrimaryIncome_tau = NFA_t * nfaReturnRate / 12
SecondaryIncome_tau = EUFunds_tau - RemittanceOutflow_tau + DiasporaInflow_tau
CurrentAccount0_tau =
  TradeBalance_tau + PrimaryIncome_tau + SecondaryIncome_tau
```

The exported BOP then subtracts foreign dividend outflows and FDI-related
profit shifting/repatriation:

```text
CurrentAccount_tau =
  CurrentAccount0_tau
  - ForeignDividendOutflow_tau
  - FdiProfitShifting_tau
  - FdiRepatriation_tau
```

Profit shifting is also booked into imports/trade balance as an imported
service. The document and output columns therefore treat `FdiGrossOutflow` as
diagnostic composition, not the direct current-account subtraction term.

The capital account combines FDI, portfolio flows, carry-trade flows, and
capital-flight outflows:

```text
FDI_tau =
  fdiBase
  * AutomationBoost_tau
  * NegativeNfaDampening_tau

PortfolioFlows_tau =
  GDP_tau
  * (RateDifferential_tau + NfaRiskPremium_tau)
  * portfolioSensitivity

CapitalAccount_tau =
  FDI_tau + PortfolioFlows_tau
  + CarryTradeFlow_tau
  - CapitalFlightOutflow_tau
```

The exchange-rate update responds to the BOP ratio, negative-NFA risk, FX
intervention shock, and PPP drift:

```text
ExchangeRateShock_tau =
  exRateAdjSpeed * (-(CurrentAccount0_tau + CapitalAccount_tau) / GDP_tau
                    + NfaRisk_tau)
  + FxInterventionShock_tau
  + PppDrift_tau

ExchangeRate_tau =
  clamp(ExchangeRate_t * (1 + ExchangeRateShock_tau),
        erFloor,
        erCeiling)
```

Open-economy NFA is first updated by the pre-adjustment current account plus a
partial exchange-rate valuation effect on foreign assets:

```text
ValuationEffect_tau =
  ForeignAssets_t * ExchangeRateChange_tau * valuationPassThrough

NFA0_tau = NFA_t + CurrentAccount0_tau + ValuationEffect_tau
```

The final exported BOP position then applies foreign-dividend and FDI-owner
outflows:

```text
NFA_tau =
  NFA0_tau
  - ForeignDividendOutflow_tau
  - FdiProfitShifting_tau
  - FdiRepatriation_tau
```

`BopState` is macro external-position memory. It is not the holder-resolved
ledger. Trade, FDI, portfolio, carry-trade, EU funds, diaspora remittance,
tourism, and capital-flight flows are emitted through runtime mechanisms and
validated through the SFC/ledger layer where supported.

## Insurance

Insurance premiums are proportional to the employed wage bill:

```text
LifePremium_tau =
  Employed_tau * Wage_tau * lifePremiumRate

NonLifePremium_tau =
  Employed_tau * Wage_tau * nonLifePremiumRate
```

Claims are:

```text
LifeClaims_tau =
  LifePremium_tau * lifeLossRatio

NonLifeClaims_tau =
  NonLifePremium_tau * nonLifeLossRatio
  * (1 + max(UnemploymentRate_tau - nonLifeUnempThreshold, 0)
       * nonLifeUnempSensitivity)
```

Investment income is:

```text
InsuranceInvestmentIncome_tau =
  InsGovBondHoldings_t * BondYield_tau / 12
  + InsCorpBondHoldings_t * CorpBondYield_tau / 12
  + InsEquityHoldings_t * EquityReturn_tau
  - InsCorpBondDefaultLoss_tau
```

Reserve updates split investment income by the opening life/non-life reserve
share:

```text
LifeReserves_tau =
  LifeReserves_t
  + LifePremium_tau
  - LifeClaims_tau
  + InvestmentIncome_tau * LifeReserveShare_t

NonLifeReserves_tau =
  NonLifeReserves_t
  + NonLifePremium_tau
  - NonLifeClaims_tau
  + InvestmentIncome_tau * (1 - LifeReserveShare_t)
```

Government-bond and equity holdings rebalance gradually toward configured
target shares of total insurance assets. Corporate-bond holdings are not
updated by `Insurance.step`; they are ledger-owned and settled by the
corporate-bond market.

## NBFI And TFI

TFI net inflow is a wage-bill flow modulated by the excess return of a
government-bond, corporate-bond, and equity fund portfolio relative to bank
deposits:

```text
BaseTfiInflow_tau =
  Employed_tau * Wage_tau * tfiInflowRate

FundReturn_tau =
  GovBondYield_tau * tfiGovBondShare
  + EquityReturnAnnualized_tau * tfiEquityShare
  + GovBondYield_tau * tfiCorpBondShare

TfiNetInflow_tau =
  BaseTfiInflow_tau
  * (1 + clamp(FundReturn_tau - DepositRate_tau, cap)
       * excessReturnSensitivity)
```

The TFI inflow signal currently proxies the corporate-bond expected-return leg
with `GovBondYield_tau`, matching `Nbfi.tfiInflow`. Realized TFI investment
income below uses the ledger-owned corporate-bond stock and `CorpBondYield_tau`.

TFI AUM and allocations are:

```text
TfiInvestmentIncome_tau =
  TfiGovBondHoldings_t * GovBondYield_tau / 12
  + TfiCorpBondHoldings_t * CorpBondYield_tau / 12
  + TfiEquityHoldings_t * EquityReturn_tau
  - TfiCorpBondDefaultLoss_tau

TfiAum_tau =
  max(TfiAum_t + TfiNetInflow_tau + TfiInvestmentIncome_tau, 0)
```

TFI government-bond and equity holdings rebalance toward target shares of AUM.
Corporate-bond holdings are ledger-owned by the corporate-bond market. TFI
inflow is exposed as a banking deposit-drain channel:

```text
NbfiDepositDrain_tau = -TfiNetInflow_tau
```

NBFI credit uses bank stress as a counter-cyclical origination signal:

```text
BankTightness_tau =
  clamp((BankNplRatio_tau - 0.03) / 0.03, 0, 1)

NbfiOrigination_tau =
  NbfiLoanStock_t * creditBaseRate
  * (1 + countercyclical * BankTightness_tau)

NbfiRepayment_tau =
  NbfiLoanStock_t / creditMaturity

NbfiDefault_tau =
  NbfiLoanStock_t * defaultBase
  * (1 + defaultUnempSensitivity
       * max(UnemploymentRate_tau - 0.05, 0))

NbfiLoanStock_tau =
  max(NbfiLoanStock_t
      + NbfiOrigination_tau
      - NbfiRepayment_tau
      - NbfiDefault_tau,
      0)
```

`NbfiNetStockFlow` is `NbfiOrigination - NbfiRepayment - NbfiDefaults`.
`NbfiDepositDrainToAum` diagnoses fund-flow pressure relative to AUM; it is not
a direct term in the NBFI loan-stock identity.

## Quasi-Fiscal BGK/PFR-Style Vehicles

Quasi-fiscal vehicles are consolidated BGK/PFR-style public financial vehicles.
They issue quasi-fiscal bonds and make subsidized loans outside the central
government debt metric, while still contributing to ESA 2010 debt diagnostics.

Monthly issuance is a share of government capital programs:

```text
QfIssuance_tau =
  max((GovCapitalSpend_tau + EuProjectCapital_tau)
      * qfIssuanceShare,
      0)
```

Bond amortization and absorption are:

```text
QfBondAmortization_tau =
  QfBondsOutstanding_t / qfAvgMaturityMonths

QfNbpAbsorption_tau =
  QfIssuance_tau * qfNbpAbsorptionShare
  if NBP QE active
  else 0

QfBankBondIssuance_tau =
  QfIssuance_tau - QfNbpAbsorption_tau
```

Amortization is split across bank and NBP holders according to opening holder
shares. The outstanding stock identity is:

```text
QfBondsOutstanding_tau =
  max(QfBondsOutstanding_t
      + QfIssuance_tau
      - QfBondAmortization_tau,
      0)

QfBondsOutstanding_tau =
  QfBankHoldings_tau + QfNbpHoldings_tau
```

Subsidized lending is:

```text
QfLending_tau =
  QfIssuance_tau * qfLendingShare

QfLoanRepayment_tau =
  QfLoanPortfolio_t / qfLoanMaturityMonths

QfLoanPortfolio_tau =
  max(QfLoanPortfolio_t
      + QfLending_tau
      - QfLoanRepayment_tau,
      0)
```

Quasi-fiscal lending has two runtime evidence legs: the quasi-fiscal loan
portfolio movement and the matching bank-deposit creation/destruction side of
the credit channel.

ESA 2010 debt is:

```text
Esa2010Debt_tau =
  GovDebtMetric_tau + QfBondsOutstanding_tau
```

This is a diagnostic general-government debt bridge, not a separate ledger
instrument.

## Accounting And SFC Mapping

Institutional behavior enters the runtime ledger through typed flow mechanisms:

| Surface | Main flow/SFC interpretation |
| --- | --- |
| taxes | firm/household/banking/equity revenue to Treasury, plus VAT/excise/customs direct Treasury revenue |
| government purchases and transfers | Treasury cash to firms/households/infrastructure |
| debt service | Treasury cash to holder-resolved government-bond owners |
| government bonds and QE | holder-resolved government-bond stock transfers among banks, foreign sector, NBP, insurance, PPK, and TFI |
| ZUS/NFZ/earmarked funds | contributions, benefits/spending, and government subventions through fund cash rows |
| JST | local-government revenue, spending, subvention, deposits, and debt metric |
| NBP reserve/standing-facility/FX plumbing | reserve liability and settlement flows between NBP and banks |
| trade/tourism/remittances/EU funds | foreign-sector cash transfers against domestic firms, households, or government |
| FDI/portfolio/carry/capital flight | foreign capital-account cash transfers with signed direction where applicable |
| insurance | premiums, claims, investment income/loss, reserves, and portfolio holdings |
| NBFI/TFI | fund-unit/AUM flows, non-bank loan origination/repayment/default, and deposit-drain diagnostics |
| quasi-fiscal vehicles | quasi-fiscal bond issuance/amortization/absorption and subsidized loan/deposit legs |

The SFC semantic projection separately validates key public and non-bank
identities, including:

```text
JstDepositsDelta =
  JstOwnRevenue + JstGovSubvention - JstSpending

QfBondStockDelta =
  QuasiFiscalBankBondIssuance
  + QuasiFiscalNbpAbsorption
  - QuasiFiscalBankBondAmortization
  - QuasiFiscalNbpBondAmortization

QfLoanPortfolioDelta =
  QfLending - QfRepayment

NbfiLoanStockDelta =
  NbfiOrigination - NbfiRepayment - NbfiDefault
```

`QuasiFiscalBankBondIssuance` and `QuasiFiscalBankBondAmortization` denote the
commercial-bank holder legs emitted as `FlowMechanism.QuasiFiscalBondIssuance`
and `FlowMechanism.QuasiFiscalBondAmortization`. The SFC projection aggregates
those bank legs with the separately addressable NBP absorption/amortization
legs when it computes total quasi-fiscal issuance and amortization.

Public and institutional outputs should be interpreted together with the
generated SFC artifacts, especially
[flow-mechanism-semantics.md](sfc-matrix-artifacts/flow-mechanism-semantics.md)
and
[stock-flow-reconciliation.md](sfc-matrix-artifacts/stock-flow-reconciliation.md).

## Output And Diagnostic Surfaces

Representative output columns include:

| Surface | Columns |
| --- | --- |
| Fiscal stance | `GovCurrentSpend`, `GovCapitalSpendDomestic`, `DebtService`, `GovPrimaryDeficitToGdp`, `DebtToGdp`, `DeficitToGdp`, `FiscalRuleBinding`, `GovSpendingCutRatio`, `PublicCapitalStock` |
| Government bonds and NBP | `RefRate`, `BondYield`, `WeightedCoupon`, `BondsOutstanding`, `ForeignBondHoldings`, `NbpBondHoldings`, `QeActive`, `NbpRemittance`, `FxReserves`, `FxInterventionAmt` |
| Social funds and JST | `ZusContributions`, `ZusPensionPayments`, `ZusGovSubvention`, `NfzContributions`, `NfzSpending`, `NfzBalance`, `NfzGovSubvention`, `PpkContributions`, `PpkBondHoldings`, `FpBalance`, `FpContributions`, `PfronBalance`, `FgspBalance`, `FgspSpending`, `JstRevenue`, `JstSpending`, `JstDebt`, `JstDeposits`, `JstDeficit` |
| External sector | `NFA`, `CurrentAccount`, `CurrentAccountToGdp`, `CurrentAccountPrimaryIncome`, `CurrentAccountSecondaryIncome`, `CurrentAccountClosureResidual`, `CapitalAccount`, `CapitalAccountToGdp`, `TradeBalance_OE`, `Exports_OE`, `TotalImports_OE`, `FDI`, `RemittanceOutflow`, `DiasporaRemittanceInflow`, `NetRemittances`, `TourismExport`, `TourismImport`, `NetTourismBalance`, `TourismSeasonalFactor` |
| Insurance | `InsLifeReserves`, `InsNonLifeReserves`, `InsGovBondHoldings`, `InsLifePremium`, `InsNonLifePremium`, `InsLifeClaims`, `InsNonLifeClaims` |
| NBFI/TFI | `NbfiTfiAum`, `NbfiTfiGovBondHoldings`, `NbfiLoanStock`, `NbfiOrigination`, `NbfiRepayment`, `NbfiDefaults`, `NbfiNetStockFlow`, `NbfiOriginationToStock`, `NbfiRepaymentToStock`, `NbfiDefaultsToStock`, `NbfiBankTightness`, `NbfiDepositDrain`, `NbfiDepositDrainToAum`, `NbfiLoansToGdp` |
| Quasi-fiscal | `QfBondsOutstanding`, `QfNbpHoldings`, `QfLoanPortfolio`, `QfIssuance`, `Esa2010DebtToGdp` |

The central-government equation above uses `NbpFiscalRemittance_tau`, the net
transfer to the budget. The current public `NbpRemittance` CSV column is a
legacy gross NBP government-bond-income surface; #729 tracks the output naming
and net/gross split.

## Validation Coverage

| Layer | Coverage |
| --- | --- |
| Unit/config tests | Fiscal, monetary, social-fund, NBP, open-economy, insurance, NBFI, quasi-fiscal, calibration, and parameter-scaling specs. |
| Flow tests | `GovBudgetFlows`, `ZusFlows`, `NfzFlows`, `PpkFlows`, `EarmarkedFlows`, `JstFlows`, `OpenEconFlows`, `InsuranceFlows`, `NbfiFlows`, `QuasiFiscalFlows`, `GovBondFlows`, and composite flow conservation specs. |
| Integration/nightly | Monte Carlo CSV integration, nightly diagnostics, scenario runs, sensitivity/robustness profiles, generated SFC matrix artifacts, and generated-output guard. |
| Accounting | Runtime ledger execution, SFC semantic projection, generated BSM/TFM artifacts, stock-flow reconciliation, and institutional row-to-flow identity checks. |

## Current Limitations

- Central government, NBP, JST, social funds, insurance, NBFI, quasi-fiscal
  vehicles, and rest-of-world are institutional aggregate agents, not
  heterogeneous micro-institution populations.
- `GovState.cumulativeDebt`, `Jst.State.debt`, `BopState`, and
  `Nbp.qeCumulative` are fiscal or macro memory surfaces. They should not be
  read as independent holder-resolved ledger instruments.
- The fiscal-rule implementation is a monthly stabilizing path. It is not a
  full legal budget-process model and does not represent political approval,
  intra-year budget amendments, or cash/accrual reconciliation in detail.
- The NBP rule is a Taylor-type policy heuristic with a QE lower-bound branch
  and FX-intervention band. It is not an estimated reaction function.
- The current QE cap basis and NBP remittance output naming are tracked in
  #728 and #729. Until those follow-ups land, this document describes the
  executable behavior and flags the observable-output ambiguity explicitly.
- External-sector behavior combines structural trade, GVC, remittance,
  tourism, FDI, portfolio, carry-trade, and capital-flight channels. Several
  coefficients remain calibration or scenario targets rather than final
  econometric estimates.
- Insurance and NBFI are aggregate balance-sheet agents. Policyholder,
  beneficiary, fund-investor, leasing-contract, and individual-insurance
  contract heterogeneity is not yet represented.
- Quasi-fiscal vehicles are represented as one consolidated BGK/PFR-style
  sector. Instrument-level program detail, guarantee accounting, and ESA
  reclassification uncertainty remain outside the current executable model.
- Some institutional stocks are diagnostic-only or unsupported as transferable
  ledger-owned instruments. Publication use should cite the ownership boundary
  above and the generated SFC artifacts rather than inferring unsupported
  counterparties.
