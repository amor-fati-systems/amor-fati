package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.agents.Firm.*
import com.boombustgroup.amorfati.types.*

/** Internal credit-audit surface used by firm decisions and diagnostics.
  *
  * The object keeps candidate-level aggregate credit diagnostics separate from
  * singleton trace fields so rejection attribution does not depend on merge
  * order.
  */
private[agents] final case class DecisionAudit(
    selectedBankApproval: Option[Boolean] = None,
    selectedBankApprovalProbability: Option[Share] = None,
    selectedBankApprovalRoll: Option[Share] = None,
    selectedBankApprovalAudit: Banking.CreditApprovalAudit = Banking.CreditApprovalAudit.empty,
    fullAiFeasible: Option[Boolean] = None,
    hybridFeasible: Option[Boolean] = None,
    fullAiAdoptionProbability: Option[Share] = None,
    hybridAdoptionProbability: Option[Share] = None,
    adoptionRoll: Option[Share] = None,
    fullAiBankApproval: Option[Boolean] = None,
    fullAiBankApprovalProbability: Option[Share] = None,
    fullAiBankApprovalRoll: Option[Share] = None,
    fullAiBankApprovalAudit: Banking.CreditApprovalAudit = Banking.CreditApprovalAudit.empty,
    hybridBankApproval: Option[Boolean] = None,
    hybridBankApprovalProbability: Option[Share] = None,
    hybridBankApprovalRoll: Option[Share] = None,
    hybridBankApprovalAudit: Banking.CreditApprovalAudit = Banking.CreditApprovalAudit.empty,
    techCreditDecisionType: Option[DecisionTrace.DecisionType] = None,
    techCreditSource: Option[DecisionTrace.TechCreditSource] = None,
    techCreditNeed: Option[PLN] = None,
    techCandidateCreditDemand: PLN = PLN.Zero,
    techCandidateCreditApproved: PLN = PLN.Zero,
    techCandidateCreditRejected: PLN = PLN.Zero,
    techCandidateCreditRejectionBreakdown: CreditRejectionBreakdown = CreditRejectionBreakdown.zero,
    implementationFailureProbability: Option[Share] = None,
    implementationRoll: Option[Share] = None,
    upgradeEfficiencyDraw: Option[Scalar] = None,
    upgradeEfficiencyMultiplier: Option[Multiplier] = None,
    digitalInvestProbability: Option[Share] = None,
    digitalInvestRoll: Option[Share] = None,
    laborAdjustmentResidualProbability: Option[Share] = None,
    laborAdjustmentResidualRoll: Option[Share] = None,
):
  def merge(next: DecisionAudit): DecisionAudit =
    DecisionAudit(
      selectedBankApproval = next.selectedBankApproval.orElse(selectedBankApproval),
      selectedBankApprovalProbability = next.selectedBankApprovalProbability.orElse(selectedBankApprovalProbability),
      selectedBankApprovalRoll = next.selectedBankApprovalRoll.orElse(selectedBankApprovalRoll),
      selectedBankApprovalAudit = next.selectedBankApprovalAudit.orElse(selectedBankApprovalAudit),
      fullAiFeasible = next.fullAiFeasible.orElse(fullAiFeasible),
      hybridFeasible = next.hybridFeasible.orElse(hybridFeasible),
      fullAiAdoptionProbability = next.fullAiAdoptionProbability.orElse(fullAiAdoptionProbability),
      hybridAdoptionProbability = next.hybridAdoptionProbability.orElse(hybridAdoptionProbability),
      adoptionRoll = next.adoptionRoll.orElse(adoptionRoll),
      fullAiBankApproval = next.fullAiBankApproval.orElse(fullAiBankApproval),
      fullAiBankApprovalProbability = next.fullAiBankApprovalProbability.orElse(fullAiBankApprovalProbability),
      fullAiBankApprovalRoll = next.fullAiBankApprovalRoll.orElse(fullAiBankApprovalRoll),
      fullAiBankApprovalAudit = next.fullAiBankApprovalAudit.orElse(fullAiBankApprovalAudit),
      hybridBankApproval = next.hybridBankApproval.orElse(hybridBankApproval),
      hybridBankApprovalProbability = next.hybridBankApprovalProbability.orElse(hybridBankApprovalProbability),
      hybridBankApprovalRoll = next.hybridBankApprovalRoll.orElse(hybridBankApprovalRoll),
      hybridBankApprovalAudit = next.hybridBankApprovalAudit.orElse(hybridBankApprovalAudit),
      techCreditDecisionType = next.techCreditDecisionType.orElse(techCreditDecisionType),
      techCreditSource = next.techCreditSource.orElse(techCreditSource),
      techCreditNeed = next.techCreditNeed.orElse(techCreditNeed),
      techCandidateCreditDemand = techCandidateCreditDemand + next.techCandidateCreditDemand,
      techCandidateCreditApproved = techCandidateCreditApproved + next.techCandidateCreditApproved,
      techCandidateCreditRejected = techCandidateCreditRejected + next.techCandidateCreditRejected,
      techCandidateCreditRejectionBreakdown = techCandidateCreditRejectionBreakdown + next.techCandidateCreditRejectionBreakdown,
      implementationFailureProbability = next.implementationFailureProbability.orElse(implementationFailureProbability),
      implementationRoll = next.implementationRoll.orElse(implementationRoll),
      upgradeEfficiencyDraw = next.upgradeEfficiencyDraw.orElse(upgradeEfficiencyDraw),
      upgradeEfficiencyMultiplier = next.upgradeEfficiencyMultiplier.orElse(upgradeEfficiencyMultiplier),
      digitalInvestProbability = next.digitalInvestProbability.orElse(digitalInvestProbability),
      digitalInvestRoll = next.digitalInvestRoll.orElse(digitalInvestRoll),
      laborAdjustmentResidualProbability = next.laborAdjustmentResidualProbability.orElse(laborAdjustmentResidualProbability),
      laborAdjustmentResidualRoll = next.laborAdjustmentResidualRoll.orElse(laborAdjustmentResidualRoll),
    )

