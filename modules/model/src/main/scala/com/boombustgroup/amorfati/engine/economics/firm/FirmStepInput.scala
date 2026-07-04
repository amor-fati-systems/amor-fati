package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.{Banking, Firm, Household}
import com.boombustgroup.amorfati.engine.World
import com.boombustgroup.amorfati.engine.economics.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState

/** Same-month firm-stage input boundary assembled by the public facade. */
private[firm] final case class StepInput(
    w: World,
    firms: Vector[Firm.State],
    households: Vector[Household.State],
    banks: Vector[Banking.BankState],
    ledgerFinancialState: LedgerFinancialState,
    fiscal: FiscalConstraintEconomics.StepOutput,
    labor: LaborEconomics.StepOutput,
    householdIncome: HouseholdIncomeEconomics.StepOutput,
    demand: DemandEconomics.StepOutput,
    traceDecisions: Boolean,
)
