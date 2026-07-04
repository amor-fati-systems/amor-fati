package com.boombustgroup.amorfati.engine

import com.boombustgroup.amorfati.engine.economics.*

/** Same-month result of executing the ordered economics pipeline for month `t`.
  *
  * This is not an opening state and not a next-month output. It is the
  * month-`t` execution surface consumed by flow emission and semantic
  * projection. Month closing receives a narrowed [[MonthClosingStateInput]]
  * derived from this transcript.
  */
final case class MonthExecution(
    openingWorld: World,
    fiscal: FiscalConstraintEconomics.StepOutput,
    labor: LaborEconomics.StepOutput,
    householdIncome: HouseholdIncomeEconomics.StepOutput,
    demand: DemandEconomics.StepOutput,
    firm: FirmEconomics.StepOutput,
    householdFinancial: HouseholdFinancialEconomics.StepOutput,
    priceEquity: PriceEquityEconomics.StepOutput,
    openEconomy: OpenEconEconomics.StepOutput,
    banking: BankingEconomics.StepOutput,
)