private[agents] final case class DecisionWithAudit(decision: Decision, audit: DecisionAudit)

private[agents] final case class AdjustmentMagnitude(
    value: Int,
    residualProbability: Option[Share],
    residualRoll: Option[Share],
)

/** Upgrade feasibility for one tech path (full-AI or hybrid). */
private[agents] final case class UpgradeCandidate(
    capex: PLN,
    loan: PLN,
    down: PLN,
    profitable: Boolean,
    canPay: Boolean,
    ready: Boolean,
    credit: CreditDecision,
):
  def bankOk: Boolean   = credit.approved
  def feasible: Boolean = profitable && canPay && ready && bankOk

private[agents] object FirmCreditAudit:
  def selectedBankAudit(credit: CreditDecision): DecisionAudit =
    DecisionAudit(
      selectedBankApproval = Some(credit.approved),
      selectedBankApprovalProbability = credit.approvalProbability,
      selectedBankApprovalRoll = credit.approvalRoll,
      selectedBankApprovalAudit = credit.audit,
    )

  def selectedTechBankAudit(
      credit: CreditDecision,
      need: PLN,
      decisionType: DecisionTrace.DecisionType,
      source: DecisionTrace.TechCreditSource,
  ): DecisionAudit =
    selectedBankAudit(credit).copy(
      techCreditDecisionType = Some(decisionType),
      techCreditSource = Some(source),
      techCreditNeed = Some(need),
    )

  def techCandidateCreditAudit(candidate: UpgradeCandidate): DecisionAudit =
    if candidate.profitable && candidate.canPay && candidate.ready then
      val approved = if candidate.bankOk then candidate.loan else PLN.Zero
      val rejected = if candidate.bankOk then PLN.Zero else candidate.loan
      DecisionAudit(
        techCandidateCreditDemand = candidate.loan,
        techCandidateCreditApproved = approved,
        techCandidateCreditRejected = rejected,
        techCandidateCreditRejectionBreakdown = CreditRejectionBreakdown.from(candidate.credit.audit.rejectionReason, rejected),
      )
    else DecisionAudit()

  def rejectedTechCandidateTraceAudit(candidate: UpgradeCandidate, decisionType: DecisionTrace.DecisionType): DecisionAudit =
    if !candidate.bankOk && candidate.profitable && candidate.canPay && candidate.ready then
      selectedTechBankAudit(candidate.credit, candidate.loan, decisionType, DecisionTrace.TechCreditSource.BankRejectedCandidate)
    else DecisionAudit()

  def primaryRejectedTechCandidateTraceAudit(fullAi: UpgradeCandidate, hybrid: UpgradeCandidate): DecisionAudit =
    val candidates = Vector(
      fullAi -> DecisionTrace.DecisionType.FullAiUpgrade,
      hybrid -> DecisionTrace.DecisionType.HybridUpgrade,
    )
    candidates
      .collectFirst:
        case (candidate, decisionType) if !candidate.bankOk && candidate.profitable && candidate.canPay && candidate.ready =>
          selectedTechBankAudit(candidate.credit, candidate.loan, decisionType, DecisionTrace.TechCreditSource.BankRejectedCandidate)
      .getOrElse(DecisionAudit())

  def fullAiBankAudit(credit: CreditDecision): DecisionAudit =
    DecisionAudit(
      fullAiBankApproval = Some(credit.approved),
      fullAiBankApprovalProbability = credit.approvalProbability,
      fullAiBankApprovalRoll = credit.approvalRoll,
      fullAiBankApprovalAudit = credit.audit,
    )

  def hybridBankAudit(credit: CreditDecision): DecisionAudit =
    DecisionAudit(
      hybridBankApproval = Some(credit.approved),
      hybridBankApprovalProbability = credit.approvalProbability,
      hybridBankApprovalRoll = credit.approvalRoll,
      hybridBankApprovalAudit = credit.audit,
    )

  def decisionCreditNeed(decision: Decision): PLN =
    decision match
      case Decision.Upgrade(_, _, _, loan, _, _)    => loan
      case Decision.UpgradeFailed(_, _, _, loan, _) => loan
      case _                                        => PLN.Zero

  def applyTechCreditDiagnostics(result: Result, decision: DecisionWithAudit): Result =
    val selectedDemand    =
      decision.audit.techCreditSource match
        case Some(DecisionTrace.TechCreditSource.BankRejectedCandidate) => PLN.Zero
        case _                                                          => decisionCreditNeed(decision.decision).max(result.techNewLoan)
    val selectedApproved  = result.techNewLoan.min(selectedDemand)
    val selectedRejected  = (selectedDemand - selectedApproved).max(PLN.Zero)
    val candidateRejected = decision.audit.techCandidateCreditRejected
    val demand            = selectedDemand + candidateRejected
    val approved          = selectedApproved
    val rejected          = selectedRejected + candidateRejected
    result.copy(
      techCreditDemand = demand,
      techCreditApproved = approved,
      techCreditRejected = rejected,
      techSelectedCreditDemand = selectedDemand,
      techSelectedCreditApproved = selectedApproved,
      techSelectedCreditRejected = selectedRejected,
      techCandidateCreditDemand = decision.audit.techCandidateCreditDemand,
      techCandidateCreditApproved = decision.audit.techCandidateCreditApproved,
      techCandidateCreditRejected = candidateRejected,
      techCreditRejectionBreakdown = CreditRejectionBreakdown.from(decision.audit.selectedBankApprovalAudit.rejectionReason, selectedRejected) +
        decision.audit.techCandidateCreditRejectionBreakdown,
    )
