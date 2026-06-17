# Household Equations

This document consolidates the implemented household-sector model into a
publication-facing equation section. It uses the canonical notation in
[model-notation-and-state-vector.md](model-notation-and-state-vector.md) and
the monthly timing contract in
[monthly-transition-function.md](monthly-transition-function.md).

It describes current executable behavior. It does not introduce new household
rules and does not replace the implementation anchors listed below.

## Source Anchors

| Source | Role |
| --- | --- |
| [Model specification](model-specification.md#reviewer-reading-path) | Canonical reviewer path and model overview. |
| [Model notation and state vector](model-notation-and-state-vector.md#households) | Household behavioral state, ledger-owned household balances, and major symbol families. |
| [Monthly transition function](monthly-transition-function.md) | `X_t -> X_tau` timing, randomness, flow emission, SFC validation, and next-pre boundary. |
| [Behavioral equations and decision rules](behavioral-equations-and-decision-rules.md#household-rules) | Detailed implementation-oriented household rule catalog and output-column map. |
| [SFC matrix evidence](sfc-matrix-evidence.md) | Accounting matrix evidence and generated runtime mapping artifacts. |
| [Engine invariants and semantics](engine-invariants-and-semantics.md) | Validation ownership and hard-fail semantics. |
| [`agents/Household.scala`](../src/main/scala/com/boombustgroup/amorfati/agents/Household.scala) and [`agents/household/*`](../src/main/scala/com/boombustgroup/amorfati/agents/household) | Household state, monthly budget pipeline, credit underwriting, liquidity waterfall, distress machine, labor transitions, and aggregate computation. |
| [`engine/economics/HouseholdIncomeEconomics.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/economics/HouseholdIncomeEconomics.scala) and [`engine/economics/HouseholdFinancialEconomics.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/economics/HouseholdFinancialEconomics.scala) | Same-month household income, consumption/import split, bank-rate surface, product-aware bank-credit supply, remittances, tourism services, and household financial aggregates. |
| [`engine/flows/HouseholdFlows.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/flows/HouseholdFlows.scala) and [`engine/flows/MortgageFlows.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/flows/MortgageFlows.scala) | Runtime household cash/credit/mortgage flow emission and SFC settlement shells. |
| [`montecarlo/McTimeseriesSchema.scala`](../src/main/scala/com/boombustgroup/amorfati/montecarlo/McTimeseriesSchema.scala), [`montecarlo/McHouseholdSnapshotSchema.scala`](../src/main/scala/com/boombustgroup/amorfati/montecarlo/McHouseholdSnapshotSchema.scala), and [`diagnostics/LoanOriginationQualityExport.scala`](../src/main/scala/com/boombustgroup/amorfati/diagnostics/LoanOriginationQualityExport.scala) | Public household time-series columns, optional household micro snapshots, and borrower-level credit-outcome diagnostics. |
| [`diagnostics/HhBankLeadLagDiagnosticsExport.scala`](../src/main/scala/com/boombustgroup/amorfati/diagnostics/HhBankLeadLagDiagnosticsExport.scala) and [`diagnostics/HouseholdCreditStressCalibrationExport.scala`](../src/main/scala/com/boombustgroup/amorfati/diagnostics/HouseholdCreditStressCalibrationExport.scala) | Household-bank lead/lag evidence and household credit-stress calibration surfaces. |

## State

For household `h`, the behavioral state is:

```text
a^H_{h,t} =
  (status_{h,t}, rent_h, skill_{h,t}, healthPenalty_{h,t}, mpc_{h,t},
   neighbors_h, bank_h, lastSector_h, immigrant_h, children_h,
   education_h, routineness_h, wageScar_{h,t}, contract_h, region_h,
   distressMonths_{h,t}, distressState_{h,t})
```

Ledger-owned household balances are:

```text
l^H_{h,t} =
  (D^H_{h,t}, M^H_{h,t}, CL^H_{h,t}, E^H_{h,t},
   RemMtgMonths_{h,t})
```

where `D^H` is the household demand-deposit asset, `M^H` is mortgage
principal, `CL^H` is unsecured consumer-loan principal, `E^H` is listed-equity
wealth, and `RemMtgMonths` is the remaining contractual mortgage maturity.

The activity status is:

```text
status_h in {
  Employed(firm, sector, wage),
  Unemployed(months),
  Retraining(monthsLeft, targetSector, cost),
  Bankrupt
}
```

The financial-distress lifecycle is separate from activity status:

```text
distressState_h in {
  Current, LiquidityStress, Arrears, Restructuring, Defaulted, Bankruptcy
}
```

The legacy activity status `Bankrupt` is an absorbing inactive labor/activity
state. The financial-distress `Bankruptcy` state is personal insolvency and
write-off evidence; it does not by itself remove the household from the labor
force.

## Monthly Household Boundary

The household batch step follows a fixed boundary:

```text
opening rows
-> validate aligned household and financial-stock rows
-> compute each household's monthly flows
-> aggregate exact public totals and per-bank flows
-> validate monthly diagnostics against aggregate totals
-> close updated household states, financial stocks, and monthly rows
```

Opening demand deposits must be non-negative. Any current-month deficit is
routed through explicit liquidity-shortfall financing and charge-off
diagnostics rather than persisted as a negative deposit.

## Income, PIT, And Transfers

The monthly labor-income base is:

```text
BaseInc_{h,tau} =
  wage_h                                  if Employed
  UnempBenefit(monthsUnemployed_h)        if Unemployed
  0                                       if Retraining or Bankrupt
```

Unemployed households increment their unemployment spell after benefit
resolution. Retraining and bankrupt activity states do not receive base income
inside the household step.

Bank-specific deposit interest is:

```text
DepInt_{h,tau} =
  D^H_{h,t} r^D_{bank(h),tau} / 12     if bank deposit rates are available
  0                                    otherwise
```

Gross monthly income is:

```text
GrossInc_{h,tau} = BaseInc_{h,tau} + max(DepInt_{h,tau}, 0)
```

PIT is annualized after employee ZUS:

```text
PitBase_{h,tau} = GrossInc_{h,tau} (1 - zusEmployeeRate)
AnnualPitBase_{h,tau} = 12 PitBase_{h,tau}
PIT_{h,tau} =
  max(ProgressiveTax(AnnualPitBase_{h,tau}) - annualTaxCredit, 0) / 12
```

Dependent-child transfers are:

```text
Transfer_{h,tau} = children_h * social800
Inc_{h,tau} = GrossInc_{h,tau} - PIT_{h,tau} + Transfer_{h,tau}
```

Aggregate pension income, when supplied by the monthly calculus, is added at
the household aggregate layer and consumed with the configured household MPC.

These income, PIT, and transfer equations are deterministic conditional on the
opening household state, bank-rate surface, and parameters.

## Mortgage Service

Household-side mortgage service uses the active contractual maturity:

```text
RemMtgMonths^*_{h,t} =
  0                              if M^H_{h,t} <= 0
  RemMtgMonths_{h,t}             if M^H_{h,t} > 0 and RemMtgMonths_{h,t} > 0
  mortgageMaturity               if M^H_{h,t} > 0 and RemMtgMonths_{h,t} = 0
```

Scheduled principal and interest are:

```text
MtgPrin_{h,tau} =
  M^H_{h,t} / RemMtgMonths^*_{h,t}    if RemMtgMonths^*_{h,t} > 0
  0                                   otherwise

r^M_{h,tau} =
  r^L_{bank(h),tau}                   if bank lending rates are available
  r^{NBP}_{tau} + mortgageSpread      otherwise

MtgInt_{h,tau} = M^H_{h,t} max(r^M_{h,tau}, 0) / 12
MtgService_{h,tau} = MtgPrin_{h,tau} + MtgInt_{h,tau}
```

The closing household mortgage stock after scheduled service is:

```text
M^H_{h,tau} = max(M^H_{h,t} - MtgPrin_{h,tau}, 0)
```

If mortgage debt remains, contractual maturity decreases by one month but not
below one. If the mortgage is fully repaid, remaining months become zero.

Mortgage principal and interest have separate accounting semantics: principal
reduces the mortgage stock through the mortgage principal settlement shell,
while interest is a household-to-bank cash flow. Aggregate mortgage
origination/default is handled by the housing and banking stages, not by a
household micro origination rule in this step.

## Consumer Credit

Consumer-loan service uses the household's bank lending rate plus the consumer
credit spread:

```text
r^{CC}_{h,tau} =
  r^L_{bank(h),tau} + ccSpread       if bank lending rates are available
  r^{NBP}_{tau} + ccSpread           otherwise

CCPrin_{h,tau} = CL^H_{h,t} ccAmortRate
CCInt_{h,tau} = CL^H_{h,t} r^{CC}_{h,tau} / 12
CCService_{h,tau} = CCPrin_{h,tau} + CCInt_{h,tau}
```

Normal underwritten consumer credit is available only to employed households.
The household-side eligibility rule is:

```text
Stressed_h =
  DisposablePreCredit_{h,tau} < wage_h * DisposableWageThreshold

Eligible_h =
  Employed_h
  and Stressed_h
  and not BlockedByDistress(distressState_{h,t})
  and U^{ccElig}_{h,tau} < ccEligRate
```

The distress states `Arrears`, `Restructuring`, `Defaulted`, and `Bankruptcy`
block new underwritten consumer-credit approval before the bank gate.
`Current` and `LiquidityStress` do not block it.

The DTI-based principal capacity is:

```text
ExistingDti_h =
  (MtgService_{h,tau} + CCService_{h,tau}) / Inc_{h,tau}

PaymentHeadroom_h =
  Inc_{h,tau} * max(ccMaxDti - ExistingDti_h, 0)

PaymentFactor_h = ccAmortRate + r^{CC}_{h,tau} / 12

Capacity^{CC}_{h,tau} =
  min(PaymentHeadroom_h / PaymentFactor_h, ccMaxLoan)
```

The desired underwritten principal is:

```text
StressGap_h =
  max(wage_h * DisposableWageThreshold - DisposablePreCredit_{h,tau}, 0)

EssentialGap_h =
  max(basicConsumptionFloor + CCService_{h,tau} - DisposablePreCredit_{h,tau}, 0)

Demand^{CC}_{h,tau} =
  min(max(StressGap_h, EssentialGap_h), Capacity^{CC}_{h,tau})
```

Small requests below the configured minimum consumer-loan size are set to zero.
Positive eligible demand is then passed through the product-aware bank-side
supply gate with `ConsumerLoan` as the requested product. That gate uses the same
audited bank credit-approval surface used for firm credit: failed-bank,
projected CAR with the consumer-loan RWA bucket incremented, LCR, NSFR, and
bank-side approval audit after those hard gates. Household diagnostics distinguish
total demand, rejected demand, the subset rejected by bank supply, and the
bank-side approval audit fields for household snapshot rows.

Ordinary closing consumer debt before residual liquidity settlement is:

```text
CL^{ordinary}_{h,tau} =
  max(CL^H_{h,t} + ApprovedCC_{h,tau} - CCPrin_{h,tau}, 0)
```

## Consumption And Savings

The first non-discretionary budget layer is:

```text
Obligations_{h,tau} =
  rent_h + MtgService_{h,tau} + Remit_{h,tau}

DisposablePreCredit_{h,tau} =
  max(Inc_{h,tau} - Obligations_{h,tau}, 0)

FullObligations_{h,tau} =
  Obligations_{h,tau} + CCService_{h,tau}

Disposable_{h,tau} =
  max(Inc_{h,tau} - FullObligations_{h,tau}, 0)
```

Savings-buffer drawdown depends on activity status:

```text
TargetSavings_h = max(Inc_{h,tau}, baseReservationWage) * bufferTargetMonths
ProtectedBuffer_h = TargetSavings_h * bufferProtectedShare

Drawdown_h =
  max(D^H_{h,t} - ProtectedBuffer_h, 0) * bufferStressDrawdownRate
    if Unemployed or Retraining
  max(D^H_{h,t} - TargetSavings_h, 0) * bufferExcessDrawdownRate
    if Employed or Bankrupt
```

The pre-waterfall consumption budget is:

```text
ConsBudget_{h,tau} =
  Disposable_{h,tau} + ApprovedCC_{h,tau} + Drawdown_h

DesiredCons^{raw}_{h,tau} = mpc_{h,t} ConsBudget_{h,tau}
```

Social-neighbor distress and the household financial-distress state then apply
precautionary compression. Positive listed-equity revaluation and the aggregate
housing wealth effect add to desired consumption:

```text
E^H_{h,tau} = max(E^H_{h,t} (1 + r^E_tau), 0)
EquityBoost_h = max(E^H_{h,tau} - E^H_{h,t}, 0) * equityWealthEffectMpc
HousingBoost_h = HousingWealthEffect_tau / population_tau
DesiredCons_{h,tau} = DistressAdjustedCons_h + EquityBoost_h + HousingBoost_h
```

The consumption waterfall pays basic consumption first, then obligations, then
affordable discretionary consumption:

```text
BasicNeed_h = min(DesiredCons_{h,tau}, basicConsumptionFloor)
AvailableCash_h = max(D^H_{h,t} + Inc_{h,tau} + ApprovedCC_{h,tau}, 0)

PaidBasic_h = min(BasicNeed_h, AvailableCash_h)
UnmetBasic_h = BasicNeed_h - PaidBasic_h

AvailableAfterBills_h =
  max(AvailableCash_h - PaidBasic_h - max(FullObligations_{h,tau}, 0), 0)

PaidDiscretionary_h =
  min(max(DesiredCons_{h,tau} - BasicNeed_h, 0), AvailableAfterBills_h)

Cons_{h,tau} = PaidBasic_h + PaidDiscretionary_h
```

Unmet basic consumption and discretionary compression are diagnostics, not
credit origination. They are recorded before any residual liquidity bridge is
settled.

The raw liquid-balance signal is:

```text
RawD_{h,tau} =
  D^H_{h,t} + Inc_{h,tau} - FullObligations_{h,tau}
  + ApprovedCC_{h,tau} - Cons_{h,tau}
```

Retraining cost, if paid in the current month, is subtracted before final
liquidity settlement.

## Liquidity Shortfall Settlement

Before booking a bridge, any remaining negative raw liquid balance is offered
to the same underwritten consumer-credit path and product-aware bank supply up
to unused DTI capacity:

```text
ResidualShortfall_h = max(-RawD_{h,tau}, 0)
UnusedCapacity_h = max(Capacity^{CC}_{h,tau} - ApprovedCC_{h,tau}, 0)
ResidualDemand_h = min(ResidualShortfall_h, UnusedCapacity_h)
```

Approved residual demand increases `ApprovedCC` and the raw liquid balance. If
the final raw balance remains negative, it is settled as explicit same-month
liquidity-shortfall financing:

```text
Shortfall_h = max(-RawD^{final}_{h,tau}, 0)
D^H_{h,tau} = max(RawD^{final}_{h,tau}, 0)
```

The shortfall is attributed in cash-priority order to:

```text
consumption shortfall
rent arrears
mortgage arrears
consumer-debt arrears
temporary overdraft
```

These components must sum exactly to
`HouseholdLiquidity_ShortfallFinancing`. The same amount is also
`LiquidityBridgeChargeOff`: it prevents negative demand deposits and is charged
off in the same month rather than becoming ordinary consumer debt.

The public consumer-credit decomposition is:

```text
ConsumerOrigination =
  ConsumerApprovedOrigination + HouseholdLiquidity_ShortfallFinancing

ConsumerDefault =
  ConsumerLoanDefault + LiquidityBridgeChargeOff

ConsumerCredit_NetStockFlow =
  ConsumerOrigination - ConsumerPrincipal - ConsumerDefault

ConsumerCredit_UnderwrittenNetFlow =
  ConsumerApprovedOrigination - ConsumerPrincipal - ConsumerLoanDefault

ConsumerCredit_BridgeNetFlow =
  HouseholdLiquidity_ShortfallFinancing - LiquidityBridgeChargeOff
```

This split is central to the accounting semantics: underwritten consumer credit
and residual liquidity settlement are both SFC-routed consumer-credit mechanisms
but they are diagnostically separate.

## MPC Adaptation

After survival resolution, the marginal propensity to consume adapts through a
buffer-stock rule:

```text
TargetSavings_h = Inc_{h,tau} * bufferTargetMonths
BufferRatio_h = D^H_{h,t} / TargetSavings_h
Deviation_h = BufferRatio_h - 1

BufferAdj_h =
  clamp(1 - bufferSensitivity * Deviation_h, 0, 1)

UnemployedAdj_h =
  1 + mpcUnemployedBoost      if Unemployed
  1                           otherwise

mpc_{h,tau} =
  clamp(mpc_{h,t} * BufferAdj_h * UnemployedAdj_h, MpcFloor, MpcCeiling)
```

If current income is non-positive, MPC is left unchanged.

## Financial Distress, Default, And Personal Insolvency

The monthly financial-distress trigger is:

```text
EssentialOutflows_h =
  max(rent_h + MtgService_{h,tau} + CCService_{h,tau}, rent_h)

DistressFloor_h = EssentialOutflows_h * bankruptcyThreshold

Triggered_h =
  RawD_{h,tau} < 0
  or UnmetBasic_h > 0
  or RawD_{h,tau} < DistressFloor_h
```

Consecutive distress months evolve as:

```text
distressMonths_{h,tau} =
  distressMonths_{h,t} + 1     if Triggered_h
  0                            otherwise
```

The distress state transition is threshold-driven by the updated consecutive
distress spell:

```text
Bankruptcy       if legacy activity status is Bankrupt or personal insolvency fires
Defaulted        if distressMonths >= bankruptcyDistressMonths
Arrears          if distressMonths >= 2
LiquidityStress  if distressMonths = 1

If distressMonths = 0:
  Arrears, Defaulted, Bankruptcy -> Restructuring
  Current, LiquidityStress, Restructuring -> Current
```

Personal insolvency fires when consecutive distress exceeds
`personalInsolvencyDistressMonths`. It writes off unsecured consumer-loan debt
and listed equity:

```text
CL^H_{h,tau} = 0
E^H_{h,tau} = 0
distressState_{h,tau} = Bankruptcy
```

The mortgage stock is not written off by this household personal-insolvency
branch. Mortgage default is an aggregate housing/banking flow, while household
personal insolvency is an unsecured consumer-credit/equity write-off.

Long unemployment creates labor-market scarring:

```text
skill_{h,tau} = skill_{h,t} (1 - skillDecayRate)
  if monthsUnemployed >= scarringOnset

healthPenalty_{h,tau} =
  min(healthPenalty_{h,t} + scarringRate, scarringCap)
  if monthsUnemployed >= scarringOnset

wageScar_{h,tau} =
  min(wageScar_{h,t} + wageScarRate, wageScarCap)
  if monthsUnemployed >= scarringOnset
```

Re-employment decays the wage scar toward zero.

## Labor Mobility And Retraining

Employed households can attempt voluntary cross-sector search. The target
sector is selected from sector wage and vacancy signals, adjusted by the labor
friction matrix. A transition into retraining occurs only when:

```text
U^{search}_{h,tau} < voluntarySearchProb
vacancies_{target,tau} > 0
targetWage_{target,tau} * crossSectorWagePenalty(friction)
  > wage_h * (1 + voluntaryWageThreshold)
friction <= adjacentFrictionMax
D^H_{h,t} > retrainingCost(friction)
```

Unemployed households can enter retraining after the unemployment threshold if
retraining is enabled, they have enough deposits to pay the relevant cost, and
the retraining draw succeeds. Distressed social neighbors increase the
retraining probability.

Retraining completion succeeds with:

```text
p^{retrain}_{h,tau} =
  retrainingBaseSuccess
  * skill_{h,tau}
  * (1 - healthPenalty_{h,tau})
  * educationMultiplier_{education_h}
  * (1 - friction * frictionSuccessDiscount)
```

A successful completion resets the unemployment spell to zero; a failed
completion returns the household to unemployment with a configured
post-failure unemployment spell. Labor-market matching is handled by the labor
market stage, not by the household budget rule itself.

## Remittances, Imports, And External Links

Immigrant households send remittances out of post-tax-and-transfer monthly
income, before rent, debt service, consumption, and liquidity settlement are
applied:

```text
Remit_{h,tau} =
  Inc_{h,tau} * remitRate      if immigrant_h
  0                            otherwise
```

The household import share is adjusted by the exchange rate:

```text
ImportAdj_tau =
  clamp(importPropensity * (baseExRate / ExRate_tau)^{ImportErElasticity}, 0, 1)
```

Household aggregate consumption is split as:

```text
GoodsCons_tau = sum_h Cons_{h,tau}
TotalCons_tau = GoodsCons_tau + sum_h rent_h
ImportCons_tau = GoodsCons_tau * ImportAdj_tau
DomesticCons_tau = TotalCons_tau - ImportCons_tau
```

Diaspora remittance inflow and tourism services are computed at the household
financial-economics aggregate boundary. They are external-sector calibration
bridges, not household micro decisions.

## Accounting And SFC Mapping

Household behavior is translated into ledger and SFC evidence through the
monthly flow layer:

| Household surface | Main flow/SFC interpretation |
| --- | --- |
| goods consumption | household cash to firms |
| rent | household cash to landlord/housing settlement |
| PIT | household cash to government |
| deposit interest | bank cash to households |
| remittances | household cash to foreign transfer settlement |
| consumer-credit origination | bank-issued consumer-loan asset/liability stock movement |
| liquidity-shortfall financing | same-month consumer-loan bridge/write-off mechanism |
| consumer principal | consumer-loan stock repayment |
| consumer interest | household cash to banks |
| consumer default | consumer-loan stock write-off/default evidence |
| mortgage origination/repayment/default | mortgage principal settlement shell |
| mortgage interest | household cash to banks |
| equity revaluation and write-off | listed-equity stock evidence and household bankruptcy diagnostics |

The exact accounting contract is validated by runtime ledger conservation and
SFC semantic identities. Household monthly rows are also checked against
aggregate totals for consumer default, consumer-loan default, bridge
charge-off, liquidity shortfall, and each shortfall component.

## Output And Diagnostic Surfaces

Representative output columns include:

| Surface | Columns |
| --- | --- |
| Labor and income | `Unemployment`, `UnemployedShare`, `RetrainingShare`, `MarketWage`, `MeanEmployedWage`, contract-type shares |
| Consumption and distribution | aggregate consumption, domestic/import consumption, Gini/poverty/savings aggregates in household summaries |
| Consumer credit | `ConsumerLoans`, `ConsumerOrigination`, `ConsumerApprovedOrigination`, `ConsumerCreditDemand`, `ConsumerRejectedOrigination`, `ConsumerBankRejectedOrigination`, `ConsumerCredit_RejectedPortfolioPreference`, household snapshot bank-audit columns (`ConsumerBankApprovalProduct`, `ConsumerBankRejectionReason`, `ConsumerBankApprovalProbability`, `ConsumerBankApprovalRoll`, `ConsumerBankProjectedCAR`, `ConsumerBankMinCAR`, `ConsumerBankManagementCAR`, `ConsumerBankCapitalThrottle`, `ConsumerBankLCR`, `ConsumerBankNSFR`, `ConsumerBankPortfolio*`), `ConsumerDebtService`, `ConsumerPrincipal`, `ConsumerDefault`, `ConsumerLoanDefault`, `LiquidityBridgeChargeOff`, `ConsumerCredit_NetStockFlow`, `ConsumerCredit_UnderwrittenNetFlow`, `ConsumerCredit_BridgeNetFlow`, `ConsumerCredit_NplStock`, approval/rejection ratios |
| Mortgages and housing wealth | `MortgageStock`, `AvgMortgageRate`, `MortgageOrigination`, `MortgageRepayment`, `MortgageDefault`, `MortgageNetStockFlow`, `MortgageToGdp`, mortgage flow-to-stock ratios, `MortgageInterestIncome`, `HhHousingWealth`, `HousingWealthEffect` |
| Distress and bankruptcy | `HouseholdBankruptcies`, `HouseholdBankruptcyRate`, `HouseholdDistress_Current`, `HouseholdDistress_LiquidityStress`, `HouseholdDistress_Arrears`, `HouseholdDistress_Restructuring`, `HouseholdDistress_Defaulted`, `HouseholdDistress_Bankruptcy`, corresponding shares, `HouseholdDistress_ActiveShare` |
| Liquidity | `HouseholdLiquidity_NetDemandDeposit`, positive deposits, implicit overdraft diagnostics, deposit percentiles, `HouseholdLiquidity_ShortfallFinancing`, unmet basic consumption, discretionary compression, consumption/rent/mortgage/consumer-debt arrears, temporary overdraft |
| External household links | `RemittanceOutflow`, `DiasporaRemittanceInflow`, `NetRemittances`, tourism import/export columns |
| Regional and micro surfaces | regional unemployment columns, optional household snapshot columns for status, wage, financial distress, deposits, mortgage/consumer debt, DSR inputs, arrears/defaults, and liquidity shortfall rows |

Borrower-level loan-origination diagnostics link household underwriting
observations to later arrears, default, bankruptcy, and write-off outcomes over
an outcome window. HH-bank lead/lag diagnostics expose timing relationships
between household credit stress and bank balance-sheet stress.

## Validation Coverage

| Layer | Coverage |
| --- | --- |
| Unit/property tests | `HouseholdSpec`, `HouseholdPropertySpec`, `BufferStockMpcSpec`, `PitSpec`, `SocialTransferSpec`, `ConsumerCreditSpec`, deposit mobility, household income/financial economics, household flow tests, labor-market and regional tests. |
| Schema/diagnostic tests | Household snapshot schema/schedule, household shortfall cohorts, Monte Carlo schema, loan-origination quality diagnostics, HH-bank lead/lag diagnostics, and household credit-stress calibration. |
| Integration/nightly | Monte Carlo TSV integration, nightly diagnostics, loan-origination quality, scenario runs, sensitivity/robustness, SFC matrix evidence, and generated-output guard. |
| Accounting | Runtime ledger execution, household-flow exactness, mortgage-flow exactness, consumer-credit stock-flow diagnostics, SFC semantic projection, and household monthly row-to-aggregate reconciliation. |

## Current Limitations

- Household equations are executable and auditable, but several credit-stress,
  consumption, mobility, and distress parameters remain calibration targets
  rather than final publication estimates.
- Mortgage origination and default are aggregate housing/banking mechanisms.
  The household step models scheduled household-side mortgage service and
  burden, not micro-level mortgage application, collateral liquidation, or
  foreclosure resolution.
- Personal insolvency writes off unsecured consumer debt and listed equity but
  does not remove the household from the labor force. This is intentional in
  the current implementation and should be read as a financial-distress state,
  not demographic exit.
- Liquidity-shortfall financing is an accounting settlement mechanism for
  non-negative deposits. It is not discretionary consumption credit and should
  be interpreted together with the shortfall-component diagnostics.
- Retraining completion currently returns households to unemployment for later
  labor-market matching. The model does not yet implement a full individual
  training-to-employment placement mechanism.
- Household snapshots and loan-origination diagnostics are optional evidence
  surfaces. The standard Monte Carlo time series remains aggregate/meso-level,
  not a full household panel export by default.
