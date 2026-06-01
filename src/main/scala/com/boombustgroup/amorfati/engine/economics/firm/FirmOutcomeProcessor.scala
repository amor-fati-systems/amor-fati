package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Per-firm decision processor used by the indexed firm processing stage. */
private[firm] object FirmOutcomeProcessor:

  def processOne(
      firm: Firm.State,
      openingStocks: Firm.FinancialStocks,
      allFirms: Vector[Firm.State],
      ledgerFinancialState: LedgerFinancialState,
      lending: LendingConditions,
      rng: RandomStream,
      traceDecisions: Boolean,
  )(using p: SimParams): FirmOutcome =
    val rate           = lending.rates(firm.bankId.toInt)
    val creditDecision = (amount: PLN) => lending.bankCreditDecision(firm.bankId.toInt, amount)
    val result         = Firm.processWithCreditAudit(
      firm,
      openingStocks,
      lending.firmWorld,
      lending.executionMonth,
      lending.operationalSignals,
      rate,
      creditDecision,
      allFirms,
      rng,
      CorporateBondOwnership.issuerBalanceFor(ledgerFinancialState, firm.id),
      traceDecision = traceDecisions,
    )
    val financing      = FirmFinancing.split(result)
    val trace          = decisionTrace(firm, result, financing, traceDecisions)

    FirmOutcome(
      firm = financing.firm,
      financialStocks = financing.financialStocks,
      flows = FirmFlows(
        tax = result.taxPaid,
        capex = result.capexSpent,
        techImp = result.techImports,
        newLoans = financing.bankLoan,
        equityIssuance = financing.equity,
        grossInvestment = result.grossInvestment,
        bondIssuance = financing.bonds,
        profitShifting = result.profitShiftCost,
        fdiRepatriation = result.fdiRepatriation,
        inventoryChange = result.inventoryChange,
        citEvasion = result.citEvasion,
        energyCost = result.energyCost,
        greenInvestment = result.greenInvestment,
        principalRepaid = result.principalRepaid,
        investmentCreditDemand = result.investmentCreditDemand,
        investmentCreditApproved = result.investmentCreditApproved,
        investmentCreditRejected = result.investmentCreditRejected,
        techCreditDemand = result.techCreditDemand,
        techCreditApproved = result.techCreditApproved,
        techCreditRejected = result.techCreditRejected,
        techSelectedCreditDemand = result.techSelectedCreditDemand,
        techSelectedCreditApproved = result.techSelectedCreditApproved,
        techSelectedCreditRejected = result.techSelectedCreditRejected,
        techCandidateCreditDemand = result.techCandidateCreditDemand,
        techCandidateCreditApproved = result.techCandidateCreditApproved,
        techCandidateCreditRejected = result.techCandidateCreditRejected,
        creditRejectedByReason = result.investmentCreditRejectionBreakdown + result.techCreditRejectionBreakdown,
      ),
      realizedPostTaxProfit = result.realizedPostTaxProfit,
      bankId = firm.bankId,
      finalLoan = financing.bankLoan,
      bondAmt = financing.bonds,
      techBankLoan = financing.techBankLoan,
      techBondAmt = financing.techBonds,
      automationUpgradeFailure = becameBankruptWith(firm, financing.firm, isImplementationFailure),
      automationAiDebtTrap = becameBankruptWith(firm, financing.firm, _ == BankruptReason.AiDebtTrap),
      automationNewFullAi = !isAutomated(firm) && isAutomated(financing.firm),
      automationNewHybrid = !isHybrid(firm) && isHybrid(financing.firm),
      principalRepaid = result.principalRepaid,
      decisionTrace = trace,
    )

  private def decisionTrace(
      firm: Firm.State,
      result: Firm.Result,
      financing: FinancingSplit,
      traceDecisions: Boolean,
  ): Option[Firm.DecisionTrace] =
    if traceDecisions then
      val baseTrace  = result.decisionTrace.getOrElse:
        throw IllegalStateException(s"Firm.process did not return a decision trace for firm ${firm.id.toInt}")
      val components = FirmDecisionTraceSupport.traceLoanComponents(
        baseTrace,
        finalLoan = financing.bankLoan,
        finalTechCreditAmount = financing.techBankLoan,
      )
      Some(
        baseTrace.copy(
          firmLoanAfter = financing.financialStocks.firmLoan,
          newLoan = financing.bankLoan,
          techCreditAmount = components.techCreditAmount,
          investmentCreditAmount = components.investmentCreditAmount,
        ),
      )
    else None

  private def bankruptcyReason(firm: Firm.State): Option[BankruptReason] =
    firm.tech match
      case TechState.Bankrupt(reason) => Some(reason)
      case _                          => None

  private def becameBankruptWith(opening: Firm.State, closing: Firm.State, matches: BankruptReason => Boolean): Boolean =
    Firm.isAlive(opening) && bankruptcyReason(closing).exists(matches)

  private def isImplementationFailure(reason: BankruptReason): Boolean =
    reason == BankruptReason.AiImplFailure || reason == BankruptReason.HybridImplFailure

  private def isAutomated(firm: Firm.State): Boolean =
    firm.tech.isInstanceOf[TechState.Automated]

  private def isHybrid(firm: Firm.State): Boolean =
    firm.tech.isInstanceOf[TechState.Hybrid]
