# Firm Equations

This document consolidates the implemented firm-sector model into a
publication-facing equation section. It uses the canonical notation in
[model-notation-and-state-vector.md](model-notation-and-state-vector.md) and
the monthly timing contract in
[monthly-transition-function.md](monthly-transition-function.md).

It describes current executable behavior. It does not introduce new model
behavior and does not replace the implementation anchors listed below.

## Source Anchors

| Source | Role |
| --- | --- |
| [Model specification](model-specification.md#reviewer-reading-path) | Canonical reviewer path and model overview. |
| [Model notation and state vector](model-notation-and-state-vector.md#firms) | Firm state, ledger-owned firm balances, and major symbol families. |
| [Monthly transition function](monthly-transition-function.md) | `X_t -> X_tau` timing, randomness, flow emission, SFC validation, and next-pre boundary. |
| [Behavioral equations and decision rules](behavioral-equations-and-decision-rules.md#firm-rules) | Detailed implementation-oriented rule catalog and output-column map. |
| [SFC matrix evidence](sfc-matrix-evidence.md) | Accounting matrix evidence and generated runtime mapping artifacts. |
| [Engine invariants and semantics](engine-invariants-and-semantics.md) | Validation ownership and hard-fail semantics. |
| [`agents/Firm.scala`](../src/main/scala/com/boombustgroup/amorfati/agents/Firm.scala) and [`agents/firm/*`](../src/main/scala/com/boombustgroup/amorfati/agents/firm) | Per-firm behavioral state, decisions, production, P&L, labor, technology, post-processing, and traces. |
| [`engine/economics/FirmEconomics.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/economics/FirmEconomics.scala) and [`engine/economics/firm/*`](../src/main/scala/com/boombustgroup/amorfati/engine/economics/firm) | Same-month firm-sector runner, lending surface, financing split, market stages, output assembly, and downstream monthly calculus boundary. |
| [`engine/mechanisms/FirmEntry.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/mechanisms/FirmEntry.scala) | Endogenous replacement and net firm entry. |
| [`montecarlo/McTimeseriesSchema.scala`](../src/main/scala/com/boombustgroup/amorfati/montecarlo/McTimeseriesSchema.scala) | Public numeric output columns for firm behavior and diagnostics. |
| [`diagnostics/LoanOriginationQualityExport.scala`](../src/main/scala/com/boombustgroup/amorfati/diagnostics/LoanOriginationQualityExport.scala), [`diagnostics/ScenarioRunExport.scala`](../src/main/scala/com/boombustgroup/amorfati/diagnostics/ScenarioRunExport.scala), and [`diagnostics/SensitivityRobustnessExport.scala`](../src/main/scala/com/boombustgroup/amorfati/diagnostics/SensitivityRobustnessExport.scala) | Borrower-level underwriting outcomes, scenario diagnostics, and robustness surfaces that expose firm-credit, investment, entry, and default behavior. |

## State

For firm `f` in sector `s = s(f)`, the behavioral state is:

```text
a^F_{f,t} =
  (tech_{f,t}, risk_f, innovCost_f, DR_{f,t}, s_f, N_f,
   bank_f, K_{f,t}, GK_{f,t}, Inv_{f,t}, markup_{f,t},
   foreign_f, stateOwned_f, region_f, startup_f, signal_f, ...)
```

Ledger-owned firm balances are:

```text
l^F_{f,t} = (Cash^F_{f,t}, L^F_{f,t}, CB^F_{f,t}, Eq^F_{f,t})
```

where `Cash^F` is firm liquidity, `L^F` is bank-loan principal, `CB^F`
is issued corporate-bond principal, and `Eq^F` is listed-equity financing
recorded in the firm ledger slice.

The technology regime is:

```text
tech_f in {Traditional(n), Hybrid(n, A), Automated(A), Bankrupt(reason)}
```

with effective worker count:

```text
n_f =
  n                                      if Traditional(n)
  n                                      if Hybrid(n, A)
  max(autoSkeletonCrew, floor(phi_skel N_f)) if Automated(A)
  0                                      if Bankrupt
```

Bankrupt firms are inactive until recycled through the entry mechanism.

## Capacity And Production

Let `N_f` be initial firm size and `N_bar` the calibration workers-per-firm
scale. The size scale is:

```text
size_f = N_f / N_bar
```

The labor/technology efficiency term is:

```text
ell_f =
  n_f / N_f                         if Traditional
  omega_H^L n_f / N_f + omega_H^A A_f if Hybrid
  A_f                               if Automated
  0                                 if Bankrupt
```

The structural capital target is based on planning workers:

```text
K^*_{f,t} = n^{plan}_{f,t} kappa_s
```

where `n^{plan}` is the current worker count, startup target, or initial size
floor used by the implementation for capital planning.

If physical capital and labor/technology efficiency are positive, firm capacity
is:

```text
C_{f,tau} =
  baseRevenue * size_f * revMult_s *
  CES_alpha,sigma_s(k_{f,t}, ell_{f,t})

k_{f,t} = clamp(K_{f,t} / K^*_{f,t}, 0.1, 2.0)
```

Otherwise:

```text
C_{f,tau} = baseRevenue * size_f * revMult_s * ell_{f,t}
```

Runtime productivity scales the realized capacity surface:

```text
C^eff_{f,tau} = prodIndex_t * C_{f,tau}
```

The CES aggregator is Cobb-Douglas at the unit-elasticity boundary:

```text
CES_alpha,sigma(k, ell) =
  k^alpha ell^(1-alpha)                                  if sigma approx 1
  [alpha k^rho + (1-alpha) ell^rho]^(1/rho)              otherwise
rho = (sigma - 1) / sigma
```

## Revenue And Profit

Let `P_t` be the domestic price level and `D_{s,tau}` the sector demand
multiplier used by same-month economics. Firm revenue is:

```text
Y^F_{f,tau} = P_t C^eff_{f,tau} D_{s,tau}
```

Labor cost is:

```text
WCost_{f,tau} = n_f wage_tau wageMult_s
```

where `wageMult_s` includes the sector wage multiplier and union wage premium.

Monthly interest cost uses bank and corporate-bond debt:

```text
IntCost_{f,tau} = (L^F_{f,t} + CB^F_{f,t}) r^L_{bank(f),tau} / 12
```

The pre-profit-shifting operating cost stack is:

```text
Cost^{prePS}_{f,tau} =
  WCost_{f,tau}
  + OtherCost_{f,tau}
  + DepK_{f,tau}
  + AIOpex_{f,tau}
  + IntCost_{f,tau}
  + InvCarry_{f,tau}
  + EnergyETS_{f,tau}
```

where:

- `OtherCost` scales with firm size, price level, startup multiplier, and the
  share of costs not already made explicit through capital, energy, or
  inventory channels;
- `DepK = K_{f,t} delta^K_s / 12`;
- `AIOpex` applies to automated and hybrid firms with domestic/import price
  splits and sublinear size scaling;
- `InvCarry = Inv_{f,t} r^{invCarry} / 12`;
- `EnergyETS` uses sector energy-cost shares, commodity prices, ETS carbon
  surcharge, and a green-capital discount.

Gross profit before FDI profit shifting is:

```text
Pi^{gross}_{f,tau} = Y^F_{f,tau} - Cost^{prePS}_{f,tau}
```

Foreign-owned firms shift a share of positive gross profit:

```text
PS_{f,tau} =
  profitShiftRate * max(Pi^{gross}_{f,tau}, 0) if foreign_f
  0                                           otherwise
```

Profit before CIT is:

```text
Pi^{preTax}_{f,tau} = Pi^{gross}_{f,tau} - PS_{f,tau}
```

CIT uses loss carryforward:

```text
Taxable_{f,tau} =
  max(Pi^{preTax}_{f,tau} - min(loss_{f,t}, carryMax * Pi^{preTax}_{f,tau}), 0)

CIT_{f,tau} = citRate * Taxable_{f,tau}
```

Losses increase when `Pi^{preTax} < 0`; remaining accumulated losses decay
after offsets.

Post-tax profit is:

```text
Pi^{net}_{f,tau} = Pi^{preTax}_{f,tau} - CIT_{f,tau}
```

These P&L equations are deterministic conditional on the opening state,
same-month macro inputs, lending rate, and parameters.

## Labor Demand And Workforce Adjustment

Traditional and hybrid firms compute desired workers from a one-period
marginal-revenue comparison. For a hypothetical worker count `n`:

```text
MR_f(n) =
  [C^eff_f(n) - C^eff_f(n - 1)] *
  P_t *
  D^{hire}_{s,tau}
```

The demand signal used for hiring blends realized sector demand with positive
sector hiring pressure:

```text
D^{hire}_{s,tau} =
  D_{s,tau} + max(HireSignal_{s,tau} - D_{s,tau}, 0) * blend
```

Desired workers are the largest `n` such that:

```text
MR_f(n) > wage_tau wageMult_s
```

The raw target is then compressed by aggregate operational hiring slack:

```text
n^{target}_{f,tau} =
  n_{f,t} + slack_tau * max(n^{raw}_{f,tau} - n_{f,t}, 0)
```

Hiring is limited by persistence, monthly headroom, liquidity, startup runway,
and profitability. Firing is smoothed by an adjustment share and softened for
state-owned firms:

```text
Delta n_{f,tau} =
  stochastic_round(lambda_hire * gap)      if gap > threshold_hire
  -stochastic_round(lambda_fire * |gap|)   if gap < -threshold_fire
  0                                        otherwise
```

The residual stochastic rounding draw is part of the firm-economics random
stream and is recorded in firm decision traces.

If negative cash cannot be cured through working-capital grace or downsizing,
the firm enters bankruptcy.

## Pricing, Intermediate Goods, And Inventory

After per-firm decisions and financing are fixed, the firm market stage applies
three deterministic or stochastic market surfaces:

1. intermediate-market payments and cash adjustments using the input-output
   matrix;
2. Calvo markup updates using sector demand pressure, wage growth, and
   energy-cost pressure;
3. labor-market matching, immigration/remigration, wage updates, and firm
   staffing synchronization.

The markup update is a Calvo lottery. With probability `theta`, firm `f` resets
its markup; otherwise it keeps the previous markup:

```text
mu_{f,tau} =
  mu^*_{f,tau}    if U^{price}_{f,tau} < theta
  mu_{f,t}        otherwise
```

The reset markup is:

```text
mu^*_{f,tau} =
  clamp(
    baseMarkup *
    [1
     + min(max(DPressure_{s,tau} - 1, 0) demandSensitivity, demandCap)
     + min(max(WageGrowth_tau, 0) costPassthrough, costCap)
     + min(max(EnergyPressure_{s,tau}, 0), energyCap)],
    minMarkup,
    maxMarkup)
```

where `EnergyPressure` depends on the commodity-price index, sector energy-cost
share, and the state-owned energy pass-through modifier. The aggregate markup
inflation contribution is the annualized capacity-weighted average markup
change:

```text
pi^{markup}_{tau} =
  annualize(
    sum_f C^eff_{f,tau} (mu_{f,tau} - mu_{f,t})
    / sum_f C^eff_{f,tau})
```

Inventory is adjusted after the primary decision:

```text
Inv^{postSpoil}_{f,tau} = Inv_{f,t} (1 - spoil_s / 12)
TargetInv_{f,tau} = RealizedRevenue_{f,tau} inventoryTarget_s
RawDeltaInv_{f,tau} =
  (TargetInv_{f,tau} - Inv^{postSpoil}_{f,tau}) inventoryAdjustSpeed
```

Positive inventory replenishment is capped by a revenue-based budget; negative
inventory movement cannot draw below zero. If cash is negative, the firm can
liquidate inventory at a discount to raise cash.

## Physical And Green Investment

Physical capital investment occurs after the selected firm decision:

```text
K^{postDep}_{f,tau} = K_{f,t} (1 - delta^K_s / 12)
K^{target}_{f,tau} =
  n^{plan}_{f,tau} kappa_s *
  [1 + demandExpansionSensitivity * pressure^+_{s,tau} * persistence_{f,t}]
```

Desired gross investment is:

```text
I^{des}_{f,tau} =
  DepK_{f,tau}
  + max(K^{target}_{f,tau} - K^{postDep}_{f,tau}, 0) * adjustSpeed * investMult_f
```

Cash investment is funded first:

```text
I^{cash}_{f,tau} = min(I^{des}_{f,tau}, max(Cash^F_{f,t}, 0))
```

Desired investment generates a target bank-credit request before the firm falls
back to internal cash. A separate shortfall leg still lets cash-constrained
firms request credit for unfunded investment:

```text
I^{targetDebt}_{f,tau} =
  I^{des}_{f,tau} * investmentDebtTargetShare

I^{shortfallDebt}_{f,tau} =
  max(I^{des}_{f,tau} - max(Cash^F_{f,t}, 0), 0) * investmentCreditShare

Demand^{invCredit}_{f,tau} =
  min(max(I^{targetDebt}_{f,tau}, I^{shortfallDebt}_{f,tau}), I^{des}_{f,tau})

I^{cash}_{f,tau} =
  min(I^{des}_{f,tau} - Credit^{approved}_{f,tau}, max(Cash^F_{f,t}, 0))
```

Approved investment credit increases both cash and firm-loan principal before
being spent on capital. Rejected credit is exposed by reason in the
`FirmCredit_*` diagnostics. If target-debt credit is rejected but the firm has
enough cash, the real investment can still proceed as self-financed CAPEX.

Green investment follows the same target-gap idea using sector green
capital-labor ratios, green depreciation, a green adjustment speed, and a
separate cash budget share.

## Technology Adoption

Traditional firms evaluate full-AI and hybrid candidates; hybrid firms can
evaluate full-AI upgrades. Candidate capex is:

```text
Capex^{AI}_{f,tau} =
  aiCapex * aiCapexMult_s *
  (1 - digiCapexDiscount * DR_{f,t}) *
  innovCost_f *
  size_f^{capexExp}

Capex^{Hybrid}_{f,tau} =
  hybridCapex * hybridCapexMult_s *
  (1 - digiCapexDiscount * DR_{f,t}) *
  innovCost_f *
  size_f^{capexExp}
```

Each candidate defines:

```text
Loan^{tech}_{f,tau} = loanShare_path * Capex_path
Down_path = downShare_path * Capex_path
```

A candidate is feasible when all of the following hold:

```text
current cost base exceeds the path cost threshold adjusted by sigma_s
Cash^F_{f,t} >= Down_path
DR_{f,t} >= readinessMin_path
relationship bank approves Loan_path
```

For traditional-firm upgrades, the path cost threshold is divided by the
sectoral substitution threshold `sigmaThreshold(sigma_s)`, matching the runtime
use of current sector technology substitutability in upgrade feasibility. Hybrid
to full-AI upgrades use the full-AI margin threshold without that additional
sector-sigma divisor.

Adoption probabilities combine:

- firm risk profile;
- digital readiness;
- local neighbor adoption;
- aggregate automation and hybrid ratios;
- loss-making desperation;
- strategic early-adoption pressure;
- a calendar-time adoption-willingness ramp;
- technology-path feasibility.

For traditional firms:

```text
p^{AI}_{f,tau} = min(1, willingness * rawFullAi)
p^{H}_{f,tau} = min(1 - p^{AI}_{f,tau}, willingness * rawHybrid)
```

A single adoption draw selects full-AI, hybrid, or no upgrade. Successful
upgrades draw technology efficiency. Failed implementations impose partial
capex, debt, and down-payment costs; catastrophic failures bankrupt the firm.

Digital-readiness investment is a separate fallback behavior when no technology
upgrade is selected and the firm has enough cash. It raises `DR` by a bounded
increment and records probability/roll evidence in decision traces.

Technology adoption is behavioral and stochastic. The resulting capex, loans,
imports, defaults, and state transitions become deterministic accounting terms
once the decision has been selected.

## Financing And Credit Approval

Firm credit requests are evaluated against the relationship bank's lending
surface. The decision carries approval probability, roll, and rejection reason
when available. Rejection reasons are exposed as:

```text
failed-bank, CAR gate, management capital-buffer throttle, LCR gate, NSFR gate,
portfolio-preference, unclassified
```

Approved technology and investment loan demand enters the firm financing split.
For a total new-financing amount `NewLoan_f`:

```text
Equity_f =
  equityFrac * NewLoan_f      if workers_f >= equityMinSize
  0                           otherwise

AfterEquity_f = NewLoan_f - Equity_f

CorpBond_f =
  bondFrac * AfterEquity_f    if workers_f >= corpBondMinSize
  0                           otherwise

BankLoan_f = AfterEquity_f - CorpBond_f
```

The technology portion is prorated across equity, corporate bonds, and bank
loans so the diagnostics can distinguish `TechBankLoan` from total financing.

Corporate-bond issuance is then constrained by market absorption. Unsold bond
issuance reverts to bank loans, preserving the global financing amount while
keeping channel diagnostics explicit.

## Default, NPL, And Exit

Per-firm decisions can produce bankruptcy directly when:

- an automated firm falls into an AI debt trap;
- technology implementation fails catastrophically;
- operating cash remains negative after grace and downsizing;
- startup runway is exhausted.

Newly dead firms are those alive at the opening stage and bankrupt after firm
market stages. Bank NPL creation and loss are:

```text
NPL^{new}_{tau} = sum_{f newly dead} L^F_{f,tau}
NPLLoss_tau = NPL^{new}_{tau} * (1 - loanRecovery)
```

Corporate-bond defaults are computed from defaulted issuer debt and cleared
from the holder-resolved corporate-bond ownership layer.

Bank and bond losses are accounting consequences of the selected firm states.
The stochastic part has already occurred in technology, labor-adjustment,
credit, and entry decisions.

## Entry And Replacement

Firm entry runs in the closed-month lifecycle boundary. It has two channels:

1. replacement entry: recycle a bounded share of bankrupt firm slots;
2. net entry: append new firms when demand, inflation, hiring slack, and
   startup absorption support expansion.

Sector entry weights combine:

```text
sector target share
* correction for over/under-representation
* demand pressure and utilization
* profitability signal
* sector entry-barrier multiplier
```

The profitability signal is based on sector-average cash relative to the
living-firm average and is clamped to a bounded range.

New entrants draw size, startup workforce target, region, bank relationship,
risk profile, innovation cost, digital readiness, neighbors, foreign ownership,
capital, inventory, and green capital. AI-native entrants become possible when
aggregate technology adoption exceeds the entry threshold and the AI-entry draw
succeeds.

## FDI, Informality, And Ownership-Sensitive Flows

Foreign-owned firms have two channels:

```text
profit shifting = profitShiftRate * max(gross profit, 0)
repatriation = min(repatriationRate * max(post-tax profit, 0), available cash)
```

Informal-economy CIT evasion is:

```text
CITEvasion_{f,tau} =
  CIT_{f,tau} *
  clamp(shadowShare_s + cyclicalInformalAdjustment_t, 0, 1) *
  citEvasionRate
```

These flows affect cash, tax payments, and external-sector/public-sector
evidence, but they do not create separate firm behavioral types.

## Accounting And SFC Mapping

Firm behavior is translated into ledger and SFC evidence through the monthly
flow layer:

| Firm surface | Main flow/SFC interpretation |
| --- | --- |
| wages and household income effects | household income and labor-market channels |
| CIT and CIT evasion | public-sector revenue and informal-economy tax adjustment |
| firm bank loans and principal | firm-credit stock movement and bank balance-sheet effects |
| firm NPL/default | bank loan-loss and firm-credit SFC diagnostics |
| corporate-bond issuance, coupon, amortization, default | holder-resolved corporate-bond runtime contracts |
| equity issuance and dividends | listed-equity and dividend/tax channels |
| investment and capex | firm cash, investment diagnostics, import split for technology capex |
| FDI profit shifting and repatriation | external income and foreign-sector flow channels |
| inventory and green capital | firm-state diagnostics and macro output surfaces |

The exact accounting contract is validated by runtime ledger conservation and
SFC semantic identities. Unsupported or diagnostic-only stock interpretations
must stay explicit in SFC matrix artifacts.

## Output And Diagnostic Surfaces

Representative output columns include:

| Surface | Columns |
| --- | --- |
| Production and investment | `GrossInvestment`, `PrivateGrossInvestmentToGdp`, `AggCapitalStock`, `AggInventoryStock`, `InventoryChange`, `InventoryToGdp`, `AggEnergyCost`, `GreenInvestment` |
| Technology adoption | `TotalAdoption`, `AutoRatio`, `HybridRatio`, `Automation_TechCapex`, `Automation_TechImports`, `Automation_TechLoans`, `Automation_UpgradeFailures`, `Automation_AiDebtTrap`, `Automation_NewFullAi`, `Automation_NewHybrid` |
| Firm credit | `FirmCredit_NewLoans`, `FirmCredit_PrincipalRepaid`, `FirmCredit_GrossDefault`, `FirmCredit_NplRecovery`, `FirmCredit_NplLoss`, `FirmCredit_NetStockFlow`, `FirmCredit_CreditDemand`, `FirmCredit_CreditApproved`, `FirmCredit_BankRejected`, `FirmCredit_ApprovalRate` |
| Credit rejection reasons | `FirmCredit_RejectedFailedBank`, `FirmCredit_RejectedCarGate`, `FirmCredit_RejectedCapitalBuffer`, `FirmCredit_RejectedLcrGate`, `FirmCredit_RejectedNsfrGate`, `FirmCredit_RejectedPortfolioPreference`, `FirmCredit_RejectedUnclassified`, `FirmCredit_RejectedPortfolioPreferenceToDemand`, `FirmCredit_RejectedPortfolioPreferenceToBankRejected` |
| Investment and technology credit | `FirmCredit_InvestmentDemand`, `FirmCredit_InvestmentApproved`, `FirmCredit_InvestmentBankRejected`, `FirmCredit_CashFinancedInvestment`, `FirmCredit_TechDemand`, `FirmCredit_TechApproved`, `FirmCredit_TechBankRejected`, selected/candidate tech-credit columns |
| Bonds and equity | `EquityIssuanceTotal`, `CorpBondOutstanding`, `CorpBondYield`, `CorpBondIssuance`, `CorpBondSpread`, `CorpBondAbsorptionRate`, holder corporate-bond columns |
| Entry and exit | `FirmBirths`, `FirmDeaths`, `NetEntry`, `LivingFirmCount`, `NetFirmBirths` |

In the credit rejection surface, `FirmCredit_RejectedPortfolioPreference` is the
absolute `portfolioPreferenceRejections` flow. The derived columns are
proportions: `FirmCredit_RejectedPortfolioPreferenceToDemand` is
`flowToFlowRatio(portfolioPreferenceRejections, firmCreditDemand)`, and
`FirmCredit_RejectedPortfolioPreferenceToBankRejected` is
`flowToFlowRatio(portfolioPreferenceRejections, firmCreditRejected)`. Both are
ratio/percentage-of-flow diagnostics, not PLN amounts.

Per-firm decision traces expose opening/closing technology, decision type,
cash/debt before and after, P&L, capex, credit decisions, bank approval
probabilities and rolls, portfolio-choice wedge components, technology
feasibility, adoption roll, implementation roll, efficiency draw,
investment-credit audit, digital-readiness investment, and labor-adjustment
residual rolls.

Loan-origination quality diagnostics link firm credit observations to later
bankruptcy outcomes over an outcome window. Scenario and sensitivity diagnostics
then aggregate firm deaths, credit, investment, and macro feedback for
publication-facing validation runs.

## Validation Coverage

| Layer | Coverage |
| --- | --- |
| Unit/property tests | `FirmSpec`, `FirmPropertySpec`, `SmoothLaborAdjustmentSpec`, `CesProductionSpec`, firm-size distribution, physical-capital, firm-entry, FDI composition, informal-economy, firm economics, and firm flow tests. |
| Schema/diagnostic tests | Firm decision trace selection/schema, firm snapshot schema/schedule, Monte Carlo schema, and loan-origination quality diagnostics. |
| Integration/nightly | Monte Carlo CSV integration, nightly diagnostics, loan-origination quality, sensitivity/robustness, scenario runs, SFC matrix evidence, and generated-output guard. |
| Accounting | Runtime ledger execution, firm-credit stock-flow, corporate-bond ownership, equity issuance, tax, external, and SFC semantic projection tests. |

## Current Limitations

- Firm equations are executable and auditable, but several empirical parameters
  still rely on calibration-register provenance rather than final publication
  estimates.
- Inventory, green capital, and informal-economy behavior are stylized model
  mechanisms with explicit diagnostics; they should be interpreted as current
  implemented channels, not settled empirical microfoundations.
- Corporate-bond and equity financing are channel splits and holder-aware
  accounting surfaces, not a full firm-level securities market with endogenous
  investor pricing at issuer resolution.
- Technology adoption is intentionally behavioral and stochastic. The model
  exposes feasibility, probabilities, and rolls, but the adoption law remains a
  calibration target for future empirical work.
- Entry and AI-native startup rules are current executable mechanisms. Their
  sector weights and AI-native threshold should be revisited when richer Polish
  firm-entry microdata are incorporated.
