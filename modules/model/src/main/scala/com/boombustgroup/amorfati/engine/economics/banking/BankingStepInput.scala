package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.engine.World
import com.boombustgroup.amorfati.engine.economics.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.random.RandomStream

/** Same-month banking-stage input boundary assembled by monthly calculus. */
final case class StepInput(
    world: World,
    ledgerFinancialState: LedgerFinancialState,
    fiscal: FiscalConstraintEconomics.StepOutput,
    labor: LaborEconomics.StepOutput,
    householdIncome: HouseholdIncomeEconomics.StepOutput,
    demand: DemandEconomics.StepOutput,
    firm: FirmEconomics.StepOutput,
    householdFinancial: HouseholdFinancialEconomics.StepOutput,
    priceEquity: PriceEquityEconomics.StepOutput,
    openEconomy: OpenEconEconomics.StepOutput,
    banks: Vector[Banking.BankState],
    depositRng: RandomStream,
)
