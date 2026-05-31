package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.agents.{Banking, BankruptReason, TechState}
import com.boombustgroup.amorfati.agents.Firm.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Builds immutable firm decision traces from already-computed decision data.
  *
  * Trace construction is deliberately side-effect free and must not draw from
  * randomness; it only projects opening state, selected decision, audit fields,
  * and closing result into an exportable record.
  */
private[agents] object FirmDecisionTraceBuilder:
  private[agents] def buildDecisionTrace(
      openingFirm: State,
      openingStocks: FinancialStocks,
      lendingRate: Rate,
      decision: DecisionWithAudit,
      result: Result,
  )(using p: SimParams): DecisionTrace =
    val d = decision.decision
    DecisionTrace(
      firmId = openingFirm.id,
      openingTech = openingFirm.tech,
      closingTech = result.firm.tech,
      decisionType = decisionType(d),
      bankruptcyReason = bankruptcyReason(d).orElse(bankruptcyReason(result.firm.tech)),
      sector = openingFirm.sector,
      foreignOwned = openingFirm.foreignOwned,
      stateOwned = openingFirm.stateOwned,
      cashBefore = openingStocks.cash,
      cashAfter = result.financialStocks.cash,
      firmLoanBefore = openingStocks.firmLoan,
      firmLoanAfter = result.financialStocks.firmLoan,
      realizedPostTaxProfit = result.realizedPostTaxProfit,
      signedRealizedPostTaxProfit = result.signedRealizedPostTaxProfit,
      grossInvestment = result.grossInvestment,
      principalRepaid = result.principalRepaid,
      digitalReadinessBefore = openingFirm.digitalReadiness,
      digitalReadinessAfter = result.firm.digitalReadiness,
      workersBefore = workerCount(openingFirm),
      workersAfter = workerCount(result.firm),
      capex = result.capexSpent,
      newLoan = result.newLoan,
      techCreditDecisionType = decision.audit.techCreditDecisionType,
      techCreditSource = decision.audit.techCreditSource,
      techCreditNeed = decision.audit.techCreditNeed.getOrElse(FirmCreditAudit.decisionCreditNeed(d)),
      techCreditAmount = result.techNewLoan,
      downPayment = downPayment(d),
      bankId = openingFirm.bankId,
      lendingRate = lendingRate,
      selectedBankApproval = decision.audit.selectedBankApproval,
      selectedBankApprovalProbability = decision.audit.selectedBankApprovalProbability,
      selectedBankApprovalRoll = decision.audit.selectedBankApprovalRoll,
      selectedBankApprovalAudit = decision.audit.selectedBankApprovalAudit,
      fullAiFeasible = decision.audit.fullAiFeasible,
      hybridFeasible = decision.audit.hybridFeasible,
      fullAiAdoptionProbability = decision.audit.fullAiAdoptionProbability,
      hybridAdoptionProbability = decision.audit.hybridAdoptionProbability,
      adoptionRoll = decision.audit.adoptionRoll,
      fullAiBankApproval = decision.audit.fullAiBankApproval,
      fullAiBankApprovalProbability = decision.audit.fullAiBankApprovalProbability,
      fullAiBankApprovalRoll = decision.audit.fullAiBankApprovalRoll,
      fullAiBankApprovalAudit = decision.audit.fullAiBankApprovalAudit,
      hybridBankApproval = decision.audit.hybridBankApproval,
      hybridBankApprovalProbability = decision.audit.hybridBankApprovalProbability,
      hybridBankApprovalRoll = decision.audit.hybridBankApprovalRoll,
      hybridBankApprovalAudit = decision.audit.hybridBankApprovalAudit,
      implementationFailureProbability = decision.audit.implementationFailureProbability,
      implementationRoll = decision.audit.implementationRoll,
      upgradeEfficiencyDraw = decision.audit.upgradeEfficiencyDraw,
      upgradeEfficiencyMultiplier = decision.audit.upgradeEfficiencyMultiplier,
      investmentCreditNeed = None,
      investmentCreditAmount = None,
      investmentBankApproval = None,
      investmentBankApprovalProbability = None,
      investmentBankApprovalRoll = None,
      investmentBankApprovalAudit = Banking.CreditApprovalAudit.empty,
      digitalInvestProbability = decision.audit.digitalInvestProbability,
      digitalInvestRoll = decision.audit.digitalInvestRoll,
      laborAdjustmentResidualProbability = decision.audit.laborAdjustmentResidualProbability,
      laborAdjustmentResidualRoll = decision.audit.laborAdjustmentResidualRoll,
    )

  private[agents] def refreshDecisionTraceClosing(trace: DecisionTrace, result: Result)(using p: SimParams): DecisionTrace =
    trace.copy(
      closingTech = result.firm.tech,
      cashAfter = result.financialStocks.cash,
      firmLoanAfter = result.financialStocks.firmLoan,
      realizedPostTaxProfit = result.realizedPostTaxProfit,
      signedRealizedPostTaxProfit = result.signedRealizedPostTaxProfit,
      grossInvestment = result.grossInvestment,
      principalRepaid = result.principalRepaid,
      digitalReadinessAfter = result.firm.digitalReadiness,
      workersAfter = workerCount(result.firm),
      capex = result.capexSpent,
      newLoan = result.newLoan,
      techCreditAmount = result.techNewLoan,
    )

  private def decisionType(decision: Decision): DecisionTrace.DecisionType =
    decision match
      case Decision.StayBankrupt                    => DecisionTrace.DecisionType.Bankrupt
      case Decision.Survive(_, _, _)                => DecisionTrace.DecisionType.Survive
      case Decision.GoBankrupt(_, _, _)             => DecisionTrace.DecisionType.Bankrupt
      case Decision.Upgrade(_, newTech, _, _, _, _) =>
        newTech match
          case _: TechState.Automated => DecisionTrace.DecisionType.FullAiUpgrade
          case _: TechState.Hybrid    => DecisionTrace.DecisionType.HybridUpgrade
          case _                      => DecisionTrace.DecisionType.Survive
      case Decision.UpgradeFailed(_, _, _, _, _)    => DecisionTrace.DecisionType.UpgradeFailed
      case Decision.Downsize(_, _, _, _, _)         => DecisionTrace.DecisionType.Downsize
      case Decision.Upsize(_, _, _, _, _)           => DecisionTrace.DecisionType.Upsize
      case Decision.DigiInvest(_, _, _)             => DecisionTrace.DecisionType.DigiInvest

  private def bankruptcyReason(decision: Decision): Option[BankruptReason] =
    decision match
      case Decision.GoBankrupt(_, _, reason)          => Some(reason)
      case Decision.UpgradeFailed(_, reason, _, _, _) => Some(reason)
      case _                                          => None

  private def bankruptcyReason(tech: TechState): Option[BankruptReason] =
    tech match
      case TechState.Bankrupt(reason) => Some(reason)
      case _                          => None

  private def downPayment(decision: Decision): Option[PLN] =
    decision match
      case Decision.Upgrade(_, _, _, _, downPayment, _) => Some(downPayment)
      case Decision.UpgradeFailed(_, _, _, _, down)     => Some(down)
      case _                                            => None
