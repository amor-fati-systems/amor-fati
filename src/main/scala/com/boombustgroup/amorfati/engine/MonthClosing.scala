package com.boombustgroup.amorfati.engine

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.engine.economics.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.engine.mechanisms.{InformalEconomy, PopulationLifecycleTransitions}
import com.boombustgroup.amorfati.types.*

/** Same-month input consumed by the month-closing phase.
  *
  * Unlike [[MonthExecution]], this is not the whole execution transcript. It is
  * the closing contract: a narrowed closing-state view plus the derived
  * mechanism, diagnostic, and agent-lifecycle inputs required to materialize
  * the realized month-`t` state.
  */
final case class MonthClosingInput(
    closingState: MonthClosingStateInput,
    mechanisms: MonthClosingMechanisms,
    diagnostics: MonthClosingDiagnostics,
    agentLifecycle: PopulationLifecycleTransitions.Input,
)

/** Same-month state surface needed to materialize the realized closed month.
  *
  * `MonthExecution` remains the full economics transcript. This type is the
  * narrower closing view consumed by `closedmonth` assemblers.
  */
final case class MonthClosingStateInput(
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

object MonthClosingStateInput:
  def fromExecution(execution: MonthExecution): MonthClosingStateInput =
    MonthClosingStateInput(
      openingWorld = execution.openingWorld,
      fiscal = execution.fiscal,
      labor = execution.labor,
      householdIncome = execution.householdIncome,
      demand = execution.demand,
      firm = execution.firm,
      householdFinancial = execution.householdFinancial,
      priceEquity = execution.priceEquity,
      openEconomy = execution.openEconomy,
      banking = execution.banking,
    )

final case class MonthClosingMechanisms(
    informalEconomy: InformalEconomy.Result,
)

final case class MonthClosingDiagnostics(
    flowOfFundsResidual: PLN,
)

/** Realized month-`t` closing state before the next-month seed is applied. */
final case class MonthClosingResult(
    world: World,
    firms: Vector[Firm.State],
    households: Vector[Household.State],
    banks: Vector[Banking.BankState],
    householdAggregates: Household.Aggregates,
    ledgerFinancialState: LedgerFinancialState,
    startupAbsorptionRate: Share,
)
