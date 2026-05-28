package com.boombustgroup.amorfati.engine

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.engine.mechanisms.{InformalEconomy, PopulationLifecycleTransitions}
import com.boombustgroup.amorfati.types.*

/** Same-month input consumed by the month-closing phase.
  *
  * Unlike [[MonthExecution]], this is not the whole execution transcript. It is
  * the closing contract: execution plus the derived mechanism, diagnostic, and
  * agent-lifecycle inputs required to materialize the realized month-`t` state.
  */
final case class MonthClosingInput(
    execution: MonthExecution,
    mechanisms: MonthClosingMechanisms,
    diagnostics: MonthClosingDiagnostics,
    agentLifecycle: PopulationLifecycleTransitions.Input,
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
