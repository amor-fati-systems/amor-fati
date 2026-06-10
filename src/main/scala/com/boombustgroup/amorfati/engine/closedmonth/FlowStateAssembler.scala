package com.boombustgroup.amorfati.engine.closedmonth

import com.boombustgroup.amorfati.engine.{FlowState, MonthClosingInput}
import com.boombustgroup.amorfati.engine.mechanisms.PopulationLifecycleTransitions

/** Maps month-closing inputs into the diagnostic flow-state surface. */
object FlowStateAssembler:

  private[closedmonth] def build(
      closingInput: MonthClosingInput,
      lifecycle: PopulationLifecycleTransitions.Result,
  ): FlowState =
    val in       = closingInput.closingState
    val informal = closingInput.mechanisms.informalEconomy
    FlowState(
      monthlyGdpProxy = in.priceEquity.gdp,
      sectorOutputs = in.priceEquity.realizedSectorOutputs,
      ioFlows = in.firm.totalIoPaid,
      fdiProfitShifting = in.firm.sumProfitShifting,
      fdiRepatriation = in.firm.sumFdiRepatriation,
      fdiCitLoss = in.openEconomy.external.fdiCitLoss,
      diasporaRemittanceInflow = in.householdFinancial.diasporaInflow,
      tourismExport = in.householdFinancial.tourismExport,
      tourismImport = in.householdFinancial.tourismImport,
      aggInventoryStock = in.priceEquity.aggInventoryStock,
      aggInventoryChange = in.priceEquity.aggInventoryChange,
      aggEnergyCost = in.firm.sumEnergyCost,
      automationTechCapex = in.firm.automationTechCapex,
      automationTechImports = in.firm.automationTechImports,
      automationTechLoans = in.firm.automationTechLoans,
      automationUpgradeFailures = in.firm.automationUpgradeFailures,
      automationAiDebtTrap = in.firm.automationAiDebtTrap,
      automationNewFullAi = in.firm.automationNewFullAi + lifecycle.automationTransitions.newFullAi,
      automationNewHybrid = in.firm.automationNewHybrid + lifecycle.automationTransitions.newHybrid,
      firmNewLoans = in.firm.sumNewLoans,
      firmPrincipalRepaid = in.firm.sumFirmPrincipal,
      firmGrossDefault = in.firm.nplNew,
      firmNplLoss = in.firm.nplLoss,
      firmInvestmentCreditDemand = in.firm.sumInvestmentCreditDemand,
      firmInvestmentCreditApproved = in.firm.sumInvestmentCreditApproved,
      firmInvestmentCreditRejected = in.firm.sumInvestmentCreditRejected,
      firmTechCreditDemand = in.firm.sumTechCreditDemand,
      firmTechCreditApproved = in.firm.sumTechCreditApproved,
      firmTechCreditRejected = in.firm.sumTechCreditRejected,
      firmTechSelectedCreditDemand = in.firm.sumTechSelectedCreditDemand,
      firmTechSelectedCreditApproved = in.firm.sumTechSelectedCreditApproved,
      firmTechSelectedCreditRejected = in.firm.sumTechSelectedCreditRejected,
      firmTechCandidateCreditDemand = in.firm.sumTechCandidateCreditDemand,
      firmTechCandidateCreditApproved = in.firm.sumTechCandidateCreditApproved,
      firmTechCandidateCreditRejected = in.firm.sumTechCandidateCreditRejected,
      firmCreditRejectedByReason = in.firm.sumCreditRejectedByReason,
      firmBirths = lifecycle.births,
      firmDeaths = in.firm.firmDeaths,
      netFirmBirths = lifecycle.netBirths,
      taxEvasionLoss = informal.taxEvasionLoss,
      realizedTaxShadowShare = informal.realizedTaxShadowShare,
      bailInLoss = in.banking.bailInLoss,
      bfgLevyTotal = in.banking.bfgLevy,
      polishBankLevyTaxTotal = in.banking.polishBankLevyTax,
      bankCapital = in.banking.bankCapitalDiagnostics,
      bankFailure = in.banking.bankFailureDiagnostics,
      bankResolution = in.banking.bankResolutionDiagnostics,
      bankReconciliation = in.banking.bankReconciliationDiagnostics,
      bankEcl = in.banking.bankEclDiagnostics,
    )
