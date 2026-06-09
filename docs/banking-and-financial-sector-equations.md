# Banking And Financial-Sector Equations

This document consolidates Amor Fati's implemented banking and financial-sector
rules into a publication-facing model section. It describes the executable
SFC-ABM contract. It does not introduce new bank behavior, new calibration
targets, or new failure semantics.

The section complements [institutional-sector-equations.md](institutional-sector-equations.md):
that document owns central government, NBP, external sector, insurance, NBFI,
quasi-fiscal, JST, and social-fund details. This document makes the commercial
banking system and its financial-sector interfaces readable as one coherent
financial-stability block.

## Source Anchors

| Source | Role |
| --- | --- |
| [Model specification](model-specification.md#reviewer-reading-path) | Canonical reviewer path and model overview. |
| [Model notation and state vector](model-notation-and-state-vector.md#banks) | Bank state, ledger stock, rate, ratio, and flow notation. |
| [Monthly transition function](monthly-transition-function.md) | Month-step timing and deterministic transition contract. |
| [Behavioral rule catalog](behavioral-equations-and-decision-rules.md#banking-rules) | Implementation-oriented banking rules and output-column map. |
| [Institutional sector equations](institutional-sector-equations.md) | NBP, insurance, NBFI/TFI, quasi-fiscal, public-sector, and external-sector equations. |
| [ADR 0001: Bank Capital SFC Semantics](adr/0001-bank-capital-sfc-semantics.md) | Bank-capital ownership, SFC, bail-in, and capital-destruction semantics. |
| [Bank balance-sheet benchmark](bank-balance-sheet-benchmark.md) | Opening bank balance-sheet guardrails and calibration-warning bands. |
| [Engine invariants and semantics](engine-invariants-and-semantics.md) | Failure semantics, hard invariants, warning surfaces, and known limitations. |
| [`agents/Banking.scala`](../src/main/scala/com/boombustgroup/amorfati/agents/Banking.scala) and [`agents/banking/*`](../src/main/scala/com/boombustgroup/amorfati/agents/banking) | Bank domain facade, credit approval, regulatory metrics, bond portfolio, capital waterfall, and resolution helpers. |
| [`engine/economics/BankingEconomics.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/economics/BankingEconomics.scala) and [`engine/economics/banking/*`](../src/main/scala/com/boombustgroup/amorfati/engine/economics/banking) | Monthly banking-stage runner, bond waterfall, interbank settlement, failure pipeline, reconciliation, and ledger close. |
| [`engine/diagnostics/banking/BankDiagnostics.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/diagnostics/banking/BankDiagnostics.scala) | Bank capital, failure, resolution, reconciliation, and ECL diagnostics. |
| [`engine/flows/BankingFlows.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/flows/BankingFlows.scala), [`InsuranceFlows.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/flows/InsuranceFlows.scala), [`NbfiFlows.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/flows/NbfiFlows.scala), [`QuasiFiscalFlows.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/flows/QuasiFiscalFlows.scala), [`CorpBondFlows.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/flows/CorpBondFlows.scala), [`GovBondFlows.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/flows/GovBondFlows.scala) | Runtime flow emission and SFC evidence for financial-sector mechanisms. |
| [`montecarlo/McTimeseriesSchema.scala`](../src/main/scala/com/boombustgroup/amorfati/montecarlo/McTimeseriesSchema.scala) | Public output columns for bank, bond, insurance, NBFI, and quasi-fiscal diagnostics. |

## Scope And Timing

Commercial banks are ten banking-sector rows: named bank archetypes plus the
residual Other banks row. A row is a banking-sector balance-sheet archetype, not
a full legal-entity model of one commercial bank.

The banking stage runs near the end of the same-month economics sequence, after
households, firms, household financial aggregation, price/equity, and
open-economy/NBP conditions are known. It then:

1. projects opening ledger-owned bank balances;
2. computes public-finance and housing-bank flows;
3. settles NBP reserve, standing-facility, FX, and interbank channels;
4. settles government-bond and bank corporate-bond holdings;
5. detects failures, applies bail-in, resolves failed rows, and reconciles
   aggregate exactness;
6. emits closing bank state, financial stocks, monetary aggregates, diagnostics,
   and ledger/SFC flow evidence.

Firm and household credit approvals call the product-aware bank-side approval
rules, but the RNG owner is the stage that invokes them: firm approvals are
owned by the firm stage and household consumer-credit approvals by the household
path. The
`BankingEconomics` randomness stream is for stochastic choices executed inside
the banking stage itself, especially health-driven deposit mobility.

## State

For bank row `b`, the operational bank state is:

```text
a^B_{b,t}
```

It contains regulatory/accounting capital, failure status, HTM book yield,
firm-loan NPL diagnostics, consumer NPL diagnostics, loan-maturity buckets, and
IFRS 9 ECL staging.

Ledger-owned financial balances are:

```text
l^B_{b,t} in L_t.banks
```

with principal bank stock families:

| Symbol | Runtime field | Meaning |
| --- | --- | --- |
| `D^B_{b,t}` | `BankBalances.totalDeposits` | customer deposit liability |
| `D^{B,demand}_{b,t}` | `BankBalances.demandDeposit` | demand-deposit liability |
| `D^{B,term}_{b,t}` | `BankBalances.termDeposit` | term-deposit liability |
| `L^{B,F}_{b,t}` | `BankBalances.firmLoan` | firm-loan asset |
| `L^{B,H}_{b,t}` | `BankBalances.consumerLoan` | consumer-loan asset |
| `M^B_{b,t}` | `BankBalances.mortgageLoan` | mortgage-loan asset mirror used by SFC evidence |
| `GB^{AFS}_{b,t}` | `BankBalances.govBondAfs` | available-for-sale government-bond asset |
| `GB^{HTM}_{b,t}` | `BankBalances.govBondHtm` | held-to-maturity government-bond asset |
| `Res^B_{b,t}` | `BankBalances.reserve` | reserve asset at NBP |
| `IB_{b,t}` | `BankBalances.interbankLoan` | net interbank position |
| `CB^B_{b,t}` | `BankBalances.corpBond` | corporate-bond asset |

Bank capital is written:

```text
K^B_{b,t} = BankState.capital
```

`K^B` is persisted operational state and an SFC-validated diagnostic stock. It
is not a holder-resolved ledger equity instrument and does not live in
`LedgerFinancialState.BankBalances`. ADR 0001 is the authoritative ownership
contract for this field.

## Lending Rates And Approval

The household deposit rate is:

```text
r^{dep}_{b,tau} =
  max(r^{NBP}_{tau} - householdDepositSpread, 0)
```

Firm lending rates are bank-specific:

```text
r^{loan}_{b,tau} =
  r^{base}_{tau}
  + baseSpread
  + lendingSpread_b
  + min(NPLRatio_b * nplSpreadFactor, NplSpreadCap)
  + capitalPenalty_b
  + crowdingOutSpread_b
```

where:

```text
effectiveMinCar_b(ccyb_tau) =
  minCar
  + ccyb_tau
  + OSIIBuffer_b
  + P2RAddon_b

managementCAR_b(ccyb_tau) =
  effectiveMinCar_b(ccyb_tau) + creditManagementCarBuffer

capitalPenalty_b =
  max(managementCAR_b(ccyb_tau) - CAR_b, 0)
  * creditCarShortfallPenaltyScale

crowdingOutSpread_b =
  max(govBondYield_tau - r^{base}_{tau} - baseSpread, 0)
  * crowdingOutSensitivity
```

The capital stack used by `Macroprudential.effectiveMinCar` is auditable from
configuration and calibration provenance:

| Component | Runtime symbol | 2026-04-30 baseline status |
| --- | --- | --- |
| Basel/CRR base minimum | `minCar` | 8% minimum capital adequacy ratio. |
| Countercyclical buffer | `ccyb_tau` | Opens at `initialCcyb = 1%`; later months follow the endogenous CCyB build/release rule capped by `ccybMax`. |
| O-SII buffer | `OSIIBuffer_b` | KNF O-SII buffer mapped to the named bank archetype; `Alior` is zero because it is not on the KNF O-SII list, `BPS/Coop` maps cooperative-bank O-SII rows, and `Other banks` carries Handlowy/SGB/residual exposure. |
| Pillar 2 requirement | `P2RAddon_b` | Bank-archetype bridge prior; the public row-level source is still marked as incomplete in the calibration register. |

Failed banks receive a fixed penalty spread and cannot approve new credit.

For a proposed new credit amount `A` in product bucket
`q in {firm, consumer, mortgage}`, approval uses hard regulatory gates and a
stochastic draw. The requested product determines which exposure bucket is
incremented before projected RWA is computed:

```text
FirmLoans'_b(q, A) =
  L^{B,F}_{b,t} + A       if q = firm
  L^{B,F}_{b,t}           otherwise

ConsumerLoans'_b(q, A) =
  L^{B,H}_{b,t} + A       if q = consumer
  L^{B,H}_{b,t}           otherwise

MortgageLoans'_b(q, A) =
  M^B_{b,t} + A           if q = mortgage
  M^B_{b,t}               otherwise

ProjectedRWA_b(q, A) =
  RWA_b(
    firmLoans = FirmLoans'_b(q, A),
    consumerLoans = ConsumerLoans'_b(q, A),
    mortgageLoans = MortgageLoans'_b(q, A),
    corpBondHoldings = CB^B_{b,t},
    interbankAssets = max(IB^B_{b,t}, 0),
    govBondHoldings = GB^B_{b,t},
    reserves = Res^B_{b,t},
    capitalBackstop = max(K^B_{b,t}, 0)
  )

ProjectedCAR_b(q, A) = K^B_{b,t} / ProjectedRWA_b(q, A)

carOk  = ProjectedCAR_b(q, A) >= effectiveMinCar_b(ccyb_tau)
lcrOk  = LCR_b >= lcrMin
nsfrOk = NSFR_b >= nsfrMin
```

Firm credit requests use `q = firm`; household consumer-credit requests use
`q = consumer` after household-side affordability and distress filters have
passed. Mortgage approval is represented in the same product vocabulary even
where current origination is handled by housing-stage aggregate flows.

If all hard gates pass, risk-weighted origination is still throttled by the
distance between projected CAR and the bank's internal management target:

```text
managementCAR_b = effectiveMinCar_b(ccyb_tau) + creditManagementCarBuffer
capitalThrottle_b(q, A) =
  clamp((ProjectedCAR_b(q, A) - effectiveMinCar_b(ccyb_tau)) /
        creditManagementCarBuffer, 0, 1)

approvalP_b(q, A) = capitalThrottle_b(q, A)
```

The proposal is approved when the hard regulatory gates pass and the replay
draw is below `approvalP_b`. A bank at or above `managementCAR_b` has
`approvalP_b = 1`; a bank between the hard floor and the management target
reduces new risk-weighted credit supply smoothly. NPL pressure is not
independently multiplied into the approval probability; it enters through loan
pricing, IFRS 9 / ECL provisioning, and the resulting capital path. Reserve
requirements are not a per-loan approval gate. They are handled through LCR/NSFR,
reserve settlement, standing facilities, and bank P&L.

This approval rule is a regulatory heuristic and behavioral credit-supply
surface, not an SFC identity. The resulting origination, repayment, default,
interest, and rejected-demand quantities are what later enter ledger/SFC and
diagnostic surfaces.

## Regulatory Ratios

Risk-weighted assets use an explicit regulatory perimeter. Default weights are
100 percent for firm and unsecured consumer loans, 35 percent for mortgages, 50
percent for bank-held corporate bonds, 20 percent for positive interbank assets,
and 0 percent for domestic sovereign bonds and NBP reserves. Two floors prevent
economically meaningless zero-RWA CAR for live shells:

```text
WeightedExposureRWA_b =
    firmLoanRiskWeight        * L^{B,F}_{b,t}
  + consumerLoanRiskWeight    * L^{B,CC}_{b,t}
  + mortgageLoanRiskWeight    * L^{B,M}_{b,t}
  + corpBondRiskWeight        * CB^B_{b,t}
  + interbankAssetRiskWeight  * max(IB^B_{b,t}, 0)
  + sovereignRiskWeight       * GB^B_{b,t}
  + reserveRiskWeight         * Res^B_{b,t}

OperationalRiskFloor_b =
  rwaOperationalRiskFloor
  * (L^{B,F}_{b,t}
     + L^{B,CC}_{b,t}
     + L^{B,M}_{b,t}
     + CB^B_{b,t}
     + max(IB^B_{b,t}, 0)
     + GB^B_{b,t}
     + Res^B_{b,t})

CapitalBackstopFloor_b =
  rwaCapitalBackstop * max(K^B_{b,t}, 0)

RWA_b =
  max(WeightedExposureRWA_b,
      OperationalRiskFloor_b,
      CapitalBackstopFloor_b)

CAR_b = K^B_{b,t} / RWA_b
```

The simplified LCR is:

```text
HQLA_b =
  Res^B_{b,t}
  + GB^{AFS}_{b,t}
  + GB^{HTM}_{b,t}

NetCashOutflows_b =
  D^{B,demand}_{b,t} * demandDepositRunoff

LCR_b = HQLA_b / NetCashOutflows_b
```

The simplified NSFR is:

```text
ASF_b =
  K^B_{b,t}
  + 0.95 * D^{B,term}_{b,t}
  + 0.90 * D^{B,demand}_{b,t}

RSF_b =
  0.50 * LoansShort_b
  + 0.65 * LoansMedium_b
  + 0.85 * LoansLong_b
  + 0.05 * GB^B_{b,t}
  + 0.50 * CB^B_{b,t}

NSFR_b = ASF_b / RSF_b
```

Ratios with near-zero denominators return a safe high ratio rather than a
spurious failure.

The aggregate bank NPL ratio used by interbank, pricing, and corporate-bond
feedback is the firm-loan NPL ratio:

```text
NPLRatio =
  FirmNplStock / FirmLoans
```

Consumer loan stock is included in the ECL covered-loan base, but consumer
defaults and consumer NPL are carried separately from ECL Stage 3 migration.
Realized consumer-credit losses enter bank-capital diagnostics through the
consumer-credit loss surfaces. Per-bank and aggregate CAR, NPL, LCR, and NSFR
values are regulatory diagnostic and decision inputs. They are not themselves
monetary flows.

## IFRS 9 ECL Staging

Each bank carries a three-stage ECL stock:

```text
ECL_b = (S1_b, S2_b, S3_b)
```

with allowance:

```text
Allowance_b =
  S1_b * eclRate1
  + S2_b * eclRate2
  + S3_b * eclRate3
```

Opening staging currently assigns the covered firm and consumer loan book to
Stage 1:

```text
ECL_{b,0} = (coveredLoans_b, 0, 0)
```

Monthly macro-driven Stage 1 to Stage 2 migration is:

```text
migrationRate_tau =
  eclMigrationSensitivity
  * max(0, unemployment_tau - referenceUnemployment_tau)
  + eclGdpSensitivity
  * max(0, -gdpGrowthMonthly_tau)
```

clamped to `[0, eclMaxMigration]`.

Stage transitions are:

```text
S1ToS2_b = S1_{b,t} * migrationRate_tau
S2ToS3_b = newDefaults_b
S3Cure_b = S3_{b,t} * eclCureRate

S3_{b,tau} = max(S3_{b,t} + S2ToS3_b - S3Cure_b, 0)
S2_{b,tau} = max(S2_{b,t} + S1ToS2_b - S2ToS3_b + S3Cure_b, 0)
S1_{b,tau} = max(TotalCoveredLoans_{b,tau} - S2_{b,tau} - S3_{b,tau}, 0)
```

In the current runtime, `newDefaults_b` is the bank's new firm-loan NPL flow.
Consumer-credit defaults do not enter `S2ToS3_b`; they are realized separately
as consumer-credit loss terms in the bank-capital waterfall.

The provision change is:

```text
EclProvisionChange_b =
  Allowance_{b,tau} - Allowance_{b,t}
```

A positive provision change reduces bank capital through the bank-capital
waterfall. ECL is an accounting provision channel; realized credit losses remain
separate diagnostics.

## Interbank, NBP Facilities, And Monetary Aggregates

The interbank rate is a corridor rate between the deposit facility and lombard
facility:

```text
creditStress =
  clamp(AggregateNplRatio / stressThreshold, 0, 1)

liquidityRatio =
  clamp(ExcessReserves / RequiredReserves, 0, 1)

interbankRate =
  depositFacilityRate
  + (1 - liquidityRatio)
    * creditStress
    * (lombardRate - depositFacilityRate)
```

Interbank clearing matches banks with reserve surpluses to banks with reserve
deficits. A hoarding factor, driven by aggregate NPL stress, reduces lending
supply. If no matching is possible, interbank loans are cleared while existing
reserves are preserved.

NBP reserve-side settlement combines:

```text
reserveInterest_b
standingFacilityIncome_b
interbankInterest_b
fxPlnInjection_b
```

on bank reserve balances. If a drain would push reserves below zero, the
shortfall is surfaced as an explicit standing-facility backstop and reserves
remain non-negative.

Monetary aggregates are:

```text
M0 = sum_live_b Res^B_b
M1 = sum_live_b D^{B,demand}_b
M2 = M1 + sum_live_b D^{B,term}_b
M3 = M2 + TfiAum + CorpBondsOutstanding
CreditMultiplier = M2 / max(M0, 1 PLN)
```

## Government-Bond And Corporate-Bond Waterfalls

Government-bond issuance or redemption first changes commercial-bank portfolios.
The banking bond waterfall then sells actual available bank-held bonds to
foreign, NBP/QE, PPK, insurance, and TFI buyers in sequence:

```text
BankPrimaryGovBondChange_tau =
  GovBondOutstanding_tau - GovBondOutstanding_t

AvailableBankBonds_tau
  -> foreign purchase
  -> NBP QE purchase
  -> PPK purchase
  -> insurance purchase
  -> TFI purchase
```

The runtime evidence records the per-bank source of each movement through
`GovBondRuntimeMovements`.

Corporate bonds are issuer and holder-resolved ledger stocks. The market step
computes yield, coupon, amortization, default, and issuance:

```text
CorpBondYield_tau =
  max(GovBondYield_tau + creditSpread_tau,
      MinCorpBondYield)

creditSpread_tau =
  min(baseSpread * (1 + NPLRatio_tau * nplSensitivity),
      maxSpread)

Coupon_tau =
  CorpBondHoldings_t * CorpBondYield_tau / 12

Amortization_tau =
  CorpBondsOutstanding_t / maturityMonths

DefaultLoss_tau =
  GrossDefault_tau * (1 - recovery)
```

New issuance is limited by investor appetite and bank CAR headroom. Bank-held
corporate-bond holdings are settled after amortization/default reductions and
new issuance allocation. Bank-held corporate-bond default losses enter the bank
capital waterfall.

## Bank Capital Waterfall

For each bank, ordinary retained income and realized losses update
`BankState.capital`:

```text
grossIncome_b =
  firmLoanInterest_b
  + govBondIncome_b
  - depositInterest_b
  + reserveInterest_b
  + standingFacilityIncome_b
  + interbankInterest_b
  + mortgageInterestIncome_b
  + consumerInterestIncome_b
  + corpBondCoupon_b

losses_b =
  firmNplLoss_b
  + mortgageNplLoss_b
  + consumerNplLoss_b
  + corpBondDefaultLoss_b
  + bfgLevy_b
  + unrealizedBondLoss_b

retainedIncome_b =
  grossIncome_b * profitRetention

K^B_{b,tau,ordinary} =
  K^B_{b,t}
  - losses_b
  + retainedIncome_b
```

The aggregate diagnostic identity is:

```text
DeltaBankCapital =
  RetainedIncome
  - RealizedCreditLoss
  - BfgLevy
  - UnrealizedBondLoss
  - HtmRealizedLoss
  - EclProvisionChange
  - InterbankContagionLoss
  - CapitalDestruction
  + ReconciliationResidual
  + WaterfallResidual
```

where:

```text
RealizedCreditLoss =
  FirmNplLoss
  + MortgageNplLoss
  + ConsumerNplLoss
  + BankHeldCorporateBondDefaultLoss
```

`WaterfallResidual` should remain near zero. A material value means the bank
capital diagnostic surface is missing an explanatory term. `ReconciliationResidual`
is the named exactness patch distributed across live banks after aggregate bank
stocks are reconciled.

`DepositBailInLoss` is carried beside bank-capital diagnostics for resolution
analysis, but it is not an equity-capital P&L term. It is a depositor-side
deposit haircut.

## Failure, Resolution, Bail-In, And Stress Semantics

Failure detection checks active banks after ordinary banking settlement and bond
waterfall. A bank newly fails when any primary trigger applies:

```text
NegativeCapital_b:
  K^B_b < 0

CarBreach_b:
  CAR_b < effectiveMinCar_b(ccyb)
  for 3 consecutive monthly checks

LiquidityBreach_b:
  LCR_b < 0.5 * lcrMin
```

Trigger priority for diagnostics is negative capital, then CAR breach, then
liquidity breach. Failed banks have capital set to zero, which records capital
destruction through the BankCapital SFC identity.

If a primary failure occurs, interbank contagion applies counterparty losses
from failed-bank exposures. A secondary failure check then re-runs on the
post-contagion bank rows.

Bail-in is event-based:

```text
UnprocessedDeposits_b =
  max(D^B_b - BailedInDeposits_b, 0)

Guaranteed_b =
  min(UnprocessedDeposits_b, bfgDepositGuarantee)

Uninsured_b =
  UnprocessedDeposits_b - Guaranteed_b

BailInLoss_b =
  Uninsured_b * bailInDepositHaircut
```

The haircut reduces deposits and is SFC-validated through the BankDeposits
identity, not through bank-equity ownership.

Purchase-and-assumption resolution then transfers failed-bank balance-sheet
rows to the healthiest surviving bank:

```text
absorber =
  live bank with strongest CAR/capital among risk-bearing rows

transferred stocks =
  deposits
  + performing firm loans
  + government bonds
  + consumer loans
  + interbank position
  + corporate-bond holdings
```

The failed bank row remains an explicit inert shell: ordinary lending, P&L, ECL
migration, NPL write-off, and deposit-flow updates are skipped in later months
unless an explicit resolution or reconciliation mechanism touches that row.

If every bank has failed while deposits still need resolution, the model fails
fast. Choosing a failed bank as an absorber would implicitly introduce a bridge
bank recapitalization or nationalization mechanism. Such a mechanism must be
modeled explicitly, with fiscal/SFC flows, before all-failed resolution paths
can be treated as valid.

Normal validation profiles should not produce ordinary bank failures or
all-failed paths. Stress and exploratory diagnostics may exercise these channels
as stress semantics, but the run output must label them as such.

## Insurance, NBFI, TFI, And Quasi-Fiscal Interfaces

Insurance, NBFI/TFI, and quasi-fiscal equations are specified in
[institutional-sector-equations.md](institutional-sector-equations.md). Their
interfaces matter for bank and financial-stability analysis:

| Sector | Banking/financial interface |
| --- | --- |
| Insurance | Premiums and claims move deposits; reserve investment income uses government bonds, corporate bonds, and equity; insurance government-bond purchases participate in the bank bond waterfall. |
| TFI/NBFI | TFI fund inflows drain bank deposits; TFI government-bond demand participates in the bank bond waterfall; NBFI credit origination rises with bank NPL tightness. |
| Corporate bonds | Firm issuance can substitute for bank loans; bank holdings enter the explicit RWA perimeter with the configured corporate-bond risk weight; bank-held corporate-bond default loss hits bank capital. |
| Quasi-fiscal BGK/PFR | Quasi-fiscal bank-held bond issuance and amortization affect bank bond holdings; subsidized lending has a matching bank-deposit creation/destruction leg. |
| NBP | Reserve remuneration, standing facility, QE purchases, FX intervention, and reserve-settlement backstops connect the central bank to commercial-bank reserves and income. |

NBFI credit tightness is:

```text
BankTightness_tau =
  clamp((BankNplRatio_tau - 0.03) / 0.03, 0, 1)
```

and non-bank credit origination is:

```text
NbfiOrigination_tau =
  NbfiLoanStock_t * creditBaseRate
  * (1 + countercyclical * BankTightness_tau)
```

Quasi-fiscal issuance and absorption are:

```text
QfIssuance_tau =
  max((GovCapitalSpend_tau + EuProjectCapital_tau)
      * qfIssuanceShare,
      0)

QfNbpAbsorption_tau =
  QfIssuance_tau * qfNbpAbsorptionShare
  if NBP QE active
  else 0

QfBankBondIssuance_tau =
  QfIssuance_tau - QfNbpAbsorption_tau
```

These financial-sector satellites are aggregate institutional agents. They
affect bank deposits, securities holdings, credit aggregates, capital losses,
and SFC identities, but they are not heterogeneous commercial banks.

## SFC And Ledger Mapping

Hard accounting and SFC identities include:

| Surface | Meaning |
| --- | --- |
| BankCapital | Retained income, realized losses, provisions, valuation losses, interbank contagion, and capital destruction explain `BankState.capital` movement. |
| BankDeposits | Household, firm, JST, insurance, TFI/NBFI, quasi-fiscal, bail-in, and resolution-side deposit movements reconcile deposit liabilities. |
| InterbankNetting | Interbank position movements and contagion exposure evidence remain explicit. |
| BondClearing | Government, corporate, quasi-fiscal, NBP, PPK, insurance, TFI, foreign, and bank holder movements reconcile supported security stocks. |
| Insurance reserves | Premiums, claims, investment income, and reserve changes reconcile insurance reserve balances. |
| NbfiCredit | NBFI origination, repayment, default, and stock movement reconcile the non-bank credit book. |
| QuasiFiscal | Quasi-fiscal bonds, holder split, subsidized lending, and deposit-side credit legs reconcile public financial vehicles. |

Regulatory ratios, pricing spreads, approval probabilities, ECL migration
heuristics, and failure triggers are model rules. They become SFC-relevant only
through the realized flows or stock changes they generate.

## Output Columns And Diagnostics

Main Monte Carlo output families:

| Family | Representative columns |
| --- | --- |
| Monetary aggregates | `M0`, `M1`, `M2`, `M3`, `CreditMultiplier` |
| Rates | `InterbankRate`, `WIBOR_1M`, `WIBOR_3M`, `WIBOR_6M` |
| Regulatory ratios | `MinBankCAR`, `MaxBankNPL`, `MinBankLCR`, `MinBankNSFR` |
| Failure and resolution | `BankFailures`, `BankFailure_*`, `BankResolution_*`, `BailInLoss`, `BfgLevyTotal` |
| Capital waterfall | `BankCapital_*`, `BankCreditLoss_*`, `BankReconciliation_*` |
| ECL | `BankEcl_*`, `EclStage1`, `EclStage2`, `EclStage3` |
| Corporate bonds | `CorpBondOutstanding`, `CorpBondYield`, `CorpBondIssuance`, `CorpBondSpread`, `BankCorpBondHoldings`, `CorpBondAbsorptionRate` |
| Insurance | `InsLifeReserves`, `InsNonLifeReserves`, `InsLifePremium`, `InsNonLifePremium`, `InsLifeClaims`, `InsNonLifeClaims` |
| NBFI/TFI | `NbfiTfiAum`, `NbfiLoanStock`, `NbfiOrigination`, `NbfiRepayment`, `NbfiDefaults`, `NbfiNetStockFlow`, `NbfiBankTightness`, `NbfiDepositDrain` |
| Quasi-fiscal | `QfBondsOutstanding`, `QfNbpHoldings`, `QfLoanPortfolio`, `QfIssuance` |

Focused diagnostics:

| Diagnostic | Role |
| --- | --- |
| [Bank balance-sheet benchmark](bank-balance-sheet-benchmark.md) | Opening capital, credit, deposit, liquidity, ECL, and concentration guardrails. |
| [Bank failure ablations](bank-failure-ablations.md) | Failure-channel and stress-driver decomposition. |
| [HH-bank lead-lag diagnostics](hh-bank-lead-lag-diagnostics.md) | Household distress and banking stress lead-lag surface. |
| [Loan origination quality diagnostics](loan-origination-quality-diagnostics.md) | Whether later bad outcomes are explained by borrower quality, bank gating, or capital stress. |
| [Nightly diagnostics](nightly-diagnostics.md) | Normal/stress/exploratory profile health summaries and failure policy. |

## Validation Coverage

| Layer | Coverage |
| --- | --- |
| Unit and property tests | Banking sector specs, bank regulatory metrics, interbank, capital semantics, monetary plumbing, NBP runtime contracts, flow emitters, insurance, NBFI, quasi-fiscal, and SFC projection specs. |
| Integration tests | Monte Carlo CSV integration, bank benchmark specs, SFC exactness tests, scenario diagnostics, and generated-output guards. |
| Generated artifacts | SFC matrix evidence, flow-mechanism semantics, and stock-flow reconciliation documents expose accounting routes. |
| Nightly diagnostics | Normal profiles should keep bank failure/all-failed semantics within expected bounds; stress/exploratory profiles report failure-channel behavior explicitly. |

## Current Limitations

- Bank capital is not holder-resolved bank equity. It is regulatory/accounting
  state, persisted on bank rows and SFC-validated through BankCapital.
- The unretained share of bank gross income has no owner-side dividend receiver
  in the current model. It is an explicit unowned outflow limitation under ADR
  0001, not a hidden transfer to bank shareholders.
- Deposit insurance is not modeled at individual account granularity. Bail-in
  uses an aggregate guarantee/haircut rule for newly failed bank rows.
- There is no bridge-bank, nationalization, or public recapitalization
  mechanism. All-failed unresolved-deposit paths fail fast.
- Insurance, NBFI/TFI, and quasi-fiscal vehicles are aggregate institutional
  agents. Their holder-resolution detail is intentionally coarser than the bank
  balance-sheet rows.
- ECL parameters, failure thresholds, bank benchmark bands, NBFI renewal rates,
  and non-bank portfolio rules are calibration assumptions with documented
  guardrails, not econometrically identified structural estimates.
