package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.Firm
import com.boombustgroup.amorfati.types.*

/** One incumbent firm's processed same-month outcome before market settlement.
  */
private[firm] final case class FirmOutcome(
    firm: Firm.State,
    financialStocks: Firm.FinancialStocks,
    flows: FirmFlows,
    realizedPostTaxProfit: PLN,
    bankId: BankId,
    finalLoan: PLN,
    bondAmt: PLN,
    techBankLoan: PLN,
    techBondAmt: PLN,
    automationUpgradeFailure: Boolean,
    automationAiDebtTrap: Boolean,
    automationNewFullAi: Boolean,
    automationNewHybrid: Boolean,
    principalRepaid: PLN,
    decisionTrace: Option[Firm.DecisionTrace],
)

/** Accumulated result of processing every incumbent firm. */
private[firm] final case class FirmProcessingResult(
    outcomes: Vector[FirmOutcome],
    flows: FirmFlows,
    firmBondAmounts: Map[FirmId, PLN],
    traceDecisions: Boolean,
)

private[firm] final case class TraceLoanComponents(techCreditAmount: PLN, investmentCreditAmount: Option[PLN])
