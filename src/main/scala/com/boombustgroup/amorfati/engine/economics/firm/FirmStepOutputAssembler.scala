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

    val totals         = FirmOutputAggregation.derive(fp, bonded, lending.nBanks)
    val decisionTraces =
      if fp.traceDecisions then finalizedDecisionTraces(fp, bonded, ioFirms, firmFinancialStocks)
      else Vector.empty

    StepOutput.fromSections(
      state = FirmOutputState(
        ioFirms = ioFirms,
        households = households,
        decisionTraces = decisionTraces,
        ledgerFinancialState = ledgerAfterFirmStocks,
      ),
      flows = FirmOutputFlowTotals(
        sumTax = flows.tax,
        sumCapex = flows.capex,
        sumTechImp = flows.techImp,
        sumNewLoans = bonded.sumNewLoans,
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
        sumFirmPrincipal = flows.principalRepaid,
      ),
      automation = FirmOutputAutomationTotals(
        techCapex = flows.capex,
        techImports = flows.techImp,
        techLoans = totals.automationTechLoans,
        upgradeFailures = totals.automationUpgradeFailures,
        aiDebtTrap = totals.automationAiDebtTrap,
        newFullAi = totals.automationNewFullAi,
        newHybrid = totals.automationNewHybrid,
      ),
      banking = FirmOutputBankingTotals(
        nplNew = npl.nplNew,
        nplLoss = npl.nplLoss,
        totalBondDefault = npl.totalBondDefault,
        firmDeaths = npl.firmDeaths,
        intIncome = npl.intIncome,
        corpBondAbsorption = bonded.corpBondAbsorption,
        actualBondIssuance = bonded.actualBondIssuance,
        perBankNewLoans = totals.perBankNewLoans,
        perBankFirmPrincipal = totals.perBankFirmPrincipal,
        perBankNplDebt = npl.perBankNplDebt,
        perBankIntIncome = npl.perBankIntIncome,
        perBankWorkers = npl.perBankWorkers,
        lendingRates = lending.rates,
      ),
      credit = FirmOutputCreditTotals(
        investmentDemand = flows.investmentCreditDemand,
        investmentApproved = flows.investmentCreditApproved,
        investmentRejected = flows.investmentCreditRejected,
        techDemand = flows.techCreditDemand,
        techApproved = flows.techCreditApproved,
        techRejected = flows.techCreditRejected,
        techSelectedDemand = flows.techSelectedCreditDemand,
        techSelectedApproved = flows.techSelectedCreditApproved,
        techSelectedRejected = flows.techSelectedCreditRejected,
        techCandidateDemand = flows.techCandidateCreditDemand,
        techCandidateApproved = flows.techCandidateCreditApproved,
        techCandidateRejected = flows.techCandidateCreditRejected,
        rejectedByReason = flows.creditRejectedByReason,
      ),
      labor = FirmOutputLaborTotals(
        netMigration = in.labor.newImmig.monthlyInflow - in.labor.newImmig.monthlyOutflow,
        crossSectorHires = crossSectorHires,
        hires = hires,
        hireCapacity = hireCapacity,
      ),
      profitability = FirmOutputProfitability(
        markupInflation = markupInflation,
        realizedPostTaxProfit = totals.realizedPostTaxProfit,
        stateOwnedPostTaxProfit = totals.stateOwnedPostTaxProfit,
      ),
    )

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
