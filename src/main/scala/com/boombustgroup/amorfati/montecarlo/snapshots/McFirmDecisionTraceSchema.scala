package com.boombustgroup.amorfati.montecarlo.snapshots

import com.boombustgroup.amorfati.agents.{Banking, BankruptReason, Firm, TechState}
import com.boombustgroup.amorfati.agents.banking.BankPortfolioChoiceAudit
import com.boombustgroup.amorfati.montecarlo.io.McTsvSchema
import com.boombustgroup.amorfati.types.*

private[montecarlo] object McFirmDecisionTraceSchema:

  final case class Row(
      runId: String,
      seed: Long,
      month: Int,
      trace: Firm.DecisionTrace,
  )

  private val columns: Vector[(String, Row => String)] = Vector[(String, Row => String)](
    "RunId"                              -> (row => text(row.runId)),
    "Seed"                               -> (row => row.seed.toString),
    "Month"                              -> (row => row.month.toString),
    "FirmId"                             -> (row => row.trace.firmId.toInt.toString),
    "OpeningTechState"                   -> (row => techState(row.trace.openingTech)),
    "ClosingTechState"                   -> (row => techState(row.trace.closingTech)),
    "DecisionType"                       -> (row => row.trace.decisionType.tsvValue),
    "BankruptcyReason"                   -> (row => row.trace.bankruptcyReason.fold("")(reason => text(bankruptcyReasonName(reason)))),
    "CashBefore"                         -> (row => row.trace.cashBefore.format(2)),
    "CashAfter"                          -> (row => row.trace.cashAfter.format(2)),
    "FirmLoanBefore"                     -> (row => row.trace.firmLoanBefore.format(2)),
    "FirmLoanAfter"                      -> (row => row.trace.firmLoanAfter.format(2)),
    "DigitalReadinessBefore"             -> (row => row.trace.digitalReadinessBefore.format(6)),
    "DigitalReadinessAfter"              -> (row => row.trace.digitalReadinessAfter.format(6)),
    "WorkersBefore"                      -> (row => row.trace.workersBefore.toString),
    "WorkersAfter"                       -> (row => row.trace.workersAfter.toString),
    "Capex"                              -> (row => row.trace.capex.format(2)),
    "NewLoan"                            -> (row => row.trace.newLoan.format(2)),
    "TechCreditDecisionType"             -> (row => row.trace.techCreditDecisionType.fold("")(_.tsvValue)),
    "TechCreditSource"                   -> (row => row.trace.techCreditSource.fold("")(_.tsvValue)),
    "TechCreditNeed"                     -> (row => row.trace.techCreditNeed.format(2)),
    "TechCreditAmount"                   -> (row => row.trace.techCreditAmount.format(2)),
    "DownPayment"                        -> (row => row.trace.downPayment.fold("")(_.format(2))),
    "BankId"                             -> (row => row.trace.bankId.toInt.toString),
    "LendingRate"                        -> (row => row.trace.lendingRate.format(6)),
    "SelectedBankApproval"               -> (row => row.trace.selectedBankApproval.fold("")(_.toString)),
    "SelectedBankApprovalProbability"    -> (row => row.trace.selectedBankApprovalProbability.fold("")(_.format(6))),
    "SelectedBankApprovalRoll"           -> (row => row.trace.selectedBankApprovalRoll.fold("")(_.format(6))),
    "SelectedBankRejectionReason"        -> (row => creditRejectionReason(row.trace.selectedBankApprovalAudit)),
    "SelectedBankProjectedCar"           -> (row => auditMultiplier(row.trace.selectedBankApprovalAudit.projectedCar)),
    "SelectedBankMinCar"                 -> (row => auditMultiplier(row.trace.selectedBankApprovalAudit.minCar)),
    "SelectedBankManagementCar"          -> (row => auditMultiplier(row.trace.selectedBankApprovalAudit.managementCarTarget)),
    "SelectedBankCapitalThrottle"        -> (row => auditShare(row.trace.selectedBankApprovalAudit.capitalThrottle)),
    "SelectedBankLcr"                    -> (row => auditMultiplier(row.trace.selectedBankApprovalAudit.lcr)),
    "SelectedBankLcrMin"                 -> (row => auditMultiplier(row.trace.selectedBankApprovalAudit.lcrMin)),
    "SelectedBankNsfr"                   -> (row => auditMultiplier(row.trace.selectedBankApprovalAudit.nsfr)),
    "SelectedBankNsfrMin"                -> (row => auditMultiplier(row.trace.selectedBankApprovalAudit.nsfrMin)),
    "FullAiFeasible"                     -> (row => row.trace.fullAiFeasible.fold("")(_.toString)),
    "HybridFeasible"                     -> (row => row.trace.hybridFeasible.fold("")(_.toString)),
    "FullAiAdoptionProbability"          -> (row => row.trace.fullAiAdoptionProbability.fold("")(_.format(6))),
    "HybridAdoptionProbability"          -> (row => row.trace.hybridAdoptionProbability.fold("")(_.format(6))),
    "AdoptionRoll"                       -> (row => row.trace.adoptionRoll.fold("")(_.format(6))),
    "FullAiBankApproval"                 -> (row => row.trace.fullAiBankApproval.fold("")(_.toString)),
    "FullAiBankApprovalProbability"      -> (row => row.trace.fullAiBankApprovalProbability.fold("")(_.format(6))),
    "FullAiBankApprovalRoll"             -> (row => row.trace.fullAiBankApprovalRoll.fold("")(_.format(6))),
    "FullAiBankRejectionReason"          -> (row => creditRejectionReason(row.trace.fullAiBankApprovalAudit)),
    "FullAiBankProjectedCar"             -> (row => auditMultiplier(row.trace.fullAiBankApprovalAudit.projectedCar)),
    "FullAiBankMinCar"                   -> (row => auditMultiplier(row.trace.fullAiBankApprovalAudit.minCar)),
    "FullAiBankManagementCar"            -> (row => auditMultiplier(row.trace.fullAiBankApprovalAudit.managementCarTarget)),
    "FullAiBankCapitalThrottle"          -> (row => auditShare(row.trace.fullAiBankApprovalAudit.capitalThrottle)),
    "FullAiBankLcr"                      -> (row => auditMultiplier(row.trace.fullAiBankApprovalAudit.lcr)),
    "FullAiBankLcrMin"                   -> (row => auditMultiplier(row.trace.fullAiBankApprovalAudit.lcrMin)),
    "FullAiBankNsfr"                     -> (row => auditMultiplier(row.trace.fullAiBankApprovalAudit.nsfr)),
    "FullAiBankNsfrMin"                  -> (row => auditMultiplier(row.trace.fullAiBankApprovalAudit.nsfrMin)),
    "HybridBankApproval"                 -> (row => row.trace.hybridBankApproval.fold("")(_.toString)),
    "HybridBankApprovalProbability"      -> (row => row.trace.hybridBankApprovalProbability.fold("")(_.format(6))),
    "HybridBankApprovalRoll"             -> (row => row.trace.hybridBankApprovalRoll.fold("")(_.format(6))),
    "HybridBankRejectionReason"          -> (row => creditRejectionReason(row.trace.hybridBankApprovalAudit)),
    "HybridBankProjectedCar"             -> (row => auditMultiplier(row.trace.hybridBankApprovalAudit.projectedCar)),
    "HybridBankMinCar"                   -> (row => auditMultiplier(row.trace.hybridBankApprovalAudit.minCar)),
    "HybridBankManagementCar"            -> (row => auditMultiplier(row.trace.hybridBankApprovalAudit.managementCarTarget)),
    "HybridBankCapitalThrottle"          -> (row => auditShare(row.trace.hybridBankApprovalAudit.capitalThrottle)),
    "HybridBankLcr"                      -> (row => auditMultiplier(row.trace.hybridBankApprovalAudit.lcr)),
    "HybridBankLcrMin"                   -> (row => auditMultiplier(row.trace.hybridBankApprovalAudit.lcrMin)),
    "HybridBankNsfr"                     -> (row => auditMultiplier(row.trace.hybridBankApprovalAudit.nsfr)),
    "HybridBankNsfrMin"                  -> (row => auditMultiplier(row.trace.hybridBankApprovalAudit.nsfrMin)),
    "ImplementationFailureProbability"   -> (row => row.trace.implementationFailureProbability.fold("")(_.format(6))),
    "ImplementationRoll"                 -> (row => row.trace.implementationRoll.fold("")(_.format(6))),
    "UpgradeEfficiencyDraw"              -> (row => row.trace.upgradeEfficiencyDraw.fold("")(_.format(6))),
    "UpgradeEfficiencyMultiplier"        -> (row => row.trace.upgradeEfficiencyMultiplier.fold("")(_.format(6))),
    "InvestmentCreditNeed"               -> (row => row.trace.investmentCreditNeed.fold("")(_.format(2))),
    "InvestmentCreditAmount"             -> (row => row.trace.investmentCreditAmount.fold("")(_.format(2))),
    "InvestmentBankApproval"             -> (row => row.trace.investmentBankApproval.fold("")(_.toString)),
    "InvestmentBankApprovalProbability"  -> (row => row.trace.investmentBankApprovalProbability.fold("")(_.format(6))),
    "InvestmentBankApprovalRoll"         -> (row => row.trace.investmentBankApprovalRoll.fold("")(_.format(6))),
    "InvestmentBankRejectionReason"      -> (row => creditRejectionReason(row.trace.investmentBankApprovalAudit)),
    "InvestmentBankProjectedCar"         -> (row => auditMultiplier(row.trace.investmentBankApprovalAudit.projectedCar)),
    "InvestmentBankMinCar"               -> (row => auditMultiplier(row.trace.investmentBankApprovalAudit.minCar)),
    "InvestmentBankManagementCar"        -> (row => auditMultiplier(row.trace.investmentBankApprovalAudit.managementCarTarget)),
    "InvestmentBankCapitalThrottle"      -> (row => auditShare(row.trace.investmentBankApprovalAudit.capitalThrottle)),
    "InvestmentBankLcr"                  -> (row => auditMultiplier(row.trace.investmentBankApprovalAudit.lcr)),
    "InvestmentBankLcrMin"               -> (row => auditMultiplier(row.trace.investmentBankApprovalAudit.lcrMin)),
    "InvestmentBankNsfr"                 -> (row => auditMultiplier(row.trace.investmentBankApprovalAudit.nsfr)),
    "InvestmentBankNsfrMin"              -> (row => auditMultiplier(row.trace.investmentBankApprovalAudit.nsfrMin)),
    "DigitalInvestProbability"           -> (row => row.trace.digitalInvestProbability.fold("")(_.format(6))),
    "DigitalInvestRoll"                  -> (row => row.trace.digitalInvestRoll.fold("")(_.format(6))),
    "LaborAdjustmentResidualProbability" -> (row => row.trace.laborAdjustmentResidualProbability.fold("")(_.format(6))),
    "LaborAdjustmentResidualRoll"        -> (row => row.trace.laborAdjustmentResidualRoll.fold("")(_.format(6))),
  ) ++ portfolioChoiceColumns("SelectedBank", row => row.trace.selectedBankApprovalAudit)
    ++ portfolioChoiceColumns("FullAiBank", row => row.trace.fullAiBankApprovalAudit)
    ++ portfolioChoiceColumns("HybridBank", row => row.trace.hybridBankApprovalAudit)
    ++ portfolioChoiceColumns("InvestmentBank", row => row.trace.investmentBankApprovalAudit)

  val header: String =
    columns.map(_._1).mkString("\t")

  val tsvSchema: McTsvSchema[Row] =
    McTsvSchema(
      header = header,
      render = row => columns.map(_._2(row)).mkString("\t"),
    )

  def rows(
      runId: String,
      seed: Long,
      month: Int,
      traces: Vector[Firm.DecisionTrace],
      selection: McFirmDecisionTraceSelection,
  ): Vector[Row] =
    traces
      .filter(trace => selection.includes(trace.firmId.toInt))
      .map(trace => Row(runId, seed, month, trace))

  private def techState(tech: TechState): String =
    tech match
      case TechState.Traditional(_) => "Traditional"
      case TechState.Hybrid(_, _)   => "Hybrid"
      case TechState.Automated(_)   => "Automated"
      case TechState.Bankrupt(_)    => "Bankrupt"

  private def bankruptcyReasonName(reason: BankruptReason): String =
    reason match
      case BankruptReason.AiDebtTrap          => "AiDebtTrap"
      case BankruptReason.HybridInsolvency    => "HybridInsolvency"
      case BankruptReason.AiImplFailure       => "AiImplFailure"
      case BankruptReason.HybridImplFailure   => "HybridImplFailure"
      case BankruptReason.LaborCostInsolvency => "LaborCostInsolvency"
      case BankruptReason.Other(msg)          => s"Other(${text(msg)})"

  private def text(value: String): String =
    value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

  private def creditRejectionReason(audit: Banking.CreditApprovalAudit): String =
    audit.rejectionReason.fold("")(reason => text(reason.diagnosticCode))

  private def auditMultiplier(value: Option[Multiplier]): String =
    value.fold("")(_.format(6))

  private def auditShare(value: Option[Share]): String =
    value.fold("")(_.format(6))

  private def portfolioChoiceColumns(prefix: String, audit: Row => Banking.CreditApprovalAudit): Vector[(String, Row => String)] =
    Vector(
      s"${prefix}PortfolioRiskAdjustedLoanReturn" -> (row => portfolioRate(audit(row), _.riskAdjustedLoanReturn)),
      s"${prefix}PortfolioRiskAdjustedBondReturn" -> (row => portfolioRate(audit(row), _.riskAdjustedBondReturn)),
      s"${prefix}PortfolioWedge"                  -> (row => portfolioRate(audit(row), _.wedge)),
      s"${prefix}PortfolioExpectedLossComponent"  -> (row => portfolioRate(audit(row), _.wedgeExpectedLossComponent)),
      s"${prefix}PortfolioCapitalComponent"       -> (row => portfolioRate(audit(row), _.wedgeCapitalComponent)),
      s"${prefix}PortfolioLevyComponent"          -> (row => portfolioRate(audit(row), _.wedgeLevyComponent)),
      s"${prefix}PortfolioFundingComponent"       -> (row => portfolioRate(audit(row), _.wedgeFundingComponent)),
      s"${prefix}PortfolioPriceShare"             -> (row => portfolioShare(audit(row), _.priceShareOfWedge)),
      s"${prefix}PortfolioPriceContribution"      -> (row => portfolioRate(audit(row), _.wedgePriceContribution)),
      s"${prefix}PortfolioQuantityThrottle"       -> (row => portfolioShare(audit(row), _.wedgeQuantityThrottle)),
    )

  private def portfolioRate(audit: Banking.CreditApprovalAudit, select: BankPortfolioChoiceAudit => Rate): String =
    audit.portfolioChoice.fold("")(choice => select(choice).format(6))

  private def portfolioShare(audit: Banking.CreditApprovalAudit, select: BankPortfolioChoiceAudit => Share): String =
    audit.portfolioChoice.fold("")(choice => select(choice).format(6))
