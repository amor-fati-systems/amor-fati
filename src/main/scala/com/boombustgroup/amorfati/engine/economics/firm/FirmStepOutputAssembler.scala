package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.{Firm, Household}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.fp.EntityIds.HhId
import com.boombustgroup.amorfati.types.*

/** Final projection from firm-stage internals to the public StepOutput. */
private[firm] object FirmStepOutputAssembler:

  def assemble(
      fp: FirmProcessingResult,
      bonded: BondAbsorptionResult,
      ioFirms: Vector[Firm.State],
      firmFinancialStocks: Vector[Firm.FinancialStocks],
      ledgerFinancialState: LedgerFinancialState,
      totalIoPaid: PLN,
      households: Vector[Household.State],
      crossSectorHires: Int,
      hires: Int,
      hireCapacity: Int,
      newHouseholdFinancialStocksById: Map[HhId, Household.FinancialStocks],
      npl: NplResult,
      in: StepInput,
      lending: LendingConditions,
      markupInflation: Rate,
  )(using p: SimParams): StepOutput =
    val flows                 = fp.flows
    val ledgerAfterFirmStocks = in.householdIncome.ledgerFinancialState.copy(
      households = LedgerFinancialState.refreshHouseholdBalances(
        households,
        in.householdIncome.updatedHouseholds,
        in.householdIncome.ledgerFinancialState.households,
        newHouseholdFinancialStocksById,
      ),
      firms = LedgerFinancialState.refreshFirmFinancialBalances(firmFinancialStocks, ledgerFinancialState.firms),
    )

    val emptyPln        = Vector.fill(lending.nBanks)(PLN.Zero)
    val perBankNewLoans = fp.outcomes.foldLeft(emptyPln): (acc, o) =>
      acc.updated(o.bankId.toInt, acc(o.bankId.toInt) + o.finalLoan)

    val perBankNewLoansWithRevert =
      if bonded.corpBondAbsorption < Share.One then
        fp.outcomes.foldLeft(perBankNewLoans): (acc, o) =>
          val revert = bonded.bondReversionByFirm.getOrElse(o.firm.id, PLN.Zero)
          if revert > PLN.Zero then acc.updated(o.bankId.toInt, acc(o.bankId.toInt) + revert)
          else acc
      else perBankNewLoans

    val perBankFirmPrincipal = fp.outcomes.foldLeft(emptyPln): (acc, o) =>
      acc.updated(o.bankId.toInt, acc(o.bankId.toInt) + o.principalRepaid)
    val decisionTraces       =
      if fp.traceDecisions then finalizedDecisionTraces(fp, bonded, ioFirms, firmFinancialStocks)
      else Vector.empty

    StepOutput(
      ioFirms = ioFirms,
      households = households,
      sumTax = flows.tax,
      sumCapex = flows.capex,
      sumTechImp = flows.techImp,
      sumNewLoans = bonded.sumNewLoans,
      automationTechCapex = flows.capex,
      automationTechImports = flows.techImp,
      automationTechLoans = automationTechLoanTotal(fp, bonded),
      automationUpgradeFailures = fp.outcomes.count(_.automationUpgradeFailure),
      automationAiDebtTrap = fp.outcomes.count(_.automationAiDebtTrap),
      automationNewFullAi = fp.outcomes.count(_.automationNewFullAi),
      automationNewHybrid = fp.outcomes.count(_.automationNewHybrid),
      sumEquityIssuance = flows.equityIssuance,
      sumGrossInvestment = flows.grossInvestment,
      sumBondIssuance = flows.bondIssuance,
      sumProfitShifting = flows.profitShifting,
      sumFdiRepatriation = flows.fdiRepatriation,
      sumInventoryChange = flows.inventoryChange,
      sumCitEvasion = flows.citEvasion,
      sumEnergyCost = flows.energyCost,
      sumGreenInvestment = flows.greenInvestment,
      totalIoPaid = totalIoPaid,
      nplNew = npl.nplNew,
      nplLoss = npl.nplLoss,
      totalBondDefault = npl.totalBondDefault,
      firmDeaths = npl.firmDeaths,
      intIncome = npl.intIncome,
      corpBondAbsorption = bonded.corpBondAbsorption,
      actualBondIssuance = bonded.actualBondIssuance,
      netMigration = in.labor.newImmig.monthlyInflow - in.labor.newImmig.monthlyOutflow,
      perBankNewLoans = perBankNewLoansWithRevert,
      sumFirmPrincipal = flows.principalRepaid,
      perBankFirmPrincipal = perBankFirmPrincipal,
      perBankNplDebt = npl.perBankNplDebt,
      perBankIntIncome = npl.perBankIntIncome,
      perBankWorkers = npl.perBankWorkers,
      lendingRates = lending.rates,
      sumInvestmentCreditDemand = flows.investmentCreditDemand,
      sumInvestmentCreditApproved = flows.investmentCreditApproved,
      sumInvestmentCreditRejected = flows.investmentCreditRejected,
      sumTechCreditDemand = flows.techCreditDemand,
      sumTechCreditApproved = flows.techCreditApproved,
      sumTechCreditRejected = flows.techCreditRejected,
      sumTechSelectedCreditDemand = flows.techSelectedCreditDemand,
      sumTechSelectedCreditApproved = flows.techSelectedCreditApproved,
      sumTechSelectedCreditRejected = flows.techSelectedCreditRejected,
      sumTechCandidateCreditDemand = flows.techCandidateCreditDemand,
      sumTechCandidateCreditApproved = flows.techCandidateCreditApproved,
      sumTechCandidateCreditRejected = flows.techCandidateCreditRejected,
      sumCreditRejectedByReason = flows.creditRejectedByReason,
      postFirmCrossSectorHires = crossSectorHires,
      postFirmHires = hires,
      postFirmHireCapacity = hireCapacity,
      markupInflation = markupInflation,
      sumRealizedPostTaxProfit = fp.outcomes.foldLeft(PLN.Zero)(_ + _.realizedPostTaxProfit),
      sumStateOwnedPostTaxProfit = fp.outcomes.filter(_.firm.stateOwned).foldLeft(PLN.Zero)((acc, o) => acc + o.realizedPostTaxProfit),
      decisionTraces = decisionTraces,
      ledgerFinancialState = ledgerAfterFirmStocks,
    )

  private def automationTechLoanTotal(fp: FirmProcessingResult, bonded: BondAbsorptionResult): PLN =
    fp.outcomes.iterator
      .map: outcome =>
        FirmFinancing.automationTechLoanAmount(
          techBankLoan = outcome.techBankLoan,
          techBondAmt = outcome.techBondAmt,
          bondAmt = outcome.bondAmt,
          revertedBond = bonded.bondReversionByFirm.getOrElse(outcome.firm.id, PLN.Zero),
        )
      .sumPln

  private def finalizedDecisionTraces(
      fp: FirmProcessingResult,
      bonded: BondAbsorptionResult,
      closingFirms: Vector[Firm.State],
      closingFinancialStocks: Vector[Firm.FinancialStocks],
  )(using p: SimParams): Vector[Firm.DecisionTrace] =
    if closingFirms.length != closingFinancialStocks.length then
      throw IllegalStateException(
        s"FirmEconomics.finalizedDecisionTraces requires aligned closing firms and stocks, got ${closingFirms.length} firms and ${closingFinancialStocks.length} stock rows",
      )

    val closingFirmById       = closingFirms.map(firm => firm.id -> firm).toMap
    val closingStocksByFirmId = closingFirms.zip(closingFinancialStocks).map((firm, stocks) => firm.id -> stocks).toMap

    fp.outcomes.map { outcome =>
      val firmId        = outcome.firm.id
      val closingFirm   = closingFirmById.getOrElse(
        firmId,
        throw IllegalStateException(s"FirmEconomics.finalizedDecisionTraces missing closing firm for firm ${firmId.toInt}"),
      )
      val closingStocks = closingStocksByFirmId.getOrElse(
        firmId,
        throw IllegalStateException(s"FirmEconomics.finalizedDecisionTraces missing closing stocks for firm ${firmId.toInt}"),
      )
      val revertedLoan  = bonded.bondReversionByFirm.getOrElse(firmId, PLN.Zero)
      val trace         = outcome.decisionTrace.getOrElse:
        throw IllegalStateException(s"FirmEconomics.finalizedDecisionTraces missing decision trace for firm ${firmId.toInt}")
      val finalLoan     = outcome.finalLoan + revertedLoan
      val finalTechLoan = FirmFinancing.automationTechLoanAmount(
        techBankLoan = outcome.techBankLoan,
        techBondAmt = outcome.techBondAmt,
        bondAmt = outcome.bondAmt,
        revertedBond = revertedLoan,
      )
      val components    = FirmDecisionTraceSupport.traceLoanComponents(trace, finalLoan = finalLoan, finalTechCreditAmount = finalTechLoan)
      trace.copy(
        closingTech = closingFirm.tech,
        cashAfter = closingStocks.cash,
        firmLoanAfter = closingStocks.firmLoan,
        digitalReadinessAfter = closingFirm.digitalReadiness,
        workersAfter = Firm.workerCount(closingFirm),
        newLoan = finalLoan,
        techCreditAmount = components.techCreditAmount,
        investmentCreditAmount = components.investmentCreditAmount,
      )
    }
