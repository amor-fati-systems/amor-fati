package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.Firm
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.{OperationalSignals, World}
import com.boombustgroup.amorfati.types.*

/** Bank-facing credit surface used by the firm decision stage. */
private[firm] final case class LendingConditions(
    firmWorld: World,
    executionMonth: ExecutionMonth,
    operationalSignals: OperationalSignals,
    rates: Vector[Rate],
    bankCreditDecision: (Int, PLN) => Firm.CreditDecision,
    nBanks: Int,
)

/** Firm financing split across bank credit, listed equity, and corporate bonds.
  */
private[amorfati] final case class FinancingChannelAmounts(
    bankLoan: PLN,
    equity: PLN,
    bonds: PLN,
    techBankLoan: PLN,
    techBonds: PLN,
)

/** Firm state/stocks after applying the financing split. */
private[firm] final case class FinancingSplit(
    bankLoan: PLN,
    equity: PLN,
    bonds: PLN,
    techBankLoan: PLN,
    techBonds: PLN,
    firm: Firm.State,
    financialStocks: Firm.FinancialStocks,
)
