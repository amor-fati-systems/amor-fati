package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.PLN

/** Processes incumbent firms through operating decisions and financing splits.
  */
private[firm] object FirmProcessingStage:

  def process(
      firms: Vector[Firm.State],
      ledgerFinancialState: LedgerFinancialState,
      lending: LendingConditions,
      rng: RandomStream,
      traceDecisions: Boolean,
  )(using p: SimParams): FirmProcessingResult =
    require(
      firms.length == ledgerFinancialState.firms.length,
      s"FirmEconomics.processFirms requires aligned firms and ledger firm balances, got ${firms.length} firms and ${ledgerFinancialState.firms.length} balance rows",
    )
    val openingStocks = ledgerFinancialState.firms.map(LedgerFinancialState.projectFirmFinancialStocks)
    val outcomes      = firms
      .zip(openingStocks)
      .map: (f, stocks) =>
        val rate           = lending.rates(f.bankId.toInt)
        val creditDecision = (amt: PLN) => lending.bankCreditDecision(f.bankId.toInt, amt)
        val r              = Firm.processWithCreditAudit(
          f,
          stocks,
          lending.firmWorld,
          lending.executionMonth,
          lending.operationalSignals,
          rate,
          creditDecision,
          firms,
          rng,
          CorporateBondOwnership.issuerBalanceFor(ledgerFinancialState, f.id),
          traceDecision = traceDecisions,
        )
        val fin            = FirmFinancing.split(r)
        val trace          =
          if traceDecisions then
            val baseTrace  = r.decisionTrace.getOrElse:
              throw IllegalStateException(s"Firm.process did not return a decision trace for firm ${f.id.toInt}")
            val components = FirmDecisionTraceSupport.traceLoanComponents(baseTrace, finalLoan = fin.bankLoan, finalTechCreditAmount = fin.techBankLoan)
            Some(
              baseTrace.copy(
                firmLoanAfter = fin.financialStocks.firmLoan,
                newLoan = fin.bankLoan,
                techCreditAmount = components.techCreditAmount,
                investmentCreditAmount = components.investmentCreditAmount,
              ),
            )
          else None

        FirmOutcome(
          firm = fin.firm,
          financialStocks = fin.financialStocks,
          flows = FirmFlows(
            tax = r.taxPaid,
            capex = r.capexSpent,
            techImp = r.techImports,
            newLoans = fin.bankLoan,
            equityIssuance = fin.equity,
            grossInvestment = r.grossInvestment,
            bondIssuance = fin.bonds,
            profitShifting = r.profitShiftCost,
            fdiRepatriation = r.fdiRepatriation,
            inventoryChange = r.inventoryChange,
            citEvasion = r.citEvasion,
            energyCost = r.energyCost,
            greenInvestment = r.greenInvestment,
            principalRepaid = r.principalRepaid,
            investmentCreditDemand = r.investmentCreditDemand,
            investmentCreditApproved = r.investmentCreditApproved,
            investmentCreditRejected = r.investmentCreditRejected,
            techCreditDemand = r.techCreditDemand,
            techCreditApproved = r.techCreditApproved,
            techCreditRejected = r.techCreditRejected,
            techSelectedCreditDemand = r.techSelectedCreditDemand,
            techSelectedCreditApproved = r.techSelectedCreditApproved,
            techSelectedCreditRejected = r.techSelectedCreditRejected,
            techCandidateCreditDemand = r.techCandidateCreditDemand,
            techCandidateCreditApproved = r.techCandidateCreditApproved,
            techCandidateCreditRejected = r.techCandidateCreditRejected,
            creditRejectedByReason = r.investmentCreditRejectionBreakdown + r.techCreditRejectionBreakdown,
          ),
          realizedPostTaxProfit = r.realizedPostTaxProfit,
          bankId = f.bankId,
          finalLoan = fin.bankLoan,
          bondAmt = fin.bonds,
          techBankLoan = fin.techBankLoan,
          techBondAmt = fin.techBonds,
          automationUpgradeFailure = becameBankruptWith(f, fin.firm, isImplementationFailure),
          automationAiDebtTrap = becameBankruptWith(f, fin.firm, _ == BankruptReason.AiDebtTrap),
          automationNewFullAi = !isAutomated(f) && isAutomated(fin.firm),
          automationNewHybrid = !isHybrid(f) && isHybrid(fin.firm),
          principalRepaid = r.principalRepaid,
          decisionTrace = trace,
        )

    val aggFlows = outcomes.foldLeft(FirmFlows.zero)((acc, o) => acc + o.flows)
    val bondMap  = outcomes.collect { case o if o.bondAmt > PLN.Zero => o.firm.id -> o.bondAmt }.toMap

    FirmProcessingResult(outcomes, aggFlows, bondMap, traceDecisions)

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
